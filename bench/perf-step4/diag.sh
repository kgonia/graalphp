#!/usr/bin/env bash
# A diagnosis run (not timed) of Gate 1 kernels on CPUs 6-13 with Truffle's compilation log: diag.sh VARIANT KERNELS
# ITERATIONS [JAVA OPTS...]; the log goes to target/p4-diag/VARIANT-KERNELS.log (VARIANT@OPTS as in ab.sh).
set -euo pipefail
export PATH=/usr/bin:/bin:/mingw64/bin:$PATH
cd "$(dirname "$0")/../.."
v=$1 kernels=$2 iterations=$3; shift 3
base=${v%%@*}; opts=$(echo "$v" | sed -n 's/^[^@]*@//p')
mkdir -p target/p4-diag
name=$(echo "$v" | tr '@=/' '___')-$(echo $kernels | tr , _)
python "C:/Users/krzgo/Projects/memory-lock/memlock.py" acquire claude-wordpress-perf4-diag --gb 3 --wait --ttl 30 \
  --note "wordpress-perf4: diagnosis run $name (CPUs 6-13)" > /dev/null || exit 3
CPUSET=6-13 PHP_CLASSES=target/classes-$base PHP_LIB=target/lib-$base \
  JAVA_OPTS="$opts -Dpolyglot.engine.TraceCompilation=true -Dpolyglot.log.file=/w/target/p4-diag/$name.log $*" \
  bash bench/gate1/truffle.sh bench/gate1/run.php "$kernels" "$iterations" > target/p4-diag/$name.out 2>&1 || true
python "C:/Users/krzgo/Projects/memory-lock/memlock.py" release claude-wordpress-perf4-diag > /dev/null
echo target/p4-diag/$name.log
