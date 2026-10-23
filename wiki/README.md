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
| [first-principles.md](first-principles.md) | What graalphp solved and how; the int array strategy and the "cold path = deopt" rule; K0 arithmetic kernels; php-src conformance baseline |
| [../issues/README.md](../issues/README.md) | One file per finding or problem |

## Status

| Gate | State |
|---|---|
| Gate 0: Amdahl ceiling of WordPress on php-fpm | not started (can run in parallel with Gate 1) |
| **Gate 1: Truffle core vs PHP 8.5 JIT on extracted WordPress hot functions** | **in progress: all 7 kernels correct; 0.70× of PHP 8.5 JIT end to end (go needs 2× on PHP code); K0 arithmetic kernels 1.96× geomean; php-src tests 27.8% passing (2,043 / 7,341); PHP-code metric for regex kernels needs a C replay of PCRE calls** |
| Gate 2: WordPress boots and renders | — |
| Gate 3: drop-in parity | — |
| Gate 4: performance and publication | — |

## Key numbers (2026-10-22)
- **WordPress core:** 625k lines of PHP, calling **677 distinct built-ins** (about 570 needed, since `sodium_*` has a PHP polyfill) in 31.8k call sites.
- **php-src:** about 700k lines of its own C (1.86M with bundled libraries and the generated VM), 1,923 built-in functions and 23,640 `.phpt` tests.
- **Estimated Truffle PHP for WordPress and the top plugins:** 150–250k lines of Java; Gate 1 needs about 15–25k.
