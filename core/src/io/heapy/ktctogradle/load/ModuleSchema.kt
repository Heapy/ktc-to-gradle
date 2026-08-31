package io.heapy.ktctogradle.load

/** Describes keys consumed by [YamlBinder] so every unconsumed module key can be reported. */
internal sealed interface Schema {
    /** Stops traversal for scalars, user-defined maps, and sections reported elsewhere. */
    data object Opaque : Schema

    data class Object(val keys: Map<String, Schema>) : Schema

    data class Items(val element: Schema) : Schema
}

/** Returns unconsumed keys in declaration order, including list indices in their paths. */
internal fun unknownModuleKeys(config: Value.Mapping): List<String> = buildList {
    for ((key, value) in config.entries) {
        val schema = topLevelSchema(key)
        if (schema == null) add(key) else collectUnknown(key, value, schema, this)
    }
}

/** Mirrors the binder's prefix matching, including keys such as `dependencies-dev`. */
private fun topLevelSchema(key: String): Schema? = when {
    key.startsWith("dependencies") || key.startsWith("test-dependencies") -> Schema.Opaque
    key.startsWith("settings@") || key.startsWith("test-settings@") -> Schema.Opaque
    else -> TOP_LEVEL.keys[key]
}

/**
 * Reports an unknown parent once rather than every leaf below it. Shape mismatches remain the
 * binder's responsibility, allowing scalar-or-object spellings to share one schema.
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

/** Unsupported settings are opaque because the converter already reports them by name. */
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

/** Only the four `test-settings.jvm` keys consumed by the binder. */
private val TEST_SETTINGS: Schema.Object = schema(
    "jvm" to schema(*leaves("freeJvmArgs", "systemProperties", "extraEnvironment", "release")),
)

private val TOP_LEVEL: Schema.Object = schema(
    "product" to schema(*leaves("type", "platforms")),
    *leaves("layout", "description"),
    // Alias keys are user-defined; TemplateGraph consumes apply before binding.
    *leaves("aliases", "apply"),
    "repositories" to Schema.Items(
        schema(
            *leaves("id", "url", "resolve", "publish"),
            "credentials" to schema(*leaves("file", "usernameKey", "passwordKey")),
        ),
    ),
    "settings" to SETTINGS,
    "test-settings" to TEST_SETTINGS,
    *leaves(*YamlBinder.UNSUPPORTED_KEYS.toTypedArray()),
)
