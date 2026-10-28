#!/usr/bin/env bash
# Where a WordPress server's memory goes: per variant, starts Truffle PHP (browse.sh) with Native Memory Tracking and
# snapshots it (tests/gate2/memsnap.sh) right after start, after one cold visit and after 200 paced visits more, and
# prints the warm server's memory split by owner (memsplit.py). Out: target/memory/<variant>/.
# The visits are the video comparison's (ThreeJsBlender productions/wordpress-graalvm, CAPTURE_DIR); its GraalVM facts
# and frames are put back afterwards.
# A variant is JVM options with commas for spaces; "libgraal" is none (the default compiler). Compare the compilers
# at one heap limit: jargraal keeps the compiler's memory on the Java heap, which without a limit just grows.
# Usage: tests/gate2/memory.sh -Xmx512m -Xmx512m,-XX:-UseJVMCINativeLibrary   (after browse.sh's setup; build first)
set -uo pipefail
cd "$(dirname "$0")/../.."
wp=$(pwd)
capture=${CAPTURE_DIR:-/c/Users/krzgo/Projects/ThreeJsBlender/productions/wordpress-graalvm}
export CPUSET=${CPUSET:-6-13}
backup=$wp/target/memory/video-facts
mkdir -p $backup && cp $capture/facts/graal-*.json $backup/ && cp -r $capture/public/assets/graal-cold $capture/public/assets/graal-warm $backup/
restore() {
  bash $wp/tests/gate2/browse.sh stop truffle
  cp $backup/graal-*.json $capture/facts/
  rm -rf $capture/public/assets/graal-cold $capture/public/assets/graal-warm
  cp -r $backup/graal-cold $backup/graal-warm $capture/public/assets/
}
trap restore EXIT
snap() { # out-file
  bash $wp/tests/gate2/memsnap.sh gate2-truffle-http > $1 2>&1
}
for variant in "$@"; do
  opts="-XX:NativeMemoryTracking=summary"; [ $variant = libgraal ] || opts="$opts ${variant//,/ }"
  out=$wp/target/memory/${variant//[^a-zA-Z0-9]/_}; mkdir -p $out
  echo "== $variant ($opts) $(date +%H:%M)"
  JAVA_OPTS="$opts" bash $wp/tests/gate2/browse.sh start truffle
  snap $out/start.txt
  (cd $capture && node scripts/capture.mjs graal-cold) && snap $out/cold.txt \
    && (cd $capture && node scripts/capture.mjs graal-warm --warmup 200) && snap $out/warm.txt \
    && cp $capture/facts/graal-*.json $out/
  # the next variant (or the exit) removes the container, and a failed visit's cause is in its log
  docker logs gate2-truffle-http > $out/server.log 2>&1
  grep -c "heap space\|OutOfMemory" $out/server.log > $out/oom.count
  [ -f $out/warm.txt ] && python $wp/tests/gate2/memsplit.py $out/warm.txt
done
