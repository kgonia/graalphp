#!/usr/bin/env bash
# Gate 2, first milestone: boots WordPress core in CLI (wp-load.php) on Truffle PHP and on PHP 8.5.
# Run after fetch.sh, export.sh and db.sh; build first. Usage: tests/gate2/boot.sh [truffle|php|both]
set -euo pipefail
cd "$(dirname "$0")/../.."
cp tests/gate2/wp-config.php target/gate2/wordpress/wp-config.php
MSYS_NO_PATHCONV=1 bash bench/gate1/php.sh php tools/ast-export.php target/ast tests/gate2/boot.php \
  target/gate2/wordpress/wp-config.php
# in the Linux container, as the benchmarks: paths as on a server (/w/...), the database on the host
bash bench/gate1/truffle.sh tests/gate2/boot.php
