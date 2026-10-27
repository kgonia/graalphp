#!/usr/bin/env bash
# Gate 2, the home page over HTTP: WordPress served by Truffle PHP's HTTP server (launcher.HttpMain, a context per
# request) and by regular PHP with mysqli (php -S in the wordpress:cli image), the same pages fetched from both and
# compared. Our SERVER_NAME is php -S's (0.0.0.0): WordPress compares it with asset hosts (dns-prefetch). Both
# display errors as php -S does (display_errors=1, html_errors=0); ?fatal=... ends a request with a fatal error
# (tests/gate2/fatal-plugin.php), which WordPress's fatal error handler turns into its error page. PHP's server runs
# workers, so a script it serves can make requests to it (tests/gate2/sockets-probe.php does).
# Run after fetch.sh, export.sh, db.sh and install.sh; build first.
# Usage: tests/gate2/http.sh [start|stop|compare|install|login|sockets]   (default: start both, compare, leave them running)
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
  cp tests/gate2/sockets-probe.php target/gate2/wordpress/sockets-probe.php
  MSYS_NO_PATHCONV=1 bash bench/gate1/php.sh php tools/ast-export.php target/ast target/gate2/wordpress/wp-config.php \
    target/gate2/wordpress/wp-content/mu-plugins/fatal-plugin.php target/gate2/wordpress/sockets-probe.php
  MSYS_NO_PATHCONV=1 docker run -d --name gate2-truffle-http -p 8080:8080 ${CPUSET:+--cpuset-cpus "$CPUSET"} \
    -v "$root:/w" -w /w gate1-truffle:8.5 sh -c '$GRAALVM_HOME/bin/java --enable-native-access=ALL-UNNAMED \
    --sun-misc-unsafe-memory-access=allow $JAVA_OPTS_BASE -Djava.net.preferIPv6Addresses=system -Dpolyglot.engine.WarnInterpreterOnly=false \
    -cp "target/classes:target/lib/*" pl.codeloom.php.launcher.HttpMain --port=8080 --threads=4 \
    --docroot=target/gate2/wordpress --ast-root=target/ast --server-name=0.0.0.0 --host=0.0.0.0 -ddisplay_errors=1 -dhtml_errors=0' > /dev/null
  MSYS_NO_PATHCONV=1 docker run -d --name gate2-php-http -p 8081:8081 --add-host=host.docker.internal:host-gateway \
    -e PHP_CLI_SERVER_WORKERS=4 \
    -v "$root:/w" -w /w/target/gate2/wordpress wordpress:cli-php8.4 php -S 0.0.0.0:8081 > /dev/null
  for port in 8080 8081; do
    for _ in $(seq 60); do
      curl -s -o /dev/null "http://localhost:$port/wp-includes/css/dashicons.css" && break
      sleep 1
    done
  done
}

# what differs between two correct responses: nonces (10 hex digits, which a logged-in user's depend on the random
# session token), timestamps, the seconds in wp-admin's date fields, generated passwords; and the C library's texts
# for EAI_NONAME and ETIMEDOUT, which PHP's image (Alpine, musl) words otherwise than glibc
mask() {
  sed -E -e 's/(nonce|_wpnonce|"nonce")([^0-9a-f]{1,12})[0-9a-f]{10}/\1\2NONCE/g' -e 's/data-pw="[^"]*"/data-pw="PW"/' \
    -e 's/(\x22|\x27)[0-9a-f]{10}(\x22|\x27)/\1NONCE\2/g' \
    -e 's/(<div class="ss">|name="ss" value=")[0-9]{2}/\1SS/g' -e 's/"[0-9]{10}:/"TIME:/g' \
    -e 's/[0-9]{4}-[0-9]{2}-[0-9]{2}[T ][0-9]{2}:[0-9]{2}:[0-9]{2}/DATETIME/g' \
    -e 's/[A-Z][a-z]{2}, [0-9]{2} [A-Z][a-z]{2} [0-9]{4} [0-9:]{8} [+-][0-9]{4}/DATE/g' \
    -e 's/ver=[0-9a-f.-]+/ver=V/g' \
    -e 's/failed: Name does not resolve/failed: Name or service not known/g' -e 's/Operation timed out/Connection timed out/g'
}

# fetches a path from both servers and compares the status, the redirect and the masked body: prints "same" or
# "DIFF" and returns 1 on a difference. $1 names the output files, $2 is the path, the rest are curl's arguments, in
# which {side} stands for the engine (truffle or php): its cookie jar, or its installer database (X-Gate2-Db, which
# wp-config.php honours only for those two names).
check() {
  local name=$1 page=$2 side
  shift 2
  for side in truffle:8080 php:8081; do
    curl -s -H 'Host: localhost' -D "$out/$name.${side%%:*}.headers" "${@//\{side\}/${side%%:*}}" \
      "http://localhost:${side##*:}$page" | mask > "$out/$name.${side%%:*}.html"
  done
  local status_t status_p location_t location_p
  # the status code: the reason phrase is the server's (the JDK's says "Temporary Redirect" for 302)
  status_t=$(head -1 "$out/$name.truffle.headers" | cut -d' ' -f2)
  status_p=$(head -1 "$out/$name.php.headers" | cut -d' ' -f2)
  location_t=$(grep -i '^location:' "$out/$name.truffle.headers" | tr -d '\r' | mask)
  location_p=$(grep -i '^location:' "$out/$name.php.headers" | tr -d '\r' | mask)
  if [ "$status_t" != "$status_p" ] || [ "$location_t" != "$location_p" ]; then
    echo "DIFF $page: status $status_t vs $status_p${location_p:+, $location_t vs $location_p}"; return 1
  elif cmp -s "$out/$name.truffle.html" "$out/$name.php.html"; then
    echo "same $page ($status_p${location_p:+, $location_p}, $(wc -c < "$out/$name.php.html") bytes)"
  else
    echo "DIFF $page: $(diff "$out/$name.php.html" "$out/$name.truffle.html" | grep -c '^[<>]') lines differ"
    return 1
  fi
}

compare() {
  mkdir -p "$out"
  local failed=0 page
  for page in "${pages[@]}"; do
    check "$(echo "$page" | tr -c 'A-Za-z0-9\n' '_')" "$page" || failed=1
  done
  return $failed
}

# the installer: each engine installs WordPress through wp-admin/install.php into an empty database of its own,
# then the two databases are dumped and compared, with the times and the password hashes (random salts) masked
install() {
  mkdir -p "$out"
  local failed=0 side db=(-H 'X-Gate2-Db: wp_install_{side}')
  for side in truffle php; do
    docker exec gate2-mariadb mariadb -uroot -proot -e "drop database if exists wp_install_$side;
      create database wp_install_$side; grant all on wp_install_$side.* to wp@'%'"
  done
  check install-0 /wp-admin/install.php "${db[@]}" || failed=1
  check install-1 '/wp-admin/install.php?step=1' "${db[@]}" -d language= || failed=1
  check install-2 '/wp-admin/install.php?step=2' "${db[@]}" -d language= --data-urlencode 'weblog_title=Gate 2' \
    -d user_name=admin -d admin_password=admin -d admin_password2=admin -d pw_weak=on \
    -d admin_email=admin@example.com -d blog_public=1 || failed=1
  for side in truffle php; do
    docker exec gate2-mariadb mariadb-dump -uroot -proot --skip-dump-date --skip-extended-insert --order-by-primary \
      "wp_install_$side" | sed -E -e 's/[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}/DATETIME/g' \
      -e 's/\$wp\$2y\$[./A-Za-z0-9$]+/PASSWORD/g' -e 's/i:[0-9]{10};/i:TIME;/g' -e 's/\x27[0-9]{10}\x27/TIME/g' \
      -e 's/Database: wp_install_[a-z]+/Database: DB/' > "$out/install-db.$side.sql"
  done
  if cmp -s "$out/install-db.truffle.sql" "$out/install-db.php.sql"; then
    echo "same database ($(grep -c '^INSERT' "$out/install-db.php.sql") rows)"
  else
    echo "DIFF database: $(diff "$out/install-db.php.sql" "$out/install-db.truffle.sql" | grep -c '^[<>]') lines differ"
    failed=1
  fi
  return $failed
}

# the wp-admin login, after install: each engine logs in to the site it installed, with a cookie jar of its own,
# and shows wp-admin pages. Logging in to the wp-cli site checks a password hash regular PHP made.
login() {
  mkdir -p "$out"
  local failed=0 page db=(-H 'X-Gate2-Db: wp_install_{side}') jar=(-b "$out/cookies.{side}" -c "$out/cookies.{side}")
  rm -f "$out"/cookies.*
  check login-form /wp-login.php "${db[@]}" "${jar[@]}" || failed=1
  check login '/wp-login.php' "${db[@]}" "${jar[@]}" -d log=admin -d pwd=admin -d testcookie=1 \
    --data-urlencode 'wp-submit=Log In' --data-urlencode 'redirect_to=http://localhost/wp-admin/' || failed=1
  for page in /wp-admin/ /wp-admin/edit.php /wp-admin/post-new.php /wp-admin/options-general.php \
      /wp-admin/users.php /wp-admin/plugins.php /wp-admin/profile.php; do
    check "admin$(echo "$page" | tr -c 'A-Za-z0-9\n' '_')" "$page" "${db[@]}" "${jar[@]}" || failed=1
  done
  check login-wpcli '/wp-login.php' -b 'wordpress_test_cookie=WP%20Cookie%20check' -d log=admin -d pwd=admin \
    -d testcookie=1 --data-urlencode 'redirect_to=http://localhost/wp-admin/' || failed=1
  return $failed
}

# client sockets (fsockopen, stream_socket_client, Requests' fsockopen transport), each engine in its container
# against both servers: tests/gate2/sockets-probe.php
sockets() {
  mkdir -p "$out"
  local failed=0
  check sockets /sockets-probe.php || failed=1
  return $failed
}

case "${1:-all}" in
  start) start ;;
  stop) stop ;;
  compare) compare ;;
  install) install ;;
  login) login ;;
  sockets) sockets ;;
  all) start; compare ;;
esac
