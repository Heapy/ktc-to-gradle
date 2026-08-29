package io.heapy.ktctogradle.load

/**
 * The keys [YamlBinder] takes out of a module.yaml, written down so a key it takes nothing out of
 * can be named.
 *
 * The binder reads by path — `present.string("android.namespace")` — so nothing records which paths
 * it consumed, and a key it never asks for binds to nothing and is never mentioned. That is the
 * fourth way a section can disappear, after the keys the converter refuses, the ones it cannot parse
 * and the ones it reads and carries nowhere: written, and read by nothing at all.
 *
 * The authority here is the binder and not the Toolchain schema. A key the Toolchain defines and
 * this converter never reads belongs in the report for the same reason a misspelling does: neither
 * one reaches a line of the generated build, and the run is otherwise silent about both.
 */
internal sealed interface Schema {
    /**
     * A node whose inside is not described.
     *
     * It covers three unrelated cases: a scalar; a node whose keys the module chooses, such as
     * `aliases` or a compiler plugin's `options`; and a section another part of the converter already
     * reports on, such as `plugins:` or a `settings@<qualifier>` block. All three mean the same thing
     * to the walk — stop here — so they share one node rather than three that behave identically.
     */
    data object Opaque : Schema

    data class Object(val keys: Map<String, Schema>) : Schema

    data class Items(val element: Schema) : Schema
}

/**
 * Every key of [config] the binder does not read, as the dotted path that reaches it, in the order
 * the module declares them.
 *
 * A list element is named by its index, `repositories[0].credentials.usernameKy`, because that is
 * how the load stage already quotes one.
 */
internal fun unknownModuleKeys(config: Value.Mapping): List<String> = buildList {
    for ((key, value) in config.entries) {
        val schema = topLevelSchema(key)
        if (schema == null) add(key) else collectUnknown(key, value, schema, this)
    }
}

/**
 * The schema of one top-level key, or `null` when the binder reads no such key.
 *
 * The prefixed sections have to be recognised exactly as the binder recognises them, or a key it
 * does read would be reported as one it does not. `bindDependencies` matches on `startsWith` alone,
 * so `dependencies-dev` is a section it binds — under its own name as the qualifier — and not a
 * misspelling of `dependencies`.
 */
private fun topLevelSchema(key: String): Schema? = when {
    key.startsWith("dependencies") || key.startsWith("test-dependencies") -> Schema.Opaque
    key.startsWith("settings@") || key.startsWith("test-settings@") -> Schema.Opaque
    else -> TOP_LEVEL.keys[key]
}

/**
 * Walks [value] against [schema], collecting the paths [schema] does not describe.
 *
 * An unknown key is reported and not descended into: the module misspelled one key, and naming its
 * children as well would report the same mistake once per leaf underneath it.
 *
 * The walk says nothing about shape. A node the schema describes as an object is skipped when the
 * YAML wrote something else there, which is what lets a key with two spellings — `product`, `ktor`,
 * `serialization`, `compileSdk`, `mavenCentral`, `scm` — carry one schema: the object form is
 * checked and the scalar form has no key to check. A wrong shape is the binder's business, and it
 * already defers a message for the ones that matter.
 */
private fun collectUnknown(path: String, value: Value, schema: Schema, into: MutableList<String>) {
    when (schema) {
        Schema.Opaque -> Unit
        is Schema.Object -> {
            val mapping = value as? Value.Mapping ?: return
            for ((key, child) in mapping.entries) {
                when (val childSchema = schema.keys[key]) {
                    null -> into += "$path.$key"
                    else -> collectUnknown("$path.$key", child, childSchema, into)
                }
            }
        }
        is Schema.Items -> {
            val items = (value as? Value.Sequence)?.items ?: return
            items.forEachIndexed { index, item -> collectUnknown("$path[$index]", item, schema.element, into) }
        }
    }
}

private fun schema(vararg keys: Pair<String, Schema>): Schema.Object = Schema.Object(keys.toMap())

private fun leaves(vararg keys: String): Array<Pair<String, Schema>> =
    Array(keys.size) { index -> keys[index] to Schema.Opaque }

/**
 * `settings:`, whose qualified `settings@<qualifier>` siblings are walked by the binder itself and
 * are [Schema.Opaque] here.
 *
 * The subtrees `UNSUPPORTED_SETTINGS` names — `compose`, `springBoot`, `lombok`, `kotlin.ksp`,
 * `kotlin.rpc`, `kotlin.dataframe` — are opaque rather than absent, because the converter already
 * refuses them by name and a second report would say the same thing in weaker words.
 */
private val SETTINGS: Schema.Object = schema(
    "kotlin" to schema(
        *leaves("version", "languageVersion", "apiVersion", "allWarningsAsErrors", "progressiveMode"),
        *leaves("freeCompilerArgs", "optIns"),
        "serialization" to schema(*leaves("enabled", "version", "format")),
        "compilerPlugins" to Schema.Items(schema(*leaves("id", "dependency"), "options" to Schema.Opaque)),
        *leaves("ksp", "rpc", "dataframe"),
    ),
    "jvm" to schema(
        "jdk" to schema(*leaves("version")),
        *leaves("release", "mainClass"),
        "test" to schema(*leaves("freeJvmArgs", "systemProperties", "extraEnvironment", "junitPlatformVersion")),
    ),
    "android" to schema(
        *leaves("namespace", "minSdk", "targetSdk", "applicationId", "versionCode", "versionName"),
        "compileSdk" to schema(*leaves("apiLevel")),
    ),
    "native" to schema(*leaves("entryPoint")),
    *leaves("junit"),
    "ktor" to schema(*leaves("enabled", "version")),
    "publishing" to schema(
        *leaves("enabled", "group", "artifactId", "version", "publishSources", "signArtifacts", "checksums"),
        "mavenCentral" to schema(*leaves("enabled", "publishingMode")),
        "pom" to schema(
            *leaves("name", "description", "url"),
            "licenses" to Schema.Items(schema(*leaves("name", "url"))),
            "developers" to Schema.Items(
                schema(*leaves("id", "name", "url", "email", "organization", "organizationUrl")),
            ),
            "scm" to schema(*leaves("url", "connection", "developerConnection")),
        ),
    ),
    *leaves("compose", "springBoot", "lombok"),
)

/**
 * `test-settings:`, which reaches a Gradle `Test` task and a test compilation, and nothing else.
 *
 * It is not a second copy of [SETTINGS]: the binder reads four keys under `jvm` out of it and reads
 * no other section of it at all, so anything else the module writes here is dropped and says so.
 */
private val TEST_SETTINGS: Schema.Object = schema(
    "jvm" to schema(*leaves("freeJvmArgs", "systemProperties", "extraEnvironment", "release")),
)

private val TOP_LEVEL: Schema.Object = schema(
    "product" to schema(*leaves("type", "platforms")),
    *leaves("layout", "description"),
    // The names under `aliases` are the module's own, and `apply` is consumed by `TemplateGraph`
    // before the binder ever sees the merged config.
    *leaves("aliases", "apply"),
    "repositories" to Schema.Items(
        schema(
            *leaves("id", "url", "resolve", "publish"),
            "credentials" to schema(*leaves("file", "usernameKey", "passwordKey")),
        ),
    ),
    "settings" to SETTINGS,
    "test-settings" to TEST_SETTINGS,
    // Reported by name as sections the converter refuses, so the walk leaves them alone.
    *leaves(*YamlBinder.UNSUPPORTED_KEYS.toTypedArray()),
)
