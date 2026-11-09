#!/bin/sh
# `php` in the Truffle PHP container of tests/wp-phpunit/run.sh, which mounts this file over /usr/local/bin/php: the
# suite, its install step (system(WP_PHP_BINARY ...)) and PHPUnit's process-isolated tests (PHP_BINARY) all run on
# Truffle PHP. Takes php's -d name=value and a script; files are parsed by Truffle PHP's own parser (no AST export).
# Given no script, php runs its stdin (PHPUnit's process isolation pipes the test's code in); PhpMain wants a file,
# so stdin is written to one first, after a line-neutral prefix that loads shims.php and ConfigLoader.php, as
# phpunit.php does (the child runs PHPUnit and reads the configuration again).
# WP_PHPUNIT_CLASSES / WP_PHPUNIT_LIB: the runtime's classes and libraries (container paths); JAVA_OPTS is added.
# target/wp-phpunit/lib-extra holds slf4j-nop (setup.sh): without an SLF4J provider every JVM prints a notice on
# stderr, which PHPUnit takes for the error of a process-isolated test.
# The first JVM gets WP_PHPUNIT_XMX of heap (default 3g); the processes it starts get 1g, since they run one test.
if [ -n "$WP_PHPUNIT_CHILD" ]; then heap=1g; else heap=${WP_PHPUNIT_XMX:-3g}; fi
export WP_PHPUNIT_CHILD=1
script=
skip=
for arg in "$@"; do
  if [ -n "$skip" ]; then skip=; continue; fi
  case $arg in
    -d) skip=1 ;;
    -*) ;;
    *) script=$arg; break ;;
  esac
done
if [ -z "$script" ]; then
  stdin=$(mktemp /tmp/php-stdin-XXXXXX)
  printf '%s' "<?php define( 'WP_PHPUNIT_QUIET_SHIMS', true ); require '/w/tests/wp-phpunit/shims.php';" \
    " if ( getenv( 'PHPUNIT_CONFIG_SERIALIZED' ) ) { require '/w/tests/wp-phpunit/ConfigLoader.php'; } ?>" > "$stdin"
  cat >> "$stdin"
  set -- "$@" "$stdin"
fi
exec "$GRAALVM_HOME/bin/java" --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow \
  -Xmx$heap -Djava.net.preferIPv6Addresses=system -Dpolyglot.engine.WarnInterpreterOnly=false \
  -Dpolyglot.php.Parser=java $JAVA_OPTS_BASE $JAVA_OPTS \
  -cp "$WP_PHPUNIT_CLASSES:$WP_PHPUNIT_LIB/*:/w/target/wp-phpunit/lib-extra/*" pl.codeloom.php.launcher.PhpMain "$@"
