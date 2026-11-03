# 026: ErrorException ignores its severity, filename and line arguments

**State:** Open

**Symptom:**
```php
$e = new ErrorException('m', 0, E_WARNING, 'f.php', 42);
var_dump($e->getSeverity(), $e->getFile(), $e->getLine());   // PHP: int(2), "f.php", int(42)
```
Truffle PHP declares ErrorException as a plain subclass of Exception (`Builtins`' class hierarchy), so the
constructor takes Exception's arguments only and the line is where the exception was made. The usual error handler
that throws `new ErrorException($message, 0, $severity, $file, $line)` reports the handler's line. Found while
writing issue 021's test, which throws a RuntimeException instead.

**Fix:** to do: php-src's ErrorException (`$severity` property, `getSeverity()`, a constructor taking
`$message, $code, $severity, $filename, $line, $previous` that sets file and line when given).
