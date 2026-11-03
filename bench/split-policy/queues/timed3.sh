export PATH=/usr/bin:/bin:/mingw64/bin:$PATH
cd /c/Users/krzgo/Projects/wordpress-splitpolicy
while ps -ef | grep -q "[t]arget/timed2.sh\|[t]arget/timed.sh"; do sleep 30; done
r() { echo "== $(date '+%F %T') $*"; bash bench/split-policy/runs.sh "$@"; echo "== $(date '+%F %T') done $* ($?)"; }
rm -f target/ba-watch.stop
bash bench/split-policy/runs.sh watch > target/watch3.log 2>&1 &
# one more run of the tree: timed2's second run took its first one's name (measure's VARIANT-1) and deleted its files
python C:/Users/krzgo/Projects/memory-lock/memlock.py acquire claude-wordpress-bcaudit-g2 --gb 6 --wait --ttl 30 --note "wordpress-splitpolicy: Gate 2 visits, the tree (CPUs 14-21, CPU lock)" || exit 3
until mkdir /c/Users/krzgo/Projects/.cpu-14-21.lock 2> /dev/null; do sleep 15; done
echo "claude-wordpress-bcaudit (wordpress-splitpolicy): Gate 2 visits, the tree, since $(date '+%F %T')" > /c/Users/krzgo/Projects/.cpu-14-21.lock/note
echo "$(date '+%F %T') g2 T-r3: $(powershell -NoProfile -ExecutionPolicy Bypass -File bench/split-policy/hostload.ps1 | tr -d '')" >> target/hostload.log
mkdir -p target/ba-g/out/t3
WPN_NAME=ba-g WPN_NET=172.31.103 WPN_DB_PORT=3373 WPN_DB_CPUS=22-23 WPN_AUX_CPUS=6-13 CLASSES_T=target/classes-C JAVA_OPTS_T="-Dpolyglot.php.Interpreter=tree -Dpolyglot.engine.TraceCompilation=true -Djdk.graal.internal.PrintGC=true -Dpolyglot.log.file=/out/engine-T.log" OUT_DIR=target/ba-g/out/t3 CPU_LOCK_HELD=1 HOST_QUIET=101 HOST_LOADED=101 bash tests/gate2/native.sh measure 1 200 1 jvm-java.T || true
rm -rf /c/Users/krzgo/Projects/.cpu-14-21.lock
python C:/Users/krzgo/Projects/memory-lock/memlock.py release claude-wordpress-bcaudit-g2
for f in target/ba-g/runs/jvm-java.T-1.*; do mv "$f" "${f/jvm-java.T-1./jvm-java.T-3.}"; done
# wpautop alone (timed2's attempt stopped: memlock's decide.lock refused, PermissionError)
OUT=target/sp-gate1-wpautop-2 KERNELS=wpautop r gate1 2 200 A B
# Gate 2's third round ran at 79-90 % host load: two more
START=4 r g2 5 200 A B C
# one confirming round on main (4214f6b, with #32): mA = main, mB = main and 59d9b30's ElementReadNode
r g2 1 200 mB mA
r ab 1 mA mB
touch target/ba-watch.stop
wait
