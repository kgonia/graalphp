# Issues

One file per finding or problem, named `NNN-short-name.md`. Each records the symptom, the cause, the fix (commit), and the state: Open, Fixed, Won't fix or Decided.

| # | Title | State |
|---|---|---|
| [001](001-dynamic-call-by-ref-args.md) | Dynamic calls don't pass arguments by reference | Open |
| [002](002-by-ref-magic-get.md) | `&__get` and `&offsetGet` aren't written through | Open |
| [003](003-class-early-binding.md) | Top-level classes aren't bound early | Open |
| [004](004-generator-resume-reevaluates.md) | A resumed generator evaluates the yielding statement again | Open |
| [005](005-destructors.md) | Destructors never run | Open |
| [006](006-compile-time-jump-checks.md) | Compile-time errors for jumps and try aren't raised | Open |
| [007](007-goto-over-try-file-level.md) | A goto over a try at file level escapes as a Java exception | Open |
| [008](008-builtin-arity.md) | Built-in functions don't check their argument count | Open |
| [009](009-member-call-on-null-message.md) | "Call to a member function" names the type and the method wrongly | Open |
| [010](010-foreach-ref-unshift.md) | A foreach by reference loops forever after array_unshift | Open |
