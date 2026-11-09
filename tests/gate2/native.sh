#!/usr/bin/env bash
# Gate 2's WordPress on native image with our own parser (php.Parser=java), against PHP 8.5 and the JVM.
# - One MariaDB of its own, wpn-mariadb, seeded from a dump of gate2-mariadb's wordpress database (read only:
#   gate2-mariadb isn't written), on a network of its own (wpn-net: the database 172.30.0.2, a server 172.30.0.10).
#   Every server container maps host.docker.internal to it, so wp-config.php's default DB_HOST
#   (host.docker.internal:3307) reaches it directly, for every engine alike. (HttpMain gives PHP only the request's
#   CGI variables, as php-fpm does, so browse.sh's GATE2_DB_HOST never reached Truffle PHP.)
# - The site, its ASTs, the classes, the jars and the native build are copied into a Docker volume, wpn-site, so no
#   server reads the Windows bind mount.
# - Variants: php (PHP 8.5.12, OPcache with the tracing JIT, php -S with 4 workers; php-nojit without the JIT,
#   php-noopc without OPcache), jvm-export (the tree, the php-ast export), jvm-java (the tree, our parser), jvm-bc
#   (bytecode, our parser), native-java, native-bc; jvm-main, native-main: another build's tree on our parser
#   (target/classes-main and target/native/wp-http-main, for comparing two builds); jvm-phases, native-phases:
#   jvm-java and native-java with the phase counters (target/native/wp-http-phases, built with -Dphp.phases=true
#   -Dphp.nativeclock=true). The java-parser variants get no AST root, so a file that needed the export would fail.
# - The deployment's variants, nginx in front of each (tests/gate2/woo-fpm/nginx.conf, port 8080, FastCGI on
#   127.0.0.1:9000 inside the container, which holds both, so its CPU and memory count nginx for every variant):
#   fpm (php-fpm 8.5.12, OPcache with the tracing JIT, pm=static with WPN_THREADS workers; image gate2-fpm:8.5.12
#   from tests/gate2/woo-fpm), jvm-fcgi, native-fcgi (launcher.FastCgiMain on the JVM, or natively as
#   target/native/wp-fcgi: NI_MAIN=pl.codeloom.php.launcher.FastCgiMain NI_NAME=wp-fcgi; our parser, image
#   gate2-fcgi:graal25.4 from tests/gate2/fcgi). The images are built by `images`.
# Run after tests/gate2/fetch.sh (or a copy of the main checkout's site), mvn compile and bench/native's build of
# the HTTP launcher (NI_MAIN=pl.codeloom.php.launcher.HttpMain NI_NAME=wp-http).
# - measure: rounds of paced visits (tests/gate2/visits.py, CPUs 0-5), the server on CPUs 14-21, the variants
#   interleaved and each round's order reversed, every run on a server just started and a database just reset.
#   Runs: target/wpn/runs/<variant>-<round>.json, tables by tests/gate2/visits_report.py.
# Usage: tests/gate2/native.sh db | volume | agent | check | reset-db | start VARIANT [CPUSET] [PORT] | stop VARIANT
#        | pages VARIANT PORT | compare A B | measure ROUNDS VISITS PAUSE VARIANT... | load ROUNDS VARIANT... | images
#        | clean
set -euo pipefail
cd "$(dirname "$0")/../.."
source tests/gate2/mask.sh
root=$(pwd -W 2>/dev/null || pwd)
# WPN_DB_SOURCE: the container the seed is dumped from (default gate2-mariadb); WPN_DB_DUMP: a dump file to seed
# from instead (another session's seed.sql), when that container isn't running.
# WPN_DB_CPUS (default 22-23), WPN_AUX_CPUS (default 0-5): the database's CPUs, and those of the volume's copy, so a
# session can keep off the CPUs another session is timing on.
# WPN_NAME, WPN_NET, WPN_DB_PORT: another prefix for the containers, network and volume, another /24 for the
# network and another host port for the database, so a second session runs beside this one (default wpn, 172.30.0, 3308)
P=${WPN_NAME:-wpn}
NET=${WPN_NET:-172.30.0}
out=target/$P
db=$P-mariadb
# browse.sh's JVM options; the native server gets the same heap limit
SERVER_OPTS="-Xmx1g -XX:TrimNativeHeapInterval=5000 -XX:G1PeriodicGCInterval=10000 -XX:MinHeapFreeRatio=10"
SERVER_OPTS+=" -XX:MaxHeapFreeRatio=30 -XX:ReservedCodeCacheSize=512m"
JAVA="exec \$GRAALVM_HOME/bin/java --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow"
JAVA+=" \$JAVA_OPTS_BASE $SERVER_OPTS -Djava.net.preferIPv6Addresses=system -Dpolyglot.engine.WarnInterpreterOnly=false"
JAVA+=" -cp 'target/classes:target/lib/*'"
LAUNCHER=pl.codeloom.php.launcher.HttpMain
# errors shown as php -S shows them (http.sh's)
HTTP_ARGS="--port=8080 --threads=${WPN_THREADS:-4} --docroot=target/gate2/wordpress --server-name=0.0.0.0 --host=0.0.0.0"
HTTP_ARGS+=" -ddisplay_errors=1 -dhtml_errors=0"
# the FastCGI server behind nginx, with the php-fpm image's php.ini error settings (tests/gate2/woo-fpm/php.ini)
FCGI_LAUNCHER=pl.codeloom.php.launcher.FastCgiMain
FCGI_ARGS="--listen=127.0.0.1:9000 --threads=${WPN_THREADS:-4} -ddisplay_errors=1 -dhtml_errors=0 -dlog_errors=1"
FPM_IMAGE=gate2-fpm:8.5.12
FCGI_IMAGE=gate2-fcgi:graal25.4
pages=(/ "/?p=1" "/?page_id=2" "/?cat=1" "/?feed=rss2" /wp-login.php "/?rest_route=/" "/?rest_route=/wp/v2/posts"
       "/?s=hello" /nonexistent-page/ "/?fatal=exception" "/?fatal=error")
admin=(/wp-admin/ /wp-admin/edit.php /wp-admin/post-new.php /wp-admin/options-general.php /wp-admin/users.php
       /wp-admin/plugins.php /wp-admin/profile.php)

# MariaDB 11.4 on CPUs 22-23, port 3307 inside (wp-config.php's), 3308 on the host
start_db() {
  docker rm -f $db > /dev/null 2>&1 || true
  docker network inspect $P-net > /dev/null 2>&1 || docker network create --subnet $NET.0/24 $P-net > /dev/null
  mkdir -p $out
  if [ -n "${WPN_DB_DUMP:-}" ]; then
    cp "$WPN_DB_DUMP" $out/wordpress.sql
  else
    docker exec ${WPN_DB_SOURCE:-gate2-mariadb} mariadb-dump -uroot -proot --single-transaction --skip-dump-date \
      --databases wordpress > $out/wordpress.sql
  fi
  docker run -d --name $db --cpuset-cpus ${WPN_DB_CPUS:-22-23} --network $P-net --ip $NET.2 -p ${WPN_DB_PORT:-3308}:3307 \
    -e MARIADB_ROOT_PASSWORD=root -e MARIADB_DATABASE=wordpress -e MARIADB_USER=wp -e MARIADB_PASSWORD=wp mariadb:11.4 \
    --port=3307 > /dev/null
  for _ in $(seq 60); do
    docker exec $db mariadb -uwp -pwp -P3307 -e 'select 1' wordpress > /dev/null 2>&1 && break
    sleep 1
  done
  docker exec -i $db mariadb -uroot -proot -P3307 < $out/wordpress.sql
  # the dump's admin carries 1,921 session tokens from Gate 2's browser visits (600 KB, unserialized on every
  # logged-in request); the copy starts with none
  docker exec $db mariadb -uroot -proot -P3307 wordpress -e "delete from wp_usermeta where meta_key='session_tokens'"
  docker exec $db mariadb-dump -uroot -proot -P3307 --skip-dump-date --databases wordpress > $out/seed.sql
  echo "$db ready: $(docker exec $db mariadb -uroot -proot -P3307 -N wordpress -e 'select count(*) from wp_posts')" \
    "posts"
}

# the database as seeded: before each server, so logins (session tokens), transients and cron start alike
reset_db() {
  docker exec -i $db mariadb -uroot -proot -P3307 < $out/seed.sql
}

volume() {
  cp tests/gate2/wp-config.php target/gate2/wordpress/wp-config.php
  mkdir -p target/gate2/wordpress/wp-content/mu-plugins
  cp tests/gate2/fatal-plugin.php target/gate2/wordpress/wp-content/mu-plugins/fatal-plugin.php
  MSYS_NO_PATHCONV=1 CPUSET=${WPN_AUX_CPUS:-0-5} bash bench/gate1/php.sh php tools/ast-export.php target/ast \
    target/gate2/wordpress/wp-config.php target/gate2/wordpress/wp-content/mu-plugins/fatal-plugin.php > /dev/null
  docker volume create $P-site > /dev/null
  MSYS_NO_PATHCONV=1 docker run --rm --cpuset-cpus ${WPN_AUX_CPUS:-0-5} -v $P-site:/srv -v "$root:/w:ro" alpine:3 sh -c '
    rm -rf /srv/target && mkdir -p /srv/target/gate2 /srv/target/ast/target /srv/target/native
    cp -r /w/target/gate2/wordpress /srv/target/gate2/ && cp -r /w/target/ast/target/gate2 /srv/target/ast/target/
    cp -r /w/target/classes /srv/target/classes && cp -r /w/target/lib /srv/target/lib
    for f in /w/target/native/wp-http* /w/target/native/wp-fcgi*; do [ ! -f "$f" ] || cp "$f" /srv/target/native/; done
    for d in classes-main classes-s2; do [ ! -d /w/target/$d ] || cp -r /w/target/$d /srv/target/$d; done
    cp /w/bench/native/check.php /srv/target/gate2/wordpress/wpn-check.php
    # the Gate 1 kernels, served (tests/gate2/kernels.php): the compile diagnosis runs them on the HTTP launcher
    rm -rf /srv/bench && mkdir -p /srv/bench && cp -r /w/bench/gate1 /srv/bench/gate1
    cp /w/tests/gate2/kernels.php /srv/target/gate2/wordpress/gate1-kernels.php
    du -sh /srv/target/*'
}

# bench/native/check.php through the native HTTP server: its downcalls are constants too
check() {
  start native-java 0-5 8097
  curl -s -H 'Host: localhost' http://127.0.0.1:8097/wpn-check.php
  stop native-java
}

# the server of a variant: container $P-VARIANT on CPUSET, port 8080 inside, PORT on the host
start() {
  local name=$1 cpus=${2:-0-5} port=${3:-8090} image=${TRUFFLE_IMAGE:-gate1-truffle:8.5-graal25.4} cmd opts= env=()
  # VARIANT.TAG: the variant with the tag's own settings, so one measurement compares builds and options:
  # CLASSES_TAG, NATIVE_BIN_TAG, JAVA_OPTS_TAG (added to JAVA_OPTS), e.g. native-phases.base with
  # NATIVE_BIN_base=wp-http-phases-base
  local variant=${name%%.*} tag= setting
  [ "$variant" = "$name" ] || tag=${name#*.}
  if [ -n "$tag" ]; then
    setting=CLASSES_$tag; [ -z "${!setting:-}" ] || local CLASSES=${!setting}
    setting=NATIVE_BIN_$tag; [ -z "${!setting:-}" ] || local NATIVE_BIN=${!setting}
    setting=JAVA_OPTS_$tag; [ -z "${!setting:-}" ] || local JAVA_OPTS="${JAVA_OPTS:-} ${!setting}"
  fi
  case $variant in
    *-java|*-main) opts="-Dpolyglot.php.Parser=java" ;;
    # the phase counters (wiki/visit-phases.md): on the JVM the properties, natively a build made with them
    # (target/native/wp-http-phases: the flags are static finals, fixed at build time)
    *-phases) opts="-Dpolyglot.php.Parser=java -Dphp.phases=true -Dphp.nativeclock=true" ;;
    *-bc|*-fcgi) opts="-Dpolyglot.php.Parser=java" ;;
  esac
  case $variant in
    # php-nojit: OPcache without its JIT; php-noopc: no OPcache (each request compiles every file it includes)
    php|php-nojit|php-noopc) image=bootshare-php:8.5; env=(-e PHP_CLI_SERVER_WORKERS=4)
         local ini=
         [ $variant != php-nojit ] || ini="-d opcache.jit=disable"
         [ $variant != php-noopc ] || ini="-d opcache.enable=0 -d opcache.enable_cli=0"
         cmd="cd target/gate2/wordpress && exec php -d auto_prepend_file= $ini -S 0.0.0.0:8080" ;;
    fpm) image=$FPM_IMAGE; env=(-e FPM_CHILDREN=${WPN_THREADS:-4})
         cmd="exec /usr/local/bin/woo-fpm-start" ;;
    jvm-fcgi) image=$FCGI_IMAGE
              cmd="exec /usr/local/bin/fcgi-start ${JAVA#exec } $opts ${JAVA_OPTS:-} $FCGI_LAUNCHER $FCGI_ARGS"
              cmd+=" --ast-root=/none" ;;
    native-fcgi) image=$FCGI_IMAGE
                 cmd="exec /usr/local/bin/fcgi-start target/native/${NATIVE_BIN:-wp-fcgi} \$JAVA_OPTS_BASE -Xmx1g $opts"
                 cmd+=" ${JAVA_OPTS:-} -Djava.net.preferIPv6Addresses=system $FCGI_ARGS --ast-root=/none" ;;
    jvm-export) cmd="$JAVA ${JAVA_OPTS:-} $LAUNCHER $HTTP_ARGS --ast-root=target/ast" ;;
    jvm-main) cmd="${JAVA/target\/classes:/target/classes-main:} $opts ${JAVA_OPTS:-} $LAUNCHER $HTTP_ARGS"
              cmd+=" --ast-root=/none" ;;
    jvm-*) cmd="$JAVA $opts ${JAVA_OPTS:-} $LAUNCHER $HTTP_ARGS --ast-root=/none" ;;
    native-*) local binary=wp-http
              [ "$variant" != native-main ] || binary=wp-http-main
              [ "$variant" != native-phases ] || binary=wp-http-phases
              # NATIVE_BIN: another build of target/native for this variant (wp-http-phases-base, ...)
              [ -z "${NATIVE_BIN:-}" ] || binary=$NATIVE_BIN
              cmd="exec target/native/$binary \$JAVA_OPTS_BASE -Xmx1g $opts ${JAVA_OPTS:-}"
              cmd+=" -Djava.net.preferIPv6Addresses=system $HTTP_ARGS --ast-root=/none" ;;
    *) echo "unknown variant $variant"; exit 2 ;;
  esac
  # CLASSES, NATIVE: another build's classes (under target/) or native launcher, for profiling a build of its own
  [ -z "${CLASSES:-}" ] || cmd=${cmd/target\/classes:/$CLASSES:}
  [ -z "${NATIVE:-}" ] || cmd=${cmd/target\/native\/wp-http /$NATIVE }
  # OUT_DIR: a host directory the server may write to, at /out (a profiler's output)
  local extra=()
  [ -z "${OUT_DIR:-}" ] || extra=(-v "$root/$OUT_DIR:/out")
  # AGENT=1: the JVM under native-image-agent, merging what it reaches into target/native/config
  local agent=()
  if [ -n "${AGENT:-}" ]; then
    agent=(-v "$root/target/native/config:/agent")
    cmd=${cmd/$LAUNCHER/-agentlib:native-image-agent=config-merge-dir=/agent $LAUNCHER}
  fi
  docker rm -f $P-$name > /dev/null 2>&1 || true
  MSYS_NO_PATHCONV=1 docker run -d --name $P-$name --cpuset-cpus "$cpus" -p "$port:8080" --network $P-net \
    --ip $NET.10 --add-host host.docker.internal:$NET.2 "${env[@]}" "${agent[@]}" "${extra[@]}" \
    -v $P-site:/srv:ro -w /srv \
    $image sh -c "$cmd" > /dev/null
  [ -z "${NOWAIT:-}" ] || return 0
  for _ in $(seq 300); do
    curl -s -o /dev/null "http://127.0.0.1:$port/wp-includes/css/dashicons.css" && return 0
    sleep 0.2
  done
  echo "$P-$name did not start"; docker logs $P-$name 2>&1 | tail -20; return 1
}

stop() {
  # a JVM under the agent writes its configuration as it exits
  docker stop -t 60 $P-$1 > /dev/null 2>&1 || true
  docker rm -f $P-$1 > /dev/null 2>&1 || true
}

# the native build's metadata: bench/native/agent.sh (the kernels and smoke tests, on both interpreters), then the
# HTTP launcher serving the pages and wp-admin (tree and bytecode on our parser, the tree on the export). The foreign
# calls the agent records are dropped, as agent.sh drops them.
agent() {
  [ -f bench/native/agent.sh ] || { echo "no native-image build on this branch (bench/native)"; exit 2; }
  local opts variant
  rm -rf target/native/config && mkdir -p target/native/config $out
  MSYS_NO_PATHCONV=1 docker run --rm --cpuset-cpus 0-5 -v "$root:/w" -w /w \
    gate1-truffle:8.5-graal25.4 sh bench/native/agent.sh > "$out/agent-kernels.log" 2>&1
  for variant in jvm-java jvm-bc jvm-export; do
    reset_db
    AGENT=1 start $variant 0-5 8095
    pages $variant 8095 > /dev/null
    stop $variant
  done
  MSYS_NO_PATHCONV=1 docker run --rm -v "$root:/w" -w /w gate1-truffle:8.5-graal25.4 php -r '
    $f = $argv[1]; $j = json_decode(file_get_contents($f), true); unset($j["foreign"]);
    file_put_contents($f, json_encode($j, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES));' \
    target/native/config/reachability-metadata.json
  ls -la target/native/config
}

# mask(), and the clock's time and date in wp-admin's format previews (options-general.php): the variants' pages are
# fetched one server after another, minutes apart, not side by side as http.sh's
mask_seq() {
  mask | sed -E 's/(class="date-time-text format-i18n">|<span class="example">)[^<]*/TIME/g'
}

# fetches the pages and wp-admin, logged in as admin with a cookie jar of its own: target/wpn/pages/VARIANT/, each
# page's status line, Location and masked body
fetch() { # dir name page curl-args...
  local dir=$1 name=$2 page=$3
  shift 3
  curl -s -H 'Host: localhost' -D "$dir/$name.headers" "$@" "http://127.0.0.1:$port$page" | mask_seq > "$dir/$name.html"
  { head -1 "$dir/$name.headers" | cut -d' ' -f2; { grep -i '^location:' "$dir/$name.headers" || true; } | tr -d '\r' \
    | mask; } > "$dir/$name.status"
}

pages() {
  local variant=$1 page
  port=$2
  local dir=$out/pages/$variant jar=(-b "$out/pages/$variant/cookies" -c "$out/pages/$variant/cookies")
  rm -rf "$dir" && mkdir -p "$dir"
  for page in "${pages[@]}"; do
    fetch "$dir" "$(echo "$page" | tr -c 'A-Za-z0-9\n' '_')" "$page"
  done
  fetch "$dir" login-form /wp-login.php "${jar[@]}"
  fetch "$dir" login /wp-login.php "${jar[@]}" -d log=admin -d pwd=admin -d testcookie=1 \
    --data-urlencode 'wp-submit=Log In' --data-urlencode 'redirect_to=http://localhost/wp-admin/'
  for page in "${admin[@]}"; do
    fetch "$dir" "admin$(echo "$page" | tr -c 'A-Za-z0-9\n' '_')" "$page" "${jar[@]}"
  done
  docker logs $P-$variant > "$dir/server.log" 2>&1
  echo "$variant: $(ls "$dir"/*.html | wc -l) pages, $(grep -c . "$dir/server.log" || true) server log lines"
}

# the pages of B against A's: status, Location and masked body
compare() {
  local a=$out/pages/$1 b=$out/pages/$2 failed=0 f name
  for f in "$a"/*.html; do
    name=$(basename "$f" .html)
    if ! cmp -s "$a/$name.status" "$b/$name.status"; then
      echo "DIFF $name: status $(tr '\n' ' ' < "$a/$name.status")against $(tr '\n' ' ' < "$b/$name.status")"; failed=1
    elif cmp -s "$f" "$b/$name.html"; then
      echo "same $name ($(head -1 "$a/$name.status"), $(wc -c < "$f") bytes)"
    else
      echo "DIFF $name: $(diff "$f" "$b/$name.html" | grep -c '^[<>]') lines differ"; failed=1
    fi
  done
  return $failed
}

# one measured run: the driver first (it polls the server's address), then the server on CPUs 14-21, whose container
# ID and PID the driver reads from runs/NAME.id
measure_run() { # variant visits pause name
  local variant=$1 visits=$2 pause=$3 name=$4 runs=$out/runs driver
  mkdir -p $runs && rm -f $runs/$name.id $runs/$name.json
  # the benchmark cores are shared: wait until no other container is pinned to them and no other memory-lock
  # reservation claims them, and note a container that came during the run. Pinning holds only inside the VM, whose
  # CPUs share the host's: wait for a quiet host too, and log its load during the run.
  # target/wpn/hold: another job of this session's is running (its own analysis), wait for it too
  while [ -e $out/hold ] || [ -n "$(others_on_bench)$(others_reserving_bench)" ] || ! host_quiet; do sleep 30; done
  rm -f $runs/$name.host
  reset_db
  MSYS_NO_PATHCONV=1 docker run --rm --name $P-driver --cpuset-cpus 0-5 --network $P-net --pid=host --cgroupns=host \
    -v /sys/fs/cgroup:/hostcg:ro -v "$root/tests/gate2:/t:ro" -v "$root/$runs:/runs" python:3.13-slim \
    python -u /t/visits.py http://$NET.10:8080 /runs/$name.id "$visits" "$pause" /runs/$name.json \
    > $runs/$name.log 2>&1 &
  driver=$!
  for _ in $(seq 100); do grep -q polling $runs/$name.log 2> /dev/null && break; sleep 0.1; done
  NOWAIT=1 start "$variant" 14-21 ${WPN_MEASURE_PORT:-8096}
  docker inspect -f '{{.Id}} {{.State.Pid}}' $P-$variant > $runs/$name.id
  while kill -0 $driver 2> /dev/null; do
    others_on_bench >> $runs/$name.others
    host_cpu >> $runs/$name.host
    sleep 10
  done
  wait $driver || true
  docker logs $P-$variant > $runs/$name.server.log 2>&1
  stop "$variant"
  local shared=
  [ ! -s $runs/$name.others ] || shared=" (shared with: $(sort -u $runs/$name.others | tr '\n' ' '))"
  echo "$name: $(tail -1 $runs/$name.log)$shared; host CPU median $(host_median $runs/$name.host)%," \
    "max $(sort -n $runs/$name.host | tail -1)%"
}

# the median of a run's host CPU samples
host_median() {
  sort -n "$1" | awk '{a[NR] = $1} END {print NR ? a[int((NR + 1) / 2)] : 100}'
}

# the host's CPU load now, in percent (all its CPUs; the measured server's 8 count for about a third)
host_cpu() {
  powershell -NoProfile -Command \
    "[math]::Round((Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average).Average)" \
    2> /dev/null | tr -d '\r'
}

# whether the host stayed under HOST_QUIET percent (default 30) over three samples 10 s apart
host_quiet() {
  local load
  for _ in 1 2 3; do
    load=$(host_cpu)
    [ "${load:-100}" -lt "${HOST_QUIET:-30}" ] || return 1
    sleep 10
  done
}

# containers other than this script's pinned to any of CPUs 14-21
others_on_bench() {
  local c cpus part
  for c in $(docker ps --format '{{.Names}}' | grep -v "^$P-"); do
    cpus=$(docker inspect -f '{{.HostConfig.CpusetCpus}}' "$c" 2> /dev/null || true)
    for part in ${cpus//,/ }; do
      if [ "${part#*-}" -ge 14 ] && [ "${part%-*}" -le 21 ]; then echo "$c:$cpus"; break; fi
    done
  done
}

# memory-lock reservations of other agents whose note names CPUs 14-21 (a timed job of theirs between containers).
# CPU_LOCK_HELD=1: this session holds the CPU lock (/c/Users/krzgo/Projects/.cpu-14-21.lock), which keeps the
# others' timed jobs off 14-21, so their notes don't count (they would wait for this run, and it for them).
others_reserving_bench() {
  [ -z "${CPU_LOCK_HELD:-}" ] || return 0
  python 'C:\Users\krzgo\Projects\memory-lock\memlock.py' status 2> /dev/null | grep '14-21' \
    | grep -v "claude-wordpress-$P" || true
}

# Rounds of every variant, the order reversed every other round. A round in which a run's median host load passed
# HOST_LOADED percent (default 55: the server's own third and about as much again) is run again, up to twice; the
# loaded round's files move to runs/loaded/.
measure() { # rounds visits pause variants...
  local rounds=$1 visits=$2 pause=$3 r i order attempt loaded v
  shift 3
  local variants=("$@") n=$#
  for r in $(seq "$rounds"); do
    if [ $((r % 2)) = 1 ]; then order=$(seq 0 $((n - 1))); else order=$(seq $((n - 1)) -1 0); fi
    for attempt in 1 2 3; do
      for i in $order; do
        measure_run "${variants[$i]}" "$visits" "$pause" "${variants[$i]}-$r"
      done
      loaded=
      for v in "${variants[@]}"; do
        [ "$(host_median $out/runs/$v-$r.host)" -le "${HOST_LOADED:-55}" ] || loaded+=" $v"
      done
      [ -n "$loaded" ] && [ $attempt -lt 3 ] || break
      echo "round $r again: the host was loaded during$loaded"
      mkdir -p $out/runs/loaded
      for v in "${variants[@]}"; do
        for f in $out/runs/$v-$r.*; do mv "$f" "$out/runs/loaded/$(basename "$f").$attempt"; done
      done
    done
  done
}

# One k6 run of tests/gate2/wp-load.js (CPUs 0-5) against the server at $NET.10, VUS users for DURATION, the
# server's container sampled meanwhile (woo_sample.py, CPUs 0-5): $out/load/NAME.k6.json, .k6.log, .sample.json.
load_run() { # variant vus duration name
  local variant=$1 vus=$2 duration=$3 name=$4 dir=$out/load sampler
  mkdir -p $dir && rm -f $dir/$name.* $dir/stop
  docker inspect -f '{{.Id}} {{.State.Pid}}' $P-$variant > $dir/$name.id
  MSYS_NO_PATHCONV=1 docker run --rm --name $P-sampler --cpuset-cpus 0-5 --pid=host --cgroupns=host \
    -v /sys/fs/cgroup:/hostcg:ro -v "$root/tests/gate2:/t:ro" -v "$root/$dir:/out" python:3.13-slim \
    python -u /t/woo_sample.py /out/$name.id /out/stop /out/$name.sample.json > $dir/$name.sampler.log 2>&1 &
  sampler=$!
  MSYS_NO_PATHCONV=1 docker run --rm --name $P-k6 --cpuset-cpus 0-5 --network $P-net \
    -v "$root/tests/gate2:/scripts:ro" -v "$root/$dir:/out" -e BASE=http://$NET.10:8080 -e VUS=$vus \
    -e DURATION=$duration -e SUMMARY=/out/$name.k6.json grafana/k6:1.3.0 run -q --no-color \
    /scripts/wp-load.js > $dir/$name.k6.log 2>&1 || true
  touch $dir/stop
  wait $sampler || true
  python -I tests/gate2/wp_load_report.py line "$dir/$name"
}

# load ROUNDS VARIANT...: concurrent visitors through nginx. Per round and variant (the order reversed every other
# round) one server's life on CPUs 14-21, holding the CPU lock (/c/Users/krzgo/Projects/.cpu-14-21.lock): started on
# the seeded database with WPN_THREADS workers (set it for the run, e.g. 8), warmed up by WARM of k6 at the highest
# level (default 300s, php-fpm's 60s), then DURATION (default 60s) of k6 at each of LEVELS (default "1 8 16") users.
# Runs: $out/load/VARIANT-rROUND-LEVEL.*; the table by `wp_load_report.py table $out/load OUT`.
load() { # rounds variants...
  local rounds=$1 r i order variant level warm levels=(${LEVELS:-1 8 16})
  shift
  local variants=("$@") n=$#
  local lock=/c/Users/krzgo/Projects/.cpu-14-21.lock
  for r in $(seq "$rounds"); do
    if [ $((r % 2)) = 1 ]; then order=$(seq 0 $((n - 1))); else order=$(seq $((n - 1)) -1 0); fi
    for i in $order; do
      variant=${variants[$i]}
      [ "$variant" = fpm ] && warm=${FPM_WARM:-60s} || warm=${WARM:-300s}
      until mkdir $lock 2> /dev/null; do
        echo "CPUs 14-21 are taken: $(cat $lock/* 2> /dev/null | head -1); waiting"; sleep 60
      done
      echo "claude-wordpress-$P ($(basename "$root")): native.sh load $variant round $r, since $(date '+%F %T')" \
        > $lock/note
      trap 'rm -rf /c/Users/krzgo/Projects/.cpu-14-21.lock' EXIT
      while ! host_quiet; do sleep 30; done
      stop "$variant"
      reset_db
      start "$variant" 14-21 ${WPN_MEASURE_PORT:-8096}
      load_run "$variant" "${levels[-1]}" "$warm" "$variant-r$r-warm"
      for level in "${levels[@]}"; do
        load_run "$variant" "$level" "${DURATION:-60s}" "$variant-r$r-$level"
      done
      docker logs $P-$variant > $out/load/$variant-r$r.server.log 2>&1
      stop "$variant"
      rm -rf $lock
      trap - EXIT
    done
  done
}

# the deployment variants' images: php-fpm behind nginx, Truffle PHP's FastCGI server behind the same nginx
images() {
  docker build -q -t $FPM_IMAGE -f tests/gate2/woo-fpm/Dockerfile tests/gate2/woo-fpm
  docker build -q -t $FCGI_IMAGE -f tests/gate2/fcgi/Dockerfile tests/gate2
}

clean() {
  for c in $(docker ps -a --format '{{.Names}}' | grep "^$P-"); do docker rm -f "$c" > /dev/null; done
  docker volume rm $P-site > /dev/null 2>&1 || true
  docker network rm $P-net > /dev/null 2>&1 || true
}

case ${1:-} in
  db) start_db ;;
  volume) volume ;;
  reset-db) reset_db ;;
  start) shift; start "$@" ;;
  stop) stop "$2" ;;
  agent) agent ;;
  check) check ;;
  pages) pages "$2" "$3" ;;
  compare) compare "$2" "$3" ;;
  measure) shift; measure "$@" ;;
  load) shift; load "$@" ;;
  images) images ;;
  clean) clean ;;
  *) sed -n 2,20p "$0"; exit 2 ;;
esac
