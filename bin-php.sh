#!/usr/bin/env bash
# Runs a PHP script on the Truffle PHP runtime (JVM mode). Usage: ./bin-php.sh [launcher options] script.php [args]
# PHP_CLASSES (relative to the repo) runs another build of the runtime, for A/B comparisons, PHP_LIB with that build's
# libraries (default target/lib); PHP_NATIVE (relative to
# the repo) runs a native-image build of the launcher instead of the JVM.
root=$(cd "$(dirname "$0")" && (pwd -W 2>/dev/null || pwd))
java="${GRAALVM_HOME:-$HOME/.jdks/graalvm-25.4.4.1.1+1.1}/bin/java"
sep=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) sep=";";; esac
# the runtime's debugging functions (truffle_*) are declared for the tools run through this script; PHP_DEBUG_OPT=
# (empty) leaves the option out, for builds older than it (which declare those functions anyway)
if [ -n "$PHP_NATIVE" ]; then
  exec "$root/$PHP_NATIVE" ${PHP_DEBUG_OPT--Dpolyglot.php.Debug=true} -Djava.net.preferIPv6Addresses=system     $JAVA_OPTS_BASE $JAVA_OPTS "$@"
fi
exec "$java" --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow \
  ${PHP_DEBUG_OPT--Dpolyglot.php.Debug=true} -Djava.net.preferIPv6Addresses=system \
  $JAVA_OPTS_BASE $JAVA_OPTS \
  -cp "$root/${PHP_CLASSES:-target/classes}${sep}$root/${PHP_LIB:-target/lib}/*" pl.codeloom.php.launcher.PhpMain "$@"
