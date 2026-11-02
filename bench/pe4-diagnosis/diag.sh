#!/usr/bin/env bash
# The diagnosis runs of wiki/pe-round4-diagnosis.md (not timed): woo.sh diag on its own stack (pe4d, CPUs 6-13, its
# database on 12-13), WooCommerce's load (16 users, half buying, 16 threads) unless named wp-*. Logs in
# target/pe4d/diag/RUN/; reports: tests/gate2/pe4_report.py and compile_report.py.
# - cap256m: libgraal's heap capped at 256 MB per isolate, its collections and JVMCI runtimes traced (4 min).
# - d1-trace: everything Optimizing.md lists that needs no CompileOnly: compilation (details, failures, statistics
#   and their details at close), splitting (decisions, polymorphic events, summary), inlining, polymorphism after
#   compilation, transfers, assumptions, performance warnings, HotSpot's deoptimizations (15 min).
# - d2-after, d2-before: the largest compilations only (CompileOnly), each with its inlining, its node and method
#   expansion trees (truffleTier); the merged build and 866e156 (15 min each).
# - wp-after, wp-before: the same for WP_Query::get_posts on plain WordPress (Gate 2's database, front-end pages),
#   both builds (8 min each).
# - d5-sampler: the CPU sampler after 4 minutes, with the time per tier, histogram (10 min; a 6 GB heap: with
#   -Xmx1g the sampler ran out of heap writing its histogram at the engine's close).
# - d4-boundaries: Truffle boundaries called from compiled code, counted (InstrumentBoundaries; 12 min). On 25.4 with
#   libgraal every compilation failed with it (ClassCastException: SnippetObjectConstant), so it has no results.
# - cap256m-long: as cap256m for 15 minutes, until the largest compilations.
# Usage: bench/pe4-diagnosis/diag.sh RUN...
set -euo pipefail
cd "$(dirname "$0")/../.."
export WPN_NAME=pe4d WPN_NET=172.31.82 WPN_DB_PORT=3352 WPN_DB_CPUS=12-13 DEV=1 DEV_CLASSES=target/classes
woo() { bash tests/gate2/woo.sh "$@"; }
TOP="WP_Query::get_posts,WP_Theme_JSON::sanitize,WP_Theme_JSON::get_block_nodes,WP_Term_Query::get_terms"
TOP+=",wp_default_scripts,WooCommerce::includes,wp_render_layout_support_flag,ThunkRootNode,FileRootNode"
TOP+=",CartCheckoutUtils::get_country_data,WC_Data::get_prop,WP_Comment_Query::get_comment_ids"
EXPAND="-Dpolyglot.engine.TraceCompilation=true -Dpolyglot.engine.CompilationFailureAction=Print"
EXPAND+=" -Dpolyglot.compiler.TraceInlining=true -Dpolyglot.compiler.TraceNodeExpansion=truffleTier"
EXPAND+=" -Dpolyglot.compiler.TraceMethodExpansion=truffleTier -Dpolyglot.compiler.NodeExpansionStatistics=truffleTier"
EXPAND+=" -Dpolyglot.compiler.MethodExpansionStatistics=truffleTier -Dpolyglot.compiler.NodeSourcePositions=true"
EXPAND+=" -Dpolyglot.engine.CompilationStatistics=true -Dpolyglot.engine.CompilationStatisticDetails=true"
for run in "$@"; do
  case $run in
    cap256m|cap256m-long)
      JAVA_OPTS="-Dpolyglot.engine.TraceCompilation=true -Dpolyglot.engine.CompilationFailureAction=Print -Djdk.graal.internal.MaxHeapSize=256m -Djdk.graal.internal.PrintGC=true -XX:JVMCITraceLevel=1" woo diag $run $([ $run = cap256m ] && echo 240s || echo 900s) ;;
    d1-trace)
      t="-Dpolyglot.engine.TraceCompilation=true -Dpolyglot.engine.TraceCompilationDetails=true"
      t+=" -Dpolyglot.engine.CompilationFailureAction=Print -Dpolyglot.engine.TraceSplitting=true"
      t+=" -Dpolyglot.engine.SplittingTraceEvents=true -Dpolyglot.engine.TraceSplittingSummary=true"
      t+=" -Dpolyglot.compiler.TraceInlining=true -Dpolyglot.engine.TraceCompilationPolymorphism=true"
      t+=" -Dpolyglot.engine.CompilationStatistics=true -Dpolyglot.engine.CompilationStatisticDetails=true"
      t+=" -Dpolyglot.engine.TraceTransferToInterpreter=true -Dpolyglot.engine.TraceAssumptions=true"
      t+=" -Dpolyglot.compiler.NodeSourcePositions=true -Dpolyglot.compiler.TracePerformanceWarnings=all"
      t+=" -Xlog:deoptimization=debug:file=/out/deopt.log -XX:NativeMemoryTracking=summary"
      JAVA_OPTS="$t" woo diag $run 900s ;;
    d2-after) JAVA_OPTS="$EXPAND -Dpolyglot.engine.CompileOnly=$TOP" woo diag $run 900s ;;
    d2-before) DEV_CLASSES=target/classes-before JAVA_OPTS="$EXPAND -Dpolyglot.engine.CompileOnly=$TOP" woo diag $run 900s ;;
    wp-after|wp-before)
      [ $run = wp-after ] || export DEV_CLASSES=target/classes-before
      DIAG_SEED=gate2-seed JAVA_OPTS="-Dpolyglot.engine.TraceCompilation=true -Dpolyglot.engine.CompileOnly=WP_Query::get_posts -Dpolyglot.compiler.TraceMethodExpansion=truffleTier" woo diag $run 480s
      export DEV_CLASSES=target/classes ;;
    d5-sampler)
      JAVA_OPTS="-Xmx6g -Dpolyglot.cpusampler=true -Dpolyglot.cpusampler.Delay=240000 -Dpolyglot.cpusampler.ShowTiers=true -Dpolyglot.cpusampler.SummariseThreads=true -Dpolyglot.cpusampler.Output=histogram -Dpolyglot.cpusampler.OutputFile=/out/cpusampler.txt -Dpolyglot.engine.TraceCompilation=true" woo diag $run 600s ;;
    d4-boundaries)
      JAVA_OPTS="-Dpolyglot.compiler.InstrumentBoundaries=true -Dpolyglot.compiler.InstrumentationTableSize=100000 -Dpolyglot.engine.TraceCompilation=true" woo diag $run 720s ;;
    *) sed -n 2,21p "$0"; exit 2 ;;
  esac
done
