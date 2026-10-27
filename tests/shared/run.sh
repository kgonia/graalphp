#!/usr/bin/env bash
# Runs PHP in many contexts at once on one shared engine (as a server runs requests) and checks they stay
# isolated: language-level arrays (constants, class defaults) are shared by every context and must never change;
# curl handles dropped while they run are freed by each context on its own thread (with a small heap, so that the
# GC and the Cleaner run often); a thread's end cleans up its persistent curl shares.
# Usage: tests/shared/run.sh [threads] [runs per thread]. Build first: mvn package -DskipTests
set -euo pipefail
cd "$(dirname "$0")/../.."
MSYS_NO_PATHCONV=1 bash bench/gate1/php.sh php tools/ast-export.php target/ast tests/shared/isolation.php \
  tests/shared/curl-lifecycle.php tests/shared/curl-persistent.php
java="${GRAALVM_HOME:-$HOME/.jdks/graalvm-jdk-25.0.4+7.1}/bin/java"
sep=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) sep=";";; esac
"$java" --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow \
  -cp "target/classes${sep}target/lib/*" tests/shared/SharedContexts.java tests/shared/isolation.php isolated \
  "${1:-4}" "${2:-10}"
"$java" -Xmx512m --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow \
  -cp "target/classes${sep}target/lib/*" tests/shared/SharedContexts.java tests/shared/curl-lifecycle.php \
  "curl-lifecycle ok" "${1:-4}" "${2:-10}"
"$java" --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow \
  -cp "target/classes${sep}target/lib/*" tests/shared/PersistentShares.java tests/shared/curl-persistent.php
