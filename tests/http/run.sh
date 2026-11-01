#!/usr/bin/env bash
# Runs each tests/http/**/*.php through a web SAPI on PHP 8.5 (php -S) and on Truffle PHP (launcher.HttpMain),
# and diffs the responses: for what only a web request shows (the working directory, CGI variables).
# Usage: tests/http/run.sh   (build first: mvn package -DskipTests)
set -uo pipefail
cd "$(dirname "$0")/../.."
root=$(pwd -W 2>/dev/null || pwd)
mkdir -p target/http
MSYS_NO_PATHCONV=1 docker run -d --rm --name http-php-$$ --cpuset-cpus ${CPUSET:-6-13} -p 18471:8080 -v "$root:/w:ro" \
  -w /w/tests/http gate1-php:8.5 php -S 0.0.0.0:8080 > /dev/null
java="${GRAALVM_HOME:-$HOME/.jdks/graalvm-25.4.4.1.1+1.1}/bin/java"
sep=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) sep=";";; esac
"$java" --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow -Dpolyglot.php.Parser=java \
  -Dpolyglot.engine.WarnInterpreterOnly=false -cp "target/classes${sep}target/lib/*" pl.codeloom.php.launcher.HttpMain \
  --port=18472 --docroot=tests/http -ddisplay_errors=1 -dhtml_errors=0 > target/http/server.log 2>&1 &
truffle=$!
for _ in $(seq 100); do curl -s -o /dev/null http://127.0.0.1:18472/ && curl -s -o /dev/null http://127.0.0.1:18471/ && break; sleep 0.2; done
failed=0
for f in $(cd tests/http && find . -name '*.php' | sort); do
  name=${f#./}
  curl -s "http://127.0.0.1:18471/$name" > "target/http/${name//\//_}.expected"
  curl -s "http://127.0.0.1:18472/$name" > "target/http/${name//\//_}.actual"
  if diff -q "target/http/${name//\//_}.expected" "target/http/${name//\//_}.actual" > /dev/null; then echo "ok   $name"
  else echo "FAIL $name (diff target/http/${name//\//_}.expected target/http/${name//\//_}.actual)"; failed=1; fi
done
docker rm -f http-php-$$ > /dev/null
kill $truffle
exit $failed
