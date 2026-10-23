#!/usr/bin/env bash
# Exports the php-ast AST of every WordPress core file (target/gate2/wordpress) under target/ast.
set -euo pipefail
cd "$(dirname "$0")/../.."
find target/gate2/wordpress -name "*.php" | sort | MSYS_NO_PATHCONV=1 xargs -n 200 bash bench/gate1/php.sh php tools/ast-export.php target/ast
echo "exported $(find target/ast/target/gate2/wordpress -name '*.json' | wc -l) files"
