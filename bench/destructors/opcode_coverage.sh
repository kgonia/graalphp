#!/usr/bin/env bash
# The fixtures' opcode coverage (opcode_coverage.py): php-src's opcode dumps (before the optimizer) of
# tests/rcaudit/*.php and, with CORPUS=DIR (e.g. the main checkout's target/gate2/wordpress), of every .php under DIR,
# compiled without running (opcache_compile_file), then the table. VM: Zend/zend_vm_def.h of the image's PHP version
# (default target/php-src-vm/zend_vm_def.h). Output: target/opcodes/{fixtures,corpus}.dump, the table on stdout.
# Usage: bench/destructors/opcode_coverage.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
out=target/opcodes
mkdir -p $out && rm -f $out/*.dump
dump='-d opcache.enable_cli=1 -d opcache.opt_debug_level=0x10000 -d opcache.optimization_level=0 -d opcache.jit=0'
dump="$dump -d memory_limit=1G"
compile='foreach (array_slice($argv, 1) as $f) { opcache_compile_file($f); }'
# a file each: one's class declarations would clash with the next's
MSYS_NO_PATHCONV=1 bash bench/gate1/php.sh sh -c "for f in tests/rcaudit/*.php; do php $dump -r '$compile' \$f; done   2> $out/fixtures.dump > /dev/null"
args=()
if [ -n "${CORPUS:-}" ]; then
  root=$(cd "$CORPUS" && pwd -W)
  MSYS_NO_PATHCONV=1 docker run --rm --cpuset-cpus 6-13 -v "$root:/c:ro" -v "$(pwd -W):/w" -w /w gate1-php:8.5 sh -c \
    "find /c -name '*.php' | xargs -n 1 php $dump -r '$compile' 2>> $out/corpus.dump > /dev/null; true"
  args+=(--corpus $out/corpus.dump)
fi
python -I bench/destructors/opcode_coverage.py $out/fixtures.dump "${args[@]}" --vm "${VM:-target/php-src-vm/zend_vm_def.h}"
