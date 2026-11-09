# 079: Object ids are never reused

**State:** Open. Found 2026-11-09 while fixing the implicit state machines (055-071); confirmed on PHP 8.5 and on issues-state-machines 5fa1ee2.

**Symptom:** `$o = new stdClass; unset($o); $p = new stdClass; var_dump(spl_object_id($p));`: PHP gives `int(1)`, the
freed object's handle. Here it gives `int(2)`, with destructors on too. `var_dump`'s `#N`, `spl_object_id` and
`spl_object_hash` show it. Smoke tests filter the ids out (`var-dump-debuginfo-recursion.filter`), and
`property-hooks` fails on it.

**Cause:** `PhpContext.nextObjectId` only counts up. php-src's object store reuses the handle of a freed object
(`objects_store.free_list_head`), last freed first.

**Fix:** to do. With reference counts (005), an object freed at zero gives its id back to a per-context free list,
taken last-in first-out as php-src's. With destructors off, objects aren't freed before request end, so there is
nothing to reuse.

**Test:** none yet.
