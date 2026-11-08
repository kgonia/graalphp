# Checks: one entry point for correctness, compiled code, time and memory

Until 2026-11-08 every dimension of a change had its own tools, output format and base:
- IR budgets in `CompilationTest`, IR attribution in its own tools;
- time in `bench/gate1/ab.py` and `tests/gate2/native.sh`;
- memory in cgroup `memory.peak`;
- correctness in smoke, phpt, the destructor fixtures, the fuzzer and the WordPress PHPUnit suite.

Results went to logs in different places and were put side by side by hand.

Most measurement problems came from the environment, not from the code, and each was found by hand after the fact:
- a Gate 1 on mixed P/E cores;
- two runs of the same script at once;
- a Gate 2 slowed by an unrelated suite.

`bench/check/` wraps the existing tools without replacing them, and adds what was missing:
- a build identified by its commit;
- a preflight that refuses to time a run on a busy or misconfigured host;
- one results store;
- one report that puts every dimension against its base.

## Levels

| Level | What | Time | Runs on |
|---|---|---|---|
| `l0` | unit tests (IR budgets: `CompilationTest`), smoke, destructor fixtures (when the commit has them) | ~10 min | host P-cores; reference PHP in docker |
| `l1` | php-src `.phpt`; with `WP=1` WordPress's PHPUnit suite against its frozen fail-set | ~1 h (+2.5 h) | CPUs 6-13 |
| `l2` | Gate 1 A/B (4 holds of 2 rounds, 400 iterations), Gate 2 (6 rounds of 200 visits, compilations counted), the build and its base interleaved | ~3 h | CPUs 14-21, CPU lock, preflight before every timed run |

```sh
bench/check/check.sh l0 my-branch                 # base: the merge-base with origin/main
CONTROL=1 JAVA_OPTS_REF=-Dphp.rc.destructors=true bench/check/check.sh l2 my-branch --base main
bench/check/check.sh report my-branch             # target/check/report-SHA-BASE.md; exit 1 if something is worse
```

## Pieces

- **The checks' checkout** (`check.sh setup`, `WORK`, default `C:/Users/krzgo/Projects/wordpress-checkhost`): a
  worktree used by nothing else. `setup` copies the data git doesn't hold from the main checkout (`SEED`), once:
  - Gate 1's `bench/gate1/wordpress` and `data`;
  - Gate 2's site (`target/gate2`, `target/ast`);
  - the WordPress suite's `wordpress-develop` and PHP's reference run;

  about 700 MB in all. Every `l0`, `l1` and `l2` first fetches, sets WORK to `TOOLS` (default `origin/main`) and
  runs `check.sh` again from there. The harness scripts are then the same for every agent. Each result records
  their commit (`tools`), and the report flags a build and a base measured with different tools. WORK with changes
  to tracked files stops the run. Until the tools are on main, `TOOLS=origin/checks` runs them from their branch.

- **Builds** (`check.sh build REF`): a detached worktree `target/check/src-SHA` per commit, built once on the
  P-cores. The output goes to `target/classes-SHA` and `target/lib-SHA` (`classes-SHA.commit` holds the full
  commit), names that `bench/gate1/ab.sh` and `bin-php.sh` (`PHP_CLASSES`) already understand. Every timed run uses the
  build's own libraries: Gate 1 through ab.sh's `lib-X`, Gate 2 through `tests/gate2/native.sh`'s `LIB_TAG`. If
  WORK's `native.sh` doesn't have `LIB_TAG` yet and the two builds' libraries differ (a GraalVM or Truffle bump),
  `l2` stops rather than run the new code on the old libraries. L0 and L1 run the
  commit's own tests in its worktree.
- **Preflight** (`preflight.py perf|correct`):
  - `correct` refuses to start beside another check run (`target/check/run.pid`) and beside a timed run (the CPU
    lock). WSL's 24 vCPUs share the 16 E-cores, so a container on CPUs 6-13 slows a run on 14-21 too.
  - `perf` checks, with the lock held:
    - that the WSL VM is on the E-cores only. An unelevated shell can't read the affinity back, so it loads 24
      vCPUs for a few seconds and samples the LPs;
    - that the E-cores are quiet;
    - that memlock has room.
  - Each preflight's sample is stored with the run's results.
- **Results** (`results.py`): one JSON line per measurement in `target/check/results.jsonl`, with sha, level, check,
  metric, value and unit. A ratio carries its base, IQR and rounds; a failure count carries the failing names. The
  parsers read the existing tools' output: smoke, phpt, surefire, the fixtures, wp-phpunit's `compare.py`, Gate 1's
  run files, and Gate 2's run JSON and engine logs.
- **Report** (`report.py SHA BASE`): one table per check, with one verdict per metric.
  - Ratios use the thresholds of [destructors.md](destructors.md): Gate 1 ≤3% per kernel, Gate 2's warm CPU and
    wait ≤5%, memory ≤5%, the cold visit ≤10%.
    - "worse": the median is over the threshold and the IQR's low end is over 1;
    - "unclear": over the threshold but within noise.
  - With `CONTROL=1` Gate 1 also runs the base twice (an A/A arm), and a kernel's threshold widens to the A/A
    difference.
  - Failing names are a ratchet: a test failing on the build and not on the base is worse, whatever the counts. A
    unit failure is named by its method and its message's first line, with numbers as N.
  - Compiled code (`ir`): `CompilationTest` prints every IR budget's size, under the budget or not. Each one is
    worse at more than 8% over the base's, and one over its budget is listed. On 2026-11-08, main (438231d) itself
    was over: `Query::sql` at 25,933 IR nodes against a budget of 24,000, and `large-function.php` too large to
    install.
- **Triage of the WordPress suite** (`wp_triage.py`, in `l1` with `WP=1`): the frozen fail-set comes from one run of
  an older build, so a new difference may come from the build, from an option it ran with, from main or from an
  unstable test. Every one is a bug to fix; the cause says where (a difference from main doesn't block the PR) and
  keeps unstable tests out of the hunt. Only the classes with a new difference run again (a `--filter` of their
  names, the suite entries that hold them: minutes, not a second full run), three times: the same build and
  options, the same build without `JAVA_OPTS`, the base. Each new difference gets one cause:
  - `unstable`: the same build and options, run again, give PHP's outcome;
  - `base`: the base differs from PHP too;
  - `option`: without the options the build gives PHP's outcome;
  - `build`: the build differs without the options too, and the base doesn't;
  - `not rerun`: a rerun has no outcome for it.

  A class run alone leaves out the tests that run before it in the same process. A difference that depends on what
  they leave behind (state, the database, a late destructor that cleans up during the next test) passes there and
  would look `unstable`. Those tests get a second stage: their whole suite entries again, unfiltered, in the same
  three configurations. A chunk is one PHPUnit process in the full run too, so this reproduces the full run's order
  and state. A test stays `unstable` only if that rerun also gives PHP's outcome. Otherwise it gets the cause the
  entry reruns show, with " (in order)" added. `target/check/SHA/wp-triage-classes.tsv` keeps the first stage's
  causes, `wp-triage.tsv` the final ones.

## For agents

The rules for when to run which level, and the host's rules, are in the repository's [CLAUDE.md](../CLAUDE.md),
which every Claude Code session loads. This page describes the tool. When a new kind of check is needed, add it to
`check.sh` and `results.py` (a parser, a level), so it gets the same build, preflight, store and report. A
one-off script would give results that nobody can compare.

## The host (2026-11-08)

The host's CPU is a Core Ultra 9 275HX:
- P-cores: LPs 0, 1, 10-13, 22, 23 (`/affinity C03C03`);
- E-cores: LPs 2-9 and 14-21.

The owner set the WSL VM onto the E-cores (`(Get-Process vmmemWSL).ProcessorAffinity = 0x3FC3FC`, elevated). The
setting is lost on `wsl --shutdown` or a reboot, and preflight `perf` catches that. `bench/perf-step4/lpload.cmd`'s
`C03FFF` includes LPs 2-9, which are E-cores.

What the first A/A arm showed (PR #57's Gate 1, 8 rounds, main against itself): kernels differed by up to 9%
(slugs 0.91, IQR 0.90–0.92), partly by their position in the order. A Gate 1 difference under ~5% isn't a finding
without a control arm.
