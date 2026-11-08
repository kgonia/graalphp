# 005: Destructors never run

**State:** Open. Decided 2026-11-03: exact php-src semantics, option A ([D17](../wiki/decisions.md)); being built (status in [destructors.md](../wiki/destructors.md), gaps in issue 036)

**Symptom:** `__destruct` is never called: not when the last reference goes, not at the end of the script. A generator destroyed while suspended inside `try` doesn't run its `finally` blocks either:
```php
function gen() { try { yield; } finally { echo "finally\n"; } }
gen()->current();   // PHP: finally; Truffle PHP: nothing
```
php-src tests that fail on it alone (2026-10-30): `Zend/tests/try/try_finally_012`, `016`, `017`, `019`, `020`, `022`, `023`, `catch_novar_2`; `Zend/tests/generators/bug69740`, `bug71601`, `bug71604`, `bug71980`, `bug74606`, `finally/finally_ran_on_close`, `finally/run_on_dtor`.

**Cause:** the runtime has no notion of an object's last reference (values are Java objects under the JVM's GC) and no `__destruct` call anywhere.

**Fix:** to do. php-src destroys an object when its refcount reaches zero, in a defined order, and the rest at shutdown (`zend_objects_store_call_destructors`), and a generator's destructor runs its pending `finally` blocks (`zend_generator_dtor_storage`). Matching that order needs reference counting of objects, or at least of the ones whose class has a destructor.

**2026-11-02 (S3, `bytecode-generators`):** a generator on bytecode that is still suspended when the request ends
cleanly (no fatal error) is destroyed then, in the order the generators were made, and runs its `finally` blocks
as `zend_generator_dtor_storage` does (`PhpContext.destroyGenerators`, `PhpGenerator.destroy`). That is php-src's
behaviour for generators alive at shutdown. Destruction when the last reference goes still needs object reference
counts: `finally/finally_ran_on_close`, `yield_from_force_closed` and the `gh9750` tests destroy a generator
before the script ends. For the same reason a reference a `function &gen()` yielded is not counted as held by the
generator: php-src releases it when the generator is destroyed, which here would be never.

## Design (2026-11-03, branch `fix-lifetime-generators`)

### What php-src does
Measured on PHP 8.5 (`class A { function __destruct() { echo "~$n "; } }`):
```php
$x = new A('x'); unset($x);            // ~x at once
function f() { $l = new A('local'); }  // ~local when f returns
new A('temp');                         // ~temp at the end of the statement (the temporary is freed)
$a = new A('a'); $b = new A('b'); $c = new A('c', new A('c.child')); $alias = $a;
$arr = [new A('arr0'), new A('arr1')];
$cyc = new A('cyc'); $cyc->child = $cyc; unset($cyc);
// end of script: [shutdown functions] ~c ~c.child ~b ~a ~arr0 ~arr1 ~cyc
```
1. **Refcount zero** (`zend_objects_store_del`): an object whose last zval goes (unset, overwrite, scope exit,
   a freed temporary, a freed container: array, object, closure, generator frame) runs `__destruct` at once, then
   releases its properties, which can cascade.
2. **Cycles** (`zend_gc.c`): `gc_collect_cycles()`, or automatically when the root buffer reaches its threshold
   (10,000 possible roots, adaptive), finds garbage cycles, runs their destructors, then frees them if no
   destructor resurrected them.
3. **Shutdown** (`php_request_shutdown`: shutdown functions first, then `shutdown_destructors`): (a) the global
   symbol table in *reverse* order, repeatedly, freeing each global that holds an object with refcount 1 (the
   cascade of 1 runs: `~c ~c.child`, then `~b`; `$a` has two holders and stays); (b)
   `zend_objects_store_call_destructors`: every object left, in handle order (creation order, handles reused).
   After a fatal error or `exit` in a destructor the rest are only marked destroyed.

### What exact semantics need
Every observable point above depends on the refcount: when (1) happens, which globals (3a) frees and what its
cascade reaches, and when (2) triggers. Truffle PHP counts only arrays' holders and references' bindings (for
copy-on-write, `wiki/tla-cow.md`); objects are not counted, and nested arrays don't release their elements when
the container dies (tla-cow, line 81).

### Options
| | What | Exact? | Code | Per-op cost |
|---|---|---|---|---|
| A | Refcount every `PhpObject` as arrays' holders are counted: in/out on every store, overwrite, unset, scope exit, temporary, argument, return value, property, static, closure use, generator frame, exception trace argument, and in every built-in that stores a value (ArrayObject, SplObjectStorage, WeakMap, Closure::bind, `func_get_args`, `debug_backtrace`...). At zero: `__destruct`, then release the properties (cascade). Nested arrays release their elements at their last holder. Plus a cycle collector with php-src's root buffer and threshold, and the shutdown order of 3a/3b. | Yes, when complete | Large: every write path on bytecode and in the runtime, the built-ins' stores, a cycle collector; weeks, with a long tail like the array holders had (TLA+ model, `refcopy.php`) | Arrays' holder counting costs 2-5% per Gate 1 kernel (`wiki/gate-1.md`); objects are as common in WordPress (hooks, posts, WC_Data), so likely another 2-5%, plus a release walk of object locals at every return |
| B | Count only instances of classes with `__destruct` (and generators with `finally`) | No: a destructible object inside an uncounted array or object can't be released when its container dies; the containers must be counted too, which is A | Medium | A class-flag check per object store |
| C | Escape analysis: destroy at scope exit when the object provably doesn't escape | Only for trivial cases: `$this` escapes into the constructor and every method call, so it needs whole-program analysis | Large, for few cases | None |
| D | End of request only: every object with a destructor in creation order (3b) | No: 3a frees globals first, in reverse order with cascades (`~c ~c.child ~b ~a` above, D gives `~a ~b ~c ~c.child`), and objects php-src destroys earlier (1) would be destroyed at the end. A reachability walk at shutdown could rebuild refcounts for 3a, still wrong for (1) and for cycles pointing into live data | Small (D), medium (with the walk) | None |
| E | The JVM's GC (Cleaner, PhantomReference) | No: nondeterministic, another thread, and `$this` is already unreachable | Small | None |

No subset is php-src-exact without object refcounts: even end-of-request destruction (D) has the wrong order for
the most common case, objects in global variables. So nothing is implemented here; the generator part (S3, above)
stays as it is.

### What WordPress and WooCommerce rely on
`__destruct` in WordPress 6.9 and WooCommerce 11.2.1 (15 files):
- `WP_HTML_Token` (`html-api/class-wp-html-token.php`): calls `on_destroy`, with which `WP_HTML_Processor`
  releases the bookmark of each token it pops. Without it the processor keeps every bookmark: at
  `MAX_BOOKMARKS` (10,000 tokens) `next_token()` returns false with `ERROR_EXCEEDED_MAX_BOOKMARKS`, where PHP
  goes on, and each lexical update walks all bookmarks. Used by block bindings (`class-wp-block.php`'s internal
  processor) and any plugin using `WP_HTML_Processor`; not on Gate 2's pages. **The one behaviour difference a
  site can hit.**
- `WP_Secret` (`secrets/class-wp-secret.php`): zeroes and unsets its vault entry. Without it secrets stay in
  memory until the request ends (a hardening loss, not an output difference).
- `PHPMailer`: closes the SMTP connection with QUIT. Without it the socket closes at the request's end without
  QUIT.
- `WC_Log_Handler_File`, WooCommerce's `FileV2\File`, Nyholm `Stream`: `fclose`; `Requests` Curl:
  `curl_close` (no effect since PHP 8); the FTP filesystems: `ftp_close`/`quit`; the image editors (GD: nothing
  since PHP 8; Imagick: `clear()`); SimplePie: breaks cycles only when `gc_enabled()` is false; sodium_compat's
  `Poly1305\State`: zeroes its keys. Resources are released at the end of the request anyway.

### Recommendation
Option A, as its own project after the correctness packages, because the HTML API's bookmarks and php-src's
test suite (the try/finally and generator tests above, most `__destruct` phpt) need exact timing, and B-E would
each be a documented deviation. Measure its cost on Gate 1 and Gate 2 first with a prototype that counts objects
on the bytecode store paths only (no destructor calls), as the array holders were measured. If the cost is too
high, the alternative is to keep destructors unsupported (state today) and list it; no partial option is exact.

**2026-11-06:** option A is being prototyped on the `destructors-merged` branch: object counts, destructors at
zero behind `-Dphp.rc.destructors=true`, and a census check (`-Dphp.rc.verify=true`). With destructors on, the
census checked 6,356 zeros on Gate 2's WordPress and found no object still reachable, so none of the checked
zeros came too early. It can't see leaks (counts too high, destructors late or never), so this doesn't show
that destructors match PHP. Its known gaps (closures,
temporary arrays, remaining fuzz groups, no cycle collector or shutdown order yet) are in issue 036.

### Decision ([D17](../wiki/decisions.md), 2026-11-03; restated by the owner 2026-11-06)
Object lifetimes must match php-src: option A, complete. Options B-E are out. This is for correct execution, not
for test coverage: frameworks and plugins depend on when a destructor runs, so a late or missing one is a bug
like any other. Examples:
- releasing a lock or a transaction when its guard object goes out of scope;
- flushing a buffered log handler;
- stopping a child process;
- removing temporary files;
- `WP_HTML_Token`'s bookmarks.

In long-running workers (queue consumers, application servers) the end of the script never comes, so "at the
end" means never.

On 2026-11-06 the owner made explicit that the measured cost decides how the work is done (P1, then P2), not
whether it is done.
