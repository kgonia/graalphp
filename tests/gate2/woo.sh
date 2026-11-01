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
#   Variants: php (php -S, OPcache and its tracing JIT, as native.sh's), fpm (php-fpm behind nginx, OPcache, the
#   tracing JIT, validate_timestamps=0, pm=static), jvm (the tree, our parser), jvm-phases (with the phase counters),
#   native (target/native/wp-http), native-phases (target/native/wp-http-phases).
# - walk VARIANT PORT: tests/gate2/woo_walk.py, the store's pages and actions; then the rows it wrote (dbrows).
#   compare A B: the walks' responses and rows, masked.
# Usage: tests/gate2/woo.sh db | volume | setup | reset-db | start VARIANT [CPUSET] [PORT] | stop VARIANT
#        | walk VARIANT PORT | compare A B | dbrows NAME | fpm-image | clean
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

wp_cli() { # wp-cli on PHP 8.5 against the store's database, the site writable
  MSYS_NO_PATHCONV=1 docker run --rm --cpuset-cpus ${WPN_AUX_CPUS:-6-13} --network $P-net \
    --add-host host.docker.internal:$NET.2 -v $P-site:/srv -v "${WP_CLI:?a directory with wp-cli.phar}:/cli:ro" \
    -v "$root/tests/gate2:/t:ro" -v "$root/$out:/out:ro" -w /srv/$WP bootshare-php:8.5 \
    sh -c 'php -d auto_prepend_file= -d memory_limit=1G -d error_reporting=24575 /cli/wp-cli.phar --allow-root "$@"' \
    wp "$@"
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
  wp_cli eval-file /t/woo-setup.php /out/sample_products.csv
  wp_cli wc hpos enable
  # what the install scheduled, run now, so that no run starts with a backlog
  wp_cli action-scheduler run --batch-size=100 --batches=20
  wp_cli cron event run --due-now
  docker exec $db mariadb-dump -uroot -proot -P3307 --skip-dump-date --databases wordpress > $out/seed.sql
  echo "seed: $(docker exec $db mariadb -uroot -proot -P3307 -N wordpress -e \
    "select count(*) from wp_posts where post_type in ('product', 'product_variation')") products"
}

reset_db() {
  docker exec -i $db mariadb -uroot -proot -P3307 < $out/seed.sql
}

# php-fpm 8.5.12 (Debian trixie, as the CLI image) with mysqli, OPcache and its tracing JIT, behind nginx
fpm_image() {
  docker build -q -t $FPM_IMAGE -f tests/gate2/woo-fpm.Dockerfile tests/gate2
}

start() {
  local variant=$1 cpus=${2:-6-13} port=${3:-8090} image=${TRUFFLE_IMAGE:-gate1-truffle:8.5-graal25.4} cmd env=()
  case $variant in
    php) image=bootshare-php:8.5; env=(-e PHP_CLI_SERVER_WORKERS=$THREADS)
         cmd="cd $WP && exec php -d auto_prepend_file= -S 0.0.0.0:8080" ;;
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
  # DEV=1: the classes from the host's target/classes, not the volume's copy (while fixing)
  local dev=()
  [ -z "${DEV:-}" ] || dev=(-v "$root/target/classes:/srv/target/classes:ro")
  docker rm -f $P-$variant > /dev/null 2>&1 || true
  MSYS_NO_PATHCONV=1 docker run -d --name $P-$variant --cpuset-cpus "$cpus" -p "$port:8080" --network $P-net \
    --ip $NET.$ip --add-host host.docker.internal:$NET.2 "${env[@]}" "${dev[@]}" \
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
      > "$dir/$table.tsv" || true
  done
  docker exec $db mariadb -uroot -proot -P3307 wordpress -e "select post_id, meta_key, meta_value from wp_postmeta
    where meta_key in ('_stock', 'total_sales', '_stock_status') order by post_id, meta_key" > "$dir/stock.tsv"
  docker exec $db mariadb -uroot -proot -P3307 wordpress -e "select option_name, option_value from wp_options
    where option_name like '%transient%' or option_name like 'woocommerce%' order by option_name" \
    | sed -E -e 's/[0-9]{10}/TIME/g' > "$dir/options.tsv"
  echo "$name: $(wc -l < "$dir/wc_orders.tsv") order lines, $(wc -l < "$dir/woocommerce_order_items.tsv") item lines"
}

walk() {
  local variant=$1 port=$2
  python -I tests/gate2/woo_walk.py "http://127.0.0.1:$port" "$out/walk/$variant"
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
  for f in $out/rows/$1/*.tsv; do
    name=$(basename "$f")
    if cmp -s "$f" "$out/rows/$2/$name"; then echo "same rows $name ($(wc -l < "$f") lines)"
    else echo "DIFF rows $name: $(diff "$f" "$out/rows/$2/$name" | grep -c '^[<>]') lines differ"; failed=1; fi
  done
  return $failed
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
  clean) clean ;;
  *) sed -n 2,22p "$0"; exit 2 ;;
esac
