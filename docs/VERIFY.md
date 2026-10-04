# Manual test cases

The integration-test module builds, converts, and rebuilds twelve synthetic fixtures. These cases convert **real Kotlin
Toolchain projects** from the [Heapy organization](https://github.com/orgs/Heapy/repositories)
instead. They cover module counts, platform sets, templates, catalogs, and build plugins that no
fixture reproduces.

Every case is read-only with respect to the upstream repository: work in a throwaway clone and
delete it afterwards. Never run these against a checkout you care about.

## Prerequisites

- The checked-in Kotlin Toolchain wrapper (`./kotlin`), which provisions everything else.
- `git` and, for listing the organization, `gh`.
- A JDK for Gradle. `gradlew` downloads Gradle itself.
- An Android SDK for the cases marked *needs Android SDK*. Without it those builds fail during
  configuration, which is an environment problem, not a converter defect.

Build the converter once:

```shell
./kotlin build -m macos -p macosArm64      # or -m linux / -m windows for the host
```

Run it through the Toolchain wrapper, forwarding flags after `--`:

```shell
./kotlin run -m macos -- --dry-run /path/to/project
```

## Set up a scratch checkout

```shell
SCRATCH="$(mktemp -d "${TMPDIR:-/tmp}/ktc-verify.XXXXXX")"
for repo in kotlm krogu-time kotmark kwasm kinetica kotgent harmon kotbusta; do
  git clone --depth 1 "https://github.com/Heapy/$repo.git" "$SCRATCH/$repo"
done
```

Remove it when finished:

```shell
rm -rf "$SCRATCH"
```

## The procedure

Run these five steps for each project. Stop at the first step that disagrees with the expected
result in that project's case below, and record the exact output.

1. **Record the Toolchain version the project pins.** The converter targets 0.13. A project pinning
   0.12.x or earlier may use syntax the converter does not model; confirm a version mismatch
   before filing a defect. In a scratch checkout, migrate `layout: amper` to `layout: default`
   and add explicit Android application namespaces before testing against 0.13.

   ```shell
   grep -m1 '^kotlin_cli_version=' "$SCRATCH/<repo>/kotlin"
   ```

2. **Build the project with the Toolchain first,** so there is a reference for what a correct build
   produces. Skip this when the project pins a Toolchain version you do not want to provision.

   ```shell
   (cd "$SCRATCH/<repo>" && ./kotlin build)
   ```

3. **Dry-run the conversion.** Nothing is written. Read every `warning:` line: a warning is the
   converter telling you it guessed.

   ```shell
   ./kotlin run -m macos -- --dry-run "$SCRATCH/<repo>"
   ```

4. **Convert, then read the generated files before building them.** Compare each `build.gradle.kts`
   against the module's `module.yaml`. Settings that are silently dropped are the defect class this
   suite exists to find, and a green build will not reveal them.

   ```shell
   ./kotlin run -m macos -- "$SCRATCH/<repo>"
   git -C "$SCRATCH/<repo>" status --short
   ```

   Do not pass `--force` to get past a refusal. The refusal means a file exists that the converter
   did not write; read it first and decide deliberately.

5. **Build with the generated wrapper,** using the task named in the project's case.

   ```shell
   (cd "$SCRATCH/<repo>" && ./gradlew --console=plain build)
   ```

   A green build is not sufficient evidence. Also check that the tests **ran**:

   ```shell
   ls "$SCRATCH/<repo>"/build/test-results/*/*.xml
   grep -ho 'tests="[0-9]*"' "$SCRATCH/<repo>"/build/test-results/*/*.xml
   ```

   Zero result files, or `tests="0"`, means the test framework was not wired up and the whole suite
   was skipped while the build reported success.

## Projects

Expected results below were observed with converter 0.12.0 against the repository state of
2026-08-27. They are historical observations, not current release checks. Toolchain 0.13 changed
Android namespace defaults: missing library namespaces now match Toolchain without a warning;
applications without a namespace are rejected. Record the current results below when rerunning.

| Project | Shape | Exercises | Expected result |
| --- | --- | --- | --- |
| [kotlm](https://github.com/Heapy/kotlm) | single `jvm/app` | `maven-like` layout, `libs.versions.toml`, JUnit 5, JDK 25 | converts and builds; tests run |
| [krogu-time](https://github.com/Heapy/krogu-time) | single `kmp/lib` | jvm + android + 3 ios targets, aliases, publishing, platform-qualified settings | converts; publishing carried, the rest named as warnings |
| [kotmark](https://github.com/Heapy/kotmark) | 13 modules | 18-platform `kmp/lib`, relative dependencies, `$kotlin.test` | converts with warnings |
| [kwasm](https://github.com/Heapy/kwasm) | 4 modules | a project that already has a hand-written Gradle build | refuses to overwrite |
| [kinetica](https://github.com/Heapy/kinetica) | 34 modules | templates, JS/browser/native/GTK, third-party compiler plugins | stops on `settings.compose` |
| [kotgent](https://github.com/Heapy/kotgent) | 8 modules | local Toolchain build plugins | converts; plugin modules left out |
| [harmon](https://github.com/Heapy/harmon) | 12 modules | templates plus a local build plugin | converts; plugin module left out |
| [kotbusta](https://github.com/Heapy/kotbusta) | 4 modules | local build plugin plus `mavenPlugins` (jacoco) | converts; plugin module left out |

## Case 1 — kotlm: the happy path

A single `jvm/app` with `layout: maven-like`, a version catalog, Ktor, and JUnit 5.
Toolchain 0.11.1. This is the case that must never regress.

```shell
./kotlin run -m macos -- --dry-run "$SCRATCH/kotlm"
./kotlin run -m macos -- "$SCRATCH/kotlm"
(cd "$SCRATCH/kotlm" && ./gradlew --console=plain build)
```

Expect 6 generated files, no warnings, and `BUILD SUCCESSFUL`.

Check in `build.gradle.kts`:

- `testImplementation(kotlin("test-junit5"))` **and** `useJUnitPlatform()` — both, not one;
- `libs.junit.jupiter` and the other `$libs.*` coordinates resolved through the catalog;
- `mainClass` set to `io.heapy.kotlm.Application`;
- the `maven-like` source directories, not the `default` ones.

Then confirm the tests executed. Six result files with non-zero `tests=` counts were observed.

## Case 2 — krogu-time: publishing, and the settings that still get dropped

A single `kmp/lib` over `jvm, android, iosArm64, iosSimulatorArm64, iosX64`, with a
`jvmAndAndroid` alias, full Maven Central publishing, per-platform `settings@<platform>` blocks, and
a `test-settings` block. Toolchain 0.12.0-dev. *Needs Android SDK to build.*

```shell
./kotlin run -m macos -- "$SCRATCH/krogu-time"
```

The conversion succeeds. The generated `build.gradle.kts` is where the case is:

- **`settings.publishing` is carried.** Expect `maven-publish` and `signing` in the plugins block,
  `group` and `version` on the project, and a `publishing { }` block whose
  `publications.withType<MavenPublication>()` carries the whole POM. Two warnings are expected and
  correct, both from `settings.publishing.mavenCentral`: it has no Gradle equivalent, because Gradle
  ships no Central Portal upload, and the publication carries no javadoc jar, because the Kotlin
  Gradle Plugin builds none per target. A third names any Central requirement the module leaves
  unmet. The `signing { }` block looks up `KOTLIN_TOOLCHAIN_SIGNING_KEY` and calls
  `sign` whether or not it found one, so a keyless `./gradlew publish` fails rather than shipping
  unsigned artifacts — check that, it is the behaviour Toolchain has.
- **`settings@jvm` and the four other platform-qualified blocks are carried over.**
  `allWarningsAsErrors` is declared five times in `module.yaml` and appears five times in the
  output: once inside `jvm { compilerOptions { } }`, once inside `androidLibrary { }`, and once in
  each of the three iOS target blocks.
- **`test-settings.jvm.release: 25` is carried.** The `jvm()` target gets a
  `compilations.named("test")` block restating `-Xjdk-release=25`, next to the `-Xjdk-release=21`
  the main compilation keeps. That is the whole point of the key: the JVM differential tests compile
  against JDK 25 while the published bytecode stays at 21. `./gradlew compileTestKotlinJvm` is the
  check — it fails without the block, because the tests read `java.time` APIs newer than 21.
- The alias hierarchy *is* honored: look for `jvmAndAndroidMain`, `nativeMain` and their
  `dependsOn` wiring.

Each dropped setting is a converter defect, not a project problem. File what you find.

## Case 3 — kotmark: many modules, many platforms

Thirteen `kmp/lib` modules; the main one declares 18 platforms including `android`, `js`, `wasmJs`,
`wasmWasi`, watchOS and tvOS. Modules depend on each other through relative `../` notation.
Toolchain 0.11.0. *Needs Android SDK.*

```shell
./kotlin run -m macos -- --dry-run "$SCRATCH/kotmark"
```

For Toolchain 0.13 input, an omitted Android library namespace follows `publishing.group`
and the effective artifact ID (hyphens become underscores and a leading digit gets an underscore).
Without a publishing group it is `org.jetbrains.ktc.mangled.p<absolute module-name hash>`, matching
the Toolchain implementation. No missing-namespace warning is expected.

Then check that `../kotmark` and the other relative dependencies became `project(":kotmark")`
rather than a Maven coordinate, and that `$kotlin.test` resolved to `kotlin("test")`.

Because of the platform count, run structure checks before a full build:

```shell
(cd "$SCRATCH/kotmark" && ./gradlew --console=plain projects)
(cd "$SCRATCH/kotmark" && ./gradlew --console=plain :kotmark:jvmTest)
```

## Case 4 — kwasm: the historical collision refusal

The historical `kwasm` checkout carried a `project.yaml` **and** a hand-written Gradle build.
The current checkout is Gradle-only, so it stops at project discovery. Use an older commit
with both builds to reproduce the overwrite-guard case below; the core write suite also tests it.

```shell
./kotlin run -m macos -- --dry-run "$SCRATCH/kwasm"
```

Expect exit code 1 and:

```text
ktc-to-gradle: Refusing to overwrite existing files: settings.gradle.kts, build.gradle.kts,
wasm-annotations/build.gradle.kts, ... Re-run with --force after reviewing them.
```

Confirm the refusal is right by checking that the existing files lack the marker:

```shell
grep -c "Generated by ktc-to-gradle" "$SCRATCH/kwasm/settings.gradle.kts"   # expect 0
```

**Do not run `--force` on this project as a routine check.** It replaces a hand-written build that
the converter cannot reproduce. If you want to test `--force`, do it on the throwaway clone only,
and inspect the diff rather than the exit code.

## Case 5 — kinetica: the largest project

Thirty-four modules, two shared module templates (`common.module-template.yaml`,
`publish.module-template.yaml`), JS and browser samples, GTK and native counters, and a module that
is itself a Gradle plugin. Toolchain 0.12.0.

```shell
./kotlin run -m macos -- --dry-run "$SCRATCH/kinetica"
```

`settings.kotlin.compilerPlugins` is converted now, so `bench-jvm` and the two JS samples get past
it. Expect exit code 1 and a stop further along:

```text
ktc-to-gradle: samples/browser-bench-compose: 'settings.compose' is not supported yet
```

That is the intended refusal, but it still stops the whole conversion on the first offending module.
The useful check is whether that is the right trade: the other modules convert and are never seen.

To reach the rest of the project, temporarily remove the Compose samples from `project.yaml` **in
the clone** and re-run. Record how far the conversion then gets — each new stop is a separate
finding. On the modules that do convert, check that `bench-jvm/build.gradle.kts` carries
`kotlinCompilerPluginClasspath` for `io.heapy.kinetica:kinetica-compiler` and one `-P
plugin:io.heapy.kinetica.compiler:<key>=<value>` pair per declared option.

## Case 6 — kotgent, harmon, kotbusta: build plugins

All three use local Toolchain build plugins, which have no automatic Gradle equivalent. The
conversion no longer stops: every other module is converted, and what could not be converted is
reported as an `error:` line. The run still exits 1, so a partial conversion is never a success.

```shell
./kotlin run -m macos -- --dry-run "$SCRATCH/kotgent"
./kotlin run -m macos -- --dry-run "$SCRATCH/harmon"
./kotlin run -m macos -- --dry-run "$SCRATCH/kotbusta"
```

Expect files for every non-plugin module, plus:

```text
error: kotgent: 'plugins' cannot be converted automatically; the section was dropped and needs a hand-written Gradle equivalent
error: plugins/build-info: Kotlin Toolchain build plugins have no automatic Gradle equivalent; the module was left out of the generated build
error: plugins/sqldelight-gen: Kotlin Toolchain build plugins have no automatic Gradle equivalent; the module was left out of the generated build
```

The three still differ in a way worth checking:

- **kotgent** reports its root module and both plugin modules. Its `project.yaml` also has a
  project-level `plugins:` list, which the converter never inspects.
- **harmon** reports `history-sqlite`, a leaf module, plus `plugins/sqldelight-gen`.
- **kotbusta** reports `plugins` and `mavenPlugins` on the same module, and
  `build-logic/distribution`. Every unsupported key of every module is now named, not just the first.

For each, check that the reported set matches what the project actually uses, and that a generated
module still references nothing that was left out.

## Latest verification: 2026-10-02

Converter 0.13.0, Kotlin Toolchain 0.13.0, Kotlin 2.4.20, Gradle 9.8.0. All eight
repositories were shallow-cloned into scratch directories. Automated verification passed:

- `./kotlin test -m core -p jvm`: 401 tests, including all 40 golden cases.
- `./kotlin test -m core -p macosArm64`: 288 tests.
- `./kotlin test -m integration-tests -p jvm --build-dir build/integration-013`: all 8 tests,
  including all three Android fixtures and the host-native fixture. A separate build directory
  prevents another local compilation from replacing classes while the long suite is running.
- `./kotlin build -m macos -p macosArm64 -v release`: successful.
- Local macOS `run.sh` and `install.sh` CI scripts: install, cache reuse, fork cache separation,
  reinstall, and checksum rejection passed. `actionlint` passed.

The dry runs used
`build/tasks/_macos_linkMacosArm64Release/macos.kexe --dry-run <clone>`; original pins were
retained, so only `kotgent` already used Toolchain 0.13.0. Other wrappers pinned 0.12.x.

| Project | Commit | Dry-run result |
| --- | --- | --- |
| kotlm | `6d3d0bad8c4c` | 7 files; warnings for JUnit Platform version and JDK distributions |
| krogu-time | `74768bee15a0` | 7 files; Central Portal and missing-javadoc warnings |
| kotmark | `728afa7ab7f6` | 20 files; mixed Kotlin plugin versions warned; no namespace warnings |
| kwasm | `ce5effd8bd32` | No Toolchain project remains; this checkout is Gradle-only |
| kinetica | `f1bfdae7295b` | Stops on Compose; also diagnoses dropped Android-qualified settings |
| kotgent | `9c98f3e33dce` | 16 files; build-plugin sections/modules reported as errors |
| harmon | `7333ba27487f` | 17 files; build-plugin section/module reported as errors |
| kotbusta | `4803807dd140` | 9 files; Maven/build plugins reported as errors; JDK/JUnit warnings |

The older cases above retain their historical context. In particular, `kwasm` no longer
exercises the overwrite refusal; the core write tests cover that behavior. These are
conversion checks; full upstream builds were run only for `kotlm`. Both its original Toolchain 0.12.2 build
and generated Gradle 9.8.0 build passed; Gradle executed 62 tests in six result files.

## PR #2 verification: 2026-10-04

The combined upgrade retains Renovate's Okio 3.18.2 and kotaml 0.111.0 updates. The failed CI
comparison for `kmp-android` came from Toolchain resolving Kotlin 2.4.20 while the converter
still defaulted to 2.4.10. Updating the converter default fixes the dependency mismatch without
relaxing the comparison. The combined branch passed 401 JVM core tests, 288 native core tests,
all 8 integration tests (including Android), the macOS release build, launcher/install smoke
checks, and workflow lint.

## Record what you find

For each case, record: the converter version (`--version`), the project's pinned Toolchain version,
the commit you cloned, the exact command, and the complete output. File a task per distinct defect
rather than one task per project — a dropped setting and a misleading error message are different
problems even when the same project surfaces both.
