#!/usr/bin/env bash
# JfrCold.java on a recording under the repo, in the gate1-truffle image on CPUs 6-13. Usage: jfr.sh FILE.jfr ARGS...
set -euo pipefail
export PATH=/usr/bin:/bin:/mingw64/bin:$PATH
cd "$(dirname "$0")/../.."
f=$1; shift
MSYS_NO_PATHCONV=1 docker run --rm --cpuset-cpus 6-13 -v "$(pwd -W):/w:ro" gate1-truffle:8.5-graal25.4 \
  sh -c '$GRAALVM_HOME/bin/java -XX:TieredStopAtLevel=1 /w/bench/cold-visit/JfrCold.java "$@"' sh "/w/$f" "$@"
