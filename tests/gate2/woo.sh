#!/usr/bin/env bash
# WooCommerce on Truffle PHP against PHP 8.5 (wiki/woocommerce.md): the store, the compatibility walk, the load runs.
# - The site is Gate 2's (tests/gate2/native.sh volume) with WooCommerce in wp-content/plugins; its database is
#   MariaDB 11.4 of its own (native.sh db), on a network of its own. Prefix, network and port as native.sh's:
#   WPN_NAME (default wc), WPN_NET (default 172.31.77), WPN_DB_PORT (default 3341).
# - setup: on PHP 8.5 with wp-cli (WP_CLI: a directory with wp-cli.phar), WooCommerce activated, the wizard skipped,
#   the sample products imported, cash on delivery and bank transfer, pretty permalinks, HPOS; the database dumped
#   as target/$WPN_NAME/seed.sql, which every later server starts from (reset-db).
# - Servers: container $P-VARIANT on CPUSET, port 8080 inside (so WordPress's loopback requests, to localhost:80,
#   find no server: neither WP-Cron nor the Action Scheduler runs during a run, on every engine alike), PORT on the
#   host; wp-content/uploads is a tmpfs of the container's own (WooCommerce's logs). THREADS: the JVM's and native
#   server's threads, php -S's workers, php-fpm's pm.max_children (default 4).
#   Variants: php (php -S, OPcache and its tracing JIT, as native.sh's, log_errors on as every server's), fpm (php-fpm behind nginx, OPcache, the
#   tracing JIT, validate_timestamps=0, pm=static), jvm (the tree, our parser), jvm-phases (with the phase counters),
#   native (target/native/wp-http), native-phases (target/native/wp-http-phases).
# - walk VARIANT PORT: tests/gate2/woo_walk.py, the store's pages and actions; then the rows it wrote (dbrows).
#   compare A B: the walks' responses and rows, masked.
# - load: k6 (tests/gate2/woo-load.js, on CPUs 0-5) against one server on CPUs 14-21, warm, at concurrency levels;
#   per run the server's CPU time and memory (woo_sample.py) and the database against the orders k6 placed
#   (woo_check.py). Results in target/$WPN_NAME/load.
# Usage: tests/gate2/woo.sh db | volume | setup | reset-db | start VARIANT [CPUSET] [PORT] | stop VARIANT
#        | walk VARIANT PORT | compare A B | dbrows NAME | fpm-image | load VARIANT THREADS TAG LEVELS...
#        | k6 VARIANT IP VUS DURATION BUY NAME | bench ROUNDS LEVELS... | clean
set -euo pipefail
cd "$(dirname "$0")/../.."
root=$(pwd -W 2>/dev/null || pwd)
export WPN_NAME=${WPN_NAME:-wc} WPN_NET=${WPN_NET:-172.31.77} WPN_DB_PORT=${WPN_DB_PORT:-3341}
P=$WPN_NAME
NET=$WPN_NET
out=target/$P
db=$P-mariadb
THREADS=${THREADS:-4}
FPM_IMAGE=wc-php-fpm:8.5
SERVER_OPTS="-Xmx1g -XX:TrimNativeHeapInterval=5000 -XX:G1PeriodicGCInterval=10000 -XX:MinHeapFreeRatio=10"
SERVER_OPTS+=" -XX:MaxHeapFreeRatio=30 -XX:ReservedCodeCacheSize=512m"
JAVA="exec \$GRAALVM_HOME/bin/java --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow"
JAVA+=" \$JAVA_OPTS_BASE $SERVER_OPTS -Djava.net.preferIPv6Addresses=system -Dpolyglot.engine.WarnInterpreterOnly=false"
JAVA+=" -cp 'target/classes:target/lib/*' -Dpolyglot.php.Parser=java"
LAUNCHER=pl.codeloom.php.launcher.HttpMain
HTTP_ARGS="--port=8080 --docroot=target/gate2/wordpress --server-name=0.0.0.0 --host=0.0.0.0 --ast-root=/none"
HTTP_ARGS+=" -ddisplay_errors=1 -dhtml_errors=0"
WP=target/gate2/wordpress

# wp-cli on PHP 8.5 against the store's database, the site writable. The image's php.ini prepends bootshare's
# /srv/prepend.php, which wp-cli's own PHP subprocesses (rewrite flush) would miss: an empty one stands in. wp-cli's
# deprecations on PHP 8.5 and the Action Scheduler's progress are left out of the output.
wp_cli() {
  : > $out/empty.php
  MSYS_NO_PATHCONV=1 docker run --rm --cpuset-cpus ${WPN_AUX_CPUS:-6-13} --network $P-net \
    --add-host host.docker.internal:$NET.2 -v $P-site:/srv -v "${WP_CLI:?a directory with wp-cli.phar}:/cli:ro" \
    -v "$root/$out/empty.php:/srv/prepend.php:ro" -v "$root/tests/gate2:/t:ro" -v "$root/$out:/out:ro" \
    -w /srv/$WP bootshare-php:8.5 sh -c 'php -d memory_limit=1G /cli/wp-cli.phar --allow-root "$@"' wp "$@" 2>&1 \
    | grep -v -e '^Deprecated: ' -e '^Started processing action' -e '^Completed processing action' || true
}

setup() {
  # the sample products without their image URLs
  python -I -c "
import csv, sys
with open(sys.argv[1], encoding='utf-8-sig', newline='') as f: rows = list(csv.reader(f))
column = rows[0].index('Images')
for row in rows[1:]: row[column] = ''
with open(sys.argv[2], 'w', encoding='utf-8', newline='') as f: csv.writer(f).writerows(rows)" \
    $WP/wp-content/plugins/woocommerce/sample-data/sample_products.csv $out/sample_products.csv
  wp_cli plugin activate woocommerce
  wp_cli rewrite structure '/%postname%/'
  wp_cli rewrite flush
  # as the shop manager: the importer creates categories and tags only for a user who may manage them
  wp_cli --user=admin eval-file /t/woo-setup.php /out/sample_products.csv
  wp_cli wc hpos enable
  # what the install scheduled, run now, so that no run starts with a backlog
  wp_cli action-scheduler run --batch-size=100 --batches=20
  wp_cli cron event run --due-now
  docker exec $db mariadb-dump -uroot -proot -P3307 --skip-dump-date --databases wordpress > $out/seed.sql
  echo "seed: $(docker exec $db mariadb -uroot -proot -P3307 -N wordpress -e \
    "select count(*) from wp_posts where post_type in ('product', 'product_variation')") products"
}

# SEED: the dump to start from, seed (default) or seed-load
reset_db() {
  docker exec -i $db mariadb -uroot -proot -P3307 < $out/${SEED:-seed}.sql
}

# The load runs' seed: the store's, its e-mails off. Without ext/dom Truffle PHP sends WooCommerce's e-mails as
# plain text, where PHP 8.5 renders HTML and inlines its CSS with DOMDocument (Emogrifier): the same work on every
# engine only without them. (sendmail is absent on every engine: mail() fails on each alike.)
load_seed() {
  SEED=seed reset_db
  wp_cli eval 'foreach (WC()->mailer()->get_emails() as $email) {
    update_option($email->get_option_key(), array_merge((array) get_option($email->get_option_key(), []),
      ["enabled" => "no"]));
  }'
  docker exec $db mariadb-dump -uroot -proot -P3307 --skip-dump-date --databases wordpress > $out/seed-load.sql
  local off="select count(*) from wp_options where option_name like 'woocommerce%_settings'"
  off+=" and option_value like '%\"enabled\";s:2:\"no\"%'"
  echo "seed-load: $(docker exec $db mariadb -uroot -proot -P3307 -N wordpress -e "$off") e-mails off"
}

# php-fpm 8.5.12 (Debian trixie, as the CLI image) with mysqli, OPcache and its tracing JIT, behind nginx
fpm_image() {
  docker build -q -t $FPM_IMAGE -f tests/gate2/woo-fpm/Dockerfile tests/gate2/woo-fpm
}

start() {
  local variant=$1 cpus=${2:-6-13} port=${3:-8090} image=${TRUFFLE_IMAGE:-gate1-truffle:8.5-graal25.4} cmd env=()
  case $variant in
    php) image=bootshare-php:8.5; env=(-e PHP_CLI_SERVER_WORKERS=$THREADS)
         cmd="cd $WP && exec php -d auto_prepend_file= -d log_errors=1 -S 0.0.0.0:8080" ;;
    fpm) image=$FPM_IMAGE; env=(-e FPM_CHILDREN=$THREADS)
         cmd="exec /usr/local/bin/woo-fpm-start" ;;
    jvm) cmd="$JAVA ${JAVA_OPTS:-} $LAUNCHER $HTTP_ARGS --threads=$THREADS" ;;
    jvm-phases) cmd="$JAVA -Dphp.phases=true -Dphp.nativeclock=true ${JAVA_OPTS:-} $LAUNCHER $HTTP_ARGS --threads=$THREADS" ;;
    native) cmd="exec target/native/wp-http \$JAVA_OPTS_BASE -Xmx1g -Dpolyglot.php.Parser=java ${JAVA_OPTS:-}"
            cmd+=" -Djava.net.preferIPv6Addresses=system $HTTP_ARGS --threads=$THREADS" ;;
    native-phases) cmd="exec target/native/wp-http-phases \$JAVA_OPTS_BASE -Xmx1g -Dpolyglot.php.Parser=java"
            cmd+=" ${JAVA_OPTS:-} -Djava.net.preferIPv6Addresses=system $HTTP_ARGS --threads=$THREADS" ;;
    *) echo "unknown variant $variant"; exit 2 ;;
  esac
  # an address per variant, so that two servers can run side by side (the walks)
  local ip
  case $variant in php) ip=11 ;; fpm) ip=12 ;; jvm) ip=13 ;; jvm-phases) ip=14 ;; native) ip=15 ;; *) ip=16 ;; esac
  # DEV=1: the classes from the host's target/classes, not the volume's copy (while fixing); WC_DOCKER_ARGS: more
  # docker run arguments (a debugging mount); DEV_CLASSES: another directory of classes (a snapshot for the load runs)
  local dev=()
  [ -z "${DEV:-}" ] || dev=(-v "$root/${DEV_CLASSES:-target/classes}:/srv/target/classes:ro")
  docker rm -f $P-$variant > /dev/null 2>&1 || true
  MSYS_NO_PATHCONV=1 docker run -d --name $P-$variant --cpuset-cpus "$cpus" -p "$port:8080" --network $P-net \
    --ip $NET.$ip --add-host host.docker.internal:$NET.2 "${env[@]}" "${dev[@]}" ${WC_DOCKER_ARGS:-} \
    -v $P-site:/srv:ro --tmpfs /srv/$WP/wp-content/uploads:mode=1777 -w /srv \
    $image sh -c "$cmd" > /dev/null
  [ -z "${NOWAIT:-}" ] || return 0
  for _ in $(seq 300); do
    curl -s -o /dev/null "http://127.0.0.1:$port/wp-includes/css/dashicons.css" && return 0
    sleep 0.2
  done
  echo "$P-$variant did not start"; docker logs $P-$variant 2>&1 | tail -20; return 1
}

stop() {
  docker stop -t 30 $P-$1 > /dev/null 2>&1 || true
  docker rm -f $P-$1 > /dev/null 2>&1 || true
}

# the rows a walk or a run wrote, masked (dates, keys, hashes), one file per table: target/$P/rows/NAME/
dbrows() {
  local name=$1 dir=$out/rows/$1 table
  rm -rf "$dir" && mkdir -p "$dir"
  for table in wc_orders wc_orders_meta wc_order_addresses wc_order_operational_data woocommerce_order_items \
               woocommerce_order_itemmeta wc_order_stats wc_order_product_lookup wc_customer_lookup \
               wc_product_meta_lookup comments; do
    docker exec $db mariadb -uroot -proot -P3307 wordpress -e "select * from wp_$table" 2> /dev/null \
      | sed -E -e 's/[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}/DATETIME/g' -e 's/wc_order_[A-Za-z0-9]{13}/wc_order_KEY/g' \
        -e 's/place-order-debug-[0-9a-f]{8}/place-order-debug-ID/g' \
      > "$dir/$table.tsv" || true
  done
  docker exec $db mariadb -uroot -proot -P3307 wordpress -e "select post_id, meta_key, meta_value from wp_postmeta
    where meta_key in ('_stock', 'total_sales', '_stock_status') order by post_id, meta_key" > "$dir/stock.tsv"
  docker exec $db mariadb -uroot -proot -P3307 wordpress -e "select option_name, option_value from wp_options
    where option_name like '%transient%' or option_name like 'woocommerce%' order by option_name" \
    | sed -E -e 's/(wc_report_[a-z_]+_)[0-9a-f]{32}/\1HASH/' -e 's/active_for=[0-9]+/active_for=N/' \
        -e 's/[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}/DATETIME/g' \
        -e 's/^(woocommerce_share_key\t).*/\1KEY/' -e 's/[0-9]{10}(\.[0-9]+)?/TIME/g' > "$dir/options.tsv"
  echo "$name: $(wc -l < "$dir/wc_orders.tsv") order lines, $(wc -l < "$dir/woocommerce_order_items.tsv") item lines"
}

walk() {
  local variant=$1 port=$2
  python -I tests/gate2/woo_walk.py "http://127.0.0.1:$port" "$out/walk/$variant"
  # WP-Cron as a visitor's spawn would run it, twice (the second runs what the first scheduled: the Action
  # Scheduler's analytics imports, wc_order_stats and wc_customer_lookup). The pages' own spawns left the cron
  # lock (doing_cron) set, which makes wp-cron.php return at once: cleared before each run.
  local i
  for i in 1 2; do
    sleep 10
    docker exec $db mariadb -uroot -proot -P3307 wordpress -e \
      "delete from wp_options where option_name in ('_transient_doing_cron', '_transient_timeout_doing_cron')"
    curl -s -H 'Host: localhost' -o "$out/walk/$variant/wp-cron-run$i.body" -w '%{http_code}\n' \
      "http://127.0.0.1:$port/wp-cron.php" > "$out/walk/$variant/wp-cron-run$i.status"
  done
  docker logs $P-$variant > "$out/walk/$variant/server.log" 2>&1
  dbrows "$variant"
  echo "$variant: $(grep -c . "$out/walk/$variant/server.log" || true) server log lines"
}

compare() {
  local a=$out/walk/$1 b=$out/walk/$2 f name failed=0
  for f in "$a"/*.body; do
    name=$(basename "$f" .body)
    if ! cmp -s "$a/$name.status" "$b/$name.status"; then
      echo "DIFF $name: status $(tr '\n' ' ' < "$a/$name.status")against $(tr '\n' ' ' < "$b/$name.status")"; failed=1
    elif cmp -s "$f" "$b/$name.body"; then
      echo "same $name ($(head -1 "$a/$name.status"), $(wc -c < "$f") bytes)"
    else
      echo "DIFF $name: $(diff "$f" "$b/$name.body" | grep -c '^[<>]') lines differ"; failed=1
    fi
  done
  # the PHP errors and the shell's lines (mail()'s sendmail) in the servers' logs, in order: php -S stamps them
  # with its worker's pid and the time
  local la lb
  la=$(grep -E '(PHP [A-Z][a-z]+( error)?: |^sh: )' "$a/server.log" | sed -E 's/^(\[[^]]*\] )+//' || true)
  lb=$(grep -E '(PHP [A-Z][a-z]+( error)?: |^sh: )' "$b/server.log" | sed -E 's/^(\[[^]]*\] )+//' || true)
  if [ "$la" = "$lb" ]; then echo "same server log errors ($(printf '%s' "$la" | grep -c . || true) lines)"
  else echo "DIFF server log errors:"; diff <(printf '%s\n' "$la") <(printf '%s\n' "$lb") | head -20 || true; failed=1; fi
  for f in $out/rows/$1/*.tsv; do
    name=$(basename "$f")
    if cmp -s "$f" "$out/rows/$2/$name"; then echo "same rows $name ($(wc -l < "$f") lines)"
    else echo "DIFF rows $name: $(diff "$f" "$out/rows/$2/$name" | grep -c '^[<>]') lines differ"; failed=1; fi
  done
  return $failed
}

# The CPU lock of the benchmark cores (14-21), shared by every agent on the box: a directory made atomically, a
# note inside; another's: wait, polling once a minute
CPU_LOCK=/c/Users/krzgo/Projects/.cpu-14-21.lock
cpu_lock() {
  until mkdir $CPU_LOCK 2> /dev/null; do
    echo "CPUs 14-21 are taken: $(cat $CPU_LOCK/* 2> /dev/null | head -1); waiting"; sleep 60
  done
  echo "claude-wordpress-$P (wordpress-woo): woo.sh load $*, since $(date '+%F %T')" > $CPU_LOCK/note
}
cpu_unlock() {
  rm -rf $CPU_LOCK
}

# One k6 run against the server at $NET.IP: VUS virtual users for DURATION, BUY of them buying; the server's
# container sampled meanwhile (woo_sample.py: CPU seconds, RssAnon, memory.current, memory.peak), the database
# checked after (woo_check.py). Files NAME.k6.json (summary), NAME.k6.log (orders, errors), NAME.sample.json,
# NAME.check.json in $out/load.
k6_run() { # variant ip vus duration buy name
  local variant=$1 ip=$2 vus=$3 duration=$4 buy=$5 name=$6 dir=$out/load sampler csv=()
  mkdir -p $dir && rm -f $dir/$name.* $dir/stop
  # the warm-up's every request, for its curve
  case $name in *-warm) csv=(--out csv=/out/$name.csv.gz) ;; esac
  docker inspect -f '{{.Id}} {{.State.Pid}}' $P-$variant > $dir/$name.id
  docker inspect -f '{{.Id}} {{.State.Pid}}' $db > $dir/$name.db.id
  # InnoDB's row lock counters before and after the run
  docker exec $db mariadb -uroot -proot -P3307 -N -e "show global status where Variable_name in
    ('Innodb_row_lock_waits', 'Innodb_row_lock_time', 'Innodb_deadlocks', 'Questions', 'Com_commit')" \
    > $dir/$name.dbstatus.before
  # a *-phases server's request lines before the run
  local first=$(docker logs $P-$variant 2>&1 | grep -c '^phases {' || true)
  MSYS_NO_PATHCONV=1 docker run --rm --name $P-sampler --cpuset-cpus 0-5 --pid=host --cgroupns=host \
    -v /sys/fs/cgroup:/hostcg:ro -v "$root/tests/gate2:/t:ro" -v "$root/$dir:/out" python:3.13-slim \
    python -u /t/woo_sample.py /out/$name.id /out/stop /out/$name.sample.json /out/$name.db.id \
    > $dir/$name.sampler.log 2>&1 &
  sampler=$!
  MSYS_NO_PATHCONV=1 docker run --rm --name $P-k6 --cpuset-cpus 0-5 --network $P-net \
    -v "$root/tests/gate2:/scripts:ro" -v "$root/$dir:/out" -e BASE=http://$NET.$ip:8080 -e VUS=$vus \
    -e DURATION=$duration -e BUY=$buy -e SUMMARY=/out/$name.k6.json grafana/k6:1.3.0 run -q --no-color \
    "${csv[@]}" /scripts/woo-load.js > $dir/$name.k6.log 2>&1 || true
  touch $dir/stop
  docker exec $db mariadb -uroot -proot -P3307 -N -e "show global status where Variable_name in
    ('Innodb_row_lock_waits', 'Innodb_row_lock_time', 'Innodb_deadlocks', 'Questions', 'Com_commit')" \
    > $dir/$name.dbstatus.after
  docker exec $db mariadb -uroot -proot -P3307 -e "show engine innodb status\G" > $dir/$name.innodb.txt 2>&1
  case $variant in *-phases)
    docker logs $P-$variant > $dir/$name.server.log 2>&1
    python -I tests/gate2/woo_report.py phases $dir/$name.server.log $first \
      "$(grep -c '^phases {' $dir/$name.server.log)" > $dir/$name.phases.json ;;
  esac
  wait $sampler || true
  python -I tests/gate2/woo_check.py $db $dir/$name.k6.log 100000 > $dir/$name.check.json
  python -I tests/gate2/woo_report.py line "$dir/$name"
  case $name in *-warm) python -I tests/gate2/woo_report.py warm "$dir/$name" ;; esac
}

# bench ROUNDS LEVELS...: the load runs of every configuration in CONFIGS (VARIANT:THREADS, default "jvm:8 jvm:16
# native:16 fpm:8 fpm:16"), ROUNDS times, the order reversed every other round; each configuration's own server life
# (load, tag VARIANTTHREADS-rROUND). WARM: the JVM's and native image's warm-up (default 300s), php-fpm's 60s.
bench() {
  local rounds=$1 r i order config variant threads
  shift
  local configs=(${CONFIGS:-jvm:8 jvm:16 native:16 fpm:8 fpm:16})
  local n=${#configs[@]}
  for r in $(seq "$rounds"); do
    if [ $((r % 2)) = 1 ]; then order=$(seq 0 $((n - 1))); else order=$(seq $((n - 1)) -1 0); fi
    for i in $order; do
      config=${configs[$i]}
      variant=${config%:*} threads=${config#*:}
      case $variant in
        fpm|php) WARM=${FPM_WARM:-60s} load $variant $threads $variant$threads-r$r "$@" ;;
        *) WARM=${JVM_WARM:-300s} load $variant $threads $variant$threads-r$r "$@" ;;
      esac
    done
  done
}

# load VARIANT THREADS TAG LEVELS...: one server's life on CPUs 14-21 (holding the CPU lock): started from the seed,
# warmed up with WARM (default 180s) of k6 at THREADS users half buying (its own run, TAG-warm: the warm-up curve),
# then for each mix in MIXES (default "0.2 1": the share of visitors who buy) and each concurrency level: the
# database reset from the seed, DURATION (default 60s) of k6 (TAG-MIX-LEVEL)
load() {
  local variant=$1 threads=$2 tag=$3 ip mix level t0
  shift 3
  case $variant in php) ip=11 ;; fpm) ip=12 ;; jvm) ip=13 ;; jvm-phases) ip=14 ;; native) ip=15 ;; *) ip=16 ;; esac
  stop $variant
  export SEED=seed-load
  reset_db
  cpu_lock "$variant $threads $tag $*"
  trap cpu_unlock EXIT
  t0=$(date +%s%N)
  THREADS=$threads start $variant 14-21 8096
  echo "$tag: $variant --threads $threads up in $((($(date +%s%N) - t0) / 1000000)) ms"
  k6_run $variant $ip $threads ${WARM:-180s} 0.5 $tag-warm
  for mix in ${MIXES:-0.2 1}; do
    for level in "$@"; do
      reset_db
      k6_run $variant $ip $level ${DURATION:-60s} $mix $tag-$mix-$level
    done
  done
  docker logs $P-$variant > $out/load/$tag.server.log 2>&1
  stop $variant
  cpu_unlock
  trap - EXIT
}

clean() {
  for c in $(docker ps -a --format '{{.Names}}' | grep "^$P-"); do docker rm -f "$c" > /dev/null; done
  docker volume rm $P-site > /dev/null 2>&1 || true
  docker network rm $P-net > /dev/null 2>&1 || true
}

case ${1:-} in
  db) bash tests/gate2/native.sh db ;;
  volume) bash tests/gate2/native.sh volume ;;
  setup) setup ;;
  reset-db) reset_db ;;
  start) shift; start "$@" ;;
  stop) stop "$2" ;;
  walk) walk "$2" "$3" ;;
  compare) compare "$2" "$3" ;;
  dbrows) dbrows "$2" ;;
  fpm-image) fpm_image ;;
  load-seed) load_seed ;;
  load) shift; load "$@" ;;
  bench) shift; bench "$@" ;;
  k6) shift; k6_run "$@" ;;
  clean) clean ;;
  *) sed -n 2,22p "$0"; exit 2 ;;
esac
