#!/usr/bin/env bash
# Roadmap step 5.1 (wiki/destructors.md, "Prototype and thresholds"): the counting-only prototype against main
# (851bb6d). Builds are class directories target/classes-X (target/classes-X.commit: the commit): base (main) and
# proto (the prototype). Every timed run is on CPUs 14-21 under the CPU lock (C:\Users\krzgo\Projects\.cpu-14-21.lock)
# and a memory reservation (memory-lock), taken per hold and let go after it, as bench/perf-check/runs.sh does.
# - gate1 HOLDS ITERATIONS VARIANT...: bench/gate1/ab.sh all ITERATIONS 2 per hold (forward, then reversed), HOLDS
#   times; the runs merged into target/rc-gate1/all (OUT: another directory, KERNELS: another kernel list).
# - ir ITERATIONS VARIANT...: Gate 1's kernels once per variant with the compilations' details (not timed, CPUs
#   6-13), the engine logs in target/rc-ir/VARIANT.log, compared with bench/bc-compile-cost/compare.py.
# - php ITERATIONS: Gate 1's kernels once on PHP 8.5 (CPUs 6-13, not timed against Truffle PHP): the checksums,
#   target/rc-gate1/php.txt.
# - report VARIANT...: Gate 1's tables (bench/perf-check/report.py), checksums against PHP's.
# QUIET=N (gate1): with the CPU lock taken, wait until the host's load is under N percent, at most QUIET_MAX seconds.
# Usage: bench/destructors/runs.sh gate1 HOLDS ITERATIONS VARIANT... | ir ITERATIONS VARIANT... | php ITERATIONS
#        | report VARIANT...
set -euo pipefail
export PATH=/usr/bin:/bin:/mingw64/bin:$PATH
cd "$(dirname "$0")/../.."
MEMLOCK="python C:/Users/krzgo/Projects/memory-lock/memlock.py"
hold() { # KIND GB TTL-minutes NOTE: a reservation, or stop
  $MEMLOCK acquire claude-wordpress-destructors-b-$1 --gb "$2" --wait --ttl "$3" --note "$4" \
    || { echo "no memlock reservation: stopping"; exit 3; }
}
release() { $MEMLOCK release claude-wordpress-destructors-b-$1 > /dev/null 2>&1 || true; }
CPU_LOCK=/c/Users/krzgo/Projects/.cpu-14-21.lock
lock() {
  until mkdir $CPU_LOCK 2> /dev/null; do [ -n "${said:-}" ] || echo "CPUs 14-21 taken: $(cat $CPU_LOCK/* 2> /dev/null | head -1)"; said=1; sleep 2; done; said=
  echo "claude-wordpress-destructors-b (wordpress-destructors-b): $*, since $(date '+%F %T')" > $CPU_LOCK/note
}
unlock() { rm -rf $CPU_LOCK; }
host_cpu() {
  powershell -NoProfile -Command \
    "[math]::Round((Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average).Average)" \
    2> /dev/null | tr -d '\r'
}
quiet() {
  [ -n "${QUIET:-}" ] || return 0
  local ok=0 load end=$(($(date +%s) + ${QUIET_MAX:-900}))
  while [ $ok -lt 3 ]; do
    [ "$(date +%s)" -lt $end ] || { echo "the host stayed loaded (${load:-?}%): going on"; return 0; }
    load=$(host_cpu)
    if [ "${load:-100}" -lt "$QUIET" ]; then ok=$((ok + 1)); else ok=0; fi
    sleep 10
  done
}
case ${1:-} in
  gate1)
    holds=$2 iterations=$3; shift 3
    out=${OUT:-target/rc-gate1}
    mkdir -p $out/all
    for h in $(seq "${START:-1}" "$holds"); do
      hold gate1 5 120 "wordpress-destructors-b: Gate 1 A/B, hold $h (CPUs 14-21, CPU lock)"
      lock "Gate 1 A/B hold $h of $*"
      quiet
      echo "$(date '+%F %T') gate1 hold $h: host CPU $(host_cpu)%" | tee -a $out/hostload.log
      AB_OUT=$out/h$h bash bench/gate1/ab.sh ${KERNELS:-all} "$iterations" 2 "$@" || true
      unlock
      release gate1
      for f in $out/h$h/*-[12].txt; do
        b=$(basename $f .txt); i=${b%-*} r=${b#*-}
        cp $f $out/all/$i-$(((h - 1) * 2 + r)).txt
      done
    done
    python bench/gate1/ab.py $out/all "$@" ;;
  ir)
    iterations=$2; shift 2
    mkdir -p target/rc-ir
    for v in "$@"; do
      CPUSET=6-13 PEAK_MEMORY=1 PHP_CLASSES=target/classes-$v \
        JAVA_OPTS="-Dpolyglot.engine.TraceCompilationDetails=true -Dpolyglot.log.file=/w/target/rc-ir/$v.log" \
        bash bench/gate1/truffle.sh bench/gate1/run.php all "$iterations" > target/rc-ir/$v.txt 2>&1
    done
    python bench/bc-compile-cost/compare.py target/rc-ir/$1.log target/rc-ir/$2.log ;;
  php)
    hold php 2 30 "wordpress-destructors-b: Gate 1 on PHP 8.5 (CPUs 6-13)"
    mkdir -p target/rc-gate1
    CPUSET=6-13 PEAK_MEMORY=1 bash bench/gate1/php.sh php bench/gate1/run.php all "$2" > target/rc-gate1/php.txt 2>&1
    release php ;;
  report)
    shift
    python -I bench/perf-check/report.py gate1 target/rc-gate1/all target/rc-gate1/php.txt "$@" ;;
  *) sed -n 2,17p "$0"; exit 2 ;;
esac
