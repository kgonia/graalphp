#!/usr/bin/env bash
# Runs tests/gate2/mysqli-bytes.php on Truffle PHP and on regular PHP with mysqli (the wordpress:cli image)
# against the Gate 2 MariaDB, and diffs the outputs. Run after db.sh; build first. Usage: tests/gate2/mysqli.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
root=$(pwd -W 2>/dev/null || pwd)
script=tests/gate2/mysqli-bytes.php
mkdir -p target/gate2
MSYS_NO_PATHCONV=1 bash bench/gate1/php.sh php tools/ast-export.php target/ast $script > /dev/null
bash bench/gate1/truffle.sh $script 2>&1 | grep -v '^SLF4J' > target/gate2/mysqli.truffle.out
MSYS_NO_PATHCONV=1 docker run --rm --add-host=host.docker.internal:host-gateway -v "$root:/w" -w /w \
  wordpress:cli-php8.4 php $script > target/gate2/mysqli.php.out 2>&1
diff target/gate2/mysqli.php.out target/gate2/mysqli.truffle.out && echo "mysqli: same bytes on both engines"
