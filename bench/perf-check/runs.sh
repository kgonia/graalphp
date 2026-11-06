#!/usr/bin/env bash
# Roadmap step 3 (wiki/roadmap.md): one performance check of main after the correctness batch (#32-#42, #35) against
# the main before it (c7cd6dc). Builds are class directories target/classes-X (target/classes-X.commit: the commit);
# every timed server runs on CPUs 14-21 under the CPU lock (C:\Users\krzgo\Projects\.cpu-14-21.lock), taken per
# server or per Gate 1 hold and let go after it, so another session's timed runs can go between. Before each timed
# run the host's load outside and on 14-21 and its busiest processes go to target/hostload.log
# (bench/split-policy/hostload.ps1). Every timed JVM logs its compilations (TraceCompilation) and libgraal's
# collections (PrintGC).
# - stack NAME: a database and a site volume of its own: pc-d (correctness: the WooCommerce walk, server and
#   database on 6-13), pc-t (WooCommerce load, database on 22-23), pc-g (Gate 2, database on 22-23), seeded from
#   target/ba-w (the store's seed.sql, seed-load.sql, gate2-seed.sql) and for pc-g from target/ba-g-seed.sql.
# - classes NAME VARIANT...: copies target/classes-VARIANT into stack NAME's volume (a build added later).
# - gate1 HOLDS ITERATIONS VARIANT...: bench/gate1/ab.sh all ITERATIONS 2 per hold (forward, then reversed), HOLDS
#   times; the runs merged into target/pc-gate1/all (OUT: another directory, KERNELS: another kernel list).
# - php ITERATIONS: Gate 1's kernels once on PHP 8.5 (CPUs 6-13, not timed against Truffle PHP): the checksums.
# - g2 ROUNDS VISITS VARIANT...: native.sh measure on pc-g, one run per lock (jvm-java.VARIANT-ROUND; START: the
#   first round's number, to add rounds; RERUN: see the loop). Beside each run threadcpu.py samples the server's threads from CPUs 0-5
#   (target/pc-g/watch/jvm-java.VARIANT-ROUND.threads.txt, for cpusplit.py).
# - woo ROUNDS VARIANT...: woo.sh load jvm 16 on pc-t (300 s warm-up, 120 s of 16 users every visitor buying),
#   interleaved, the order reversed every round; tags VARIANT-rN (START as for g2).
# - watch: beside the timed runs, every new server container of pc-t and pc-g gets memwatch.py (its memory every
#   0.1 s, target/STACK/watch/ID.mem.json) and pc-t's also threadcpu.py (ID.threads.txt); stops when
#   target/pc-watch.stop exists. Nothing of it runs on 14-21.
# - check VARIANT: correctness on CPUs 6-13: Gate 2's pages against PHP 8.5's (pc-g), the WooCommerce walk
#   against PHP 8.5's (pc-d).
# - clean: every pc- container, volume and network.
# QUIET=N (gate1, g2, woo): with the CPU lock taken (so no other session's timed run is on), wait until the host's
# load is under N percent, at most QUIET_MAX seconds.
# Usage: bench/perf-check/runs.sh stack NAME | classes NAME VARIANT... | gate1 ... | php ITERATIONS | g2 ... | woo ...
#        | watch | check VARIANT | clean
set -euo pipefail
export PATH=/usr/bin:/bin:/mingw64/bin:$PATH
cd "$(dirname "$0")/../.."
root=$(pwd -W)
MEMLOCK="python C:/Users/krzgo/Projects/memory-lock/memlock.py"
hold() { # KIND GB TTL-minutes NOTE: a reservation, or stop
  $MEMLOCK acquire claude-wordpress-perfcheck-$1 --gb "$2" --wait --ttl "$3" --note "$4" \
    || { echo "no memlock reservation: stopping"; exit 3; }
}
release() { $MEMLOCK release claude-wordpress-perfcheck-$1 > /dev/null 2>&1 || true; }
CPU_LOCK=/c/Users/krzgo/Projects/.cpu-14-21.lock
lock() {
  until mkdir $CPU_LOCK 2> /dev/null; do [ -n "${said:-}" ] || echo "CPUs 14-21 taken: $(cat $CPU_LOCK/* 2> /dev/null | head -1)"; said=1; sleep 2; done; said=
  echo "claude-wordpress-perfcheck (wordpress-perfcheck): $*, since $(date '+%F %T')" > $CPU_LOCK/note
}
unlock() { rm -rf $CPU_LOCK; }
# the host's CPU load now, in percent of all its CPUs (native.sh's host_cpu)
host_cpu() {
  powershell -NoProfile -Command     "[math]::Round((Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average).Average)"     2> /dev/null | tr -d '
'
}
# QUIET=N: wait until three samples 10 s apart find the host under N percent (other sessions' load; none of ours runs),
# at most QUIET_MAX seconds (default 900)
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
hostload() {
  echo "$(date '+%F %T') $*: $(powershell -NoProfile -ExecutionPolicy Bypass -File bench/split-policy/hostload.ps1 | tr -d '\r')" \
    | tee -a target/hostload.log
}
stack_env() {
  case $1 in
    pc-d) export WPN_NAME=pc-d WPN_NET=172.31.141 WPN_DB_PORT=3441 WPN_DB_CPUS=6-13 ;;
    pc-t) export WPN_NAME=pc-t WPN_NET=172.31.142 WPN_DB_PORT=3442 WPN_DB_CPUS=22-23 ;;
    pc-g) export WPN_NAME=pc-g WPN_NET=172.31.143 WPN_DB_PORT=3443 WPN_DB_CPUS=22-23 WPN_MEASURE_PORT=8196 ;;
    *) echo "unknown stack $1"; exit 2 ;;
  esac
  export WPN_AUX_CPUS=6-13
}
copy_classes() { # STACK VARIANT...
  local stack=$1; shift
  MSYS_NO_PATHCONV=1 docker run --rm --cpuset-cpus 6-13 -v $stack-site:/srv -v "$root:/w:ro" alpine:3 sh -c '
    for v in "$@"; do rm -rf /srv/target/classes-$v; cp -r /w/target/classes-$v /srv/target/; done; ls /srv/target' sh "$@"
}
LOGS="-Dpolyglot.engine.TraceCompilation=true -Djdk.graal.internal.PrintGC=true"
case ${1:-} in
  stack)
    stack_env $2
    mkdir -p target/$2
    cp target/ba-w/seed.sql target/$2/src-seed.sql
    cp target/ba-w/seed-load.sql target/ba-w/gate2-seed.sql target/$2/
    [ "$2" != pc-g ] || cp target/ba-g-seed.sql target/$2/src-seed.sql
    WPN_DB_DUMP=target/$2/src-seed.sql bash tests/gate2/native.sh db
    bash tests/gate2/native.sh volume
    copy_classes $2 $(ls -d target/classes-* | grep -v '\.' | sed 's#target/classes-##') ;;
  classes)
    stack_env $2
    copy_classes "${@:2}" ;;
  gate1)
    holds=$2 iterations=$3; shift 3
    out=${OUT:-target/pc-gate1}
    mkdir -p $out/all
    for h in $(seq "${START:-1}" "$holds"); do
      hold gate1 5 120 "wordpress-perfcheck: Gate 1 A/B, hold $h (CPUs 14-21, CPU lock)"
      lock "Gate 1 A/B hold $h of $*"
      quiet
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
  php)
    hold php 2 30 "wordpress-perfcheck: Gate 1 on PHP 8.5 (CPUs 6-13)"
    mkdir -p target/pc-gate1
    CPUSET=6-13 PEAK_MEMORY=1 bash bench/gate1/php.sh php bench/gate1/run.php all "$2" > target/pc-gate1/php.txt 2>&1
    release php ;;
  g2)
    stack_env pc-g
    rounds=$2 visits=$3; shift 3
    variants=("$@")
    runs=target/pc-g/runs
    mkdir -p target/pc-g/watch $runs
    for r in $(seq "${START:-1}" "$rounds"); do
      if [ $((r % 2)) = 1 ]; then order="${variants[*]}"; else order=$(printf '%s\n' "${variants[@]}" | tac | tr '\n' ' '); fi
      for v in $order; do
       # RERUN=N: a run whose median host load (the server's own third included) passed N percent is run again, up
       # to twice; the loaded run's files go to runs/loaded/
       for attempt in 1 2 3; do
        if [ -e $runs/jvm-java.$v-$r.json ] || [ -e $runs/jvm-java.$v-x$r.json ]; then
          echo "jvm-java.$v-$r exists: skipped"; break
        fi
        hold g2 6 120 "wordpress-perfcheck: Gate 2 visits jvm-java.$v-$r (CPUs 14-21, CPU lock)"
        export CLASSES_$v=target/classes-$v JAVA_OPTS_$v="$LOGS -Dpolyglot.log.file=/out/engine-$v.log"
        mkdir -p target/pc-g/out/r$r
        # measure names the run VARIANT-1 and deletes that name's files first: an earlier series' round 1 is moved
        # aside (back at the end), and this run renamed to its round at once
        for f in $runs/jvm-java.$v-1.*; do [ ! -e "$f" ] || mv "$f" "${f/jvm-java.$v-1./jvm-java.$v-x1.}"; done
        rm -f $runs/jvm-java.$v-1.id
        MSYS_NO_PATHCONV=1 docker run --rm --name pc-tc-$v-$r --cpuset-cpus 0-5 --pid=host --cgroupns=host \
          -v /sys/fs/cgroup:/hostcg:ro -v "$root/bench/perf-check:/t:ro" -v "$root/target/pc-g:/o" python:3.13-slim \
          python -u /t/threadcpu.py /o/runs/jvm-java.$v-1.id /o/watch/jvm-java.$v-$r.threads.txt > /dev/null 2>&1 &
        sampler=$!
        lock "Gate 2 visits jvm-java.$v round $r"
        quiet
        hostload "g2 $v-r$r"
        OUT_DIR=target/pc-g/out/r$r CPU_LOCK_HELD=1 HOST_QUIET=101 HOST_LOADED=101 \
          bash tests/gate2/native.sh measure 1 "$visits" 1 jvm-java.$v || true
        unlock
        release g2
        wait $sampler || true
        for f in $runs/jvm-java.$v-1.*; do [ ! -e "$f" ] || mv "$f" "${f/jvm-java.$v-1./jvm-java.$v-x$r.}"; done
        load=$(sort -n $runs/jvm-java.$v-x$r.host 2> /dev/null | awk '{a[NR] = $1} END {print NR ? a[int((NR + 1) / 2)] : 100}') || load=100
        [ -n "${RERUN:-}" ] && [ "$load" -gt "$RERUN" ] && [ $attempt -lt 3 ] || break
        echo "jvm-java.$v-$r again: the host's median load was $load%"
        mkdir -p $runs/loaded
        for f in $runs/jvm-java.$v-x$r.* target/pc-g/watch/jvm-java.$v-$r.threads.txt target/pc-g/out/r$r/engine-$v.log; do
          [ ! -e "$f" ] || mv "$f" "$runs/loaded/$(basename "$f").$attempt"
        done
       done
      done
    done
    for f in $runs/jvm-java.*-x[0-9]*; do [ ! -e "$f" ] || mv "$f" "${f/-x/-}"; done ;;
  woo)
    stack_env pc-t
    rounds=$2; shift 2
    variants=("$@")
    for r in $(seq "${START:-1}" "$rounds"); do
      if [ $((r % 2)) = 1 ]; then order="${variants[*]}"; else order=$(printf '%s\n' "${variants[@]}" | tac | tr '\n' ' '); fi
      for v in $order; do
        tag=$v-r$r
        hold woo 7 120 "wordpress-perfcheck: WooCommerce load A/B, $tag (JVM server on 14-21)"
        mkdir -p target/pc-t/abl/$tag
        # the lock taken here, polled every 2 s (woo.sh's own polls once a minute)
        lock "WooCommerce load $tag"
        quiet
        hostload "woo $tag"
        DEV=1 DEV_CLASSES=target/classes-$v WC_DOCKER_ARGS="-v $root/target/pc-t/abl/$tag:/out" \
          JAVA_OPTS="$LOGS -Dpolyglot.log.file=/out/engine.log" CPU_LOCK_HELD=1 \
          MIXES=1 WARM=300s DURATION=120s bash tests/gate2/woo.sh load jvm 16 $tag 16 || true
        unlock
        release woo
      done
    done ;;
  watch)
    while [ ! -e target/pc-watch.stop ]; do
      for c in $(docker ps --format '{{.Names}}' | grep -E '^pc-[tg]-jvm' || true); do
        stack=${c%%-jvm*}; id=$(docker inspect -f '{{.Id}} {{.State.Pid}}' $c 2> /dev/null) || continue
        mkdir -p target/$stack/watch
        set -- $id
        if [ ! -e target/$stack/watch/$1.mem.started ]; then
          touch target/$stack/watch/$1.mem.started
          MSYS_NO_PATHCONV=1 docker run -d --rm --name pc-m-${1:0:12} --cpuset-cpus 0-5 --cgroupns=host --pid=host \
            -v /sys/fs/cgroup:/hostcg:ro -v "$root/bench/split-policy:/t:ro" -v "$root/target/$stack/watch:/out" \
            python:3.13-slim python -u /t/memwatch.py $1 /out/$1.mem.json > /dev/null || true
          if [ $stack = pc-t ]; then
            echo "$1 $2" > target/$stack/watch/$1.id
            MSYS_NO_PATHCONV=1 docker run -d --rm --name pc-tc-${1:0:12} --cpuset-cpus 0-5 --cgroupns=host --pid=host \
              -v /sys/fs/cgroup:/hostcg:ro -v "$root/bench/perf-check:/t:ro" -v "$root/target/$stack/watch:/out" \
              python:3.13-slim python -u /t/threadcpu.py /out/$1.id /out/$1.threads.txt > /dev/null || true
          fi
        fi
      done
      sleep 2
    done ;;
  check)
    v=$2
    hold check 5 90 "wordpress-perfcheck: correctness of $v (CPUs 6-13)"
    ( stack_env pc-g
      export CLASSES_$v=target/classes-$v
      g2() { bash tests/gate2/native.sh "$@"; }
      g2 reset-db && g2 start php 6-13 8193 && g2 pages php 8193; g2 stop php
      g2 reset-db && g2 start jvm-java.$v 6-13 8193 && g2 pages jvm-java.$v 8193; g2 stop jvm-java.$v
      g2 compare php jvm-java.$v > target/pc-g/pages-$v.txt || true
      echo "pages jvm-java.$v: $(grep -c '^same' target/pc-g/pages-$v.txt) of $(ls target/pc-g/pages/php/*.html | wc -l) the same"
      grep '^DIFF' target/pc-g/pages-$v.txt || true )
    ( stack_env pc-d
      w() { bash tests/gate2/woo.sh "$@"; }
      if [ ! -d target/pc-d/walk/php ]; then
        SEED=seed w reset-db && w start php 6-13 8194 && w walk php 8194; w stop php
      fi
      rm -rf target/pc-d/walk/jvm target/pc-d/rows/jvm
      SEED=seed w reset-db && DEV=1 DEV_CLASSES=target/classes-$v w start jvm 6-13 8194 && w walk jvm 8194; w stop jvm
      rm -rf target/pc-d/walk/$v target/pc-d/rows/$v
      mv target/pc-d/walk/jvm target/pc-d/walk/$v; mv target/pc-d/rows/jvm target/pc-d/rows/$v
      w compare php $v > target/pc-d/walk-$v.txt || true
      echo "walk $v: $(grep -c '^same' target/pc-d/walk-$v.txt) same, $(grep -c '^DIFF' target/pc-d/walk-$v.txt) DIFF"
      grep '^DIFF' target/pc-d/walk-$v.txt | head -20 || true )
    release check ;;
  clean)
    for s in pc-d pc-t pc-g; do (stack_env $s; bash tests/gate2/native.sh clean); done ;;
  *) sed -n 2,30p "$0"; exit 2 ;;
esac
