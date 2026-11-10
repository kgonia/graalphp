#!/usr/bin/env bash
# Does the machine run at one speed? calib.php (a fixed piece of integer arithmetic, timed chunk by chunk for
# SECONDS) on CPUSET, in php-cli of the php-fpm image (default) or in PhpMain of the JVM image (the servers'
# options, as k8-cli.sh), under the CPU lock (unless K8_NOLOCK=1). Issue 101: on this box a core runs at two speeds
# (1 : 1.8-2, switching every 0.3-0.4 s), and a timing is read against a calibration run made beside it.
# Output target/$WPN_NAME/calib/NAME.json (and .err); the table by `python -I tests/gate2/calib.py FILE.json...`.
#
# Usage: WPN_NAME=.. tests/gate2/calib.sh NAME CPUSET SECONDS [php|jvm]
set -uo pipefail
cd "$(dirname "$0")/../.."
root=$(pwd -W 2> /dev/null || pwd)
P=${WPN_NAME:-wpn}
name=$1 cpus=$2 seconds=$3 engine=${4:-php}
dir=target/$P/calib
mkdir -p $dir
lock=/c/Users/krzgo/Projects/.cpu-14-21.lock
if [ -z "${K8_NOLOCK:-}" ]; then
  until mkdir $lock 2> /dev/null; do
    echo "CPUs 14-21 are taken: $(cat $lock/* 2> /dev/null | head -1); waiting"
    sleep 60
  done
  echo "$P ($(basename "$root")): calib.sh $name, since $(date '+%F %T')" > $lock/note
  trap 'rm -rf /c/Users/krzgo/Projects/.cpu-14-21.lock' EXIT
fi
if [ "$engine" = jvm ]; then
  java="\$GRAALVM_HOME/bin/java --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow"
  java+=" \$JAVA_OPTS_BASE -Xmx1g -XX:TrimNativeHeapInterval=5000 -XX:G1PeriodicGCInterval=10000"
  java+=" -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30 -XX:ReservedCodeCacheSize=512m"
  java+=" -Dpolyglot.engine.WarnInterpreterOnly=false -Dpolyglot.php.Parser=java ${JAVA_OPTS:-}"
  java+=" -cp 'target/classes:target/lib/*' pl.codeloom.php.launcher.PhpMain --ast-root=/none"
  MSYS_NO_PATHCONV=1 docker run --rm --name $P-calib --cpuset-cpus "$cpus" -v $P-site:/srv:ro \
    -v "$root/tests/gate2:/t:ro" -w /srv gate2-fcgi:graal25.4 sh -c "$java /t/calib.php $seconds" \
    > $dir/$name.json 2> $dir/$name.err || echo "calib $name: exit $?"
else
  MSYS_NO_PATHCONV=1 docker run --rm --name $P-calib --cpuset-cpus "$cpus" -v "$root/tests/gate2:/t:ro" \
    ${FPM_RUN_IMAGE:-gate2-fpm:8.5.12} php -d opcache.enable_cli=1 ${PHP_OPTS:-} /t/calib.php $seconds \
    > $dir/$name.json 2> $dir/$name.err || echo "calib $name: exit $?"
fi
echo "calib $name ($engine) on $cpus: $(wc -c < $dir/$name.json) bytes"
