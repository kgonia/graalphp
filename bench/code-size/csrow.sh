#!/usr/bin/env bash
# Query::sql's last compilation per tier for a fixture under trial switches: code bytes and IR.
# Usage: bench/code-size/csrow.sh LABEL CLASSES "IR_OPTS" SCRIPT...   (a gen.py fixture each; CLASSES: target/classes[-TAG])
cd "$(dirname "$0")/../.."
label=$1 classes=$2 opts=$3; shift 3
for script in "$@"; do
  out=$(IR_OPTS="-Dpolyglot.engine.OSR=false $opts" bash bench/code-size/irsize.sh "$script" $classes target/lib | grep -a "Query::sql")
  t1=$(echo "$out" | grep -a "Tier 1" | tail -1 | sed -E 's/.*\|Time +([0-9]+).*\|IR +([0-9]+)\/ *([0-9]+)\|CodeSize +([0-9]+).*/code \4 IR \2\/\3 ms \1/')
  t2=$(echo "$out" | grep -a "Tier 2" | tail -1 | sed -E 's/.*\|Time +([0-9]+).*\|IR +([0-9]+)\/ *([0-9]+)\|CodeSize +([0-9]+).*/code \4 IR \2\/\3 ms \1/')
  printf "%-12s %-8s T1 %-34s T2 %s\n" "$(basename "$script" .php)" "$label" "$t1" "$t2"
done
