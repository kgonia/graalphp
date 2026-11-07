#!/usr/bin/env bash
# Roadmap step 5.1 (wiki/destructors-prototype.md): Gate 2 for the counting-only refcount prototype, on a stack of its
# own (rcg: containers rcg-*, network 172.31.151.0/24, database on host port 3451 and CPUs 22-23), as
# bench/perf-check/runs.sh's pc-g but with this session's names.
# - stack: the database (seeded from gate2-mariadb, read only) and the site volume, with target/classes-VARIANT
#   copied in for every such directory.
# - check VARIANT: Gate 2's pages on PHP 8.5 and on jvm-java.VARIANT (CPUs 6-13), compared.
# - g2 ROUNDS VISITS VARIANT...: native.sh measure, one run per CPU lock and memory reservation, the variants
#   interleaved and the order reversed every round; every server logs its compilations (engine-VARIANT.log).
# - clean: the stack's containers, volume and network.
# OPTS_VARIANT: more server options for that variant (e.g. OPTS_dt=-Dphp.rc.destructors=true).
set -euo pipefail
export PATH=/usr/bin:/bin:/mingw64/bin:$PATH
cd "$(dirname "$0")/../.."
root=$(pwd -W)
export WPN_NAME=rcg WPN_NET=172.31.151 WPN_DB_PORT=3451 WPN_DB_CPUS=22-23 WPN_MEASURE_PORT=8251 WPN_AUX_CPUS=6-13
MEMLOCK="python C:/Users/krzgo/Projects/memory-lock/memlock.py"
NAME=claude-wordpress-destructors-g2
CPU_LOCK=/c/Users/krzgo/Projects/.cpu-14-21.lock
lock() {
  until mkdir $CPU_LOCK 2> /dev/null; do [ -n "${said:-}" ] || echo "CPUs 14-21 taken: $(cat $CPU_LOCK/* 2> /dev/null | head -1)"; said=1; sleep 2; done; said=
  echo "claude-wordpress-destructors (wordpress-destructors): $*, since $(date '+%F %T')" > $CPU_LOCK/note
}
unlock() { rm -rf $CPU_LOCK; }
g2() { bash tests/gate2/native.sh "$@"; }
LOGS="-Dpolyglot.engine.TraceCompilation=true -Djdk.graal.internal.PrintGC=true"
case ${1:-} in
  stack)
    g2 db
    g2 volume
    MSYS_NO_PATHCONV=1 docker run --rm --cpuset-cpus 6-13 -v rcg-site:/srv -v "$root:/w:ro" alpine:3 sh -c '
      for d in /w/target/classes-*; do [ -d "$d" ] || continue; v=$(basename $d); rm -rf /srv/target/$v; cp -r $d /srv/target/; done
      ls /srv/target' ;;
  check)
    v=$2
    $MEMLOCK acquire $NAME-check --gb 5 --wait --ttl 90 --note "wordpress-destructors: Gate 2 pages of $v (CPUs 6-13)"
    opts=OPTS_$v; export CLASSES_$v=target/classes-$v JAVA_OPTS_$v="${!opts:-}"
    if [ ! -d target/rcg/pages/php ]; then
      g2 reset-db && g2 start php 6-13 8252 && g2 pages php 8252; g2 stop php
    fi
    g2 reset-db && g2 start jvm-java.$v 6-13 8252 && g2 pages jvm-java.$v 8252; g2 stop jvm-java.$v
    g2 compare php jvm-java.$v > target/rcg/pages-$v.txt || true
    $MEMLOCK release $NAME-check > /dev/null 2>&1 || true
    echo "pages jvm-java.$v: $(grep -c '^same' target/rcg/pages-$v.txt) of $(ls target/rcg/pages/php/*.html | wc -l) the same"
    grep '^DIFF' target/rcg/pages-$v.txt || true ;;
  g2)
    rounds=$2 visits=$3; shift 3
    variants=("$@")
    runs=target/rcg/runs
    mkdir -p $runs
    for r in $(seq "${START:-1}" "$rounds"); do
      if [ $((r % 2)) = 1 ]; then order="${variants[*]}"; else order=$(printf '%s\n' "${variants[@]}" | tac | tr '\n' ' '); fi
      for v in $order; do
        if [ -e $runs/jvm-java.$v-$r.json ] || [ -e $runs/jvm-java.$v-x$r.json ]; then echo "jvm-java.$v-$r exists: skipped"; continue; fi
        $MEMLOCK acquire $NAME --gb 6 --wait --ttl 120 --note "wordpress-destructors: Gate 2 visits jvm-java.$v-$r (CPUs 14-21, CPU lock)" \
          || { echo "no memlock reservation: stopping"; exit 3; }
        opts=OPTS_$v; export CLASSES_$v=target/classes-$v JAVA_OPTS_$v="$LOGS -Dpolyglot.log.file=/out/engine-$v.log ${!opts:-}"
        mkdir -p target/rcg/out/r$r
        lock "Gate 2 visits jvm-java.$v round $r"
        trap 'unlock; $MEMLOCK release $NAME > /dev/null 2>&1 || true' EXIT
        OUT_DIR=target/rcg/out/r$r CPU_LOCK_HELD=1 HOST_QUIET=101 HOST_LOADED=101 \
          bash tests/gate2/native.sh measure 1 "$visits" 1 jvm-java.$v || true
        unlock
        $MEMLOCK release $NAME > /dev/null 2>&1 || true
        trap - EXIT
        # measure names the run VARIANT-1 (and deletes that name's files first): renamed to x ROUND at once, and to
        # ROUND at the end
        for f in $runs/jvm-java.$v-1.*; do [ ! -e "$f" ] || mv "$f" "${f/jvm-java.$v-1./jvm-java.$v-x$r.}"; done
      done
    done
    for f in $runs/jvm-java.*-x[0-9]*; do [ ! -e "$f" ] || mv "$f" "${f/-x/-}"; done ;;
  clean)
    g2 clean ;;
  *) sed -n 2,12p "$0"; exit 2 ;;
esac
