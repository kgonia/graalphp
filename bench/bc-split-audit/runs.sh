#!/usr/bin/env bash
# The measurements of wiki/bytecode-dsl.md's "Which splits pay" (2026-11-03): one build (target/classes-BUILD, default
# s27: bc-split-fix's 6b0988a), three variants:
#   on   bytecode, its roots split (the default since #27)
#   off  bytecode, -Dpolyglot.engine.Splitting=false
#   tree -Dpolyglot.php.Interpreter=tree
#   fix  on, the build with ElementReadNode reporting no polymorphism (target/classes-fix)
# - stack NAME NET PORT DBCPUS SEEDS: a database (MariaDB, on DBCPUS) and a site volume of its own (NAME-site: the
#   site, the jars, every target/classes-*), seeded from SEEDS (a directory with woo.sh's seed.sql, seed-load.sql and
#   gate2-seed.sql). Stacks: ba-d (diagnosis, server on 6-13, database on 12-13), ba-t (the WooCommerce A/B, server on
#   14-21, database on 22-23), ba-g (Gate 2's visits, native.sh measure: server on 14-21, database on 22-23).
# - diag VARIANT TAG SECONDS [g2]: woo.sh diag on ba-d (CPUs 6-13; g2: Gate 2's database and pages instead of the
#   store), with TraceCompilation, TraceSplitting, SplittingTraceEvents, TraceSplittingSummary and TraceInlining:
#   target/ba-d/diag/TAG/engine.log, read by split_audit.py.
# - ab ROUNDS VARIANT...: woo.sh load jvm 16 on ba-t (300 s warm-up at 16 users half buying, then 120 s at 16 users
#   every visitor buying), the variants interleaved, the order reversed every round; woo.sh load takes the CPU lock
#   of 14-21 per server. Each server's compilations logged (TraceCompilation) to target/ba-t/abl/TAG/engine.log.
# - g2 ROUNDS VISITS VARIANT...: native.sh measure 1 VISITS 1 on ba-g (jvm-java.VARIANT) per round, the order
#   reversed every round, holding the CPU lock; compilations logged to target/ba-g/out/rROUND/engine-VARIANT.log.
# - gate1 ITERATIONS ROUNDS: Gate 1's kernels, BUILD, fix and BUILD without splitting (bench/gate1/ab.sh).
# - watch: beside ab and g2, the servers' compiler-thread CPU and code cache (jcmd), target/STACK/watch/ID.txt;
#   stops when target/ba-watch.stop exists.
# Every server waits for a memlock reservation of its own.
# Usage: bench/bc-split-audit/runs.sh stack ... | diag ... | ab ... | g2 ...
set -euo pipefail
export PATH=/usr/bin:/bin:/mingw64/bin:$PATH
cd "$(dirname "$0")/../.."
MEMLOCK="python C:/Users/krzgo/Projects/memory-lock/memlock.py"
hold() { # KIND GB TTL-minutes NOTE: a reservation, or stop
  $MEMLOCK acquire claude-wordpress-bcaudit-$1 --gb "$2" --wait --ttl "$3" --note "$4" \
    || { echo "no memlock reservation: stopping"; exit 3; }
}
release() { $MEMLOCK release claude-wordpress-bcaudit-$1 > /dev/null 2>&1 || true; }
CPU_LOCK=/c/Users/krzgo/Projects/.cpu-14-21.lock
BUILD=${BUILD:-s27}
# the classes of a variant: fix (BUILD=s27's tree with ElementReadNode reporting no polymorphism) is a build of its
# own, target/classes-fix, run as on
classes() { case $1 in fix) echo target/classes-fix ;; *) echo target/classes-$BUILD ;; esac; }
vopts() {
  case $1 in
    on|fix) echo ;;
    off) echo -Dpolyglot.engine.Splitting=false ;;
    tree) echo -Dpolyglot.php.Interpreter=tree ;;
    *) echo "unknown variant $1" >&2; exit 2 ;;
  esac
}
stack_env() {
  case $1 in
    ba-d) export WPN_NAME=ba-d WPN_NET=172.31.101 WPN_DB_PORT=3371 WPN_DB_CPUS=12-13 ;;
    ba-t) export WPN_NAME=ba-t WPN_NET=172.31.102 WPN_DB_PORT=3372 WPN_DB_CPUS=22-23 ;;
    ba-g) export WPN_NAME=ba-g WPN_NET=172.31.103 WPN_DB_PORT=3373 WPN_DB_CPUS=22-23 ;;
  esac
  export WPN_AUX_CPUS=6-13
}
TRACES="-Dpolyglot.engine.TraceCompilation=true -Dpolyglot.engine.CompilationFailureAction=Print"
TRACES+=" -Dpolyglot.engine.TraceSplitting=true -Dpolyglot.engine.SplittingTraceEvents=true"
TRACES+=" -Dpolyglot.engine.TraceSplittingSummary=true -Dpolyglot.compiler.TraceInlining=true"
case ${1:-} in
  stack)
    stack_env $2
    mkdir -p target/$2
    cp $6/seed.sql target/$2/src-seed.sql
    for f in seed-load gate2-seed; do [ ! -f $6/$f.sql ] || cp $6/$f.sql target/$2/$f.sql; done
    WPN_DB_DUMP=target/$2/src-seed.sql bash tests/gate2/native.sh db
    bash tests/gate2/native.sh volume
    # every build snapshot, beside target/classes (native.sh volume copies only that)
    MSYS_NO_PATHCONV=1 docker run --rm --cpuset-cpus 6-13 -v $2-site:/srv -v "$(pwd -W):/w:ro" alpine:3 sh -c '
      for d in /w/target/classes-*; do rm -rf /srv/target/$(basename $d); cp -r $d /srv/target/; done; ls /srv/target' ;;
  diag)
    stack_env ba-d
    v=$2 tag=$3 seconds=$4
    hold diag 6 $((seconds / 60 + 30)) "wordpress-bcsplit-audit: diagnosis server $tag (CPUs 6-13)"
    seed=
    [ "${5:-}" != g2 ] || seed=gate2-seed
    DEV=1 DEV_CLASSES=$(classes $v) DIAG_SEED=$seed JAVA_OPTS="$TRACES $(vopts $v)" \
      bash tests/gate2/woo.sh diag $tag ${seconds}s
    release diag ;;
  ab)
    stack_env ba-t
    rounds=$2; shift 2
    variants=("$@")
    for r in $(seq "$rounds"); do
      if [ $((r % 2)) = 1 ]; then order="${variants[*]}"; else order=$(printf '%s\n' "${variants[@]}" | tac | tr '\n' ' '); fi
      for v in $order; do
        tag=$BUILD-$v-r$r
        hold ab 7 30 "wordpress-bcsplit-audit: WooCommerce load A/B, $tag (JVM server on 14-21)"
        mkdir -p target/ba-t/abl/$tag
        DEV=1 DEV_CLASSES=$(classes $v) WC_DOCKER_ARGS="-v $(pwd -W)/target/ba-t/abl/$tag:/out" \
          JAVA_OPTS="$(vopts $v) -Dpolyglot.engine.TraceCompilation=true -Dpolyglot.log.file=/out/engine.log" \
          MIXES=1 WARM=300s DURATION=120s bash tests/gate2/woo.sh load jvm 16 $tag 16
        release ab
      done
    done ;;
  g2)
    stack_env ba-g
    rounds=$2 visits=$3; shift 3
    variants=("$@")
    hold g2 6 $((rounds * $# * 10 + 30)) "wordpress-bcsplit-audit: Gate 2 visits on CPUs 14-21 (CPU lock held)"
    until mkdir $CPU_LOCK 2> /dev/null; do echo "CPUs 14-21 taken: $(cat $CPU_LOCK/* 2> /dev/null | head -1)"; sleep 60; done
    echo "claude-wordpress-bcaudit (wordpress-bcsplit-audit): Gate 2 visits ${variants[*]}, since $(date '+%F %T')" > $CPU_LOCK/note
    trap 'rm -rf $CPU_LOCK; release g2' EXIT
    # one run at a time (measure 1 VISITS 1 VARIANT), renamed to its round at once: each round's servers log their
    # compilations to a directory of their own. RUNS (VARIANT:ROUND ...) replaces the interleaved order, to finish
    # a series that stopped.
    runs=${RUNS:-}
    if [ -z "$runs" ]; then
      for r in $(seq "$rounds"); do
        if [ $((r % 2)) = 1 ]; then order="${variants[*]}"; else order=$(printf '%s
' "${variants[@]}" | tac | tr '
' ' '); fi
        for v in $order; do runs+=" $v:$r"; done
      done
    fi
    for run in $runs; do
      v=${run%:*} r=${run#*:}
      export CLASSES_$v=$(classes $v)
      export JAVA_OPTS_$v="$(vopts $v) -Dpolyglot.engine.TraceCompilation=true -Dpolyglot.log.file=/out/engine-$v.log"
      mkdir -p target/ba-g/out/r$r
      OUT_DIR=target/ba-g/out/r$r CPU_LOCK_HELD=1 HOST_QUIET=${HOST_QUIET:-40} bash tests/gate2/native.sh measure 1 "$visits" 1 jvm-java.$v
      # measure names the run VARIANT-1 and deletes that name's files first: parked as VARIANT-1ROUND until the end
      for f in target/ba-g/runs/jvm-java.$v-1.*; do mv "$f" "${f/jvm-java.$v-1./jvm-java.$v-1$r.}"; done
    done
    for f in target/ba-g/runs/jvm-java.*-1[0-9].*; do mv "$f" "$(echo "$f" | sed -E 's/-1([0-9])\./-\1./')"; done ;;
  gate1)
    # Gate 1's kernels (bench/gate1/ab.sh all ITERATIONS ROUNDS): on, fix and off, interleaved, under the CPU lock
    hold gate1 5 240 "wordpress-bcsplit-audit: Gate 1 A/B on CPUs 14-21 (CPU lock held)"
    until mkdir $CPU_LOCK 2> /dev/null; do echo "CPUs 14-21 taken: $(cat $CPU_LOCK/* 2> /dev/null | head -1)"; sleep 60; done
    echo "claude-wordpress-bcaudit (wordpress-bcsplit-audit): Gate 1 A/B, since $(date '+%F %T')" > $CPU_LOCK/note
    trap 'rm -rf $CPU_LOCK; release gate1' EXIT
    AB_OUT=target/ba-gate1 bash bench/gate1/ab.sh all $2 $3 $BUILD fix $BUILD@-Dpolyglot.engine.Splitting=false ;;
  watch)
    # every 30 s, for each running server of ba-t and ba-g: its compiler threads' CPU seconds and (every other
    # time) its code cache (jcmd Compiler.codecache), the last snapshot kept per container ID in target/STACK/watch/
    i=0
    while [ ! -e target/ba-watch.stop ]; do
      for c in $(docker ps --format '{{.Names}}' | grep -E '^ba-[tg]-jvm' || true); do
        stack=${c%%-jvm*}; id=$(docker inspect -f '{{.Id}}' $c 2> /dev/null) || continue
        mkdir -p target/$stack/watch
        cc=
        [ $((i % 2)) = 1 ] || cc=1
        MSYS_NO_PATHCONV=1 docker exec -e CC=$cc $c sh -c '
          echo "## time $(date +%s)"
          echo "## thread cpu (name count seconds)"
          for t in /proc/1/task/*; do echo "$(cat $t/comm 2>/dev/null | tr -d "0-9 " )|$(cut -d")" -f2 $t/stat 2>/dev/null | cut -d" " -f13,14)"; done             | awk -F"|" "{split(\$2,c,\" \"); n[\$1]++; s[\$1]+=c[1]+c[2]} END {for (k in n) printf \"%s %d %.1f\n\", k, n[k], s[k]/100}"
          [ -z "$CC" ] || { echo "## codecache"; $GRAALVM_HOME/bin/jcmd 1 Compiler.codecache; }'           > target/$stack/watch/$id.tmp 2> /dev/null || continue
        if [ -n "$cc" ]; then mv target/$stack/watch/$id.tmp target/$stack/watch/$id.txt
        else mv target/$stack/watch/$id.tmp target/$stack/watch/$id.threads.txt; fi
      done
      i=$((i + 1))
      sleep 30
    done ;;
  *) sed -n 2,24p "$0"; exit 2 ;;
esac
