# Native calls: one mechanism for FFM bindings (JVM and native image)

Truffle PHP calls native libraries (PCRE2, libcurl, the C library) through the FFM API. The JVM makes any downcall
handle fast; a native image does so only for handles that are constants of the image. This page is the contract
for adding a binding. The code is `pl.codeloom.php.ffi`: `NativeLibrary`, `Upcall`, `NativeCallsFeature`.

## Why: what a native image does with FFM (GraalVM 25.4)
- **Run-time handles are slow.** A downcall handle made at run time isn't a constant, so each `invokeExact` takes
  the generic method-handle path. Measured on 25.4 (`strlen`, 2M calls): 2,450–2,900 ns a call, against 12–20 ns for a
  constant handle and 9–35 ns on the JVM. That was regex on native before this fix: `preg_match` 4.7 µs a call
  against 0.13 µs after (bench/native/check.php).
- **Descriptor-only upcalls are slow too.** An upcall registered by its descriptor alone made qsort's 200,000-element
  sort with a Java comparator take 7.8–9.2 s; registered as a direct upcall (a static target) it took 98–115 ms, as
  on the JVM. The FFM page: direct upcalls create "an optimized variant of the upcall stub that's usually much faster
  and should be preferred."
- **What the page says about build time** ([Foreign Function and Memory API in Native Image](https://www.graalvm.org/latest/reference-manual/native-image/native-code-interoperability/ffm-api/), "Initializing Downcalls and Upcalls During Image Build"):
  - "In general, it is not possible to initialize classes at build time if they are creating downcall handles or
    upcall stubs in the static initializer."
  - The reason: "the image build would _freeze_ native addresses as constants into the resulting native image", and
    at run time "those addresses will most likely be different and no longer valid."
  - Likewise, the page "currently disallows native `MemorySegment` instances from being included in the resulting image heap".
  - Support "is enabled by default starting with GraalVM 25", so `-H:+ForeignAPISupport` isn't needed.
- **What we do beyond that guidance:** the classes that declare downcalls *are* initialized at build time, but their
  handles carry no address. `Linker.downcallHandle(descriptor)` makes an address-less handle, which takes the
  function's address as its first argument; folded with a lookup of the address at the first call, it is a constant
  with nothing native in it. The page doesn't describe this; it is measured (above), and the checks below guard
  it.

## The mechanism
- **`NativeLibrary`**: `NativeLibrary.named(property, unixName, windowsName)` or `NativeLibrary.LIBC`, and
  `library.downcall(symbol, descriptor, options...)`, which returns a `MethodHandle` called with `invokeExact`.
  - On the JVM the handle is bound to the address, looked up when the declaring class initializes, as before.
  - In a native image it is built with the image: the address-less handle, folded with the symbol's lookup at the
    first call.
  - `has(symbol)` tells at run time whether an optional function exists (glibc's `fopencookie`).
- **`Upcall`**: `Upcall.of(lookup, owner, staticMethod, descriptor)` declares a direct upcall; `upcall.stub()` makes
  its C function pointer at run time.
- **`NativeCallsFeature`** (enabled by `META-INF/native-image/pl.codeloom/graalphp/native-image.properties`):
  - it initializes the classes listed in `BINDINGS` at build time, and those in `NATIVE_STATE` at run time;
  - it then registers every downcall descriptor and direct upcall they declared, exactly as declared
    (`RuntimeForeignAccess.registerForDowncall`, `registerForDirectUpcall`).
- **The tracing agent** still records reflection and resources. `bench/native/agent.sh` drops the foreign calls it
  records, and `image.sh` refuses a configuration that has any. The agent only sees the paths its runs reach (it
  missed `CURLOPT_STDERR`'s `cookieWrite` upcall); the declarations cover all of them.
- **C types:** `NativeLibrary.C_LONG` and `SIZE_T` are the platform's canonical layouts (C `long` is 32 bits on
  Windows); bindings use them, not `JAVA_LONG`, for `long` and `size_t`.

## Adding a library
1. In a class of its own (a nested `static final class` is fine), declare:
   ```java
   static final NativeLibrary LIBFOO = NativeLibrary.named("php.foo", "libfoo.so.1", "libfoo-1.dll");
   static final MethodHandle FOO_DO = LIBFOO.downcall("foo_do", FunctionDescriptor.of(JAVA_INT, ADDRESS, SIZE_T));
   static final Upcall ON_DATA = Upcall.of(MethodHandles.lookup(), Callbacks.class, "onData", DATA);
   ```
   Call with `(int) FOO_DO.invokeExact(pointer, length)`.
2. List the class in `NativeCallsFeature.BINDINGS`.
3. Keep native state in classes listed in `NATIVE_STATE`, initialized at run time:
   - upcall stubs (`static final MemorySegment ON_DATA_STUB = ON_DATA.stub()`);
   - native memory and arenas;
   - a library's own initialization call (`curl_global_init`).
4. Add a call of the new library to `bench/native/check.php`, and build the native image with `bench/native/build.sh`,
   which runs the check.

| at build time (in `BINDINGS`) | at run time (in `NATIVE_STATE`, or reached only at run time) |
|---|---|
| `NativeLibrary` objects, downcall handles, `FunctionDescriptor`s and layouts, `Upcall` declarations | the library's handle (opened at the first call), function addresses, upcall stubs, `Arena`s, `MemorySegment`s, calls into the library |

## The checks
- **A downcall made at run time in an image fails.** `NativeLibrary.downcall` throws ("made at run time in a native
  image, where it isn't a constant"), so a binding class initialized at run time can't silently take the slow path.
  The same happens to a declaration that comes after the image registered its stubs (a class missing from `BINDINGS`).
- **Nothing native at build time.** Opening a library or making an upcall stub while the image is built throws, and
  the builder itself refuses native `MemorySegment`s in the image heap.
- **The registered set is the declared set.** Registration comes only from the declarations. `image.sh` fails if the
  agent's configuration registers foreign calls. So the image has no upcall stub registered by its descriptor alone,
  the slow kind: every upcall it has is a direct one.
- **The per-call cost is direct:** `bench/native/check.sh target/native/php` times `preg_match` and `curl_escape` and
  fails above 2 µs a call. `bench/native/build.sh` runs it after every build.
  - Built from `aa3f70f`, with handles made at run time, the calls took 4,659 and 4,954 ns and failed.
  - With the helper they take 126 and 474 ns (CPUs 2–5, 2026-10-31).
- **No raw FFM outside the helper.** `NativeCallsTest` (`mvn test`) fails on a `downcallHandle`, `upcallStub` or
  library lookup outside `pl.codeloom.php.ffi`, and on a class name in the Feature's lists that doesn't exist.
- **On the JVM nothing changes.** Handles are bound as before, and the A/Bs below find no difference.
- **Not supported:** `NativeLibrary.LIBC` uses `Linker.defaultLookup()`, which a `-H:+StaticExecutable` image doesn't
  have. We don't build static.

## Measurements (2026-10-31)
Interleaved runs on CPUs 14–21, order reversed every other round. A cell is the median over rounds of each run's
steady state (the last quarter of its iterations), in ms. Speed is the median over rounds of the per-round ratio
(IQR in parentheses), above 1 faster. Peak memory is the run's cgroup `memory.peak`. The variants:
- **native before**: the image built at `aa3f70f`, handles made at run time;
- **native after**: the image at `e34181c` (the helper);
- **JVM before / after**: the same two revisions on the JVM.

Every run of every variant gave the same checksum per kernel. Gate 1, K0, the regex micro and the curl micro give
PHP 8.5's checksums on both the JVM after and native after.

**Gate 1** (`ab.sh all 300 6`):

| kernel | native before | native after | after vs before | JVM before | native after vs JVM before |
|---|---|---|---|---|---|
| hooks | 22.9 | 22.8 | 1.00× (0.92–1.08) | 13.8 | 0.60× (0.56–0.66) |
| escape | 11.2 | 3.34 | 3.07× (2.65–3.62) | 2.11 | 0.60× (0.56–0.65) |
| slugs | 59.2 | 1.63 | 35.1× (25.3–38.7) | 1.65 | 0.83× (0.66–1.20) |
| wpautop | 179 | 7.34 | 25.5× (21.0–26.4) | 4.34 | 0.58× (0.55–0.68) |
| wptexturize | 110 | 5.35 | 18.6× (17.8–22.6) | 2.90 | 0.49× (0.48–0.67) |
| blocks | 39.3 | 9.61 | 4.38× (3.81–5.11) | 4.59 | 0.53× (0.43–0.70) |
| kses | 518 | 41.5 | 12.8× (11.5–14.3) | 20.8 | 0.56× (0.44–0.62) |
| **geomean** | | | **8.37×** | | **0.59×** |
| peak memory | 403 MB | 423 MB | | 1549 MB | |

- The first iteration is where native wins: kses 163 ms against the JVM's 293, hooks 156 against 472.
- At steady state native is at 0.59× the JVM. Its regex is direct now; the rest of the gap is PHP code that runs
  slower on native (hooks, which has no regex, is 0.60× too). gate-1.md found the same in 2026-10.

**Regex micro** (`bench/regex/micro.php`, short subjects, `ab.sh all 10 6`):

| kernel | native before | native after | after vs before | JVM before | native after vs JVM before |
|---|---|---|---|---|---|
| match | 552 | 32.7 | 16.5× (15.9–17.3) | 15.2 | 0.45× (0.38–0.54) |
| match_all | 646 | 25.3 | 24.6× (23.0–27.2) | 8.74 | 0.36× (0.32–0.37) |
| replace | 1222 | 12.6 | 95.1× (93.1–101) | 21.0 | 1.54× (1.40–1.75) |
| split | 1271 | 24.5 | 53.4× (47.3–54.2) | 8.35 | 0.38× (0.32–0.41) |
| **geomean** | | | **37.9×** | | **0.55×** |
| peak memory | 197 MB | 220 MB | | 767 MB | |

**Curl micro** (`bench/native/curl.php`, offline: `curl_escape`, `curl_setopt`, `file://` transfers whose data
comes back through the write callback, an upcall; `ab.sh all 10 6`):

| kernel | native before | native after | after vs before | JVM before | native after vs JVM before |
|---|---|---|---|---|---|
| escape | 112 | 12.7 | 9.54× (9.43–10.1) | 13.7 | 1.04× (0.99–1.07) |
| setopt | 158 | 4.32 | 33.1× (31.2–37.0) | 10.3 | 2.35× (1.97–2.59) |
| transfer | 64.2 | 14.3 | 5.69× (4.45–6.43) | 22.5 | 1.75× (1.45–2.04) |
| **geomean** | | | **12.2×** | | **1.62×** |
| peak memory | 187 MB | 238 MB | | 666 MB | |

**Peak memory on native is 5–27% higher after.** The binding isn't the cause:
- The old path's garbage ran the GC far more often: 1,785 collections on the regex micro against 25.
- curl's easy handles are freed by a `Cleaner` after a collection (`CurlFunctions`; php-src frees them at refcount
  zero). With fewer collections, more of them are alive at once.
- Measured with compilation off: after the transfer kernel's 10,000 handles, 178 MB anonymous memory against
  58 MB, after 1 collection against 18. `-Xmx64m` brings it to 52 MB.
- That refcount-against-GC difference holds on the JVM as well ([tla-curl.md](tla-curl.md),
  [issue 005](../issues/005-destructors.md)). It matters wherever a PHP loop makes many curl handles.

**Critical downcalls** (`Linker.Option.critical(false)` on `pcre2_match` and `pcre2_jit_match`, built as a third
image) against native after: Gate 1 0.98×, with every kernel's IQR across 1 except blocks 1.11× (1.02–1.24); the
regex micro 0.99×. No clear win, so they aren't kept. On the JVM, gate-1.md had already rejected them (2026-10):
they made wpautop and wptexturize about 40% slower, because the critical stub skips `vzeroupper`.

**The JVM, before against after** (the same bound handles):

| run | after vs before | per kernel | peak memory |
|---|---|---|---|
| Gate 1 (`ab.sh all 400 8 new ffi`) | **1.01×** | 0.86× (0.79–1.21, escape) to 1.12× (blocks, wpautop) | 1533 → 1525 MB |
| Gate 1, in the five-way run (300 × 6) | 0.99× | wptexturize 0.86× (0.76–0.99), the rest 0.97–1.05× | 1549 → 1541 MB |
| regex micro (60 × 8) | 1.19× | 1.10× (split) to 1.33× (match_all) | 961 → 997 MB |
| regex micro, in the five-way run (10 × 6) | 0.93× | 0.87× (replace) to 1.00× (match) | 767 → 768 MB |
| curl micro (10 × 6) | 0.98× | 0.91× (setopt) to 1.05× (transfer) | 666 → 665 MB |

The longer runs settle the five-way run's lows: wptexturize is 1.09× (1.08–1.16) over 400 × 8. Ten iterations
don't reach the JVM's steady state. The code is the same on the JVM, so read the spread (0.86–1.33×) as the run's
noise, neither a loss nor a gain.
