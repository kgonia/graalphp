#!/usr/bin/env bash
# The compilations of a script, forced as CompilationTest forces them: per compilation its tier, time, IR nodes
# (after partial evaluation / in the end) and machine code bytes (wiki/ceiling.md).
# Usage: bench/code-size/irsize.sh FIXTURE|PATH.php [CLASSES [LIB]]
#   FIXTURE: src/test/resources/compile/FIXTURE.php; CLASSES, LIB: a build (default target/classes, target/lib)
#   IR_OPTS: more JVM options, e.g. -Dpolyglot.engine.OSR=false (a script's loop then does not compile the file's
#   top level with the function inlined, which hides the function's own row)
cd "$(dirname "$0")/../.."
script=${1:-query-builder}
case "$script" in *.php) ;; *) script="src/test/resources/compile/$script.php";; esac
export PHP_CLASSES=${2:-target/classes} PHP_LIB=${3:-target/lib}
JAVA_OPTS="-Dpolyglot.engine.BackgroundCompilation=false -Dpolyglot.engine.DynamicCompilationThresholds=false -Dpolyglot.engine.MinInvokeThreshold=1 -Dpolyglot.engine.FirstTierMinInvokeThreshold=1 -Dpolyglot.engine.FirstTierCompilationThreshold=50 -Dpolyglot.engine.LastTierCompilationThreshold=400 -Dpolyglot.engine.OSRCompilationThreshold=1000 -Dpolyglot.engine.TraceCompilation=true -Dpolyglot.engine.CompilationFailureAction=Print -Dpolyglot.compiler.FirstTierUseEconomy=false -Dpolyglot.php.Parser=java ${IR_OPTS:-}" \
  ./bin-php.sh "$script" 2>&1 | grep -a "opt done\|opt failed" | sed -E 's/\|Src.*//; s/engine=[0-9]+ +//' | cut -c1-200
