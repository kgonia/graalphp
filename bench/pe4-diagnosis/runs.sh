#!/usr/bin/env bash
# The timed runs of wiki/pe-round4-diagnosis.md, in order, each server's life under the CPU lock of 14-21
# (woo.sh load / memrun take and release it):
# - ab: question 4, WooCommerce before the merge of #19 and #20 (866e156, target/classes-before) against after it
#   (b6ca11c, target/classes): the JVM, 16 threads, 300 s warm-up, then 120 s at 16 users, every visitor buying;
#   3 rounds, the order reversed in round 2.
# - mem: question 3, memrun (12 min at 16 users, half buying, 16 threads) of the merged build: baseline, libgraal's
#   heap capped at 2 GB and 1 GB per isolate (-Djdk.graal.internal.MaxHeapSize), one Truffle compiler thread;
#   interleaved, the order reversed in round 2, baseline and one thread a third time. Every run logs Truffle's
#   compilations and libgraal's collections alike.
# Usage: bench/pe4-diagnosis/runs.sh ab|mem
set -euo pipefail
cd "$(dirname "$0")/../.."
export WPN_NAME=pe4 WPN_NET=172.31.81 WPN_DB_PORT=3351 DEV=1
woo() { bash tests/gate2/woo.sh "$@"; }
case ${1:-} in
  ab)
    for r in 1 2 3; do
      if [ $r = 2 ]; then order="after before"; else order="before after"; fi
      for v in $order; do
        if [ $v = before ]; then classes=target/classes-before; else classes=target/classes; fi
        DEV_CLASSES=$classes MIXES=1 WARM=300s DURATION=120s woo load jvm 16 ab-$v-r$r 16
      done
    done ;;
  mem)
    log="-XX:NativeMemoryTracking=summary -Dpolyglot.engine.TraceCompilation=true"
    log+=" -Dpolyglot.engine.CompilationFailureAction=Print -Dpolyglot.log.file=/out/engine.log"
    log+=" -Djdk.graal.internal.PrintGC=true"
    declare -A opts=([base]="" [cap2g]="-Djdk.graal.internal.MaxHeapSize=2g"
                     [cap1g]="-Djdk.graal.internal.MaxHeapSize=1g" [ct1]="-Dpolyglot.engine.CompilerThreads=1")
    for run in base-r1 ct1-r1 cap2g-r1 cap1g-r1 cap1g-r2 cap2g-r2 ct1-r2 base-r2 base-r3 ct1-r3; do
      DEV_CLASSES=target/classes SNAP=60 JAVA_OPTS="$log ${opts[${run%-r*}]}" woo memrun jvm 16 m-$run 16 720s
    done ;;
  *) sed -n 2,13p "$0"; exit 2 ;;
esac
