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
