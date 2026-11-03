export PATH=/usr/bin:/bin:/mingw64/bin:$PATH
cd /c/Users/krzgo/Projects/wordpress-splitpolicy
r() { echo "== $(date '+%F %T') $*"; bash bench/split-policy/runs.sh "$@"; echo "== $(date '+%F %T') done $* ($?)"; }
rm -f target/ba-watch.stop
bash bench/split-policy/runs.sh watch > target/watch.log 2>&1 &
r gate1 3 200 A B C
OUT=target/sp-gate1-nohooks KERNELS=escape,slugs,wpautop,wptexturize,blocks,kses r gate1 1 200 A B
OUT=target/sp-gate1-wpautop KERNELS=wpautop r gate1 1 200 A B
r ab 3 A B C
r g2 3 200 A B C
touch target/ba-watch.stop
wait
