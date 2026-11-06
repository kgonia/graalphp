#!/usr/bin/env bash
# translate.php on a build, in the gate1-truffle image with the site volume cv-site (bench/cold-visit/runs.sh stack):
# every .php file of wp-includes and wp-admin, a fresh JVM. CPUSET (default 6-13); JAVA_OPTS added (a JFR recording
# goes to /out = target/cv/translate). Prints translate.php's line and the container's memory.peak.
# Usage: bench/cold-visit/translate.sh target/classes-X (copied into the volume by runs.sh classes)
set -euo pipefail
export PATH=/usr/bin:/bin:/mingw64/bin:$PATH
cd "$(dirname "$0")/../.."
root=$(pwd -W)
mkdir -p target/cv/translate
MSYS_NO_PATHCONV=1 docker run --rm --cpuset-cpus "${CPUSET:-6-13}" -v cv-site:/srv:ro -v "$root:/w:ro" \
  -v "$root/target/cv/translate:/out" -w /srv gate1-truffle:8.5-graal25.4 sh -c '
  find /srv/target/gate2/wordpress/wp-includes /srv/target/gate2/wordpress/wp-admin -name "*.php" | sort > /tmp/list
  $GRAALVM_HOME/bin/java --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow -Xmx1g \
    -Dpolyglot.php.Debug=true -Dpolyglot.php.Parser=java -Dpolyglot.engine.WarnInterpreterOnly=false $1 \
    -cp "/srv/$0:/srv/target/lib/*" pl.codeloom.php.launcher.PhpMain --ast-root=/none /w/bench/cold-visit/translate.php /tmp/list
  echo "memory.peak $(( $(cat /sys/fs/cgroup/memory.peak) / 1048576 )) MB"' "$1" "${JAVA_OPTS:-}"
