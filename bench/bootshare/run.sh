#!/usr/bin/env bash
# Bootstrap share of WordPress requests on PHP 8.5 (OPcache, tracing JIT; php -S, one request at a time, pinned to
# the benchmark cores): how long each request takes to reach wp_loaded (the bootstrap: core, plugins, theme, init)
# against its whole time, and its peak memory then and at the end. Pages: the home page, a post and the dashboard
# logged in, a REST collection; on each database of setup.sh. Rounds of requests per page, after a warmup; the
# requests' phase times go to target/bootshare/log.jsonl (prepend.php, mu-plugin.php), summarize.py prints medians.
# The site is copied into the container's own file system and the database is reached over the Docker bridge: a
# Windows bind mount and the host's port forwarding would make file and database access several times slower.
# PROFILE=1 also times each callback of the bootstrap's hooks (mu-plugin.php).
# Run after setup.sh. Usage: bench/bootshare/run.sh [rounds] [requests per round] [warmup]
set -euo pipefail
cd "$(dirname "$0")/../.."
rounds=${1:-4}; requests=${2:-50}; warmup=${3:-50}
root=$(pwd -W 2>/dev/null || pwd)
out=target/bootshare
pages=(home:/ post:/?p=1:login dashboard:/wp-admin/:login rest:/?rest_route=/wp/v2/posts)
docker rm -f bootshare-php > /dev/null 2>&1 || true
db_host=$(docker inspect gate2-mariadb --format '{{.NetworkSettings.Networks.bridge.IPAddress}}')
MSYS_NO_PATHCONV=1 docker run -d --name bootshare-php -p 8090:8090 --cpuset-cpus "${CPUSET:-14-21}" \
  -e BOOTSHARE_DB_HOST="$db_host:3306" -v "$root:/w:ro" bootshare-php:8.5 sh -c 'cp -r /w/target/bootshare/wordpress \
  /w/bench/bootshare/prepend.php /srv/ && cd /srv/wordpress && exec php -S 0.0.0.0:8090' > /dev/null
trap 'docker rm -f bootshare-php > /dev/null' EXIT
for _ in $(seq 300); do curl -s -o /dev/null http://127.0.0.1:8090/wp-includes/css/dashicons.css && break; sleep 1; done
fetch() {  # db page cookies label
  curl -s -o /dev/null -w '%{http_code} %{time_total}\n' -H 'Host: localhost' -H "X-Bootshare-Db: $1" \
    ${4:+-H "X-Bootshare: $4"} ${PROFILE:+-H "X-Bootshare-Profile: 1"} ${3:+-b "$out/cookies.$1"} "http://127.0.0.1:8090$2"
}
rm -f $out/curl.txt
docker exec bootshare-php rm -f /srv/log.jsonl /srv/profile.jsonl
for db in core plugins; do
  curl -s -o /dev/null -H 'Host: localhost' -H "X-Bootshare-Db: $db" -c "$out/cookies.$db" \
    -b 'wordpress_test_cookie=WP%20Cookie%20check' -d log=admin -d pwd=admin -d testcookie=1 \
    http://127.0.0.1:8090/wp-login.php
  for page in "${pages[@]}"; do
    IFS=: read -r name path login <<< "$page"
    for _ in $(seq "$warmup"); do fetch $db "$path" "$login" > /dev/null; done
  done
done
for round in $(seq "$rounds"); do
  for db in core plugins; do
    for page in "${pages[@]}"; do
      IFS=: read -r name path login <<< "$page"
      for _ in $(seq "$requests"); do echo "$db/$name $(fetch $db "$path" "$login" "$db/$name")" >> $out/curl.txt; done
    done
  done
done
docker cp bootshare-php:/srv/log.jsonl $out/log.jsonl > /dev/null
rm -f $out/profile.jsonl
[ -z "${PROFILE:-}" ] || docker cp bootshare-php:/srv/profile.jsonl $out/profile.jsonl > /dev/null
python -I bench/bootshare/summarize.py $out
