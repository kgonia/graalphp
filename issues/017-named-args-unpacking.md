# 017: String keys in argument unpacking aren't taken as named arguments

**State:** Fixed (option 1, the owner's choice of 2026-11-04; php-src's rules and the design in [wiki/named-arguments.md](../wiki/named-arguments.md))

**Symptom:** an unpacked array's or Traversable's string keys are php-src's named arguments (PHP 8.1):
```php
function k($a, $b) { echo "$a $b\n"; }
k(...['b' => 2, 'a' => 1]);           // PHP: "1 2"; Truffle PHP: "2 1"
function h(...$args) { var_dump($args); }
h(...['a' => 1, 'b' => 2]);           // PHP: ['a' => 1, 'b' => 2]; Truffle PHP: [1, 2]
```

**Cause:** Truffle PHP has no named arguments at all: `f(a: 1)` isn't translated by either interpreter (php-src's
tests with named arguments stay on the tree and fail there). Unpacking passes the values by position
(`ArgumentsNode.addSpread`, whose `ponytail:` comment points here).

**Options** (the owner decides):
1. **Named arguments in full**, then unpacking takes them: binding by parameter name in the callee's frame
   (`zend_handle_named_arg`: "Unknown named parameter $x", "Named parameter $x overwrites previous argument",
   "Cannot use positional argument after named argument during unpacking"), skipped parameters filled from their
   defaults (`zend_handle_undef_args`, which needs a marker for "not passed" in the frame arguments that every
   function prologue understands), extra string keys collected by a variadic parameter; for built-ins, their
   parameter names (the arity table of issue 008 can carry them from Reflection). The largest piece; real code uses
   named arguments more and more (WooCommerce's newer code).
2. **Only what unpacking needs**, for user functions: string keys mapped to parameter names, a variadic collecting
   the rest, php-src's Errors for the cases above; built-ins keep taking positions. Smaller, but half of a feature
   whose other half (`f(a: 1)`) would then be written again.
3. **An Error** ("Unsupported named arguments") where a string key meets unpacking, instead of a wrong call: honest
   and cheap, no semantics.

**Fix:** named arguments in full (branch `named-args`). An unpacked array's string key, and a Traversable's that
isn't numeric, is bound by name against the callee resolved before the arguments (`SpreadArguments`, php-src's
zend_handle_named_arg): the parameters skipped are filled by the callee's defaults target before the call, a name
the callee doesn't have goes to its variadic parameter under that name (or is "Unknown named parameter $x"), a slot
set twice is "Named parameter $x overwrites previous argument", and a position after a name in the same unpacking is
"Cannot use positional argument after named argument during unpacking". `call_user_func_array()` and the calls
like it bind the same way with ZEND_SEND_ARRAY's message and by-reference warning. Tests:
`tests/smoke/named-args-unpack.php`, `named-args-variadic.php`, `named-args-callables.php`. A built-in binds no name
until its parameter table is loaded (the named arguments' part 3): a string key unpacked into one is an Unsupported
Error meanwhile.
