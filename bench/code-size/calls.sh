#!/usr/bin/env bash
# The call sites in the machine code of a script's largest compilation, by callee and caller (calls.py). The
# printed compilation stays in target/code-size/calls-SCRIPT-BUILD.dump for calls.py's `seq` and marks.py.
# Usage: bench/code-size/calls.sh BUILD SCRIPT [TOP]   BUILD as irrow.sh's
cd "$(dirname "$0")/../.."
b=$1; script=$2
if [ "$b" = . ]; then c=target/classes; l=target/lib; else c=../wordpress-checkhost/target/classes-$b; l=../wordpress-checkhost/target/lib-$b; fi
mkdir -p target/code-size
out=target/code-size/calls-$(basename "$script" .php)-$b.dump
PHP_CLASSES=$c PHP_LIB=$l JAVA_OPTS="-Dpolyglot.engine.BackgroundCompilation=false -Dpolyglot.engine.DynamicCompilationThresholds=false -Dpolyglot.engine.MinInvokeThreshold=1 -Dpolyglot.engine.FirstTierMinInvokeThreshold=1 -Dpolyglot.engine.FirstTierCompilationThreshold=50 -Dpolyglot.engine.LastTierCompilationThreshold=400 -Dpolyglot.engine.OSRCompilationThreshold=1000 -Dpolyglot.compiler.FirstTierUseEconomy=false -Dpolyglot.php.Parser=java -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints -XX:CompileCommand=print,*OptimizedCallTarget.profiledPERoot -Dpolyglot.compiler.NodeSourcePositions=true -Djdk.graal.TrackNodeSourcePosition=true ${IR_OPTS:-}" \
  ./bin-php.sh "$script" > "$out" 2>&1
python -I bench/code-size/calls.py "$out" ${3:-25}
