#!/usr/bin/env bash
# The tables of wiki/perf-check.md from bench/perf-check/runs.sh's runs, into bench/perf-check/*.md.
# Usage: bench/perf-check/report.sh
set -euo pipefail
export PATH=/usr/bin:/bin:/mingw64/bin:$PATH
cd "$(dirname "$0")/../.."
out=bench/perf-check
all="base p38 p39 p40 p41 p42 main fix"
{
  echo "# Gate 1, base (4214f6b) against main (9ae85cb): runs.sh gate1 4 400 base main"
  echo
  python -I bench/perf-check/report.py gate1 target/pc-gate1/all target/pc-gate1/php.txt base main
  echo
  echo "# The first series, base against b29eafd (main before #35, p42)"
  echo
  python -I bench/perf-check/report.py gate1 target/pc-gate1-pre35/all target/pc-gate1/php.txt base p42
  echo
  echo "# The bisection: kses and hooks, every merge commit (KERNELS=kses,hooks runs.sh gate1 3 300 ...)"
  echo
  python -I bench/perf-check/report.py gate1 target/pc-gate1-bisect/all target/pc-gate1/php.txt base p33 p38 p41 \
    p36 p39 p34 p37 p40 p42 main fix
} > $out/gate1.md
{
  echo "# Gate 2: base (4214f6b), main (9ae85cb), p42 (b29eafd, main before #35), the bisection's builds, fix"
  echo
  echo "runs.sh g2: rounds 1-3 base and p42; 4-6 base and main; 7 base, p38, p39, p40, p42, main; 9-10 base, p38,"
  echo "p41, main, fix; 11-12 base, main, fix (the host quiet). Round 8 was not run."
  echo
  echo '```'
  python -I tests/gate2/visits_report.py target/pc-g/runs jvm-java.base $(for v in $all; do printf 'jvm-java.%s ' $v; done)
  echo '```'
  echo
  python -I bench/perf-check/report.py threads target/pc-g g2 $all
  echo
  echo "## Every run, and the builds against base at the same host load"
  echo
  python -I bench/perf-check/report.py fit target/pc-g $all
  echo
  echo "## Memory (mem_report.py)"
  echo
  python -I bench/split-policy/mem_report.py g2 target/pc-g $all
  echo
  echo "## Host load per run (median of the samples every 10 s, %; the server's 8 CPUs are about a third)"
  echo
  for f in target/pc-g/runs/*.host; do
    echo "- $(basename $f .host): $(sort -n $f | awk '{a[NR] = $1} END {print a[int((NR + 1) / 2)]}')"
  done
} > $out/gate2.md
{
  echo "# WooCommerce load: runs.sh woo (300 s warm-up, 120 s of 16 users every visitor buying)"
  echo
  echo "Rounds 1-3 base and main (the host loaded), 4-6 main and fix, 7-8 base and main (the host quiet)."
  echo
  python -I bench/split-policy/ab_report.py woo target/pc-t base main fix
  echo
  echo "## Per run"
  echo
  for f in target/pc-t/load/*-r[0-9]-1-16.k6.json; do
    echo "- $(python -I tests/gate2/woo_report.py line ${f%.k6.json} | cut -d';' -f1)"
  done
  echo
  python -I bench/split-policy/mem_report.py woo target/pc-t base main fix
  echo
  python -I bench/split-policy/mem_report.py woo target/pc-t base main fix --runs
  echo
  python -I bench/perf-check/report.py threads target/pc-t woo base main fix
  echo
  echo "## Host load before each server (hostload.ps1)"
  echo
  grep ' woo ' target/hostload.log | sed 's/^/- /'
} > $out/woo.md
grep -h 'hold\| g2 ' target/hostload.log | sed 's/^/- /' > $out/hostload.md
ls -la $out
