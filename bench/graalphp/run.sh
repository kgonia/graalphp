#!/usr/bin/env bash
# graalphp's own benchmarks on PHP 8.5 (OPcache, tracing JIT) and on Truffle PHP: the PHP variants of the Benchmarks
# Game programs that graalphp (github.com/abertschi/graalphp, last commit 29e22de, 2020) measured against PHP 7.4
# and PHP 8.0 alpha with its JIT (benchmarks/scripts-report/bench). Each program is fetched into target/graalphp/ and
# runs as graalphp ran it, with its own hrtime around each iteration, except for $N and $iter. graalphp's sizes
# (fannkuch-redux 12, spectral-norm 5500, binary-trees 21) take minutes per iteration on PHP; the sizes below take
# well under a second, with enough iterations for Truffle PHP to warm up. The engines run one after the other on
# the same cores; summarize.py compares their steady states and first iterations, their outputs (without the timing
# lines) and their peak memory.
# Usage: bench/graalphp/run.sh [cpuset] [iterations]   (defaults: 14-21, 60)
set -euo pipefail
cd "$(dirname "$0")/../.."
export CPUSET=${1:-14-21}
iterations=${2:-60}
commit=29e22de14d919ccd5647603b54ccd9eb25725d50
base=https://raw.githubusercontent.com/abertschi/graalphp/$commit/benchmarks/scripts-report/bench
# program file, its N here (graalphp's N in the comment)
programs=(
  "fannkuchredux/fannkuchredux.php-1.php 10"           # 12
  "spectralnorm/spectralnorm.php-2-unmodified.php 1000" # 5500
  "spectralnorm/spectralnorm.php-2-val.php 1000"        # 5500
  "spectralnorm/spectralnorm.php-2-ref.php 1000"        # 5500
  "binary-trees/binarytrees.php-3-val.php 16"           # 21
  "binary-trees/binarytrees.php-3-ref.php 16"           # 21
)
out=target/graalphp
mkdir -p "$out"
for entry in "${programs[@]}"; do
  read -r path n <<< "$entry"
  name=$(basename "$path" .php)
  [ -f "$out/$name.orig.php" ] || curl -fsSL -o "$out/$name.orig.php" "$base/$path"
  sed -E -e "s/^\\\$N = [0-9]+;/\$N = $n;/" -e "s/^\\\$iter = [0-9]+;/\$iter = $iterations;/" "$out/$name.orig.php" \
    > "$out/$name.php"
  grep -q "^\$N = $n;" "$out/$name.php" && grep -q "^\$iter = $iterations;" "$out/$name.php"
done
MSYS_NO_PATHCONV=1 bash bench/gate1/php.sh php tools/ast-export.php target/ast \
  $(for entry in "${programs[@]}"; do read -r path n <<< "$entry"; echo "$out/$(basename "$path")"; done) > /dev/null
for entry in "${programs[@]}"; do
  read -r path n <<< "$entry"
  name=$(basename "$path" .php)
  # a program that fails on an engine is reported by summarize.py; the others still run
  PEAK_MEMORY=1 bash bench/gate1/php.sh php "$out/$name.php" > "$out/$name.php.txt" 2>&1 || true
  PEAK_MEMORY=1 bash bench/gate1/truffle.sh "$out/$name.php" > "$out/$name.truffle.txt" 2>&1 || true
done
python bench/graalphp/summarize.py "$out" $(for entry in "${programs[@]}"; do read -r path n <<< "$entry"; basename "$path" .php; done)
