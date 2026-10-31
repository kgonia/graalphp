#!/usr/bin/env bash
# Interleaved A/B rounds of the TRegex replay (tregex-replay.php over target/compare/pcre.rec), the order reversed
# every other round, pinned to the benchmark cores, each run's peak memory appended. Then tregex-ab.py summarizes.
# Usage: bench/gate1/tregex-ab.sh rounds passes 'label=build;options;mode;patterns;rewrites' ...
#   build: a class directory target/classes-X (with target/lib-X and target/classes-X.image if they exist, as in
#   ab.sh) or native/NAME (the native launcher target/native/NAME); the other fields are tregex-replay.php's.
#   e.g. bench/gate1/tregex-ab.sh 3 150 'g2504=g2504;;;;' 'g254=g254;;;;'
# Before each run, and every 10 s during it, the cores are checked for other benchmarks (containers whose cpuset
# overlaps CPUSET): a run starts after the cores were free for QUIET seconds (default 120), and a run another
# benchmark overlaps is stopped and repeated.
# CPUSET overrides the cores (default 14-21); AB_OUT the runs' directory (default target/tregex-ab, emptied first).
set -euo pipefail
cd "$(dirname "$0")/../.."
rounds=$1; passes=$2; shift 2
variants=("$@")
export CPUSET=${CPUSET:-14-21} PEAK_MEMORY=1
out=${AB_OUT:-target/tregex-ab}
rm -rf "$out" && mkdir -p "$out"

cpus() { # expands a cpuset like 1-3,7 into one cpu per line
  echo "$1" | tr ',' '\n' | while IFS=- read -r a b; do [ -n "$a" ] && seq "$a" "${b:-$a}"; done
}
mine=$(cpus "$CPUSET")
busy() { # prints the other containers pinned to our cores
  for c in $(docker ps -q); do
    read -r name set < <(docker inspect -f '{{.Name}} {{.HostConfig.CpusetCpus}}' "$c")
    case $name in /tregex-ab-*) continue ;; esac
    [ -n "$set" ] && [ -n "$(comm -12 <(cpus "$set" | sort) <(echo "$mine" | sort))" ] && echo "$name($set)"
  done
  return 0
}

n=${#variants[@]}
for r in $(seq "$rounds"); do
  if [ $((r % 2)) = 1 ]; then order=$(seq 0 $((n - 1))); else order=$(seq $((n - 1)) -1 0); fi
  for i in $order; do
    label=${variants[$i]%%=*}
    IFS=';' read -r build options mode patterns rewrites <<< "${variants[$i]#*=}"
    if [ "${build#native/}" != "$build" ]; then classes=; lib=; image=; native=target/$build; else
      classes=target/classes-$build; native=
      lib=; [ -d "target/lib-$build" ] && lib=target/lib-$build
      image=; [ -f "target/classes-$build.image" ] && image=$(cat "target/classes-$build.image")
    fi
    while true; do
      # the cores must stay free for QUIET seconds: another job's runs follow each other within seconds
      quiet=0
      while [ "$quiet" -lt "${QUIET:-120}" ]; do
        if [ -n "$(busy)" ]; then echo "waiting for $(busy)" >&2; quiet=0; else quiet=$((quiet + 10)); fi
        sleep 10
      done
      name=tregex-ab-$$-$label-$r
      CONTAINER_NAME=$name PHP_CLASSES=$classes PHP_LIB=$lib PHP_NATIVE=$native TRUFFLE_IMAGE=${image:-${TRUFFLE_IMAGE:-}} \
        bash bench/gate1/truffle.sh bench/gate1/tregex-replay.php "$passes" Python "$options" "$mode" "$patterns" \
        "$rewrites" > "$out/$label-$r.txt" 2>&1 &
      pid=$!; overlapped=
      while kill -0 "$pid" 2> /dev/null; do
        sleep 10
        if [ -n "$(busy)" ]; then overlapped=1; echo "stopping $name: $(busy)" >&2; docker kill "$name" > /dev/null 2>&1 || true; fi
      done
      wait "$pid" || true
      [ -z "$overlapped" ] && break
    done
    echo "round $r $label: $(grep -c . "$out/$label-$r.txt") lines, $(grep peak_memory "$out/$label-$r.txt")" >&2
  done
done
python bench/gate1/tregex-ab.py "$out" "${variants[@]%%=*}"
