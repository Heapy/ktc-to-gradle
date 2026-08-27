docs/VERIFY.md – manual test cases

## Architecture

The conversion runs as four stages, wired in `core/src/io/heapy/ktctogradle/Converter.kt`:
load, interpret, render, write.

- **`load/`** reads the project from disk: `ProjectLoader`, `TemplateGraph`, `ModuleLayout`,
  `YamlBinder`, `YamlValues`. It produces a `ToolchainProject` of `ToolchainModel` data classes.
  This is the only stage that parses YAML, and the untyped `Value` tree may not leave it.
- **`interpret/`** turns a `ToolchainProject` into a `GradleProject`: `ProjectInterpreter`,
  `JvmInterpreter`, `AndroidInterpreter`, `MultiplatformInterpreter`, `KmpFragments`,
  `PluginResolution`, `Dependencies`, `Repositories`, `Serialization`, `Defaults`.
- **`model/`** holds `GradleModel.kt`, the description of the build to be generated. No Gradle DSL
  text lives here; plugins are the sealed `GradlePlugin` type, not strings.
- **`render/`** turns a `GradleProject` into file contents: `KtsWriter`, `ModuleRenderer`,
  `SettingsRenderer`, `StaticAssets`. `KtsWriter` owns indentation, so no renderer builds its own.
- **`write/`** is `FileWriter`: it diffs content, refuses to overwrite files this tool did not
  generate, and implements `--dry-run`.

The file system is touched only at the two ends, in `load/` and `write/`. `interpret/`, `render/`
and `model/` are pure: they never open a file and never see a `Value`. Keep it that way — the
purity is what makes the middle of the pipeline testable without a fake file system. Filesystem
facts an interpreter needs arrive as a `ModuleLayout` that the load stage filled in.

## Tests

There are three layers, and a change belongs in exactly one of them.

1. **`YamlBinderTest`** — pure `String -> ToolchainModel`. Feed YAML, compare the resulting data
   classes with `assertEquals`. Common code, in `core/test/`.
2. **Interpreter tests** — pure `ToolchainProject -> GradleProject`, compared with `assertEquals`.
   Hand-build the `ModuleLayout` instead of creating directories. Common code, in `core/test/`.
3. **Golden snapshots** — 20 cases under `core/testResources@jvm/golden/`, driven by
   `core/test@jvm/io/heapy/ktctogradle/GeneratedOutputSnapshotTest.kt` through
   `Converter.generateFiles()`. Every generated file, the file list, and the diagnostics are
   compared byte for byte against the baseline.

Substring assertions over generated text (`assertTrue("kotlin(\"jvm\")" in build)`) were removed
deliberately and must not come back. They pass while the surrounding output is wrong and they say
nothing about what else changed. A new behaviour gets an equality-based unit test in layer 1 or 2,
or a new golden case in layer 3.

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

`.gitattributes` exempts the baselines from line-ending normalisation:

```
core/testResources@jvm/golden/** -text !eol
```

`gradlew.bat` legitimately ends its lines with CRLF, and the repository-wide `eol=lf` rule would
rewrite it on checkout, so the comparison would fail against a baseline git had silently changed.

## Multiplatform constraints

`core` is a `kmp/lib` module, so `core/src/` and `core/test/` must compile for every platform: no
`java.nio`, no `System.getenv`, no `System.getProperty`. Use okio for paths and files. Only
`core/test@jvm/` may use JVM-only APIs, which is why the snapshot harness lives there.

`core/module.yaml` declares no test dependencies, so tests get `kotlin.test` and whatever the module
itself already depends on. There is no okio fake file system, which is the practical reason layers 1
and 2 take their inputs as data rather than as a tree.
