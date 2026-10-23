#!/usr/bin/env bash
# Regenerates the prelude's AST exports (src/main/php/prelude/*.php -> src/main/resources/pl/codeloom/php/prelude/)
# and its class index (classes.txt: "lower-case class name<TAB>file"), which the runtime reads from the classpath.
# Run after changing a prelude file; commit the result. Usage: tools/export-prelude.sh
set -euo pipefail
cd "$(dirname "$0")/.."
out=src/main/resources/pl/codeloom/php/prelude
rm -rf target/prelude-ast && mkdir -p "$out"
MSYS_NO_PATHCONV=1 bash bench/gate1/php.sh php tools/ast-export.php target/prelude-ast src/main/php/prelude/*.php
: > "$out/classes.txt"
for source in src/main/php/prelude/*.php; do
  name=$(basename "$source")
  cp "target/prelude-ast/$source.json" "$out/$name.json"
  grep -oE '^(abstract |final )?(class|interface|trait) [A-Za-z_][A-Za-z0-9_]*' "$source" | awk '{print $NF}' \
    | tr 'A-Z' 'a-z' | sed "s/\$/\t$name/" >> "$out/classes.txt"
done
echo "$(wc -l < "$out/classes.txt") prelude classes"
