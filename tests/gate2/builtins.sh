#!/usr/bin/env bash
# Gate 2 probe, step 2: the PHP built-in functions and classes WordPress core uses, and which Truffle PHP lacks.
# Writes target/gate2/builtins.md. Run after tests/gate2/fetch.sh and tests/gate2/export.sh; build first.
set -euo pipefail
cd "$(dirname "$0")/../.."
python -I tests/gate2/builtins.py names
python -I tests/gate2/builtins.py drivers
MSYS_NO_PATHCONV=1 bash bench/gate1/php.sh php target/gate2/reference.php > target/gate2/reference.out
MSYS_NO_PATHCONV=1 bash bench/gate1/php.sh php tools/ast-export.php target/ast target/gate2/ours.php
./bin-php.sh target/gate2/ours.php > target/gate2/ours.out 2>&1 || true
python -I tests/gate2/builtins.py
