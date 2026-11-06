#!/usr/bin/env bash
# The count audit on Gate 2's WordPress (wiki/destructors.md, issue 036): a Truffle PHP server from this worktree's
# target/classes, with destructors at zero and the audit (-Dphp.rc.audit, every EVERY statement ends, default 20000),
# serves Gate 2's front-end pages and, logged in, its wp-admin pages (no writes but the login's session); then the
# findings are grouped across requests (audit_groups.py).
# - The site and AST exports come from the main checkout (MAIN, default ../wordpress: target/gate2/wordpress,
#   target/ast), the database from gate2-mariadb (on docker's default bridge, DB_HOST).
# - The server runs on CPUs 6-13 (off the benchmark CPUs 14-21) under a memory-lock reservation, and is removed after.
# Output: target/rcaudit/wp-audit.txt (the raw reports), wp-audit-groups.txt, pages/.
# Usage: bench/destructors/audit.sh [PORT]
set -euo pipefail
export PATH=/usr/bin:/bin:/mingw64/bin:$PATH
cd "$(dirname "$0")/../.."
root=$(pwd -W)
main=$(cd "${MAIN:-../wordpress}" && pwd -W)
port=${1:-8097}
out=target/rcaudit
MEMLOCK="python C:/Users/krzgo/Projects/memory-lock/memlock.py"
NAME=claude-wordpress-destructors-audit
$MEMLOCK acquire $NAME --gb 4 --timeout 0 --ttl 90 --note "wordpress-destructors: WordPress rc audit (CPUs 6-13)" \
  || { echo "no memlock reservation: stopping"; exit 3; }
trap 'docker rm -f rca1 > /dev/null 2>&1 || true; $MEMLOCK release $NAME > /dev/null 2>&1 || true' EXIT
mkdir -p $out/pages && rm -f $out/wp-audit.txt $out/pages/*
docker rm -f rca1 > /dev/null 2>&1 || true
MSYS_NO_PATHCONV=1 docker run -d --name rca1 --cpuset-cpus 6-13 -e GATE2_DB_HOST=${DB_HOST:-172.17.0.2:3306} \
  -p $port:8080 -v "$root:/m" -v "$main:/w" gate1-truffle:8.5-graal25.4 sh -c 'mkdir -p /srv/target/gate2 &&
    cp -r /w/target/ast /srv/target/ && cp -r /w/target/gate2/wordpress /srv/target/gate2/ && cd /srv &&
    exec $GRAALVM_HOME/bin/java --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow -Xmx2g
      -Dphp.rc.destructors=true -Dphp.rc.audit=true -Dphp.rc.audit.every='"${EVERY:-20000}"' -Dphp.rc.audit.groups=60
      -Dphp.rc.audit.out=/m/'"$out"'/wp-audit.txt -Djava.net.preferIPv6Addresses=system
      -Dpolyglot.engine.WarnInterpreterOnly=false $JAVA_OPTS_BASE -cp "/m/target/classes:/m/target/lib/*"
      pl.codeloom.php.launcher.HttpMain --port=8080 --threads=1 --docroot=target/gate2/wordpress
      --ast-root=target/ast --server-name=0.0.0.0 --host=0.0.0.0' > /dev/null
until docker logs rca1 2>&1 | grep -q listening; do
  docker ps -q --filter name=rca1 | grep -q . || { docker logs rca1; exit 1; }
  sleep 2
done
jar=(-b $out/pages/cookies -c $out/pages/cookies)
fetch() { # name page curl-args...
  local name=$1 page=$2
  shift 2
  echo "$(curl -s -m 600 -H 'Host: localhost' -o $out/pages/$name.html -w '%{http_code}' "$@" \
    "http://127.0.0.1:$port$page") $page"
}
for page in / "/?p=1" "/?page_id=2" "/?cat=1" "/?feed=rss2" /wp-login.php "/?rest_route=/" \
    "/?rest_route=/wp/v2/posts" "/?s=hello" /nonexistent-page/; do
  fetch "$(echo "$page" | tr -c 'A-Za-z0-9\n' '_')" "$page"
done
fetch login /wp-login.php "${jar[@]}" -d log=admin -d pwd=admin -d testcookie=1 --data-urlencode 'wp-submit=Log In' \
  --data-urlencode 'redirect_to=http://localhost/wp-admin/'
for page in /wp-admin/ /wp-admin/edit.php /wp-admin/post-new.php /wp-admin/options-general.php /wp-admin/users.php \
    /wp-admin/plugins.php /wp-admin/profile.php; do
  fetch "admin$(echo "$page" | tr -c 'A-Za-z0-9\n' '_')" "$page" "${jar[@]}"
done
python -I bench/destructors/audit_groups.py $out/wp-audit.txt > $out/wp-audit-groups.txt
grep -c '^rc-audit (end of script)\|^rc-audit (exit)' $out/wp-audit.txt | sed 's/^/requests audited: /'
head -1 $out/wp-audit-groups.txt
