# Named arguments (PHP 8.0+): php-src's rules and the design for Truffle PHP

**State (2026-11-04):** phase 2 done on branch `named-args` (PR #45). Part 1: the compile errors (1.1, checked
with the file's compile in `JumpChecks`), binding at the call site (`NamedSite`, `BindingPlan`, the named sends and
calls of 4.2 and 4.3) and the defaults target (4.4), for user functions, methods, constructors, closures and callable
values; constant-expression `new` (zend_ast_evaluate's order). Part 2: extras into a variadic and `__call` (4.5),
string keys in unpacking and names after it, `call_user_func_array`, `forward_static_call(_array)` and Reflection's
`invoke(Args)` and `newInstance(Args)` (4.7); issue 017 is fixed. Part 3: the built-ins (4.6) bind their arginfo
names, holes take their arginfo defaults, the forwarding built-ins pass names on and the other variadic ones refuse
them; zend_call_function's by-reference warning for call_user_func() and built-ins calling back; `__call` keeps
call_user_func_array()'s references; Closure::fromCallable() of a `__call` stand-in takes no names. Left as issues:
`ReflectionAttribute` (029), `assert()` compiled out (030, `named-args-assert` waits for it), `Fiber` (031,
`named-args-fiber`), `#[\SensitiveParameter]` (032), and a zpp ordering corner of the refusal (033).

The owner chose issue 017's option 1, full support exactly
as php-src. Under D18 the observable behaviour must equal PHP's; the internals need not mirror php-src. The design is
for the bytecode interpreter only (since `bytecode-only` the only one).

Before phase 2 Truffle PHP had no named arguments: the bytecode translator threw `Unsupported("argument")` on
`AST_NAMED_ARG`. `ArgumentsNode.addSpread` passed an unpacked array's string keys by position (issue 017).

Tests (they fail until phase 2), each recorded on PHP 8.5 by `tests/smoke/run.sh`:

| Test | What it covers |
|---|---|
| `named-args-basic` | binding, order, skipped defaults (constants, `new`, `self::`, `parent::`, `static`), `func_get_args`, reserved words, by-reference parameters, methods, constructors, closures, `__invoke`, first-class callables, constant-expression `new`, evaluation order |
| `named-args-errors` | every run-time error, and when it is thrown relative to the arguments' evaluation and to the callee's type checks; the traces of "not passed" and of a throwing default |
| `named-args-variadic` | extra names collected by a variadic, typed and by-reference variadics, backtraces, `__call` and `__callStatic` |
| `named-args-unpack` | string keys in `...$array` and `...Traversable`, named after unpacking, references, key rules |
| `named-args-callables` | `call_user_func(_array)`, `forward_static_call(_array)`, `Closure::__invoke`/`call`/`fromCallable`, Reflection's invoke and newInstance, `Fiber::start` |
| `named-args-builtins` | internal functions and methods: names, skipped arginfo defaults, the errors, by-reference parameters, `assert` |
| `named-args-compile-{positional,unpack,const,attribute}` | the four compile errors |

## 1. php-src's semantics

Sources: php-src 8.5.12. `Zend/zend_compile.c` (`zend_compile_args`, `zend_compile_const_expr_args`, the attributes),
`Zend/zend_execute.c` (`zend_get_arg_offset_by_name`, `zend_handle_named_arg`, `zend_handle_undef_args`),
`Zend/zend_vm_def.h` (the `SEND_*` handlers, `SEND_UNPACK`, `SEND_ARRAY`, `CHECK_UNDEF_ARGS`, `RECV*`),
`Zend/zend_execute_API.c` (`zend_call_function`'s `named_params`), `Zend/zend_API.c` (zpp),
`Zend/zend_closures.c`, `Zend/zend_attributes.c`, `Zend/zend_ast.c`, and the 33 tests in `Zend/tests/named_params/`.

### 1.1 Compile time

`zend_compile_args`:
- "Cannot use positional argument after named argument". This applies to calls, attribute arguments and
  constant-expression `new`.
- "Cannot use argument unpacking after named arguments". The other order, `f(...$a, x: 1)`, is allowed.
- "Cannot use positional argument after argument unpacking" (Truffle PHP already has this one).
- A name given twice in one call is **not** a compile error, but a run-time "overwrites" Error. In an attribute it
  **is** a compile error: "Duplicate named parameter $a".
- A name is any identifier, reserved words included (`f(array: [])`), and is compared case-sensitively. php-src
  interns it.
- When the callee is known at compile time and a name lands exactly where its position would, php-src sends it as a
  positional argument. This is an optimisation with no observable effect.
- A call that may leave holes gets `ZEND_CHECK_UNDEF_ARGS` before `DO_FCALL`. Holes are possible when there is a
  name not in order, or any unpack. `call_user_func_array` always gets it.

### 1.2 Binding, `zend_handle_named_arg`

Each named argument is bound when it is sent. The name is looked up in the callee's `arg_info`. It is
case-sensitive, and the lookup is cached per opline by `arg_info` identity.
- **Found at slot i:**
  - If i ≥ the current `num_args`, the frame grows to i+1. The slots in between become UNDEF and the call is
    flagged `MAY_HAVE_UNDEF`.
  - If slot i is already set, "Named parameter $x overwrites previous argument".
- **Not found, callee variadic:** the argument goes into `extra_named_params`, a hash by name. A second
  occurrence of the same name also overwrites.
- **Not found otherwise:** "Unknown named parameter $x". The Error is thrown before the call frame runs, so the
  trace has no callee frame.

`num_args` (what `func_num_args()` counts) is the highest bound positional slot plus one. Extra names never count.

**When the errors are thrown** (`named-args-errors` checks each case):
- An argument's own value is evaluated before its SEND binds it, so the error comes after that value's side effects
  and before any later argument is evaluated.
- Exception: a plain variable argument is read inside the SEND (`SEND_VAR(_EX)`, `SEND_REF`), after the binding.
  So `f(zz: $undefined)` throws "Unknown named parameter $zz" **without** the "Undefined variable" warning.
- An element or property argument (`f(zz: $a['k'])`) is fetched first (`CHECK_FUNC_ARG` with an unknown name
  falls back to by-value), so its "Undefined array key" warning comes **before** the Error.
- `new Cls(x: 1)` with no constructor gives "Unknown named parameter $x", for user classes and for `stdClass`
  alike.

**By reference:** whether an argument goes by reference is decided per bound slot (`ARG_SHOULD_BE_SENT_BY_REF` of the
slot found).
- A literal sent by name to a by-reference parameter: "f(): Argument #2 ($e) could not be passed by reference".
- A variable is made a reference.
- A by-reference variadic collects references under the names (`&...$refs`).

### 1.3 Holes, `zend_handle_undef_args` (`ZEND_CHECK_UNDEF_ARGS`)

After **all** arguments are sent and before the callee's frame starts, each UNDEF slot below `num_args` is filled in
parameter order:
- **A user function's optional parameter:** its default is evaluated. A literal is copied; a `CONSTANT_AST` is
  evaluated with the callee's scope (`self`, `parent`; `new` in an initializer). The run-time cache keeps the result
  when it isn't refcounted.
- **A required parameter:** `ArgumentCountError` "f(): Argument #2 ($b) not passed".
  - The first such parameter stops the loop.
- **An internal function:**
  - A required parameter gives "not passed".
  - A default parsed from the arginfo string (`zend_get_default_from_internal_arg_info`, a constant expression) is
    filled in, wrapped in a new reference for a by-reference parameter.
  - A parameter whose default is unknown gives "f(): Argument #2 ($filter_value) must be passed explicitly, because
    the default value is not known".
  - A `ZEND_ACC_USER_ARG_INFO` function (a trampoline) is left to deal with the holes itself.

All of this runs in a **fake frame of the callee** (`start_fake_frame`, its opline the parameter's `RECV`):
- An error's line is the parameter's line, in the callee's file.
- Its trace's frame #0 is the callee called from the call's line, with the holes shown as `NULL`:
  `#0 f.php(99): four('a', NULL, NULL, 'd')`. A later hole is still `NULL` when an earlier one throws.
- Consequences for the order, all three tested:
  - A hole's "not passed" error comes before a `TypeError` of an earlier argument:
    `typed(int $a, $b, $c = 0)` called as `typed(a: 'x', c: 1)` reports `$b`.
  - A throwing default comes before it too.
  - A default with side effects (`new Noisy()`) runs before the `TypeError`.
- Defaults of parameters **after** `num_args` aren't holes. `RECV_INIT` evaluates them in the callee as usual,
  interleaved with the parameters' type checks.
- A filled default is a passed argument from then on: `RECV_INIT` type-checks it, and `func_get_args()` lists it
  (`test(c: 'C', a: 'A')` gives 3 arguments, `['A', 'b', 'C']`).

### 1.4 Variadics and extra names

`RECV_VARIADIC`:
- It collects the positional extras, then appends `extra_named_params` under their names: `[0 => 'b', 1 => 'c',
  'd' => 'd']`.
- A typed variadic checks each extra named value. The message gives the argument number `num_args + 1` for every
  one of them, and no name: "collectInt(): Argument #2 must be of type int, string given".
- `func_get_args()` and `func_num_args()` leave the extras out.
- Backtraces list them after the positional arguments as `name: value`. `debug_backtrace()`'s `args` has them under
  string keys. `getTraceAsString` prints `traced(1, 2, x: 3, y: 4)`.
- `#[\SensitiveParameter]` on the variadic redacts them.
- Generators keep them in the generator's frame.

### 1.5 Unpacking with string keys, `ZEND_SEND_UNPACK`

**An array:**
- Elements are taken in iteration order. An int key is the next position, whatever its value: `[1 => 'A', 0 =>
  'B']` passes `'A'` then `'B'`.
- A string key is a name, bound as in 1.2.
- An int key after a string key in the **same** unpack: "Cannot use positional argument after named argument during
  unpacking".
  - Each `...` starts afresh: `abc(...['a', 'b' => 'b'], ...['c'])` passes `c` as position 3 (tested).
- An element landing on a by-reference parameter is made a reference in the array. A variable's shared array is
  separated first.

**A Traversable:**
- Its keys: an int is a position. A string is a name, except that a numeric string (`'0'`) is a position. Anything
  else: "Keys must be of type int|string during argument unpacking".
- A by-reference parameter gets the value, with "Warning: Cannot pass by-reference argument 2 of f() by unpacking a
  Traversable, passing by-value instead".

**Named arguments after unpacking** (`f(...[1, 2], d: 40)`) are bound at run time: "overwrites" if the unpack
already filled the slot.

### 1.6 Callables

- **`call_user_func_array($f, $args)`** (`ZEND_SEND_ARRAY`, or `zend_call_function`'s `named_params` through the
  built-in):
  - String keys are names, with the same errors.
  - The positional-after-named message has no "during unpacking": "Cannot use positional argument after named
    argument".
  - A value for a by-reference parameter is a **warning**, "f(): Argument #1 ($ref) must be passed by reference,
    value given", and the call goes on with a temporary reference.
  - A reference element stays one.
  - The same applies to `forward_static_call_array`, `ReflectionFunction::invokeArgs`,
    `ReflectionMethod::invokeArgs`, `ReflectionClass::newInstanceArgs` and `ReflectionAttribute::newInstance`.
- **Built-ins that forward their own extra names** (`Z_PARAM_VARIADIC_WITH_NAMED`), so that `call_user_func($f, 'A',
  c: 'C')` binds `c` in `$f`:
  - `call_user_func`, `Closure::__invoke`, `Closure::call`
  - `ReflectionFunction::invoke`, `ReflectionMethod::invoke`, `ReflectionClass::newInstance`
  - `Fiber::start`
- **Every other variadic built-in** (`array_merge`, `sprintf`, `forward_static_call`, `max`...) throws
  `ArgumentCountError` "array_merge() does not accept unknown named parameters".
  - It is thrown in its frame, `array_merge(Array, a: Array)`, after its argument count is checked:
    `max(x: 1)` gives "max() expects at least 1 argument, 0 given".
  - An `ArgumentCountError` with an empty name prints `array_multisort(: 1)`.
- **`__call` and `__callStatic`** (trampolines with one variadic `arg_info`): every name is an extra. They arrive in
  `$args` under string keys after the positional ones.
- **`__invoke`, closures, first-class callables** (`f(...)`, `$obj->m(...)`) and `Closure::fromCallable` bind
  against the function they stand for.
- **Constructors:**
  - `new`.
  - Constant-expression `new` (`zend_ast_evaluate`: arguments in a hash, "overwrites" for a duplicate, then
    `zend_call_known_function` with named params).
  - Attributes (`zend_get_attribute_object`: positional then a named hash, into `object_init_with_constructor`).
    Truffle PHP has no `ReflectionAttribute` yet, so only the attributes' compile errors apply for now.
- **`assert()`** is compiled specially:
  - The generated message keeps the names, "assert(assertion: false)".
  - The message is appended as a positional second argument, so `assert(description: 'D')` throws "Named parameter
    $description overwrites previous argument".

### 1.7 Built-ins

Internal functions bind by their `arg_info` names exactly as user functions do. Holes take the arginfo defaults
(1.3). The trace of a hole's error shows the built-in's frame: `array_keys(NULL, NULL, true)`.

An unknown name on a non-variadic built-in is the plain Error at the SEND, with no built-in frame. Built-ins that
php-src compiles into opcodes (`strlen`, `count`, `in_array`, `func_get_args`...) take names too:
`strlen(string: 'x')`.

## 2. GraalPy's design (oracle/graalpython master, 13f3504)

**Layout.** `PArguments` is an `Object[]`: 6 fixed slots, then the user slots in signature order, `[positional...]
[kw-only...][*args Object[]][**kwargs PKeyword[]]`.
- Keywords are bound **on the caller side**, into slots, before the call. The callee never sees a keyword list.
- Only the leftovers go into a `PKeyword[]` in the `**kwargs` slot. That slot exists only for callees that take one.

**Signature.** An immutable `Signature` holds the parameter and kw-only names (`@CompilationFinal(dimensions=1)
TruffleString[]`), the varargs index and the `**kwargs` flag. It lives on the root node and the code object.
- Defaults are values on the function object, evaluated at `def` time. They fold under `codeStableAssumption`.

**`CreateArgumentsNode`.**
- One `@Exclusive @Cached` instance per callee-identity cache entry of the call dispatch
  (`CallDispatchers.FunctionCachedCallNode`, limit 4), so binding happens **after** the identity check, with the
  signature as a PE constant.
- `ApplyKeywordsNode` is cached on (keyword count, signature identity) with `@ExplodeLoop`. Its
  `SearchNamedParameterNode` has three levels:
  - an identity-cached single name,
  - an exploded `TruffleString.EqualNode` loop,
  - a plain loop.
- The call site's keyword names are not part of the key, and are not constants: the Bytecode DSL compiler builds a
  dict for `f(a=1)` (`MakeDict`, then `MappingToKeywords`).
- The `Lazy*` wrappers create the keyword, default and error nodes on first use, so positional-only sites pay
  nothing.
- **"Not passed" is Java `null`** in the slot while binding. That detects "multiple values" for free.
- `PNone.NO_VALUE` is the default of optional builtin parameters, which the builtin's argument clinic maps to the
  real default.

**Defaults** are filled on the caller side (`FillDefaultsNode`, cached on the counts) from the evaluated defaults.

**Built-ins.**
- `@Builtin` declares the parameter names. `BuiltinFunctionRootNode` builds an ordinary `Signature` from them, so
  built-ins go through the same binding.
- Fixed-arity call ops (`CallUnaryMethod`...) skip binding when the signature is simple.

**What we take.**
- Binding on the caller side, into the flat frame-argument array the callee already reads.
- Binding after the callee-identity check, with a cache entry per callee, so everything about the callee is a
  constant.
- A sentinel for "not bound".
- Lazily made slow-path nodes.
- One parameter description shared by user functions and built-ins, the built-ins' generated.

**Where we do better.**
- PHP's names are literals, so a site's names are a constant `TruffleString[]` operand, not a dict.
- The cache key includes the site, so the binding is a precomputed `int[]` of slots: a sequence of constant stores
  after partial evaluation, with no string compare at all.
- No allocation of `missingNames`.

**What doesn't transfer.**
- Python's defaults are values. PHP's are constant expressions evaluated per call in the callee's scope, after all
  arguments are sent and before the callee's checks (1.3), so they can't be copied from the function object.
- PHP has by-reference parameters: whether an argument is a reference depends on the slot its name finds, which
  must be known before the argument is fetched (the `SEND_VAR_EX` problem).
- PHP's extras go into the variadic's array, after the positional extras, not into a separate `**kwargs`.
- Python has positional-only parameters; PHP has none for user functions.
- The error rules and their order differ.

## 3. Truffle PHP today

- **Frame arguments** (`PhpFunction`): `[this, static, closure, args...]`. The argument count is
  `arguments.length - ARG_FIRST`. `ARG_CLOSURE` also carries an include's `IncludeScope` and a method's scope.
- **Calls on bytecode** (`BytecodeTranslator.call`, `methodCall`, `staticCall`, `callValue`, `newObject`):
  1. The callee is resolved first (`ResolveFunction`, `ResolveMethod`, `ResolveCallable`, `ResolveConstructor`)
     into a stack value, as `INIT_FCALL` does.
  2. Each argument becomes a `Send*` operation taking `(int index, PhpFunction callee)`, which decides by reference
     or by value (`SendVar`, `SendElement`, `SendProp`, `SendNested`, `SendTemporary`, `SendValue`, `SendCall`,
     `ArgByRef` with a `Conditional`).
  3. A call operation takes the callee and the values as `@Variadic(startOffset = ARG_FIRST) Object[]`. The DSL
     builds the frame-argument array directly, and the `DirectCallNode` is cached on callee identity (`Call`,
     `CallRef`, `CallMethod`, `CallStatic`, `CallValue`, `Construct`).
- **Unpacking** is a slow path of its own: `SpreadStart`/`SpreadAdd`/`SpreadValue`/`SpreadVar` build a
  `SpreadArguments` list behind `@TruffleBoundary`, taken by the `*Spread` call operations.
- **The prologue** (`BytecodeTranslator.prologue`):
  - `CheckArity` (internal prelude functions only).
  - Per parameter, `BindParameter(local, i)` of `Argument(i)`, or of `Conditional(ArgumentGiven(i), Argument(i),
    default)`, under `CheckParameter` for typed ones. The default is a literal or an expression evaluated in the
    callee; `ArgumentGiven` compares the index with the length.
  - Then `BindVariadic`, `BindCapture`, the promoted properties.
- **`func_get_args`, `func_num_args`, `func_get_arg`** (`FuncGetArgs`...) count the frame arguments.
- **Built-ins:**
  - `Registry` declares them with `refMask`-only parameters named `arg0`... and the arity table `arity.txt` (#35,
    `tools/builtin-arity.php`: required and maximum counts from the reference PHP's Reflection).
  - `BuiltinRootNode` checks the count and calls the Java body behind a boundary.
  - The bodies read `Object[]` by position (`Args`).
  - Some are compiled in place by the translator: `func_get_args`, `call_user_func(_array)`, `compact`, `extract`,
    `count`, and `Intrinsics`.
- **Backtraces** already print string keys of the `args` array as `name: value` (`Backtrace`, `_build_trace_args`).

## 4. The design

### 4.1 Principles

1. **Calls without names don't change.**
   - The translator emits today's bytecode for every call without `AST_NAMED_ARG`.
   - No call operation, `Send*` operation or callee prologue gains a check.
   - The only exception is the unpack slow path, which gets a key-type test per element (one `isPacked`-style test
     for a list).
2. **Bind at the call site, after the callee is known.** The callee is already resolved before the arguments (as
   php-src), so every named site binds against a constant callee after partial evaluation.
3. **Holes are filled before the callee runs, in a frame that looks like the callee's**, as php-src's fake frame.
   The callee's prologue never sees a hole, so it doesn't change at all.
4. **Everything about the binding is a constant per (site, callee):** slots, by-reference flags, errors, holes,
   extras. It is computed once, outside partial evaluation, and cached in the site's nodes.

### 4.2 Compile time (`BytecodeTranslator`)

- **The compile errors** of 1.1 for calls, constant-expression `new` and attributes. The attribute duplicate check
  goes where attributes are compiled.
- **A named site** gets a constant `NamedSite` operand:
  - `TruffleString[] names` (interned; `null` at a positional argument; the names before any unpack in source
    order),
  - `int positional`,
  - the site's identity (its plan cache key).
- **Sends.** Each argument at or after the first name is sent by a named variant:

| Argument | Today | Named | Error timing (1.2) |
|---|---|---|---|
| plain variable | `SendVar(local, i)` | `SendVarNamed(local, site, k)` | binding before the read: no "Undefined variable" warning on an unknown name |
| any value (literal, expression) | `SendValue(i)` | `SendValueNamed(site, k)` | after the value |
| a call's result | `SendCall(i)` | `SendCallNamed(site, k)` | after the value |
| element, property, static property, nested, temporary | `SendElement`/`SendProp`/... | the same positional send at position 0, its callee `NamedTarget(site, k)` (a stand-in by reference or by value as the plan says), into `SendPlaceNamed(site, k)` | after the fetch, as `CHECK_FUNC_ARG` + `SEND_FUNC_ARG` |

  The positional arguments before the first name keep their operations: their slots are their indices.
- **Calls.** The call becomes a named variant taking the callee and the values in source order:
  - `CallNamed(info, site, keepRef)`, `CallMethodNamed`, `CallStaticNamed`, `CallValueNamed`, `ConstructNamed`.
  - Five operations; `keepRef` is a constant operand, rather than a separate `*Ref` operation.
- **Built-ins compiled in place** (frame functions, `count`, the intrinsics) fall back to the plain built-in call
  when the call has names, as `hasUnpackOrNames` already does for some.
- **`assert`** keeps php-src's appended positional message, so the "overwrites" quirk and the
  "assert(assertion: false)" text come out as php-src's.

### 4.3 The binding plan

`NamedSite.plan(PhpFunction callee)` returns a `BindingPlan`. It is pure, computed behind a boundary, and immutable:
- `int[] slot`: per argument, its frame position, or `EXTRA` (to the variadic) or `MAGIC` (`__call`).
- `boolean[] byRef`: per argument.
- `int errorAt` with its `PhpError` factory: the first argument whose binding fails ("Unknown", "overwrites").
- `int length`: the frame length, `ARG_FIRST + num_args`.
- `int[] holes`: the unbound slots below `length`.
- `TruffleString[] extraNames`.
- `boolean builtinEntry`: holes or extras on a built-in (4.6).

The lookup is case-sensitive over the callee's `Param.name()`, then the variadic, then the error. That is
`zend_get_arg_offset_by_name` over the whole argument list at once, with each failure recorded at its argument.

**Caching.**
- Every named operation has a specialization guarded by `callee == cachedCallee` with `@Cached("site.plan(callee)")`
  and the same limit as the plain operation (1 for functions, 4 for callable values and methods). In compiled code
  the plan's arrays are `@CompilationFinal(dimensions = 1)` constants and the loops are exploded.
- The fallback specialization takes the plan from a small per-site map keyed by the function (an identity map,
  synchronized: functions are shared by contexts).
- `SendXNamed(site, k)` throws when `plan.errorAt == k` (folded to nothing elsewhere), and decides by reference from
  `plan.byRef[k]`.

**The call operation** scatters the values into one new frame array: `frame[ARG_FIRST + slot[k]] = values[k]`.
- Unbound slots get `PhpFunction.NOT_PASSED`, a sentinel distinct from PHP `null`.
- Extras build a `PhpArray` in order.
- Then:
  - With **no holes and no extras** (names in order, or reordered without gaps): the plain `DirectCallNode` call.
    This costs the same as a positional call: one array, constant stores.
  - With **holes**: first a call of the callee's **defaults target** (4.4) on the same array, then the plain call.
  - With **extras**: the array goes through the variadic channel (4.5).

### 4.4 Holes: the callee's defaults target

Per user function, a second, small bytecode root, made lazily the first time a call leaves a hole in it. It is
translated from the declaration's parameter list, which the translator keeps for reparsing. Its body, per
parameter i below the frame length, in order:

```
if (IsNotPassed(i)) {
    required: NotPassed(i)                  // ArgumentCountError "f(): Argument #i+1 ($x) not passed"
    else:     SetArgument(i, <default i>)   // the same translation as the prologue's default
}
```

- It runs in its own frame. `Backtrace.Callee.describe` names it as the callee and shows `NOT_PASSED` as `NULL`, and
  its source sections are the parameters' lines. Errors and traces are then php-src's fake frame: line, `#0
  f.php(99): four('a', NULL, NULL, 'd')`.
- It runs after every argument is sent and before the callee's prologue, so the order of 1.3 holds.
- The `DirectCallNode` to it is cached with the plan, so it inlines. A literal default is then a constant store into
  the virtual frame array.
- Compiled per function only when needed: functions never called with a hole cost nothing, not even bytecode.

This deliberately differs from "the prologue fills a marker". A marker check in the prologue would cost every call
of every function with two or more parameters. It would also evaluate a hole's default in the middle of the
parameters' type checks, which php-src doesn't (the three order tests in `named-args-errors`).

Built-ins have their own entry (4.6). `__call` targets never have holes.

### 4.5 Extras into a variadic

`ARG_CLOSURE` carries a `NamedExtras {Object slot; PhpArray named}` wrapper for the call, restoring the slot's own
value (a closure, or null).
- Only a variadic function can receive one. Its prologue starts with `TakeNamedExtras(local)`:
  - It unwraps the slot before anything can read it (a default's `self::` in a closure reads the scope there).
  - It keeps the names in a hidden local, which `BindVariadic` appends after the positional extras:
    - each typed-checked with argument number `num_args + 1`;
    - references for a by-reference variadic.
  - `describe()` reads the same local for backtraces.
- `TakeNamedExtras` is guarded by an `Assumption` on the root, invalidated when the first plan with extras is made
  for that function. Compiled code is unchanged until then. In the interpreter it is one instruction, in variadic
  functions only.
- `func_get_args` and `func_num_args` count only the frame arguments, so they leave the extras out, as php-src.

**`__call`/`__callStatic`:** `Callables.Target.frameArguments` (the magic path) builds `$args` with the extras'
string keys after the positional values. There is no channel; the plan of a magic target has every name as an extra.

**As built (part 2).**
- `NamedExtras` keeps the names and the values as two Java arrays (the names are the plan's constant array), not a
  PHP array: nothing holds a count until the variadic's array takes them, as php-src frees extra_named_params with
  the call.
- Two operations, both only in variadic functions and both nothing while the root's `noNamedExtras` Assumption holds:
  `TakeNamedExtras` first (the slot's value back, the wrapper into a hidden local), `BindNamedExtras` right after
  `BindVariadic`, which is unchanged. The Assumption is invalidated by the first call that passes the function extras
  (`BindingPlan.enter`, `SpreadArguments.enter`), before it calls; a clone shares it.
- A typed variadic checks each extra with the argument number `max(num_args, variadic position)` + 1, unnamed, as
  RECV_VARIADIC's arg_num; a by-reference variadic's are references (a value given one gets a new reference).
- The defaults target of a variadic function takes the extras too (its frame shows them, as php-src's fake frame is
  the call's) and puts the slot back: the call sets the wrapper again after it.
- `describe()` appends them to the trace's `args` under their names.
- `__call` and `__callStatic` (and a closure of a method `__call` stands in for): `BindingPlan.magicValues` and
  `SpreadArguments.magicValues` build `$args`; a `Closure`'s own methods (`call`, `bindTo`) with names are part 3.

### 4.6 Built-ins

**The table.** `tools/builtin-arity.php` also writes each internal function's and method's parameters:
- the name,
- by reference or prefer-ref,
- variadic,
- and the default: `var_export` of `ReflectionParameter::getDefaultValue()` when `isDefaultValueAvailable()`, `?`
  when unknown, nothing when required.

It goes to `builtins/params.txt`, or as more fields in `arity.txt`. `Registry` builds real `Param`s from it, parsed
once (lazily, on the first named call of a built-in, to keep the start-up cost) instead of `arg0`, `arg1`...
- The `refMask` given in Java is checked against the table's by-reference flags (a unit test).
- The seven forwarding built-ins of 1.6 are a fixed list.

**The entry.** A plan whose callee is a built-in, with holes or extras, calls the built-in's lazily made **named
entry**. It is a `RootNode` that describes itself as the built-in in traces. It:
1. checks the count, as `BuiltinRootNode` does;
2. fills each hole from the table: the value, a new reference for a by-reference parameter, "not passed", or "must
   be passed explicitly, because the default value is not known";
3. for extras, a forwarding built-in gets them (4.7); any other variadic built-in throws "does not accept unknown
   named parameters";
4. calls the body.

A named call without holes or extras calls the built-in's ordinary target. Unknown names on a non-variadic
built-in fail at the send (no frame), as php-src.

**Ordering detail.** php-src raises "does not accept unknown named parameters" where zpp reaches the variadic,
after the parameters before it are parsed. The entry raises it after the count check and before the body. The
difference shows only when an earlier parameter's zpp type error would win. To be exact, the entry instead hands
the extras to the body, and `Args`' variadic accessor throws at that point (php-src's place).

**As built (part 3).**
- `BuiltinSignature` (builtins): `named-params.txt` (tools/builtin-named-params.php, the reference's Reflection: names,
  by reference, variadic, each default `serialize()`d, `?` when php-src doesn't know it), read on the first named call
  of a built-in. `Registry` gives each built-in its key; `PhpFunction.getBuiltinSignature()` finds it, and
  `BindingPlan.parameterIndex`/`isVariadic`/`variadicIndex` and `SpreadArguments` bind built-ins by it. A built-in's
  positional behaviour is unchanged: its by-reference flags stay Registry's.
- Holes: `BuiltinDefaultsRootNode`, the built-in's defaults target (`Registry` sets its factory), fills the arginfo
  default (an array a copy, an enum case looked up, a by-reference parameter's in a new reference), or throws "not
  passed" / "must be passed explicitly, because the default value is not known"; it describes itself as the
  built-in's frame (`BuiltinRootNode.describe`, a hole `NULL`).
- Extras: `NamedExtras.expect` tells the built-in's `BuiltinRootNode` (an Assumption, so a built-in never called with
  extras checks nothing). Once invalidated it throws "f() does not accept unknown named parameters" after the count
  check, unless the built-in forwards (`BuiltinSignature`'s list of the seven), whose body takes the names:
  `call_user_func` and `Closure::call` through `NamedExtras.forwarded` and `SpreadArguments.sendArray`; a Closure's
  own methods called with names (`$c->call(...)`, `bindTo`) bind as built-ins. The prelude's three forwarding
  variadics (`ReflectionFunction::invoke`, `ReflectionMethod::invoke`, `ReflectionClass::newInstance`) are user
  functions that unpack their extras; the prelude has no other public variadic.
- Messages name a built-in's parameters by its arginfo (`PhpFunction.argumentName`): "preg_match(): Argument #3
  ($matches) could not be passed by reference", positional calls too (they said `$arg2`).
- `Callables.sendValues`: zend_call_function's sends from runtime code (call_user_func, call_user_func_array's
  positional path, every built-in calling back through `CallableDispatchNode` or `Callables.Target`): a value for a
  by-reference parameter is zend_param_must_be_ref's warning and a temporary reference; skipped at once for a function
  without by-reference parameters (`PhpFunction.hasByRefParameter`, a constant for a cached callee).

**Prelude classes written in PHP** (Reflection, some SPL) are user functions. Their parameter names must be
php-src's, and a unit test checks them against the table. A prelude method that is a forwarding built-in in php-src
(`ReflectionFunction::invoke(mixed ...$args)`) gets the extras in its variadic and unpacks them by name, which is
exactly php-src's behaviour. Other prelude variadics must reject extras as php-src's do: an attribute or a table
flag makes their plan throw.

### 4.7 Unpacking, `call_user_func_array` and the other dynamic paths

`SpreadArguments` gains named binding, behind a boundary like today's list:
- `bindNamed(name, value, callee)`: slot by name (through a per-function name index, a `HashMap` made lazily for
  functions with more than 8 parameters, else a scan), "overwrites", "Unknown" or an extra.
- A per-unpack "seen a name" flag gives "... during unpacking".
- `SpreadValue` and `SpreadVar` apply 1.5:
  - an array's string keys;
  - a Traversable's keys and their errors;
  - references for an array, the warning for a Traversable.
- A literal named argument after `...` uses a `SpreadNamed*` variant of the sends, its by-reference flag from the
  name's slot at run time.
- `frameArguments()` places the holes and extras.
- The `*Spread` call operations call the defaults target or the built-in entry when there are holes, behind the
  boundary.

The same binder serves `ZEND_SEND_ARRAY`'s semantics for:
- `call_user_func_array` (the compiled `CallUserFuncArray` and the built-in);
- `forward_static_call_array`, `Callables`' callers, Reflection's `invokeArgs` and `newInstanceArgs`.

Here the message has no "during unpacking", and a value for a by-reference parameter is the warning plus a
temporary reference.

The forwarding built-ins (`call_user_func`, `Closure::__invoke`/`call`, `Fiber::start`, Reflection's `invoke`) take
the extras from their entry and pass them on to the binder.

**As built (part 2).**
- `SpreadArguments` binds by position or by name as the values come (`bind`/`set`), the holes `NOT_PASSED` and the
  extras in two lists; `isNamed()` (a field test, no boundary) tells the `*Spread` calls whether to `enter` (defaults
  target, then the extras), so unpacking without names costs what it did plus a key test per element.
- `SpreadStart(construct)`: a `new`'s list knows that `MethodSite.BY_VALUE` is a class without a constructor
  (zend_pass_function, every name unknown) rather than `__call` (every name an extra).
- A variable's shared array is separated when an element lands on a by-reference parameter by its name or its
  position (php-src's check: an unknown name looks at the variadic parameter).
- Names after unpacking: `SpreadVarNamed(local, name)` (bound before the read, by reference as the bound slot's
  parameter), `SpreadNamed(name, kind)` for a value (SEND_VAL_EX's Error), a call's result (SEND_VAR_NO_REF) or a place,
  whose fetch asks `SpreadTarget(name)` (CHECK_FUNC_ARG by name). Three operations.
- ZEND_SEND_ARRAY (`addSendArray`, `sendArray`): the compiled `CallUserFuncArray` takes it only when the array has a
  string key (`CallableCallNode.arrayArguments` gives null: a hash's key test per element, the list layouts none), so a
  positional call is unchanged; the built-ins `call_user_func_array`, `forward_static_call(_array)` (new; the calling
  code's called class goes on when it is an instance of the callable's) and ReflectionMethod's `reflection_call` always
  bind with it. The prelude's ReflectionFunction `invoke(Args)` call `call_user_func_array()`; ReflectionClass
  `newInstance(Args)` call `Truffle\reflection_construct` (zend_call_known_function with named_params).
- A call made from runtime code for the binder (the holes' defaults target, the named `call_user_func_array`) runs
  with the call's node as its location (`Backtrace.callFrom`), so the trace shows the call's line.
- Fixed in part 3: positional `call_user_func(_array)` and built-ins' callbacks give zend_call_function's
  by-reference warning, `__call`'s `$args` keeps the references `call_user_func_array` passes, and
  `Closure::fromCallable([$obj, 'missing'])` takes no names ("Unknown named parameter", `MethodSite.NO_PARAMETERS`)
  while `$obj->missing(...)`'s collects them (`tests/smoke/callbacks-by-reference.php`).

Constant-expression `new` (the defaults target, static variables, global constants, and class constants once their
thunks are bytecode roots) compiles named arguments as any `new` does.

### 4.8 The fast-path guarantee, stated

| Path | Change |
|---|---|
| call without names, any callee | none: same bytecode, same operations |
| callee prologue, non-variadic | none |
| callee prologue, variadic | `TakeNamedExtras`: nothing compiled while no extras ever came, one instruction in the interpreter |
| `ArgumentGiven`, `BindParameter`, `func_*_args` | none |
| built-in call without names | none (`BuiltinRootNode` unchanged) |
| named call, no holes, no extras | one frame array, constant stores: as a positional call |
| named call with holes | plus the defaults target, inlined: a literal default is a constant store |
| unpacking | the existing slow path plus a key-type check per element |

**Allocation.**
- Named calls without extras allocate only the frame array, as positional calls do.
- Extras allocate their `PhpArray` (php-src too) and the wrapper. The wrapper is virtual when the call inlines.

**Memory.**
- Per named site: the names array and the cached plans.
- Defaults targets and built-in entries only where used.
- The built-in parameter table is parsed on the first named call of a built-in.

The Gate 1 A/B at the end of phase 2 shows the first two rows.

## 5. Size and risks

**Estimate:** about 2,500–3,000 lines.

| Part | Lines |
|---|---|
| translator (names, compile errors, sends, defaults root, `assert`) | 500 |
| operations (sends, calls, `TakeNamedExtras`, defaults) | 700 |
| `NamedSite`/`BindingPlan` | 300 |
| `SpreadArguments` and the binder for the dynamic paths | 350 |
| built-in table, tool, `Registry`, named entry | 400 |
| variadic, backtrace, `__call` | 150 |
| unit tests | 300 |

**Risks:**
1. **Order.** Top-level code needs `bytecode-only`: until it lands, named calls at file level stay unsupported.
2. **Generated interpreter size.** About ten new operations grow it and `PhpBytecodeRootNode`'s
   partial-evaluation size. Mitigation: shared helpers; keep the specializations to cached plus generic.
3. **Built-in bodies that tell "absent" from "the default passed".** They differ from php-src already for an
   explicit default, but named holes will expose it more. Mitigation: the smoke tests on the common built-ins; the
   table's defaults are php-src's own values.
4. **Prelude parameter names** that differ from php-src's turn a valid named call into "Unknown named parameter".
   Mitigation: the unit test against the table.
5. **The defaults target needs the parameter list after the body's translation.** The translator keeps
   `declaration` for reparsing. Check that this keeps only the header (the body is read lazily, issue 003's memory
   work), else keep just the parameter list.
6. **`ARG_CLOSURE`'s wrapper** must be unwrapped before anything reads the slot. Only variadic callees and the
   built-in entry can receive it, and both unwrap first. An assertion in the readers' shared helper catches a
   leak in tests.
7. **php-src's corners:** the per-unpack reset; Traversable numeric keys; `assert`; `array_multisort(: 1)`;
   `#[\SensitiveParameter]` on extras; attributes (no `ReflectionAttribute` yet, a separate gap).

## 6. Phase 2 plan

On a branch from `origin/bytecode-only`, merged regularly. Each step is test first, then `mvn clean package`, then
smoke:
1. compile errors and `NamedSite`;
2. named sends and calls for user functions without holes;
3. the defaults target;
4. extras and `__call`;
5. unpacking and `call_user_func_array`;
6. the built-in table and entry;
7. `assert` and the remaining callables.

At the end:
- the phpt delta (`Zend/tests/named_params` and the rest, no new failure);
- Gate 2 pages 19/21;
- the WooCommerce walk;
- a Gate 1 A/B on CPUs 14-21 with `memory.peak`.
