#!/usr/bin/env bash
# ops.php's kernels as machine code, with the time each instruction took (wiki/ceiling.md): Truffle PHP runs the
# kernels under perf (the cpu-clock event, 10 kHz: this VM has no hardware counters) and prints its compilations
# (asmdump.py), on CPUs 14-21 under the CPU lock, as hotbench.sh. Output in target/$WPN_NAME/perf: NAME.dump (the
# JVM's output: the kernels' lines and the compilations), NAME.ips (the samples), NAME.N.asm (the Nth hottest
# compilation: samples, offset, instruction, under the source positions HotSpot recorded).
#
# Usage: WPN_NAME=.. tests/gate2/kernels/perfasm.sh NAME ROUNDS KERNELS [TOP]   TOP hottest compilations, default 1
# A kernel runs 2-3 s per 600 rounds; take 3000 or more for a few thousand samples an instruction path.
set -uo pipefail
cd "$(dirname "$0")/../../.."
root=$(pwd -W 2> /dev/null || pwd)
P=${WPN_NAME:-wpn}
name=$1 rounds=$2 kernels=$3 top=${4:-1}
dir=target/$P/perf
mkdir -p $dir
image=gate2-fcgi:perf
if ! docker image inspect $image > /dev/null 2>&1; then
  printf 'FROM gate2-fcgi:graal25.4\nUSER root\nRUN apt-get update && apt-get install -y --no-install-recommends linux-perf && rm -rf /var/lib/apt/lists/*\n' \
    | docker build -q -t $image - || exit 1
fi
java="\$GRAALVM_HOME/bin/java --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow"
java+=" \$JAVA_OPTS_BASE -Xmx1g -XX:ReservedCodeCacheSize=512m -Dpolyglot.engine.WarnInterpreterOnly=false"
java+=" -Dpolyglot.php.Parser=java -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints"
java+=" -XX:CompileCommand=print,*OptimizedCallTarget.profiledPERoot"
java+=" -Dpolyglot.compiler.NodeSourcePositions=true -Djdk.graal.TrackNodeSourcePosition=true ${JAVA_OPTS:-}"
java+=" -cp 'target/classes:target/lib/*' pl.codeloom.php.launcher.PhpMain --ast-root=/none"
java+=" -ddisplay_errors=1 -dhtml_errors=0"
lock=/c/Users/krzgo/Projects/.cpu-14-21.lock
until mkdir $lock 2> /dev/null; do
  echo "CPUs 14-21 are taken: $(cat $lock/* 2> /dev/null | head -1); waiting"
  sleep 60
done
echo "$P ($(basename "$root")): perfasm.sh $name, since $(date '+%F %T')" > $lock/note
trap 'rm -rf /c/Users/krzgo/Projects/.cpu-14-21.lock' EXIT
MSYS_NO_PATHCONV=1 docker run --rm --name $P-perf --cap-add PERFMON --cpuset-cpus 14-21 \
  -v $P-site:/srv:ro -v "$root/tests/gate2/kernels:/s:ro" -v "$root/$dir:/out" -w /srv $image \
  sh -c "perf record -q -e cpu-clock:u -F 10000 -o /tmp/perf.data -- $java /s/ops.php x $rounds $kernels > /out/$name.dump 2> /out/$name.err; echo exit \$?; perf script -i /tmp/perf.data -F time,ip > /out/$name.ips 2> /dev/null"
grep -aE '^[a-z_0-9]+ [0-9]+ [0-9.]+ ' $dir/$name.dump | tail -n 3
python -I tests/gate2/kernels/asmdump.py list $dir/$name.dump $dir/$name.ips > $dir/$name.list
head -n 8 $dir/$name.list
n=0
for index in $(head -n "$top" $dir/$name.list | cut -d' ' -f1); do
  n=$((n + 1))
  address=$(python -I tests/gate2/kernels/asmdump.py bin $dir/$name.dump "$index" $dir/$name.$n.bin)
  MSYS_NO_PATHCONV=1 docker run --rm --cpuset-cpus 6-13 -v "$root/$dir:/out" $image \
    sh -c "objdump -D -b binary -m i386:x86-64 -M intel --adjust-vma=$address /out/$name.$n.bin > /out/$name.$n.lst"
  python -I tests/gate2/kernels/asmdump.py merge $dir/$name.dump "$index" $dir/$name.$n.lst $dir/$name.ips \
    > $dir/$name.$n.asm
  rm -f $dir/$name.$n.bin $dir/$name.$n.lst
  echo "$dir/$name.$n.asm: compilation $index, $(head -n 1 $dir/$name.$n.asm)"
done
