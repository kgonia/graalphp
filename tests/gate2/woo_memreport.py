"""Where a server's memory went over a memrun (tests/gate2/woo.sh memrun): each snapshot in DIR (memsnap.sh output,
named by its Unix time) split by owner (memsplit.py's parts, for a JVM), beside the requests served until then
(k6's every request, LOAD/TAG-steady.csv.gz) and the CPU seconds of Truffle's compiler threads (a native image's
too) and of the JVM's own compiler threads so far.

Usage: python woo_memreport.py DIR CSV_GZ [--json OUT]
"""
import csv
import gzip
import json
import os
import re
import sys

sys.dont_write_bytecode = True
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import memsplit  # noqa: E402


def threads(text):
    out = {}
    if "## thread cpu" not in text:
        return out
    for line in text[text.index("## thread cpu"):].splitlines()[1:]:
        parts = line.rsplit(" ", 2)
        if len(parts) != 3 or not re.fullmatch(r"[0-9.]+", parts[2]):
            break
        out[parts[0]] = (int(parts[1]), float(parts[2]))
    return out


def main():
    directory, csv_path = sys.argv[1], sys.argv[2]
    stamps = []
    with gzip.open(csv_path, "rt", newline="") as f:
        for row in csv.DictReader(f):
            if row["metric_name"] == "http_reqs":
                stamps.append(int(row["timestamp"]))
    stamps.sort()
    start = stamps[0] if stamps else 0
    rows = []
    for name in sorted(os.listdir(directory)):
        match = re.fullmatch(r"([0-9]+)\.txt", name)
        if not match:
            continue
        text = open(os.path.join(directory, name), encoding="utf-8", errors="replace").read()
        t = int(match.group(1))
        rss = re.search(r"RssAnon:\s+(\d+) kB", text)
        if not rss:
            continue
        row = {"t": t - start, "requests": sum(1 for s in stamps if s <= t), "rss_anon": int(rss.group(1)) // 1024}
        cpu = threads(text)
        truffle = {k: v for k, v in cpu.items() if k.startswith("TruffleCompiler") or k.endswith("mpilerThread-")}
        row["truffle_compiler_threads"] = sum(n for n, _ in truffle.values())
        row["truffle_compile_s"] = round(sum(s for _, s in truffle.values()), 1)
        row["jvm_jit_s"] = round(sum(s for k, (_, s) in cpu.items() if k.startswith(("CCompilerThre", "JVMCI"))), 1)
        if "## nmt" in text:
            try:
                result = memsplit.split(text)
                row.update({k: round(v / 2 ** 20) for k, v in result["parts"].items()})
            except (AttributeError, ValueError):
                pass
        else:
            mappings = [int(x) // 1024 for x in re.findall(r"^[0-9a-f]+ [0-9a-f]+ (\d+)", text, re.M)]
            row["largest_mappings_mb"] = sorted(mappings, reverse=True)[:5]
        rows.append(row)
    keys = []
    for row in rows:
        keys += [k for k in row if k not in keys]
    print(" ".join(keys))
    for row in rows:
        print(" ".join(str(row.get(k, "")) for k in keys))
    if "--json" in sys.argv:
        json.dump(rows, open(sys.argv[sys.argv.index("--json") + 1], "w"), indent=1)


if __name__ == "__main__":
    main()
