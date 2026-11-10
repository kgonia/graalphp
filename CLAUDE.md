# Truffle PHP for WordPress

The goal: PHP run by this interpreter gives exactly the results PHP 8.5 gives, and the implementation is fast and
memory-efficient. A difference from PHP is a bug, whatever caused it.

## Checks

Every claim about a change (it works, it is as fast, it uses no more memory, it compiles to no more IR) comes from
`bench/check/check.sh`, the one entry point for checks. Its levels, the report and the triage are in
[wiki/checks.md](wiki/checks.md): read it before the first check you run.

| When | Run |
|---|---|
| a commit you push | `bench/check/check.sh l0 REF` |
| before a PR | `l1 REF`; `WP=1` too when the change touches runtime semantics (objects, references, arrays, built-ins) |
| performance, memory or compiled code in question | `CONTROL=1 bench/check/check.sh l2 REF` |
| a PR's description | the report, `target/check/report-SHA-BASE.md` |

- Checks run in their own checkout, `C:/Users/krzgo/Projects/wordpress-checkhost` (made once by
  `bench/check/check.sh setup`), which every run sets to origin/main's tools: every agent measures with the same
  scripts, and each result records their commit. That checkout belongs to `check.sh`; changes to the tools go
  through a branch and a PR.
- The base is REF's merge-base with origin/main unless `--base` names another. An option goes to a build through
  `JAVA_OPTS_REF` (l2) or `JAVA_OPTS` (l1).
- A "worse" verdict blocks the merge. Fix it, or name it in the PR as the owner's decision.
- The WordPress suite's triage gives each new difference a cause, and the cause says where to fix it: `base` on
  main, `option` and `build` in the PR. `unstable` goes into the fail-set only after the triage's second stage has
  kept it unstable.

## The host

The host is shared with other sessions. Wait for a lock rather than work around it.
- **CPUs 14-21 are for timed runs only.** A timed run holds the CPU lock (`C:/Users/krzgo/Projects/.cpu-14-21.lock`),
  and `check.sh l2` takes it per run. While anyone holds it, the WSL VM stays idle: no containers, builds or
  benchmarks. The VM's 24 vCPUs share the 16 E-cores, so a container on CPUs 6-13 slows a timed run too.
- **Host-side work runs on the P-cores** (`start /affinity C03C03`; `check.sh` does it for its builds and tests).
- **Memory comes from memlock** before any heavy job, and a refused reservation stops the job: `python
  C:/Users/krzgo/Projects/memory-lock/memlock.py acquire NAME --gb N --wait --ttl MIN`, then `release NAME`.
- **The WSL VM runs on the E-cores only**, an owner's setting lost on a reboot or `wsl --shutdown`. When `check.sh`'s
  preflight reports it, ask the owner to set it again (elevated PowerShell:
  `(Get-Process vmmemWSL).ProcessorAffinity = 0x3FC3FC`).
- **One check run at a time** per checkout (`target/check/run.pid`). An `l2` run that stops keeps its finished
  rounds: the same command continues it.

## The repository

The main checkout (`C:/Users/krzgo/Projects/wordpress`) is shared. Work in a worktree on a branch of your own. In
the main checkout, look at `git status` and for `.git/MERGE_HEAD` before any commit. Findings go into `wiki/` and
`issues/` (one `NNN-name.md` per issue, with a row in `issues/README.md`).
