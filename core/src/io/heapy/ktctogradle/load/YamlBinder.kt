package io.heapy.ktctogradle.load

import io.heapy.ktctogradle.ConversionException

/**
 * Turns a merged module.yaml into a [ToolchainModel].
 *
 * The binder is deliberately lenient: it never throws. A region the YAML gets wrong is bound as if
 * it were absent and its [ConversionException] message is recorded in [ToolchainModel.errors], so
 * the failure still reaches the user from the stage that reads the region. That matters because
 * `pluginsOf` swallows the `product` and serialization failures, and `rejectUnsupported` reports
 * `plugins:` before either is consulted; an eagerly-throwing binder would change both the messages
 * and their order.
 */
internal object YamlBinder {
    /**
     * [displayName] is the module name the deferred messages quote, matching what the render stage
     * puts in front of `.dependencies` today.
     */
    fun bind(config: Value.Mapping, displayName: String): ToolchainModel {
        val errors = mutableMapOf<String, String>()
        return ToolchainModel(
            product = deferred(errors, "product", ProductSpec("", emptyList())) {
                val product = product(config)
                ProductSpec(product.type, product.platforms)
            },
            layout = if (config.string("layout") == "maven-like") Layout.MAVEN_LIKE else Layout.AMPER,
            aliases = deferred(errors, "aliases", emptyMap()) { bindAliases(config) },
            dependencies = bindDependencies(config, "dependencies", displayName, errors),
            testDependencies = bindDependencies(config, "test-dependencies", displayName, errors),
            repositories = deferred(errors, "repositories", emptyList()) { bindRepositories(config) },
            settings = deferred(errors, "settings", Settings.EMPTY) {
                bindSettings(
                    settings = config.value("settings") as? Value.Mapping,
                    testSettings = config.value("test-settings") as? Value.Mapping,
                    lenient = false,
                    errors = errors,
                )
            },
            qualifiedSections = bindQualifiedSections(config),
            unsupported = bindUnsupported(config),
            errors = errors,
        )
    }

    private inline fun <T> deferred(
        errors: MutableMap<String, String>,
        region: String,
        fallback: T,
        bind: () -> T,
    ): T = try {
        bind()
    } catch (error: ConversionException) {
        errors[region] = error.message.orEmpty()
        fallback
    }

    private fun bindUnsupported(config: Value.Mapping): List<String> = buildList {
        for (key in UNSUPPORTED_KEYS) if (config.value(key) != null) add(key)
        for (path in UNSUPPORTED_SETTINGS) if (config.value(path) != null) add(path)
    }

    private fun bindAliases(config: Value.Mapping): Map<String, Set<String>> {
        val node = config.value("aliases") ?: return emptyMap()
        val entries = when (node) {
            is Value.Mapping -> node.entries.entries.toList()
            is Value.Sequence -> node.items.flatMapIndexed { index, item ->
                val mapping = item as? Value.Mapping
                    ?: throw ConversionException("aliases[$index] must be an object")
                if (mapping.entries.size != 1) throw ConversionException("aliases[$index] must define exactly one alias")
                mapping.entries.entries.toList()
            }
            else -> throw ConversionException("aliases must be an object or list")
        }
        return buildMap {
            for ((name, value) in entries) {
                if (name in this) throw ConversionException("Alias '$name' is declared more than once")
                val platforms = value.asSequence("aliases.$name").mapIndexed { index, platform ->
                    platform.scalarOrNull()
                        ?: throw ConversionException("aliases.$name[$index] must be a platform name")
                }.toSet()
                if (platforms.isEmpty()) throw ConversionException("Alias '$name' must contain at least one platform")
                put(name, platforms)
            }
        }
    }

    /**
     * The `<prefix>` section plus every `<prefix>@<qualifier>` section, keyed by qualifier with `""`
     * standing for the unqualified one. Each section defers its own failure, because a module can
     * name a bad dependency under one qualifier and a good one under another.
     */
    private fun bindDependencies(
        config: Value.Mapping,
        prefix: String,
        displayName: String,
        errors: MutableMap<String, String>,
    ): Map<String, List<RawDependency>> = buildMap {
        for ((key, value) in config.entries) {
            val qualifier = when {
                key == prefix -> ""
                key.startsWith("$prefix@") -> key.removePrefix("$prefix@")
                else -> continue
            }
            put(
                qualifier,
                deferred(errors, key, emptyList()) {
                    value.asSequence("$displayName.$key").map(::bindDependency)
                },
            )
        }
    }

    private fun bindDependency(value: Value): RawDependency = when (value) {
        is Value.Scalar -> {
            val match = SCOPE_SUFFIX.matchEntire(value.text)
            val suffix = match?.groupValues?.get(2)
            RawDependency(
                notation = match?.groupValues?.get(1) ?: value.text,
                scope = if (suffix == "exported") "all" else suffix ?: "all",
                exported = suffix == "exported",
            )
        }
        is Value.Mapping -> {
            if (value.entries.size != 1) throw ConversionException("Dependency objects must have one coordinate")
            val (key, details) = value.entries.entries.single()
            when {
                key == "bom" -> RawDependency(
                    notation = details.scalarOrNull() ?: throw ConversionException("bom must be a coordinate"),
                    bom = true,
                )
                details.scalarOrNull() != null -> shorthandDependency(key, details.scalarOrNull()!!)
                else -> {
                    val detailMap = details as? Value.Mapping
                    RawDependency(
                        notation = key,
                        scope = detailMap?.string("scope") ?: "all",
                        exported = detailMap?.boolean("exported") ?: false,
                    )
                }
            }
        }
        else -> throw ConversionException("Dependencies must be strings or objects")
    }

    private fun shorthandDependency(notation: String, shorthand: String): RawDependency = when (shorthand) {
        "all" -> RawDependency(notation)
        "compile-only", "runtime-only" -> RawDependency(notation, scope = shorthand)
        "exported" -> RawDependency(notation, exported = true)
        else -> throw ConversionException("Dependency '$notation' has unknown scope '$shorthand'")
    }

    private fun bindRepositories(config: Value.Mapping): List<RawRepository> =
        config.value("repositories").asSequence("repositories").mapIndexed { index, value ->
            when (value) {
                // A bare URL carries no id of its own; naming it is the interpret stage's job.
                is Value.Scalar -> RawRepository(id = null, url = value.text)
                is Value.Mapping -> RawRepository(
                    id = value.string("id"),
                    url = value.string("url") ?: throw ConversionException("repositories[$index].url is required"),
                    resolve = value.boolean("resolve") ?: true,
                    publish = value.boolean("publish") ?: false,
                    credentials = (value.value("credentials") as? Value.Mapping)?.let { credentials ->
                        RawCredentials(
                            file = credentials.string("file")
                                ?: throw ConversionException("repositories[$index].credentials.file is required"),
                            usernameKey = credentials.string("usernameKey")
                                ?: throw ConversionException("repositories[$index].credentials.usernameKey is required"),
                            passwordKey = credentials.string("passwordKey")
                                ?: throw ConversionException("repositories[$index].credentials.passwordKey is required"),
                        )
                    },
                )
                else -> throw ConversionException("repositories[$index] must be a URL string or object")
            }
        }

    /**
     * A qualified section that is not an object, or whose values are malformed, is reported as a
     * [io.heapy.ktctogradle.Diagnostic] and dropped by the stage that knows the module's platforms.
     * The binder therefore binds such a section as empty and records no error, but it does record
     * which keys the section got wrong, in declaration order, because that stage may not walk the
     * YAML itself and a key such as `settings@jvm.foo.bar` has no field of its own to bind to.
     */
    private fun bindQualifiedSections(config: Value.Mapping): List<QualifiedSection> = buildList {
        for ((key, value) in config.entries) {
            val test = key.startsWith(TEST_SETTINGS_PREFIX)
            val qualifier = when {
                test -> key.removePrefix(TEST_SETTINGS_PREFIX)
                key.startsWith(SETTINGS_PREFIX) -> key.removePrefix(SETTINGS_PREFIX)
                else -> continue
            }
            val section = value as? Value.Mapping
            add(
                QualifiedSection(
                    key = key,
                    qualifier = qualifier,
                    test = test,
                    settings = section?.let { bindSettings(it, testSettings = null, lenient = true, errors = null) },
                    // A test-settings@ section is dropped whole, so its keys are never inspected.
                    unsupportedKeys = if (test || section == null) emptyList() else unsupportedKeys(section),
                ),
            )
        }
    }

    /**
     * Every key of a qualified section the converter cannot carry into the Gradle build.
     *
     * Only `kotlin` has a Gradle equivalent per target, and only six of its keys; everything else is
     * named by its leaf path so the diagnostic can point at the exact key that was dropped.
     */
    private fun unsupportedKeys(settings: Value.Mapping): List<UnsupportedKey> = buildList {
        for ((section, value) in settings.entries) {
            if (section != "kotlin") {
                for (path in leafPaths(section, value)) add(UnsupportedKey(path, UnsupportedKey.UNSUPPORTED))
                continue
            }
            val kotlin = value as? Value.Mapping
            if (kotlin == null) {
                add(UnsupportedKey(section, "must be an object"))
                continue
            }
            for ((key, option) in kotlin.entries) {
                when (key) {
                    "languageVersion", "apiVersion" ->
                        if (option.scalarOrNull() == null) add(UnsupportedKey("kotlin.$key", "must be a string"))
                    "allWarningsAsErrors", "progressiveMode" ->
                        if (kotlin.boolean(key) == null) add(UnsupportedKey("kotlin.$key", "must be true or false"))
                    "freeCompilerArgs", "optIns" ->
                        if (option !is Value.Sequence) add(UnsupportedKey("kotlin.$key", "must be a list"))
                    else -> for (path in leafPaths("kotlin.$key", option)) {
                        add(UnsupportedKey(path, UnsupportedKey.UNSUPPORTED))
                    }
                }
            }
        }
    }

    /** Every scalar, list or empty node under [prefix], as the dotted path that reaches it. */
    private fun leafPaths(prefix: String, value: Value): List<String> = when (value) {
        is Value.Mapping -> value.entries.flatMap { (key, child) -> leafPaths("$prefix.$key", child) }
        else -> listOf(prefix)
    }

    private fun bindSettings(
        settings: Value.Mapping?,
        testSettings: Value.Mapping?,
        lenient: Boolean,
        errors: MutableMap<String, String>?,
    ): Settings {
        if (settings == null && testSettings == null) return Settings.EMPTY
        val present = settings ?: Value.Mapping(emptyMap())
        return Settings(
            kotlin = present.value("kotlin")?.let {
                KotlinSettings(
                    version = present.string("kotlin.version"),
                    languageVersion = present.string("kotlin.languageVersion"),
                    apiVersion = present.string("kotlin.apiVersion"),
                    allWarningsAsErrors = present.boolean("kotlin.allWarningsAsErrors"),
                    progressiveMode = present.boolean("kotlin.progressiveMode"),
                    freeCompilerArgs = present.stringList("kotlin.freeCompilerArgs", "settings.", lenient),
                    optIns = present.stringList("kotlin.optIns", "settings.", lenient),
                    serialization = bindSerialization(present.value("kotlin.serialization"), lenient, errors),
                )
            },
            jvm = present.value("jvm")?.let {
                JvmSettings(
                    jdkVersion = present.string("jvm.jdk.version"),
                    release = present.string("jvm.release"),
                    mainClass = present.string("jvm.mainClass"),
                    testFreeJvmArgs = present.stringList("jvm.test.freeJvmArgs", "settings.", lenient),
                    testSystemProperties = stringMap(present.value("jvm.test.systemProperties")),
                    testExtraEnvironment = stringMap(present.value("jvm.test.extraEnvironment")),
                )
            },
            android = present.value("android")?.let {
                AndroidSettings(
                    namespace = present.string("android.namespace"),
                    compileSdk = present.string("android.compileSdk") ?: present.string("android.compileSdk.apiLevel"),
                    minSdk = present.string("android.minSdk"),
                    targetSdk = present.string("android.targetSdk"),
                    applicationId = present.string("android.applicationId"),
                    versionCode = present.string("android.versionCode"),
                    versionName = present.string("android.versionName"),
                )
            },
            native = present.value("native")?.let { NativeSettings(entryPoint = present.string("native.entryPoint")) },
            junit = present.string("junit"),
            ktor = present.value("ktor")?.let {
                KtorSettings(
                    enabled = present.boolean("ktor") ?: present.boolean("ktor.enabled"),
                    version = present.string("ktor.version"),
                )
            },
            test = testSettings?.let {
                TestSettings(
                    freeJvmArgs = it.stringList("jvm.freeJvmArgs", "test-settings.", lenient),
                    systemProperties = stringMap(it.value("jvm.systemProperties")),
                    extraEnvironment = stringMap(it.value("jvm.extraEnvironment")),
                )
            },
        )
    }

    private fun bindSerialization(
        node: Value?,
        lenient: Boolean,
        errors: MutableMap<String, String>?,
    ): SerializationSpec? = when (node) {
        null -> null
        is Value.Scalar -> when (node.text) {
            "disabled", "false" -> null
            "enabled", "true" -> SerializationSpec()
            else -> SerializationSpec(format = node.text)
        }
        is Value.Mapping -> if (node.boolean("enabled") == false) {
            null
        } else {
            SerializationSpec(version = node.string("version"), format = node.string("format"))
        }
        else -> {
            if (!lenient) {
                errors?.put("settings.kotlin.serialization", "settings.kotlin.serialization must be a string or object")
            }
            null
        }
    }

    /**
     * A list of scalars. In a qualified section a malformed list is dropped instead of reported;
     * elsewhere it raises the message it raises today, which quotes the path from the module root,
     * hence [prefix].
     */
    private fun Value.Mapping.stringList(path: String, prefix: String, lenient: Boolean): List<String> = if (lenient) {
        (value(path) as? Value.Sequence)?.items?.mapNotNull(Value::scalarOrNull).orEmpty()
    } else {
        value(path).asSequence("$prefix$path").mapIndexed { index, item ->
            item.scalarOrNull() ?: throw ConversionException("Expected a string at $prefix$path[$index]")
        }
    }

    private fun stringMap(value: Value?): Map<String, String> =
        (value as? Value.Mapping)?.entries?.mapValues { (_, item) -> item.scalarOrNull().orEmpty() }.orEmpty()

    private const val SETTINGS_PREFIX = "settings@"

    private const val TEST_SETTINGS_PREFIX = "test-settings@"

    private val SCOPE_SUFFIX = Regex("^(.*):\\s+(all|compile-only|runtime-only|exported)$")

    private val UNSUPPORTED_KEYS = listOf("plugins", "mavenPlugins")

    private val UNSUPPORTED_SETTINGS = listOf(
        "settings.compose", "settings.springBoot", "settings.lombok", "settings.kotlin.ksp",
        "settings.kotlin.rpc", "settings.kotlin.dataframe", "settings.kotlin.compilerPlugins",
    )
}
