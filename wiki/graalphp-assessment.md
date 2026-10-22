# graalphp assessment (2026-10-22)

[graalphp](https://github.com/abertschi/graalphp) is the 2020 ETH Zurich thesis that implemented a PHP subset on Truffle.

**Verdict: a reference, not a base to fork** (decision D3).

## Measured
- **Last commit:** 2020-09-26, on GraalVM/Truffle **20.2**.
- **Language:** about 100 Java files, **6.7k non-blank lines**. The Apache-2.0 language module has ints, floats, booleans, numeric-index arrays (long/object backends via `ArrayLibrary`), functions, local and global variables, arithmetic and comparisons, and while/for/if.
- **Missing:** strings, objects and classes, string-keyed or ordered hash arrays, copy-on-write, closures, exceptions, include, and the standard library. Its built-ins are `min`, `max`, `sqrt`, `hrtime`, `array_fill` and print.
- **Parser:** 61k lines forked from Eclipse PDT's IDE parser (CUP + JFlex), for the **PHP 7.4** grammar, under **EPL-2.0**.
- **Results:** up to 8.6× faster than PHP 7 on Benchmarks Game programs (numeric kernels).

## Why not fork
1. Truffle APIs moved a lot between 20.2 and 25.x: Maven-artifact distribution, the Bytecode DSL, and changes to context and library APIs. Porting would touch nearly every file.
2. It's about 3–4% of the estimated 150–250k-line target, so little would survive.
3. The expensive design decisions are missing: a byte-string model, an ordered hash map with COW, references, and context-independent ASTs for sharing compiled code across requests.
4. The parser is an IDE parser for 7.4 syntax. Plugins use PHP 8 syntax (`match`, enums, `readonly`, named arguments, `?->`, attributes), and an IDE parser isn't php-src-exact.

## What is reused
- The **Benchmarks Game set and method**, as the language-level part of Gate 1.
- The **storage-strategy pattern** for arrays, extended to ordered hash maps.
- For a stopgap parser, D5 chose php-src via php-ast instead.

## Better starting points
- **Truffle's SimpleLanguage** at the current GraalVM version, for API usage.
- **TruffleRuby and GraalPy**, for strategies, COW, context sharing and C-extension bridging.
- **php-src C and its tests** as the spec; **PeachPie** (`Pchp.Library`) and **JPHP** for built-in semantics.
