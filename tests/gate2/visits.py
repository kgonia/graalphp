"""Paced WordPress visits against one server: each request timed, the server's CPU and memory read from its cgroup.

A visit is the pages of Gate 2's browser visit, by plain HTTP: the home page, a post, the login page, logging in
(a cookie jar of the visit's own, so every visit logs in), the dashboard and the posts list. Static files (CSS, JS,
images) aren't fetched. Each visit after the first comes after a pause, so the server idles between visits as it
would for a reader; a warm visit's CPU is the server's from the end of the visit before, the pause included.

Started before the server (tests/gate2/native.sh measure): it polls the server's address until a static file
answers, so the time from the server's start to its first response is exact to the poll's 5 ms. It runs in a
container on the client's cores with the host's process table (--pid=host) and cgroups (-v /sys/fs/cgroup:/hostcg:ro)
and learns the server's container from a file the host writes once the server runs (ID and PID, one line).

Usage: python visits.py URL ID_FILE VISITS PAUSE_SECONDS OUT.json
"""
import http.cookiejar
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

HOST = {"Host": "localhost"}
LOGIN = urllib.parse.urlencode({"log": "admin", "pwd": "admin", "testcookie": "1", "wp-submit": "Log In",
                                "redirect_to": "http://localhost/wp-admin/"}).encode()
# name, path, form, expected status
PAGES = [("home", "/", None, 200), ("post", "/?p=1", None, 200), ("login-form", "/wp-login.php", None, 200),
         ("login", "/wp-login.php", LOGIN, 302), ("dashboard", "/wp-admin/", None, 200),
         ("posts", "/wp-admin/edit.php", None, 200)]


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def fetch(opener, url, data=None):
    """(status, body bytes, seconds) of one request."""
    request = urllib.request.Request(url, data=data, headers=HOST)
    start = time.perf_counter()
    try:
        with opener.open(request, timeout=300) as response:
            body = response.read()
            status = response.status
    except urllib.error.HTTPError as e:
        body = e.read()
        status = e.code
    return status, body, time.perf_counter() - start


class Server:
    """The server's container: its cgroup under /hostcg and its processes."""

    def __init__(self, id_file):
        while not os.path.exists(id_file) or not open(id_file).read().strip():
            time.sleep(0.05)
        container, pid = open(id_file).read().split()
        self.cgroup = "/hostcg/docker/" + container
        self.pid = int(pid)

    def started(self):
        """The server process's start (its container's first process), from /proc: to the clock tick."""
        ticks = os.sysconf("SC_CLK_TCK")
        fields = open(f"/proc/{self.pid}/stat").read().rsplit(")", 1)[1].split()
        uptime = float(open("/proc/uptime").read().split()[0])
        return time.time() - uptime + int(fields[19]) / ticks

    def cpu(self):
        """CPU seconds the container used so far."""
        for line in open(self.cgroup + "/cpu.stat"):
            name, value = line.split()
            if name == "usage_usec":
                return int(value) / 1e6
        raise ValueError("no usage_usec")

    def memory(self):
        """MB: the processes' RssAnon, the cgroup's anonymous memory, its current and its peak."""
        rss_anon = 0
        for pid in open(self.cgroup + "/cgroup.procs").read().split():
            try:
                for line in open(f"/proc/{pid}/status"):
                    if line.startswith("RssAnon:"):
                        rss_anon += int(line.split()[1])
            except OSError:
                pass
        anon = next(int(line.split()[1]) for line in open(self.cgroup + "/memory.stat") if line.startswith("anon "))
        return {"rss_anon": rss_anon / 1024, "anon": anon / 2**20,
                "current": int(open(self.cgroup + "/memory.current").read()) / 2**20,
                "peak": int(open(self.cgroup + "/memory.peak").read()) / 2**20}


def visit(base):
    """One visit: per page (name, status, ms), and whether every page answered as expected."""
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()), NoRedirect)
    pages, ok = [], True
    for name, path, form, expected in PAGES:
        status, body, seconds = fetch(opener, base + path, form)
        good = status == expected and (expected != 200 or b"</html>" in body)
        ok = ok and good
        pages.append([name, status, round(seconds * 1000, 2)])
    return pages, ok


def main():
    base, id_file, visits, pause, out = sys.argv[1], sys.argv[2], int(sys.argv[3]), float(sys.argv[4]), sys.argv[5]
    print("polling", flush=True)
    while True:
        try:
            if fetch(urllib.request.build_opener(), base + "/wp-includes/css/dashicons.css")[0] == 200:
                break
        except OSError:
            pass
        time.sleep(0.005)
    ready = time.time()
    server = Server(id_file)
    result = {"start_to_ready": ready - server.started(), "memory": {"start": server.memory()}}
    cpu0, began = server.cpu(), time.time()
    pages, ok = visit(base)
    result["cold"] = {"pages": pages, "ok": ok, "wait": sum(p[2] for p in pages) / 1000, "cpu": server.cpu() - cpu0,
                      "start_to_first_page": began - server.started() + pages[0][2] / 1000}
    result["memory"]["cold"] = server.memory()
    warm = []
    cpu = server.cpu()
    for n in range(1, visits + 1):
        time.sleep(pause)
        pages, ok = visit(base)
        if n % 25 == 0:
            result["memory"][f"visit{n}"] = server.memory()
        now = server.cpu()
        warm.append({"pages": pages, "ok": ok, "wait": sum(p[2] for p in pages) / 1000, "cpu": now - cpu})
        cpu = now
    result["warm"] = warm
    result["memory"]["warm"] = server.memory()
    json.dump(result, open(out, "w"), indent=1)
    failed = sum(not v["ok"] for v in warm) + (not result["cold"]["ok"])
    print(f"ready {result['start_to_ready']:.2f} s, cold visit {result['cold']['wait']:.2f} s "
          f"({result['cold']['cpu']:.1f} CPU-s), warm median "
          f"{sorted(v['wait'] for v in warm)[len(warm) // 2]:.3f} s, {failed} failed visits")


if __name__ == "__main__":
    main()
