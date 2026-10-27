#!/usr/bin/env bash
# Gate 2's WordPress to look at in a browser: Truffle PHP on http://localhost:8080/ and PHP 8.5 (OPcache, tracing
# JIT) on http://localhost:8081/, the same site and database (log in to wp-admin as admin / admin). Unlike http.sh's
# servers, each copies the site (and Truffle PHP its ASTs) into its own file system and reaches MariaDB over the
# Docker bridge: through the Windows bind mount and the host's port forwarding, PHP takes ~1 s a page instead of
# tens of ms. Truffle PHP is slow on each page's first requests, until the JIT has compiled it.
# CPUSET pins the servers (e.g. CPUSET=6-13), each to the same cores. A server is ready when it serves a static file:
# for Truffle PHP that is a started JVM that hasn't run any PHP yet.
# Run after fetch.sh, export.sh, db.sh and install.sh, and bench/bootshare's image; build first.
# Usage: tests/gate2/browse.sh [start|stop] [both|truffle|php]
set -euo pipefail
cd "$(dirname "$0")/../.."
root=$(pwd -W 2>/dev/null || pwd)
action=${1:-start}; which=${2:-both}
servers=()
[ "$which" = php ] || servers+=(truffle)
[ "$which" = truffle ] || servers+=(php)
for server in "${servers[@]}"; do docker rm -f gate2-$server-http > /dev/null 2>&1 || true; done
[ "$action" = stop ] && exit 0
cp tests/gate2/wp-config.php target/gate2/wordpress/wp-config.php
MSYS_NO_PATHCONV=1 bash bench/gate1/php.sh php tools/ast-export.php target/ast target/gate2/wordpress/wp-config.php
db_host=$(docker inspect gate2-mariadb --format '{{.NetworkSettings.Networks.bridge.IPAddress}}'):3306
for server in "${servers[@]}"; do
  if [ $server = truffle ]; then
    MSYS_NO_PATHCONV=1 docker run -d --name gate2-truffle-http -p 8080:8080 ${CPUSET:+--cpuset-cpus "$CPUSET"} \
      -e GATE2_DB_HOST=$db_host -v "$root:/w:ro" gate1-truffle:8.5 sh -c 'mkdir -p /srv/target/gate2 \
      && cp -r /w/target/ast /srv/target/ && cp -r /w/target/gate2/wordpress /srv/target/gate2/ && cd /srv \
      && exec $GRAALVM_HOME/bin/java --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow \
      $JAVA_OPTS_BASE -Djava.net.preferIPv6Addresses=system -Dpolyglot.engine.WarnInterpreterOnly=false \
      -cp "/w/target/classes:/w/target/lib/*" pl.codeloom.php.launcher.HttpMain --port=8080 --threads=4 \
      --docroot=target/gate2/wordpress --ast-root=target/ast --server-name=0.0.0.0 --host=0.0.0.0' > /dev/null
    port=8080
  else
    MSYS_NO_PATHCONV=1 docker run -d --name gate2-php-http -p 8081:8081 ${CPUSET:+--cpuset-cpus "$CPUSET"} \
      -e GATE2_DB_HOST=$db_host -e PHP_CLI_SERVER_WORKERS=4 -v "$root:/w:ro" bootshare-php:8.5 sh -c \
      'cp -r /w/target/gate2/wordpress /srv/ && cd /srv/wordpress && exec php -d auto_prepend_file= -S 0.0.0.0:8081' \
      > /dev/null
    port=8081
  fi
  for _ in $(seq 300); do curl -s -o /dev/null "http://127.0.0.1:$port/wp-includes/css/dashicons.css" && break; sleep 1; done
done
echo "Truffle PHP: http://localhost:8080/   PHP 8.5: http://localhost:8081/   (wp-admin: admin / admin)"
