#!/usr/bin/env bash
# PHP 8.5 (the reference container, OPcache and its tracing JIT) against a Truffle PHP build on Gate 1, interleaved
# under the CPU lock on CPUs 14-21, the order reversed every round: phpref.sh ROUNDS ITERATIONS VARIANT. Runs go to
# OUT (default target/p4-php): php-ROUND.txt and VARIANT-ROUND.txt, then each kernel's median ratio Truffle / PHP.
set -euo pipefail
export PATH=/usr/bin:/bin:/mingw64/bin:$PATH
cd "$(dirname "$0")/../.."
rounds=$1 iterations=$2 v=$3
out=${OUT:-target/p4-php}
mkdir -p $out
MEMLOCK="python C:/Users/krzgo/Projects/memory-lock/memlock.py"
CPU_LOCK=/c/Users/krzgo/Projects/.cpu-14-21.lock
export CPUSET=14-21 PEAK_MEMORY=1
for r in $(seq "$rounds"); do
  $MEMLOCK acquire claude-wordpress-perf4-php --gb 4 --wait --ttl 60 \
    --note "wordpress-perf4: Gate 1 PHP 8.5 against $v, round $r (CPUs 14-21, CPU lock)" > /dev/null || exit 3
  until mkdir $CPU_LOCK 2> /dev/null; do sleep 2; done
  echo "claude-wordpress-perf4 (wordpress-perf4): Gate 1 PHP 8.5 against $v round $r, since $(date '+%F %T')" > $CPU_LOCK/note
  if [ $((r % 2)) = 1 ]; then order="php $v"; else order="$v php"; fi
  for x in $order; do
    if [ $x = php ]; then
      bash bench/gate1/php.sh php bench/gate1/run.php all "$iterations" > $out/php-$r.txt 2>&1 || true
    else
      PHP_CLASSES=target/classes-$v PHP_LIB=target/lib-$v bash bench/gate1/truffle.sh bench/gate1/run.php all \
        "$iterations" > $out/$v-$r.txt 2>&1 || true
    fi
  done
  rm -rf $CPU_LOCK
  $MEMLOCK release claude-wordpress-perf4-php > /dev/null || true
done
python bench/perf-step4/phpref.py "$out" "$v" "$rounds"
