#!/usr/bin/env bash
# The measurements of wiki/bytecode-dsl.md's "Which splits pay", on the patched processor (#28). Three builds,
# target/classes-X:
#   A  #28 as it is (7363eba): the proxied tree nodes report polymorphism on bytecode too
#   B  A and ElementReadNode reporting no polymorphism (59d9b30, bc-split-audit's 8225a73)
#   C  B and StableValueNode reporting none (03fc08b)
# Every server logs its compilations (TraceCompilation) and libgraal's collections (PrintGC); before each timed
# run the host's load outside and on CPUs 14-21 and its busiest processes go to target/hostload.log
# (hostload.ps1). The CPU lock of 14-21 is held per server (woo.sh load takes it itself), per Gate 2 run and per
# two Gate 1 rounds, so another session's timed runs can go between.
# - stack NAME: a database and a site volume of its own: ba-d (diagnosis, server on 6-13, database on 12-13),
#   ba-t (WooCommerce A/B, database on 22-23), ba-g (Gate 2, database on 22-23), seeded from target/ba-w (the store's
#   seed.sql, seed-load.sql, gate2-seed.sql) and for ba-g from target/ba-g-seed.sql.
# - diag VARIANT TAG SECONDS [g2]: woo.sh diag on ba-d (CPUs 6-13) with TraceSplitting, SplittingTraceEvents,
#   TraceSplittingSummary and TraceCompilation (target/ba-d/diag/TAG/engine.log, split_audit.py reads it).
# - ab ROUNDS VARIANT...: woo.sh load jvm 16 on ba-t (300 s warm-up, 120 s of 16 users every visitor buying),
#   interleaved, the order reversed every round; tags VARIANT-rN (START: the first N, to add rounds).
# - g2 ROUNDS VISITS VARIANT...: native.sh measure on ba-g, one run at a time (jvm-java.VARIANT-ROUND; START as
#   for ab).
# - gate1 HOLDS ITERATIONS VARIANT...: bench/gate1/ab.sh all ITERATIONS 2 per hold (forward, then reversed), HOLDS
#   times; the runs merged into target/sp-gate1/all (KERNELS: another kernel list, OUT: another directory).
# - watch: beside the timed runs, every new server container of ba-t and ba-g gets memwatch.py (its memory every
#   0.1 s, target/STACK/watch/ID.mem.json), and every 30 s its compiler threads' CPU and code cache
#   (target/STACK/watch/ID.txt); stops when target/ba-watch.stop exists.
# Usage: bench/split-policy/runs.sh stack NAME | diag ... | ab ... | g2 ... | gate1 ... | watch
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
lock() {
  until mkdir $CPU_LOCK 2> /dev/null; do echo "CPUs 14-21 taken: $(cat $CPU_LOCK/* 2> /dev/null | head -1)"; sleep 30; done
  echo "claude-wordpress-bcaudit (wordpress-splitpolicy): $*, since $(date '+%F %T')" > $CPU_LOCK/note
}
unlock() { rm -rf $CPU_LOCK; }
hostload() {
  echo "$(date '+%F %T') $*: $(powershell -NoProfile -ExecutionPolicy Bypass -File bench/split-policy/hostload.ps1 | tr -d '\r')" \
    | tee -a target/hostload.log
}
stack_env() {
  case $1 in
    ba-d) export WPN_NAME=ba-d WPN_NET=172.31.101 WPN_DB_PORT=3371 WPN_DB_CPUS=12-13 ;;
    ba-t) export WPN_NAME=ba-t WPN_NET=172.31.102 WPN_DB_PORT=3372 WPN_DB_CPUS=22-23 ;;
    ba-g) export WPN_NAME=ba-g WPN_NET=172.31.103 WPN_DB_PORT=3373 WPN_DB_CPUS=22-23 ;;
  esac
  export WPN_AUX_CPUS=6-13
}
LOGS="-Dpolyglot.engine.TraceCompilation=true -Djdk.graal.internal.PrintGC=true"
case ${1:-} in
  stack)
    stack_env $2
    mkdir -p target/$2
    cp target/ba-w/seed.sql target/$2/src-seed.sql
    cp target/ba-w/seed-load.sql target/ba-w/gate2-seed.sql target/$2/
    [ "$2" != ba-g ] || cp target/ba-g-seed.sql target/$2/src-seed.sql
    WPN_DB_DUMP=target/$2/src-seed.sql bash tests/gate2/native.sh db
    bash tests/gate2/native.sh volume
    MSYS_NO_PATHCONV=1 docker run --rm --cpuset-cpus 6-13 -v $2-site:/srv -v "$(pwd -W):/w:ro" alpine:3 sh -c '
      for d in /w/target/classes-*; do rm -rf /srv/target/$(basename $d); cp -r $d /srv/target/; done; ls /srv/target' ;;
  diag)
    stack_env ba-d
    v=$2 tag=$3 seconds=$4 seed=
    [ "${5:-}" != g2 ] || seed=gate2-seed
    hold diag 6 $((seconds / 60 + 30)) "wordpress-splitpolicy: diagnosis server $tag (CPUs 6-13)"
    t="-Dpolyglot.engine.TraceCompilation=true -Dpolyglot.engine.CompilationFailureAction=Print"
    t+=" -Dpolyglot.engine.TraceSplitting=true -Dpolyglot.engine.SplittingTraceEvents=true"
    t+=" -Dpolyglot.engine.TraceSplittingSummary=true"
    DEV=1 DEV_CLASSES=target/classes-$v DIAG_SEED=$seed JAVA_OPTS="$t" bash tests/gate2/woo.sh diag $tag ${seconds}s
    release diag ;;
  ab)
    stack_env ba-t
    rounds=$2; shift 2
    variants=("$@")
    # START: the first round's number, to add rounds to a series
    for r in $(seq "${START:-1}" "$rounds"); do
      if [ $((r % 2)) = 1 ]; then order="${variants[*]}"; else order=$(printf '%s\n' "${variants[@]}" | tac | tr '\n' ' '); fi
      for v in $order; do
        tag=$v-r$r
        hold ab 7 30 "wordpress-splitpolicy: WooCommerce load A/B, $tag (JVM server on 14-21)"
        hostload "woo $tag"
        mkdir -p target/ba-t/abl/$tag
        DEV=1 DEV_CLASSES=target/classes-$v WC_DOCKER_ARGS="-v $(pwd -W)/target/ba-t/abl/$tag:/out" \
          JAVA_OPTS="$LOGS -Dpolyglot.log.file=/out/engine.log" \
          MIXES=1 WARM=300s DURATION=120s bash tests/gate2/woo.sh load jvm 16 $tag 16
        release ab
      done
    done ;;
  g2)
    stack_env ba-g
    rounds=$2 visits=$3; shift 3
    variants=("$@")
    for r in $(seq "${START:-1}" "$rounds"); do
      if [ $((r % 2)) = 1 ]; then order="${variants[*]}"; else order=$(printf '%s\n' "${variants[@]}" | tac | tr '\n' ' '); fi
      for v in $order; do
        [ ! -e target/ba-g/runs/jvm-java.$v-$r.json ] || { echo "jvm-java.$v-$r exists: stopping"; exit 2; }
        hold g2 6 30 "wordpress-splitpolicy: Gate 2 visits jvm-java.$v-$r (CPUs 14-21, CPU lock)"
        export CLASSES_$v=target/classes-$v JAVA_OPTS_$v="$LOGS -Dpolyglot.log.file=/out/engine-$v.log"
        mkdir -p target/ba-g/out/r$r
        lock "Gate 2 visits jvm-java.$v round $r"
        hostload "g2 $v-r$r"
        # measure names the run VARIANT-1 and deletes that name's files first: an earlier series' round 1 is moved
        # aside (back at the end), and this run renamed to its round at once
        for f in target/ba-g/runs/jvm-java.$v-1.*; do [ ! -e "$f" ] || mv "$f" "${f/jvm-java.$v-1./jvm-java.$v-x1.}"; done
        OUT_DIR=target/ba-g/out/r$r CPU_LOCK_HELD=1 HOST_QUIET=101 HOST_LOADED=101 \
          bash tests/gate2/native.sh measure 1 "$visits" 1 jvm-java.$v || true
        unlock
        release g2
        for f in target/ba-g/runs/jvm-java.$v-1.*; do mv "$f" "${f/jvm-java.$v-1./jvm-java.$v-x$r.}"; done
      done
    done
    for f in target/ba-g/runs/jvm-java.*-x[0-9]*; do mv "$f" "${f/-x/-}"; done ;;
  gate1)
    holds=$2 iterations=$3; shift 3
    out=${OUT:-target/sp-gate1}
    mkdir -p $out/all
    for h in $(seq "$holds"); do
      hold gate1 5 40 "wordpress-splitpolicy: Gate 1 A/B, hold $h (CPUs 14-21, CPU lock)"
      lock "Gate 1 A/B hold $h"
      hostload "gate1 hold $h"
      AB_OUT=$out/h$h bash bench/gate1/ab.sh ${KERNELS:-all} "$iterations" 2 "$@" || true
      unlock
      release gate1
      for f in $out/h$h/*-[12].txt; do
        b=$(basename $f .txt); i=${b%-*} r=${b#*-}
        cp $f $out/all/$i-$(((h - 1) * 2 + r)).txt
      done
    done
    python bench/gate1/ab.py $out/all "$@" ;;
  watch)
    i=0
    while [ ! -e target/ba-watch.stop ]; do
      for c in $(docker ps --format '{{.Names}}' | grep -E '^ba-[tg]-jvm' || true); do
        stack=${c%%-jvm*}; id=$(docker inspect -f '{{.Id}}' $c 2> /dev/null) || continue
        mkdir -p target/$stack/watch
        if [ ! -e target/$stack/watch/$id.mem.started ]; then
          touch target/$stack/watch/$id.mem.started
          MSYS_NO_PATHCONV=1 docker run -d --rm --name ba-m-${id:0:12} --cpuset-cpus 0-5 --cgroupns=host --pid=host \
            -v /sys/fs/cgroup:/hostcg:ro -v "$(pwd -W)/bench/split-policy:/t:ro" -v "$(pwd -W)/target/$stack/watch:/out" \
            python:3.13-slim python -u /t/memwatch.py $id /out/$id.mem.json > /dev/null || true
        fi
        if [ $((i % 6)) = 0 ]; then
          MSYS_NO_PATHCONV=1 docker exec $c sh -c '
            echo "## time $(date +%s)"
            echo "## thread cpu (name count seconds)"
            for t in /proc/1/task/*; do echo "$(cat $t/comm 2>/dev/null | tr -d "0-9 " )|$(cut -d")" -f2 $t/stat 2>/dev/null | cut -d" " -f13,14)"; done | awk -F"|" "{split(\$2,c,\" \"); n[\$1]++; s[\$1]+=c[1]+c[2]} END {for (k in n) printf \"%s %d %.1f\\n\", k, n[k], s[k]/100}"
            echo "## codecache"; $GRAALVM_HOME/bin/jcmd 1 Compiler.codecache' \
            > target/$stack/watch/$id.tmp 2> /dev/null && mv target/$stack/watch/$id.tmp target/$stack/watch/$id.txt || true
        fi
      done
      i=$((i + 1))
      sleep 5
    done ;;
  *) sed -n 2,26p "$0"; exit 2 ;;
esac
