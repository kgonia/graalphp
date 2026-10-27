#!/usr/bin/env bash
# Where a WordPress server's memory goes: per variant, starts Truffle PHP (browse.sh) with Native Memory Tracking and
# snapshots it right after start, after one cold visit and after 200 paced visits more. A snapshot holds the
# process's RssAnon, the cgroup's anon, the NMT summary, the code cache, and the largest anonymous mappings with no
# name (what NMT doesn't see: libgraal's heap, mallocs from native libraries such as PCRE2). Out: target/memory/<variant>/.
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
  MSYS_NO_PATHCONV=1 docker exec gate2-truffle-http sh -c '
    echo "## status"; grep -E "VmRSS|RssAnon|RssFile" /proc/1/status
    echo "## cgroup $(grep "^anon " /sys/fs/cgroup/memory.stat)"; echo "## threads $(ls /proc/1/task | wc -l)"
    echo "## nmt"; $GRAALVM_HOME/bin/jcmd 1 VM.native_memory summary scale=MB
    echo "## codecache"; $GRAALVM_HOME/bin/jcmd 1 Compiler.codecache
    echo "## live heap (after a full GC)"; $GRAALVM_HOME/bin/jcmd 1 GC.run > /dev/null; $GRAALVM_HOME/bin/jcmd 1 GC.heap_info
    echo "## unnamed anonymous mappings (Rss kB, size kB, start)"
    awk "/^[0-9a-f]+-[0-9a-f]+ /{split(\$1,a,\"-\"); name=\$6; start=a[1]; size=0} /^Size:/{size=\$2} /^Rss:/{if(name==\"\") print \$2, size, start}" /proc/1/smaps | sort -rn | head -40
    echo "## named mappings over 10 MB (Rss kB)"; awk "/^[0-9a-f]+-[0-9a-f]+ /{name=\$6} /^Rss:/{r[name]+=\$2} END{for(n in r) if(r[n]>10000) print r[n], n}" /proc/1/smaps | sort -rn
  ' > $1 2>&1
}
for variant in "$@"; do
  opts="-XX:NativeMemoryTracking=summary"; [ $variant = libgraal ] || opts="$opts ${variant//,/ }"
  out=$wp/target/memory/${variant//[^a-zA-Z0-9]/_}; mkdir -p $out
  echo "== $variant ($opts) $(date +%H:%M)"
  JAVA_OPTS="$opts" bash $wp/tests/gate2/browse.sh start truffle
  snap $out/start.txt
  (cd $capture && node scripts/capture.mjs graal-cold) && snap $out/cold.txt || continue
  (cd $capture && node scripts/capture.mjs graal-warm --warmup 200) && snap $out/warm.txt || continue
  cp $capture/facts/graal-*.json $out/
  docker logs gate2-truffle-http 2>&1 | grep -c "heap space\|OutOfMemory" > $out/oom.count
  grep -E "RssAnon|cgroup anon" $out/*.txt
done
