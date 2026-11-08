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
  - Failing names are a ratchet: a test failing on the build and not on the base is worse, whatever the counts.

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
