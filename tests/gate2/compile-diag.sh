#!/usr/bin/env bash
# The compilation diagnosis: what Truffle compiles, what fails, what bails out, what is invalidated again and again,
# and where partial evaluation warns, on Gate 1's kernels and on WordPress, on the JVM and on the native image. See
# wiki/partial-evaluation.md.
# - The servers are tests/gate2/native.sh's (HttpMain, our parser, the tree interpreter; INTERPRETER=bytecode for the
#   bytecode one), with their own prefix (WPN_NAME, default pe), network and database, on CPUS (default 6-13, off the
#   benchmark cores: nothing here is timed).
# - kernels-ENGINE: one request runs bench/gate1/run.php all ITERATIONS (default 300) through /gate1-kernels.php.
# - wp-ENGINE: rounds of native.sh's 21 pages (the front end, the login, seven wp-admin pages) until compilation
#   settles: SETTLE_ROUNDS (default 2) rounds in a row that each finish at most SETTLE (default 2) compilations,
#   after at least MIN_ROUNDS (default 5) and at most MAX_ROUNDS (default 40).
# - Then the server closes its engine (php.http.closeFile), which prints the compilation statistics.
# - ENGINE: jvm, or native (target/native/wp-http-diag, built with -H:+IncludeNodeSourcePositions so that failures
#   and deoptimizations carry a stack trace; skipped when there is no such build).
# - The options: TraceCompilation, CompilationFailureAction=Print, TracePerformanceWarnings=all,
#   CompilationStatistics, TraceTransferToInterpreter and node source positions; EXTRA adds more (e.g.
#   "-Dpolyglot.engine.CompileOnly=wptexturize -Dpolyglot.compiler.TraceMethodExpansion=truffleTier", to see what
#   one compilation expands).
# - Output: target/compile-diag/TARGET-ENGINE/ (engine.log, server.log, rounds.txt, kernels.txt), then the report:
#   tests/gate2/compile_report.py, which --check turns into a pass or fail.
# Usage: tests/gate2/compile-diag.sh setup | run [TARGET-ENGINE...] | report [--check] | build-native | clean
#   setup: native.sh db and volume under this prefix (rerun volume after mvn package: it copies target/classes)
#   run: the given runs, default kernels-jvm wp-jvm kernels-native wp-native, then the report
#   build-native: the diagnosis build (about 16 GB: take a memory-lock reservation first); NATIVE_DIAG runs another
#   one (a name under target/native/)
set -euo pipefail
cd "$(dirname "$0")/../.."
export WPN_NAME=${WPN_NAME:-pe} WPN_NET=${WPN_NET:-172.31.7} WPN_DB_PORT=${WPN_DB_PORT:-3318}
native() { bash tests/gate2/native.sh "$@"; }
out=target/compile-diag
cpus=${CPUS:-6-13}
port=${PORT:-8190}
DIAG="-Dpolyglot.engine.TraceCompilation=true -Dpolyglot.engine.CompilationFailureAction=Print"
DIAG+=" -Dpolyglot.compiler.TracePerformanceWarnings=all -Dpolyglot.engine.CompilationStatistics=true"
DIAG+=" -Dpolyglot.engine.TraceTransferToInterpreter=true -Dpolyglot.compiler.NodeSourcePositions=true"

# the number of compilations finished so far
done_count() {
  grep -c '^\[engine\] opt done' "$1/engine.log" 2> /dev/null || true
}

# the server closes its engine after the next request, then exits
close_server() { # variant dir
  touch "$2/close"
  curl -s -o /dev/null "http://127.0.0.1:$port/wp-includes/css/dashicons.css" || true
  timeout 300 docker wait "$WPN_NAME-$1" > /dev/null 2>&1 || echo "$WPN_NAME-$1 did not close its engine in 300 s"
  docker logs "$WPN_NAME-$1" > "$2/server.log" 2>&1 || true
  native stop "$1"
}

run_one() { # target engine
  local target=$1 engine=$2 dir=$out/$1-$2 variant opts
  case $engine in
    jvm) variant=jvm-java; unset NATIVE ;;
    native) variant=native-java; export NATIVE=target/native/${NATIVE_DIAG:-wp-http-diag}
            if [ ! -f "$NATIVE" ]; then echo "$target-native: no $NATIVE (compile-diag.sh build-native), skipped"; return 0; fi ;;
    *) echo "unknown engine $engine"; exit 2 ;;
  esac
  [ "${INTERPRETER:-tree}" = tree ] || variant=${variant%-java}-bc
  rm -rf "$dir" && mkdir -p "$dir"
  opts="$DIAG -Dpolyglot.log.file=/out/engine.log -Dphp.http.closeFile=/out/close ${EXTRA:-}"
  native reset-db
  OUT_DIR=$dir JAVA_OPTS="$opts" native start "$variant" "$cpus" "$port"
  case $target in
    kernels)
      curl -s -H 'Host: localhost' "http://127.0.0.1:$port/gate1-kernels.php?n=${ITERATIONS:-300}" > "$dir/kernels.txt"
      echo "$target-$engine: $(wc -l < "$dir/kernels.txt") kernel iterations, $(done_count "$dir") compilations" ;;
    wp)
      local round=0 before after quiet=0
      while [ $round -lt "${MAX_ROUNDS:-40}" ]; do
        round=$((round + 1))
        before=$(done_count "$dir")
        native pages "$variant" "$port" > /dev/null
        # the compilations a round's requests queued finish in the background: wait for the queue to drain
        sleep "${ROUND_PAUSE:-5}"
        after=$(done_count "$dir")
        echo "round $round: $((after - before)) compilations, $after in all" >> "$dir/rounds.txt"
        if [ $((after - before)) -le "${SETTLE:-2}" ]; then quiet=$((quiet + 1)); else quiet=0; fi
        [ $round -lt "${MIN_ROUNDS:-5}" ] || [ $quiet -lt "${SETTLE_ROUNDS:-2}" ] || break
      done
      cp -r "target/$WPN_NAME/pages/$variant" "$dir/pages"
      echo "$target-$engine: $(tail -1 "$dir/rounds.txt")" ;;
    *) echo "unknown target $target"; exit 2 ;;
  esac
  close_server "$variant" "$dir"
}

case ${1:-} in
  setup) native db; native volume ;;
  run)
    shift
    runs=("$@")
    [ ${#runs[@]} -gt 0 ] || runs=(kernels-jvm wp-jvm kernels-native wp-native)
    for r in "${runs[@]}"; do run_one "${r%-*}" "${r##*-}"; done
    python -I tests/gate2/compile_report.py "$out" ;;
  report) shift; python -I tests/gate2/compile_report.py "$out" "$@" ;;
  build-native)
    [ -f bench/native/build.sh ] || { echo "no native-image build on this branch (bench/native)"; exit 2; }
    # NI_CLASSES builds another class directory, NI_NAME names the binary, NI_OPTS adds options (e.g. -J-Xmx8g)
    NI_MAIN=pl.codeloom.php.launcher.HttpMain NI_NAME=${NI_NAME:-wp-http-diag} \
      NI_OPTS="-H:+IncludeNodeSourcePositions ${NI_OPTS:-}" bash bench/native/build.sh ;;
  clean) native clean ;;
  *) sed -n 2,25p "$0"; exit 2 ;;
esac
