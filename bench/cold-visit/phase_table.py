"""The cold visit's phases per diagnosis variant (bench/cold-visit/runs.sh, suffix d), medians over rounds (range):
- on the requests' thread, from the phase counters (tests/gate2/phases_report.py's visits_of): parse (file read,
  file parse, body read), translate (file and body), includes, native calls and the context; execution is the
  rest of the wall time, split into
  - off-CPU: the thread's wall minus its CPU (I/O and the database, and waiting for a CPU);
  - on-CPU, by the JFR samples of execution (JfrCold.java's "phase / leaf kind" and "nearest interpreter frame"):
    Java run by HotSpot's interpreter, the interpreter's host-compiled Java, Truffle-compiled PHP;
- off the thread (CPU-s, from the server's start to the cold visit's end): HotSpot's compiler threads (C1 and
  libgraal compiling Java, the interpreter's among it), Truffle's compiler threads, GC and VM threads.
JfrCold's output is read from target/cv/out/rROUND/VARIANT.jfr.txt, made first if missing.

Usage: python bench/cold-visit/phase_table.py VARIANT...
"""
import json
import os
import re
import statistics
import subprocess
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "tests", "gate2"))
from phases_report import visits_of  # noqa: E402

RUNS = "target/cv/runs"


def jfr_split(variant, r, cutoff_ms):
    out = f"target/cv/out/r{r}/{variant}.jfr.txt"
    if not os.path.exists(out) or os.path.getsize(out) == 0:
        with open(out, "w") as f:
            # Git's bash: a bare "bash" on Windows is WSL's
            subprocess.run([r"C:\Program Files\Git\usr\bin\bash.exe", "bench/cold-visit/jfr.sh",
                            f"target/cv/out/r{r}/{variant}.jfr", str(cutoff_ms), "--top=30"], stdout=f, stderr=subprocess.STDOUT, check=False)
    text = open(out).read()

    def section(title):
        m = re.search(r"^" + re.escape(title) + r"\n((?:\s+\d+\s+[\d.]+%\s+.*\n)+)", text, re.M)
        rows = {}
        if m:
            for line in m.group(1).splitlines():
                n, _, key = line.split(None, 2)
                rows[key.strip()] = int(n)
        return rows

    kinds = section("phase / leaf kind")
    frames = section("execution: the nearest interpreter frame [its type]")
    guest = section("execution: the guest frame's kind (leaf kind)")
    total = int(re.search(r"up to the cutoff: (\d+);", text).group(1))
    hc = re.search(r"host compilations up to the cutoff: (\d+), (\d+) ms", text)
    deopts = int(re.search(r"deoptimizations up to the cutoff: (\d+)", text).group(1))
    t4 = re.search(r"tier-4 continueAt installed for ([\d.]+) s", text)
    ex = {k.split(" / ")[1]: v for k, v in kinds.items() if k.startswith("execution / ")}
    exec_total = sum(ex.values())
    interp = sum(v for k, v in frames.items() if "[Interpreted]" in k)
    inlined_bc = sum(v for k, v in frames.items() if k.startswith("bytecode") and "[Inlined]" in k)
    return {"samples": total, "exec": exec_total, "hs": ex.get("hotspot-interpreted", 0),
            "native": ex.get("native", 0),
            "truffle": sum(v for k, v in guest.items() if k.startswith("truffle-compiled")
                           and "leaf hotspot-interpreted" not in k and "leaf native" not in k),
            "guest_interp": interp, "bc_inlined": inlined_bc,
            "continueat_hs": sum(v for k, v in frames.items() if k.startswith("bytecode continueAt") and "[Interpreted]" in k),
            "continueat_all": sum(v for k, v in frames.items() if k.startswith("bytecode continueAt")),
            "host_compile_ms": int(hc.group(2)) if hc else 0, "deopts": deopts,
            "tier4": float(t4.group(1)) if t4 else 0.0,
            "parse_s": sum(v for k, v in kinds.items() if k.startswith("parse")),
            "translate_s": sum(v for k, v in kinds.items() if k.startswith("translate"))}


def run(variant, r):
    path = f"{RUNS}/jvm-java.{variant}-{r}.json"
    client = json.load(open(path))
    v = visits_of(f"{RUNS}/jvm-java.{variant}-{r}.server.log")[0]
    cut = int(client["cold"]["t"] * 1000)
    j = jfr_split(variant, r, cut)
    parse = v["read"] + v["parse"] + v["body"]
    translate = v["file_translate"] + v["translate"]
    other = v["include"] + v["native"] + v["native_compile"] + v["context"]
    exe = v["execution"]
    off_cpu = max(v["wall"] - v["cpu"], 0)
    on = max(exe - off_cpu, 0)
    share = lambda n: on * n / j["exec"] if j["exec"] else 0
    return {
        "wait": client["cold"]["wait"], "wall": v["wall"], "cpu": v["cpu"], "parse": parse, "translate": translate,
        "other": other, "execution": exe, "off_cpu": off_cpu,
        "exec_hs": share(j["hs"]), "exec_truffle": share(j["truffle"]),
        "exec_host": share(j["exec"] - j["hs"] - j["truffle"] - j["native"]), "exec_native": share(j["native"]),
        "gc": v["gc_pause"],
        "off_jit": v.get("off_jit", 0), "off_truffle": v.get("off_truffle", 0), "off_gc": v.get("off_gc", 0),
        "process": v.get("off_process", 0),
        "continueat_hs": 100 * j["continueat_hs"] / j["exec"] if j["exec"] else 0,
        "continueat_all": 100 * j["continueat_all"] / j["exec"] if j["exec"] else 0, "host_compile_s": j["host_compile_ms"] / 1000, "deopts": j["deopts"], "tier4": j["tier4"],
        "peak": client["memory"]["cold"]["peak"]}


ROWS = [("cold visit wait (client)", "wait"), ("requests' wall", "wall"), ("  parse (read, file parse, body read)", "parse"),
        ("  translate (file and bodies)", "translate"), ("  includes, native calls, context", "other"),
        ("  execution", "execution"), ("    off-CPU (I/O, database, waiting for a CPU)", "off_cpu"),
        ("    on-CPU: HotSpot's interpreter", "exec_hs"),
        ("    on-CPU: host-compiled Java (the interpreter, built-ins, runtime)", "exec_host"),
        ("    on-CPU: Truffle-compiled PHP", "exec_truffle"), ("    on-CPU: native", "exec_native"),
        ("    execution samples in continueAt, %", "continueat_all"),
        ("    execution samples in continueAt run by HotSpot's interpreter, %", "continueat_hs"),
        ("  GC pauses (inside the lines above)", "gc"), ("requests' CPU", "cpu"),
        ("off-thread CPU-s: HotSpot's compilers (JVM JIT)", "off_jit"),
        ("off-thread CPU-s: Truffle's compilers", "off_truffle"), ("off-thread CPU-s: GC and VM", "off_gc"),
        ("process CPU-s", "process"), ("host compilations on compiler threads, s (JFR)", "host_compile_s"),
        ("deoptimizations (JFR)", "deopts"),
        ("s of the visit with a tier-4 (libgraal) continueAt installed", "tier4"), ("memory.peak after the cold visit, MB", "peak")]


def main():
    variants = sys.argv[1:]
    data = {}
    for v in variants:
        rounds = sorted(int(m.group(1)) for f in os.listdir(RUNS)
                        if (m := re.fullmatch(re.escape(f"jvm-java.{v}-") + r"(\d+)\.json", f))
                        and os.path.exists(f"target/cv/out/r{m.group(1)}/{v}.jfr"))
        data[v] = [run(v, r) for r in rounds]
    print("| s, cold visit | " + " | ".join(f"{v} ({len(data[v])} rounds)" for v in variants) + " |")
    print("|---|" + "---|" * len(variants))
    for name, key in ROWS:
        cells = []
        for v in variants:
            xs = [x[key] for x in data[v]]
            if not xs:
                cells.append("-")
                continue
            m = statistics.median(xs)
            ref = statistics.median([x["wall"] for x in data[v]])
            fmt = "{:.0f}" if key in ("deopts", "peak") else "{:.2f}"
            share = f", {m / ref:.0%}" if key in ("parse", "translate", "other", "execution", "off_cpu", "exec_hs",
                                                   "exec_host", "exec_truffle", "exec_native") else ""
            cells.append(f"{fmt.format(m)} ({fmt.format(min(xs))}-{fmt.format(max(xs))}){share}")
        print(f"| {name} | " + " | ".join(cells) + " |")


if __name__ == "__main__":
    main()
