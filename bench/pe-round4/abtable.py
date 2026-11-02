"""An interleaved load A/B of bench/pe-round4/runs.sh ab A B ROUNDS as a Markdown table: per round both builds'
orders/s, p50, p99, CPU-s per page and memory.peak, and B's ratios to A; then the medians and the geometric means
of the ratios over the rounds. The runs are target/p4i/load/A_B.BUILD-rN-1-16.* (woo.sh load, every visitor buying).

Usage: python -I bench/pe-round4/abtable.py A B [LOAD_DIR]
"""
import math
import os
import statistics
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "tests", "gate2"))
import woo_report  # noqa: E402


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    a, b = sys.argv[1], sys.argv[2]
    directory = sys.argv[3] if len(sys.argv) > 3 else "target/p4i/load"
    rounds = []
    r = 1
    while True:
        names = [os.path.join(directory, f"{a}_{b}.{v}-r{r}-1-16") for v in (a, b)]
        if not all(os.path.exists(n + ".check.json") and os.path.exists(n + ".k6.json") for n in names):
            break
        rounds.append([woo_report.numbers(n) for n in names])
        r += 1
    keys = [("orders_s", "orders/s", "{:.2f}"), ("p50", "p50 ms", "{:.0f}"), ("p99", "p99 ms", "{:.0f}"),
            ("cpu_per_page", "CPU-s/page", "{:.3f}"), ("peak", "memory.peak MB", "{:.0f}")]
    head = "| round (first) | " + " | ".join(f"{label} {a} → {b}" for _, label, _ in keys) + \
        f" | {b} / {a}: orders, CPU/page, p99, peak |"
    lines = [head, "|" + "---|" * (len(keys) + 2)]
    ratios = {k: [] for k, _, _ in keys}
    for i, (na, nb) in enumerate(rounds, 1):
        first = a if i % 2 == 1 else b
        cells = [f"{fmt.format(na[k])} → {fmt.format(nb[k])}" for k, _, fmt in keys]
        for k, _, _ in keys:
            ratios[k].append(nb[k] / na[k])
        lines.append(f"| {i} ({first}) | " + " | ".join(cells) + " | " + ", ".join(
            f"{ratios[k][-1]:.2f}" for k in ("orders_s", "cpu_per_page", "p99", "peak")) + " |")
    if rounds:
        med = [f"{fmt.format(statistics.median(n[0][k] for n in rounds))} → "
               f"{fmt.format(statistics.median(n[1][k] for n in rounds))}" for k, _, fmt in keys]
        geo = {k: math.exp(statistics.mean(math.log(x) for x in v)) for k, v in ratios.items()}
        lines.append(f"| **{len(rounds)} rounds** (medians) | " + " | ".join(med) + " | **geometric mean " + ", ".join(
            f"{geo[k]:.2f}" for k in ("orders_s", "cpu_per_page", "p99", "peak")) + "** |")
    errors = sum(n["failed"] + n["db_problems"] for pair in rounds for n in pair)
    print("\n".join(lines))
    print(f"\n{len(rounds)} rounds; failed requests (rate) and database problems summed: {errors}")


if __name__ == "__main__":
    main()
