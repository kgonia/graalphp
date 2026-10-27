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

## Milestone: the home page over HTTP (2026-10-23)

`tests/gate2/http.sh` serves WordPress twice and fetches the same pages from both (the site is still installed by wp-cli on regular PHP, so the installer milestone stays open):
- on Truffle PHP through `launcher.HttpMain`: the JDK HTTP server, a new context per request on one shared engine, the language under its CGI SAPI (`php.Sapi=cgi`: the request in environment variables and stdin, the header block written before the first output byte);
- on regular PHP with mysqli (`php -S` in the `wordpress:cli-php8.4` image).

Nonces, dates and asset versions are masked. 7 of the 9 pages are byte-identical with the same status: the home page, a category archive, the RSS feed, `wp-login.php`, the REST index (137 routes), a search, and a 404. The single post and the page each differ by one line: the oEmbed XML discovery link, which WordPress prints only when `SimpleXMLElement` exists.

What the pages needed after the CLI boot:
- typed `executeBoolean` beside the truthiness `executeCondition` (`false === 0` was true, so the HTML tag processor saw no tags), and global-variable caching only under the single-context assumption (`e3fb16b`);
- the CGI SAPI, `HttpMain`, the SPL classes in the prelude, `trigger_error` that is fatal only for `E_USER_ERROR` (`d177c66`);
- references in array copies (`4d510ab`): a reference left in an array element by `foreach ($a as &$v)` stayed shared by every copy, so `WP_REST_Server::get_routes()` emptied `$this->endpoints` and the REST index listed 7 routes. `PhpRef` now approximates php-src's refcount (the variables bound to it, and the array elements made references), and a copy turns a reference only its element holds into a value;
- magic properties (`aa2613d`), generators, `goto`, `extract()`, `instanceof` with a dynamic class (`e8a8da1`), the date classes (`7ef2b14`), output-buffer handlers (`acf4e60`).

The installer and the wp-admin login are next; the order of the rest of Gate 2 (the AST export kept for now, the own parser as a separate track, a FastCGI SAPI replacing php-fpm) is [D11](decisions.md). Known gaps on the way: `SimpleXMLElement`, `__call` and `__callStatic` on direct method calls (callables have `__call`), returning by reference, ticks.

At `acf4e60` the php-src score is 2622 of 7341 strict (35.7%) and 2821 lenient (38.4%).

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

## Code review and its fixes (2026-10-23)

Three reviewers read the code at `925485a`: Codex, Opus and Fable. They checked idiomatic Truffle, security and simplicity; their reports are `target/review/{codex,opus,fable}.md`. Together they listed 71 findings, many of them the same defect seen three times. All but three were fixed (the three are below), under the rule that a fix is php-src's behaviour, not a documented deviation: 51 commits from `6429986` to `19fb78c`. Smoke and the Gate 1 and K0 checksums passed after every commit; context isolation, the boot, mysqli and the HTTP comparison after every commit of the main line and every merged branch.

| Area | What changed |
|---|---|
| PCRE | Compiled patterns are shared by the engine (bounded); the JIT stack, match data and buffers are per context and freed when it closes. A pattern that doesn't compile makes the call fail as in php-src instead of killing the request. |
| Untrusted input | `unserialize` honours `allowed_classes`, checks lengths before allocating, and reads nesting on an explicit stack (php-src's 4096 levels); `random_int` is uniform; HttpMain limits bodies (8 MB) and request time, resolves symlinks and case before serving, drops `Proxy:`, listens on loopback, and shows fatal errors only with `--display-errors` (`php_error_cb`'s 500 otherwise); `setcookie` checks its parts as php-src. |
| mysqli | Raw `COM_QUERY` bytes, escaping by the connection's charset and `NO_BACKSLASH_ESCAPES` (from `mysqlnd_charset.c`), `mysqli_report`, no `LOAD DATA LOCAL`. |
| I/O | Every file function goes through the context's `Env` (`TruffleFile`), with php-src's stream read buffer, real `flock`, atomic `x` mode, streams closed with the context; launchers grant only the access PHP needs. |
| Truffle idioms | Splitting enabled (18 splits on the kernels, 0 before); inline caches for `instanceof`, `self::`/`parent::`, globals, static properties, string `==`/`switch`, `.=`; caches keep functions, not the first request's objects; `ClassKey` includes traits; `@ExplodeLoop` where child loops were missed; no mutable static state; translations outside the cache lock. |
| PHP semantics | `finally` doesn't run while `exit()` unwinds; callables resolve as `zend_is_callable` (for built-ins) or as the dynamic call (`$f()`), with php-src's errors; `__call`/`__callStatic` on direct calls; first-class callable syntax (it used to *call* the method); runtime errors carry their PHP class (`JsonException` is catchable); names fold as ASCII (`zend_str_tolower`); property names keep their bytes; references in arrays count toward the refcount. |
| Built-ins | var_dump/print_r/var_export as bytes, ports of `php_formatted_print`, `zend_compare`, html.c, `php_url_parse_ex2`, `date_format`, base64, `version_compare`'s special forms; `get_declared_classes` and friends. |
| Simplicity | One UTF-8 validator, `HexFormat`, `PhpArray.pop`, one string-list helper, debug built-ins only with `php.Debug`, dead code removed. |

Gate 1 kernels, `925485a` against `19fb78c` (interleaved, 6 rounds, CPUs 14–21; median of per-round ratios, <1 is faster): hooks 0.97, escape 0.84, slugs 0.87, wpautop 0.84, wptexturize 0.81, blocks 1.01 (0.96–1.09), kses 0.98 (0.86–1.15). On the way kses was 13% slower: checking a built-in's callback on entry (as php-src does) resolved string callbacks by name on every `preg_replace_callback`, mostly calls without a match. Built-ins now take the resolved target once (`8bdbe45`, php-src's fci_cache), and resolved function names are kept per context (`19fb78c`), which brought it back. An earlier 1.10 on blocks didn't reproduce in a bisection or a direct re-run; the compiled code was the same (same deopts and inlining).

Left as they are, deliberately: `PhpFunction.isPublic` (needed once method visibility is enforced), the sorted `EXTENSIONS` set (`Set.of` has no order), and `PhpContext`'s name maps beside its id slots (they keep the declaration order the listings need).

php-src `.phpt` at `078e824`: 2868 of 7341 strict (39.1%) and 3048 lenient, from 2622 (35.7%) and 2821 before: 247 cases fixed, one broken (a closure of an abstract method, fixed in `312e1cb` and checked by hand).

Found on the way, still open:
- named arguments are unsupported;
- php-src binds an anonymous class at compile time;
- var_dump doesn't show `:protected`/`:private` or a closure's name, file and parameters;
- object ids aren't reused after an object is freed;
- `match` and enums are unsupported in the translator.

## Errors, exceptions and traces (2026-10-24)

WordPress's fatal error handler (`WP_Fatal_Error_Handler`) is a shutdown function that reads `error_get_last()` and renders the "There has been a critical error on this website." page with a 500. Neither worked before: an uncaught error ended the context, no shutdown function ran, and `error_get_last()` returned null. The error model now follows php-src. `tests/gate2/http.sh` fetches `?fatal=exception` and `?fatal=error` (a must-use plugin fails during `init`), and both pages are byte-identical with PHP's.

| Commit | What |
|---|---|
| `1d2a783` | A Throwable's file and line are where it was made (zend_default_exception_new), whatever its constructor does. A runtime error records the innermost statement it left; every statement list is a block for that. `Exception::__toString` follows zend_exceptions.c. |
| `8f4a978` | Stack traces as zend_fetch_debug_backtrace and zend_trace_to_string build them: `getTrace`, `getTraceAsString`, `debug_backtrace`, `debug_print_backtrace`. Arguments are as the parameter variables are now. `[internal function]` is shown for calls the runtime makes, and include frames are listed. Calls php-src compiles into opcodes (strlen, count, call_user_func...) have no entry. The prelude counts as internal code. Closures are named as in PHP 8.4. |
| `afc0ade` | zend_error and php_error_cb: the user error handler, `error_get_last`, display (stdout, stderr, HTML), the log, `error_reporting` and `@`. Fatal errors set exit status 255, a 500 when not displayed, and end the script without catch or finally; shutdown functions and the output still run. `trigger_error(E_USER_ERROR)` is fatal. Uncaught exceptions go to `set_exception_handler`. The error ini directives and the `php.Ini` option (`PhpMain -d`). |
| `2081cc5` | The fatal error pages in the HTTP comparison. |
| `fe95202`, `c95d043` | An output handler's error at the end is reported. Redeclaring a function or class is php-src's fatal error (E_COMPILE_ERROR or E_ERROR, "previously declared in"). |

Probes on PHP 8.5 and Truffle PHP give the same stdout, stderr and exit status for: uncaught exceptions, `E_USER_ERROR` with its backtrace, an exception handler (plain and throwing), shutdown functions that throw or call exit, the ini directives, HTML and stderr display, the log, and `ignore_repeated_errors`. Smoke tests: `exception-location`, `traces`, `errors`, `redeclare-*`.

php-src `.phpt` at `2081cc5`: 2920 of 7341 strict (39.8%) and 3092 lenient, from 2868 and 3048. Against the 2622 baseline, 5 cases newly fail. Two are nondeterministic (`random_int`, today's date). One is a timing test (`preg_match` 64k times in under 0.5 s), which fails on the build before the series too when the machine is loaded. The other two passed by accident: both engines died, ours on the then-missing `set_exception_handler`. They need the `#[\Deprecated]` attribute and "Undefined array key" warnings.

Gate 1 kernels, `20a3134` → `c95d043` → `20a3134` (interleaved, 10 rounds, CPUs 14–21; median of per-round ratios, there and back). All kernels: hooks 0.94/1.20, escape 1.05/0.97, slugs 0.97/1.05, wpautop 1.14/0.88, wptexturize 1.06/0.91, blocks 0.88/0.99, kses 1.00/0.97. The machine was loaded (per-round ratios 0.4–2.4), so wpautop and wptexturize got a second run of their own with 80 iterations: wpautop 1.02/0.96, wptexturize 1.06/0.85. wptexturize is bimodal on both builds: a run settles at about 4.4 ms or at 11–16 ms, with 3 or 4 fast runs in 10 for each build. That decides its median, so it is no regression; why compilation lands in two states is open. A `preg_match` loop inside a function is faster than before (0.42 against 0.52 s steady).

Still open after it:
- built-ins don't check their arguments' types as zend_parse_parameters does: `strlen([])` returns instead of throwing a TypeError;
- the ini directives beyond the error ones (`memory_limit`, `precision`...) are unknown to `ini_get`;
- `STDIN`, `STDOUT` and `STDERR` are undefined;
- `__toString` called by a conversion (echo, concatenation) shows `[internal function]` in a trace instead of the line;
- an invalid callback given to the compiled-in `array_map`/`array_filter` has no `array_map` entry in its trace;
- PHP's warnings and notices (undefined variable, array key...) aren't raised yet: the error model is ready for them.

## Idiomaticity review of the error series (2026-10-24)

Three reviewers read `20a3134..16f4079`: the nodes for Truffle idioms, the runtime classes, and the built-ins, launchers and tests. What they found and what was done:

| Commit | What |
|---|---|
| `3ac2512` | A megamorphic `new` computed a Throwable's location and trace (a full stack walk) for every class: arguments were evaluated before `instantiate()` looked at the class. |
| `846d838` | Every root captured frames for every exception that left it, for a trace only `PhpError` read. `PhpError` takes its trace when raised, as zend_throw_error's object does; no exception keeps a Truffle stack trace and no root overrides `isCaptureFramesForTrace`. |
| `6aec54d` | The language never declared `@ProvidedTags`, so no tool could attach to statements; with one attached, a runtime error took an outer statement's line (the wrapper's line is 0). Statement lists' catch is behind a `BranchProfile`. The exception-location smoke test also runs under the CPU tracer. |
| `e084cba`, `35d94c3`, `5da6a2d` | The intrinsics' second list of functions php-src compiles in had drifted (an unqualified call in a namespace, `key_exists`): the translator's answer reaches the node. A built-in php-src calls with a frame gets one: the intrinsic's fallback is a `DirectCallNode`, so traces list it and its callbacks without the `raisedIn` stand-in. A trace through array_map's fallback no longer reads the slot only its calls back fill. |
| `5a993f9` | `Ini` as php-src keeps it: OnUpdate handlers parse into typed settings (no string parsing per reported error), one `error_reporting`, owned by the context's `BuiltinState`; `ini_get_all`; a rejected startup value keeps the default. |
| `f35d7db` | The prelude is internal by `RootNode.isInternal()`, not by a path prefix tested on every frame. |
| `531966a` | `php.Ini` is a map option (`php.Ini.display_errors=0`); `-d name=value`, `-dname=value` and `-d name` alike in both launchers; `--display-errors` goes. |
| `bedde28` | A sealed `PhpThrowable` over `PhpError` and `PhpException`; `SavedHandler` and `LastError` records; php_error_cb's formats through one `ByteBuilder`; `ErrorFunctions.fatal()` returns the bailout to throw (zend_error_noreturn); constants from their definitions. |
| `1f8fe99`, `7cb4f30` | Smaller idioms (iterateFrames' skip, named php-ast flags, switch expressions, `callMethod(Node, ...)` in foreach, `CallingBackNode`), and two test fixes. |

Left as they are, on purpose:
- the statement lists still catch `PhpError` to record the line it left: nodes and runtime helpers raise errors in 165 places, most without a node to pass, and php-src's equivalent is the current opline. Behind a profile, compiled code has no handler until an error passes;
- `declaredAt` stays a setter, like `PhpFunction`'s other optional properties (`setBuiltin`, `setClassName`);
- the smoke tests' path normalisation stays per test, and the reference runs keep php.ini's absence implicit: `php -n` would also drop the extensions the image loads through conf.d.

Checks after each commit: smoke (also 3 contexts, and exception-location under the tracer), Gate 1 and K0 checksums, 40 shared contexts, boot, mysqli; the HTTP comparison after the launcher and error-report commits (9 of 11 pages identical, the 2 oEmbed lines as before). Gate 1 kernels `16f4079` → `35d94c3` → `16f4079` (10 rounds): hooks 0.95/1.05, escape 1.18/1.03, slugs 1.03/0.99, wpautop 0.99/1.04, wptexturize 1.00/0.93, blocks 0.96/0.89, kses 1.06/1.00. kses was measured again on its own, 14 rounds: `6aec54d` 32.1 ms, `35d94c3` 31.4 ms (0.97); forcing the fallback's call to inline gave nothing more (1.01). The built-in fallback through the call target from a boundary (`e084cba`, before `35d94c3`) had looked 6–7% slower on kses.

php-src `.phpt` at `5da6a2d`: 2932 of 7341 strict (39.9%) and 3092 lenient, from 2920 and 3092 at `2081cc5`. 12 cases newly pass (redeclaration and name-collision messages, exception messages, line numbers), none newly fails; the first run found two (`bug70895`, `bug70898`), fixed by `5da6a2d`.

Still open, found on the way:
- an alias compiled in (`key_exists`, `join`, `chop`, `sizeof`) is listed and named in messages under the function it aliases;
- built-ins' TypeError messages say "integer given" where php-src says "int", and `array_key_exists`'s omits the type;
- a -d or php.ini value is not run through the ini parser: `error_reporting=E_ALL & ~E_NOTICE` is read as 0.

## Milestones: the installer and the wp-admin login (2026-10-26)

`tests/gate2/http.sh install` and `tests/gate2/http.sh login` (after `start`) drive both engines through the same requests. Each engine works in a database of its own (`wp_install_truffle`, `wp_install_php`), named by an `X-Gate2-Db` header that the test's `wp-config.php` honours only for those two names:
- **install:** each engine installs WordPress through `wp-admin/install.php` into an empty database: the welcome page, step 1, and the form post of step 2. The three pages are compared, then the two databases are dumped and compared, with times and the password hashes (random salts) masked.
- **login:** each engine logs in to the site it installed, with a cookie jar of its own. The redirect is compared, then seven wp-admin pages: the dashboard, posts, a new post (the block editor's bootstrap), general settings, users, plugins and the profile. A login to the wp-cli site checks a password hash that regular PHP made.

The comparison:
- masks nonces (a logged-in user's depend on the random session token), times, the post date's seconds and the post lock, and generated passwords;
- compares status codes, not reason phrases: the JDK server's phrase for 302 is "Temporary Redirect";
- runs with `WP_HTTP_BLOCK_EXTERNAL`, which keeps api.wordpress.org's answers out of it (the update checks, the PHP and browser version checks).

**Result at `2c018b0`, and at `3782f7e`:** the installer's three pages and the database (134 rows) are identical. The login redirect is identical, and so are the dashboard, posts, users and profile pages. Three pages still differ:
- a new post and general settings, in the upload settings only: PHP has GD, so `wp_image_editor_supports()` accepts WebP and AVIF, and Truffle PHP has no image editor;
- plugins: the filesystem credentials form offers FTP on PHP only. `get_filesystem_method()` finds `fsockopen`, and Truffle PHP has no sockets.

The 9 public pages and the 2 fatal error pages are as before.

What the installer and wp-admin needed, in the order they hit it:

| Commit | What |
|---|---|
| `55f0127` | `password_hash` and its family, and `crypt()` for bcrypt. `Bcrypt.java` is a port of crypt_blowfish.c with the `$2a$`/`$2b$`/`$2x$`/`$2y$` variants and the sign-extension safety. WordPress 6.8+ hashes with bcrypt; the default cost is 12 since PHP 8.4. |
| `e5e3587`, `5c59843` | `http_build_query` (http.c), with `arg_separator.output`. Core's ini directives WordPress reads (`memory_limit`, `upload_max_filesize`, `post_max_size`...) get php-src's defaults and access levels: `ini_get()` gave false, so `wp_max_upload_size()` was 0 B. |
| `5e4c6e0` | **An included file runs in the including scope** (zend_attach_symbol_table). It used to run in the global scope: `wp_get_wp_version()` (`static $wp_version; require version.php`) returned null, and template parts never saw `load_template()`'s `$args`. A call's variables are its frame slots, reached by name through the materialized frame (`IncludeScope`); names it has no slot for live in the call's table. `$this`, the static class and the class scope carry over, and `global`, `compact()` and `extract()` in the included file act on that scope. |
| `6758730` | By-reference parameters through unpacked arguments (ZEND_SEND_UNPACK) and by-reference variadics. `Walker::walk` passes `&$output` with `...array_values($args)`, so category lists were empty. |
| `d9d77c5` | **Property visibility.** It was never checked, so the magic methods of an inaccessible property never ran: `WP_Block_Type`'s private `$variations`, behind `__get`/`__isset`, was missing from the block editor. Protected properties, and the private ones of an object's own class, carry shape flags. Only those sites check the calling scope: a closure's bound scope, the scope a file was included into, else the lexical class. From a scope that may not access a property, it gets the magic methods, else php-src's Error. `get_object_vars()` called directly and `foreach` over an object see what the scope may access (`Requests\Iri::__get` relies on it). |

Gate 1 kernels (interleaved, 8 rounds, CPUs 14–21; median of per-round ratios):
- The series up to the spread commit, against `3c77dd2`: neutral. Hooks 1.02, escape 1.00, slugs 0.93, wpautop 0.96, wptexturize 1.00, blocks 0.99, kses 1.00.
- The visibility checks against the commit before them: hooks 1.04 (IQR 1.04–1.07) and kses 1.05, because `WP_Hook`'s private properties are read on every dispatch. A site in a method now takes its scope from its code, not the frame, and `$this->protected` needs no check. With that, hooks is 1.01 and kses 1.03 (1.01–1.04), escape 1.03, slugs 1.02, wpautop 0.97, blocks 1.00. wptexturize measured 1.06 (1.04–1.10). Its own run of 10 rounds, with kses and escape, at `d58df0b` against the series' start gave wptexturize 1.01 (1.00–1.03), kses 1.03 (1.00–1.04), escape 1.00, so the 1.06 was noise. kses's 3% is left to find.

php-src `.phpt` at `d58df0b`: 2953 of 7341 strict (40.2%), from 2932. The series fixed 23 cases. It broke 2, both fixed by `d58df0b`: an `isset()` through an inaccessible property, and `foreach` over an object changed in the loop.

Gaps found on the way, not fixed yet:
- argon2, and `crypt()` with MD5, SHA-256, SHA-512 and DES salts;
- GD (`webp_upload_error`/`avif_upload_error` on post-new.php and options-general.php);
- `get_object_vars` called as a callback, `get_class_vars`, `(array)` casts and `var_dump` don't mangle or annotate non-public properties by scope;
- a private property declared again by a subclass shares one slot with the parent's;
- static properties' visibility isn't checked.

## Arithmetic operands and the Closure class (2026-10-27)

| Commit | What |
|---|---|
| `414a217` | **Arithmetic operands as zend_operators.c takes them.** Before, an operand that wasn't a number was taken as 0 or 1. Now a non-numeric string, an array, an object or a resource is zend_binop_error's TypeError ("Unsupported operand types: string + int"). A numeric string with trailing data warns "A non-numeric value encountered" at the operation's line. `%`, the shifts and the bitwise operators deprecate the precision a float loses. Two strings combine byte by byte under `&`, `|`, `^` and `~`. `++`/`--` follow increment_function: a Perl-style string increment with its deprecation, the bool and null warnings, "Cannot increment array". `str_increment()` and `str_decrement()` were added. |
| `1583f52` | **The Closure class:** `bind`, `bindTo`, `call` and `fromCallable` with zend_valid_closure_binding's rules and warnings. Fake closures (from a function or method) keep their scope. Closures whose body uses `$this` can't be unbound from it. `new Closure` is an Error. |
| `da59284` | **Benchmarks report peak memory beside time.** `PEAK_MEMORY=1` makes `truffle.sh` and `php.sh` print the container's cgroup `memory.peak`, the whole JVM's or php's footprint. `compare.sh` prints it for both engines. `bench/gate1/ab.sh` is the A/B of builds that so far lived outside the repo; it adds the median ratio of peak memory. From now on every comparison reports both. |

`414a217` against `d58df0b` (8 rounds): time 0.97–1.01 on every kernel, peak memory 1.01 (IQR 0.97–1.07). The all-kernels run at 400 iterations peaks at about 1.58 GB on Truffle PHP. A first look at hooks alone, 20 iterations: 870 MB against PHP's 25 MB. The JVM has no heap limit set (its default maximum is a quarter of the machine's memory), and a quiet-window comparison with PHP will measure this properly.

`d58df0b` fixed the two `.phpt` cases the visibility series broke. The score is 2953 of 7341 strict.

## curl and client sockets (D13), 2026-10-27

| Commit | What |
|---|---|
| `e9eea8e` | **ext/curl over libcurl by FFM**, ported whole from 8.5.12 ([D13](decisions.md)). Its handles use a general mechanism for a built-in class's object handlers (get_constructor, clone_obj, cast_object, not serializable, not comparable). `Closure` now uses the same mechanism; its field is `@CompilationFinal`, so `new` sites fold the check. `serialize()` of something that can't be serialized throws Exception, as PHP does. |
| `90a6797` | **Client sockets and TLS on the JDK:** `fsockopen`, `stream_socket_client`, `stream_socket_enable_crypto`, stream contexts, `default_socket_timeout`. Contexts take resource ids as PHP's do. `http.sh sockets` runs a probe inside WordPress on both engines. The probe covers raw HTTP, timeouts, non-blocking reads, refused and unresolvable hosts, and Requests' fsockopen transport. |

Gate 2 after both:
- the public pages: 9 of 11 same, the two differences being the oEmbed XML link, which WordPress emits only when SimpleXML is there;
- the installer and its database: same;
- the login and wp-admin:
  - `plugins.php` is now the same too, because the FTP credentials form follows `fsockopen`;
  - post-new.php and options-general.php still differ by GD only.
- `http.sh sockets`: same.
- On the TLS checks against badssl.com and google.com, the warnings, OpenSSL error texts, protocols and ciphers are PHP's.

php-src `.phpt`: 3064 of 7341 strict (41.7%), from 2953: the Closure class, the arithmetic operands and the stream contexts. Three cases newly fail; each test's output depends on `random_int`/`rand` or `memory_get_usage`, so they vary between runs.

Gate 1 kernels, `da59284` (before the Closure class) against `90a6797`, 8 rounds, CPUs 14–21:

| | hooks | escape | slugs | wpautop | wptexturize | blocks | kses | peak memory |
|---|---|---|---|---|---|---|---|---|
| time ratio | 1.01 | 0.98 | 0.99 | 1.00 | 0.99 | 1.00 | 0.98 | 0.99 (IQR 0.98–1.00) |

The Closure commit alone had cost blocks and kses 2% (IQR 1.01–1.03), because a `new` site's guard read a field that wasn't `@CompilationFinal`; that cost is gone. Peak memory is 1.59 GB on both (all kernels, 400 iterations).

## Return by reference, 2026-10-27

| Commit | What |
|---|---|
| `ff344d6` | **Return by reference as php-src does it.** `function &f`, methods, closures and `fn&` return a reference: `return` of a variable, element, property or static property returns that place's reference (a missing element is created as null); `return` of a call passes on the reference its callee returned; anything else, and the implicit return at the closing brace, returns a new reference with "Only variable references should be returned by reference". `$x = &f()` (also `&$o->m()`, `&C::m()`, `&$f()`, into `$a[]` and `$o->p`) binds to it, or assigns the value with "Only variables should be assigned by reference". A call passed to a by-reference parameter passes the reference, or the value with "Only variables should be passed by reference". `foreach (f() as &$v)` changes the array the returned reference holds; over any other expression it iterates a temporary. `clone` now copies a property reference only it holds as its value (`zval_add_ref`). |

**Where it is decided:** at translation. A by-value function keeps the plain `ReturnNode`, and `FunctionRootNode.returnsRef` is `@CompilationFinal`. Call nodes are `CallNode`s: `executeRef()` is the callee's result as it came, `execute()` dereferences it, which the call nodes did before inside their specializations. Runtime callers (callbacks, magic methods) dereference too. A returned reference whose bindings all went (a local's, after the frame is released) holds no array until the caller binds it (`PhpRef.unbound` for a new one), so a value context leaves no extra holder behind.

WordPress uses it in `pomo/mo.php` (`$entry = &$this->make_entry(...)`), which failed to translate before, and in SimplePie and Text_Diff. graalphp's binary-trees by-reference benchmark (N=16, 60 iterations) prints what PHP prints.

php-src `.phpt`: 3096 of 7341 strict (42.2%), from 3066 at `590e74b`, the only newly failing case being the `random_int` one. Of the 325 cases about references and returns (names with "ref", or sources with `function &`, `fn&` or `= &` of a call) 118 pass, from 89. Smoke (74 tests, also in 3 contexts), Gate 1 and K0 checksums and the shared contexts pass. A/B against `590e74b` on CPUs 6–13 (the benchmark cores were in use), 4 rounds: within noise (0.91–1.12 per kernel, IQRs up to ±0.15, peak memory 0.97–1.00); `k0_fib`, whose 8-round ratio was 1.04 (IQR 1.01–1.13), compiles to the same code before and after (Tier 2: 28 AST nodes, 6 calls inlined, 5408 bytes).

Open, found on the way: [001](../issues/001-dynamic-call-by-ref-args.md), dynamic calls (`$f($x)`) don't pass arguments by reference; [002](../issues/002-by-ref-magic-get.md), writes through `&__get` and `&offsetGet`.

## A heap limit on a WordPress server, 2026-10-27

The video comparison's GraalVM server had no heap limit and held 2.1 GB of anonymous memory once warm, against PHP's 34 MB. Truffle PHP then started again with `-Xmx` (`JAVA_OPTS` for `tests/gate2/browse.sh`). Each run is one recorded visit (home page, a post, the login page, logging in, the posts list) right after start, and one after 200 paced visits, pinned to CPUs 6-13, on the same build (classes from 18:10, return by reference included). Memory is the container's anonymous memory, sampled every 100 ms.

| Heap | Cold visit: wait, CPU, peak | After 200 visits: peak | Visits 101-200: median wait (IQR) |
|---|---|---|---|
| `-Xmx256m` | fails: `Java heap space` on the second page | | |
| `-Xmx512m` | 6.2 s, 50.5 CPU-s, 1828 MB | 2614 MB | 2.55 s (2.44-2.72) |
| none | 9.8 s, 65.9 CPU-s, 1474 MB | 2443 MB | 2.57 s (2.52-2.63) |

- **A heap limit doesn't lower the footprint.** At 512 MB the heap is at most a fifth of the 2.6 GB. The rest is outside the heap, so `-Xmx` doesn't reach it. In Gate 1 that was mostly libgraal, the runtime compiler, with its own heap. A WordPress server compiles far more PHP code than a kernel does.
- **The steady state doesn't change.** The median waits are equal. Each cold visit is a single run, and single runs vary by 15-30%, so the cold difference isn't read as an effect.
- **Next: whose memory is it?** Is the ~2 GB outside the heap what a Truffle language on the JVM costs, or this implementation's fault? `tests/gate2/memory.sh libgraal -XX:-UseJVMCINativeLibrary` runs the same visits with Native Memory Tracking, once with libgraal and once with jargraal (Graal as Java code, its memory on the Java heap), and snapshots each server after start, after the cold visit and after 200 visits. The two runs take ~70 minutes, pinned to 6-13. A first run started on 2026-10-27 was cancelled before any warm snapshot, to be run on 2026-10-28.
  - **NMT** splits what the JVM allocates: heap, code cache, metaspace, threads, GC and Unsafe/FFM memory.
  - **Unnamed anonymous mappings that NMT misses** are libgraal's heap and native libraries' own `malloc`s.
  - **libgraal's share** is the difference between the two runs.
  - **Expected:** libgraal plus the code cache, both growing with the amount of compiled PHP.
  - **Our fault:** a growing NMT category (Unsafe/FFM from our bindings), or an untracked remainder that jargraal doesn't remove.
  - **Ruled out by reading the code:** unbounded PCRE2 memory. The compiled patterns are shared and capped at 4096 entries, as in php-src's cache, and a context frees its match blocks, JIT stack and unshared patterns when it closes.
