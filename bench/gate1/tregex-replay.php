<?php
// Replays target/compare/pcre.rec through PCRE2 and TRegex (TRegexReplay). Arguments: passes, flavor (Python or
// JavaUtilPattern), more TRegex options (e.g. MaxDFASize=8000), mode (full, boolean, two_step), pattern ids, and a
// file of rewrites (TRegex sources for some patterns, still checked against PCRE2's matches).
echo truffle_tregex_replay("/w/target/compare/pcre.rec", (int) ($argv[1] ?? 20), $argv[2] ?? "Python",
    $argv[3] ?? "", $argv[4] ?? "", $argv[5] ?? "", $argv[6] ?? "");
