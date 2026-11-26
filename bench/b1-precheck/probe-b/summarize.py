#!/usr/bin/env python3
"""Parse Probe B Linux Docker outputs and evaluate every wiki/b1-precheck.md §3 kill criterion."""
from pathlib import Path
import re
import statistics
import sys

OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else (Path(__file__).resolve().parent / "out-v4b")


def read_text_auto(path: Path) -> str:
    raw = path.read_bytes()
    if raw.startswith(b"\xff\xfe") or raw.startswith(b"\xfe\xff"):
        return raw.decode("utf-16")
    return raw.decode("utf-8", errors="replace").lstrip("\ufeff")


def parse_run(path: Path, row_prefix: str):
    vals = []
    checksums = set()
    peak_mem = None
    for line in read_text_auto(path).splitlines():
        parts = line.strip().split()
        if not parts:
            continue
        if parts[0] == "peak_memory":
            peak_mem = int(parts[1])
        elif parts[0] == row_prefix and len(parts) >= 4:
            vals.append(float(parts[2]))
            checksums.add(parts[3])
    if not vals:
        raise RuntimeError(f"No rows for {row_prefix} in {path}")
    if len(checksums) != 1:
        raise RuntimeError(f"Checksum drift in {path}: {checksums}")
    half = vals[len(vals) // 2 :]
    half_sorted = sorted(half)
    p10 = half_sorted[max(0, int(len(half_sorted) * 0.10) - 1)]
    med = statistics.median(half_sorted)
    return {
        "n": len(vals),
        "p10": p10,
        "med": med,
        "checksum": next(iter(checksums)),
        "peak_mib": peak_mem / (1024 * 1024) if peak_mem is not None else float("nan"),
    }


def agg(file_kernel: str, engine: str, row_prefix: str = None, rounds: int = 5):
    prefix = row_prefix or file_kernel
    runs = [parse_run(OUT / f"{file_kernel}_{engine}_r{r}.txt", prefix) for r in range(1, rounds + 1)]
    checksums = {r["checksum"] for r in runs}
    if len(checksums) != 1:
        raise RuntimeError(f"Cross-round checksum drift {file_kernel}/{engine}/{prefix}: {checksums}")
    return {
        "p10_med": statistics.median([r["p10"] for r in runs]),
        "p10_runs": [r["p10"] for r in runs],
        "med_med": statistics.median([r["med"] for r in runs]),
        "med_runs": [r["med"] for r in runs],
        "peak_mib": statistics.median([r["peak_mib"] for r in runs]),
        "checksum": next(iter(checksums)),
    }


def parse_nmt_mib(match) -> float:
    if not match:
        return float("nan")
    val = int(match.group(2))
    unit = (match.group(3) or "").upper()
    if unit == "KB":
        return val / 1024.0
    if unit == "MB":
        return float(val)
    if unit == "GB":
        return val * 1024.0
    return val / (1024.0 * 1024.0)  # raw bytes (JDK 25 default scale=1)


def analyze_deopts_and_nmt(kernel: str, engine: str):
    txt = read_text_auto(OUT / f"deopt_{kernel}_{engine}.txt")
    m_start = re.search(r"STEADY_START_MS\s+(\d+)", txt)
    m_end = re.search(r"STEADY_END_MS\s+(\d+)", txt)
    if not m_start or not m_end:
        raise RuntimeError(f"Missing STEADY_START_MS/STEADY_END_MS in deopt_{kernel}_{engine}.txt")
    t_start = int(m_start.group(1)) / 1000.0
    t_end = int(m_end.group(1)) / 1000.0

    # Parse NMT total committed (handles raw bytes or KB/MB/GB suffixes)
    nmt_total = re.search(r"Total:\s+reserved=(\d+)(KB|MB|GB)?,\s+committed=(\d+)(KB|MB|GB)?", txt)
    if nmt_total:
        val = int(nmt_total.group(3))
        unit = (nmt_total.group(4) or "").upper()
        nmt_committed_mib = val * 1024.0 if unit == "GB" else (float(val) if unit == "MB" else (val / 1024.0 if unit == "KB" else val / (1024.0 * 1024.0)))
    else:
        nmt_committed_mib = float("nan")
    nmt_code = re.search(r"-\s+Code\s+\(reserved=(\d+)(KB|MB|GB)?,\s+committed=(\d+)(KB|MB|GB)?", txt)
    if nmt_code:
        val = int(nmt_code.group(3))
        unit = (nmt_code.group(4) or "").upper()
        nmt_code_mib = val * 1024.0 if unit == "GB" else (float(val) if unit == "MB" else (val / 1024.0 if unit == "KB" else val / (1024.0 * 1024.0)))
    else:
        nmt_code_mib = float("nan")

    # Parse exact LogCompilation events strictly inside [t_start, t_end]
    xml_path = OUT / f"deopt_{kernel}_{engine}.xml"
    content = xml_path.read_text(encoding="utf-8", errors="ignore")
    traps_total = 0
    traps_steady = 0
    for m in re.finditer(r"<uncommon_trap\b[^>]*stamp='([0-9.]+)'", content):
        ts = float(m.group(1))
        traps_total += 1
        if t_start <= ts <= t_end:
            traps_steady += 1

    nmethods_total = 0
    nmethods_steady = 0
    for m in re.finditer(r"<nmethod\b[^>]*stamp='([0-9.]+)'", content):
        ts = float(m.group(1))
        nmethods_total += 1
        if t_start <= ts <= t_end:
            nmethods_steady += 1

    ne_total = 0
    ne_steady = 0
    for m in re.finditer(r"<make_not_entrant\b[^>]*stamp='([0-9.]+)'", content):
        ts = float(m.group(1))
        ne_total += 1
        if t_start <= ts <= t_end:
            ne_steady += 1

    return {
        "t_start": t_start,
        "t_end": t_end,
        "traps_total": traps_total,
        "traps_steady": traps_steady,
        "nmethods_total": nmethods_total,
        "nmethods_steady": nmethods_steady,
        "ne_total": ne_total,
        "ne_steady": ne_steady,
        "nmt_committed_mib": nmt_committed_mib,
        "nmt_code_mib": nmt_code_mib,
    }


def fmt_time(kernel: str, ns: float) -> str:
    if kernel.startswith("hooks"):
        return f"{ns / 1e6:.2f} ms"
    return f"{ns:,.0f} ns"


def main():
    php_env_lines = read_text_auto(OUT / "env-php.txt").strip().splitlines()
    php_header = php_env_lines[0] if php_env_lines else "UNVERIFIED"
    if "jit_on=1" not in php_header or "kind=5" not in php_header:
        raise RuntimeError(f"PHP JIT verification failed or missing: {php_header}")
    php_ver = php_header.split()[1]

    print(f"=== Probe B Summary ({OUT.name}, 5 interleaved rounds, Linux Docker CPUs 14-21) ===")
    print("Verified PHP:", " | ".join(php_env_lines))
    print("Primary decision statistic: p10 of 2nd half (matching Gate A0 / opstable.py); median of 2nd half also shown.")
    print()

    main_kernels = ["hooks", "aget", "post", "mega"]
    res = {}
    for k in main_kernels:
        res[k] = {e: agg(k, e) for e in ["php", "c2", "graal"]}

    print(f"| Kernel | Metric | PHP {php_ver} JIT | Probe B C2 (-Xmx64m) | Probe B Graal (-Xmx64m) | C2 vs PHP | Graal vs PHP |")
    print("|---|---|---:|---:|---:|---:|---:|")
    for k in main_kernels:
        p = res[k]["php"]
        c = res[k]["c2"]
        g = res[k]["graal"]
        assert p["checksum"] == c["checksum"] == g["checksum"], f"Checksum mismatch on {k}"
        print(
            f"| `{k}` | p10 (2nd half) | {fmt_time(k, p['p10_med'])} | {fmt_time(k, c['p10_med'])} | {fmt_time(k, g['p10_med'])} "
            f"| **{p['p10_med']/c['p10_med']:.2f}x** | **{p['p10_med']/g['p10_med']:.2f}x** |"
        )
        print(
            f"| `{k}` | median (2nd half) | {fmt_time(k, p['med_med'])} | {fmt_time(k, c['med_med'])} | {fmt_time(k, g['med_med'])} "
            f"| {p['med_med']/c['med_med']:.2f}x | {p['med_med']/g['med_med']:.2f}x |"
        )
        print(
            f"| `{k}` | cgroup peak MiB | {p['peak_mib']:.1f} MiB | {c['peak_mib']:.1f} MiB | {g['peak_mib']:.1f} MiB | - | - |"
        )

    print("\n=== Paired Two-Context Penalty (Both CtxA and CtxB vs their own solo controls, interleaved) ===")
    for base, ctxb, two, two_a, two_b in [
        ("hooks", "hooks-ctxb", "hooks-2ctx", "hooks-2ctx-a", "hooks-2ctx-b"),
        ("mega", "mega-ctxb", "mega-2ctx", "mega-2ctx-a", "mega-2ctx-b"),
    ]:
        for e in ["c2", "graal"]:
            solo_a = res[base][e]
            solo_b = agg(ctxb, e)
            alt_a = agg(two, e, two_a)
            alt_b = agg(two, e, two_b)
            # Paired per-round penalty: median over rounds r of (2ctx[r] - solo[r]) / solo[r]
            pen_a_p10 = statistics.median([(a - s) / s * 100.0 for s, a in zip(solo_a["p10_runs"], alt_a["p10_runs"])])
            pen_a_med = statistics.median([(a - s) / s * 100.0 for s, a in zip(solo_a["med_runs"], alt_a["med_runs"])])
            pen_b_p10 = statistics.median([(a - s) / s * 100.0 for s, a in zip(solo_b["p10_runs"], alt_b["p10_runs"])])
            pen_b_med = statistics.median([(a - s) / s * 100.0 for s, a in zip(solo_b["med_runs"], alt_b["med_runs"])])
            print(
                f"- `{base}` CtxA ({e}): solo p10={fmt_time(base, solo_a['p10_med'])} -> 2ctx p10={fmt_time(base, alt_a['p10_med'])} "
                f"(paired {pen_a_p10:+.2f}%) | med {fmt_time(base, solo_a['med_med'])} -> {fmt_time(base, alt_a['med_med'])} (paired {pen_a_med:+.2f}%)"
            )
            print(
                f"- `{base}` CtxB ({e}): solo p10={fmt_time(base, solo_b['p10_med'])} -> 2ctx p10={fmt_time(base, alt_b['p10_med'])} "
                f"(paired {pen_b_p10:+.2f}%) | med {fmt_time(base, solo_b['med_med'])} -> {fmt_time(base, alt_b['med_med'])} (paired {pen_b_med:+.2f}%)"
            )

    print("\n=== Steady-State Compilation, Deoptimization & NMT Window [STEADY_START_MS, STEADY_END_MS] ===")
    for k in ["hooks-2ctx", "mega-2ctx"]:
        for e in ["c2", "graal"]:
            d = analyze_deopts_and_nmt(k, e)
            print(
                f"- `{k}` ({e}) [{d['t_start']:.2f}s..{d['t_end']:.2f}s]: "
                f"nmethods={d['nmethods_total']} (steady={d['nmethods_steady']}), "
                f"make_not_entrant={d['ne_total']} (steady={d['ne_steady']}), "
                f"uncommon_traps={d['traps_total']} (steady={d['traps_steady']}), "
                f"NMT committed={d['nmt_committed_mib']:.1f} MiB (Code={d['nmt_code_mib']:.1f} MiB)"
            )


if __name__ == "__main__":
    main()
