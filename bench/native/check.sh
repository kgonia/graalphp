#!/usr/bin/env bash
# The per-call check of a native build (bench/native/check.php), in the Truffle PHP image on CPUSET (default 6-13).
# Usage: bench/native/check.sh [binary, default target/native/php] [limit in ns]
set -euo pipefail
cd "$(dirname "$0")/../.."
MSYS_NO_PATHCONV=1 CPUSET=${CPUSET:-6-13} bash bench/gate1/php.sh php tools/ast-export.php target/ast bench/native/check.php > /dev/null
CPUSET=${CPUSET:-6-13} PHP_NATIVE=${1:-target/native/php} bash bench/gate1/truffle.sh bench/native/check.php ${2:-2000}
