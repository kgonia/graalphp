#!/usr/bin/env bash
# Runs the open findings of the curl lifecycle model (wiki/tla-curl.md) on PHP 8.5 and on Truffle PHP and shows how
# their outputs differ. These are known differences, kept out of tests/smoke until they are fixed.
# Usage: models/curl/repro/run.sh [name...]. Build first: mvn package -DskipTests
set -uo pipefail
shopt -s nullglob
cd "$(dirname "$0")/../../.."
names=("$@")
if [ ${#names[@]} -eq 0 ]; then
  for f in models/curl/repro/*.php; do names+=("$(basename "$f" .php)"); done
fi
mkdir -p target/curl-repro
files=()
for n in "${names[@]}"; do files+=("models/curl/repro/$n.php"); done
MSYS_NO_PATHCONV=1 bash bench/gate1/php.sh php tools/ast-export.php target/ast "${files[@]}"
status=0
for n in "${names[@]}"; do
  MSYS_NO_PATHCONV=1 bash bench/gate1/php.sh php "models/curl/repro/$n.php" > "target/curl-repro/$n.expected" 2>&1
  ./bin-php.sh --ast-root=target/ast "models/curl/repro/$n.php" > "target/curl-repro/$n.actual" 2>&1
  if diff -q "target/curl-repro/$n.expected" "target/curl-repro/$n.actual" > /dev/null; then
    echo "same    $n"
  else
    echo "differs $n"
    diff "target/curl-repro/$n.expected" "target/curl-repro/$n.actual" | sed 's/^/        /'
    status=1
  fi
done
exit $status
