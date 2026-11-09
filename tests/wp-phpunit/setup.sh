#!/usr/bin/env bash
# Prepares WordPress core's PHPUnit suite (wordpress-develop) for tests/wp-phpunit/run.sh, under target/wp-phpunit:
#  - wordpress-develop at the commit Gate 2's WordPress was built from (svn trunk@64680, WordPress 7.2-beta1), from
#    codeload, and the built assets src/ lacks without `npm run build` (js/dist, css/dist, the blocks' built files),
#    copied from WordPress/WordPress at the same revision (only files src/ doesn't have; no PHP file of src/ changes);
#  - the reference image wp-phpunit-php:8.5 (tests/wp-phpunit/php.Dockerfile: gate1-php:8.5 with mysqli, gd, exif);
#  - composer's dev dependencies (PHPUnit 9 through yoast/phpunit-polyfills 1.x), installed on that image;
#  - wp-tests-config.php (tests/wp-phpunit/wp-tests-config.php), and a database user on gate2-mariadb that can only
#    reach the wp\_phpunit% databases (one per engine, made by run.sh), never Gate 2's own.
# Records what it fetched in target/wp-phpunit/versions.txt.
# Usage: tests/wp-phpunit/setup.sh     (needs gate2-mariadb running: tests/gate2/db.sh)
set -euo pipefail
cd "$(dirname "$0")/../.."
root=$(pwd -W 2>/dev/null || pwd)
DEVELOP=e29e51dac68aa0082c37022ee7ea640c2f7f6b24   # WordPress/wordpress-develop, svn trunk@64680, 2026-10-22
BUILD=d86e2079edbf869a0393ee99a91da19351420461     # WordPress/WordPress "Built from trunk@64680" (tests/gate2/fetch.sh)
COMPOSER=2.8.4
CPUSET=${CPUSET:-6-13}                             # never the benchmark CPUs 14-21
out=target/wp-phpunit
dev=$out/wordpress-develop
mkdir -p "$out"

echo "== wordpress-develop $DEVELOP"
rm -rf "$dev" "$out/build" && mkdir -p "$out/build"
curl -fsSL "https://codeload.github.com/WordPress/wordpress-develop/tar.gz/$DEVELOP" | tar -xz -C "$out"
mv "$out/wordpress-develop-$DEVELOP" "$dev"
curl -fsSL "https://codeload.github.com/WordPress/WordPress/tar.gz/$BUILD" | tar -xz -C "$out/build"
# built assets only: wp-includes and wp-admin files src/ lacks (cp -n never replaces one of src/'s)
cp -rn "$out/build/WordPress-$BUILD/wp-includes/." "$dev/src/wp-includes/"
cp -rn "$out/build/WordPress-$BUILD/wp-admin/." "$dev/src/wp-admin/"
rm -rf "$out/build"

echo "== reference image wp-phpunit-php:8.5"
docker build -q --cpuset-cpus "$CPUSET" -t wp-phpunit-php:8.5 -f tests/wp-phpunit/php.Dockerfile tests/wp-phpunit

echo "== composer $COMPOSER, dev dependencies"
[ -f "$out/composer.phar" ] || curl -fsSL -o "$out/composer.phar" "https://getcomposer.org/download/$COMPOSER/composer.phar"
# composer.json has "lock": false, so install resolves as update does (as WordPress's CI runs it)
MSYS_NO_PATHCONV=1 docker run --rm --cpuset-cpus "$CPUSET" -v "$root:/w" -w "/w/$dev" -e COMPOSER_HOME=/w/$out/composer-home \
  wp-phpunit-php:8.5 sh -c "php /w/$out/composer.phar update --no-interaction --no-progress --no-audit -q \
    && php /w/$out/composer.phar show --no-interaction | grep -E '^(phpunit/phpunit|yoast/phpunit-polyfills) '" \
  > "$out/composer-show.txt"
cat "$out/composer-show.txt"

# The suite refuses to start without gd (bootstrap.php's $required_extensions). Truffle PHP has no gd yet, so
# the check is dropped from the copy, for both engines: on PHP, which has gd, nothing changes; on Truffle PHP the
# image tests then run and fail by name instead of the whole suite not starting.
sed -i "s/^\t\t'gd',$/\t\t\/\/ 'gd', (dropped by tests\/wp-phpunit\/setup.sh)/" "$dev/tests/phpunit/includes/bootstrap.php"
grep -q "dropped by tests/wp-phpunit/setup.sh" "$dev/tests/phpunit/includes/bootstrap.php"
cp tests/wp-phpunit/wp-tests-config.php "$dev/wp-tests-config.php"
# an SLF4J provider for Truffle PHP's JVMs (truffle-php.sh): without one each prints a notice on stderr, which
# PHPUnit takes for the error of a process-isolated test
mkdir -p "$out/lib-extra"
[ -f "$out/lib-extra/slf4j-nop-2.0.7.jar" ] || curl -fsSL -o "$out/lib-extra/slf4j-nop-2.0.7.jar" \
  https://repo1.maven.org/maven2/org/slf4j/slf4j-nop/2.0.7/slf4j-nop-2.0.7.jar

echo "== database user wp_phpunit on gate2-mariadb"
docker exec gate2-mariadb mariadb -uroot -proot -e "CREATE USER IF NOT EXISTS 'wp_phpunit'@'%' IDENTIFIED BY 'wp_phpunit';
  GRANT ALL PRIVILEGES ON \`wp\\_phpunit%\`.* TO 'wp_phpunit'@'%'; FLUSH PRIVILEGES;"

{
  echo "wordpress-develop $DEVELOP (svn trunk@64680)"
  echo "WordPress build $BUILD (assets src/ lacks)"
  echo "composer $COMPOSER"
  cat "$out/composer-show.txt"
  echo "bootstrap.php: gd requirement dropped"
} > "$out/versions.txt"
echo "setup done: $(find "$dev/tests/phpunit/tests" -name '*.php' | wc -l) test files"
