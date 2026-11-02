# 005: Destructors never run

**State:** Open

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
