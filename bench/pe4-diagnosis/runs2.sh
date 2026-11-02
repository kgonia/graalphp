#!/usr/bin/env bash
# The timed runs added after runs.sh's (wiki/pe-round4-diagnosis.md), each server's life under the CPU lock of
# 14-21, interleaved:
# - mem-before: question 3 and 4, the memrun of runs.sh's baseline (12 min at 16 users, half buying, 16 threads,
#   the same logging) on the build before the merge (866e156), twice, around a fourth baseline of the merged build:
#   whether the merge changed libgraal's burst; and one run with libgraal's heap capped at 256 MB per isolate
#   (where the diagnosis run cap256m-long saw the three largest compilations fail early, and no other).
# - ab-more: question 4's A/B, rounds 4-6 (as runs.sh ab), on a quieter box.
# Usage: bench/pe4-diagnosis/runs2.sh mem-before|ab-more
set -euo pipefail
cd "$(dirname "$0")/../.."
export WPN_NAME=pe4 WPN_NET=172.31.81 WPN_DB_PORT=3351 DEV=1
woo() { bash tests/gate2/woo.sh "$@"; }
log="-XX:NativeMemoryTracking=summary -Dpolyglot.engine.TraceCompilation=true"
log+=" -Dpolyglot.engine.CompilationFailureAction=Print -Dpolyglot.log.file=/out/engine.log"
log+=" -Djdk.graal.internal.PrintGC=true"
case ${1:-} in
  mem-before)
    for run in before-r1 base-r4 cap256m-r1 before-r2; do
      if [ "${run%-r*}" = before ]; then classes=target/classes-before; else classes=target/classes; fi
      extra=; [ "${run%-r*}" != cap256m ] || extra=-Djdk.graal.internal.MaxHeapSize=256m
      DEV_CLASSES=$classes SNAP=60 JAVA_OPTS="$log $extra" woo memrun jvm 16 m-$run 16 720s
    done ;;
  ab-more)
    for r in 4 5 6; do
      if [ $r = 5 ]; then order="before after"; else order="after before"; fi
      for v in $order; do
        if [ $v = before ]; then classes=target/classes-before; else classes=target/classes; fi
        DEV_CLASSES=$classes MIXES=1 WARM=300s DURATION=120s woo load jvm 16 ab-$v-r$r 16
      done
    done ;;
  *) sed -n 2,9p "$0"; exit 2 ;;
esac
