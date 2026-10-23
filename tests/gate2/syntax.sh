#!/usr/bin/env bash
# Gate 2 probe, step 1: translates all of WordPress core (every file and function body, nothing runs) and
# reports what the translator can't handle yet. Writes target/gate2/syntax.md.
set -euo pipefail
cd "$(dirname "$0")/../.."
python -I tests/gate2/syntax.py driver
MSYS_NO_PATHCONV=1 bash bench/gate1/php.sh php tools/ast-export.php target/ast target/gate2/translate-all.php
./bin-php.sh target/gate2/translate-all.php > target/gate2/translate.out 2>&1 || true
python -I tests/gate2/syntax.py
