#!/usr/bin/env bash
# A/B of Truffle PHP builds: interleaved runs of prebuilt class directories (target/classes-X, copies of
# target/classes), the order reversed every other round, pinned to the benchmark cores. Prints per-variant medians
# of the steady state (last quarter of the iterations) and of each run's peak memory, then the median per-round
# ratio of each variant to the first, for the time of each kernel and for the peak memory.
# Usage: bench/gate1/ab.sh kernels iterations rounds A B...   e.g. bench/gate1/ab.sh all 400 8 before after
# A variant may carry JVM options after an @: bench/gate1/ab.sh all 400 8 g9 g9@-Xmx512m compares heap limits.
# A variant native/NAME runs the native-image build target/native/NAME instead of a class directory.
# DRIVER=bench/k0/run.php compares another kernel set; CPUSET overrides the cores (default 14-21).
set -euo pipefail
cd "$(dirname "$0")/../.."
kernels=$1; iterations=$2; rounds=$3; shift 3
variants=("$@")
export CPUSET=${CPUSET:-14-21} PEAK_MEMORY=1
out=target/ab
rm -rf "$out" && mkdir -p "$out"
n=${#variants[@]}
for r in $(seq "$rounds"); do
  if [ $((r % 2)) = 1 ]; then order=$(seq 0 $((n - 1))); else order=$(seq $((n - 1)) -1 0); fi
  for i in $order; do
    base=${variants[$i]%%@*}
    if [ "${base#native/}" != "$base" ]; then classes=; native=target/$base; else classes=target/classes-$base; native=; fi
    PHP_CLASSES=$classes PHP_NATIVE=$native JAVA_OPTS=$(echo "${variants[$i]}" | sed -n 's/^[^@]*@//p') \
      bash bench/gate1/truffle.sh "${DRIVER:-bench/gate1/run.php}" "$kernels" "$iterations" > "$out/$i-$r.txt" 2>&1
  done
done
python bench/gate1/ab.py "$out" "${variants[@]}"
