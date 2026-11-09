# 035: The lazy parse reads `break_error_001`'s function body eagerly

**State:** Open (seen 2026-11-05/06; `mvn test` fails on `main` as well as on the destructor branches)

**Symptom:** `ZendParserCorpusTest.lazyTreesAreEagerOnPhpt` fails:
```
target\phpt\cases\Zend\tests\break_error_001.php: not every body was left unparsed ==> expected: <1941> but was: <1940>
```
The file is a function whose body is a compile-time error:
```php
function foo () {
    break 0;
}
```
The trees are the same; one function body was parsed with the file instead of being left as a `SourceBody`.

**Cause:** not investigated. Lead: the compile-time jump checks (issue 006, `parser/JumpChecks`) need the body to
raise "'break' operator accepts only positive integers" at compile time, so the parser reads it with the file.

**Fix:** to do. Either leave the body lazy and raise the error when the file compiles (php-src raises it then,
before anything runs), or, if that body must be read eagerly, make the test count only the bodies that may stay
lazy.
