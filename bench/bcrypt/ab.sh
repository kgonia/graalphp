#!/usr/bin/env bash
# bcrypt (bench/bcrypt/run.php) on PHP 8.5 and on Truffle PHP builds, interleaved on the benchmark cores as
# bench/gate1/ab.sh runs them, the order reversed every other round, each run's cgroup memory.peak recorded. Prints
# per variant and kernel the first call and the steady state (median of the last quarter), medians over rounds.
# Variants: php (the gate1-php:8.5 image), X (the class directory target/classes-X on the JVM), native/NAME (the
# native-image build target/native/NAME); JVM options after an @ (X@-Dphp.bcrypt=...).
# Usage: bench/bcrypt/ab.sh kernels iterations rounds VARIANT...   e.g. bench/bcrypt/ab.sh all 12 3 php base native/php
# CPUSET overrides the cores (default 14-21); AB_OUT the runs' directory (default target/ab-bcrypt, emptied first).
# Pinning holds only inside the Docker VM, whose CPUs share the host's: each run waits for the host's CPU load to
# stay under HOST_QUIET percent (default 30) over three samples, as tests/gate2/native.sh's measure does.
set -euo pipefail
cd "$(dirname "$0")/../.."
kernels=$1; iterations=$2; rounds=$3; shift 3
variants=("$@")
export CPUSET=${CPUSET:-14-21} PEAK_MEMORY=1
out=${AB_OUT:-target/ab-bcrypt}
rm -rf "$out" && mkdir -p "$out"
MSYS_NO_PATHCONV=1 bash bench/gate1/php.sh php tools/ast-export.php target/ast bench/bcrypt/run.php > /dev/null

host_quiet() {
  local load
  for _ in 1 2 3; do
    load=$(powershell -NoProfile -Command \
      "[math]::Round((Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average).Average)" \
      2> /dev/null | tr -d '\r')
    [ "${load:-100}" -lt "${HOST_QUIET:-30}" ] || return 1
    sleep 3
  done
}

n=${#variants[@]}
for r in $(seq "$rounds"); do
  if [ $((r % 2)) = 1 ]; then order=$(seq 0 $((n - 1))); else order=$(seq $((n - 1)) -1 0); fi
  for i in $order; do
    until host_quiet; do sleep 10; done
    base=${variants[$i]%%@*}
    opts=$(echo "${variants[$i]}" | sed -n 's/^[^@]*@//p')
    if [ "$base" = php ]; then
      bash bench/gate1/php.sh php bench/bcrypt/run.php "$kernels" "$iterations" > "$out/$i-$r.txt" 2>&1
    else
      if [ "${base#native/}" != "$base" ]; then classes=; native=target/$base; else classes=target/classes-$base; native=; fi
      PHP_CLASSES=$classes PHP_NATIVE=$native JAVA_OPTS=$opts \
        bash bench/gate1/truffle.sh bench/bcrypt/run.php "$kernels" "$iterations" > "$out/$i-$r.txt" 2>&1
    fi
  done
done
python bench/bcrypt/summarize.py "$out" "${variants[@]}"
