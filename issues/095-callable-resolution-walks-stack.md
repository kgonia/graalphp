# 095: A class-name callable walks the stack on every resolution, and two of its results were wrong

**State:** Fixed 2026-11-10 on `fastcgi` (below). Found 2026-11-10 by the K8 differential profile
([wiki/beat-php.md](../wiki/beat-php.md)): `WP_Style_Engine::get_individual_property_css_declarations` took 118× PHP's
time per hot render, `usort` with `array( self::class, 'sort_start_ascending' )` 38×.

**What happens:** a callable that names a class (`['Cls', 'm']`, `'Cls::m'`) resolves against the PHP code that
hands it to the built-in: the class called is that code's `$this`'s class when it is an instance of the named
class through the code's scope, and a private or protected method is callable from its scope only
(`zend_is_callable_check_class`, `zend_is_callable_check_func`). `Callables` found that code with
`Truffle.getRuntime().iterateFrames` on every resolution: `is_callable`, `call_user_func`, `array_map`, `usort`,
`preg_replace_callback` and the rest. A stack walk deoptimizes nothing but materializes the frames: 6–12 µs.

`target/scratch/cuf.php`, ns per call, host JVM (P-cores), steady state:

| loop body | before | after, one context | after, third context of a shared engine |
|---|---|---|---|
| `is_callable([Cls, 'pub'])` | 6,000 | 154 | 95 |
| `is_callable([Cls, 'prot'])` from the class | 11,900 | 86 | 116 |
| `call_user_func([self::class, 'prot'], …)` | 8,800 | 245 | 127 |
| `isset($d['f']) && is_callable($d['f'])` then `call_user_func($d['f'], …)` (the style engine's) | 22,200 | 258 | 184 |
| `call_user_func([Cls, 'pub'], …)` (kept by the call site's cache before too) | 20 | 123 (noisy) | 7–13 |

In a hot render of WordPress's front page that pattern was ≈ 2.65 ms of ~33 ms (style engine 1.42 ms against PHP's
0.012 ms; `usort` 1.07 ms), plus an unmeasured share inside `WP_Hook::apply_filters`.

**Two wrong results found on the way** (both present before; `tests/smoke/callable-caller.php` covers them):

1. `CallableDispatchNode.classMethod` (a call site's kept `['Cls', 'static_method']`) passed the named class as the
   called class. From a method of a subclass's object PHP passes the object's class: `call_user_func(['Base', 'who'])`
   in `Child extends Base` returns `Child` for `static::class`; ours returned `Base` from the second call on.
2. `Callables.LastCall` (a built-in's kept last callable) kept a target resolved for one caller and gave it to the
   next: `array_map(['Base', 'who'], …)` called from a `Child` object, then from a `Base` object, answered `Child`
   twice.

**Fix:** the caller is passed, not searched for.

- The bytecode operations that take a callable (`CallUserFunc`, `CallUserFuncArray`) pass their frame's arguments and
  scope to `CallableDispatchNode.execute(callerArguments, callerScope, callable, values)` and to
  `Callables.resolveOrThrow(context, callable, parameter, callerArguments, callerScope)`.
- A built-in function whose body asks (`Callables.resolve(context, a, callable)`, `Callables.parameter(context, a,
  …)`: `is_callable`, `call_user_func(_array)`, `array_map`, `array_filter`, `array_walk(_recursive)`,
  `array_reduce`, the `u*sort` and `array_u*` families, `array_find*`/`any`/`all`, `preg_replace_callback`) walks the
  stack once; the walk marks its `BuiltinRootNode` (`wantCaller()`, an Assumption). From then on `Call.direct` and
  `CallRef.direct` put a `Callables.Caller(frameArguments, scope)` into the frame arguments' static-class slot
  (`ARG_STATIC`, which only a built-in method uses) and the body reads it there. Built-ins that never ask pay
  nothing: `wantsCaller()` is a constant of the compiled call.
- `classMethod` takes the called class from `Callables.calledClass(context, named, callerArguments, callerScope)`.
- `LastCall` keeps, with a target that depended on the caller, the caller's `$this` class and scope, and is a hit
  for the same pair only. The paths that give no caller (`Callables.call`, `check`, the three-argument `parameter`)
  walk the stack on such a hit to compare: exact before fast, and none of them is on a hot path of WordPress.

Still on the walk (not seen in the profile): `forward_static_call(_array)`, `Closure::fromCallable` and the
closure methods, curl's and the filter extension's callbacks, `unserialize`'s, `set_error_handler` and the other
registrations, Fiber, `spl_autoload_register`, `register_shutdown_function`.

Memory: one Assumption per built-in root; one `Caller` (two fields) per call of a built-in that asked, which escape
analysis removes when the built-in is inlined.

**Test:** `tests/smoke/callable-caller.php`: the called class and the visibility of class-name callables from
instance methods (objects of the class and of a subclass in turn), static methods, closures (bound, static, bound
to a scope only), generators, plain functions and the top level; through `call_user_func(_array)`, `array_map`,
`is_callable`, `usort`, `preg_replace_callback`, `array_filter`, `array_reduce` and their variable-function forms;
the errors of callables not accessible; one callable value used by callers in turn. One context and three.
`CompilationTest`: the same two known failures as before (094).
