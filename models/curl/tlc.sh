#!/usr/bin/env bash
# Checks the curl handles' lifecycle model with TLC. Usage: models/curl/tlc.sh Config.cfg [TLC options]
# TLA2TOOLS (default ~/tools/tla2tools.jar) and GRAALVM_HOME (any JDK 11+) locate the tools; TLC's states go to a
# temporary directory.
set -euo pipefail
cd "$(dirname "$0")"
java="${GRAALVM_HOME:-$HOME/.jdks/graalvm-jdk-25.0.4+7.1}/bin/java"
config="$1"
shift
meta="$(mktemp -d)"
trap 'rm -rf "$meta"' EXIT
"$java" -XX:+UseParallelGC -Xmx2g -cp "${TLA2TOOLS:-$HOME/tools/tla2tools.jar}" tlc2.TLC -workers 4 -difftrace -deadlock \
  -metadir "$meta" -config "$config" "$@" CurlLifecycle.tla
