#!/usr/bin/env bash
# Where run time goes (the IR's counterpart, attribute.py): a JFR recording of a Truffle PHP run on Gate 1's kernels
# (or DRIVER), its execution samples summed per Java method (jfr_top.py). Compiled PHP code shows as the interpreter's
# methods it was partially evaluated from only where it isn't inlined; out-of-line runtime code (@TruffleBoundary,
# built-ins' helpers, TruffleString) shows by name. Not timed against anything: CPUs 6-13 (CPUSET).
# Usage: bench/ir-attribution/profile.sh TAG CLASSES-SUFFIX [KERNELS [ITERATIONS [JAVA OPTIONS...]]]
#   output: target/ir-dump/TAG.jfr and target/ir-dump/TAG-profile.txt
set -euo pipefail
export PATH=/usr/bin:/bin:/mingw64/bin:$PATH
cd "$(dirname "$0")/../.."
tag=$1 classes=$2 kernels=${3:-all} iterations=${4:-300}
shift $(($# < 4 ? $# : 4))
mkdir -p target/ir-dump
MEMLOCK="python C:/Users/krzgo/Projects/memory-lock/memlock.py"
NAME=claude-wordpress-profile-$tag
$MEMLOCK acquire $NAME --gb 4 --wait --ttl 60 --note "wordpress-destructors: JFR profile $tag (CPUs 6-13)" > /dev/null \
  || { echo "no memlock reservation: stopping"; exit 3; }
trap '$MEMLOCK release $NAME > /dev/null 2>&1 || true' EXIT
rm -f target/ir-dump/$tag.jfr
CPUSET=${CPUSET:-6-13} PHP_CLASSES=target/classes-$classes \
  JAVA_OPTS="-XX:StartFlightRecording=filename=/w/target/ir-dump/$tag.jfr,settings=profile $*" \
  bash bench/gate1/truffle.sh "${DRIVER:-bench/gate1/run.php}" "$kernels" "$iterations" > target/ir-dump/$tag-profile.out 2>&1
"${GRAALVM_HOME:-$HOME/.jdks/graalvm-25.4.4.1.1+1.1}/bin/jfr" print --json --events jdk.ExecutionSample \
  target/ir-dump/$tag.jfr > target/ir-dump/$tag-samples.json
python -I bench/ir-attribution/jfr_top.py target/ir-dump/$tag-samples.json > target/ir-dump/$tag-profile.txt
rm -f target/ir-dump/$tag-samples.json
head -45 target/ir-dump/$tag-profile.txt
