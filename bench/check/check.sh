#!/usr/bin/env bash
# One entry point for the checks of a build: correctness, compiled code, time and memory (wiki/checks.md).
#
#   build [REF]                 build REF (default HEAD) once: WORK/target/classes-SHA and lib-SHA (SHA: 7 digits,
#                               classes-SHA.commit: the full commit), from a detached worktree WORK/target/check/src-SHA
#                               that L0 and L1 run their tests in (the commit's own tests)
#   l0 [REF] [--base REF]       ~10 min, no CPU lock: unit tests (IR budgets: CompilationTest), smoke, the destructor
#                               fixtures (when the commit has them)
#   l1 [REF] [--base REF]       ~1 h: php-src's .phpt (CPUs 6-13); WP=1 adds WordPress's PHPUnit suite (~2.5 h),
#                               L1="wp" with WP=1 runs only that
#   l2 [REF] [--base REF]       ~3 h, timed, on CPUs 14-21 under the CPU lock: Gate 1 (HOLDS x 2 rounds, ITERATIONS)
#                               and Gate 2 (ROUNDS of VISITS), REF and BASE interleaved
#   report [REF] [--base REF]   the Markdown report (report.py) to target/check/report-SHA-BASE.md; exit 1 when a
#                               check is worse
# BASE defaults to REF's merge-base with origin/main, or REF's first parent when REF is on main. Every level builds
# what it needs first, appends its results to WORK/target/check/results.jsonl (results.py) and prints the report.
#
# WORK (default the main checkout, C:/Users/krzgo/Projects/wordpress) holds the data the gates need and that git
# doesn't (bench/gate1/wordpress and data, target/gate2/wordpress, target/ast); its bench/gate1 and tests/gate2
# scripts run them. Host-side work (the build, unit tests, smoke's JVMs) runs on the P-cores only (start /affinity
# C03C03: LPs 0, 1, 10-13, 22, 23), so it stays off the E-cores, where the WSL VM and the timed runs are. A check run
# refuses to start beside another (target/check/run.pid); L2 runs preflight.py perf before every timed run.
# CONTROL=1 adds BASE a second time to Gate 1 (an A/A arm: how far two identical builds differ in this run; on
# 2026-11-08 up to 9% on slugs over 8 rounds, the order's effect included).
# Options: HOLDS (4), ITERATIONS (400), ROUNDS (6), VISITS (200), JAVA_OPTS_REF / JAVA_OPTS_BASE (e.g.
# -Dphp.rc.destructors=true), QUIET (preflight's E-core load limit, %), MVN, JAVA_HOME.
set -euo pipefail
export PATH=/usr/bin:/bin:/mingw64/bin:$PATH
here=$(cd "$(dirname "$0")" && pwd)
WORK=${WORK:-/c/Users/krzgo/Projects/wordpress}
export JAVA_HOME=${JAVA_HOME:-$HOME/.jdks/graalvm-25.4.4.1.1+1.1}
MVN=${MVN:-/c/Users/krzgo/.m2/wrapper/dists/apache-maven-3.9.16/0daed3be3ebd1c706f0e69e8b07c6b73f5cc4ea3dfce72a8d0ec2e849ca2ddb0/bin/mvn}
MEMLOCK="python C:/Users/krzgo/Projects/memory-lock/memlock.py"
CPU_LOCK=/c/Users/krzgo/Projects/.cpu-14-21.lock
PY="python -I"
cd "$WORK"
mkdir -p target/check

command=${1:-}; shift || true
ref=HEAD base=
while [ $# -gt 0 ]; do
  case $1 in
    --base) base=$2; shift 2 ;;
    *) ref=$1; shift ;;
  esac
done
sha=$(git rev-parse --short=7 "$ref^{commit}")
if [ -z "$base" ]; then
  base=$(git merge-base "$sha" origin/main)
  [ "$(git rev-parse "$base")" != "$(git rev-parse "$sha")" ] || base=$(git rev-parse "$sha^1")
fi
base=$(git rev-parse --short=7 "$base^{commit}")

reserve() { # NAME GB TTL-minutes NOTE: a reservation, or stop
  $MEMLOCK acquire "claude-check-$1" --gb "$2" --wait --ttl "$3" --note "check: $4" > /dev/null \
    || { echo "no memlock reservation: stopping"; exit 3; }
}
unreserve() { $MEMLOCK release "claude-check-$1" > /dev/null 2>&1 || true; }
lock() {
  until mkdir $CPU_LOCK 2> /dev/null; do [ -n "${said:-}" ] || echo "CPUs 14-21 taken: $(cat $CPU_LOCK/* 2> /dev/null | head -1)"; said=1; sleep 2; done; said=
  echo "claude-check ($WORK): $*, since $(date '+%F %T')" > $CPU_LOCK/note
}
unlock() { rm -rf $CPU_LOCK; }
on_pcores() { # COMMAND...: run with the host's P-cores only (its children inherit the affinity), with its exit code
  # (start /wait doesn't pass it on); Git's bash by its path (cmd's PATH also finds WSL's bash.exe first or second)
  local status code
  status=$(mktemp)
  cmd //c start "" //b //wait //affinity C03C03 "$(cygpath -w /usr/bin/bash)" -c \
    "$(printf '%q ' "$@"); echo \$? > $(printf '%q' "$status")"
  code=$(cat "$status" 2> /dev/null || echo 1)
  rm -f "$status"
  return "${code:-1}"
}
running() { # take the run's pidfile, or stop (preflight checks it too)
  local pidfile=target/check/run.pid
  if [ -f $pidfile ] && ps -p "$(cut -d' ' -f1 $pidfile)" > /dev/null 2>&1; then
    echo "another check run is active: $(cat $pidfile)"; exit 3
  fi
  echo "$$ $command $sha $(date '+%F %T')" > $pidfile
  export CHECK_RUN_PID=$$
  trap 'rm -f target/check/run.pid' EXIT
}

build() { # SHA: classes-SHA, lib-SHA and the source worktree, once
  local s=$1 src=target/check/src-$1
  if [ ! -d "$src" ]; then
    git worktree add --detach -f "$src" "$s" > /dev/null
  fi
  if [ -f target/classes-$s.commit ] && [ -d target/lib-$s ]; then
    return 0
  fi
  reserve build-$s 4 30 "mvn package $s"
  (cd "$src" && on_pcores "$MVN" -q -o package -DskipTests > build.log 2>&1) \
    || { unreserve build-$s; grep -v '^WARNING' "$src/build.log" | tail -20; echo "build of $s failed"; exit 1; }
  unreserve build-$s
  rm -rf target/classes-$s target/lib-$s
  cp -r "$src/target/classes" target/classes-$s
  cp -r "$src/target/lib" target/lib-$s
  git rev-parse "$s" > target/classes-$s.commit
  echo "built $s"
}

host=
preflight() { # perf|correct: the host's conditions, or stop
  host=$($PY "$here/preflight.py" "$@" | tail -1) || { echo "preflight failed: stopping"; exit 3; }
}

l0() { # SHA
  local s=$1 src=target/check/src-$1 out=target/check/$1
  build "$s"
  mkdir -p "$out"
  reserve l0-$s 4 60 "L0 of $s (unit tests, smoke)"
  (cd "$src" && on_pcores "$MVN" -q -o test > "$WORK/$out/unit.txt" 2>&1) || true
  $PY "$here/results.py" surefire "$s" "$src/target/surefire-reports" --level L0
  (cd "$src" && on_pcores bash tests/smoke/run.sh > "$WORK/$out/smoke.txt" 2>&1) || true
  $PY "$here/results.py" smoke "$s" "$out/smoke.txt" --level L0
  if [ -f "$src/bench/destructors/fixtures.sh" ]; then
    (cd "$src" && bash bench/destructors/fixtures.sh > "$WORK/$out/fixtures.txt" 2>&1) || true
    $PY "$here/results.py" fixtures "$s" "$out/fixtures.txt" --level L0
  fi
  unreserve l0-$s
}

l1() { # SHA: L1 (default "phpt"; WP=1 adds "wp")
  local s=$1 src=target/check/src-$1 out=target/check/$1
  build "$s"
  mkdir -p "$out"
  if [[ " ${L1:-phpt} " == *" phpt "* ]]; then
    reserve l1-$s 6 120 "L1 of $s (phpt on CPUs 6-13)"
    (cd "$src" && CPUSET=6-13 bash tests/phpt/run.sh > "$WORK/$out/phpt.txt" 2>&1) || true
    $PY "$here/results.py" phpt "$s" "$src/target/phpt" --level L1
    unreserve l1-$s
  fi
  if [ -n "${WP:-}" ]; then
    # WordPress's PHPUnit suite in WORK (its wordpress-develop tree and PHP's reference run,
    # target/wp-phpunit/runs/php-all-chunked, from tests/wp-phpunit/setup.sh and run.sh php), on this build;
    # JAVA_OPTS reaches its JVMs
    reserve l1-wp-$s 8 300 "L1 of $s (WordPress PHPUnit on CPUs 6-13)"
    CLASSES=target/classes-$s LIB=target/lib-$s RUN=truffle-$s-chunked CHUNKED=1       bash tests/wp-phpunit/run.sh truffle > "$out/wp-run.txt" 2>&1 || true
    unreserve l1-wp-$s
    python tests/wp-phpunit/compare.py diff target/wp-phpunit/runs/php-all-chunked       target/wp-phpunit/runs/truffle-$s-chunked --frozen tests/wp-phpunit/wordpress.failset.tsv       --out "$out/wp-report.txt" > "$out/wp.txt" 2>&1 || true
    $PY "$here/results.py" wp "$s" "$out/wp.txt" --level L1
  fi
}

l2() { # SHA BASE
  local s=$1 b=$2 out=target/check/$1-$2
  build "$s"; build "$b"
  mkdir -p "$out/gate1/all" "$out/gate2/runs"
  # Gate 1: HOLDS holds of 2 rounds (forward, reversed), each under the CPU lock
  for h in $(seq "${START:-1}" "${HOLDS:-4}"); do
    [ -z "$(ls $out/gate1/all/0-$(((h - 1) * 2 + 2)).txt 2> /dev/null)" ] || continue
    reserve gate1 5 120 "Gate 1 $s vs $b, hold $h (CPUs 14-21)"
    lock "Gate 1 $s vs $b hold $h"
    trap 'unlock; unreserve gate1; rm -f target/check/run.pid' EXIT
    preflight perf
    echo "$host" > "$out/gate1/host-$h.json"
    AB_OUT=$out/gate1/h$h bash bench/gate1/ab.sh all "${ITERATIONS:-400}" 2 "$b${JAVA_OPTS_BASE:+@$JAVA_OPTS_BASE}" \
      "$s${JAVA_OPTS_REF:+@$JAVA_OPTS_REF}" ${CONTROL:+"$b${JAVA_OPTS_BASE:+@$JAVA_OPTS_BASE}"} || true
    unlock; unreserve gate1
    trap 'rm -f target/check/run.pid' EXIT
    for f in $out/gate1/h$h/*-[12].txt; do
      n=$(basename "$f" .txt); cp "$f" "$out/gate1/all/${n%-*}-$(((h - 1) * 2 + ${n#*-})).txt"
    done
  done
  $PY "$here/results.py" gate1 "$s" "$b" "$out/gate1/all" --level L2 --host "$host"
  # Gate 2: its own stack (ck: network 172.31.152.0/24, database on host port 3452 and CPUs 22-23), the database
  # seeded from gate2-mariadb; ROUNDS rounds of VISITS visits, the two builds interleaved, the order reversed every
  # round; every server logs its compilations
  export WPN_NAME=ck WPN_NET=172.31.152 WPN_DB_PORT=3452 WPN_DB_CPUS=22-23 WPN_MEASURE_PORT=8252 WPN_AUX_CPUS=6-13
  bash tests/gate2/native.sh db > /dev/null
  bash tests/gate2/native.sh volume > /dev/null
  # each build runs with its own libraries (LIB_VARIANT); a native.sh without LIB_TAG would run both on WORK's
  # target/lib, wrong for a change of dependencies (a GraalVM/Truffle bump): then stop, unless the libraries match
  if ! grep -q 'LIB_\$tag' tests/gate2/native.sh && ! diff -rq "target/lib-$s" "target/lib-$b" > /dev/null; then
    echo "Gate 2: $s and $b have different libraries, and WORK's tests/gate2/native.sh can't give a build its own"
    echo "(no LIB_TAG; the checks branch adds it): stopping"
    exit 3
  fi
  MSYS_NO_PATHCONV=1 docker run --rm --cpuset-cpus 6-13 -v ck-site:/srv -v "$(pwd -W):/w:ro" alpine:3 sh -c \
    'for v in "$@"; do rm -rf /srv/target/classes-$v /srv/target/lib-$v
       cp -r /w/target/classes-$v /w/target/lib-$v /srv/target/; done' sh "$s" "$b"
  local runs=target/ck/runs logs="-Dpolyglot.engine.TraceCompilation=true -Djdk.graal.internal.PrintGC=true"
  for r in $(seq 1 "${ROUNDS:-6}"); do
    if [ $((r % 2)) = 1 ]; then order="$b $s"; else order="$s $b"; fi
    for v in $order; do
      [ ! -e "$out/gate2/runs/jvm-java.$v-$r.json" ] || continue
      reserve gate2 6 120 "Gate 2 $v round $r (CPUs 14-21)"
      [ "$v" = "$s" ] && opts=${JAVA_OPTS_REF:-} || opts=${JAVA_OPTS_BASE:-}
      export CLASSES_$v=target/classes-$v LIB_$v=target/lib-$v
      export JAVA_OPTS_$v="$logs -Dpolyglot.log.file=/out/engine-$v.log $opts"
      mkdir -p "$out/gate2/r$r"
      lock "Gate 2 $v round $r"
      trap 'unlock; unreserve gate2; rm -f target/check/run.pid' EXIT
      preflight perf
      echo "$host" > "$out/gate2/host-$v-$r.json"
      OUT_DIR=$out/gate2/r$r CPU_LOCK_HELD=1 HOST_QUIET=101 HOST_LOADED=101 \
        bash tests/gate2/native.sh measure 1 "${VISITS:-200}" 1 jvm-java.$v || true
      unlock; unreserve gate2
      trap 'rm -f target/check/run.pid' EXIT
      for f in $runs/jvm-java.$v-1.*; do [ ! -e "$f" ] || mv "$f" "$out/gate2/runs/$(basename "${f/jvm-java.$v-1./jvm-java.$v-$r.}")"; done
    done
  done
  $PY "$here/results.py" gate2 "$s" "$b" "$out/gate2/runs" "$out/gate2" --level L2 --host "$host"
}

report() {
  $PY "$here/report.py" "$sha" "$base" --out "target/check/report-$sha-$base.md"
}

case $command in
  build) build "$sha" ;;
  l0) running; preflight correct; l0 "$sha"
      # the base's results, for the ratchets, unless the store has them
      grep -q "\"sha\": \"$base\", \"check\": \"smoke\"" target/check/results.jsonl 2> /dev/null || l0 "$base"
      report ;;
  l1) running; preflight correct; l1 "$sha"; report ;;
  l2) running; l2 "$sha" "$base"; report ;;
  report) report ;;
  *) sed -n 2,26p "$0"; exit 2 ;;
esac
