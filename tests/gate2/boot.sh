#!/usr/bin/env bash
# Gate 2, first milestone: boots WordPress core in CLI (wp-load.php) on Truffle PHP, then reads through wpdb and
# the WordPress APIs (query.php) on Truffle PHP and on regular PHP with mysqli (the wordpress:cli image), and
# compares the two. Run after fetch.sh, export.sh, db.sh and install.sh; build first. Usage: tests/gate2/boot.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
cp tests/gate2/wp-config.php target/gate2/wordpress/wp-config.php
MSYS_NO_PATHCONV=1 bash bench/gate1/php.sh php tools/ast-export.php target/ast tests/gate2/boot.php \
  tests/gate2/query.php target/gate2/wordpress/wp-config.php
# in the Linux container, as the benchmarks: paths as on a server (/w/...), the database on the host
bash bench/gate1/truffle.sh tests/gate2/boot.php 2>&1 | grep -v '^SLF4J'
bash bench/gate1/truffle.sh tests/gate2/query.php 2>&1 | grep -v '^SLF4J' > target/gate2/query.truffle
root=$(pwd -W 2>/dev/null || pwd)
MSYS_NO_PATHCONV=1 docker run --rm --add-host=host.docker.internal:host-gateway -v "$root:/w" -w /w \
  wordpress:cli-php8.4 php tests/gate2/query.php > target/gate2/query.php.out 2>&1
if diff target/gate2/query.php.out target/gate2/query.truffle; then
  echo "query.php: same output on both engines ($(wc -l < target/gate2/query.truffle) lines)"
else
  echo "query.php: outputs differ (above: < PHP, > Truffle PHP)"; exit 1
fi
