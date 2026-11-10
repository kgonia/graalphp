"""The hot renders' time per PHP function on both engines, side by side (wiki/beat-php.md).

PHP: the K8 requests' Excimer profiles (k8_drive.py's OUT of a run with k8prof, the php-fpm image with Excimer):
samples per function, a built-in's time counted as its calling PHP function's. Ours: launcher.GuestSampler's table
over the same requests (its "user" column: a sample counted for the nearest PHP function of its stack, so a
built-in's time is its caller's, as Excimer's). Each engine's samples are scaled to its hot render's time
(PHP_MS and OUR_MS, from the timed run: k8_report.py), so a row reads "ms of one render spent in this function".

The table: the functions by the difference of self time, ours minus PHP's (the largest first), then the functions
where PHP is slower. "incl" is the time with the callees.

Usage: python k8_profile.py PHP_K8_JSON GUEST_TXT PHP_MS OUR_MS OUT [ROWS]
"""
import json
import sys


def php_profile(name):
    self, inclusive = {}, {}
    for block in json.load(open(name))["blocks"]:
        for request in block["requests"]:
            for function, s, i in request.get("profile") or []:
                self[function] = self.get(function, 0) + s
                inclusive[function] = inclusive.get(function, 0) + i
    return self, inclusive


def guest_profile(name):
    """GuestSampler's table: self by the top frame, total, and user (the nearest PHP function's)."""
    self, inclusive, own = {}, {}, {}
    lines = open(name, encoding="utf-8").read().split("\n")
    header = lines[1].split("\t")
    user = header.index("user") if "user" in header else None
    for line in lines[2:]:
        parts = line.split("\t")
        if len(parts) == len(header):
            function = parts[-1]
            own[function] = int(parts[0])
            inclusive[function] = int(parts[header.index("total")])
            self[function] = int(parts[user]) if user is not None else int(parts[0])
        elif len(parts) == 2 and parts[0].isdigit():
            inclusive[parts[1]] = int(parts[0])
    return self, inclusive, own


def main():
    php_name, guest_name, php_ms, our_ms, out = sys.argv[1:6]
    rows = int(sys.argv[6]) if len(sys.argv) > 6 else 40
    php_ms, our_ms = float(php_ms), float(our_ms)
    php_self, php_incl = php_profile(php_name)
    our_self, our_incl, our_own = guest_profile(guest_name)
    php_scale = php_ms / sum(php_self.values())
    our_scale = our_ms / sum(our_self.values())
    table = []
    for function in set(php_self) | set(our_self) | set(our_own):
        p = php_self.get(function, 0) * php_scale
        o = our_self.get(function, 0) * our_scale
        table.append((o - p, function, p, o, php_incl.get(function, 0) * php_scale,
                      our_incl.get(function, 0) * our_scale))
    table.sort(reverse=True)

    def fmt(row):
        diff, function, p, o, pi, oi = row
        ratio = f"{o / p:.1f}" if p > 0.005 else "—"
        return f"| `{function}` | {p:.3f} | {o:.3f} | {diff:+.3f} | {ratio} | {pi:.2f} | {oi:.2f} |"

    head = ["| function | PHP self ms | ours self ms | difference | ours/PHP | PHP incl ms | ours incl ms |",
            "|---|---|---|---|---|---|---|"]
    gap = our_ms - php_ms
    covered = sum(r[0] for r in table[:rows])
    lines = [f"Hot render: PHP {php_ms:.2f} ms, ours {our_ms:.2f} ms ({our_ms / php_ms:.2f}x); PHP's samples"
             f" {sum(php_self.values())}, ours {sum(our_self.values())}. The {rows} rows below hold {covered:.2f} ms"
             f" of the {gap:.2f} ms difference.", ""]
    lines += head + [fmt(r) for r in table[:rows]]
    lines += ["", "Where PHP is slower:", ""] + head + [fmt(r) for r in table[-1:-rows // 2 - 1:-1] if r[0] < 0]
    builtins = [(n, f) for f, n in our_own.items() if f not in php_self and f not in our_self or
                (our_self.get(f, 0) == 0 and n > 0)]
    builtins.sort(reverse=True)
    total_own = sum(our_own.values())
    lines += ["", "Ours, the built-ins that are frames of their own (share of our samples, counted above in their"
              " callers):", ""]
    lines += [f"- `{f}` {100 * n / total_own:.1f}%" for n, f in builtins[:30]]
    text = "\n".join(lines) + "\n"
    open(out, "w", encoding="utf-8").write(text)
    sys.stdout.reconfigure(encoding="utf-8")
    print(text)


if __name__ == "__main__":
    main()
