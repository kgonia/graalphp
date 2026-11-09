# 094: L0 is red on main: four smoke tests fail on the harness, two CompilationTest cases on budgets

**State:** Open. Written 2026-11-09 from bench/check's results (wordpress-checkhost/target/check/*/smoke.txt and
unit.txt) and the `fastcgi` branch's own runs: the same failures on every checked commit since c0e2f7f (PR #59's
merge) through main's 8113e1c and `fastcgi`. While they stay red, a new failure in those tests passes unnoticed,
and a PR's L0 can't be green.

**What fails, and why:**

| test | cause | issue |
|---|---|---|
| smoke `flock-dropped-handle` | runtime: a dropped handle's lock | 065 |
| smoke `lazy-objects`, `property-hooks` | runtime: object ids not reused (`#5` for PHP's `#4`) | 079 |
| smoke `named-args-assert` | runtime: assert() compiled out | 030 |
| smoke `blocks`, `blocks-corpus` | harness: they require `bench/gate1/wordpress/wp-includes/class-wp-block-parser-block.php`, which a worktree hasn't (WordPress is downloaded by Gate 1's setup); both PHP and Truffle PHP fail to open it, with different paths | this |
| smoke `closure-debug`, `closure-scope` | harness: a closure's name and `__FILE__` hold the script's path, `/w/tests/smoke/...` in PHP's container, `C:\Users\...\tests\smoke\...` here; no `.filter` file maps them | this |
| CompilationTest `large-function` | an OSR compilation of the file's loop deoptimizes (uncommon trap) after it is installed: "failed compilations or performance warnings" | this |
| CompilationTest `query-builder` | `Query::sql` compiles to 27.2k IR nodes, its budget 24k (27248 on 8113e1c's runtime, 27232 after 090) | this |

**Fix:**
- `blocks`, `blocks-corpus`: run.sh fetches Gate 1's WordPress (or the two tests take their fixture from
  `target/gate2/wordpress`), or skip with a message when it is absent instead of failing.
- `closure-debug`, `closure-scope`: a `.filter` (as `var-dump-debuginfo-recursion.filter`) replacing the script's
  directory by `DIR` in both outputs, the lengths in `string(N)` recomputed.
- CompilationTest: find which merge raised `Query::sql` above its budget and what the OSR deopt's trap is (the
  test's log names it); fix the code, or set the budget from a measured reason, not to make it pass.

**Test:** bench/check L0 green on main except the runtime issues listed (065, 079, 030), then green when they are
fixed.
