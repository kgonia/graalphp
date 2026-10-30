#!/usr/bin/env bash
# Writes the Java parser's reference from PHP 8.5 itself (tests/parser/reference.php) for the two corpora:
# Gate 2's WordPress (target/gate2/wordpress) and php-src's tests (target/phpt/cases, tests/phpt/run.sh extracts
# them). Their php-ast exports, the parser's other reference, come from tests/gate2/export.sh and tests/phpt/run.sh.
# Output: target/parser/reference/{tokens/...,errors.tsv}. Runs off the benchmark cores (CPUSET, default 6-13).
set -euo pipefail
cd "$(dirname "$0")/../.."
root=$(pwd -W 2>/dev/null || pwd)
out=target/parser/reference
rm -rf "$out" && mkdir -p "$out"
{
    find target/gate2/wordpress -name '*.php'
    (cd target/phpt/cases && find . \( -name '*.php' ! -name '*.skipif.php' \) -o -name '*.inc') | sed 's|^\./|target/phpt/cases/|'
} | sort > "$out/files.txt"
split -n l/8 -d "$out/files.txt" "$out/part."
for part in "$out"/part.*; do
    MSYS_NO_PATHCONV=1 docker run --rm --name "parser-reference-${part##*.}" --cpuset-cpus "${CPUSET:-6-13}" \
        -v "$root:/w" -w /w gate1-php:8.5 php -d memory_limit=2G -d error_reporting=0 \
        tests/parser/reference.php "$out" "$part" "$part.errors" &
done
wait
cat "$out"/part.*.errors | sort > "$out/errors.tsv"
rm "$out"/part.*
echo "$(wc -l < "$out/files.txt") files, $(wc -l < "$out/errors.tsv") rejected"
