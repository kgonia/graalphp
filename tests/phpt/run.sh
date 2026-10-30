#!/usr/bin/env bash
# Differential conformance run on PHP 8.5.12's own tests (php-src .phpt files), beside the WordPress kernels:
# extract the tests from the reference image's source tarball, run each on PHP (the oracle) and on the Truffle
# PHP (one JVM, a fresh context per test), compare stdout. Report: target/phpt/report.txt, failures.txt.
# Usage: tests/phpt/run.sh [--reuse-oracle]    (build first; ~15 min, the oracle about 6 of them)
# JAVA_OPTS reaches the Truffle PHP's JVM (-Dpolyglot.php.Interpreter=bytecode); PHP_CLASSES (relative to the repo)
# runs another build than target/classes, e.g. a copy that a rebuild during the run can't change.
set -euo pipefail
cd "$(dirname "$0")/../.."
root=$(pwd -W 2>/dev/null || pwd)
dir=target/phpt
export CPUSET=${CPUSET:-14-21}
if [ "${1:-}" != --reuse-oracle ]; then
    rm -rf "$dir" target/php-src
    mkdir -p target/php-src
    MSYS_NO_PATHCONV=1 docker run --rm -v "$root:/w" gate1-php:8.5 sh -c 'tar -xJf /usr/src/php.tar.xz \
        -C /w/target/php-src --strip-components=1 --wildcards "*/Zend/tests/*" "*/ext/standard/tests/*" \
        "*/ext/pcre/tests/*" "*/ext/json/tests/*" "*/ext/ctype/tests/*" "*/tests/lang/*"'
    python tests/phpt/extract.py target/php-src "$dir/cases"
    MSYS_NO_PATHCONV=1 bash bench/gate1/php.sh bash tests/phpt/oracle.sh "$dir"
    (cd "$dir/cases" && find . \( -name '*.php' ! -name '*.skipif.php' \) -o -name '*.inc') \
        | sed "s|^\./|$dir/cases/|" > "$dir/export-list.txt"
    MSYS_NO_PATHCONV=1 docker run --rm -v "$root:/w" -w /w gate1-php:8.5 sh -c \
        "xargs -a $dir/export-list.txt -n 400 php -d memory_limit=1G tools/ast-export.php target/ast" \
        2> "$dir/export-errors.txt" > /dev/null || true
fi
grep -P '\tran\t' "$dir/expected/results.txt" | cut -f1 | sed "s|^|$dir/cases/|" > "$dir/run-list.txt"
rm -rf "$dir/actual" "$dir/batch.log" && mkdir -p "$dir/actual"
# BatchMain exits with 3 when a script can't be stopped; it then resumes after that script
status=3
while [ $status -eq 3 ]; do
    status=0
    MSYS_NO_PATHCONV=1 docker run --rm ${CPUSET:+--cpuset-cpus "$CPUSET"} ${JAVA_OPTS:+-e JAVA_OPTS="$JAVA_OPTS"} ${PHP_CLASSES:+-e PHP_CLASSES="$PHP_CLASSES"} -v "$root:/w" -w /w gate1-truffle:8.5 sh -c \
        '$GRAALVM_HOME/bin/java --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow \
         $JAVA_OPTS_BASE $JAVA_OPTS -Djava.net.preferIPv6Addresses=system -Dpolyglot.engine.WarnInterpreterOnly=false -cp "${PHP_CLASSES:-target/classes}:target/lib/*" \
         pl.codeloom.php.launcher.BatchMain --ast-root=target/ast --list='"$dir"'/run-list.txt \
         --out='"$dir"'/actual --timeout=10' >> "$dir/batch.log" 2>&1 || status=$?
done
python tests/phpt/compare.py "$dir"
