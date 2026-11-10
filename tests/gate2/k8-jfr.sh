#!/usr/bin/env bash
# k8-cli.sh's loop of the engine under a JFR recording and the compilation trace, for JfrSplit.java
# (wiki/ceiling.md): where a render's time is by the machine code that ran. Never a timed run: the recording and
# the trace cost time.
#
# The recording starts DELAY_S seconds after the process (so: after the compile queue has drained; see the trace;
# 0 records from the start) and has -XX:+DebugNonSafepoints, so the frames inlined into a compilation are the
# sample's own and not the nearest safepoint's. The JVM options are k8-cli.sh's (G1, 1 GB) and JAVA_OPTS after
# them: give the options of the run the split is for. With G1, WP_Query::get_posts is 765 KB of machine code
# (492 KB with ParallelGC) and HotSpot does not install it ("code is too large", issues/107): its time shows as
# the interpreter's.
#
# Output: target/$WPN_NAME/jfrcli/NAME/{rec.jfr,engine.log,out.json,err.txt}; NAME's directory is emptied first.
# Usage: WPN_NAME=.. WPN_NET=.. [JAVA_OPTS=..] tests/gate2/k8-jfr.sh NAME one|shared N PAGE DELAY_S
#        java tests/gate2/JfrSplit.java target/$WPN_NAME/jfrcli/NAME/rec.jfr 0 60 main src/main/java
set -uo pipefail
cd "$(dirname "$0")/../.."
root=$(pwd -W 2> /dev/null || pwd)
P=${WPN_NAME:-wpn}
NET=${WPN_NET:-172.30.0}
name=$1 engine=$2 n=$3 page=$4 delay=$5
dir=target/$P/jfrcli/$name
mkdir -p $dir
rm -f $dir/rec.jfr $dir/engine.log $dir/out.json $dir/err.txt
java="\$GRAALVM_HOME/bin/java --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow"
java+=" \$JAVA_OPTS_BASE -Xmx1g -XX:TrimNativeHeapInterval=5000 -XX:G1PeriodicGCInterval=10000"
java+=" -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30 -XX:ReservedCodeCacheSize=512m"
java+=" -Djava.net.preferIPv6Addresses=system -Dpolyglot.engine.WarnInterpreterOnly=false"
java+=" -Dpolyglot.php.Parser=java"
rec="filename=/out/rec.jfr,settings=profile"
[ "$delay" != 0 ] && rec+=",delay=${delay}s"
java+=" -XX:StartFlightRecording=$rec"
java+=" -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints"
java+=" -Dpolyglot.engine.TraceCompilation=true -Dpolyglot.engine.CompilationFailureAction=Print"
java+=" -Dpolyglot.engine.TraceTransferToInterpreter=false -Dpolyglot.log.file=/out/engine.log"
java+=" ${JAVA_OPTS:-} -cp '${K8_CLASSES:-target/classes}:target/lib/*' pl.codeloom.php.launcher.PhpMain --ast-root=/none"
case $engine in
  one) cmd="$java -ddisplay_errors=1 -dhtml_errors=0" ;;
  shared) cmd="$java --contexts=${K8_CONTEXTS:-2} -ddisplay_errors=1 -dhtml_errors=0" ;;
  *) echo "unknown engine $engine"; exit 2 ;;
esac
lock=/c/Users/krzgo/Projects/.cpu-14-21.lock
if [ -z "${K8_NOLOCK:-}" ]; then
  until mkdir $lock 2> /dev/null; do
    echo "CPUs 14-21 are taken: $(cat $lock/* 2> /dev/null | head -1); waiting"; sleep 60
  done
  echo "claude-wordpress-$P ($(basename "$root")): k8-jfr.sh $name, since $(date '+%F %T')" > $lock/note
  trap 'rm -rf /c/Users/krzgo/Projects/.cpu-14-21.lock' EXIT
fi
start=$(date +%s)
MSYS_NO_PATHCONV=1 docker run --rm --name $P-jfrcli --cpuset-cpus "${K8_CPUS:-14-21}" --network $P-net \
  --add-host host.docker.internal:$NET.2 -v $P-site:/srv:ro -v "$root/tests/gate2:/t:ro" \
  -v "$root/$dir:/out" -w /srv gate2-fcgi:graal25.4 \
  sh -c "$cmd /t/k8-cli.php '$page' $n; s=\$?; echo peak \$(cat /sys/fs/cgroup/memory.peak) >&2; exit \$s" \
  > $dir/out.json 2> $dir/err.txt || echo "$name: exit $?"
echo "$name: $(($(date +%s) - start)) s, $(wc -c < $dir/out.json) bytes, $(tail -1 $dir/err.txt)"
