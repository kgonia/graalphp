#!/usr/bin/env bash
# The measurements of wiki/bytecode-dsl.md's "What a bytecode compilation costs" (2026-11-03).
# - stack: a database (MariaDB on CPUs 4-5) and a site volume of its own (bc-d-site, target/classes and every
#   target/classes-*), seeded from target/bc-d/src-seed.sql (Gate 2's).
# - diag TAG VISITS [OPTIONS...]: one diagnosis server (CPUs 6-13, nothing timed against another host's run) serving
#   tests/gate2/visits.py's paced visits (1 s pause, the driver on CPUs 0-3), the options as JVM options; the server's
#   threads' CPU sampled every second (thread name, utime + stime, a timestamp), the engine's log in
#   target/bc-d/out/TAG/engine.log. After the visits the server closes its engine (php.http.closeFile), so
#   CompilationStatistics print. Then cpusplit.py splits the CPU by thread class over windows of visits.
#   CLASSES: another class directory under target/ (default target/classes).
# Usage: bench/bc-compile-cost/runs.sh stack | diag TAG VISITS OPTIONS... | clean
set -euo pipefail
export PATH=/usr/bin:/bin:/mingw64/bin:$PATH
cd "$(dirname "$0")/../.."
root=$(pwd -W 2>/dev/null || pwd)
export WPN_NAME=bc-d WPN_NET=172.31.121 WPN_DB_PORT=3391 WPN_DB_CPUS=4-5 WPN_AUX_CPUS=6-13
P=bc-d
NET=172.31.121
out=target/$P
case ${1:-} in
  stack)
    WPN_DB_DUMP=$out/src-seed.sql bash tests/gate2/native.sh db
    bash tests/gate2/native.sh volume
    MSYS_NO_PATHCONV=1 docker run --rm --cpuset-cpus 6-13 -v $P-site:/srv -v "$root:/w:ro" alpine:3 sh -c '
      for d in /w/target/classes-*; do [ -d "$d" ] || continue; rm -rf /srv/target/$(basename $d); cp -r $d /srv/target/; done
      ls /srv/target' ;;
  diag)
    tag=$2 visits=$3; shift 3
    dir=$out/out/$tag runs=$out/runs
    rm -rf $dir && mkdir -p $dir $runs
    rm -f $runs/$tag.id $runs/$tag.json
    bash tests/gate2/native.sh reset-db
    MSYS_NO_PATHCONV=1 docker run --rm --name $P-driver --cpuset-cpus 0-3 --network $P-net --pid=host --cgroupns=host \
      -v /sys/fs/cgroup:/hostcg:ro -v "$root/tests/gate2:/t:ro" -v "$root/$runs:/runs" python:3.13-slim \
      python -u /t/visits.py http://$NET.10:8080 /runs/$tag.id "$visits" 1 /runs/$tag.json > $runs/$tag.log 2>&1 &
    driver=$!
    for _ in $(seq 100); do grep -q polling $runs/$tag.log 2> /dev/null && break; sleep 0.1; done
    export JAVA_OPTS_d="$* -Dpolyglot.log.file=/out/engine.log -Dphp.http.closeFile=/out/close"
    [ -z "${CLASSES:-}" ] || export CLASSES_d=$CLASSES
    OUT_DIR=$dir NOWAIT=1 bash tests/gate2/native.sh start jvm-java.d 6-13 8391
    docker inspect -f '{{.Id}} {{.State.Pid}}' $P-jvm-java.d > $runs/$tag.id
    # every second: a timestamp, the cgroup's CPU, and each thread's name and ticks
    MSYS_NO_PATHCONV=1 docker exec $P-jvm-java.d sh -c '
      rm -f /tmp/threadcpu.stop
      while [ ! -e /tmp/threadcpu.stop ]; do
        echo "## $(head -1 /sys/fs/cgroup/cpu.stat) clk_tck $(getconf CLK_TCK) time $(date +%s.%N)"
        for t in /proc/1/task/*; do
          printf "%s\t%s\t%s\n" "${t##*/}" "$(cat $t/comm)" "$(sed "s/.*) //" $t/stat | cut -d" " -f12,13)"
        done 2> /dev/null
        sleep 1
      done' > $dir/threads.txt 2> /dev/null &
    sampler=$!
    wait $driver || true
    MSYS_NO_PATHCONV=1 docker exec $P-jvm-java.d touch /tmp/threadcpu.stop || true
    wait $sampler || true
    # the engine closes after the next request (its statistics print), then the server exits
    touch $dir/close
    curl -s -o /dev/null "http://127.0.0.1:8391/wp-includes/css/dashicons.css" || true
    timeout 300 docker wait $P-jvm-java.d > /dev/null 2>&1 || echo "$P-jvm-java.d did not close in 300 s"
    docker logs $P-jvm-java.d > $dir/server.log 2>&1 || true
    bash tests/gate2/native.sh stop jvm-java.d
    cp $runs/$tag.json $dir/visits.json
    echo "$tag: $(tail -1 $runs/$tag.log)"
    python -I bench/bc-compile-cost/cpusplit.py $dir/visits.json $dir/threads.txt | tee $dir/split.txt ;;
  clean) bash tests/gate2/native.sh clean ;;
  *) sed -n 2,13p "$0"; exit 2 ;;
esac
