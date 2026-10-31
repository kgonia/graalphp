#!/bin/sh
# Inside the native-image build image: builds Truffle PHP's launcher as a native standalone (target/native/$NI_NAME,
# default php) from the JVM build and the metadata agent.sh collected. The language initializes at build time; the
# classes that depend on the environment (the terminal, a Cleaner's thread) at run time. Native calls register
# themselves: pl.codeloom.php.ffi.NativeCallsFeature (enabled by the classes' META-INF/native-image) initializes the
# binding classes at build time, their native state at run time, and registers each declared downcall and direct
# upcall, so the agent's configuration must not carry foreign calls (wiki/native-calls.md). Extra options come in
# NI_OPTS; NI_CLASSES builds other classes (default target/classes).
set -e
cd /w
config=target/native/config
if grep -q '"foreign"' $config/reachability-metadata.json; then
  echo "image.sh: $config registers foreign calls; they must come from the bindings (rerun agent.sh)" >&2
  exit 1
fi
B=pl.codeloom.php.builtins
RT="$B.CurlFunctions,$B.EncodingFunctions,$B.PcreRecorder"
BT="pl.codeloom.php,com.fasterxml.jackson.core"
$GRAALVM_HOME/bin/native-image -J-Xmx14g \
  -cp "${NI_CLASSES:-target/classes}$(ls target/lib/*.jar | sed 's/^/:/' | tr -d '\n')" \
  --enable-native-access=ALL-UNNAMED -H:ConfigurationFileDirectories=$config \
  --initialize-at-build-time=$BT --initialize-at-run-time=$RT \
  $NI_OPTS \
  -o target/native/${NI_NAME:-php} pl.codeloom.php.launcher.PhpMain
