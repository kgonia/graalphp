#!/usr/bin/env bash
# hot.php's kernels (the functions the K8 differential profile ranked worst, wiki/beat-php.md) on php-cli
# (OPcache + JIT) and on Truffle PHP (one: a single context; shared: PhpMain --contexts=3, a shared engine's code),
# CPUs 14-21 under the CPU lock (unless K8_NOLOCK=1). Output target/$WPN_NAME/hot/NAME.ENGINE.out (lines
# "kernel round ns/op checksum") and .err (ending "peak BYTES"); the table by
# `python -I tests/gate2/kernels/hottable.py target/$WPN_NAME/hot NAME.php NAME.one NAME.shared`.
# A single kernel's value moves by up to 1.8x between runs on this box (issue 101).
#
# Usage: WPN_NAME=.. tests/gate2/kernels/hotbench.sh NAME ROUNDS [KERNELS] [ENGINES]   ENGINES: "php one shared"
# HOT_SCRIPT=ops.php runs that file of this directory in place of hot.php (the elementary operations).
# HOT_CLASSES=target/classes-TAG runs another build's classes of the volume (`native.sh volume` copies them).
# HOT_SAMPLE=SECONDS (the engine only): launcher.GuestSampler's profile from that many seconds on, every 2 ms, in
# NAME.ENGINE.guest.txt (the PHP functions and the built-ins under them): for analysis, never beside a timed run.
set -uo pipefail
cd "$(dirname "$0")/../../.."
root=$(pwd -W 2> /dev/null || pwd)
P=${WPN_NAME:-wpn}
name=$1 rounds=$2 kernels=${3:-} engines=${4:-php one shared}
dir=target/$P/hot
mkdir -p $dir
java="\$GRAALVM_HOME/bin/java --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow"
java+=" \$JAVA_OPTS_BASE -Xmx1g -XX:ReservedCodeCacheSize=512m"
java+=" -Dpolyglot.engine.WarnInterpreterOnly=false"
java+=" -Dpolyglot.php.Parser=java ${JAVA_OPTS:-}"
out=()
if [ -n "${HOT_SAMPLE:-}" ]; then
  out=(-v "$root/$dir:/out")
  java+=" -Dphp.sample.out=/out/$name.\$HOT_ENGINE.guest.txt -Dphp.sample.after=$HOT_SAMPLE -Dphp.sample.period=2"
fi
java+=" -cp '${HOT_CLASSES:-target/classes}:target/lib/*' pl.codeloom.php.launcher.PhpMain --ast-root=/none"
lock=/c/Users/krzgo/Projects/.cpu-14-21.lock
if [ -z "${K8_NOLOCK:-}" ]; then
  until mkdir $lock 2> /dev/null; do
    echo "CPUs 14-21 are taken: $(cat $lock/* 2> /dev/null | head -1); waiting"
    sleep 60
  done
  echo "$P ($(basename "$root")): hotbench.sh $name, since $(date '+%F %T')" > $lock/note
  trap 'rm -rf /c/Users/krzgo/Projects/.cpu-14-21.lock' EXIT
fi
for engine in $engines; do
  case $engine in
    php) image=${FPM_RUN_IMAGE:-gate2-fpm:8.5.12}; cmd="php -d opcache.enable_cli=1" ;;
    one) image=gate2-fcgi:graal25.4; cmd="$java -ddisplay_errors=1 -dhtml_errors=0" ;;
    shared) image=gate2-fcgi:graal25.4; cmd="$java --contexts=3 -ddisplay_errors=1 -dhtml_errors=0" ;;
    *) echo "unknown engine $engine"; exit 2 ;;
  esac
  start=$(date +%s)
  MSYS_NO_PATHCONV=1 docker run --rm --name $P-hot --cpuset-cpus "${K8_CPUS:-14-21}" \
    -v $P-site:/srv:ro -v "$root/tests/gate2/kernels:/s:ro" ${out[@]+"${out[@]}"} -e HOT_ENGINE=$engine     -w /srv $image \
    sh -c "$cmd /s/${HOT_SCRIPT:-hot.php} /srv/target/gate2/wordpress $rounds $kernels; s=\$?; echo peak \$(cat /sys/fs/cgroup/memory.peak) >&2; exit \$s" \
    > $dir/$name.$engine.out 2> $dir/$name.$engine.err || echo "$name $engine: exit $?"
  echo "$name $engine: $(($(date +%s) - start)) s, $(wc -l < $dir/$name.$engine.out) lines, $(tail -n 1 $dir/$name.$engine.err)"
done
