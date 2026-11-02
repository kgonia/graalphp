#!/usr/bin/env bash
# Run-once code (wiki/pe-round4.md, target 1) in small, in 10 contexts on one engine with compilation thresholds of
# 3 and 6 calls: main.php requires a class map of 1,200 entries (`'Pe4\ClassN' => $baseDir . '/src/ClassN.php'`, as
# Jetpack's) from two sites, as Jetpack's autoloader does, and has a class constant of 800 elements (a thunk);
# loop.php requires the map from one site in a loop. Before target 1 the map's compilation fails (code too large)
# after seconds and the thunk compiles; after it, main.php compiles neither (prepareForCompilation declines them)
# and loop.php still compiles the map (a site ran it twice: its runs depend on the data). The output is the same.
# Usage: bench/pe-round4/run-once.sh BUILD...   (target/classes-BUILD; e.g. t2 t1)
set -euo pipefail
cd "$(dirname "$0")/../.."
dir=target/run-once && mkdir -p $dir
python -I - $dir <<'PY'
import sys
d = sys.argv[1]
lines = ['<?php', '$baseDir = __DIR__;', 'return array(']
lines += [f"    'Pe4\\\\Class{i}' => $baseDir . '/src/Class{i}.php'," for i in range(1200)]
open(f'{d}/map.inc', 'w', newline='\n').write('\n'.join(lines + [');']) + '\n')
elements = ', '.join(f"'k{i}' => PHP_INT_SIZE + {i}" for i in range(800))
open(f'{d}/main.php', 'w', newline='\n').write(
    "<?php\n$m = require __DIR__ . '/map.inc';\n$version = require __DIR__ . '/map.inc';\n"
    "echo count($m), ' ', count($version), \"\\n\";\n"
    f"class Big {{ const T = ['a' => PHP_VERSION, {elements}]; }}\necho count(Big::T), \"\\n\";\n")
open(f'{d}/loop.php', 'w', newline='\n').write(
    "<?php\nfor ($i = 0; $i < 2; $i++) {\n    $m = require __DIR__ . '/map.inc';\n}\necho count($m), \"\\n\";\n")
PY
MSYS_NO_PATHCONV=1 bash bench/gate1/php.sh php tools/ast-export.php target/ast $dir/main.php $dir/loop.php \
  $dir/map.inc > /dev/null
opts="-Dpolyglot.engine.BackgroundCompilation=false -Dpolyglot.engine.DynamicCompilationThresholds=false"
opts+=" -Dpolyglot.engine.MinInvokeThreshold=1 -Dpolyglot.engine.FirstTierMinInvokeThreshold=1"
opts+=" -Dpolyglot.engine.FirstTierCompilationThreshold=3 -Dpolyglot.engine.LastTierCompilationThreshold=6"
opts+=" -Dpolyglot.engine.TraceCompilation=true -Dpolyglot.engine.CompilationFailureAction=Print"
for build in "$@"; do
  for script in main loop; do
    echo "== $build $script.php"
    PHP_CLASSES=target/classes-$build JAVA_OPTS="$opts" ./bin-php.sh --ast-root=target/ast --contexts=10 \
      $dir/$script.php 2>&1 | grep -E '^[0-9]|opt (done|failed).*(map\.inc|ThunkRootNode)' \
      | sed -E 's/ +/ /g; s/\|Addr.*\|UTC [^|]*//; s/\|UTC [^|]*//' | cut -c1-200
  done
done
