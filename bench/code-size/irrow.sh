#!/usr/bin/env bash
# One row per build: the last compilation of TARGET (default sql, the method of gen.py's fixtures) in SCRIPT: its
# tier, IR nodes after partial evaluation and in the end, machine code bytes, compile time.
# Usage: [TARGET=Query::sql] bench/code-size/irrow.sh "BUILD..." SCRIPT...
#   BUILD: a commit built by bench/check (wordpress-checkhost/target/classes-SHA7), or "." for target/classes
cd "$(dirname "$0")/../.."
builds=$1; shift
for script in "$@"; do
  for b in $builds; do
    if [ "$b" = . ]; then c=target/classes; l=target/lib; else c=../wordpress-checkhost/target/classes-$b; l=../wordpress-checkhost/target/lib-$b; fi
    row=$(bash bench/code-size/irsize.sh "$script" $c $l | grep -a "${TARGET:-sql}" | tail -1 | sed -E 's/.*opt done.*\|(Tier [0-9])\|Time +([0-9]+).*\|IR +([0-9]+)\/ *([0-9]+)\|CodeSize +([0-9]+).*/\1 IR \3 \4 code \5 time \2/; s/.*opt failed.*\|(Tier [0-9])\|.*Reason: (.{0,50}).*/\1 FAILED \2/')
    printf "%-14s %-8s %s\n" "$(basename "$script" .php)" "$b" "$row"
  done
done
