#!/usr/bin/env bash
# K8's loop without a server (wiki/beat-php.md): one process, one request of N renders of a page
# (tests/gate2/k8-cli.php, tests/gate2/k8-plugin.php). WordPress is booted once and its context lives as long as the
# loop: what a deployment that kept a booted WordPress between requests would run, and the engine with all the time
# it wants to compile.
#
# ENGINE: php     php-cli of the php-fpm image, OPcache and its tracing JIT on (opcache.enable_cli);
#         one     launcher.PhpMain with its one context: the engine's single-context mode (a site's caches keep what
#                 they found as constants);
#         shared  PhpMain --contexts=2: a shared engine as the servers', so code that any context may run; the first
#                 context's loop warms the code up, the second's is the one reported.
# Per page a file target/$WPN_NAME/k8cli/NAME.INDEX.json (the plugin's answer: every iteration's time, length and
# checksum) and NAME.INDEX.err (the process's stderr, then "peak BYTES": the container's memory.peak).
# The server on K8_CPUS (default 14-21, under the CPU lock unless K8_NOLOCK=1), the database as native.sh's (reset
# first: `native.sh reset-db`), the site from native.sh's volume. JAVA_OPTS: more JVM options; PHP_OPTS: more of php's
# (`-d opcache.jit=disable`: PHP without its JIT). The table by
# `k8_cli_report.py`.
#
# Usage: WPN_NAME=.. WPN_NET=.. tests/gate2/k8-cli.sh ENGINE NAME N [PAGE...]
set -euo pipefail
cd "$(dirname "$0")/../.."
root=$(pwd -W 2> /dev/null || pwd)
P=${WPN_NAME:-wpn}
NET=${WPN_NET:-172.30.0}
engine=$1 name=$2 n=$3
shift 3
pages=("$@")
[ ${#pages[@]} -gt 0 ] || pages=(/ "/?p=1" "/?page_id=2" "/?cat=1" "/?s=hello")
dir=target/$P/k8cli
mkdir -p $dir
# the servers' JVM options (native.sh)
java="\$GRAALVM_HOME/bin/java --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow"
java+=" \$JAVA_OPTS_BASE -Xmx1g -XX:TrimNativeHeapInterval=5000 -XX:G1PeriodicGCInterval=10000"
java+=" -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30 -XX:ReservedCodeCacheSize=512m"
java+=" -Djava.net.preferIPv6Addresses=system -Dpolyglot.engine.WarnInterpreterOnly=false"
java+=" -Dpolyglot.php.Parser=java ${JAVA_OPTS:-} -cp 'target/classes:target/lib/*' pl.codeloom.php.launcher.PhpMain"
java+=" --ast-root=/none"
case $engine in
  php) image=${FPM_RUN_IMAGE:-gate2-fpm:8.5.12}; cmd="php -d opcache.enable_cli=1 ${PHP_OPTS:-}" ;;
  one) image=gate2-fcgi:graal25.4; cmd="$java -ddisplay_errors=1 -dhtml_errors=0" ;;
  shared) image=gate2-fcgi:graal25.4; cmd="$java --contexts=2 -ddisplay_errors=1 -dhtml_errors=0" ;;
  *) echo "unknown engine $engine"; exit 2 ;;
esac
lock=/c/Users/krzgo/Projects/.cpu-14-21.lock
if [ -z "${K8_NOLOCK:-}" ]; then
  until mkdir $lock 2> /dev/null; do
    echo "CPUs 14-21 are taken: $(cat $lock/* 2> /dev/null | head -1); waiting"; sleep 60
  done
  echo "claude-wordpress-$P ($(basename "$root")): k8-cli.sh $engine $name, since $(date '+%F %T')" > $lock/note
  trap 'rm -rf /c/Users/krzgo/Projects/.cpu-14-21.lock' EXIT
fi
i=0
for page in "${pages[@]}"; do
  i=$((i + 1))
  start=$(date +%s)
  MSYS_NO_PATHCONV=1 docker run --rm --name $P-k8cli --cpuset-cpus "${K8_CPUS:-14-21}" --network $P-net \
    --add-host host.docker.internal:$NET.2 -v $P-site:/srv:ro -v "$root/tests/gate2:/t:ro" -w /srv $image \
    sh -c "$cmd /t/k8-cli.php '$page' $n; s=\$?; echo peak \$(cat /sys/fs/cgroup/memory.peak) >&2; exit \$s" \
    > $dir/$name.$i.json 2> $dir/$name.$i.err || echo "$name $page: exit $?"
  echo "$name $page: $(($(date +%s) - start)) s, $(wc -c < $dir/$name.$i.json) bytes, $(tail -1 $dir/$name.$i.err)"
done
