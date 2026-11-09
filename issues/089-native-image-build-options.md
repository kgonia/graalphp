# 089: The native image is built without PGO, G1 or a CPU target, and starts cold

**State:** Open, to measure. Written 2026-11-09, after FastCgiMain's native image (`target/native/wp-fcgi`) was
built for the deployment's measurements. Deliberately after the JIT's own problems (splitting, call costs,
compilation that never settles): these options change how the runtime's Java code is compiled ahead of time, not
the code Truffle compiles from PHP.

**What the native image is now:** `bench/native/image.sh` runs `native-image` with the defaults of Oracle GraalVM
25.4.4.1.1: serial GC, no profile, `-O2`, the generic x86-64 target. Against the JVM, on Gate 2 (2026-10-31,
wiki/gate-2.md) it serves the cold visit 1.9x faster with a third of the CPU and 0.72x the memory, but warm it is
0.72x the JVM's speed and spends 2.1x its CPU per visit. Under WooCommerce's load (2026-11-02,
wiki/woocommerce.md) it took 0.8 orders/s, against the JVM's 2.1 and php-fpm's 7.6: its throughput fell after 4-8
users and its latency grew linearly with them.

**Options to measure, one variable at a time, on Gate 2's visits and `native.sh load`:**

| option | what it changes | expected | why |
|---|---|---|---|
| `--gc=G1` | the GC: G1's concurrent, parallel collector instead of the serial one, which runs in the requests' threads | throughput under load, tail latency | the serial GC stops every request thread for each collection; the WooCommerce load's falling throughput |
| `--pgo-instrument`, then `--pgo=default.iprof` | profile-guided AOT compilation of the runtime's Java: the bytecode interpreter, the built-ins (strings, arrays, PCRE's FFM calls, mysqli), the Graal compiler inside the image, the GC | 10-30% of the warm CPU per visit; faster compilation | WordPress's flat profile runs much of each request in the interpreter and the built-ins, which the JVM compiles with its own run-time profile and the image without one |
| `-march=native` | the CPU target: AVX2 and the rest of the host's instructions in the AOT code | a few % | free on a known host; a server image would name its fleet's CPU (`-march=x86-64-v3`) |
| Truffle's auxiliary engine cache (`--engine.CacheStore`/`CacheLoad`, Oracle GraalVM, native image only) | the warm engine stored: parsed and translated files, compiled PHP code; a new process loads it | the cold visit, a deploy's warm-up | Gate 2's cold visit is 13 s on the JVM today (2026-11-09), and a restart starts cold (wiki/plan.md's open question on persisted profiles) |

**GraalVM 25.5** (wiki/graalvm-25-5.md, wiki/roadmap.md step 7) moves the auxiliary engine cache into Community
Edition with Native Image's auxiliary images (a training run stores bytecode, compiled code and a pre-initialized
context), and adds an unboxed calling convention for compiled Truffle-to-Truffle calls: the upgrade goes before the
engine cache's probe, as its own PR.

**Profile for PGO:** the profiled run should be the deployment's own traffic: Gate 2's visits and the k6 load
(readers and the admin) through nginx (`native.sh measure` and `load` on a `native-fcgi` built with
`--pgo-instrument`). A profile of the kernels alone would optimize what WordPress doesn't spend its time in.

**What none of them changes:** the code Truffle's JIT makes from PHP. The gap to php-fpm under load is 4x the CPU
per page (wiki/woocommerce.md), a quarter of it the compiler threads' and most of the rest the requests' compiled
and interpreted PHP. The best these options can do is bring the native image to the JVM's warm speed with its lower
memory; beating PHP needs the JIT's fixes first.

**Fix:** to do. For each option, the image built (`bench/native/build.sh` with `NI_OPTS`), then
`tests/gate2/native.sh measure` and `load` against the default build, interleaved, with time and memory
(memory.peak, RssAnon) per run. Options that win go into `bench/native/image.sh`.

**Test:** the measurements; L1's native image build (bench/check) keeps the image building.
