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
