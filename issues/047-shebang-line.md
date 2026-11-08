# 047: A script's `#!` line isn't skipped

**State:** Open. Found 2026-11-08 by the WordPress PHPUnit suite: PHPUnit's own launcher, `vendor/bin/phpunit`,
doesn't start. The harness works around it with `tests/wp-phpunit/phpunit.php`.

**Symptom:**
```php
#!/usr/bin/env php
<?php declare(strict_types=1);
echo "ok\n";
// PHP 8.5: ok
// Truffle PHP: Fatal error: strict_types declaration must be the very first statement in the script ... on line 2
```

**Cause:** php-src's CLI skips a first line that starts with `#!` (`CG(skip_shebang)`), for the main script only.
Our launcher passes the line to the parser as inline HTML.

**Fix:** to do. Skip the main script's shebang line as the CLI does, and keep the line numbers: the skipped line
still counts. An included file's `#!` line stays output, as in PHP.
