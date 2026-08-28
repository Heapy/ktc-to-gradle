docs/VERIFY.md – manual test cases

## Architecture

The conversion runs as four stages, wired in `core/src/io/heapy/ktctogradle/Converter.kt`:
load, interpret, render, write.

- **`load/`** reads the project from disk: `ProjectLoader`, `TemplateGraph`, `ModuleLayout`,
  `YamlBinder`, `YamlValues`, `ModuleIndex`. It produces a `ToolchainProject` of `ToolchainModel`
  data classes. This is the only stage that parses YAML, and the untyped `Value` tree may not leave
  it. Nothing here imports from a later stage.
- **`interpret/`** turns a `ToolchainProject` into a `GradleProject`: `ProjectInterpreter`,
  `JvmInterpreter`, `AndroidInterpreter`, `MultiplatformInterpreter`, `KmpFragments`,
  `PluginResolution`, `Dependencies`, `Repositories`, `Serialization`, `Defaults`.
- **`model/`** holds `GradleModel.kt`, the description of the build to be generated. No Gradle DSL
  text lives here; plugins are the sealed `GradlePlugin` type, not strings.
- **`render/`** turns a `GradleProject` into file contents: `KtsWriter`, `ModuleRenderer`,
  `SettingsRenderer`, `RepositoryBlock`, `StaticAssets`, `GradleWrapperAssets`. `KtsWriter` owns
  indentation, so no renderer builds its own.
- **`write/`** is `FileWriter`: it diffs content, refuses to overwrite files this tool did not
  generate, and implements `--dry-run`.

The file system is touched only at the two ends, in `load/` and `write/`. `interpret/`, `render/`
and `model/` are pure: they never open a file and never see a `Value`. Keep it that way — the
purity is what makes the middle of the pipeline testable without a fake file system. Filesystem
facts an interpreter needs arrive as a `ModuleLayout` that the load stage filled in.

## The Gradle wrapper is Gradle's, not ours

`render/GradleWrapperAssets.kt` is a **generated file**. It holds base64 of the three files
`gradle wrapper` produces, and it is rewritten only by `tools/update-gradle-wrapper.sh`. Never edit
it by hand, and never hand-write a launcher again: the converter used to ship one and it cost four
backlog tasks of bugs before it was replaced.

Two consequences worth knowing before touching `write/`:

- `gradle-wrapper.jar` is binary, so `GeneratedFile.content` is the sealed `FileContent` type and
  `FileWriter` compares and writes bytes.
- The jar can carry no ownership marker. It names the file whose ownership it shares instead —
  `FileContent.Binary.ownershipFollows`, pointing at the `gradle-wrapper.properties` beside it.
  That is the one exception to "every generated file carries the marker", and it is a decision, so
  it is made in `Converter` and not in the write stage.

The update script verifies the distribution against the checksum services.gradle.org publishes, and
the jar against the `wrapperChecksum` published for the same release. A scheduled workflow runs it
and opens a pull request; the diff is the review.

## The one deliberate output difference from the pre-pipeline converter

The pipeline replaced `GradleGenerator` behaviour for behaviour. The golden baselines were generated
from the old code and every case still reproduces it byte for byte, with exactly one recorded
exception.

`settings.android.compileSdk` accepts a bare level and a nested `compileSdk: { apiLevel: <n> }` form.
`GradleGenerator` read the nested form on the `android/app` path only: a `kmp/lib` declaring the
`android` platform ignored it and emitted the default `37`. `load/YamlBinder` binds both forms to one
field, so `interpret/AndroidInterpreter.libraryTarget` now honours the nested form too. That
asymmetry was an accident rather than a rule, so it was not restored.

It is pinned by the `kmp-android-compile-sdk` golden case and by
`AndroidInterpreterTest.aLibraryTargetReadsTheNestedCompileSdkForm`.

That is the only place the *refactor* changed the output, and it is the rule to read a moved
baseline by: a change that was meant to be behaviour-preserving and moved one anyway is a bug in
that change, not a baseline to update. Features added since the refactor do move baselines, on
purpose — the embedded Gradle wrapper, `kotlin.mpp.applyDefaultHierarchyTemplate=false` and
`kotlin.test.infer.jvm.variant=false` in `gradle.properties` are three — and for those the
diff is the review.

## Tests

There are five layers, and a change belongs in exactly one of them.

1. **`YamlBinderTest`** — pure `String -> ToolchainModel`. Feed YAML, compare the resulting data
   classes with `assertEquals`. Common code, in `core/test/`.
2. **Interpreter tests** — pure `ToolchainProject -> GradleProject`, compared with `assertEquals`.
   Hand-build the `ModuleLayout` instead of creating directories. Common code, in `core/test/`.
3. **Render tests** — pure `GradleProject -> String`, in `core/test/io/heapy/ktctogradle/render/`.
   Reach for one only for what a golden baseline cannot express as an intent, such as an embedded
   version constant or a required line ending.
4. **Load and write stage tests** — the parts of those two stages that need a real tree, so they
   live in `core/test@jvm/`: `ProjectRootTest`, `TemplateGraphTest`, `TemplateResolutionTest`,
   `ModuleLayoutProbeTest`, `ModuleDirectoryResolutionTest`, `DiagnosticIsolationTest`,
   `QualifiedSettingsDiagnosticsTest`, `FileWriterTest`. Drive them through `ProjectLoader` or
   `FileWriter` against a temp directory.
5. **Golden snapshots** — 32 cases under `core/testResources@jvm/golden/`, driven by
   `core/test@jvm/io/heapy/ktctogradle/GeneratedOutputSnapshotTest.kt` through
   `Converter.generateFiles()`. Every generated file, the file list, and the diagnostics are
   compared byte for byte against the baseline.

Substring assertions over generated *build scripts* (`assertTrue("kotlin(\"jvm\")" in build)`) were
removed deliberately and must not come back. They pass while the surrounding output is wrong and
they say nothing about what else changed. A new behaviour gets an equality-based unit test in layer
1 or 2, or a new golden case in layer 5.

The exception is layer 3: `StaticAssetsTest` asserts substrings over the wrapper assets on purpose,
because "the properties file names the Gradle version we pin" and "`gradlew.bat` ends its lines with
CRLF" are intents a byte-for-byte baseline states but does not explain. Do not delete those.

Add a golden case whenever a new product type, a new setting, or a new emitted file appears. An
existing case that merely grows a line does not need one; something the suite cannot currently
produce does.

### Updating the baselines

```shell
UPDATE_SNAPSHOTS=1 ./kotlin test -m core -p jvm
```

This rewrites every baseline from the current output. Never run it to make a failing test pass. A
golden test fails because the generated output changed, which is one of two things: an intended
change, or a bug. If it is intended, regenerate and then read the whole diff line by line before
committing it — the diff is the review. If it is not intended, fix the code.

**An update run is red by design.** It deletes each `expected/` directory before regenerating it, so
it compares nothing; every golden test then fails with a message saying the baselines were rewritten.
Re-run without the switch to actually verify. `SnapshotSupportTest` additionally fails whenever the
suite is in update mode, so a leaked switch can never look like a green run.

Three switches turn update mode on, because a runner may drop either of the first two on the way to
the forked test JVM:

- the `UPDATE_SNAPSHOTS` environment variable,
- the `-Dktc.updateSnapshots` system property,
- a `core/testResources@jvm/golden/.update-snapshots` marker file.

The marker sits inside the tracked golden tree. `.gitignore` covers it; it must never be committed,
and it must be deleted after use.

`.gitattributes` exempts the baselines from line-ending normalisation:

```
core/testResources@jvm/golden/** -text !eol
```

`gradlew.bat` legitimately ends its lines with CRLF, and the repository-wide `eol=lf` rule would
rewrite it on checkout, so the comparison would fail against a baseline git had silently changed.

## Multiplatform constraints

`core` is a `kmp/lib` module, so `core/src/` and `core/test/` must compile for every platform: no
`java.nio`, no `System.getenv`, no `System.getProperty`. Use okio for paths and files.

JVM-only APIs are allowed in exactly two places. `core/test@jvm/` holds the tests that need them,
which is why the snapshot harness lives there. Platform-specific production code goes in
`core/src@<platform>/` (`src@jvm`, `src@apple`, `src@linux`, `src@mingw`, `src@native`) as the
`actual` of an `internal expect` declared in `core/src/` — that is how `makeExecutable`,
`systemFileSystem` and `exitWith` reach `java.io.File`, `kotlin.system.exitProcess` and POSIX
`chmod` (see `FilePermissions.kt` and `Platform.kt`).

`core/module.yaml` declares no test dependencies, so tests get `kotlin.test` and whatever the module
itself already depends on. There is no okio fake file system, which is the practical reason layers 1
and 2 take their inputs as data rather than as a tree.
