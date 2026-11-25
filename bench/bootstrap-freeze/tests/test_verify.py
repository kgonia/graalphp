"""Offline regressions for the bootstrap-freeze host verifier."""
import base64
import json
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import verify as v


def audit(frozen, repeated_id=41, cutoff=40):
    lines = ["listening on localhost"]
    hit = "freeze-rc-hit\t{}\tWP_Post\tf < g"
    if frozen:
        for variant, limit in (("frontend", cutoff), ("rest", 65)):
            lines.extend([f"freeze-rc-begin phase=capture route={variant} snapshot_max_obj_id=-1",
                          hit.format(20), f"freeze build snapshot_max_obj_id={limit} frozen_arrays=1", "freeze-rc-end"])
    for page, route in v.ROUTES.items():
        kind = "restored" if frozen else "full"
        limit = (65 if page == "rest" else cutoff) if frozen else -1
        lines.append(f"freeze-rc-begin phase={kind} route={route} snapshot_max_obj_id={limit}")
        if page == "post":
            lines.append(hit.format(repeated_id))
        lines.append("freeze-rc-end")
    lines.append(f"rc-verify: 50 of 50 zeros checked, {3 if frozen else 1} still reachable at 1 sites")
    return "\n".join(lines)


class VerifyTests(unittest.TestCase):
    def test_wordpress_time_units_and_half_up_rounding(self):
        day = 86400
        for age, label in ((0, "1 second ago"), (2, "2 seconds ago"), (59, "59 seconds ago"),
                           (60, "1 minute ago"), (90, "2 minutes ago"), (3599, "60 minutes ago"),
                           (3600, "1 hour ago"), (15 * 3600 + 1800, "16 hours ago"),
                           (day - 1, "24 hours ago"), (day, "1 day ago"), (7 * day, "1 week ago"),
                           (30 * day, "1 month ago"), (45 * day, "2 months ago"),
                           (365 * day, "1 year ago"), (730 * day, "2 years ago")):
            with self.subTest(age=age):
                self.assertEqual(v.wp_time_ago(age), label)

    def test_time_rollover_restricted_to_request_interval(self):
        stamp = "2026-11-24T20:27:09+00:00"
        boundary = v.dt.datetime.fromisoformat(stamp).timestamp() + 15 * 3600 + 1800
        def check(text, start, end, datetime=stamp):
            tag = f'<time datetime="{datetime}">{text}</time>'.encode()
            return v.relative_time_fields("post", tag, start, end)
        a, logged_a = check("15 hours ago", boundary - 1, boundary - .001)
        b, logged_b = check("16 hours ago", boundary, boundary + 1)
        self.assertEqual(a, b)
        self.assertIn(stamp.encode(), a)
        self.assertNotEqual(logged_a, logged_b)
        for label in ("15 hours ago", "16 hours ago"):
            self.assertEqual(check(label, boundary - .1, boundary + .1)[0], a)
        for text, start, end in (("16 hours ago", boundary - 1, boundary - .001),
                                 ("15 hours ago", boundary, boundary + 1),
                                 ("14 hours ago", boundary - .1, boundary + .1),
                                 ("16 hour ago", boundary, boundary + 1),
                                 ("016 hours ago", boundary, boundary + 1),
                                 ("1 second ago", boundary - 60000, boundary - 59999)):
            with self.subTest(text=text, start=start), self.assertRaises(ValueError):
                check(text, start, end)
        for invalid in ("2026-11-24T20:27:09", "not-a-date"):
            with self.assertRaises(ValueError):
                check("16 hours ago", boundary, boundary + 1, invalid)

    def test_time_normalization_preserves_markup_and_non_html_data(self):
        absolute = b'<time datetime="1970-01-01T00:00:00Z">January 1</time>'
        label = b"<time\n class='age' datetime='1970-01-01T01:00:00+01:00'>1 hour ago</time>"
        raw = b'\xc3\xa9' + absolute + b' <!--' + label + b'--><script>' + label + b'</script><textarea>' + label + b'</textarea>'
        raw += b'<time>7 hours ago</time> 7 hours ago'
        body = raw + label + label
        normalized, seen = v.relative_time_fields("post", body, 3600, 3600)
        self.assertEqual(normalized, raw + label.replace(b"1 hour ago", b"{human_time_diff}") * 2)
        self.assertEqual(seen, [{"datetime": "1970-01-01T01:00:00+01:00", "label": "1 hour ago"}] * 2)
        self.assertEqual(v.relative_time_fields("rest", body, 0, 0), (body, []))

    def test_akismet_exact(self):
        def field(n):
            return b'<input type="hidden" id="ak_js_1" name="ak_js" value="' + n + b'"/>'
        for n in (0, 1, 99, 250):
            raw = b"x42" + field(str(n).encode())
            normalized, value = v.akismet_field("post", raw)
            self.assertEqual(value, n)
            self.assertEqual(normalized, b"x42" + field(b"{ak_js_1}"))
        for raw in (b"", field(b"-1"), field(b"07"), field(b"251"), field(b"3") * 2,
                    field(b"2") + b"<input id='ak_js_1'/>", field(b"1").replace(b'hidden', b'text')):
            with self.assertRaises(ValueError):
                v.akismet_field("post", raw)

    def test_header_multiplicity_order_and_length(self):
        headers = [("date", "D"), ("x-freeze-mode", "frontend"), ("link", "a"), ("link", "b"),
                   ("content-length", "11"), ("x-wp-total", "3"), ("x-freeze-other", "app")]
        comparable = v.comparable_headers(headers, 11, 20)
        self.assertEqual(comparable, {"link": ["a", "b"], "content-length": ["20"],
                                      "x-wp-total": ["3"], "x-freeze-other": ["app"]})
        self.assertNotEqual(comparable, v.comparable_headers(headers[::-1], 11, 20))
        with self.assertRaises(ValueError):
            v.comparable_headers(headers, 12, 20)
        with self.assertRaises(ValueError):
            v.one(headers, "link")

    def test_nested_duplicate_json(self):
        valid = b'[{"id":1,"type":"post","title":{"rendered":"yes"}}]'
        v.structure("rest", valid, "application/json")
        for src in (valid.replace(b'"id":1', b'"id":2,"id":1'),
                    valid.replace(b'"rendered":"yes"', b'"rendered":"no","rendered":"yes"')):
            with self.assertRaisesRegex(ValueError, "Duplicate JSON key"):
                v.structure("rest", src, "application/json")

    def test_audit_repeated_site_and_snapshot_cutoff(self):
        baseline = v.audit_log(audit(False), False)
        frozen = v.audit_log(audit(True), True)
        self.assertEqual(frozen["sites"], baseline["request_sites"])
        self.assertEqual((frozen["capture_hits"], frozen["request_hits"], frozen["restored_hits"]), (2, 1, []))
        bad = v.audit_log(audit(True, repeated_id=40), True)
        self.assertEqual([h["id"] for h in bad["restored_hits"]], [40])
        changed = audit(True).replace("41\tWP_Post\tf < g", "41\tWP_Post\tf < h").replace("3 still reachable at 1", "3 still reachable at 2")
        self.assertEqual(set(v.audit_log(changed, True)["sites"]) - set(baseline["sites"]), {"WP_Post at f < h"})

    def test_audit_requires_all_hits_matching_cutoffs_and_shutdown(self):
        data = audit(True)
        for altered in (data.replace("snapshot_max_obj_id=65 frozen", "snapshot_max_obj_id=100 frozen"),
                        data.replace("freeze-rc-hit\t41\tWP_Post\tf < g", ""),
                        data.replace("50 of 50", "49 of 50"), data.rsplit("\n", 1)[0],
                        data.replace("freeze-rc-end", "", 1)):
            with self.assertRaises(ValueError):
                v.audit_log(altered, True)

    def test_gc_and_runtime_mode(self):
        start = "## live heap (after a full GC)"
        info = "garbage-first heap total reserved 8192K, committed 4096K, used 3072K [0x001, 0x002)"
        output = "\n".join([start, "1:", "Command executed successfully", "1:", info, "## class histogram (live objects)"])
        self.assertEqual(v.verify_gc(output), 3)
        for bad in (output.replace("Command executed successfully", ""), output.replace(start, "## live heap (no GC)")):
            with self.assertRaises(ValueError):
                v.verify_gc(bad)
        marker = "freeze runtime_mode=fallback-interpreter runtime=Interpreted"
        self.assertEqual(v.runtime_mode(marker)["mode"], "fallback-interpreter")
        with self.assertRaises(ValueError):
            v.runtime_mode("uses a fallback runtime\nfreeze runtime_mode=compiled runtime=other")

    def test_guest_isolation_and_expirations(self):
        args = SimpleNamespace(warm_rounds=1, timing_rounds=1, heap_warm_rounds=1)
        run = v.Run(args, Path("."))
        def probe(i, **edits):
            p = dict(uri="/", cf7="/", cookie=[], filter_cookie=None, filter_post_id=None, get_post_id=None,
                     request_post_id=None, marker="abc", result_cleared=True, wp_start=1001.5, timestart=1001.5,
                     request_time=1001.51, checked_at=1003, connection=i, placeholder_hash=str(i),
                     localized={"store_time": 1002, "store_nonce": True},
                     guest=dict(id="t_" + f"{i:030x}", expiring=1002 + 86400, expiration=1002 + 172800))
            p.update(edits)
            h = [("x-freeze-check", base64.b64encode(json.dumps(p).encode()).decode()),
                 ("x-freeze-arrival-micros", "1001500000")]
            return run.check_probe(h, "home", "abc")
        a = probe(1)["guest"]
        self.assertNotEqual(a, probe(2)["guest"])
        with self.assertRaisesRegex(ValueError, "Reused guest_id"):
            probe(3, guest=a)
        with self.assertRaisesRegex(ValueError, "Stale guest"):
            probe(4, guest=dict(a, expiration=172899))
        with self.assertRaisesRegex(ValueError, "localized"):
            probe(5, localized={"rest_nonce": False})
        with self.assertRaisesRegex(ValueError, "state mismatch"):
            probe(6, wp_start=1002)


if __name__ == "__main__":
    unittest.main()
