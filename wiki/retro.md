# Retro: what once-campfire-java and stable-diffusion.cpp teach

Written 2026-10-22. Sources:
- the claude.ai conversation "PHP on JVM with WordPress" (2026-10-22);
- the Claude Code transcripts of `once-campfire-java` (Rails → Java/Quarkus, 2026-10-16 → 10-21);
- the Claude Code transcripts of `stable-diffusion.cpp` (C++ engine → browser WASM/WebGPU, desktop, Android GPU/NPU, 2026-09-25 → 10-22);
- the memory notes of both projects.

---

## 1. Retro: what the two earlier migrations teach

### once-campfire-java (Rails → Java 25 + Quarkus): about two days to a port that beat Rust

**What worked**

| Practice | Evidence |
|---|---|
| **A running reference as the oracle.** The Rust image and the Rails container ran next to the port, and every change was diffed against them. | 125-case page parity, plain and gzip, under 7 user agents |
| **Golden vectors before moving on.** Each compatibility layer had to pass the reference's own vectors first. | Crypto passed all Rails vectors; the rich-text corpus went from 564/657 to all; the UA parser passed 385/385 |
| **Write parity, not just page parity.** The same forms were posted to both apps on fresh seeds, and the resulting DB rows were diffed. | Found 4 real bugs that page parity missed, e.g. query params lost on `_method` forms |
| **Coverage-driven dead-code triage**, with three classes: called by the framework (keep), replaced by the JDK or a library (delete), caller never ported (a **missing feature**). | About 75 members deleted; the startup membership reset was wired up |
| **Measured pilots before adopting a library.** jOOQ execution cost 6–10 µs per query on SQLite, so jOOQ was kept as a render-once SQL builder only. | README section "Idiomatic approach and exceptions" |
| **Profile, then fix.** JFR and async-profiler were used before any optimization. | +20% on reads. The WAL `RESTART` → `TRUNCATE` bug was found by timing the write lock. Uncached pages +53–89% from pre-compressed head fragments |
| **Parallel forks in worktrees**, one subsystem each. The main agent merged each fork and reran all checks after every merge. | UA rewrite: 1,185 → 320 lines. Storage: −3,500. Integrations: −900 |
| **Side-agent "heads up" notes** acted as an independent reviewer. | Caught untested native/AOT images, unbenchmarked merges, and gzip size skew |
| **An honest, reproducible publication.** Performance is the second README section, followed by "where Java falls behind", known differences and the scripts in the repo. | The EC2 c7a.4xlarge run and franz1981's bench with std and Welch's t-test |

**What went wrong (cost real time)**

1. **The goal drifted.** The agent chose byte-identity with Rust implicitly. A day later the user learned that Rust isn't byte-identical with Rails either, and the goal became "drop-in for Rails data and cookies, behaviour like Rails". About 450 lines of Rack/Thruster emulation existed only for the old goal.
2. **Transliteration first, idiom later.** The first draft read like Rust/Rails in Java (a `Pipeline` of before-actions, `buttonTo` helpers, static `Boot`). The user had to step in: "should have feeling of reading modern java". A large reshape followed.
3. **Hand-rolled code where libraries existed:** an HTML scanner next to jsoup, P-256 math, a MIME table, a router next to Vert.x. The user asked for a ponytail review, which became a standing rule.
4. **Build variants went stale.** The native and AOT images weren't rebuilt for hours after jOOQ, Agroal, epoll and Tika landed. A side agent caught it.
5. **Merges landed without a per-step benchmark.** The fix was to tag one image per merge step.
6. **Noisy hosts produced unusable benchmark runs:** forks building, a Vite server, another session's WSL jobs, WSL mirrored networking hanging `curl`. Two of five reps differed by up to 70% under WSL. After pinning and the performance governor, the spread was 0.6–1.3%.
7. **Subagents hit turn limits mid-task.** One fork's worktree ended up inside a submodule.
8. **Windows friction ate many turns:** CRLF, bash escaping, `pkill` not seeing Windows Java, Python `write_text` adding CRLF, IPv6 `localhost`.
9. **Answering from memory or from summaries.** One claim came from a small model's README summary; another was a guessed GitHub URL. Both were caught later.
10. **Over-adopting side-agent ideas.** The agent picked up "mixed mode" until the user said to use the harness defaults.

### stable-diffusion.cpp (C++ → WASM/WebGPU, desktop, Android, NPU): about four weeks, many targets

**What worked**
- **An `issues/` directory with one `.md` per finding, plus a wiki and a decisions log.** The user asked for it on day 1, and it kept dozens of parallel threads recoverable.
- **Search for existing solutions before writing your own.** Cherry-picking from forks and PRs (happyyzy's fork, leejet PR #1970, local-dream's ggml patch) beat solo engineering.
- **Ask whether we're near the ceiling, not just whether it got faster.** Measured peak TFLOPS gave every kernel a target, e.g. Q4_K MMQ on D3D12 went from 2.5 to 14 TFLOPS.
- **Benchmarks pinned to commit and build** (`bench/bench.py`): the same commit without `GGML_CPU_ARM_ARCH` was 2× slower on the phone.
- **Cross-model review of plans** (an Opus subagent plus Codex CLI) before big bets.
- **Commit after each verified unit**, with explicit paths.
- **Telemetry from real devices** (opt-in logs streamed to the server) instead of guessing about Android failures.

**What went wrong**
- **"Done" isn't correct.** An untiled VAE gave all-white images, and the web build wasn't image-checked after a merge. Always compare output pixels against a reference.
- **Evaluated on the calibration data.** The NPU quantization was checked on its own 80 calibration samples, and later only negative-prompt samples were used. A held-out test set is mandatory.
- **A change was verified on 2 of 6 models**, but the README claimed "same images".
- **Shared resources poisoned measurements:** the GPU was shared with other sessions and the laptop has two power modes. Interleaved A/B runs and power logging became rules.
- **Build scripts outside git, and Docker images re-tagged silently.** Results couldn't be reproduced.

**Directly relevant lesson.** On 2026-09-28 you asked whether to run `sd-web.wasm` on GraalWasm. The answer was no. An Emscripten module isn't self-contained: it imports hundreds of functions from its JS glue and relied on JSPI, Wasm exceptions and memory64. The better route was **native library + FFM (Panama) + optional native-image**. WordPress Playground's php.wasm is the same kind of artifact: an Emscripten build, with extensions as JSPI side modules. The same reasoning applies below.

### Your standing rules (from memory and corrections, carried over as is)
- Idiomatic target-platform code, not a transliteration. Ready libraries before hand-rolled code. Comments only for non-obvious facts.
- **No perf loss** for idiom. Benchmark each change interleaved against the previous image. Keep exceptions in a README section.
- Commit often, with explicit paths. Document findings in repo `.md` files.
- Benchmarks: pinned cores, the app and the load generator never share a core, governor and power mode logged, a quiet host, std between reps and within a run, significance tests.
- Use the harness defaults. Be honest in the README: lead strong, then list where we fall behind.

---

