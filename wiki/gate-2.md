# Gate 2: WordPress boots and renders

The spec is in [plan.md](plan.md) §4. The scorecard covers php-src `.phpt` pass %, AST parity and differential fuzzing. The milestones are `wp-load.php` in CLI → installer → home page over HTTP → wp-admin login.

## Probe (2026-10-23): how far WordPress core is from running

WordPress core at the Gate 1 commit (`d86e2079`, 2080 PHP files) is measured against Truffle PHP at `ac27670`. These scripts re-run the probe and write their reports under `target/gate2/`:
- `tests/gate2/fetch.sh` fetches the core.
- `tests/gate2/export.sh` exports the ASTs (2080 files in 54 s, 79 MB of JSON).
- `tests/gate2/syntax.sh` checks syntax.
- `tests/gate2/builtins.sh` checks built-ins.

### Syntax
The probe translates every file and every function, method and closure body (with `truffle_translate`, so nothing runs). The translation completes in 6 s. **601 of 2080 files hit at least one gap.** Only the first construct a file or body stops at is counted, so more will surface as these are fixed.

| Errors | Gap | Notes |
|---:|---|---|
| 301 + 43 | **namespaces** and `use` | The bundled libraries: Requests, PHPMailer, SimplePie, sodium_compat, getID3, the PHP AI client |
| 510 | **superglobals**: `$_GET`, `$_POST`, `$_REQUEST`, `$_SERVER`, `$_COOKIE`, `$_FILES`, `$GLOBALS` | The boot needs `$_SERVER` |
| 62 + 1 | **`compact()` and `extract()`** | Access to the local scope by name |
| 16 | **`goto`** | WordPress's own HTML API (the block processor) uses it |
| 26 | assignment to `$this` | Old code in `deprecated.php` |
| 6 | anonymous classes | |
| 5 | `instanceof` with a dynamic class | |
| 5 | generators (`yield`) | |
| 1 each | trait adaptations, a dynamic static method name, a method call as an assignment target, `extract()` | |

### Built-ins
Names come from the AST: statically named calls, `new`, static calls, class constants, `instanceof`, `catch`, `extends` and `implements`. A name counts as a built-in when the reference PHP 8.5 declares it.

- **Functions: WordPress calls 572 distinct built-ins (31,786 call sites). Truffle PHP has 217 and lacks 355 (3,538 call sites).** By extension:
  - standard: 159 missing (`call_user_func`, `fopen`/`fread`/`fwrite`/`fclose`, `header`, `serialize`, …);
  - Core: 14 (**`exit` with 414 call sites**, `clone` with 154, `class_alias`, `func_get_args`, …);
  - date: 16 (`gmdate`, `strtotime`, `mktime`);
  - curl: 14;
  - hash: 9;
  - sodium: 76, though `sodium_compat` polyfills them in PHP;
  - xml: 16;
  - zlib: 9;
  - random: 2 (`random_bytes`, `random_int`);
  - mbstring: 6;
  - openssl: 10;
  - the rest: 1–5 each.
- **Classes: 48 used, 19 present, 29 missing:** `SodiumException`, `SplFixedArray`, `DateTime`/`DateTimeZone`/`DateTimeImmutable`/`DateInterval`, DOM (`DOMDocument`, `DOMXPath`), the SPL iterators and `ArrayObject`, Reflection, `XMLReader`, `SimpleXMLElement`, `PDO`.
- **`mysqli` is missing from the census**, because the oracle container doesn't load it: `wpdb` calls 23 `mysqli_*` functions at 30 sites and uses the `mysqli` and `mysqli_result` classes. Truffle PHP has none of them.
- `exit` and `clone` are language constructs that PHP 8.4/8.5 also exposes as functions; php-ast exports them as calls, and the translator handles neither.

### What it means for the first milestone (`wp-load.php` in CLI)
The boot path needs, at least:
- superglobals;
- `exit`;
- namespaces, if the boot loads Requests, as `class-wp-http.php` does;
- `compact`/`extract`;
- the date functions;
- `serialize`/`unserialize`;
- file functions for the config and the drop-ins;
- a database.

The probe can't say which of the 355 missing functions the boot actually reaches. Running the boot will show that.

## Milestone 1 (2026-10-23): `wp-load.php` boots in CLI

`tests/gate2/boot.sh` boots WordPress 7.2-beta1 on Truffle PHP against MariaDB (`tests/gate2/db.sh`, installed once by `tests/gate2/install.sh` with wp-cli on regular PHP). It then runs `tests/gate2/query.php` on both engines and diffs the output:
- options (`get_option`);
- raw `wpdb` reads (`get_var`, `get_col`, `get_row`);
- `WP_Query`;
- `get_categories`.

The two outputs are identical. The reference is the `wordpress:cli-php8.4` image, because the PHP 8.5 oracle image has no mysqli.

What the boot needed, in the order the boot loop hit it:
1. superglobals, `$GLOBALS`, autoloading, namespaces, `exit`, `clone`, `compact`, the date and encoding built-ins (commit `62e3403`);
2. **mysqli on JDBC** (`builtins/MysqliFunctions.java`, MariaDB Connector/J):
   - the procedural API `wpdb` calls;
   - results read eagerly as mysqlnd returns them without `MYSQLI_OPT_INT_AND_FLOAT_NATIVE`: every value is the column's bytes or null;
   - the connection lives in the object's `internal` slot;
3. file and stream functions with a `resource` type (`builtins/FileFunctions.java`), plus output-buffer levels, math, missing array and string functions, and the standard constants;
4. anonymous classes, declared once per context when first evaluated;
5. `array(&$this)`, which becomes a by-value element;
6. **traits**:
   - a trait's members are re-translated from its AST for each class that uses it, so `self`, `parent` and `__CLASS__` are that class's;
   - the translation is cached in the using class's template, shared by all contexts;
   - `insteadof` and `as` aliases are supported;
7. `compact()` with names computed at run time: frame slots looked up by name, behind a boundary.

The next milestones are the installer and the home page over HTTP. The census above still lists the gaps the boot path doesn't reach: `goto`, generators, `extract`, DOM, Reflection, and others.

## php-src `.phpt` score: strict and lenient (2026-10-23)

When both engines die, `tests/phpt/compare.py` used to count the case as passed whatever the reason. A case could then "pass" because Truffle PHP died on a missing class while PHP died on the error the test checks. Now:
- the oracle (`tests/phpt/oracle.sh`) logs PHP's errors per case to `expected/<case>.err`;
- the **strict** score also needs the same fatal message. The comparison ignores the "Uncaught" prefix, the exception class, file paths and line numbers;
- the **lenient** score is the old one;
- the cases that pass only leniently are `fatal-reason`, listed with both messages in `target/phpt/fatal-reasons.txt`.

At `7cbeca2`, of 7341 cases:

| Score | Passing |
|---|---:|
| Strict | 2431 (33.1%) |
| Lenient | 2636 (35.9%) |

Of the 205 cases that pass only leniently, 101 died on a missing class, such as `DateTime`, `ArrayObject` or `ReflectionClass`. Most of the rest are message details, for example `on NULL` vs `on null`, `integer given` vs `int given`, and a missing "previously declared in". The strict score is the one to report.
