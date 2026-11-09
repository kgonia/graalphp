"""PHP 8.5 against a Truffle PHP build from phpref.sh's runs: python phpref.py OUT VARIANT ROUNDS."""
import statistics
import sys


def load(path):
    times, memory = {}, None
    for line in open(path, encoding="utf-8", errors="replace"):
        parts = line.split()
        if len(parts) >= 4 and parts[1].isdigit():
            times.setdefault(parts[0], []).append(int(parts[2]))
        elif len(parts) == 2 and parts[0] == "peak_memory":
            memory = int(parts[1]) / 2**20
    run = {k: statistics.median(ns[3 * len(ns) // 4:]) / 1e6 for k, ns in times.items()}
    run["memory"] = memory
    return run


out, v, rounds = sys.argv[1], sys.argv[2], int(sys.argv[3])
php = [load(f"{out}/php-{r}.txt") for r in range(1, rounds + 1)]
tr = [load(f"{out}/{v}-{r}.txt") for r in range(1, rounds + 1)]
for k in ["hooks", "escape", "slugs", "wpautop", "wptexturize", "blocks", "kses", "memory"]:
    p = statistics.median(x[k] for x in php)
    t = statistics.median(x[k] for x in tr)
    ratios = sorted(b[k] / a[k] for a, b in zip(php, tr))
    print(f"| {k} | {p:.2f} | {t:.2f} | {statistics.median(ratios):.2f} ({ratios[0]:.2f}-{ratios[-1]:.2f}) |")
