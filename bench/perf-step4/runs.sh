#!/usr/bin/env bash
# Roadmap step 4 (wiki/roadmap.md): Gate 1 A/Bs of class directories target/classes-X (bench/gate1/ab.sh), a
# variant X@OPTS with JVM options. Each hold takes a memlock reservation and the CPU lock
# (C:\Users\krzgo\Projects\.cpu-14-21.lock), runs ab.sh with 2 rounds (forward, then reversed) and lets both go, so
# other sessions' timed runs can go between. Beside each hold, the host's per-LP load (lpload.ps1, run off 14-21:
# the P-cores are where the VM's busy vCPUs land, wiki/cold-visit.md) goes to OUT/hN.lpload.
# - g2 ROUNDS VISITS VARIANT...: Gate 2's paced visits from a cold server (tests/gate2/native.sh measure, one run per
#   lock, the order reversed every round) on the p4-g stack (bench/perf-step4/g2env.sh; classes-VARIANT copied
#   into its volume), runs jvm-java.VARIANT-ROUND in target/p4-g/runs, the host's per-LP load beside each.
# Usage: bench/perf-step4/runs.sh gate1 HOLDS ITERATIONS VARIANT... | g2 ROUNDS VISITS VARIANT...
#        (OUT: gate1's runs directory, default target/p4-gate1; KERNELS: a kernel list, default all; START: the
#        first hold's or round's number)
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
      cmd //c "$(cygpath -w bench/perf-step4/lpload.cmd)" "$(cygpath -w $out/h$h.stop)" \
          "$(cygpath -w $out/h$h.lpload)" &
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
  g2)
    source bench/perf-step4/g2env.sh
    rounds=$2 visits=$3; shift 3
    variants=("$@")
    runs=target/p4-g/runs
    mkdir -p $runs
    for r in $(seq "${START:-1}" "$rounds"); do
      if [ $((r % 2)) = 1 ]; then order="${variants[*]}"; else order=$(printf '%s
' "${variants[@]}" | tac | tr '
' ' '); fi
      for v in $order; do
        $MEMLOCK acquire claude-wordpress-perf4-g2 --gb 6 --wait --ttl 60 \
          --note "wordpress-perf4: Gate 2 visits jvm-java.$v-$r (CPUs 14-21, CPU lock)" > /dev/null || { echo "no memlock"; exit 3; }
        export CLASSES_$v=target/classes-$v
        # measure names the run VARIANT-1: renamed to its round at once
        rm -f $runs/jvm-java.$v-1.*
        lock "Gate 2 visits jvm-java.$v round $r"
        rm -f $runs/jvm-java.$v-x$r.stop
        cmd //c "$(cygpath -w bench/perf-step4/lpload.cmd)" "$(cygpath -w $runs/jvm-java.$v-x$r.stop)" \
          "$(cygpath -w $runs/jvm-java.$v-x$r.lpload)" &
        lp=$!
        CPU_LOCK_HELD=1 HOST_QUIET=101 HOST_LOADED=101 bash tests/gate2/native.sh measure 1 "$visits" 1 jvm-java.$v || true
        unlock
        $MEMLOCK release claude-wordpress-perf4-g2 > /dev/null || true
        touch $runs/jvm-java.$v-x$r.stop
        wait $lp 2> /dev/null || true
        for f in $runs/jvm-java.$v-1.*; do mv "$f" "${f/jvm-java.$v-1./jvm-java.$v-x$r.}"; done
        tail -1 $runs/jvm-java.$v-x$r.lpload
      done
    done
    for f in $runs/jvm-java.*-x[0-9]*; do [ ! -e "$f" ] || mv "$f" "${f/-x/-}"; done ;;
  *) sed -n 2,13p "$0"; exit 2 ;;
esac
