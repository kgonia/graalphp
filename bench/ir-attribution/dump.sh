#!/usr/bin/env bash
# IR attribution (attribute.py): a Truffle PHP run on Gate 1's kernels (bench/gate1/run.php, or DRIVER) whose
# compilations are dumped with node source positions, summarized per tier into target/ir-dump/TAG.json; the dumps
# (about 10 MB a compilation) are deleted after. Not timed: CPUs 6-13 (CPUSET), under a memory-lock reservation.
# Usage: bench/ir-attribution/dump.sh TAG CLASSES-SUFFIX [KERNELS [ITERATIONS [JAVA OPTIONS...]]]
#   e.g. dump.sh base base all 300; dump.sh dt p5 all 300 -Dphp.rc.destructors=true
#   then: python -I bench/ir-attribution/attribute.py report target/ir-dump/base.json target/ir-dump/dt.json
set -euo pipefail
export PATH=/usr/bin:/bin:/mingw64/bin:$PATH
cd "$(dirname "$0")/../.."
tag=$1 classes=$2 kernels=${3:-all} iterations=${4:-300}
shift $(($# < 4 ? $# : 4))
dir=target/ir-dump/$tag
MEMLOCK="python C:/Users/krzgo/Projects/memory-lock/memlock.py"
NAME=claude-wordpress-ir-dump-$tag
$MEMLOCK acquire $NAME --gb 6 --wait --ttl 60 --note "wordpress-destructors: IR dump $tag (CPUs 6-13)" > /dev/null \
  || { echo "no memlock reservation: stopping"; exit 3; }
trap '$MEMLOCK release $NAME > /dev/null 2>&1 || true' EXIT
rm -rf "$dir" && mkdir -p "$dir"
CPUSET=${CPUSET:-6-13} PHP_CLASSES=target/classes-$classes JAVA_OPTS="-Dpolyglot.compiler.NodeSourcePositions=true \
  -Djdk.graal.TrackNodeSourcePosition=true -Djdk.graal.Dump=Truffle:1 -Djdk.graal.DumpPath=/w/$dir $*" \
  bash bench/gate1/truffle.sh "${DRIVER:-bench/gate1/run.php}" "$kernels" "$iterations" > "$dir.out" 2>&1
python -I bench/ir-attribution/attribute.py summarize "$dir" "target/ir-dump/$tag.json"
rm -rf "$dir"
