# 109: L1's WordPress suite is "worse" on main: `tests/template.php` ends in "Requested array size exceeds VM limit"

**State:** Open. Written 2026-11-10 from the L1 of PR #61 (`WP=1 bench/check/check.sh l1 38a4acfe`, base main
`8113e1c`). The cause is on main, so every L1 with `WP=1` gives this verdict until it is fixed, and a "worse"
verdict blocks a merge ([CLAUDE.md](../CLAUDE.md)). Nothing is fixed here: this records the run, what its 30 new
differences are, and what is known about the one that is a regression.

Where the results are (the checks' checkout, `wordpress-checkhost/target`):
- `check/report-38a4acf-8113e1c.md`: the report;
- `check/38a4acf/`: `wp.txt` and `wp-report.txt` (the comparison with PHP), `wp-triage.tsv` (a cause per test),
  `phpt.txt`, `native.txt`;
- `wp-phpunit/runs/truffle-38a4acf-chunked`: the suite's full run; `truffle-38a4acf-again` and
  `truffle-38a4acf-base-8113e1c`: the triage's reruns on the PR's build and on main's.

## The run

2026-11-10, started between 14:13 and 14:29, ended 18:54. The suite's full run took 14,098 s (12,915 s on
2026-11-09), the triage seven minutes. **2 ok, 1 worse.**

| check | result | verdict |
|---|---|---|
| fixtures (from L0) | 0 lines matching neither PHP | ok |
| native | the image builds, 104.8 MB | ok |
| phpt | 5,714 passing strict, 5,826 lenient, 1,627 failing (no base value: a count, not a comparison) | |
| wp-phpunit | 28,049 tests with PHP's outcome, 6 now agreeing, 30 newly differing | worse |
| wp-phpunit triage | `base` 1, `not rerun` 29 | |

The suite as a whole: 29,710 tests ran (PHP: 30,635), 27,894 pass, 1,015 error, 114 failure, 631 skip, 47 warning,
3 crash, 6 timeout. 1,606 tests that ran on both differ from PHP, and 980 of PHP's have no outcome on ours. The
frozen fail-set (`tests/wp-phpunit/wordpress.failset.tsv`) holds all of these but 30.

The 30:

| tests | PHP → ours | what it is |
|---|---|---|
| 27 `Tests_Template::*` | pass → missing | the regression below: the file's process ends before them, on main's build too |
| 2 `Tests_Filesystem_UnzipFilePclzip::*` | pass → crash | standing since 2026-11-09 (below) |
| `PHPUnit\Framework\DataProviderTestSuite` | skip → missing | standing since 2026-11-09 (below) |

None of the 30 is traced to PR #61's commits. The 6 now agreeing:
`Tests_Admin_Includes_Template_GetMediaStates::test_header_image_state_with_random_header_images`, two
`Tests_Admin_WpAutomaticUpdater::test_is_allowed_dir_*` with `open_basedir`, two
`Tests_Filesystem_UnzipFileZiparchive::*` (skipped as in PHP),
`Tests_Secrets_WPSecret::test_destructing_the_last_reference_removes_it_from_the_vault`.

## The regression: `tests/phpunit/tests/template.php`

**Symptom.** 60 of the file's 87 tests get an outcome: 25 pass, 35 error with
`Error: Undefined constant "PHP_OUTPUT_HANDLER_CLEAN"`. After the outcome of
`test_wp_finalize_template_enhancement_output_buffer_with_errors_while_processing` with data set
"notice_without_display_errors_or_log_errors" the process ends:

```text
PHP Fatal error:  Requested array size exceeds VM limit        (exit 255)
```

No test had started (`run.sh`'s "#start" line), so the runner records no crash and has no test to leave out of a
second process: the 27 tests after it have no outcome.
- 20 of them passed on 2026-11-09: 14 data sets of `test_wp_hoist_late_printed_styles`, 5 of
  `test_wp_load_classic_theme_block_styles_on_demand`, and
  `test_wp_load_classic_theme_block_styles_on_demand_in_block_theme`;
- 7 are in the fail-set as `pass error`: the `*_without_display_errors_or_log_errors` data sets of the test above.

**It is main's.** The triage ran the file again on the PR's build and on main's (`8113e1c`): the same 60 outcomes,
name by name, and the same error. Before main's PRs #57–#60 it ran to its end: at `8e06b64` (the L1 of 2026-11-09,
base `438231d`) the file gave all 87 outcomes, 45 pass and 42 error. The 42 are the fail-set's `Tests_Template`
lines.

| run | build | outcomes | memory.peak |
|---|---|---|---:|
| L1 of 2026-11-09, full run | `8e06b64` | 87 (45 pass, 42 error) | 1.35 GB |
| L1 of 2026-11-10, full run | `38a4acf` | 60 (25 pass, 35 error), then the error | 2.94 GB |
| its triage, the build again | `38a4acf` | the same 60 | 2.96 GB |
| its triage, the base | `8113e1c` | the same 60 | 3.62 GB |
| by hand, three runs | `38a4acf` | 25 pass, 35 error, then the error | 3.06 and 3.09 GB (two measured) |

The container has 6 GB, the JVM's heap 3 GB (`WP_PHPUNIT_XMX`).

**The allocation that fails** (the JVM's `-Xlog:exceptions=info`, a run by hand on `38a4acf`):

```text
java.lang.OutOfMemoryError: Requested array size exceeds VM limit
  java.util.Arrays.copyOf(byte[], int)
  pl.codeloom.php.runtime.ByteBuilder.ensure(int)
  ByteBuilder.write(byte[], int, int)
  ByteBuilder.write(byte[])
  pl.codeloom.php.PhpContext.write(TruffleString, Node)     target.bytes.write(...): an output buffer's bytes
  pl.codeloom.php.nodes.stmt.BasicStmts.EchoNode.echo
  pl.codeloom.php.bytecode.PhpBytecodeRootNode.Print        an echo or print statement
```

An `echo` or `print` writes into an output buffer (`ob_start`), and the buffer's growth asks for an array above
HotSpot's limit (about 2^31 bytes). `ensure` asks for `max(count + extra, 2 * buf.length)`, so either the buffer
holds about a gigabyte already or the string written is that long. The process's peak agrees: 1.6–2.3 GB more
than when the file ran to its end.

The last exception before it, 0.19 s earlier, is an `Undefined constant "PHP_OUTPUT_HANDLER_CLEAN"`, the error of
the 35 tests. WordPress's handler of the template enhancement buffer reads that constant on every call
(`wp_finalize_template_enhancement_output_buffer`, `wp-includes/template.php`:
`if ( ( $phase & PHP_OUTPUT_HANDLER_CLEAN ) !== 0 )`), and the engine's constant table (`Builtins.java`) has only
`PHP_OUTPUT_HANDLER_STDFLAGS`, `_CLEANABLE`, `_FLUSHABLE` and `_REMOVABLE`. So in each of these tests an output
handler throws. That alone is the fail-set's 42 errors, old and separate from the growth; the growth is what is
new.

**Not known:**
- which PHP statement writes, and into which buffer (`PHP_TRACE=1` prints no PHP frames for this error);
- how the buffer comes to a gigabyte: one long write, or bytes kept or copied from test to test;
- which commit of main brought it. Suspect, not checked: `f1d48fe1` "Output buffers: a "handler running" state and
  a disabled handler" ([055](055-output-handler-state.md), PR #58), which changed what happens to a buffer whose
  handler throws: the handler is disabled, its bytes go to the buffer below, the exception is raised after the
  operation. No build between `438231d` and `8113e1c` was run.

One thing tried: a buffer whose handler throws, inside a second buffer, closed the way PHPUnit 9 closes a test's
buffers, gives PHP's output on the build with no growth. A throwing handler by itself is not enough; the suite's
sequence matters.

**Reproduce** (a minute; 6 GB under memlock). The triage's reruns are this chunk with a `--filter`; the runs by
hand started the chunk's container themselves, with their own tree and database, so this line is not run as
written:

```sh
CHUNKED=1 CHUNKS='tests/template\.php$' RUN=tpl tests/wp-phpunit/run.sh truffle
```

The Java frames need `JAVA_OPTS=-Xlog:exceptions=info:file=/tmp/exc.log::filecount=1,filesize=300m` and the file
copied out of the container before it exits (`run.sh` removes the container).

## The three standing differences

They were "newly differing" in the L1 of 2026-11-09 too (`report-8e06b64-438231d.md`: 12 newly differing, these
three among them), so they are in every L1's count:

- **`Tests_Filesystem_UnzipFilePclzip::test_should_apply_pre_unzip_file_filters` and
  `::test_should_apply_unzip_file_filters`.** PclZip finds no `gzopen` and ends the process with
  `Abort class-pclzip.php : Missing zlib extensions` (exit 0). The fail-set has
  both as `pass missing`; the runner records a test that ends its process as `crash`, and the comparison takes
  `missing` and `crash` for two outcomes.
- **`PHPUnit\Framework\DataProviderTestSuite`.** Not a test's name: PHP's run records a skipped data-provider
  suite under it, ours has no line of that name. Which suite, and what our run does with it, is not looked up.

## What the run showed about the checks

- **Triage: `not rerun` for a process that ends early.** `wp_triage.py` gives a cause from a test's outcomes in
  the reruns. A test no rerun reaches has none, so 29 of 30 got `not rerun`, though the reruns answer the
  question: the build's and the base's end after the same test with the same error. Read by hand here (`cut -f1,2`
  of the two reruns' `outcomes.tsv`, no difference).
- **Runner: a process that ends between two tests is not resumed.** `run.sh` runs a process again without the test
  that ended it; with no test started there is none to leave out, and the rest of the chunk is lost.
- **Comparison: `missing` against `crash`, and a name that is no test** (above).

## Fix

1. The growth. Run the chunk on main's commits between `438231d` and `8113e1c` (`f1d48fe1` and its parent first)
   to find the commit; then the PHP statement: `ByteBuilder.ensure` reports the PHP stack once a buffer passes a
   size no test needs. Fix the cause in the output buffers, with a smoke test of the sequence that grows.
2. The constants: export the phases the engine already passes to a handler (`PhpContext`'s
   `PHP_OUTPUT_HANDLER_*` values) as PHP constants, checked against PHP 8.5's list. Do this after 1 or keep a test
   with a throwing handler: with the constant defined the handlers stop throwing, and the growth may stop showing
   without being fixed.
3. The triage: a chunk whose process ends before a test in the build's rerun and in the base's gets `base`; in the
   build's only, `build`.
4. The runner: a process that ends with no test started runs again from the test after the last outcome.
5. The standing three: `gzopen` and the zlib functions PclZip uses, or the two lines frozen as `pass crash`; the
   generic name left out of the comparison, or its suite found.

**Test:** `tests/phpunit/tests/template.php` gives 87 outcomes under L1 with `WP=1`, and main's own L1 reports 0
newly differing.
