#!/usr/bin/env bash
# The measurements of wiki/pe-round4.md. Builds: target/classes-base (b580a2a, round 4's diagnosis, no change to the
# interpreter since b6ca11c), classes-t3 (defined() compiled in place), classes-t2 (and the dispatch per method),
# classes-t1 (and run-once code not compiled: all three). Every timed run holds the CPU lock of 14-21 (woo.sh load
# and memrun take it; gate1 here) and a memlock reservation.
# - gate1: Gate 1's kernels, the four builds interleaved, the order reversed every other round (bench/gate1/ab.sh).
# - ab A B ROUNDS: woo.sh load jvm 16 of two builds (300 s warm-up at 16 users half buying, then 120 s at 16 users
#   every visitor buying), interleaved, the order reversed every round (the first server of a round won 6 of 6 in
#   the diagnosis whichever build it was); tags A-rN, B-rN.
# - mem A B ROUNDS: woo.sh memrun jvm 16 (12 min at 16 users half buying, from cold) of two builds, interleaved and
#   reversed every round, with TraceCompilation and libgraal's PrintGC: compile time by kind, failures, libgraal's
#   heap, memory.peak (pe4_report.py kinds, libgraal_gc.py, pe4_memtable.py).
# - diag BUILD TAG MODE: woo.sh diag on its own stack (p4id, CPUs 6-13, its database on 12-13), 16 users half
#   buying: MODE splits (TraceCompilation, splitting and its events and summary, statistics; 900 s) or sampler
#   (the CPU sampler after 4 minutes, 600 s, as the diagnosis' d5-sampler).
# - pages: Gate 2's WordPress pages on PHP 8.5 and on each build.
# Usage: bench/pe-round4/runs.sh gate1 | ab A B ROUNDS | mem A B ROUNDS | diag BUILD TAG MODE | pages
set -euo pipefail
cd "$(dirname "$0")/../.."
woo() { bash tests/gate2/woo.sh "$@"; }
MEMLOCK="python C:/Users/krzgo/Projects/memory-lock/memlock.py"
# a reservation of its own per kind of run (gate1, ab, mem, diag), so that a diagnosis beside a timed run holds its own
hold() { # GB TTL-minutes NOTE: a reservation, or stop
  $MEMLOCK acquire claude-wordpress-pe4impl-$1 --gb "$2" --wait --ttl "$3" --note "$4"     || { echo "no memlock reservation: stopping"; exit 3; }
}
release() { $MEMLOCK release claude-wordpress-pe4impl-$1 > /dev/null 2>&1 || true; }
CPU_LOCK=/c/Users/krzgo/Projects/.cpu-14-21.lock
case ${1:-} in
  gate1)
    hold gate1 6 240 "wordpress-pe4impl: Gate 1 A/B of 4 builds on CPUs 14-21"
    until mkdir $CPU_LOCK 2> /dev/null; do echo "CPUs 14-21 taken: $(cat $CPU_LOCK/* 2> /dev/null | head -1)"; sleep 60; done
    echo "claude-wordpress-pe4impl: Gate 1 A/B base t3 t2 t1 (bench/pe-round4/runs.sh gate1), since $(date '+%F %T')" > $CPU_LOCK/note
    trap 'rm -rf $CPU_LOCK; release gate1' EXIT
    AB_OUT=target/pe4i/gate1 bash bench/gate1/ab.sh all 400 ${ROUNDS:-4} base t3 t2 t1 ;;
  ab)
    export WPN_NAME=p4i WPN_NET=172.31.91 WPN_DB_PORT=3361 DEV=1
    a=$2 b=$3 rounds=$4
    for r in $(seq "$rounds"); do
      if [ $((r % 2)) = 1 ]; then order="$a $b"; else order="$b $a"; fi
      for v in $order; do
        hold ab 12 30 "wordpress-pe4impl: WooCommerce load A/B $a/$b, $v round $r (JVM server on 14-21)"
        DEV_CLASSES=target/classes-$v MIXES=1 WARM=300s DURATION=120s woo load jvm 16 $v-r$r 16
        release ab
      done
    done ;;
  mem)
    export WPN_NAME=p4i WPN_NET=172.31.91 WPN_DB_PORT=3361 DEV=1
    a=$2 b=$3 rounds=$4
    log="-XX:NativeMemoryTracking=summary -Dpolyglot.engine.TraceCompilation=true"
    log+=" -Dpolyglot.engine.CompilationFailureAction=Print -Dpolyglot.log.file=/out/engine.log"
    log+=" -Djdk.graal.internal.PrintGC=true"
    for r in $(seq "$rounds"); do
      if [ $((r % 2)) = 1 ]; then order="$a $b"; else order="$b $a"; fi
      for v in $order; do
        hold mem 12 30 "wordpress-pe4impl: WooCommerce memrun $a/$b, $v round $r (JVM server on 14-21)"
        DEV_CLASSES=target/classes-$v SNAP=60 JAVA_OPTS="$log" woo memrun jvm 16 m-$v-r$r 16 720s
        release mem
      done
    done ;;
  diag)
    export WPN_NAME=p4id WPN_NET=172.31.92 WPN_DB_PORT=3362 WPN_DB_CPUS=12-13 DEV=1 DEV_CLASSES=target/classes-$2
    case $4 in
      splits)
        t="-Dpolyglot.engine.TraceCompilation=true -Dpolyglot.engine.CompilationFailureAction=Print"
        t+=" -Dpolyglot.engine.TraceSplitting=true -Dpolyglot.engine.SplittingTraceEvents=true"
        t+=" -Dpolyglot.engine.TraceSplittingSummary=true -Dpolyglot.engine.CompilationStatistics=true"
        t+=" -Djdk.graal.internal.PrintGC=true"
        hold diag 12 40 "wordpress-pe4impl: WooCommerce diagnosis server $3 (CPUs 6-13)"
        JAVA_OPTS="$t" woo diag $3 900s ;;
      sampler)
        hold diag 14 30 "wordpress-pe4impl: WooCommerce sampler server $3 (CPUs 6-13)"
        JAVA_OPTS="-Xmx6g -Dpolyglot.cpusampler=true -Dpolyglot.cpusampler.Delay=240000 -Dpolyglot.cpusampler.ShowTiers=true -Dpolyglot.cpusampler.SummariseThreads=true -Dpolyglot.cpusampler.Output=histogram -Dpolyglot.cpusampler.OutputFile=/out/cpusampler.txt -Dpolyglot.engine.TraceCompilation=true" woo diag $3 600s ;;
      *) echo "diag mode: splits or sampler"; exit 2 ;;
    esac
    release diag ;;
  pages)
    # Gate 2's pages (native.sh pages, the Gate 2 seed, WooCommerce inactive) on PHP 8.5 and on each build, CPUs 6-13
    export WPN_NAME=p4g WPN_NET=172.31.93 WPN_DB_PORT=3363 CLASSES_base=target/classes-base
    export CLASSES_t3=target/classes-t3 CLASSES_t2=target/classes-t2 CLASSES_t1=target/classes-t1
    g2() { bash tests/gate2/native.sh "$@"; }
    hold pages 8 40 "wordpress-pe4impl: Gate 2 pages, one server at a time (CPUs 6-13)"
    g2 reset-db && g2 start php 6-13 8093 && g2 pages php 8093; g2 stop php
    for b in base t3 t2 t1; do
      g2 reset-db && g2 start jvm-java.$b 6-13 8093 && g2 pages jvm-java.$b 8093; g2 stop jvm-java.$b
      echo "$b: $(g2 compare php jvm-java.$b | grep -c '^same') of $(ls target/p4g/pages/php/*.html | wc -l) pages the same"
      g2 compare php jvm-java.$b | grep '^DIFF' || true
    done
    release pages ;;
  *) sed -n 2,20p "$0"; exit 2 ;;
esac
