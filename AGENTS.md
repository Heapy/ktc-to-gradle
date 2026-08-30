For manual end-to-end verification, follow [`docs/VERIFY.md`](docs/VERIFY.md).

## Kotlin Toolchain semantics

Treat Kotlin Toolchain's implementation and behavior as the source of truth whenever parsing its
configuration or deciding what Gradle output to generate. Reproduce its semantics as closely as
Gradle permits; adapt only where Gradle requires it, and do not invent converter-specific rules for
how Kotlin Toolchain works.

## Architecture

The conversion pipeline is wired in `core/src/io/heapy/ktctogradle/Converter.kt`:
load, interpret, render, write.

- `load/` owns filesystem reads and YAML parsing. The untyped `Value` tree must not leave this
  stage, and nothing here may import a later stage.
- `interpret/`, `model/`, and `render/` are pure: they do not access the filesystem. Filesystem
  facts needed by an interpreter arrive through `ModuleLayout`.
- Gradle DSL text belongs only in `render/`; plugins remain typed as `GradlePlugin` in `model/`.
- `KtsWriter` owns indentation. Renderers must not build indentation themselves.

## Generated Gradle wrapper

`core/src/io/heapy/ktctogradle/render/GradleWrapperAssets.kt` is generated. Never edit it by hand;
regenerate it with `tools/update-gradle-wrapper.sh` and review the resulting diff.

`gradle-wrapper.jar` is binary and cannot carry an ownership marker. Its
`FileContent.Binary.ownershipFollows` points to the adjacent `gradle-wrapper.properties`. Keep that
ownership decision in `Converter`, not in the write stage.

## Tests

Place coverage at the narrowest layer that states the intended behavior:

1. `YamlBinderTest`: YAML text to `ToolchainModel`, compared with `assertEquals`.
2. Interpreter tests: hand-built `ToolchainProject` and `ModuleLayout` to `GradleProject`, compared
   with `assertEquals`.
3. Render tests: only for rendering intents a golden baseline does not explain, such as embedded
   constants or required line endings.
4. Load and write tests in `core/test@jvm/`: exercise `ProjectLoader` or `FileWriter` against a
   temporary directory.
5. Golden snapshots in `core/testResources@jvm/golden/`: exercise `Converter.generateFiles()` and
   compare every generated file, the file list, and diagnostics byte for byte.

Do not use substring assertions over generated build scripts. Use equality-based binder or
interpreter tests, or a golden case. `StaticAssetsTest` is the deliberate exception: its substring
assertions document asset-level invariants that a byte-for-byte baseline does not explain.

Add a golden case for a new product type, setting, or emitted file. An existing case that merely
grows a line does not need another case.

### Updating golden baselines

```shell
UPDATE_SNAPSHOTS=1 ./kotlin test -m core -p jvm
```

Update mode is red by design: it rewrites the baselines and then fails. Regenerate only after
confirming the output change is intended, inspect the complete diff, and rerun without the switch;
the verification run must pass.

## Multiplatform constraints

`core` is a `kmp/lib` module. `core/src/` and `core/test/` must compile for every platform: do not
use JVM-only APIs such as `java.nio`, `System.getenv`, or `System.getProperty`; use okio for paths
and files.

Put tests requiring JVM APIs in `core/test@jvm/`. Platform-specific production APIs must be
`internal expect` declarations in `core/src/` with `actual` implementations in the appropriate
`core/src@<platform>/` source set.
