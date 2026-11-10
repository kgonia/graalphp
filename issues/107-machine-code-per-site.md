# 107: a render's time follows the amount of last-tier machine code: Graal compiles every site of every operation for itself

**State:** Open. Found 2026-11-10 in [wiki/ceiling.md](../wiki/ceiling.md) ("The page: what moves a render"; the
table of every configuration is there and is not repeated here).

**What happens:** a hot render of the front page runs through 31–33 MB of last-tier machine code. Whatever makes
that code smaller makes the render faster, down to about 19 MB, and whatever makes it larger makes the render
slower, also when every operation in it became cheaper measured alone:

| last-tier code compiled | steady render | how |
|---:|---:|---|
| 16.7 MB | 17.2 ms | inlining budgets 1,500 (an eighth of the default) |
| 18.7 MB | 16.9 ms | inlining budgets 3,000 (a quarter) |
| 24–25 MB | 17.5–17.6 ms | inlining budgets 6,000 |
| 31–32 MB | 17.4–18.4 ms | the defaults (12,000) |
| 33 MB | 18.0–18.7 ms | the engine before the ceiling work |
| 43 MB | 20.1 and 23.3 ms | the whole hash walk inline at every element site; inlining budgets 24,000 |

PHP renders the page in 9.75 ms. Transparent huge pages take 6–7% off the engine's render and 1% off PHP's. The VM (WSL2) has no hardware counters:
that the cause is instruction-cache and TLB misses is an inference from these two responses.

**Where the code is:**

- Per site (`bench/code-size`, tier 1, bytes a site): an element read or `isset` 0.8–1.1 KB, `array_key_exists`
  0.9 KB, a string-key write 0.6–1.3 KB, `$a[] = …` about 3.7 KB. php-src has one handler per opcode for all sites;
  its tracing JIT, which adds per-site code, is worth 12% on this page.
- Per function: `WP_Query::get_posts` 492 KB (70,000 IR nodes, none of its 95 calls inlined), `WP_Theme_JSON::sanitize`
  462 KB, `WP_Theme_JSON::get_block_nodes` 335 KB and 350 KB for its loop compiled alone, `WP_Term_Query::get_terms`
  222 KB, `wp_get_layout_style` 185 KB, `wp_render_layout_support_flag` 175 KB, five more compilations of
  140–160 KB.
  `get_posts` is at three quarters of HotSpot's `JVMCINMethodSizeLimit` (640 KB) with ParallelGC and over it with
  G1 (765 KB): there HotSpot refuses to install it and it runs in the interpreter
  ([094](094-l0-red-baseline.md), [100](100-warm-up.md)).
- 60% of the calls in the installed last-tier code are inlined at the default budgets, 51% at half, 37% at a
  quarter, 22% at an eighth: the inlined copies are the largest single part that a setting removes.
- By the collector: G1's barriers make the first last-tier compilations of the functions that a G1 run and a
  ParallelGC run both compile 7% larger (2% at the median; `get_posts` 55%, `WP_Term_Query::get_terms` 52%).

**Candidates,** each to be measured on the page (`tests/gate2/k8-cli.sh one NAME 8000 /` with the compilation
trace, `k8_compcmp.py`), with `memory.peak`, the kernels (`ops.php`, `hot.php`) and a served run before it becomes
a default:

1. **The launcher's defaults**: `-XX:+UseTransparentHugePages` (−6.6% in two runs), lower inlining budgets
   (`polyglot.compiler.InliningExpansionBudget`, `InliningInliningBudget`: −3.2% at 6,000 in two runs, −6.8% at
   3,000 and half the compile time, −5.1% at 1,500, one run each). Huge pages with a quarter of the budgets:
   −11.5% in two runs (16.06 ms, 1.65× PHP); huge pages, half the budgets and no rich sites: −11.1%.
2. **Rich sites** (`LargeCode.RICH`, eight a function): on the page none is 2.8% faster in three runs;
   WordPress's functions alone are slower without them (`hot.php`'s geometric mean 1.62 against 1.55) and the
   element kernels 30–45%. Kept at eight for now. To try: rich forms only for sites inside a loop.
3. **A site's size**: `$a[] = …` (3.7 KB), a string-key write (two `put` calls a statement), `array_key_exists`
   through the shared lookup (0.9 KB, 0.5 KB before `8475ed8c`).
4. **The giant functions**: what in `get_posts`, `sanitize` and `get_block_nodes` makes 150–500 KB (their sites
   by kind from `bench/code-size/marks.py`), and whether code that runs once a render should be compiled in the
   last tier at all.
5. **The collector of the servers.** On the page in one context G1 is 11.5% slower than ParallelGC (20.23 and
   20.22 ms against 18.15; 2416–2512 MB against 2316–2558), and 7.1% with
   `-XX:+UnlockExperimentalVMOptions -XX:JVMCINMethodSizeLimit=4194304` (19.30 and 19.58 ms; 2553–2614 MB), which
   lets `get_posts` install. To measure in a server: ParallelGC at the servers' heap with several contexts, and
   the flag alone.

The lever has an end: from 32 MB to 18.7 MB the render gained 7%, and 16.7 MB gained no more (the calls left
out cost what the code saved). It is not the factor to PHP by itself.
