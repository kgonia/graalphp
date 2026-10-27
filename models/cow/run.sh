#!/usr/bin/env bash
# Checks the copy-on-write model with TLC. Usage: models/cow/run.sh [config.cfg] [TLC options...]
# (default CowRefs.cfg). TLA2TOOLS and JAVA override the jar and the JVM; XMX the heap (default 2g).
set -euo pipefail
cd "$(dirname "$0")"
cfg=${1:-CowRefs.cfg}
shift || true
java=${JAVA:-$HOME/.jdks/graalvm-jdk-25.0.4+7.1/bin/java}
jar=${TLA2TOOLS:-$HOME/tools/tla2tools.jar}
meta=$(mktemp -d)
trap 'rm -rf "$meta"' EXIT
"$java" -XX:+UseParallelGC -Xmx${XMX:-2g} -cp "$jar" tlc2.TLC -workers ${WORKERS:-4} -metadir "$meta" \
  -config "$cfg" "$@" CowRefs.tla
