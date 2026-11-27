#!/usr/bin/env python3
"""Summarize a bench/b1-week2 run (modelled on bench/b1-precheck/probe-b/summarize.py).

Usage: summarize.py OUT_DIR [--rounds N]
Reports, per kernel and engine (PHP 8.5.13 tracing JIT, B1 on OpenJDK 25 C2, B1 on OpenJDK 28-ea C2): p10 and
median of each run's second half (median over runs), speed-up over PHP, cgroup memory.peak; the 4-kernel Probe B
parity geomean (hooks, aget, post, mega), the full-suite geomean, the paired two-context penalty, and for the
paired runs: LogCompilation events inside [STEADY_START_MS, STEADY_END_MS], NMT committed/Code and the C2 nmethod
sizes of apply_filters and WP_Hook::apply_filters.
"""
from pathlib import Path
import math
import re
import statistics
import sys

ENGINES = ["php", "c2-25", "c2-28"]
JVMS = ["c2-25", "c2-28"]
PARITY = ["hooks", "aget", "post", "mega"]
PROBE_B_CONTROL = 1.11  # Probe B Rev 4b C2 4-kernel geomean (bench/b1-precheck/probe-b/RESULTS.md)
ORDER = ["hooks", "aget", "post", "mega", "none", "one", "hasf"]


def read_text(path: Path) -> str:
    raw = path.read_bytes()
    if raw.startswith(b"\xff\xfe") or raw.startswith(b"\xfe\xff"):
        return raw.decode("utf-16")
    return raw.decode("utf-8", errors="replace").lstrip("﻿")


def row_prefix(kernel: str) -> str:
    return kernel[3:] if kernel.startswith("k0-") else kernel


def parse_run(path: Path, prefix: str):
    vals, checksums, peak = [], set(), None
    for line in read_text(path).splitlines():
        parts = line.split()
        if not parts:
            continue
        if parts[0] == "peak_memory" and len(parts) > 1 and parts[1].isdigit():
            peak = int(parts[1])
        elif parts[0] == prefix and len(parts) >= 4:
            vals.append(float(parts[2]))
            checksums.add(parts[3])
    if not vals:
        raise RuntimeError(f"No rows for {prefix} in {path}")
    if len(checksums) != 1:
        raise RuntimeError(f"Checksum drift in {path}: {checksums}")
    half = sorted(vals[len(vals) // 2:])
    return {
        "p10": half[max(0, int(len(half) * 0.10) - 1)],
        "med": statistics.median(half),
        "checksum": next(iter(checksums)),
        "peak_mib": peak / 2**20 if peak is not None else float("nan"),
    }


def agg(out: Path, kernel: str, engine: str, rounds: int, prefix: str = None):
    prefix = prefix or row_prefix(kernel)
    runs = [parse_run(out / f"{kernel}_{engine}_r{r}.txt", prefix) for r in range(1, rounds + 1)]
    checks = {r["checksum"] for r in runs}
    if len(checks) != 1:
        raise RuntimeError(f"Cross-round checksum drift {kernel}/{engine}: {checks}")
    return {
        "p10": statistics.median(r["p10"] for r in runs), "p10_runs": [r["p10"] for r in runs],
        "med": statistics.median(r["med"] for r in runs), "med_runs": [r["med"] for r in runs],
        "peak_mib": statistics.median(r["peak_mib"] for r in runs), "checksum": next(iter(checks)),
    }


def fmt(kernel: str, ns: float) -> str:
    if kernel.startswith("hooks") or kernel.startswith("k0-"):
        return f"{ns / 1e6:,.2f} ms"
    return f"{ns:,.0f} ns"


def geomean(values):
    values = [v for v in values if v > 0 and not math.isnan(v)]
    return math.exp(sum(math.log(v) for v in values) / len(values)) if values else float("nan")


def nmt_mib(match, value_group, unit_group):
    if not match:
        return float("nan")
    value, unit = int(match.group(value_group)), (match.group(unit_group) or "").upper()
    return value * 1024.0 if unit == "GB" else float(value) if unit == "MB" else value / 1024.0 if unit == "KB" else value / 2**20


def steady(out: Path, kernel: str, engine: str):
    txt = read_text(out / f"deopt_{kernel}_{engine}.txt")
    start = re.search(r"STEADY_START_MS\s+(\d+)", txt)
    end = re.search(r"STEADY_END_MS\s+(\d+)", txt)
    if not start or not end:
        raise RuntimeError(f"Missing STEADY markers in deopt_{kernel}_{engine}.txt")
    t0, t1 = int(start.group(1)) / 1000.0, int(end.group(1)) / 1000.0
    total = re.search(r"Total:\s+reserved=(\d+)(KB|MB|GB)?,\s+committed=(\d+)(KB|MB|GB)?", txt)
    code = re.search(r"-\s+Code\s+\(reserved=(\d+)(KB|MB|GB)?,\s+committed=(\d+)(KB|MB|GB)?", txt)
    xml = (out / f"deopt_{kernel}_{engine}.xml").read_text(encoding="utf-8", errors="ignore")

    def window(tag):
        stamps = [float(m.group(1)) for m in re.finditer(rf"<{tag}\b[^>]*stamp='([0-9.]+)'", xml)]
        return len(stamps), sum(1 for s in stamps if t0 <= s <= t1)

    # Hidden classes print as pl.codeloom.php.b1.CompiledN/0x...; LogCompilation names them by that address.
    sizes = {}
    for m in re.finditer(r"^B1METHOD (\S+) (\S+) (\S+) (\d+)$", txt, re.M):
        function, hidden, method, bytecodes = m.groups()
        if function not in ("apply_filters", "WP_Hook::apply_filters"):
            continue
        # LogCompilation prints method='<class> <name> (<signature>)...': anchor on the whole class and name.
        # OSR compilations of a loop are not the method's own nmethod; they are counted separately.
        needle = f"method='{hidden} {method} "
        found, osr = [], 0
        for n in re.finditer(r"<nmethod\b([^>]*)>", xml):
            attrs = n.group(1)
            if needle not in attrs or "compiler='c2'" not in attrs:
                continue
            if "compile_kind='osr'" in attrs:
                osr += 1
                continue
            size = re.search(r"\bsize='(\d+)'", attrs)
            stamp = re.search(r"\bstamp='([0-9.]+)'", attrs)
            start = re.search(r"\binsts_offset='(-?\d+)'", attrs)
            stubs = re.search(r"\bstub_offset='(-?\d+)'", attrs)
            found.append((float(stamp.group(1)) if stamp else 0.0, int(size.group(1)) if size else -1,
                          int(stubs.group(1)) - int(start.group(1)) if start and stubs else -1))
        osr_note = f", {osr} C2 OSR installs" if osr else ""
        if found:
            last = max(found)
            sizes[f"{function} {method}"] = (f"{last[1]} B nmethod ({last[2]} B instructions, bytecode {bytecodes} B, "
                                             f"{len(found)} C2 installs{osr_note})")
        elif method == "run" or osr:
            sizes[f"{function} {method}"] = f"no C2 nmethod (bytecode {bytecodes} B{osr_note})"
    traps = window("uncommon_trap")
    nmethods = window("nmethod")
    not_entrant = window("make_not_entrant")
    return {
        "t0": t0, "t1": t1, "traps": traps, "nmethods": nmethods, "not_entrant": not_entrant,
        "nmt": nmt_mib(total, 3, 4), "code": nmt_mib(code, 3, 4), "sizes": sizes,
    }


def main():
    args = sys.argv[1:]
    out = Path(args[0]) if args else Path(__file__).resolve().parent / "out-baseline"
    rounds = int(args[args.index("--rounds") + 1]) if "--rounds" in args else max(
        int(m.group(1)) for m in (re.search(r"_r(\d+)\.txt$", p.name) for p in out.glob("*_r*.txt")) if m)
    env = read_text(out / "env-php.txt").strip().splitlines()
    if not env or "jit_on=1" not in env[0] or "kind=5" not in env[0]:
        raise RuntimeError(f"PHP JIT verification failed or missing: {env[:1]}")
    kernels = sorted({p.name.split("_")[0] for p in out.glob("*_php_r1.txt")},
                     key=lambda k: (k.startswith("k0-"), ORDER.index(k) if k in ORDER else 99, k))
    print(f"=== B1 Week 2 ({out.name}): {rounds} interleaved round(s), Linux Docker ===")
    print("PHP:", " | ".join(env))
    for engine in JVMS:
        version = read_text(out / f"env-{engine}.txt").strip().splitlines()
        print(f"{engine}: {version[0] if version else '?'}")
    print("Statistic: p10 and median of each run's second half, median over runs; speed-up = PHP time / B1 time.\n")

    res = {k: {e: agg(out, k, e, rounds) for e in ENGINES} for k in kernels}
    print("| Kernel | Metric | PHP 8.5.13 JIT | B1 JDK 25 C2 | B1 JDK 28-ea C2 | 25 vs PHP | 28 vs PHP |")
    print("|---|---|---:|---:|---:|---:|---:|")
    for k in kernels:
        p, a, b = (res[k][e] for e in ENGINES)
        if not (p["checksum"] == a["checksum"] == b["checksum"]):
            raise RuntimeError(f"Checksum mismatch on {k}: {p['checksum']} {a['checksum']} {b['checksum']}")
        print(f"| `{k}` | p10 | {fmt(k, p['p10'])} | {fmt(k, a['p10'])} | {fmt(k, b['p10'])} | **{p['p10'] / a['p10']:.2f}x** | **{p['p10'] / b['p10']:.2f}x** |")
        print(f"| `{k}` | median | {fmt(k, p['med'])} | {fmt(k, a['med'])} | {fmt(k, b['med'])} | {p['med'] / a['med']:.2f}x | {p['med'] / b['med']:.2f}x |")
        print(f"| `{k}` | memory.peak | {p['peak_mib']:.1f} MiB | {a['peak_mib']:.1f} MiB | {b['peak_mib']:.1f} MiB | - | - |")
    print("\nAll checksums identical across PHP and both JDKs (and across rounds).")

    print("\n=== Geomeans (p10 speed-up over PHP) ===")
    for engine in JVMS:
        parity = [res[k]["php"]["p10"] / res[k][engine]["p10"] for k in PARITY if k in res]
        full = [res[k]["php"]["p10"] / res[k][engine]["p10"] for k in kernels]
        if len(parity) == len(PARITY):
            print(f"- {engine}: Probe B parity 4-kernel geomean {geomean(parity):.2f}x (Probe B Rev 4b control {PROBE_B_CONTROL:.2f}x)")
        print(f"- {engine}: full-suite geomean over {len(full)} kernel(s) {geomean(full):.2f}x")

    pairs = [(base, f"{base}-ctxb", f"{base}-2ctx") for base in ("hooks", "mega") if base in res
             and (out / f"{base}-2ctx_c2-25_r1.txt").exists()]
    if pairs:
        print("\n=== Paired two-context penalty (2ctx vs each context's solo run, per round, median) ===")
    for base, ctxb, two in pairs:
        for engine in JVMS:
            solo_a = res[base][engine]
            solo_b = agg(out, ctxb, engine, rounds)
            alt_a = agg(out, two, engine, rounds, f"{two}-a")
            alt_b = agg(out, two, engine, rounds, f"{two}-b")
            for label, solo, alt in (("CtxA", solo_a, alt_a), ("CtxB", solo_b, alt_b)):
                p10 = statistics.median((x - s) / s * 100 for s, x in zip(solo["p10_runs"], alt["p10_runs"]))
                med = statistics.median((x - s) / s * 100 for s, x in zip(solo["med_runs"], alt["med_runs"]))
                print(f"- `{base}` {label} ({engine}): p10 {fmt(base, solo['p10'])} -> {fmt(base, alt['p10'])} ({p10:+.1f}%), "
                      f"median {fmt(base, solo['med'])} -> {fmt(base, alt['med'])} ({med:+.1f}%), checksum {alt['checksum']}")

    deopts = [(k, e) for k in ("hooks-2ctx", "mega-2ctx") for e in JVMS if (out / f"deopt_{k}_{e}.txt").exists()]
    if deopts:
        print("\n=== Steady state [STEADY_START_MS, STEADY_END_MS]: compilation, deoptimization, NMT, nmethod sizes ===")
    for k, e in deopts:
        d = steady(out, k, e)
        print(f"- `{k}` ({e}) [{d['t0']:.2f}s..{d['t1']:.2f}s]: nmethods={d['nmethods'][0]} (steady {d['nmethods'][1]}), "
              f"make_not_entrant={d['not_entrant'][0]} (steady {d['not_entrant'][1]}), "
              f"uncommon_traps={d['traps'][0]} (steady {d['traps'][1]}), NMT committed={d['nmt']:.1f} MiB (Code {d['code']:.1f} MiB)")
        for name, size in sorted(d["sizes"].items()):
            print(f"    {name}: {size}")


if __name__ == "__main__":
    main()
