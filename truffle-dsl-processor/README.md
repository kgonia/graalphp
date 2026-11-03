# truffle-dsl-processor 25.4.4.1.1, patched

The released Truffle DSL processor 25.4.4.1.1 with two upstream Bytecode DSL fixes that aren't in any release yet.
Only the annotation processor changes; it runs inside `javac` and writes the `...Gen.java` classes. The runtime
(`truffle-api`, `truffle-runtime`, the compiler) stays the released 25.4.4.1.1, and the generated code uses only API
that 25.4.4.1.1 has. `pom.xml` takes it as `dsl.processor.version` (`${graalvm.version}-patched`).

| patch | upstream | fixes |
|---|---|---|
| `patches/pr14872-report-polymorphism.diff` | [oracle/graal#14872](https://github.com/oracle/graal/pull/14872) (GR-81207, open on 2026-11-03) | [#14871](https://github.com/oracle/graal/issues/14871): `@ReportPolymorphism` on a Bytecode DSL operation generates `checkForPolymorphicSpecialize` reading `$bc`/`$bci` without them in scope (25.1+) |
| `patches/gr79787-unquicken-state.diff` | commit [9a8b5489c5](https://github.com/oracle/graal/commit/9a8b5489c5) (GR-79787, master since 2026-09-23) | `cloneUninitialized` (splitting) keeps the specialization state bits in the bytecode but makes the caches anew, so a `@Fallback` guard trusts stale bits and a split copy answers wrong |

Both are the processor part of the upstream diffs, without their tests. They apply to the 25.4.4.1.1 sources with
line offsets and touch different files.

## Build

Once per machine (and again after a change to `patches/`), before `mvn`:

```sh
bash truffle-dsl-processor/build.sh    # JAVA_HOME: a JDK 21+; mvn, curl, unzip and patch on PATH
```

1. Downloads the processor's `-sources.jar` from Maven Central.
2. Applies the patches (`patch`: inside a git checkout `git apply` skips them without an error).
3. Recompiles only the changed files, against the released jar.
4. Puts those classes into a copy of the released jar.
5. Installs it in `~/.m2` as `org.graalvm.truffle:truffle-dsl-processor:25.4.4.1.1-patched`; the jar is also at
   `truffle-dsl-processor/target/`.

Without it `mvn` stops: `Could not find artifact org.graalvm.truffle:truffle-dsl-processor:jar:25.4.4.1.1-patched`.
It never falls back to the released processor, which without the workarounds below compiles code that answers wrong.

After switching processors, build with `mvn clean ...`: an incremental build keeps the old generated classes.

## Checked (2026-11-03)

- The jar built from this directory has the same classes as the one built outside a checkout.
- `truffle-bytecode-clone-state-repro` (`./check-versions.sh 25.4.4.1.1 25.4.4.1.1-patched`): the released processor's
  copy answers `false` for a `Foo`, the patched one's `true`, through `cloneUninitialized()` and through
  `DirectCallNode.cloneCallTarget()`.
- `PhpBytecodeRootNodeGen.unquickenBytecode` zeroes each custom operation's state
  (`BYTES.putShort(copy, bci + 6, (short) 0)`), and both `cloneUninitialized` copy the bytecode through it.
- Truffle PHP without the workarounds: wiki/bytecode-dsl.md, "The patched processor".

## Dropping it

Once a Truffle release has both fixes (GR-79787 and #14872):
1. Set `graalvm.version` to it and `dsl.processor.version` to `${graalvm.version}` (or put `${graalvm.version}` back
   in `annotationProcessorPaths`).
2. Delete this directory.
3. `mvn clean package`, then `tests/smoke/split-copies.php` with splitting on (the default) must still pass.

If a release has only one of them, drop that patch and rebuild. The workarounds the patches replaced: the unused
`@Shared` caches on the fallbacks (6b0988a) and the 21 `@ReportPolymorphism.Exclude` in `bytecode/Proxies.java`.
