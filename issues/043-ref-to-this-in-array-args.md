# 043: `array(&$this)` loses its reference when passed on to a by-reference callback

**State:** Open. Found 2026-11-08 by the WordPress PHPUnit suite. 14 tests warn `Argument #1 ($query) must be passed
by reference, value given`: `Tests_Query_IsTerm` (8), and `Tests_Query_Conditionals`, `Tests_Query_VerbosePageRules`
and `Tests_Query` (2 each).

**Symptom:** `WP_Query::get_posts` runs `do_action_ref_array( 'pre_get_posts', array( &$this ) )`, and
`WP_Hook::apply_filters` passes the array on to `call_user_func_array`. A callback that takes `&$query` gets a value:
```php
class Q { public $v = 0; function run() { do_ref(array(&$this)); return $this->v; } }
function do_ref($args) { call_user_func_array(function (&$q) { $q->v = 7; }, $args); }
var_dump((new Q)->run());
// PHP 8.5: int(7)
// Truffle PHP: Warning: {closure}(): Argument #1 ($q) must be passed by reference, value given; int(7)
```
`[&$v]` with an ordinary variable works, so the difference is `&$this` in an array literal.

**Cause:** not diagnosed. Likely, the `&$this` element of an array literal is stored as a value, because `$this`
isn't a variable slot a reference can bind to. In php-src, `ZEND_ADD_ARRAY_ELEMENT` with a reference wraps `$this`
in a reference.

**Fix:** to do. A reference to `$this` must make a reference, as one to a local does, in an array literal and in a
by-reference argument.
