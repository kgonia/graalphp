#!/usr/bin/env bash
# Runs each tests/http/**/*.php behind nginx, through php-fpm 8.5.12 and through Truffle PHP's FastCGI server
# (launcher.FastCgiMain), with the same nginx.conf (tests/gate2/woo-fpm), and diffs the whole responses: the status
# line, the headers (but Date and Server) and the body. status.php runs once per case in CASES.
# Usage: tests/http/fpm.sh   (build first: mvn package -DskipTests; the images: tests/gate2/native.sh images)
set -uo pipefail
cd "$(dirname "$0")/../.."
root=$(pwd -W 2>/dev/null || pwd)
out=target/http-fpm
mkdir -p $out
docroot=/srv/target/gate2/wordpress
MSYS_NO_PATHCONV=1 docker run -d --rm --name http-fpm-$$ --cpuset-cpus ${CPUSET:-6-13} -p 18473:8080 \
  -v "$root/tests/http:$docroot:ro" gate2-fpm:8.5.12 /usr/local/bin/woo-fpm-start > /dev/null
MSYS_NO_PATHCONV=1 docker run -d --rm --name http-fcgi-$$ --cpuset-cpus ${CPUSET:-6-13} -p 18474:8080 \
  -v "$root/tests/http:$docroot:ro" -v "$root:/w:ro" gate2-fcgi:graal25.4 sh -c \
  'exec /usr/local/bin/fcgi-start $GRAALVM_HOME/bin/java --enable-native-access=ALL-UNNAMED \
     --sun-misc-unsafe-memory-access=allow $JAVA_OPTS_BASE -Dpolyglot.php.Parser=java \
     -Dpolyglot.engine.WarnInterpreterOnly=false -cp "/w/target/classes:/w/target/lib/*" \
     pl.codeloom.php.launcher.FastCgiMain --listen=127.0.0.1:9000 -ddisplay_errors=1 -dhtml_errors=0 -dlog_errors=1' \
  > /dev/null
for _ in $(seq 300); do
  curl -s -o /dev/null -f http://127.0.0.1:18473/sub/cwd.php && curl -s -o /dev/null -f http://127.0.0.1:18474/sub/cwd.php && break
  sleep 0.2
done
CASES=("m=code&c=200" "m=code&c=302" "m=code&c=404" "m=code&c=418" "m=code&c=421" "m=code&c=507" "m=code&c=599"
       "m=line&c=404" "m=line&c=200" "m=status&c=404" "m=status&c=200" "m=line-then-code" "m=code-then-status"
       "m=location" "m=location-301")
fetch() { # port path [curl args]: the response without the headers that differ by time and server
  local port=$1 path=$2
  shift 2
  curl -s -i "$@" "http://127.0.0.1:$port$path" | tr -d '\r' | grep -v -i '^\(date\|server\): '
}
failed=0
check() { # name path [curl args]
  local name=$1 path=$2
  shift 2
  fetch 18473 "$path" "$@" > "$out/$name.expected"
  fetch 18474 "$path" "$@" > "$out/$name.actual"
  if diff -q "$out/$name.expected" "$out/$name.actual" > /dev/null; then echo "ok   $name"
  else echo "FAIL $name (diff $out/$name.expected $out/$name.actual)"; failed=1; fi
}
for f in $(cd tests/http && find . -name '*.php' ! -name status.php | sort); do
  name=${f#./}
  check "${name//\//_}" "/$name"
done
for c in "${CASES[@]}"; do
  check "status_${c//[&=]/_}" "/status.php?$c"
done
check status_post_location "/status.php?m=location" -d x=1
docker logs http-fcgi-$$ > $out/fcgi.log 2>&1
docker rm -f http-fpm-$$ http-fcgi-$$ > /dev/null
exit $failed
