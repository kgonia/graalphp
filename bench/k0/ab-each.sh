#!/usr/bin/env bash
# bench/gate1/ab.sh once per K0 kernel, so each kernel runs in JVMs of its own (its own warmup curve and peak
# memory), then bench/gate1/curve.py's tables. KERNELS overrides the list.
# Usage: bench/k0/ab-each.sh <out dir> iterations rounds A B...   (variants as ab.sh's, e.g. s1@-Dpolyglot.php.X=y)
set -euo pipefail
cd "$(dirname "$0")/../.."
out=$1; iterations=$2; rounds=$3; shift 3
for k in ${KERNELS:-fannkuch spectralnorm binarytrees nbody fib intloop floatloop mandelbrot sieve bubblesort}; do
  DRIVER=bench/k0/run.php AB_OUT="$out/$k" bash bench/gate1/ab.sh "$k" "$iterations" "$rounds" "$@" > /dev/null
done
python bench/gate1/curve.py "$out" "$@"
