# 050: The launcher can't run a script from stdin, and every JVM prints an SLF4J notice on stderr

**State:** Open. Found 2026-11-08 by the WordPress PHPUnit suite. PHPUnit's process isolation pipes each isolated
test's code to `php` on stdin, and counts anything on stderr as the test's error.

**Symptom:**
1. `PhpMain` wants a file. `echo '<?php echo 1;' | php` runs nothing, where PHP's CLI runs stdin when it gets no
   script. The harness's `tests/wp-phpunit/truffle-php.sh` writes stdin to a file first.
2. Every JVM prints `SLF4J: No SLF4J providers were found` (and two more lines) on stderr. 14 isolated admin
   tests fail on it (`PHPUnit\Framework\Exception: SLF4J: ...`). The harness puts `slf4j-nop` on the class path.

**Fix:** to do.
- The launcher runs stdin when no script is given, as `php` does: `php -` and no argument. `__FILE__` is
  `Standard input code`, as in PHP.
- Ship an SLF4J provider that discards (or route it to the runtime's logging), so nothing reaches stderr that PHP
  wouldn't print.
