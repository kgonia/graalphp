#!/usr/bin/env bash
# Runs a PHP script on Truffle PHP in the Linux container (gate1-truffle image: the PHP reference image plus
# GraalVM, so both engines share the distro, the CPU and libpcre2). The repo is mounted at /w.
# Usage: bench/gate1/truffle.sh [8.5|8.4] bench/gate1/run.php all 5     (build first: mvn package -DskipTests)
set -euo pipefail
version=8.5
if [[ "${1:-}" =~ ^8\.[0-9]+$ ]]; then version=$1; shift; fi
root=$(cd "$(dirname "$0")/../.." && pwd -W 2>/dev/null || pwd)
MSYS_NO_PATHCONV=1 exec docker run --rm ${CPUSET:+--cpuset-cpus "$CPUSET"} ${JAVA_OPTS:+-e JAVA_OPTS="$JAVA_OPTS"} \
  -v "$root:/w" -w /w "gate1-truffle:$version" ./bin-php.sh --ast-root=target/ast "$@"
