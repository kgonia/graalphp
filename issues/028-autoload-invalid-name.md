# 028: Autoloaders run for an empty or invalid class name

**State:** Fixed

**Symptom:** with the undefined-offset warnings (issue 022), the WooCommerce walk's server logged
`Warning: Uninitialized string offset 0 in wp-includes/sodium_compat/lib/namespaced.php on line 24`, which PHP's
doesn't: sodium_compat's autoloader reads `$class[0]`, and Truffle PHP called it with `""`.

**Cause:** `PhpContext.findClassAutoloading` ran the autoloaders for any name. php-src's `zend_lookup_class_ex`
returns before autoloading for an empty name, and for one with a character outside `0-9 a-z A-Z _ \ 0x80-0xff`
(`zend_is_valid_class_name`).

**Fix:** the same test before the autoloaders. Test: `tests/smoke/autoload-invalid-name.php`.
