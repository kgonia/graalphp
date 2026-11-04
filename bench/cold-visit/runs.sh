#!/usr/bin/env bash
# WordPress's cold visit on Truffle PHP, bytecode against the tree (wiki/cold-visit.md). Builds are class directories
# target/classes-X (target/classes-X.commit: the commit). Every timed server runs on CPUs 14-21 under the CPU lock
# (C:\Users\krzgo\Projects\.cpu-14-21.lock), taken per server and let go after it; the database on 22-23, the
# client and the samplers on 0-13. Each run is tests/gate2/native.sh's measure_run: a server just started, a
# database just reset, the cold visit and VISITS more, 1 s apart.
# - stack: the database (cv-mariadb, seeded from wordpress-perfcheck's Gate 2 seed) and the site volume (cv-site:
#   the site, target/lib, every target/classes-X and target/app-X.jar, the AOT caches in target/aot).
# - classes: copies the class directories, jars and AOT caches into the volume again.
# - burn: the cpuset check (wiki/cold-visit.md, "Measurement"): a CPU burner in a container pinned to 14-21, then to
#   14-17, the VM's per-vCPU load (vmload.sh) beside the host's per-LP load (lpload.ps1). Takes the CPU lock.
# - runs ROUNDS VISITS VARIANT...: the variants interleaved, the order reversed every other round; START: the first
#   round's number. Runs: target/cv/runs/jvm-java.VARIANT-ROUND.{json,server.log,host,lp}; diagnosis output
#   (JFR, engine logs) in target/cv/out/rROUND.
# - aot-train VARIANT: a training run for VARIANT's AOT cache (CPUs 6-13, not timed): the server under
#   -XX:AOTCacheOutput, a cold visit and 20 more, then a clean exit that writes target/aot/VARIANT.aot.
# - clean: every cv- container, the volume and the network.
# Variants (define()): tree (main's 9ae85cb, php.Interpreter=tree), bc (this branch), with suffixes: d (diagnosis:
# phase counters, JFR with 1 ms samples, TraceCompilation), j (classes from the jar, the AOT cache's class path,
# no cache), a (the jar and its AOT cache).
set -euo pipefail
export PATH=/usr/bin:/bin:/mingw64/bin:$PATH
cd "$(dirname "$0")/../.."
root=$(pwd -W)
export WPN_NAME=cv WPN_NET=172.31.150 WPN_DB_PORT=3450 WPN_DB_CPUS=22-23 WPN_AUX_CPUS=6-13 WPN_MEASURE_PORT=8250
MEMLOCK="python C:/Users/krzgo/Projects/memory-lock/memlock.py"
hold() { # KIND GB TTL-minutes NOTE: a reservation, or stop
  $MEMLOCK acquire claude-wordpress-cold-$1 --gb "$2" --wait --ttl "$3" --note "$4" \
    || { echo "no memlock reservation: stopping"; exit 3; }
}
release() { $MEMLOCK release claude-wordpress-cold-$1 > /dev/null 2>&1 || true; }
CPU_LOCK=/c/Users/krzgo/Projects/.cpu-14-21.lock
lock() {
  local said=
  until mkdir $CPU_LOCK 2> /dev/null; do
    [ -n "$said" ] || echo "CPUs 14-21 taken: $(cat $CPU_LOCK/* 2> /dev/null | head -1)"; said=1; sleep 2
  done
  echo "claude-wordpress-cold (wordpress-cold): $*, since $(date '+%F %T')" > $CPU_LOCK/note
}
unlock() { rm -rf $CPU_LOCK; }
lpload() { # SECONDS OUT: the host's per-LP load, in the background (Windows, off LPs 14-21)
  cmd //c "$(cygpath -w bench/cold-visit/lpload.cmd)" "$1" "$(cygpath -w "$2")" &
}
JFR_EVENTS="settings=profile,jdk.ExecutionSample#period=1ms,jdk.Compilation#threshold=0ms"
define() { # VARIANT ROUND: CLASSES_V and JAVA_OPTS_V for native.sh's start
  local v=$1 r=$2 base=${1%[djam]} opts=
  case $base in
    tree) export CLASSES_$v=target/classes-tree; opts="-Dpolyglot.php.Interpreter=tree" ;;
    bc) export CLASSES_$v=target/classes-bc ;;
    *) echo "unknown variant $v"; exit 2 ;;
  esac
  case $v in
    *d) opts+=" -Dphp.phases=true -Dphp.nativeclock=true -Dpolyglot.engine.TraceCompilation=true"
        opts+=" -Dpolyglot.log.file=/out/engine-$v.log"
        opts+=" -XX:StartFlightRecording=filename=/out/$v.jfr,dumponexit=true,$JFR_EVENTS" ;;
    *j) export CLASSES_$v=target/app-$base.jar ;;
    *a) export CLASSES_$v=target/app-$base.jar
        opts+=" ${AOT_EXTRA:-} -XX:AOTCache=target/aot/$base${AOT_SUFFIX:-}.aot -Xlog:aot=info,cds=info:file=/out/aot-$v.log" ;;
    # the cache trained with jdk.internal.vm.ci named on the command line (AOT_EXTRA=--add-modules=..., AOT_SUFFIX=-m)
    *m) export CLASSES_$v=target/app-$base.jar
        opts+=" --add-modules=jdk.internal.vm.ci -XX:AOTCache=target/aot/$base-m.aot -Xlog:aot=info,cds=info:file=/out/aot-$v.log" ;;
  esac
  export JAVA_OPTS_$v="$opts"
}
case ${1:-} in
  stack)
    WPN_DB_DUMP=/c/Users/krzgo/Projects/wordpress-perfcheck/target/ba-g-seed.sql bash tests/gate2/native.sh db
    docker volume create cv-site > /dev/null
    MSYS_NO_PATHCONV=1 docker run --rm --cpuset-cpus 6-13 -v cv-site:/srv \
      -v "C:/Users/krzgo/Projects/wordpress-perfcheck/target/gate2/wordpress:/site:ro" -v "$root:/w:ro" alpine:3 sh -c '
      rm -rf /srv/target && mkdir -p /srv/target/gate2 && cp -r /site /srv/target/gate2/wordpress
      cp /w/tests/gate2/wp-config.php /srv/target/gate2/wordpress/wp-config.php
      mkdir -p /srv/target/gate2/wordpress/wp-content/mu-plugins
      cp /w/tests/gate2/fatal-plugin.php /srv/target/gate2/wordpress/wp-content/mu-plugins/
      cp -r /w/target/lib /srv/target/lib && cp -r /w/target/classes-bc /srv/target/ && du -sh /srv/target/*'
    "$0" classes ;;
  classes)
    MSYS_NO_PATHCONV=1 docker run --rm --cpuset-cpus 6-13 -v cv-site:/srv -v "$root:/w:ro" alpine:3 sh -c '
      cd /w/target
      for d in classes-*; do case $d in *.commit) ;; *) rm -rf /srv/target/$d; cp -r $d /srv/target/ ;; esac; done
      for j in app-*.jar; do [ ! -f "$j" ] || cmp -s $j /srv/target/$j || cp -p $j /srv/target/; done
      if [ -d aot ]; then rm -rf /srv/target/aot; cp -r aot /srv/target/aot; fi
      chmod -R a+rwX /srv/target/aot 2> /dev/null || true; ls /srv/target /srv/target/aot 2> /dev/null' ;;
  burn)
    out=target/cv/burn && mkdir -p $out
    hold burn 1 15 "wordpress-cold: cpuset check, a CPU burner on 14-21 (CPU lock)"
    lock "cpuset check: a CPU burner on 14-21"
    for set in 14-21:8 14-17:4; do
      cpus=${set%:*} n=${set#*:}
      MSYS_NO_PATHCONV=1 docker run -d --rm --name cv-burn --cpuset-cpus $cpus alpine:3 sh -c "
        for i in \$(seq $n); do (while :; do :; done) & done; sleep 40" > /dev/null
      sleep 3
      MSYS_NO_PATHCONV=1 docker run --rm --name cv-vmload --cpuset-cpus 6-13 -v "$root/bench/cold-visit:/t:ro" alpine:3 \
        sh /t/vmload.sh 25 > $out/vm-$cpus.txt 2>&1 &
      lpload 25 $out/lp-$cpus.txt
      wait
      docker rm -f cv-burn > /dev/null 2>&1 || true
      sleep 5
    done
    unlock
    release burn
    for f in $out/*.txt; do echo "== $f"; tail -3 $f; done ;;
  runs)
    rounds=$2 visits=$3; shift 3
    variants=("$@")
    runs=target/cv/runs
    mkdir -p $runs
    for r in $(seq "${START:-1}" "$rounds"); do
      if [ $((r % 2)) = 1 ]; then order="${variants[*]}"; else order=$(printf '%s\n' "${variants[@]}" | tac | tr '\n' ' '); fi
      for v in $order; do
        if [ -e $runs/jvm-java.$v-$r.json ]; then echo "jvm-java.$v-$r exists: skipped"; continue; fi
        define $v $r
        mkdir -p target/cv/out/r$r
        hold run 6 30 "wordpress-cold: Gate 2 cold visit jvm-java.$v-$r (CPUs 14-21, CPU lock)"
        rm -f $runs/jvm-java.$v-1.*
        lock "Gate 2 cold visit jvm-java.$v round $r"
        echo "$(date '+%F %T') $v-r$r: $(powershell -NoProfile -ExecutionPolicy Bypass -File bench/split-policy/hostload.ps1 | tr -d '\r')" \
          >> target/cv/hostload.log
        lpload 40 $runs/jvm-java.$v-$r.lp
        OUT_DIR=target/cv/out/r$r CPU_LOCK_HELD=1 HOST_QUIET=101 HOST_LOADED=101 \
          bash tests/gate2/native.sh measure 1 "$visits" 1 jvm-java.$v || true
        unlock
        release run
        wait
        for f in $runs/jvm-java.$v-1.*; do [ ! -e "$f" ] || mv "$f" "${f/jvm-java.$v-1./jvm-java.$v-$r.}"; done
      done
    done ;;
  aot-train)
    base=${2%[djam]}
    mkdir -p target/aot target/cv/train
    hold train 6 30 "wordpress-cold: AOT cache training run $base (CPUs 6-13)"
    define ${base}j 0
    opts=JAVA_OPTS_${base}j
    # the volume is mounted read only: the cache goes to /out (target/cv/train), then into target/aot and the volume
    # AOT_EXTRA: more options for the training and the timed runs alike; AOT_SUFFIX: the cache's name after $base
    name=$base${AOT_SUFFIX:-}
    export JAVA_OPTS_${base}t="${!opts} ${AOT_EXTRA:-} -XX:AOTCacheOutput=/out/$name.aot -Xlog:aot=info,cds=info:file=/out/$name.train.log"
    export CLASSES_${base}t=target/app-$base.jar
    bash tests/gate2/native.sh reset-db
    OUT_DIR=target/cv/train bash tests/gate2/native.sh start jvm-java.${base}t 6-13 8251
    MSYS_NO_PATHCONV=1 docker run --rm --name cv-train-driver --cpuset-cpus 0-5 --network cv-net python:3.13-slim python -c '
import urllib.request, http.cookiejar, urllib.parse, time
login = urllib.parse.urlencode({"log": "admin", "pwd": "admin", "testcookie": "1", "wp-submit": "Log In",
    "redirect_to": "http://localhost/wp-admin/"}).encode()
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a): return None
for n in range(21):
    jar = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()), NoRedirect)
    t = time.time()
    for p, data in [("/", None), ("/?p=1", None), ("/wp-login.php", None), ("/wp-login.php", login), ("/wp-admin/", None), ("/wp-admin/edit.php", None)]:
        try: jar.open(urllib.request.Request("http://172.31.150.10:8080" + p, data=data, headers={"Host": "localhost"}), timeout=300).read()
        except urllib.error.HTTPError as e: e.read()
    print("visit", n, round(time.time() - t, 2), flush=True)
    time.sleep(1)
' | tee target/cv/train/$name.visits.txt
    # a clean exit writes the cache: SIGTERM, then wait for the JVM (the dump takes a while)
    t0=$(date +%s)
    docker stop -t 600 cv-jvm-java.${base}t > /dev/null
    echo "stopped after $(( $(date +%s) - t0 )) s"
    docker logs cv-jvm-java.${base}t > target/cv/train/$name.server.log 2>&1 || true
    docker rm -f cv-jvm-java.${base}t > /dev/null 2>&1 || true
    release train
    ls -la target/cv/train
    [ ! -f target/cv/train/$name.aot ] || { cp target/cv/train/$name.aot target/aot/; "$0" classes; } ;;
  clean)
    bash tests/gate2/native.sh clean ;;
  *) sed -n 2,22p "$0"; exit 2 ;;
esac
