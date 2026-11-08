#!/usr/bin/env bash
# Runs WordPress core's PHPUnit suite (after setup.sh) on one engine and records every test's outcome by name.
# Usage: tests/wp-phpunit/run.sh ENGINE [SELECTION...]
#   ENGINE     php        PHP 8.5.12, OPcache and its tracing JIT on in CLI (the image's default)
#              php-noopc  PHP 8.5.12 with -d opcache.enable_cli=0
#              truffle    Truffle PHP on GraalVM 25.4, from CLASSES / LIB (relative to the repo; default the
#                         checkout's target/classes and target/lib, copied first so a rebuild can't change a run);
#                         JAVA_OPTS reaches every JVM (e.g. JAVA_OPTS=-Dphp.rc.destructors=true), WP_PHPUNIT_XMX
#                         sets the main JVM's heap (default 3g)
#   SELECTION  none: the whole suite (phpunit.xml.dist's groups excluded, as in WordPress's CI); one bare word: that
#              --group; otherwise PHPUnit's own arguments (--filter X, ...)
# CHUNKED=1 runs the suite as one PHPUnit process per chunk: each entry of each test suite's directory, in the
# suites' order (config-export.php --chunks). WordPress is installed once, by the first chunk run; the selection
# applies to each chunk. CHUNKS (an extended regex) keeps only the chunks whose path matches, FROM=N starts at
# chunk N. A chunk that dies costs only its own tests, and a process holds only its chunk's test classes and data
# sets (PHPUnit loads every file of its suite before it filters by group).
# A test that ends its process (outcome crash: a fatal error, the JVM's death) or makes no progress for
# TEST_TIMEOUT seconds (default 600; outcome timeout: the container is killed) is recorded with that outcome, and
# its process is run again without it (--filter), up to RESUMES times (default 5) per process; not when the
# selection has its own --filter.
# Writes target/wp-phpunit/runs/RUN (RUN default ENGINE-<selection>[-chunked]): outcomes.tsv (Phpunit9Outcomes.php),
# junit.xml (PHP, unchunked: it takes ext-dom), stdout.txt, stderr.txt, run.txt (the commands, wall times, the
# containers' memory.peak; chunked, a line per chunk). Compare runs with tests/wp-phpunit/compare.py.
# Tests write into the tree (wp-content/uploads, ...): each engine runs on its own copy of wordpress-develop
# (target/wp-phpunit/trees/ENGINE, made afresh per run, mounted at the original's path), so engines can run at once;
# one run per engine at a time (a second waits).
# Each engine has its own database (wp_phpunit_<engine>) on gate2-mariadb; CPUSET (default 6-13) and MEMORY
# (default 6g) bound the container; it never runs on the benchmark CPUs 14-21.
set -euo pipefail
cd "$(dirname "$0")/../.."
root=$(pwd -W 2>/dev/null || pwd)
engine=${1:?usage: run.sh php|php-noopc|truffle [SELECTION...]}
shift
CPUSET=${CPUSET:-6-13}
MEMORY=${MEMORY:-6g}
CHUNKED=${CHUNKED:-0}
FROM=${FROM:-1}
TEST_TIMEOUT=${TEST_TIMEOUT:-600}
RESUMES=${RESUMES:-5}
case ",$CPUSET," in *,1[4-9]*|*,2[01]*|*-1[4-9]*|*-2[01]*) echo "CPUSET $CPUSET reaches the benchmark CPUs 14-21"; exit 2;; esac
base=target/wp-phpunit
dev=$base/wordpress-develop
[ -f "$dev/wp-tests-config.php" ] && [ -d "$dev/vendor/phpunit" ] || { echo "run tests/wp-phpunit/setup.sh first"; exit 2; }

if [ $# -eq 0 ]; then selection=(); label=all
elif [ $# -eq 1 ] && [[ "$1" =~ ^[A-Za-z0-9_-]+$ ]]; then selection=(--group "$1"); label=$1
else selection=("$@"); label=$(echo "$*" | tr -c 'A-Za-z0-9_.-' '_' | sed 's/_*$//' | cut -c1-60)
fi
[ "$CHUNKED" = 1 ] && label+=-chunked${CHUNKS:+-subset}
[ "$FROM" = 1 ] || label+=-from$FROM
run=${RUN:-$engine-$label}
out=$base/runs/$run
db=wp_phpunit_${engine//-/_}
config=${CONFIG:-phpunit.xml.dist}
resumable=1
for arg in "${selection[@]}"; do [[ "$arg" != --filter* ]] || resumable=0; done

# one run per engine at a time (mkdir is atomic); a lock left by a dead run is removed by hand
lock=$base/run-$engine.lock
until mkdir "$lock" 2> /dev/null; do echo "waiting for the run in $(cat "$lock/owner" 2>/dev/null)"; sleep 30; done
echo "$run" > "$lock/owner"
trap 'rm -rf "$lock"' EXIT

rm -rf "$out" && mkdir -p "$out"
# the engine's own copy of the tree, mounted at the original's path
tree=$base/trees/$engine
rm -rf "$tree" && mkdir -p "$base/trees" && cp -r "$dev" "$tree"
rm -rf "$tree/src/wp-content/uploads"
docker exec gate2-mariadb mariadb -uroot -proot -e "DROP DATABASE IF EXISTS $db; CREATE DATABASE $db;"
mounts=(-v "$root:/w" -v "$root/$tree:/w/$dev" -w "/w/$dev")

# the configuration, read by PHPUnit's own loader on the reference PHP (config-export.php) and serialized for both
# engines (ConfigLoader.php), with the outcome listener added; chunked, one per chunk
export_config() {
  MSYS_NO_PATHCONV=1 docker run --rm --cpuset-cpus "$CPUSET" "${mounts[@]}" wp-phpunit-php:8.5 \
    php /w/tests/wp-phpunit/config-export.php "$config" "$@"
}

tsv=$out/outcomes.tsv
docker_args=(--rm --cpuset-cpus "$CPUSET" --memory "$MEMORY" "${mounts[@]}"
  -e WP_PHPUNIT_DB="$db" -e PHPUNIT_OUTCOMES="/w/$tsv")
php_args=()
# PHPUnit's JUnit log takes ext-dom: on PHP only
junit=(--log-junit "/w/$out/junit.xml")
case $engine in
  php) image=wp-phpunit-php:8.5 ;;
  php-noopc) image=wp-phpunit-php:8.5; php_args=(-d opcache.enable_cli=0) ;;
  truffle)
    image=${TRUFFLE_IMAGE:-gate1-truffle:8.5-graal25.4}
    junit=()
    rt=$base/runtime/$run
    rm -rf "$rt" && mkdir -p "$rt"
    cp -r "${CLASSES:-target/classes}" "$rt/classes"
    cp -r "${LIB:-target/lib}" "$rt/lib"
    { echo "runtime: ${CLASSES:-target/classes} at $(git log -1 --format=%h), copied to $rt"
      git status --short -- src; } > "$out/runtime.txt"
    docker_args+=(-v "$root/tests/wp-phpunit/truffle-php.sh:/usr/local/bin/php:ro"
      -e WP_PHPUNIT_CLASSES="/w/$rt/classes" -e WP_PHPUNIT_LIB="/w/$rt/lib" ${JAVA_OPTS:+-e JAVA_OPTS="$JAVA_OPTS"}
      ${WP_PHPUNIT_XMX:+-e WP_PHPUNIT_XMX="$WP_PHPUNIT_XMX"}) ;;
  *) echo "unknown engine $engine"; exit 2 ;;
esac
[ "$CHUNKED" = 1 ] && junit=()
touch "$tsv"

# kills container $1 when the outcomes file stops growing for TEST_TIMEOUT seconds, touching $2; ends with the run
watchdog() {
  local name=$1 flag=$2 size last idle=0
  last=$(wc -c < "$tsv")
  while sleep 10; do
    size=$(wc -c < "$tsv")
    if [ "$size" != "$last" ]; then last=$size; idle=0; else idle=$((idle + 10)); fi
    if [ $idle -ge "$TEST_TIMEOUT" ]; then touch "$flag"; docker kill "$name" > /dev/null 2>&1; return 0; fi
  done
}

# run_phpunit NAME SERIALIZED [docker args...]: one PHPUnit process (and its reruns past a test that ended it);
# appends to stdout.txt, stderr.txt, run.txt
run_phpunit() {
  local name=$1 ser=$2
  shift 2
  local skipped=$out/skip-$$.txt filter=() attempt=0
  : > "$skipped"
  while :; do
    local cmd=(php "${php_args[@]}" /w/tests/wp-phpunit/phpunit.php -c "$config" "${junit[@]}" "${selection[@]}")
    echo "== $name: $engine ($image): ${cmd[*]}${filter:+ --filter (all but the $(wc -l < "$skipped") above)}" \
      | tee -a "$out/stdout.txt" >> "$out/stderr.txt"
    cmd+=("${filter[@]}")
    local start status=0 wall peak cname=wp-phpunit-$engine-$$-$RANDOM flag=$out/killed-$$ before
    rm -f "$flag"
    before=$(wc -c < "$out/stdout.txt")
    start=$(date +%s)
    watchdog "$cname" "$flag" &
    local watch=$!
    # memory.peak: the container's whole footprint (every process, the JVM's heap and libgraal included)
    MSYS_NO_PATHCONV=1 docker run --name "$cname" "${docker_args[@]}" -e PHPUNIT_CONFIG_SERIALIZED="/w/$ser" "$@" \
      "$image" sh -c '"$@"; s=$?; echo "peak_memory $(cat /sys/fs/cgroup/memory.peak)" >&2; exit $s' sh "${cmd[@]}" \
      >> "$out/stdout.txt" 2> "$out/last-stderr.txt" || status=$?
    kill $watch 2> /dev/null || true
    wait $watch 2> /dev/null || true
    wall=$(( $(date +%s) - start ))
    cat "$out/last-stderr.txt" >> "$out/stderr.txt"
    # none when the container was killed
    peak=$( { grep -o 'peak_memory [0-9]*' "$out/last-stderr.txt" || true; } | tail -1 | cut -d' ' -f2)
    printf '%s\texit %s\twall %ss\tpeak_memory %s%s\n' "$name" "$status" "$wall" "${peak:-?}" \
      "$([ -f "$flag" ] && echo " (killed: no progress for ${TEST_TIMEOUT}s)")" | tee -a "$out/run.txt"
    # a test started and never ended: it ended the process
    local last
    last=$(tail -n 1 "$tsv")
    [[ "$last" == "#start "* ]] || break
    local test=${last#\#start } outcome=crash why
    if [ -f "$flag" ]; then outcome=timeout; why="no progress for ${TEST_TIMEOUT}s, killed"
    else why="the process ended (exit $status): $( { tail -c +$((before + 1)) "$out/stdout.txt"; cat "$out/last-stderr.txt"; } \
      | { grep -E 'Fatal error|Error|Exception' || true; } | tail -n 1 | tr '\t' ' ' | cut -c1-300)"
    fi
    printf '%s\t%s\t%s\t%s\n' "$test" "$outcome" "$wall" "$why" >> "$tsv"
    echo "$test" >> "$skipped"
    attempt=$((attempt + 1))
    [ $resumable = 1 ] && [ $attempt -le "$RESUMES" ] || break
    # the same process again without the tests that ended it; those before them run again too
    filter=(--filter "$(python -c 'import re, sys
names = [l.rstrip("\n") for l in open(sys.argv[1], encoding="utf-8")]
print("/^(?!(?:" + "|".join(re.escape(n).replace("/", "\\/") for n in names) + ")$)/")' "$skipped")")
    set -- "$@" -e WP_TESTS_SKIP_INSTALL=1
  done
  rm -f "$skipped" "$out/killed-$$"
}

start=$(date +%s)
if [ "$CHUNKED" = 1 ]; then
  mkdir -p "$out/config"
  # every chunk's configuration at once: config/N.ser for line N
  export_config --chunks "/w/$out/config" > "$out/chunks.txt"
  echo "$engine: $(wc -l < "$out/chunks.txt") chunks, $(grep -cE "${CHUNKS:-.}" "$out/chunks.txt") selected, from $FROM" \
    | tee -a "$out/run.txt"
  n=0
  first=1
  while IFS=$'\t' read -r suite path; do
    n=$((n + 1))
    [ $n -ge "$FROM" ] && [[ "$path" =~ ${CHUNKS:-.} ]] || continue
    if [ $first = 1 ]; then run_phpunit "$suite $path" "$out/config/$n.ser" < /dev/null; first=0
    else run_phpunit "$suite $path" "$out/config/$n.ser" -e WP_TESTS_SKIP_INSTALL=1 < /dev/null
    fi
  done < "$out/chunks.txt"
else
  export_config "/w/$out/phpunit-config.ser"
  run_phpunit all "$out/phpunit-config.ser"
fi
wall=$(( $(date +%s) - start ))
echo "total wall ${wall}s" | tee -a "$out/run.txt"
tail -n 3 "$out/stdout.txt"
python tests/wp-phpunit/compare.py summary "$out"
