#!/usr/bin/env bash
# Gate 2, the home page over HTTP: WordPress served by Truffle PHP's HTTP server (launcher.HttpMain, a context per
# request) and by regular PHP with mysqli (php -S in the wordpress:cli image), the same pages fetched from both and
# compared. Our SERVER_NAME is php -S's (0.0.0.0): WordPress compares it with asset hosts (dns-prefetch). Both
# display errors as php -S does (display_errors=1, html_errors=0); ?fatal=... ends a request with a fatal error
# (tests/gate2/fatal-plugin.php), which WordPress's fatal error handler turns into its error page.
# Run after fetch.sh, export.sh, db.sh and install.sh; build first.
# Usage: tests/gate2/http.sh [start|stop|compare]   (default: start both, compare, leave them running)
set -euo pipefail
cd "$(dirname "$0")/../.."
root=$(pwd -W 2>/dev/null || pwd)
out=target/gate2/http
pages=(/ "/?p=1" "/?page_id=2" "/?cat=1" "/?feed=rss2" /wp-login.php "/?rest_route=/" "/?s=hello" /nonexistent-page/
       "/?fatal=exception" "/?fatal=error")

stop() {
  docker rm -f gate2-truffle-http gate2-php-http > /dev/null 2>&1 || true
}

start() {
  stop
  cp tests/gate2/wp-config.php target/gate2/wordpress/wp-config.php
  mkdir -p target/gate2/wordpress/wp-content/mu-plugins
  cp tests/gate2/fatal-plugin.php target/gate2/wordpress/wp-content/mu-plugins/fatal-plugin.php
  MSYS_NO_PATHCONV=1 bash bench/gate1/php.sh php tools/ast-export.php target/ast target/gate2/wordpress/wp-config.php \
    target/gate2/wordpress/wp-content/mu-plugins/fatal-plugin.php
  MSYS_NO_PATHCONV=1 docker run -d --name gate2-truffle-http -p 8080:8080 ${CPUSET:+--cpuset-cpus "$CPUSET"} \
    -v "$root:/w" -w /w gate1-truffle:8.5 sh -c '$GRAALVM_HOME/bin/java --enable-native-access=ALL-UNNAMED \
    --sun-misc-unsafe-memory-access=allow $JAVA_OPTS_BASE -Dpolyglot.engine.WarnInterpreterOnly=false \
    -cp "target/classes:target/lib/*" pl.codeloom.php.launcher.HttpMain --port=8080 --threads=4 \
    --docroot=target/gate2/wordpress --ast-root=target/ast --server-name=0.0.0.0 --host=0.0.0.0 --display-errors -dhtml_errors=0' > /dev/null
  MSYS_NO_PATHCONV=1 docker run -d --name gate2-php-http -p 8081:8081 --add-host=host.docker.internal:host-gateway \
    -v "$root:/w" -w /w/target/gate2/wordpress wordpress:cli-php8.4 php -S 0.0.0.0:8081 > /dev/null
  for port in 8080 8081; do
    for _ in $(seq 60); do
      curl -s -o /dev/null "http://localhost:$port/wp-includes/css/dashicons.css" && break
      sleep 1
    done
  done
}

# what differs between two correct responses: nonces, timestamps, the server's own name
mask() {
  sed -E -e 's/(nonce|_wpnonce|"nonce")([^0-9a-f]{1,12})[0-9a-f]{10}/\1\2NONCE/g' \
    -e 's/[A-Z][a-z]{2}, [0-9]{2} [A-Z][a-z]{2} [0-9]{4} [0-9:]{8} [+-][0-9]{4}/DATE/g' \
    -e 's/ver=[0-9a-f.-]+/ver=V/g'
}

compare() {
  mkdir -p "$out"
  local failed=0
  for page in "${pages[@]}"; do
    local name
    name=$(echo "$page" | tr -c 'A-Za-z0-9\n' '_')
    for side in truffle:8080 php:8081; do
      curl -s -H 'Host: localhost' -D "$out/$name.${side%%:*}.headers" "http://localhost:${side##*:}$page" \
        | mask > "$out/$name.${side%%:*}.html"
    done
    local status_t status_p
    status_t=$(head -1 "$out/$name.truffle.headers" | tr -d '\r')
    status_p=$(head -1 "$out/$name.php.headers" | tr -d '\r')
    if [ "${status_t#* }" != "${status_p#* }" ]; then
      echo "DIFF $page: status '$status_t' vs '$status_p'"; failed=1
    elif cmp -s "$out/$name.truffle.html" "$out/$name.php.html"; then
      echo "same $page (${status_t#* }, $(wc -c < "$out/$name.php.html") bytes)"
    else
      echo "DIFF $page: $(diff "$out/$name.php.html" "$out/$name.truffle.html" | grep -c '^[<>]') lines differ"
      failed=1
    fi
  done
  return $failed
}

case "${1:-all}" in
  start) start ;;
  stop) stop ;;
  compare) compare ;;
  all) start; compare ;;
esac
