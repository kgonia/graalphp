#!/usr/bin/env bash
# The destructor fixtures (tests/rcaudit/*.php with a .expected) against both of php-src's behaviours: PHP 8.5
# without OPcache (each .expected, the opcodes as compiled) and with it (the image's default; its optimizer moves
# some frees, wiki/destructors.md). A line of ours equal to either passes; the report names the lines that match
# only one, and the ones that match neither (a bug).
# CLASSES: the class directory to run (default target/classes). Output: target/fixtures/NAME.{actual,opcache}.
# Usage: bench/destructors/fixtures.sh [NAME...]
set -euo pipefail
cd "$(dirname "$0")/../.."
classes=${CLASSES:-target/classes}
out=target/fixtures
mkdir -p $out
names=("$@")
if [ ${#names[@]} -eq 0 ]; then
  for f in tests/rcaudit/*.expected; do names+=("$(basename "$f" .expected)"); done
fi
files=()
for n in "${names[@]}"; do files+=("tests/rcaudit/$n.php"); done
MSYS_NO_PATHCONV=1 bash bench/gate1/php.sh sh -c "php tools/ast-export.php target/ast ${files[*]} > /dev/null;
  for n in ${names[*]}; do php tests/rcaudit/\$n.php > $out/\$n.opcache 2>&1; done"
status=0
for n in "${names[@]}"; do
  MSYS_NO_PATHCONV=1 docker run --rm --cpuset-cpus 6-13 -v "$(pwd -W):/m" gate1-truffle:8.5-graal25.4 sh -c \
    "cd /m && \$GRAALVM_HOME/bin/java --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow \
      -Dphp.rc.destructors=true -Dpolyglot.engine.WarnInterpreterOnly=false \$JAVA_OPTS_BASE ${JAVA_OPTS:-} \
      -cp '$classes:target/lib/*' pl.codeloom.php.launcher.PhpMain --ast-root=target/ast tests/rcaudit/$n.php" \
    > $out/$n.actual 2>&1 || true
  python -I - tests/rcaudit/$n.expected $out/$n.opcache $out/$n.actual "$n" <<'EOF' || status=1
import sys
off, on, ours = (open(p, encoding='utf-8', errors='replace').read().splitlines() for p in sys.argv[1:4])
name = sys.argv[4]
if not (len(off) == len(on) == len(ours)):
    print(f'{name}: line counts differ (no opcache {len(off)}, opcache {len(on)}, ours {len(ours)})')
    import difflib
    print('\n'.join(difflib.unified_diff(off, ours, 'no opcache', 'ours', lineterm='', n=0)))
    sys.exit(1)
both = only_off = only_on = 0
bad = []
for a, b, c in zip(off, on, ours):
    if c == a and c == b:
        both += 1
    elif c == a:
        only_off += 1
        print(f'  {name}: as without opcache only: {c}\n      (opcache: {b})')
    elif c == b:
        only_on += 1
        print(f'  {name}: as with opcache only: {c}\n      (no opcache: {a})')
    else:
        bad.append((a, b, c))
for a, b, c in bad:
    print(f'  {name}: DIFF ours:       {c}\n          no opcache: {a}' + (f'\n          opcache:    {b}' if b != a else ''))
print(f'{name}: {both} lines as both, {only_off} as no opcache only, {only_on} as opcache only, {len(bad)} as neither')
sys.exit(1 if bad else 0)
EOF
done
exit $status
