#!/usr/bin/env bash
# The A/B of wiki/bytecode-dsl.md's "What a bytecode compilation costs": Gate 2's paced visits (native.sh measure, one
# server just started per run on CPUs 14-21, its database on 22-23) for VARIANTS of jvm-java.VARIANT, interleaved,
# the order reversed every round; the CPU lock (C:\Users\krzgo\Projects\.cpu-14-21.lock) taken per round and let go
# after it. The host's CPU load is logged per run (native.sh: target/bc-d/runs/NAME.host) and before each round.
# Variants: base (bytecode, target/classes-base), fix (bytecode, target/classes-fix), tree (the tree, classes-fix).
# - run ROUNDS VISITS VARIANT...; report: tests/gate2/visits_report.py over the runs.
# - gate1 ITERATIONS ROUNDS: Gate 1's kernels, base, fix and the tree (fix@-Dpolyglot.php.Interpreter=tree).
# Usage: bench/bc-compile-cost/ab.sh run ROUNDS VISITS VARIANT... | gate1 ITERATIONS ROUNDS | report
set -euo pipefail
export PATH=/usr/bin:/bin:/mingw64/bin:$PATH
cd "$(dirname "$0")/../.."
export WPN_NAME=bc-d WPN_NET=172.31.121 WPN_DB_PORT=3391 WPN_DB_CPUS=4-5 WPN_AUX_CPUS=6-13
export CLASSES_base=target/classes-base CLASSES_fix=target/classes-fix CLASSES_tree=target/classes-fix
export JAVA_OPTS_tree=-Dpolyglot.php.Interpreter=tree
CPU_LOCK=/c/Users/krzgo/Projects/.cpu-14-21.lock
host_cpu() {
  powershell -NoProfile -Command \
    "[math]::Round((Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average).Average)" \
    2> /dev/null | tr -d '\r'
}
case ${1:-} in
  run)
    rounds=$2 visits=$3; shift 3
    variants=("$@")
    mkdir -p target/bc-d/runs
    for r in $(seq "$rounds"); do
      if [ $((r % 2)) = 1 ]; then order="${variants[*]}"; else order=$(printf '%s\n' "${variants[@]}" | tac | tr '\n' ' '); fi
      until mkdir $CPU_LOCK 2> /dev/null; do echo "CPUs 14-21 taken: $(cat $CPU_LOCK/* 2> /dev/null | head -1)"; sleep 60; done
      echo "claude-wordpress-bccompile (wordpress-bccompile): Gate 2 visits round $r of ${variants[*]}, since $(date '+%F %T')" > $CPU_LOCK/note
      trap 'rm -rf $CPU_LOCK' EXIT
      echo "round $r ($order): host CPU before $(host_cpu)%" | tee -a target/bc-d/rounds.txt
      for v in $order; do
        # one run per call, renamed to its round (measure names it VARIANT-1)
        CPU_LOCK_HELD=1 HOST_QUIET=101 HOST_LOADED=101 bash tests/gate2/native.sh measure 1 "$visits" 1 jvm-java.$v \
          | tee -a target/bc-d/rounds.txt
        for f in target/bc-d/runs/jvm-java.$v-1.*; do mv "$f" "${f/jvm-java.$v-1./jvm-java.$v-r$r.}"; done
      done
      rm -rf $CPU_LOCK
      trap - EXIT
    done
    for f in target/bc-d/runs/jvm-java.*-r[0-9].*; do mv "$f" "$(echo "$f" | sed -E 's/-r([0-9])\./-\1./')"; done ;;
  gate1)
    # Gate 1's kernels (bench/gate1/ab.sh all ITERATIONS ROUNDS): base, fix and the tree, under the CPU lock
    until mkdir $CPU_LOCK 2> /dev/null; do echo "CPUs 14-21 taken: $(cat $CPU_LOCK/* 2> /dev/null | head -1)"; sleep 60; done
    echo "claude-wordpress-bccompile (wordpress-bccompile): Gate 1 A/B, since $(date '+%F %T')" > $CPU_LOCK/note
    trap 'rm -rf $CPU_LOCK' EXIT
    echo "Gate 1: host CPU before $(host_cpu)%"
    AB_OUT=target/bc-d/gate1 bash bench/gate1/ab.sh all $2 $3 base fix fix@-Dpolyglot.php.Interpreter=tree ;;
  report) python -I tests/gate2/visits_report.py target/bc-d/runs jvm-java.tree jvm-java.base jvm-java.fix ;;
  *) sed -n 2,9p "$0"; exit 2 ;;
esac
