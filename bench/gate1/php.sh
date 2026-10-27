#!/usr/bin/env bash
# Runs a command in the reference PHP container with the repo mounted at /w.
# Usage: bench/gate1/php.sh [8.5|8.4] php bench/gate1/run.php all 5
# PEAK_MEMORY=1 prints the container's peak memory (cgroup memory.peak) to stderr at the end.
set -euo pipefail
version=8.5
if [[ "${1:-}" =~ ^8\.[0-9]+$ ]]; then version=$1; shift; fi
root=$(cd "$(dirname "$0")/../.." && pwd -W 2>/dev/null || pwd)
MSYS_NO_PATHCONV=1 exec docker run --rm ${CPUSET:+--cpuset-cpus "$CPUSET"} -v "$root:/w" -w /w "gate1-php:$version"   ${PEAK_MEMORY:+sh -c "\$0 \"\$@\"; s=\$?; echo peak_memory \$(cat /sys/fs/cgroup/memory.peak) >&2; exit \$s"} "$@"
