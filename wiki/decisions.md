# Decisions

Newest at the bottom. Each entry gives the decision, the date, and why.

## D1: the goal is Graal making PHP code faster (2026-10-22)
The owner said: "Graal speeding up PHP is the point." Success means unmodified WordPress and plugins running faster than PHP 8.4 with OPcache JIT, not just hosting PHP on the JVM.

## D2: a Truffle language, not libphp+FFM or php.wasm (2026-10-22)
- **libphp embedded via FFM:** PHP still runs in the C engine, and Java only hosts HTTP. Every plugin works, but nothing gets faster.
- **php.wasm on GraalWasm:** Graal compiles the *PHP interpreter* compiled to Wasm. The PHP script is data in linear memory, so Graal can't specialize it: no inlining of PHP calls and no type speculation. OPcache's JIT can't run inside Wasm either, so it is expected to be *slower* than php-fpm. It's still useful for sandboxing, but not for this goal. Running php-src on Sulong has the same problem.
- **JPHP and Quercus:** dead, and stuck at PHP 7.x and 5.4.
- **PHP → JVM bytecode** (JPHP style) is optimized by Graal as a JIT, but without Truffle's runtime type specialization and deoptimization. Every fast dynamic language on GraalVM is a Truffle language.

## D3: start fresh, not from a graalphp fork (2026-10-22)
See [graalphp-assessment.md](graalphp-assessment.md). graalphp is on Truffle 20.2 (2020), is 6.7k lines, and has no strings, objects, hash arrays or copy-on-write. Its benchmarks and storage-strategy idea are reused; its code isn't.

## D4: gates with measured go/no-go criteria (2026-10-22)
HHVM's lesson is that compatibility work can swallow a project whose speed lead then disappears. The cheapest decisive experiment comes first: Gate 1 measures Truffle against PHP 8.4 JIT on real WordPress functions before any long-tail runtime work.

## D5: Gate 1 parses with php-src itself (2026-10-22)
The PHP source is parsed by real PHP 8.4 with the `ast` extension (nikic/php-ast) and exported as JSON. The Truffle side builds nodes from **zend_ast kinds**.
- **Why:** zero parser work in Gate 1, and the parse is exactly php-src's.
- **Later:** a Java parser ported from `zend_language_parser.y` replaces the front end in Gate 2 and emits the same zend_ast kinds, so the node builder stays.

## D6: Gate 1 uses a Truffle AST interpreter (Truffle DSL), not the Bytecode DSL (2026-10-22)
Gate 1 answers a peak-performance question, and AST and bytecode interpreters reach the same compiled code. The Bytecode DSL mainly improves interpreter speed, memory and warmup. Gate 1 records warmup curves. If warmup is the problem, moving to the Bytecode DSL is a Gate 2 decision.

## D7: `preg_*` binds the real PCRE2 via FFM from the start (2026-10-22)
- **Semantics:** they are PHP's exactly, because PHP bundles PCRE2. `java.util.regex` differs.
- **Fairness:** regex time is the same on both sides, so Gate 1 measures what Graal does to the PHP code around the regexes.
- **Later:** a PCRE flavor for GraalVM's TRegex, which would make regexes compilable, is a separate later project.

## D8: toolchain (2026-10-22, revised the same day)
- **Oracle GraalVM 25.0.4** with Truffle **25.0.4** from Maven Central, on both platforms (`~/.jdks/graalvm-jdk-25.0.4+7.1` on Windows; the Linux build in the `gate1-truffle` image).
  - First choice was 25.4.4.1.1, but Oracle's newest Linux download is 25.0.4, and Truffle refuses to compile on a JDK older than itself ("Version check failed"; it falls back to the interpreter).
- Maven (IntelliJ's bundled 3.x locally).
- Package `pl.codeloom.php`.
- The reference PHP runs in Docker (`php:8.5-cli` plus `ast`, OPcache tracing JIT on; 8.4 too, see D9).
- **Truffle runs in Docker too:** `bench/truffle/Dockerfile` builds `gate1-truffle` on top of the PHP image (same Debian and CPU), adding Linux GraalVM and PHP's own bundled PCRE2 built as a shared library (`build-pcre2.sh`). `bench/gate1/truffle.sh` is the counterpart of `php.sh`.
- Published Gate 1 numbers come from Linux with pinned cores, never from Windows. On Windows, Git's libpcre2 scans about 20× slower than PHP's, which makes regex kernels incomparable there.

## D9: Gate 1 reference is PHP 8.5, and Go is judged on the PHP-code speedup (2026-10-22)
- **PHP 8.5:** it is the newest stable release (8.5.12; 8.6 is at RC4), and the retro says to compare against the newest PHP. 8.4.27 is measured too.
- **PHP-code speedup:** both engines run the same PCRE2, so regex time caps the end-to-end speedup regardless of Graal (side agent note, 2026-10-22). Gate 1 times native calls on the Truffle side and judges (T_php - T_native) / (T_truffle - T_native), with end-to-end reported next to it. Details in [gate-1.md](gate-1.md).

## D10: migrate to idiomatic Truffle structure, in a fixed order (2026-10-23)
**Decision:** the runtime moves to the structure that Truffle languages (GraalJS, TruffleRuby, GraalPy) and the Truffle guides use. The steps run in the order below. Each step is done when the Gate 1 checksums, the smoke tests and the php-src run show no regressions, and an alternating-order A/B is neutral or better.

**Why:** Gate 1 stands at 0.58× on PHP code. A review found that the runtime departs from the Truffle idiom where it hurts most:
- **Hand-written nodes.** Only 5 of 50 node files use the Truffle DSL, and polymorphism is never reported to the runtime, so splitting never triggers. Each specialization has been re-implemented by hand: the array and `foreach` "seen" flags.
- **Built-ins are Java lambdas behind one boundary.** That means argument arrays, boxing, and 48 ns per call when not inlined, against PHP's 2.6 ns.
- **Array layouts are if-chains in one class**, not storage types dispatched by DSL guards.
- **17 static mutable fields** hold per-context state.
- **ASTs are tied to a context.** A WordPress server with a context per request would re-translate and re-warm everything on every request.

The first versions got all kernels running fast; their limits are now measured.

**Migration order:**
1. **Per-context state.** Move the static mutable fields into `PhpContext`, and thread-safe shared caches (compiled regexes) into `PhpLanguage`. Small and mechanical; it removes `BatchMain`'s manual resets. Debug-only statics (`NativeClock`, `PcreRecorder`) may stay.
2. **Hot built-ins as DSL nodes.** Pick the set by measurement (`--engine.InstrumentBoundaries`, call counts in the kernels). Each becomes a node with `@Specialization`s and cached `TruffleString` nodes, with a boundary only around code partial evaluation can't handle. The lambda built-ins stay for the long tail. The existing `Intrinsics` are the start of this.
3. **Core nodes on the DSL.** Element access, assignment, `foreach` and calls get DSL specializations that report polymorphism. `PhpArray` storage dispatches by type through guards (int list, Object list, hash), replacing the hand-written seen flags.
4. **Context-independent ASTs and `ContextPolicy.SHARED`.**
   - Translation no longer takes a `PhpContext`.
   - Nodes cache only language-level objects (function and class templates), checked per context.
   - Parsed files are cached per language.

   Must land before Gate 2: a server reuses compiled code across requests.

   **Design (2026-10-23).** Today the translator holds a `PhpContext`:
   - function declarations are lambdas that capture it;
   - a class declaration translates its methods when it runs, once per context, and resolves its parent and interfaces through the context;
   - constants already defined in the context are folded into the AST.

   `PhpFunction` also carries per-context state (static variables, the declaring `PhpClass`). The split:
   - **Translation output is language-level.**
     - A `PhpFunction` keeps only its name, flags, parameters and call target. Static variables move to the context, keyed by function.
     - A class becomes a `ClassTemplate`: name, parent and interface names, flags, method `PhpFunction`s, property declarations and constant initializers. A declaration node instantiates it into a per-context `PhpClass` when it runs.
   - **Name lookup goes through language-level ids.** `PhpLanguage` gives each function and class name an index, and a context keeps its declarations in arrays by that index. A call site does an array load, then a DSL inline cache on the identity of the template it finds, then a `DirectCallNode`. Under `SHARED` every context declares the same template objects, so the site stays monomorphic.
   - **Inline caches on classes key on a language-level class identity, not the per-context `PhpClass`** (owner, 2026-10-23). Under `SHARED`, a context per request would otherwise turn every class-keyed cache megamorphic: `new`, method calls, `InstantiateNode`, class constants. This is how GraalJS and TruffleRuby key caches on shapes rather than on objects.
     - Each `PhpClass` carries a `ClassKey`, interned in `PhpLanguage` from its `ClassTemplate`, its parent's key and its interfaces' keys. Two contexts that declare the same classes from the same files get the same keys.
     - A cache guards on the key and takes from it only what the key determines: methods and the constructor, property names and the shape.
     - Per-context values, such as evaluated defaults, static properties and evaluated constants, are read from the context's `PhpClass`.
   - **Only built-in constants** (`PHP_*`, `E_*` and the like, equal in every context) are folded at translation time. User constants go through the same id-indexed lookup.
   - **Parsed files are cached in `PhpLanguage`** by path and modification time. `include` asks the language for the call target.
   - **Then** `@Registration(contextPolicy = SHARED)`, with a test that runs two contexts on one engine and checks their outputs and that code isn't re-translated.
5. **Bytecode DSL evaluation**, the Gate 2 decision already set in D6, judged on warmup and memory over WordPress-sized code.
6. **Own parser and instrumentation tags** (D5's planned replacement; statement, call and root tags for debugging, coverage and statement-level profiling).

**Gate 0.5 (owner, 2026-10-23):** the migration is a gate of its own, ahead of further Gate 1 tuning. It is done when steps 1–4 have landed. Gate 1 is re-measured from time to time as a progress and regression check, not as each step's bar: step 1 and step 4 are structural and aren't expected to be faster.

**Rules for new code from now on:**
- Node behaviour that depends on value types is written with the DSL.
- No mutable `static` state.
- A built-in that is hot in measured kernels becomes a node.
- `@TruffleBoundary` only wraps code partial evaluation can't handle, never a whole built-in on a hot path.
- Partially evaluated code uses cached `TruffleString` nodes, not the uncached helpers.
- A branch that only needs "not seen yet, leave it out of compiled code" uses a `BranchProfile`, not a hand-written `@CompilationFinal` flag.

**DSL pitfalls met during the migration:**
- **`UnexpectedResultException` is not a "try the next specialization" signal.** When a specialization throws it, the generated code returns `e.getResult()` as the node's value. To leave a specialization, throw your own `SlowPathException` subclass listed in `rewriteOn`. A value the specialization depends on is better as a DSL child, so the guard sees it before the specialization runs.
- **Implicit casts carry over into rewrites.** With `long → double` an `@ImplicitCast`, a `double` specialization also takes ints:
  - when a specialization rewrites, the next one receives the already-cast value, so `NULL += 1` gave `float(1)`;
  - once `rewriteOn` excludes an int specialization, every int pair falls through to the `double` one.

  Put an `(long, long)` specialization ahead of the `double` one for every int pair the fast one doesn't take, as `CompoundAssignNode.longPair` does.
- **The DSL overrides every `execute*` method of the base class as "return this type".** `ExprNode.executeBoolean` means truthiness, but a generated node's override casts the result to `boolean`. That is safe only where a condition that isn't boolean-valued is wrapped in `ConditionNode` (`ConditionNode.of`). The `for` loop evaluated its comma-separated conditions before the last with `executeBoolean`, unwrapped, and `(void)f()` in that position crashed with a `ClassCastException` once casts were DSL nodes. Truthiness on an arbitrary node goes through `ConditionNode` or a `ToBooleanNode`; a value needed only for its effects goes through `execute`.
