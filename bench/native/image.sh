#!/bin/sh
# Inside the native-image build image: builds Truffle PHP's launcher as a native standalone (target/native/$NI_NAME,
# default php) from the JVM build and the metadata agent.sh collected. The language initializes at build time; the
# classes that depend on the environment (libraries, the terminal) at run time. PcreFunctions.Native's address-less
# downcall handles are made at build time (constants, so calls are direct); the PCRE2 addresses (Native.Symbols) at
# run time. Extra options come in NI_OPTS; NI_CLASSES builds other classes (default target/classes).
set -e
cd /w
B=pl.codeloom.php.builtins
RT="$B.CurlFunctions,$B.CurlFunctions\$StderrFile\$Cookie,$B.CurlNative,$B.CurlNative\$Library,$B.CurlNative\$Callbacks,$B.EncodingFunctions,$B.PcreFunctions\$Native\$Symbols,$B.PcreRecorder"
BT="pl.codeloom.php,com.fasterxml.jackson.core"
$GRAALVM_HOME/bin/native-image -J-Xmx14g \
  -cp "${NI_CLASSES:-target/classes}$(ls target/lib/*.jar | sed 's/^/:/' | tr -d '\n')" \
  --enable-native-access=ALL-UNNAMED -H:+UnlockExperimentalVMOptions -H:+ForeignAPISupport \
  -H:ConfigurationFileDirectories=target/native/config \
  --initialize-at-build-time=$BT --initialize-at-run-time=$RT \
  $NI_OPTS \
  -o target/native/${NI_NAME:-php} pl.codeloom.php.launcher.PhpMain
