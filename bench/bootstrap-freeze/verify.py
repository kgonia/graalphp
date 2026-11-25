"""Host driver for the five-plugin bootstrap freeze experiment. Python 3.10+, standard library."""
import argparse
import base64

import concurrent.futures
import datetime as dt
import difflib
import hashlib
import http.client
import json
import math
import os
from pathlib import Path
import re
import shutil
import statistics
import subprocess
import sys
import threading
import time
import traceback
from collections import Counter
from html.parser import HTMLParser
from urllib.parse import parse_qs, urlsplit

from uuid import uuid4

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
IMAGE = "gate1-truffle:8.5-graal25.4"
ROUTES = {"home": "/", "post": "/?p=1", "rest": "/?rest_route=/wp/v2/posts"}
PHASES = ("baseline", "frozen", "rc-baseline", "rc-frozen", "heap-baseline", "heap-frozen")
LOG_ERROR = re.compile(r"PHP (?:Fatal error|Warning|Notice|Parse error|Deprecated):.*|"
                       r"(?:request failed:|Exception in thread|(?:Caused by: )?[\w.$]+(?:Exception|Error):).*")
BODY_ERROR = re.compile(rb"(?:^|\n)\s*(?:<br\s*/?>\s*)?(?:PHP\s+)?(?:<b>)?"
                        rb"(?:Fatal error|Parse error)(?:</b>)?:\s", re.I)


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def execute(argv, log=None, **kwargs):
    """Use argv boundaries directly, including for paths on the Windows host."""
    if log is None:
        return subprocess.check_output(argv, text=True, encoding="utf-8", errors="replace", **kwargs).strip()
    with log.open("wb") as dest:
        subprocess.run(argv, stdout=dest, stderr=subprocess.STDOUT, check=True, **kwargs)


def ms_stats(v):
    ordered = sorted(v)
    if not ordered:
        raise ValueError("Missing timing samples")
    return {"n": len(v), "median": statistics.median(v), "p95": ordered[math.ceil(.95 * len(v)) - 1]}


class Html(HTMLParser):
    """Compare parsed tags, all attributes, comments, declarations, text and script contents."""
    def __init__(self):
        super().__init__(convert_charrefs=False)
        self.events = []
        self.body_classes = set()
        self.has_title = False

    def handle_starttag(self, tag, attrs):
        self.events.append(("open", tag, sorted(attrs, key=lambda x: x[0])))
        if tag == "body":
            self.body_classes.update((dict(attrs).get("class") or "").split())
        if tag == "title":
            self.has_title = True

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)
        self.events.append(("empty", tag))

    def handle_endtag(self, tag): self.events.append(("close", tag))
    def handle_data(self, data): self.events.append(("text", data))
    def handle_comment(self, data): self.events.append(("comment", data))
    def handle_decl(self, data): self.events.append(("decl", data))
    def handle_entityref(self, data): self.events.append(("entity", data))
    def handle_charref(self, data): self.events.append(("charref", data))
    def handle_pi(self, data): self.events.append(("pi", data))


# WordPress emits time() at script registration.
# Check it against the actual request's clock before comparing the surrounding bytes.
SCRIPT_TIME = re.compile(rb'(\bvar userSettings\s*=\s*\{[^}\n]*?"time"\s*:\s*")(\d+)(")')


def timed_fields(body, started, ended):
    values = [int(m[2]) for m in SCRIPT_TIME.finditer(body)]
    if any(t < math.floor(started) - 1 or t > math.ceil(ended) + 1 for t in values):
        raise ValueError(f"Stale WP_Scripts userSettings.time {values}")
    return SCRIPT_TIME.sub(rb'\g<1>{request time}\g<3>', body), values


# WordPress human_time_diff() selects the unit before applying PHP's half-up rounding.
WP_AGE_UNITS = ((365 * 86400, "year"), (30 * 86400, "month"), (7 * 86400, "week"),
                (86400, "day"), (3600, "hour"), (60, "minute"), (1, "second"))


def wp_time_ago(seconds):
    for scale, unit in WP_AGE_UNITS:
        if seconds >= scale or scale == 1:
            count = max(1, (seconds + scale // 2) // scale)
            return f"{count} {unit}{'s' if count != 1 else ''} ago"


class RelativeTimes(HTMLParser):
    """Locate direct text in real time elements, retaining offsets into the raw response."""
    CDATA_CONTENT_ELEMENTS = HTMLParser.CDATA_CONTENT_ELEMENTS + ("title", "textarea")
    LABEL = re.compile(rb"[0-9]+ [A-Za-z]+ ago(?=</(?i:time)\s*>)")

    def __init__(self, body):
        super().__init__(convert_charrefs=False)
        self.body = body
        self.line_starts = [0, *(m.end() for m in re.finditer(b"\n", body))]
        self.labels = []

    def handle_starttag(self, tag, attrs):
        if tag != "time":
            return
        line, col = self.getpos()
        start = self.line_starts[line - 1] + col + len(self.get_starttag_text())
        match = self.LABEL.match(self.body, start)
        if match:
            dates = [value for name, value in attrs if name == "datetime"]
            if not dates:
                return
            if len(dates) != 1 or not dates[0]:
                raise ValueError("Relative time needs a single datetime attribute")
            self.labels.append((start, match.end(), dates[0], match[0].decode("ascii")))

    def handle_startendtag(self, tag, attrs):
        pass


def relative_time_fields(page, body, started, ended):
    if page == "rest" or b" ago<" not in body:
        return body, []
    parser = RelativeTimes(body)
    # One character per byte keeps HTMLParser's columns aligned with the original UTF-8 bytes.
    parser.feed(body.decode("latin-1"))
    parser.close()
    parts, observed = [], []
    previous = 0
    for start, end, iso, label in parser.labels:
        instant = dt.datetime.fromisoformat(iso[:-1] + "+00:00" if iso.endswith("Z") else iso)
        if instant.tzinfo is None:
            raise ValueError(f"Relative time needs a UTC offset: {iso}")
        stamp = instant.timestamp()
        first = max(0, math.floor(math.floor(started) - stamp))
        last = math.floor(math.floor(ended) - stamp)
        if not any(wp_time_ago(age) == label for age in range(first, last + 1)):
            raise ValueError(f"Relative time {label!r} for {iso} disagrees with request [{started}, {ended}]")
        observed.append({"datetime": iso, "label": label})
        parts.extend((body[previous:start], b"{human_time_diff}"))
        previous = end
    parts.append(body[previous:])
    return b"".join(parts), observed


AKISMET = re.compile(rb'(<input type="hidden" id="ak_js_1" name="ak_js" value=")([0-9]+)("/>)')
AKISMET_ID = re.compile(rb'''<input\b[^>]*\bid\s*=\s*["']ak_js_1["']''', re.I)
INSTRUMENT_HEADERS = {"date", "x-freeze-mode", "x-freeze-boot-end-ns", "x-freeze-eval-start-ns",
                      "x-freeze-restore-ns", "x-freeze-total-ns", "x-freeze-check", "x-freeze-arrival-micros"}


def akismet_field(page, body):
    if page != "post":
        return body, None
    matches = list(AKISMET.finditer(body))
    if len(matches) != 1 or len(AKISMET_ID.findall(body)) != 1:
        raise ValueError("Expected exactly one Akismet ak_js_1 hidden input")
    m = matches[0]
    n = int(m[2])
    if not 0 <= n <= 250 or str(n).encode() != m[2]:
        raise ValueError(f"Invalid Akismet mt_rand field {m[2]!r}")
    return body[:m.start(2)] + b"{ak_js_1}" + body[m.end(2):], n


def one(headers, key, default=None):
    values = [v for k, v in headers if k == key]
    if len(values) > 1:
        raise ValueError(f"Repeated singleton header {key}: {values}")
    return values[0] if values else default


def comparable_headers(headers, size, normalized_size):
    result = {}
    for name, value in headers:
        if name in INSTRUMENT_HEADERS:
            continue
        if name == "content-length":
            if not value.isdecimal() or int(value) != size:
                raise ValueError(f"Content-Length {value} disagrees with {size} body bytes")
            # The validated variable-width integer changes the wire length. Compare the
            # exact length after the same substitutions that were applied to its body.
            value = str(normalized_size)
        result.setdefault(name, []).append(value)
    return result


def unique_object(pairs):
    obj = {}
    for key, value in pairs:
        if key in obj:
            raise ValueError(f"Duplicate JSON key {key!r}")
        obj[key] = value
    return obj


def structure(page, data, content_type):
    text = data.decode("utf-8")
    if page == "rest":
        if content_type != "application/json":
            raise ValueError(f"REST content type {content_type}")
        posts = json.loads(text, object_pairs_hook=unique_object)
        if not isinstance(posts, list) or not posts or not all(
                isinstance(p, dict) and isinstance(p.get("id"), int) and p.get("type") == "post"
                and isinstance(p.get("title", {}).get("rendered"), str) for p in posts):
            raise ValueError("Expected a nonempty WordPress posts collection")
        return json.dumps(posts, sort_keys=True, separators=(",", ":"), allow_nan=False)
    h = Html()
    h.feed(text)
    h.close()
    has_kind = ("postid-1" in h.body_classes if page == "post"
                else bool({"home", "blog"} & h.body_classes))
    if content_type != "text/html" or not h.has_title or not has_kind:
        raise ValueError(f"Unexpected {page} document, body classes {sorted(h.body_classes)}")
    return h.events


def runtime_mode(content):
    match = re.search(r"^freeze runtime_mode=(fallback-interpreter|interpreter|compiled) runtime=(.+)$", content, re.M)
    if not match:
        raise ValueError("Missing Truffle runtime mode marker")
    if "uses a fallback runtime" in content and match[1] != "fallback-interpreter":
        raise ValueError(f"Truffle mode disagrees with the engine warning: {match.group()}")
    return {"mode": match[1], "runtime": match[2]}


def verify_gc(text):
    header = "## live heap (after a full GC)"
    histogram = "## class histogram (live objects)"
    if (text.count(header) != 1 or text.count(histogram) != 1 or text.index(header) >= text.index(histogram)
            or "## live heap (no GC" in text):
        raise ValueError("memsnap did not run its full-GC branch")
    section = text.split(header, 1)[1].split(histogram, 1)[0]
    match = re.search(r"used (\d+)K \[0x[0-9a-f]+, 0x[0-9a-f]+\)", section)
    if not match or "Command executed successfully" not in section[:match.start()]:
        raise ValueError("Missing successful jcmd GC.run or subsequent GC.heap_info")
    return int(match[1]) / 1024


def audit_log(text, frozen):
    scope = None
    groups = []
    all_sites = set()
    raw_sites = set()
    restored = []
    ready = False
    for line in text.splitlines():
        if line.startswith("listening on "):
            ready = True
        elif line.startswith("freeze-rc-begin "):
            m = re.fullmatch(r"freeze-rc-begin phase=(capture|full|restored) route=(\S+) snapshot_max_obj_id=(-?\d+)", line)
            if not m or scope is not None:
                raise ValueError(f"Unexpected overlapping audit scopes: {line}")
            kind, route, cutoff = m.groups()
            if kind != "capture" and not ready:
                raise ValueError("Request audit preceded server readiness")
            scope = {"phase": kind, "route": route, "snapshot_max_obj_id": int(cutoff),
                     "after_listening": ready, "hits": 0, "sites": set()}
            groups.append(scope)
        elif line == "freeze-rc-end":
            if scope is None:
                raise ValueError("Unmatched audit end")
            scope = None
        elif line.startswith("freeze-rc-hit\t"):
            if scope is None:
                raise ValueError("Unattributed D17 occurrence")
            _, oid, cls, stack = line.split("\t", 3)
            raw_sites.add((cls, stack))
            # Use the same complete class and stack that Census uses as its site key.
            site = cls + " at " + stack
            scope["hits"] += 1
            scope["sites"].add(site)
            all_sites.add(site)
            if scope["phase"] == "restored" and int(oid) <= scope["snapshot_max_obj_id"]:
                restored.append({"id": int(oid), "route": scope["route"], "site": site})
    totals = re.findall(r"^rc-verify: (\d+) of (\d+) zeros checked, (\d+) still reachable at (\d+) sites$", text, re.M)
    if scope is not None or len(totals) != 1:
        raise ValueError("Incomplete D17 scope or shutdown summary")
    checked, zeros, hits, sites = map(int, totals[0])
    if not checked or checked != zeros or hits != sum(g["hits"] for g in groups) or sites != len(raw_sites):
        raise ValueError("D17 event stream does not reconcile to its shutdown counters")
    captures = [g for g in groups if g["phase"] == "capture"]
    requests = [g for g in groups if g["phase"] != "capture"]
    if (frozen and not captures or not frozen and captures
            or [g["route"] for g in requests] != list(ROUTES.values())
            or any(g["phase"] != ("restored" if frozen else "full") for g in requests)
            or any(g["snapshot_max_obj_id"] < 1 for g in requests if frozen)):
        raise ValueError("D17 requests or snapshot cutoffs do not match the planned phase")
    if frozen:
        captured_ids = list(map(int, re.findall(r"^freeze build snapshot_max_obj_id=(\d+)\b", text, re.M)))
        if len(captured_ids) != len(captures):
            raise ValueError("Missing per-template D17 object-ID boundary")
        latest = {}
        for g in groups:
            if g["phase"] == "capture":
                latest[g["route"]] = captured_ids.pop(0)
            else:
                variant = "rest" if g["route"] == ROUTES["rest"] else "frontend"
                if latest.get(variant) != g["snapshot_max_obj_id"]:
                    raise ValueError(f"D17 cutoff disagrees with the captured {variant} template")
    for g in groups:
        g["sites"] = sorted(g["sites"])
    return {"checked": checked, "zeros": zeros, "capture_hits": sum(g["hits"] for g in captures),
            "request_hits": sum(g["hits"] for g in requests), "restored_hits": restored,
            "sites": sorted(all_sites), "request_sites": sorted(set().union(*(g["sites"] for g in requests))),
            "scopes": groups}


class Run:
    def __init__(self, args, directory):
        self.args, self.directory = args, directory
        self.lock = threading.Lock()
        self.refs = {}
        self.parity_diagnostics = {}
        self.seen_probes = {k: set() for k in ('connection', 'placeholder_hash', 'guest_id')}
        self.rows = []
        self.errors = []
        self.container = "bootstrap-freeze-" + uuid4().hex[:12]
        self.phase = None
        self.summary = {"container": self.container, "cpus": "14-21", "routes": ROUTES,
                        "warm_rounds": args.warm_rounds, "timing_rounds": args.timing_rounds,
                        "heap_warm_rounds": args.heap_warm_rounds, "phases": {}, "errors": self.errors}

    @property
    def folder(self):
        return self.directory / self.phase

    def fail(self, message):
        with self.lock:
            self.errors.append(f"{self.phase or 'setup'}: {message}")

    def fetch(self, path, method="GET", extra=None, timeout=None, payload=None):
        client = http.client.HTTPConnection("127.0.0.1", self.args.port, timeout=timeout or 3600)
        try:
            start = time.perf_counter_ns()
            client.request(method, path, body=payload, headers={"Host": "localhost", "X-Bootshare-Db": "plugins", **(extra or {})})
            response = client.getresponse()
            data = response.read()
            elapsed = (time.perf_counter_ns() - start) / 1e6
            return response.status, [(k.lower(), v) for k, v in response.getheaders()], data, elapsed
        finally:
            client.close()

    def check_probe(self, headers, page, marker):
        probe = json.loads(base64.b64decode(one(headers, "x-freeze-check"), validate=True))
        arrival = int(one(headers, "x-freeze-arrival-micros")) / 1e6
        wanted = ROUTES[page]
        post_id = "1" if page == "post" else None
        cf7 = urlsplit(probe["cf7"])
        if not (probe["uri"] == wanted and cf7.path == "/" and parse_qs(cf7.query) == parse_qs(urlsplit(wanted).query)
                and probe["marker"] == marker and probe["cookie"] == [] and probe["filter_cookie"] is None
                and probe["filter_post_id"] == probe["get_post_id"] == probe["request_post_id"] == post_id
                and probe["result_cleared"] is True
                and abs(probe["wp_start"] - arrival) <= 0.00001 and probe["wp_start"] <= probe["request_time"] + 0.00001
                and probe["wp_start"] == probe["timestart"]):
            raise ValueError(f"Request-local PHP state mismatch: {probe}")
        for key, value in dict(probe["localized"]).items():
            if key in ("utils_time", "store_time"):
                if not int(arrival) <= value <= probe["checked_at"]:
                    raise ValueError(f"Stale localized {key} on {page}")
            elif value is not True:
                raise ValueError(f"Invalid localized {key} on {page}")
        guest = probe["guest"]
        if page != "rest" or guest is not None:
            if not guest or not re.fullmatch(r"t_[a-f0-9]{30}", guest["id"]):
                raise ValueError(f"Missing or malformed guest session on {page}")
            for key, ttl in (("expiring", 86400), ("expiration", 172800)):
                if not int(arrival) <= guest[key] - ttl <= probe["checked_at"]:
                    raise ValueError(f"Stale guest {key} on {page}: {guest[key]}")
        with self.lock:
            for key, seen in self.seen_probes.items():
                if key == "guest_id" and guest is None:
                    continue
                value = guest["id"] if key == "guest_id" else probe[key]
                if not value or value in seen:
                    raise ValueError(f"Reused {key} in {page}")
                seen.add(value)
        return probe

    def request(self, page, group, worker=0):
        row = {"phase": self.phase, "group": group, "worker": worker, "page": page}
        try:
            log_path = self.folder / "server.log"
            offset = log_path.stat().st_size if group == "parity" else None

            marker = uuid4().hex if group in ("isolation", "stress") else None
            extra = {"X-Freeze-Verify": marker} if marker else None
            started_at = time.time()

            status, headers, body, elapsed = self.fetch(ROUTES[page], extra=extra)
            ended_at = time.time()
            row.update(request_started_at=started_at, request_ended_at=ended_at)
            if offset is not None:
                with log_path.open("rb") as log:
                    log.seek(offset)
                    per_request = log.read().decode("utf-8", "replace")
                (self.folder / f"{page}-parity.log").write_text(per_request, encoding="utf-8")
                diagnostics = Counter(m.group() for m in LOG_ERROR.finditer(per_request))
                if self.phase == "baseline":
                    self.parity_diagnostics[page] = diagnostics
                else:
                    excess = diagnostics - self.parity_diagnostics.get(page, Counter())
                    if excess:
                        self.fail(f"Added diagnostics on {page}: {dict(excess)}")

            digest = hashlib.sha256(body).hexdigest()
            mime = one(headers, "content-type", "").split(";")[0].lower()
            mode = one(headers, "x-freeze-mode")
            frozen = self.phase not in ("baseline", "rc-baseline", "heap-baseline")
            expected = ("rest" if page == "rest" else "frontend") if frozen else "full"
            row.update(status=status, client_ms=elapsed, sha256=digest, size=len(body), mode=mode, headers=headers)
            raw = self.directory / "bodies" / (digest + (".json" if page == "rest" else ".html"))
            with self.lock:
                if not raw.exists():
                    raw.write_bytes(body)
            if status != 200 or mode != expected:
                raise ValueError(f"status={status}, mode={mode}, expected={expected}")
            row["server_ms"] = int(one(headers, "x-freeze-total-ns")) / 1e6
            if frozen:
                row["barrier_ms"] = int(one(headers, "x-freeze-restore-ns")) / 1e6
            else:
                row["barrier_ms"] = (int(one(headers, "x-freeze-boot-end-ns")) -
                                     int(one(headers, "x-freeze-eval-start-ns"))) / 1e6
            if row["barrier_ms"] <= 0 or row["barrier_ms"] > row["server_ms"]:
                raise ValueError("Invalid bootstrap or restore interval")
            compared, row["script_times"] = timed_fields(body, started_at, ended_at)
            compared, row["relative_times"] = relative_time_fields(page, compared, started_at, ended_at)

            compared, row["ak_js_1"] = akismet_field(page, compared)
            comparable = comparable_headers(headers, len(body), len(compared))
            norm_digest = hashlib.sha256(compared).hexdigest()
            row["normalized_sha256"] = norm_digest
            if marker:
                row["probe"] = self.check_probe(headers, page, marker)

            if page not in self.refs:
                if self.phase != "baseline":
                    raise ValueError("No validated baseline for this page")
                model = structure(page, compared, mime)
                if BODY_ERROR.search(body):
                    raise ValueError("PHP diagnostic in baseline response")
                self.refs[page] = (digest, norm_digest, raw, comparable, model)
                row["match"] = "reference"
            else:
                canonical, reference_norm, origin, expected_headers, model = self.refs[page]
                if comparable != expected_headers:
                    write_json(self.folder / f"{page}-{digest}-{uuid4().hex[:8]}-headers.json",
                               {"baseline": expected_headers, "received": comparable, "wire": headers})
                    raise ValueError(f"Application headers differ on {page}")
                if digest == canonical:
                    row["match"] = "bytes"
                else:
                    diff = self.folder / f"{page}-{digest}.diff"
                    with self.lock:
                        if not diff.exists():
                            a, b = (p.read_bytes().decode("utf-8", "backslashreplace").splitlines(keepends=True)
                                    for p in (origin, raw))
                            diff.write_text("".join(difflib.unified_diff(a, b, fromfile=origin.name, tofile=raw.name)),
                                            encoding="utf-8")
                    row["diff"] = diff.name
                    if norm_digest == reference_norm:
                        row["match"] = "normalized bytes"
                    elif structure(page, compared, mime) == model:
                        row["match"] = "structure"
                    else:
                        raise ValueError(f"Content differs from baseline, see {diff.name}")
        except Exception as exc:
            row["error"] = str(exc)
            self.fail(f"{group} {page}: {exc}")
        with self.lock:
            self.rows.append(row)
            with (self.folder / "requests.jsonl").open("a", encoding="utf-8") as out:
                out.write(json.dumps(row) + "\n")
        return row

    def rounds(self, group, rounds):
        for _ in range(rounds):
            for page in ROUTES:
                row = self.request(page, group)
                if "error" in row and group == "warmup":
                    raise RuntimeError(f"Warm-up failed: {row['error']}")

    def clients(self, count, seconds, group):
        ready = threading.Barrier(count)
        pages = list(ROUTES)

        def visit(worker):
            ready.wait(timeout=30)
            end = time.monotonic() + seconds
            n = 0
            while time.monotonic() < end:
                self.request(pages[(worker + n) % len(pages)], group, worker)
                n += 1
            return n

        with concurrent.futures.ThreadPoolExecutor(count) as pool:
            visits = list(pool.map(visit, range(count)))
        if not all(visits):
            self.fail(f"{group}: an idle client in {visits}")
        return visits

    def fallback(self):
        cases = [
            ("HEAD", "/", {}, "frontend"),
            ("GET", "/", {"Cookie": "wordpress_logged_in_freeze=invalid"}, "full"),
            ("GET", "/", {"Cookie": "wp_woocommerce_session_freeze=invalid"}, "full"),
            ("POST", "/", {}, "full"), ("HEAD", "/wp-login.php", {}, "full"),
            ("HEAD", "/wp-admin/", {}, "full"), ("HEAD", "/", {"X-Bootshare-Db": "core"}, "full"),
        ]
        evidence = []
        cases.extend([
            ("GET", "/", {"Cookie": "wordpress_deadbeef=x"}, "full"),
            ("GET", "/", {"Cookie": "wordpress_rec_deadbeef=x"}, "full"),
            ("GET", "/", {"Cookie": "comment_author_deadbeef=x"}, "full"),
            ("GET", "/", {"Cookie": "unrelated=x"}, "full"),
            ("GET", "/", {"User-Agent": "Mozilla/5.0 Firefox/100"}, "full"),
            ("GET", "/robots.txt", {}, "full"), ("GET", "/?REST_ROUTE=%2Fwp%2Fv2%2Fposts", {}, "full"),
            ("GET", "/?rest_route=%2fwp%2Fv2%2fposts", {}, "rest"),
            ("GET", "/?unknown=1", {}, "full"),
        ])
        with_body = ("GET", "/", {"Content-Type": "application/x-www-form-urlencoded"}, "full")
        for case in [*cases, with_body]:
            method, path, headers, expected = case
            payload = b"p=1" if case is with_body else None
            status, received, body, _ = self.fetch(path, method, headers, payload=payload)
            mode = one(received, "x-freeze-mode")
            evidence.append({"method": method, "path": path, "headers": headers, "status": status,
                             "payload_bytes": len(payload or b""), "expected": expected, "actual": mode, "bytes": len(body)})
            if (mode != expected or status >= 500 or method == "HEAD" and body
                    or expected != "full" and status != 200):
                self.fail(f"Eligibility failed: {evidence[-1]}")
        write_json(self.folder / "eligibility.json", evidence)

    def start(self):
        (self.directory / "phase").write_bytes(self.phase.encode("ascii"))
        execute(["docker", "start", self.container])
        end = time.monotonic() + 7200
        while time.monotonic() < end:
            try:
                if self.fetch("/wp-includes/css/dashicons.css", timeout=2)[0] == 200:
                    return
            except OSError:
                pass
            if execute(["docker", "inspect", "-f", "{{.State.Running}}", self.container]) != "true":
                raise RuntimeError(f"Server exited, see {self.folder / 'server.log'}")
            time.sleep(1)
        raise TimeoutError("Server startup exceeded two hours")

    def memory(self, info):
        self.rounds("warmup", self.args.heap_warm_rounds)
        time.sleep(15)
        git_bash = Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Git/bin/bash.exe"
        bash = str(git_bash) if git_bash.is_file() else shutil.which("bash")
        if not bash:
            raise RuntimeError("Git Bash is needed to run tests/gate2/memsnap.sh")
        env = os.environ.copy()
        for name in ("NOGC", "NATIVE"):
            env.pop(name, None)
        # Preserve the upstream probe and expose its otherwise discarded GC.run acknowledgement.
        script = (ROOT / "tests/gate2/memsnap.sh").read_text(encoding="utf-8")
        command = "jcmd 1 GC.run > /dev/null"
        entry = "sh -c '\n"
        if script.count(command) != 1 or script.count(entry) != 1:
            raise ValueError("Unexpected memsnap GC command or container entry")
        script = script.replace(entry, entry + "  unset NOGC NATIVE\n")
        observed = self.folder / "memsnap.sh"
        observed.write_bytes(script.replace(command, "jcmd 1 GC.run || exit 1").encode())
        execute([bash, observed.relative_to(ROOT).as_posix(), self.container], self.folder / "idle.txt", cwd=ROOT, env=env)
        info["idle_heap_MiB"] = verify_gc((self.folder / "idle.txt").read_text(encoding="utf-8", errors="replace"))
        info["full_gc_confirmed"] = True
        execute([sys.executable, "-B", str(ROOT / "tests/gate2/memsplit.py"), str(self.folder / "idle.txt"),
                 "--json"], self.folder / "memsplit.json")
        info["idle"] = json.loads((self.folder / "memsplit.json").read_text())
        info["heap"] = {}
        log = self.folder / "server.log"
        for count in (1, 4, 8):
            start = log.stat().st_size
            time.sleep(15)
            visits = self.clients(count, 60, f"heap-{count}")
            time.sleep(3)
            with log.open("rb") as stream:
                stream.seek(start)
                segment = stream.read()
            part = self.folder / f"heap-{count}.log"
            part.write_bytes(segment)
            execute([sys.executable, "-B", str(ROOT / "tests/gate2/heapprobe.py"), str(part)],
                    self.folder / f"heapprobe-{count}.txt")
            probes = [json.loads(line[5:]) for line in segment.splitlines() if line.startswith(b"heap {")]
            idle = [p["live"] for p in probes if p["in_flight_max"] == 0]
            full = [p["live"] for p in probes if p["in_flight_max"] == p["in_flight"] == count]
            if not idle or not full:
                raise ValueError(f"Missing GC samples for {count} clients: idle={len(idle)}, busy={len(full)}")
            info["heap"][str(count)] = {"visits": visits, "idle_samples": len(idle), "busy_samples": len(full),
                                        "MiB_per_request": (statistics.median(full) - statistics.median(idle))
                                                           / count / 2**20}
        peak = execute(["docker", "exec", self.container, "cat", "/sys/fs/cgroup/memory.peak"])
        (self.folder / "memory.peak").write_text(peak + "\n")
        info["cgroup_peak_MiB"] = int(peak) / 2**20

    def inspect_log(self, info):
        content = (self.folder / "server.log").read_text(encoding="utf-8", errors="replace")
        info["execution"] = runtime_mode(content)
        info["snapshots"] = [s for s in content.splitlines()
                             if s.startswith(("freeze build ", "freeze ready ", "freeze option_reads "))]
        info["diagnostics"] = dict(Counter(m.group() for m in LOG_ERROR.finditer(content)))
        for msg in info["diagnostics"]:
            if msg.startswith(("PHP Warning:", "PHP Notice:", "PHP Deprecated:")):
                if msg in self.summary["phases"]["baseline"].get("diagnostics", {}):
                    continue
            self.fail(f"Server diagnostic: {msg}")
        if self.phase in ("rc-baseline", "rc-frozen"):
            rc = info["rc"] = audit_log(content, self.phase == "rc-frozen")
            if self.phase == "rc-frozen":
                baseline = self.summary["phases"]["rc-baseline"]
                if "rc" not in baseline:
                    raise ValueError("Missing matched baseline D17 event stream")
                rc["new_sites"] = sorted(set(rc["sites"]) - set(baseline["rc"]["request_sites"]))
                if rc["restored_hits"] or rc["new_sites"]:
                    self.fail(f"D17 found {len(rc['restored_hits'])} restored-object violations and "
                              f"{len(rc['new_sites'])} new sites")
            write_json(self.folder / "d17.json", rc)

    def phase_run(self, phase):
        self.phase = phase
        self.folder.mkdir()
        print(f"Starting {phase}. Evidence: {self.folder}", flush=True)
        info = self.summary["phases"][phase] = {}
        first = len(self.errors)
        try:
            self.start()
            if phase.startswith("heap-"):
                self.memory(info)
            elif phase.startswith("rc-"):
                self.rounds("rc", 1)
            else:
                self.rounds("warmup", self.args.warm_rounds)
                self.rounds("parity", 1)
                self.rounds("timing", self.args.timing_rounds)
                if phase == "frozen":
                    for i in range(20):
                        self.request(tuple(ROUTES)[i % 3], "isolation")
                    info["stress_visits"] = self.clients(8, 30, "stress")
                    self.fallback()
        except Exception:
            self.fail(traceback.format_exc())
        finally:
            # The PID 1 JVM's shutdown hook emits the D17 summary after the last completed request.
            try:
                execute(["docker", "stop", "--time", "60", self.container])
                self.inspect_log(info)
            except Exception:
                self.fail(traceback.format_exc())
            subset = [r for r in self.rows if r["phase"] == phase]
            info["requests"] = dict(Counter(r["group"] for r in subset))
            info["status"] = dict(Counter(str(r.get("status", "exception")) for r in subset))
            info["akismet_values"] = sorted({r["ak_js_1"] for r in subset if r.get("ak_js_1") is not None})
            if phase == "frozen" and len(info["akismet_values"]) < 2:
                self.fail("Akismet hidden input did not vary across frozen post requests")
            info["matches"] = dict(Counter(r.get("match", "failed") for r in subset))
            if phase in ("baseline", "frozen"):
                info["timing"] = {}
                for page in ROUTES:
                    visits = [r for r in subset if r["group"] == "timing" and r["page"] == page and "error" not in r]
                    if len(visits) != self.args.timing_rounds:
                        self.fail(f"{page}: {len(visits)} valid timing samples, expected {self.args.timing_rounds}")
                    if visits:
                        info["timing"][page] = {k: ms_stats([r[k] for r in visits])
                                                for k in ("barrier_ms", "server_ms", "client_ms")}
            info["passed"] = len(self.errors) == first
            print(f"{phase}: {'passed' if info['passed'] else 'failed'}; "
                  f"requests={info['requests']}, status={info['status']}", flush=True)
            if not info["passed"] and (self.folder / "server.log").is_file():
                print("\n".join((self.folder / "server.log").read_text(
                    encoding="utf-8", errors="replace").splitlines()[-18:]), file=sys.stderr)

            self.report()

    def report(self):
        write_json(self.directory / "summary.json", self.summary)
        rel = self.directory.relative_to(HERE).as_posix()
        phases = self.summary["phases"]
        state = ("FAILED" if self.errors else "PASSED" if len(phases) == len(PHASES) else "INCOMPLETE")
        lines = ["# Bootstrap freeze results", "", f"**{state}** on CPUs 14–21. One container, one new JVM per phase.",
                 "", f"[Machine-readable results and configuration]({rel}/summary.json). "
                 f"[Raw response bodies keyed by SHA-256]({rel}/bodies). Times are milliseconds. "
                 "Every distinct body is retained. Each changed body has a unified diff. "
                 "The verifier records userSettings.time, the post ak_js_1 integer (0 through 250), and each "
                 "relative time label. It checks each label against its datetime and the request interval "
                 "before normalizing the text. JSON objects must have unique keys. "
                 "It compares application header values in occurrence order and validates Content-Length against "
                 "the raw body before accounting for variable-width fields.",
                 "", "| Phase | Execution mode | Outcome | Requests by group | HTTP status counts | Body checks |",
                 "|---|---|---|---|---|---|"]
        for phase, i in phases.items():
            mode = i.get('execution', {}).get('mode', 'unverified')
            lines.append(f"| [{phase}]({rel}/{phase}/server.log) | {mode} | {'pass' if i.get('passed') else 'fail'} "
                         f"| {i.get('requests', {})} | {i.get('status', {})} | {i.get('matches', {})} |")
        lines += ["", "| Route | Full bootstrap median / p95 | Restore median / p95 | "
                  "Full HTTP median / p95 | Frozen HTTP median / p95 | Median HTTP speedup |",
                  "|---|---:|---:|---:|---:|---:|"]
        for page in ROUTES:
            a = phases.get("baseline", {}).get("timing", {}).get(page)
            b = phases.get("frozen", {}).get("timing", {}).get(page)
            def cell(stats, key):
                return "pending" if not stats else f"{stats[key]['median']:.3f} / {stats[key]['p95']:.3f}"
            speed = f"{a['client_ms']['median'] / b['client_ms']['median']:.2f}×" if a and b else "pending"
            lines.append(f"| {page} | {cell(a, 'barrier_ms')} | {cell(b, 'barrier_ms')} | "
                         f"{cell(a, 'client_ms')} | {cell(b, 'client_ms')} | {speed} |")
        lines += ["", "| Heap phase | Idle after GC (MiB) | 1 client (MiB/req) | 4 clients | 8 clients | "
                  "Cgroup peak (MiB) |", "|---|---:|---:|---:|---:|---:|"]
        for phase in ("heap-baseline", "heap-frozen"):
            i = phases.get(phase, {})
            values = [i.get("idle_heap_MiB")] + [
                i.get("heap", {}).get(str(n), {}).get("MiB_per_request") for n in (1, 4, 8)
            ] + [i.get("cgroup_peak_MiB")]
            lines.append("| " + phase + " | " + " | ".join("pending" if v is None else f"{v:.2f}" for v in values) + " |")
        lines += ["", "Each heap segment runs for 60 seconds. The per-request calculation uses full-GC "
                  "samples with exactly the stated concurrency and the idle median from that segment. "
                  "The two memory phases exercise the same three anonymous pages.",
                  "", f"Eight-client completions by worker: {phases.get('frozen', {}).get('stress_visits', 'pending')}.",
                  "", f"Distinct frozen post Akismet values observed: {len(phases.get('frozen', {}).get('akismet_values', []))}.",
                  "", "## Refcount verification", "",
                  "Each audit checks every zero. The per-phase evidence records every capture and request, "
                  "including the object-ID cutoff for that request's snapshot.",
                  "", f"Counted-holder audit enabled in both JVMs: {self.summary.get('rc_audit', False)}.",
                  "", "| Phase | Zeros checked | Capture hits | Request hits | Restored-object hits | New sites |",
                  "|---|---:|---:|---:|---:|---:|"]
        for phase in ("rc-baseline", "rc-frozen"):
            rc = phases.get(phase, {}).get("rc", {})
            restored = len(rc["restored_hits"]) if rc else "pending"
            new = len(rc["new_sites"]) if "new_sites" in rc else ("reference" if rc else "pending")
            lines.append(f"| {phase} | {rc.get('checked', 'pending')} | {rc.get('capture_hits', 'pending')} | "
                         f"{rc.get('request_hits', 'pending')} | {restored} | {new} |")
        lines += ["", "## Evidence", ""]
        for phase, i in phases.items():
            lines.append(f"- [{phase} requests]({rel}/{phase}/requests.jsonl), "
                         f"[server diagnostics and snapshot build statistics]({rel}/{phase}/server.log)")
            if "rc" in i:
                lines.append(f"  - [D17 attribution by request and capture]({rel}/{phase}/d17.json)")
            for line in i.get("snapshots", []):
                lines.append(f"  - {line}")
            if "idle" in i:
                lines.append(f"  - GC.run confirmed: {i.get('full_gc_confirmed', False)}. [Full-GC memory snapshot]({rel}/{phase}/idle.txt), "
                             f"[memory split]({rel}/{phase}/memsplit.json)")
                for n in (1, 4, 8):
                    lines.append(f"  - [{n}-client probe analysis]({rel}/{phase}/heapprobe-{n}.txt)")
        if self.errors:
            lines += ["", f"{len(self.errors)} failures. The summary records every error.", "",
                      "~~~~text", *self.errors[:12], "~~~~"]
        result = "\n".join(lines) + "\n"
        (self.directory / "RESULTS.md").write_text(result.replace(rel + "/", "./"), encoding="utf-8")
        (HERE / "RESULTS.md").write_text(result, encoding="utf-8")

    def run(self):
        args, out = self.args, self.directory
        self.summary["rc_audit"] = args.exact_audit
        site = ROOT / "target/bootshare/wordpress"
        elem = args.elementor_root.resolve()
        if not (elem / "target/classes/pl/codeloom/php/PhpContext.class").is_file():
            raise ValueError(f"Missing compiled elementor classes at {elem}")
        # Container runtimes must find this nested mount point on the read-only /freeze bind.
        (HERE / "classes").mkdir(exist_ok=True)

        (out / "classes").mkdir()
        (out / "bodies").mkdir()
        header = (site / "wp-blog-header.php").read_bytes()
        barrier = b"require_once __DIR__ . '/wp-load.php';"
        if header.count(barrier) != 1:
            raise ValueError("Unrecognized wp-blog-header.php bootstrap boundary")
        (out / "wp-blog-header.php").write_bytes(header.replace(
            barrier, barrier + b"\n\theader('X-Freeze-Boot-End-Ns: ' . hrtime(true));"))
        # Bind the reference projects read-only. A new classes directory for each run prevents stale overlays.
        mounts = ["-v", f"{ROOT}:/w:ro", "-v", f"{elem}:/elem:ro", "-v", f"{HERE}:/freeze:ro"]
        print(f"Compiling the overlay. Evidence: {out}", flush=True)
        execute(["docker", "run", "--rm", *mounts, "-v", f"{out / 'classes'}:/freeze/classes",
                 IMAGE, "sh", "/freeze/container.sh", "compile"], out / "compile.log")
        print((out / "compile.log").read_text(encoding="utf-8", errors="replace"), flush=True)
        if args.compile_only:
            return
        db = execute(["docker", "inspect", "--format", "{{.NetworkSettings.Networks.bridge.IPAddress}}", "gate2-mariadb"])
        if not db:
            raise RuntimeError("gate2-mariadb has no bridge address")
        cmd = ["docker", "create", "--name", self.container, "--cpuset-cpus", "14-21",
               "-p", f"127.0.0.1:{args.port}:8080", "-e", f"BOOTSHARE_DB_HOST={db}:3306",
               "-e", f"FREEZE_RC_AUDIT={str(args.exact_audit).lower()}", *mounts,
               "-v", f"{out / 'classes'}:/freeze/classes:ro", "-v", f"{out}:/evidence",
               IMAGE, "sh", "/freeze/container.sh", "serve"]
        self.summary["container_id"] = execute(cmd)
        try:
            self.summary["image_id"] = execute(["docker", "inspect", "-f", "{{.Image}}", self.container])
            for phase in PHASES:
                self.phase_run(phase)
        finally:
            execute(["docker", "rm", "-f", self.container])


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--port", type=int, default=8098)
    p.add_argument("--elementor-root", type=Path, default=ROOT.parent / "wordpress-elementor")
    p.add_argument("--warm-rounds", type=int, default=15)
    p.add_argument("--timing-rounds", type=int, default=25)
    p.add_argument("--heap-warm-rounds", type=int, default=150)
    p.add_argument("--exact-audit", action="store_true", help="also enable the expensive counted-holder walk at every zero in both D17 phases")
    p.add_argument("--compile-only", action="store_true")
    args = p.parse_args()
    if min(args.warm_rounds, args.timing_rounds, args.heap_warm_rounds) < 1:
        p.error("all sample and warm-up counts must be positive")
    out = HERE / "out" / (dt.datetime.now().strftime("%Y%m%d-%H%M%S") + "-" + uuid4().hex[:6])
    out.mkdir(parents=True)
    run = Run(args, out)
    try:
        run.run()
    except Exception:
        if run.phase is None and (out / "compile.log").is_file():
            print((out / "compile.log").read_text(encoding="utf-8", errors="replace"), file=sys.stderr)

        run.fail(traceback.format_exc())
        run.report()
    if run.errors:
        print(run.errors[0], file=sys.stderr)
        print(f"Evidence: {out}", file=sys.stderr)
        return 1
    print(f"Evidence: {out}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
