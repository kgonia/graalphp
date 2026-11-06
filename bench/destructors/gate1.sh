#!/usr/bin/env bash
# Roadmap step 5.1 (wiki/destructors-prototype.md): Gate 1 A/B of the counting-only refcount prototype against main,
# as bench/perf-check/runs.sh gate1 does it but with this session's own lock note and memory reservation names.
# Each hold: the memory reservation, then the CPU lock (C:\Users\krzgo\Projects\.cpu-14-21.lock), then
# bench/gate1/ab.sh all ITERATIONS 2 (forward, then reversed); both let go after it.
# Usage: bench/destructors/gate1.sh HOLDS ITERATIONS VARIANT...   (OUT: the runs' directory, default target/rc-gate1)
set -euo pipefail
export PATH=/usr/bin:/bin:/mingw64/bin:$PATH
cd "$(dirname "$0")/../.."
MEMLOCK="python C:/Users/krzgo/Projects/memory-lock/memlock.py"
NAME=claude-wordpress-destructors-gate1
CPU_LOCK=/c/Users/krzgo/Projects/.cpu-14-21.lock
lock() {
  until mkdir $CPU_LOCK 2> /dev/null; do [ -n "${said:-}" ] || echo "CPUs 14-21 taken: $(cat $CPU_LOCK/* 2> /dev/null | head -1)"; said=1; sleep 2; done; said=
  echo "claude-wordpress-destructors (wordpress-destructors): $*, since $(date '+%F %T')" > $CPU_LOCK/note
}
unlock() { rm -rf $CPU_LOCK; }
holds=$1 iterations=$2; shift 2
out=${OUT:-target/rc-gate1}
mkdir -p $out/all
for h in $(seq "${START:-1}" "$holds"); do
  $MEMLOCK acquire $NAME --gb 5 --wait --ttl 120 --note "wordpress-destructors: Gate 1 A/B, hold $h (CPUs 14-21)" \
    || { echo "no memlock reservation: stopping"; exit 3; }
  lock "Gate 1 A/B hold $h of $*"
  trap 'unlock; $MEMLOCK release $NAME > /dev/null 2>&1 || true' EXIT
  AB_OUT=$out/h$h bash bench/gate1/ab.sh ${KERNELS:-all} "$iterations" 2 "$@" || true
  unlock
  $MEMLOCK release $NAME > /dev/null 2>&1 || true
  trap - EXIT
  for f in $out/h$h/*-[12].txt; do
    b=$(basename $f .txt); i=${b%-*} r=${b#*-}
    cp $f $out/all/$i-$(((h - 1) * 2 + r)).txt
  done
done
python bench/gate1/ab.py $out/all "$@"
