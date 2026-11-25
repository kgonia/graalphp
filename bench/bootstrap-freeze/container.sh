#!/bin/sh
set -eu
case "$1" in
compile)
    find /freeze/src -name '*.java' | sort > /tmp/sources.txt
    "$GRAALVM_HOME/bin/javac" -d /freeze/classes -cp "/elem/target/classes:/w/target/lib/*" @/tmp/sources.txt
    echo "Compiled $(wc -l < /tmp/sources.txt) overlay sources"
    mkdir -p /tmp/freeze-check/classes
    find /freeze/tests -name '*.java' | sort > /tmp/freeze-check/sources.txt
    classpath="/freeze/classes:/elem/target/classes:/w/target/lib/*"
    "$GRAALVM_HOME/bin/javac" -cp "$classpath" -d /tmp/freeze-check/classes @/tmp/freeze-check/sources.txt
    "$GRAALVM_HOME/bin/java" --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow \
        -cp "/tmp/freeze-check/classes:$classpath" pl.codeloom.php.launcher.FreezeRegression /tmp/freeze-check /freeze/resume.php
    exit 0
    ;;
serve) ;;
*) exit 2 ;;
esac

phase=$(cat /evidence/phase)
case "$phase" in
    baseline|rc-baseline|heap-baseline) frozen=false ;;
    frozen|rc-frozen|heap-frozen) frozen=true ;;
    *) exit 2 ;;
esac
exec > "/evidence/$phase/server.log" 2>&1
cd /srv
if [ ! -f target/bootshare/.freeze-site-ready ]; then
    mkdir -p target/bootshare
    cp -R /w/target/bootshare/wordpress target/bootshare/
    cp /evidence/wp-blog-header.php target/bootshare/wordpress/wp-blog-header.php
    touch target/bootshare/.freeze-site-ready
fi
set -- -Xmx2g -XX:NativeMemoryTracking=summary -Dpolyglot.php.Parser=java \
    -Djava.net.preferIPv6Addresses=system "-Dphp.bootstrap.freeze=$frozen"
case "$phase" in
    heap-*) set -- "$@" -Dphp.heapprobe=250 ;;
    rc-*) set -- "$@" -Dphp.rc.verify=true -Dphp.rc.verify.every=1 "-Dphp.rc.audit=${FREEZE_RC_AUDIT:-false}" ;;
esac
"$GRAALVM_HOME/bin/java" -version
echo "phase=$phase cpus=$(grep Cpus_allowed_list /proc/self/status) JAVA_OPTS_BASE=${JAVA_OPTS_BASE:-} flags=$*"
exec "$GRAALVM_HOME/bin/java" --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow \
    ${JAVA_OPTS_BASE:-} "$@" -cp "/freeze/classes:/elem/target/classes:/w/target/lib/*" \
    pl.codeloom.php.launcher.HttpMain --port=8080 --threads=8 --docroot=target/bootshare/wordpress \
    --ast-root=/none --server-name=localhost --host=0.0.0.0
