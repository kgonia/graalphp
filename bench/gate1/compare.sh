#!/usr/bin/env bash
# Runs the kernels on PHP, on the Truffle PHP, and on the Truffle PHP with native-time accounting, back to back on
# the same cores, then prints the comparison. Comparing runs made at different times is unreliable on a shared
# machine: PHP's own timings moved by 30% between quiet and loaded periods.
# Usage: bench/gate1/compare.sh [kernels|all] [iterations] [cpuset]   (defaults: all 200 14-21)
set -euo pipefail
cd "$(dirname "$0")/../.."
kernels=${1:-all}
iterations=${2:-200}
export CPUSET=${3:-14-21}
out=target/compare
mkdir -p "$out"
bash bench/gate1/php.sh php bench/gate1/run.php "$kernels" "$iterations" > "$out/php.txt"
bash bench/gate1/truffle.sh bench/gate1/run.php "$kernels" "$iterations" > "$out/truffle.txt"
JAVA_OPTS="-Dphp.nativeclock=true" bash bench/gate1/truffle.sh bench/gate1/run.php "$kernels" "$iterations" \
    > "$out/native.txt"
python bench/gate1/summarize.py "$out/php.txt" "$out/truffle.txt" "$out/native.txt"
