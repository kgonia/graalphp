#!/usr/bin/env bash
# Runs the kernels on PHP and on the Truffle PHP back to back on the same cores, measures the regex time by
# replaying the recorded PCRE2 calls in C (pcre-replay.c), then prints the comparison with each engine's peak memory. Comparing runs made at different times is unreliable on a shared
# machine: PHP's own timings moved by 30% between quiet and loaded periods.
# Usage: bench/gate1/compare.sh [kernels|all] [iterations] [cpuset]   (defaults: all 400 14-21)
# Truffle reaches its plateau after 250-300 iterations of the WordPress kernels (Tier 2 compiles of the big
# call trees take seconds), and the Truffle steady state is the last quarter: fewer iterations measure warmup.
# DRIVER=bench/k0/run.php compares another kernel set (no native-time run: K0 makes no native calls).
set -euo pipefail
cd "$(dirname "$0")/../.."
kernels=${1:-all}
iterations=${2:-400}
export CPUSET=${3:-14-21}
driver=${DRIVER:-bench/gate1/run.php}
out=target/compare
mkdir -p "$out"
# each run's peak memory (the container's, so the whole JVM or php process) is appended to its output
PEAK_MEMORY=1 bash bench/gate1/php.sh php "$driver" "$kernels" "$iterations" > "$out/php.txt" 2>&1
PEAK_MEMORY=1 bash bench/gate1/truffle.sh "$driver" "$kernels" "$iterations" > "$out/truffle.txt" 2>&1
if [ "$driver" = bench/gate1/run.php ]; then
    # native time: the last iteration's PCRE2 calls, recorded and replayed in C against the same pcre2lib
    root=$(pwd -W 2>/dev/null || pwd)
    MSYS_NO_PATHCONV=1 docker run --rm -v "$root:/w" -w /w ${TRUFFLE_IMAGE:-gate1-truffle:8.5-graal25.4} sh -c         'gcc -O2 -I/opt/pcre2-php -o target/pcre-replay bench/gate1/pcre-replay.c -L/opt/pcre2-php -lpcre2-8          -Wl,-rpath,/opt/pcre2-php'
    JAVA_OPTS="-Dphp.pcre2.record=/w/$out/pcre.rec" bash bench/gate1/truffle.sh "$driver" "$kernels" 30 > /dev/null
    MSYS_NO_PATHCONV=1 docker run --rm ${CPUSET:+--cpuset-cpus "$CPUSET"} -v "$root:/w" -w /w ${TRUFFLE_IMAGE:-gate1-truffle:8.5-graal25.4}         target/pcre-replay "$out/pcre.rec" 200 > "$out/native.txt"
    python bench/gate1/summarize.py "$out/php.txt" "$out/truffle.txt" "$out/native.txt"
else
    python bench/gate1/summarize.py "$out/php.txt" "$out/truffle.txt"
fi
