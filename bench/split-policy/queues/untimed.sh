export PATH=/usr/bin:/bin:/mingw64/bin:$PATH
cd /c/Users/krzgo/Projects/wordpress-splitpolicy
MEMLOCK="python C:/Users/krzgo/Projects/memory-lock/memlock.py"
say() { echo "== $(date '+%F %T') $*"; }
# Gate 2's pages: PHP 8.5 and C, one server at a time on CPUs 6-13
say pages
$MEMLOCK acquire claude-wordpress-bcaudit-pages --gb 4 --wait --ttl 60 --note "wordpress-splitpolicy: Gate 2 pages (CPUs 6-13)" || exit 3
( export WPN_NAME=ba-g WPN_NET=172.31.103 WPN_DB_PORT=3373 WPN_AUX_CPUS=6-13 CLASSES_C=target/classes-C
  g2() { bash tests/gate2/native.sh "$@"; }
  g2 reset-db && g2 start php 6-13 8093 && g2 pages php 8093; g2 stop php
  g2 reset-db && g2 start jvm-java.C 6-13 8093 && g2 pages jvm-java.C 8093; g2 stop jvm-java.C
  echo "jvm-java.C: $(g2 compare php jvm-java.C | grep -c '^same') of $(ls target/ba-g/pages/php/*.html | wc -l) pages the same"
  g2 compare php jvm-java.C | grep '^DIFF' || true )
$MEMLOCK release claude-wordpress-bcaudit-pages
# the WooCommerce walk: the tree against C, on ba-d
say walk
$MEMLOCK acquire claude-wordpress-bcaudit-walk --gb 4 --wait --ttl 60 --note "wordpress-splitpolicy: WooCommerce walk (CPUs 6-13)" || exit 3
( export WPN_NAME=ba-d WPN_NET=172.31.101 WPN_DB_PORT=3371 WPN_DB_CPUS=12-13 WPN_AUX_CPUS=6-13 DEV=1
  w() { bash tests/gate2/woo.sh "$@"; }
  SEED=seed w reset-db && DEV_CLASSES=target/classes-C JAVA_OPTS=-Dpolyglot.php.Interpreter=tree w start jvm 6-13 8094 \
    && w walk jvm 8094; w stop jvm
  rm -rf target/ba-d/walk/tree target/ba-d/rows/tree; mv target/ba-d/walk/jvm target/ba-d/walk/tree; mv target/ba-d/rows/jvm target/ba-d/rows/tree
  SEED=seed w reset-db && DEV_CLASSES=target/classes-C w start jvm 6-13 8094 && w walk jvm 8094; w stop jvm
  w compare tree jvm > target/walk-compare.txt; echo "walk: $(grep -c '^same' target/walk-compare.txt) same, $(grep -c '^DIFF' target/walk-compare.txt) DIFF"
  grep '^DIFF' target/walk-compare.txt | head )
$MEMLOCK release claude-wordpress-bcaudit-walk
# smoke on bytecode (the default), C
say smoke
cmd //c target\smoke.cmd
grep -v '^ok' target/smoke-C.txt; echo "smoke: $(grep -c '^ok' target/smoke-C.txt) ok of $(ls tests/smoke/*.php | wc -l)"
# mvn clean test of C's tree
say mvn
$MEMLOCK acquire claude-wordpress-bcaudit-mvn --gb 8 --wait --ttl 90 --note "wordpress-splitpolicy: mvn clean test (CPUs 0-13)" || exit 3
cmd //c target\mvntest.cmd
$MEMLOCK release claude-wordpress-bcaudit-mvn
grep -E "Tests run:|FAIL|BUILD" target/mvn-C.log | tail -5
# the splits per variant
for v in A B C; do
  say diag $v
  bash bench/split-policy/runs.sh diag $v woo-$v 300
  bash bench/split-policy/runs.sh diag $v g2-$v 300 g2
done
say done
