# Wiki: WordPress on GraalVM (Truffle PHP)

**Goal:** GraalVM makes PHP code itself faster. WordPress and its plugins run unmodified on a PHP implementation written as a Truffle language, and beat PHP 8.4 with OPcache JIT. See [decisions.md](decisions.md), D1.

## Pages

| Page | What's in it |
|---|---|
| [retro.md](retro.md) | Lessons from once-campfire-java and stable-diffusion.cpp, and the standing rules for this project |
| [plan.md](plan.md) | Goal, precedents (graalphp, HHVM, PeachPie, TruffleRuby), architecture, size estimate, gates 0–4, risks |
| [decisions.md](decisions.md) | Numbered decisions with the reason for each |
| [graalphp-assessment.md](graalphp-assessment.md) | Why graalphp is a reference, not a base to fork |
| [gate-1.md](gate-1.md) | Gate 1 spec, method, go/no-go criteria and status log |
| [gate-2.md](gate-2.md) | Gate 2 probe: syntax and built-in gaps of WordPress core |
| [first-principles.md](first-principles.md) | What graalphp solved and how; the int array strategy and the "cold path = deopt" rule; K0 arithmetic kernels; php-src conformance baseline |
| [../issues/README.md](../issues/README.md) | One file per finding or problem |

## Status

| Gate | State |
|---|---|
| Gate 0: Amdahl ceiling of WordPress on php-fpm | not started (can run in parallel with Gate 1) |
| **Gate 0.5: idiomatic Truffle structure ([D10](decisions.md))** | **done: steps 1–4 landed (per-context state; hot built-ins as DSL nodes; core nodes on the DSL; context-independent ASTs and `ContextPolicy.SHARED`, with class caches on language-level keys and frozen shared arrays). Step 4 cost 5–10% in a single context (hooks, wptexturize), spread over its commits at 1–3% each; server mode (2 contexts) is within 0–20% of single** |
| **Gate 1: Truffle core vs PHP 8.5 JIT on extracted WordPress hot functions** | **in progress: all 7 kernels correct; steady state 0.70× of PHP 8.5 JIT end to end, 0.58× on PHP code (go needs 2×; exact regex time from a C replay); plateau after 250–300 iterations (3–7 s); K0 arithmetic kernels 1.96×; arrays in strategy stores, hashes built at size, int-key hash reads inline (against 2026-10-25: kses about 0.9×, blocks about 0.85×, hooks about 0.9×, escape and slugs about 0.85×); TRegex beside PCRE2, chosen per pattern ([D12](decisions.md), planned), would take kses's regex time to 0.68× and slugs' to 0.31× (simulated); next: computed string keys, copy-on-write copies, a quiet-window re-measurement** |
| Gate 2: WordPress boots and renders ([gate-2.md](gate-2.md)) | in progress: `wp-load.php` boots in CLI; over HTTP 7 of 9 pages byte-identical to PHP (the other two miss only the oEmbed link, `SimpleXMLElement`); code review fixed; PHP's error model, so WordPress's fatal error page matches too; its idiomaticity review fixed (php-src `.phpt` 39.9% strict); the installer's pages and database identical to PHP's; the wp-admin login and 4 of 7 wp-admin pages identical (the others: GD, sockets); next: sockets and curl ([D13](decisions.md)), Closure, arithmetic TypeErrors ([D11](decisions.md)) |
| Gate 3: drop-in parity | — |
| Gate 4: performance and publication | — |

## Key numbers (2026-10-22)
- **WordPress core:** 625k lines of PHP, calling **677 distinct built-ins** (about 570 needed, since `sodium_*` has a PHP polyfill) in 31.8k call sites.
- **php-src:** about 700k lines of its own C (1.86M with bundled libraries and the generated VM), 1,923 built-in functions and 23,640 `.phpt` tests.
- **Estimated Truffle PHP for WordPress and the top plugins:** 150–250k lines of Java; Gate 1 needs about 15–25k.
