#!/usr/bin/env bash
# Builds the native launcher target/native/${NI_NAME:-php}: agent.sh in the Truffle PHP image collects the metadata,
# image.sh in the build image runs native-image (about 14 GB of heap: take a memory-lock reservation first). Both
# run on CPUSET (default 6-13, off the benchmark cores). Build the JVM classes first (mvn package -DskipTests).
# TRUFFLE_IMAGE / NATIVE_IMAGE override the images (defaults: GraalVM 25.4.4.1.1).
set -euo pipefail
cd "$(dirname "$0")/../.."
root=$(pwd -W 2>/dev/null || pwd)
cpus=${CPUSET:-6-13}
MSYS_NO_PATHCONV=1 docker run --rm --cpuset-cpus "$cpus" ${JAVA_OPTS:+-e JAVA_OPTS="$JAVA_OPTS"} -v "$root:/w" -w /w \
  "${TRUFFLE_IMAGE:-gate1-truffle:8.5-graal25.4}" sh bench/native/agent.sh > target/native-agent.log 2>&1
start=$(date +%s)
MSYS_NO_PATHCONV=1 docker run --rm --cpuset-cpus "$cpus" ${NI_OPTS:+-e NI_OPTS="$NI_OPTS"} ${NI_NAME:+-e NI_NAME="$NI_NAME"} \
  -v "$root:/w" -w /w "${NATIVE_IMAGE:-gate1-truffle-native:8.5-graal25.4}" sh bench/native/image.sh > target/native-build.log 2>&1
echo "native-image: $(( $(date +%s) - start )) s, $(du -m "target/native/${NI_NAME:-php}" | cut -f1) MB"
grep -E "Peak RSS|Finished generating|Error|error:" target/native-build.log | cut -c1-300 | tail -5
