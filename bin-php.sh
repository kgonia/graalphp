#!/usr/bin/env bash
# Runs a PHP script on the Truffle PHP runtime (JVM mode). Usage: ./bin-php.sh [launcher options] script.php [args]
# PHP_CLASSES (relative to the repo) runs another build of the runtime, for A/B comparisons.
root=$(cd "$(dirname "$0")" && (pwd -W 2>/dev/null || pwd))
java="${GRAALVM_HOME:-$HOME/.jdks/graalvm-jdk-25.0.4+7.1}/bin/java"
sep=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) sep=";";; esac
exec "$java" --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow $JAVA_OPTS_BASE $JAVA_OPTS \
  -cp "$root/${PHP_CLASSES:-target/classes}${sep}$root/target/lib/*" pl.codeloom.php.launcher.PhpMain "$@"
