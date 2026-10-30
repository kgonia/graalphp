#!/bin/sh
# Inside the Truffle PHP image: runs the kernels and the smoke tests on the JVM under native-image-agent, merging what
# each run reaches (reflection, resources, FFM downcalls and upcalls) into target/native/config. JAVA_OPTS reaches
# the runs (e.g. -Dpolyglot.php.Interpreter=bytecode, to cover that interpreter's paths too).
set -e
out=target/native/config
mkdir -p "$out"
run() {
  $GRAALVM_HOME/bin/java -agentlib:native-image-agent=config-merge-dir=$out \
    --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow $JAVA_OPTS_BASE $JAVA_OPTS \
    -Djava.net.preferIPv6Addresses=system -Dpolyglot.engine.WarnInterpreterOnly=false \
    -cp "target/classes:target/lib/*" pl.codeloom.php.launcher.PhpMain --ast-root=target/ast "$@" > /dev/null 2>&1 || true
}
run bench/gate1/run.php all 20
run bench/k0/run.php all 3
for f in tests/smoke/*.php; do run "$f"; done
ls -la "$out"
