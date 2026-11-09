# 083: Language-level state grows with every request that evaluates code or declares names

**State:** Open. Found 2026-11-09 by the concurrency reviews of the fiber work (031: Codex, Opus, Fable;
reports in `issues/031-reviews/`); the growth confirmed by Opus (`issues/031-reviews/repro/Heap.java`), its
holders reasoned.

**Symptom:** on one engine shared by many contexts (`HttpMain`), the live heap after a GC grows with the number of
requests served:

| Script, 40 requests on one engine | Live heap, first to 40th request |
|---|---|
| 500 `eval`'d class declarations, the same code every request | 17 MB to 49 MB, about 1.1 MB per request |
| `eval` of unique literals | 9 MB to 14 MB |
| plain `eval` | 7 MB, flat |

php-fpm frees all of a request's compiled code at its end.

**Cause (suspected, not proven for each):**
- `eval`'d code isn't cached (`SourceFiles.java:71-102`), so each request translates it again and interns new
  `ClassKey`s (`PhpLanguage.classKeys`, `PhpLanguage.java:134`). A `ClassKey.Id` holds its `ClassTemplate`
  (`PhpClass.java:1134`), which holds the eval'd AST.
- New literals go into `literals`/`literalsByKey` (`PhpLanguage.java:136-142`).
- A file rewritten while the server runs keeps its old templates the same way.
- Names declared at run time (`define()` with request-specific names) get language-wide ids that are never freed
  (`NameIds`). Each context's dense `constantSlots` (`PhpContext.java:736`) then grows to the largest id ever given,
  however few names this request defines.

**Fix:** to do. Intern class keys and literals only from cached translations, or hold them weakly. Keep names
declared at run time out of the dense ids: a per-context map, or ids reclaimed with the context.

**Test:** none yet (`issues/031-reviews/repro/Heap.java` with `evalclass2.php`).
