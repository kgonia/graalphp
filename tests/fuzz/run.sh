#!/usr/bin/env bash
# Runs COUNT generated programs (gen.py, seeds SEED..SEED+COUNT-1) on PHP 8.5 (the reference container, OPcache off:
# its optimizer drops stores to variables never read, freeing objects early) and on Truffle PHP (BatchMain: one
# JVM, a fresh context per program), then compare.py. Both sides log errors to stderr instead of displaying them
# (BatchMain's settings, the phpt oracle's), so a warning can't land in the output on one side only; compare.py
# checks the logs too.
# CLASSES: the Truffle PHP build to run, a classes directory relative to the repo (default target/classes), with
# LIB its libraries (default target/lib); JAVA_OPTS goes to its JVM (e.g. -Dphp.rc.echoZeros=true).
# FEATURES: gen.py's feature list. Outputs in target/fuzz/SEED/. Compare options (--mode) are passed on.
# Usage: tests/fuzz/run.sh SEED COUNT [compare.py options]
set -euo pipefail
cd "$(dirname "$0")/../.."
seed=$1; count=$2; shift 2
dir=target/fuzz/$seed
rm -rf "$dir" && mkdir -p "$dir/expected" "$dir/actual" "$dir/batch"
python -I tests/fuzz/gen.py "$seed" "$count" "$dir/cases" ${FEATURES:+--features=$FEATURES}
ls "$dir"/cases/*.php > "$dir/list.txt"
# the reference, and the AST exports Truffle PHP reads, in one container
MSYS_NO_PATHCONV=1 bash bench/gate1/php.sh sh -c "for f in \$(cat $dir/list.txt); do
    php -d opcache.enable_cli=0 -d display_errors=0 -d log_errors=1 \$f > $dir/expected/\$(basename \$f).out 2> $dir/expected/\$(basename \$f).err; done
  xargs -a $dir/list.txt -n 200 php -d memory_limit=1G tools/ast-export.php target/ast"
java="${GRAALVM_HOME:-$HOME/.jdks/graalvm-25.4.4.1.1+1.1}/bin/java"
sep=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) sep=";";; esac
status=3
while [ $status -eq 3 ]; do
  status=0
  "$java" --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow -Dpolyglot.engine.WarnInterpreterOnly=false \
    ${JAVA_OPTS:-} -cp "${CLASSES:-target/classes}${sep}${LIB:-target/lib}/*" pl.codeloom.php.launcher.BatchMain \
    --ast-root=target/ast --list="$dir/list.txt" --out="$dir/batch" --timeout=20 >> "$dir/batch.log" 2>&1 || status=$?
done
for f in $(cat "$dir/list.txt"); do
  for ext in out err; do
    cp "$dir/batch/$f.$ext" "$dir/actual/$(basename "$f").$ext" 2> /dev/null || : > "$dir/actual/$(basename "$f").$ext"
  done
done
python -I tests/fuzz/compare.py "$dir" "$@"
