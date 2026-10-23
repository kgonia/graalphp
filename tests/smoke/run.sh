#!/usr/bin/env bash
# Runs each smoke test on PHP 8.5 (the reference container) and on Truffle PHP, and diffs the outputs. Truffle PHP
# runs each test twice: in one context, and in three contexts on one shared engine (a server's mode: shared
# translations and compiled code, per-context state), whose last output must match too.
# Usage: tests/smoke/run.sh [name...]   (default: every tests/smoke/*.php). Build first: mvn package -DskipTests
set -uo pipefail
cd "$(dirname "$0")/../.."
names=("$@")
if [ ${#names[@]} -eq 0 ]; then
  for f in tests/smoke/*.php; do names+=("$(basename "$f" .php)"); done
fi
mkdir -p target/smoke
files=()
for n in "${names[@]}"; do files+=("tests/smoke/$n.php"); done
MSYS_NO_PATHCONV=1 bash bench/gate1/php.sh php tools/ast-export.php target/ast "${files[@]}"
failed=0
for n in "${names[@]}"; do
  MSYS_NO_PATHCONV=1 bash bench/gate1/php.sh php "tests/smoke/$n.php" > "target/smoke/$n.expected" 2>&1
  ./bin-php.sh --ast-root=target/ast "tests/smoke/$n.php" > "target/smoke/$n.actual" 2>&1
  ./bin-php.sh --ast-root=target/ast --contexts=3 "tests/smoke/$n.php" > "target/smoke/$n.shared" 2>&1
  if ! diff -q "target/smoke/$n.expected" "target/smoke/$n.actual" > /dev/null; then
    echo "FAIL $n (diff target/smoke/$n.expected target/smoke/$n.actual)"
    failed=1
  elif ! diff -q "target/smoke/$n.expected" "target/smoke/$n.shared" > /dev/null; then
    echo "FAIL $n in 3 contexts (diff target/smoke/$n.expected target/smoke/$n.shared)"
    failed=1
  else
    echo "ok   $n"
  fi
done
exit $failed
