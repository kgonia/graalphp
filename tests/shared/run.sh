#!/usr/bin/env bash
# Runs PHP in many contexts on one shared engine (as a server runs requests) and checks they stay isolated
# (wiki/tla-engine.md):
# - isolation.php in many contexts at once: language-level arrays (constants, class defaults) are shared by every
#   context and must never change, and shared call and property sites answer each context as it declared its
#   classes;
# - requests/*: requests in sequence, each of which must print what PHP prints for it alone, whatever ran before;
# - requests/retained*.php: a closed context's state must not stay reachable from the engine, and what the engine
#   caches about request data stays bounded.
# Usage: tests/shared/run.sh [threads] [runs per thread]. Build first: mvn package -DskipTests
set -euo pipefail
cd "$(dirname "$0")/../.."
MSYS_NO_PATHCONV=1 bash bench/gate1/php.sh php tools/ast-export.php target/ast tests/shared/isolation.php \
  tests/shared/requests/*.php
java="${GRAALVM_HOME:-$HOME/.jdks/graalvm-jdk-25.0.4+7.1}/bin/java"
sep=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) sep=";";; esac
shared() {
  "$java" -Xmx2g --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow \
    -cp "target/classes${sep}target/lib/*" tests/shared/SharedContexts.java "$@"
}
failed=0
shared tests/shared/isolation.php isolated "${1:-4}" "${2:-10}" || failed=1

# each sequence: the requests one after another on one engine, against each one run alone on PHP
mkdir -p target/shared
for sequence in "callable-unbound callable-bound" "callable-bound callable-unbound callable-bound"; do
  files=()
  : > target/shared/expected
  for name in $sequence; do
    files+=("tests/shared/requests/$name.php")
    echo "== tests/shared/requests/$name.php" >> target/shared/expected
    MSYS_NO_PATHCONV=1 bash bench/gate1/php.sh php "tests/shared/requests/$name.php" >> target/shared/expected
  done
  shared --sequence "${files[@]}" > target/shared/actual
  if diff target/shared/expected target/shared/actual; then
    echo "ok: in sequence: $sequence"
  else
    echo "FAILED: in sequence: $sequence"
    failed=1
  fi
done

shared --retained tests/shared/requests/retained.php 64 || failed=1
exit $failed
