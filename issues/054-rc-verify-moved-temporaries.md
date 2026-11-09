# 054: `-Dphp.rc.verify` reports moved arguments as reachable from the caller's temporary

**State:** Open (check tool). Found 2026-11-09: the fuzzer (seed 1000, 1000 programs, destructors and verify at every
zero) gives 51 `rc-verify` reports. The outputs match PHP's, and 1183d27 reports the same, so wiki/destructors.md's
"0 reports" for that run is wrong.

**Symptom:** `$v1->take(new D('o7'))` with `function take($x) { $l = $x; return 1; }`. D reaches zero in take's
epilogue, at php-src's moment (`~o7` where PHP prints it), and verify reports `D #7 reached zero but is reachable
via p1035.php local 1`.

**Cause:** the argument was moved into the call (`StoreTemp`, `Refcount.moved`). The caller's hidden temporary
still holds the value without a count until `ReleaseTemp` clears it after the call. The census counts that slot as
a holder.

**Fix:** to do. Either the census skips a temporary whose value moved into a running call, or the move clears the
slot (free in compiled code, where the frame is virtual). Until then, a report from a hidden local (`local N` with
no variable name) during a call needs checking by hand. A report from a named variable is still a bug.
