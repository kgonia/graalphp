# 019: extract() with EXTR_REFS binds values

**State:** Fixed

**Symptom:** `extract($data, EXTR_REFS)` assigned the values: a later `$p = 10` didn't change `$data['p']`.

**Cause:** `ExtractNode` masked the flag off (`& 0xff`) and took the array by value. php-src's extract() takes its
array by reference when it can (ZEND_SEND_PREFER_REF), separates it with EXTR_REFS and binds each variable to its
entry, made a reference in the array (`php_extract_ref_*`).

**Fix:** the translators pass a place (a variable, element or property) by reference unless the flags are a constant
without EXTR_REFS (`Translator.extractTakesRef`, on both interpreters); with EXTR_REFS, `ExtractNode.extract`
separates the variable's array (a temporary's: a copy) and binds each selected variable to its entry, made a
reference (`refAtPosition`). Test: `tests/smoke/extract-refs.php` (in a function, at the top level, an element, with
skip and prefixes, flags in a variable, a temporary array).
