#!/usr/bin/env bash
# Roadmap step 4 (wiki/roadmap.md): Gate 1 A/Bs of class directories target/classes-X (bench/gate1/ab.sh), a
# variant X@OPTS with JVM options. Each hold takes a memlock reservation and the CPU lock
# (C:\Users\krzgo\Projects\.cpu-14-21.lock), runs ab.sh with 2 rounds (forward, then reversed) and lets both go, so
# other sessions' timed runs can go between. Beside each hold, the host's per-LP load (lpload.ps1, run off 14-21:
# the P-cores are where the VM's busy vCPUs land, wiki/cold-visit.md) goes to OUT/hN.lpload.
# Usage: bench/perf-step4/runs.sh gate1 HOLDS ITERATIONS VARIANT...   (OUT: the runs' directory, default
#        target/p4-gate1; KERNELS: a kernel list, default all; START: the first hold's number)
set -euo pipefail
export PATH=/usr/bin:/bin:/mingw64/bin:$PATH
cd "$(dirname "$0")/../.."
MEMLOCK="python C:/Users/krzgo/Projects/memory-lock/memlock.py"
CPU_LOCK=/c/Users/krzgo/Projects/.cpu-14-21.lock
lock() {
  until mkdir $CPU_LOCK 2> /dev/null; do sleep 2; done
  echo "claude-wordpress-perf4 (wordpress-perf4): $*, since $(date '+%F %T')" > $CPU_LOCK/note
}
unlock() { rm -rf $CPU_LOCK; }
case ${1:-} in
  gate1)
    holds=$2 iterations=$3; shift 3
    out=${OUT:-target/p4-gate1}
    mkdir -p $out/all
    for h in $(seq "${START:-1}" "$holds"); do
      $MEMLOCK acquire claude-wordpress-perf4-gate1 --gb 4 --wait --ttl 120 \
        --note "wordpress-perf4: Gate 1 A/B, hold $h (CPUs 14-21, CPU lock)" > /dev/null || { echo "no memlock"; exit 3; }
      lock "Gate 1 A/B hold $h of $*"
      rm -f $out/h$h.stop
      cmd //c "$(cygpath -w bench/perf-step4/lpload.cmd)" "$(cygpath -w $out/h$h.stop)" "$(cygpath -w $out/h$h.lpload)" &
      lp=$!
      AB_OUT=$out/h$h bash bench/gate1/ab.sh ${KERNELS:-all} "$iterations" 2 "$@" > $out/h$h.ab.txt 2>&1 || true
      unlock
      $MEMLOCK release claude-wordpress-perf4-gate1 > /dev/null || true
      touch $out/h$h.stop
      wait $lp 2> /dev/null || true
      tail -1 $out/h$h.lpload
      for f in $out/h$h/*-[12].txt; do
        b=$(basename $f .txt); i=${b%-*} r=${b#*-}
        cp $f $out/all/$i-$(((h - 1) * 2 + r)).txt
      done
    done
    python bench/gate1/ab.py $out/all "$@" ;;
  *) sed -n 2,9p "$0"; exit 2 ;;
esac
