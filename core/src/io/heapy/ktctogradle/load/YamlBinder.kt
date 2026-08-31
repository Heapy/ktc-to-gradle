package io.heapy.ktctogradle.load

import io.heapy.ktctogradle.ConversionException

/** Binds leniently, recording regional failures for the stage that consumes each value. */
internal object YamlBinder {
    fun bind(config: Value.Mapping, displayName: String): ToolchainModel {
        val errors = mutableMapOf<String, String>()
        return ToolchainModel(
            product = deferred(errors, Region.PRODUCT, ProductSpec("", emptyList())) { product(config) },
            layout = if (config.string("layout") == "maven-like") Layout.MAVEN_LIKE else Layout.AMPER,
            description = config.string("description"),
            aliases = deferred(errors, Region.ALIASES, emptyMap()) { bindAliases(config) },
            dependencies = bindDependencies(config, "dependencies", displayName, errors),
            testDependencies = bindDependencies(config, "test-dependencies", displayName, errors),
            repositories = deferred(errors, Region.REPOSITORIES, emptyList()) { bindRepositories(config) },
            settings = deferred(errors, Region.SETTINGS, Settings.EMPTY) {
                bindSettings(
                    settings = config.value("settings") as? Value.Mapping,
                    testSettings = config.value("test-settings") as? Value.Mapping,
                    lenient = false,
                    errors = errors,
                )
            },
            qualifiedSections = bindQualifiedSections(config),
            unsupported = bindUnsupported(config),
            unknownKeys = unknownModuleKeys(config),
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
        // A region reports its first failure.
        errors.getOrPut(region) { error.message.orEmpty() }
        fallback
    }

    private inline fun <T> deferring(
        errors: MutableMap<String, String>?,
        region: String,
        fallback: T,
        bind: () -> T,
    ): T = if (errors == null) bind() else deferred(errors, region, fallback, bind)

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
     * Binds each prefixed dependency section independently. Shape failures are load-stage regions;
     * scope and BOM failures use [Region.dependencyContent] so ignored qualifiers do not fail.
     * Prefix matching intentionally includes keys such as `dependencies-dev`.
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
                key.startsWith(prefix) -> key
                else -> continue
            }
            put(qualifier, bindDependencySection(value, "$displayName.$key", key, errors))
        }
    }

    private fun bindDependencySection(
        value: Value,
        path: String,
        key: String,
        errors: MutableMap<String, String>,
    ): List<RawDependency> {
        val items: List<Value>
        val notations: List<String>
        try {
            items = value.asSequence(path)
            notations = items.map(::dependencyNotation)
        } catch (error: ConversionException) {
            errors[key] = error.message.orEmpty()
            return emptyList()
        }
        return deferred(errors, Region.dependencyContent(key), notations.map { RawDependency(it) }) {
            items.map(::bindDependency)
        }
    }

    /** A BOM's nested notation remains reader-only and is not resolved during load. */
    private fun dependencyNotation(value: Value): String = when (value) {
        is Value.Scalar -> SCOPE_SUFFIX.matchEntire(value.text)?.groupValues?.get(1) ?: value.text
        is Value.Mapping -> value.entries.keys.singleOrNull()
            ?: throw ConversionException("A dependency object must have exactly one coordinate")
        else -> throw ConversionException("Dependency entries must be strings or objects")
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
            if (value.entries.size != 1) throw ConversionException("A dependency object must have exactly one coordinate")
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
        else -> throw ConversionException("Dependency entries must be strings or objects")
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
     * Qualified sections never defer failures: they bind what they can and record dropped keys for
     * platform-aware diagnostics. `test-settings@` binds under [Settings.test].
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
            val unsupportedKeys = when {
                section == null -> emptyList()
                test -> unsupportedTestSectionKeys(section)
                else -> unsupportedKeys(section)
            }
            add(
                QualifiedSection(
                    key = key,
                    qualifier = qualifier,
                    test = test,
                    settings = section?.let {
                        if (test) {
                            bindSettings(settings = null, testSettings = it, lenient = true, errors = null)
                        } else {
                            bindSettings(it, testSettings = null, lenient = true, errors = null)
                        }
                    },
                    unsupportedKeys = unsupportedKeys,
                    malformedOptions = if (test) emptySet() else malformedOptions(unsupportedKeys),
                ),
            )
        }
    }

    /** Names every unsupported qualified key by its leaf path. */
    private fun unsupportedKeys(settings: Value.Mapping): List<UnsupportedKey> = buildList {
        for ((section, value) in settings.entries) {
            if (section == "jvm") {
                addAll(unsupportedJvmKeys(value))
                continue
            }
            if (section != QualifiedOption.KOTLIN) {
                for (path in leafPaths(section, value)) add(UnsupportedKey(path, UnsupportedKey.UNSUPPORTED))
                continue
            }
            val kotlin = value as? Value.Mapping
            if (kotlin == null) {
                add(UnsupportedKey(section, "must be an object", QualifiedOption.ALL))
                continue
            }
            for ((key, option) in kotlin.entries) {
                when (key) {
                    "languageVersion", "apiVersion" -> if (option.scalarOrNull() == null) {
                        add(UnsupportedKey("kotlin.$key", "must be a string", setOf(key)))
                    }
                    "allWarningsAsErrors", "progressiveMode" -> if (kotlin.boolean(key) == null) {
                        add(UnsupportedKey("kotlin.$key", "must be true or false", setOf(key)))
                    }
                    "freeCompilerArgs", "optIns" -> if (option !is Value.Sequence) {
                        add(UnsupportedKey("kotlin.$key", "must be a list", setOf(key)))
                    }
                    else -> for (path in leafPaths("kotlin.$key", option)) {
                        add(UnsupportedKey(path, UnsupportedKey.UNSUPPORTED))
                    }
                }
            }
        }
    }

    /** Only the `test` child of a qualified `jvm` node reaches Gradle. */
    private fun unsupportedJvmKeys(node: Value): List<UnsupportedKey> {
        val jvm = node as? Value.Mapping
            ?: return leafPaths("jvm", node).map { UnsupportedKey(it, UnsupportedKey.UNSUPPORTED) }
        return buildList {
            for ((key, value) in jvm.entries) {
                if (key == "test") {
                    addAll(testSettingKeys("jvm.test", value))
                    continue
                }
                for (path in leafPaths("jvm.$key", value)) add(UnsupportedKey(path, UnsupportedKey.UNSUPPORTED))
            }
        }
    }

    /** Qualified test settings support task options but not per-target compilation release. */
    private fun unsupportedTestSectionKeys(settings: Value.Mapping): List<UnsupportedKey> = buildList {
        for ((section, value) in settings.entries) {
            if (section == "jvm") {
                addAll(testSettingKeys("jvm", value))
                continue
            }
            for (path in leafPaths(section, value)) add(UnsupportedKey(path, UnsupportedKey.UNSUPPORTED))
        }
    }

    /** Reports malformed task options at the offending list index or map key. */
    private fun testSettingKeys(prefix: String, node: Value): List<UnsupportedKey> {
        val test = node as? Value.Mapping ?: return listOf(UnsupportedKey(prefix, "must be an object"))
        return buildList {
            for ((key, value) in test.entries) {
                when (key) {
                    "freeJvmArgs" -> if (value !is Value.Sequence) {
                        add(UnsupportedKey("$prefix.$key", "must be a list"))
                    } else {
                        value.items.forEachIndexed { index, item ->
                            if (item.scalarOrNull() == null) {
                                add(UnsupportedKey("$prefix.$key[$index]", "must be a string"))
                            }
                        }
                    }
                    "systemProperties", "extraEnvironment" -> if (value !is Value.Mapping) {
                        add(UnsupportedKey("$prefix.$key", "must be an object"))
                    } else {
                        for ((name, item) in value.entries) {
                            if (item.scalarOrNull() == null) {
                                add(UnsupportedKey("$prefix.$key.$name", "must be a string"))
                            }
                        }
                    }
                    else -> for (path in leafPaths("$prefix.$key", value)) {
                        add(UnsupportedKey(path, UnsupportedKey.UNSUPPORTED))
                    }
                }
            }
        }
    }

    /** Uses structural option metadata, not dotted display paths, to track malformed declarations. */
    private fun malformedOptions(keys: List<UnsupportedKey>): Set<String> =
        keys.flatMapTo(mutableSetOf(), UnsupportedKey::options)

    /** Treats an empty object as its own leaf so a written key cannot disappear from diagnostics. */
    private fun leafPaths(prefix: String, value: Value): List<String> =
        if (value is Value.Mapping && value.entries.isNotEmpty()) {
            value.entries.flatMap { (key, child) -> leafPaths("$prefix.$key", child) }
        } else {
            listOf(prefix)
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
                    compilerPlugins = bindCompilerPlugins(present.value("kotlin.compilerPlugins"), lenient),
                )
            },
            jvm = present.value("jvm")?.let {
                JvmSettings(
                    jdkVersion = present.integer("jvm.jdk.version", "settings.", lenient, atLeast = JDK_FLOOR),
                    release = present.integer("jvm.release", "settings.", lenient),
                    mainClass = present.string("jvm.mainClass"),
                    testFreeJvmArgs = deferring(errors, Region.JVM_TEST_SETTINGS, emptyList()) {
                        present.stringList("jvm.test.freeJvmArgs", "settings.", lenient)
                    },
                    testSystemProperties = deferring(errors, Region.JVM_TEST_SETTINGS, emptyMap()) {
                        present.stringMap("jvm.test.systemProperties", "settings.", lenient)
                    },
                    testExtraEnvironment = deferring(errors, Region.JVM_TEST_SETTINGS, emptyMap()) {
                        present.stringMap("jvm.test.extraEnvironment", "settings.", lenient)
                    },
                    testJunitPlatformVersion = present.string("jvm.test.junitPlatformVersion"),
                )
            },
            android = present.value("android")?.let {
                AndroidSettings(
                    namespace = present.string("android.namespace"),
                    // Select the scalar or nested spelling before integer validation.
                    compileSdk = if (present.value("android.compileSdk") is Value.Mapping) {
                        present.integer("android.compileSdk.apiLevel", "settings.", lenient, atLeast = ANDROID_FLOOR)
                    } else {
                        present.integer("android.compileSdk", "settings.", lenient, atLeast = ANDROID_FLOOR)
                    },
                    minSdk = present.integer("android.minSdk", "settings.", lenient, atLeast = ANDROID_FLOOR),
                    targetSdk = present.integer("android.targetSdk", "settings.", lenient, atLeast = ANDROID_FLOOR),
                    applicationId = present.string("android.applicationId"),
                    versionCode = present.integer("android.versionCode", "settings.", lenient),
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
            publishing = bindPublishing(present.value("publishing"), lenient),
            test = testSettings?.let {
                TestSettings(
                    freeJvmArgs = deferring(errors, Region.JVM_TEST_SETTINGS, emptyList()) {
                        it.stringList("jvm.freeJvmArgs", "test-settings.", lenient)
                    },
                    systemProperties = deferring(errors, Region.JVM_TEST_SETTINGS, emptyMap()) {
                        it.stringMap("jvm.systemProperties", "test-settings.", lenient)
                    },
                    extraEnvironment = deferring(errors, Region.JVM_TEST_SETTINGS, emptyMap()) {
                        it.stringMap("jvm.extraEnvironment", "test-settings.", lenient)
                    },
                    release = it.integer("jvm.release", "test-settings.", lenient),
                )
            },
        )
    }

    private fun bindPublishing(node: Value?, lenient: Boolean): PublishingSettings? {
        val mapping = node as? Value.Mapping ?: return null
        return PublishingSettings(
            enabled = mapping.boolean("enabled"),
            group = mapping.string("group"),
            artifactId = mapping.string("artifactId"),
            version = mapping.string("version"),
            publishSources = mapping.boolean("publishSources"),
            signArtifacts = mapping.boolean("signArtifacts"),
            checksums = mapping.stringList("checksums", "settings.publishing.", lenient),
            mavenCentral = bindMavenCentral(mapping.value("mavenCentral")),
            pom = bindPom(mapping.value("pom"), lenient),
        )
    }

    private fun bindMavenCentral(node: Value?): MavenCentralSpec? = when (node) {
        null -> null
        is Value.Mapping -> MavenCentralSpec(
            enabled = node.boolean("enabled"),
            publishingMode = node.string("publishingMode"),
        )
        else -> MavenCentralSpec(enabled = node.scalarOrNull().let { it == "enabled" || it == "true" })
    }

    private fun bindPom(node: Value?, lenient: Boolean): PomSpec? {
        val mapping = node as? Value.Mapping ?: return null
        return PomSpec(
            name = mapping.string("name"),
            description = mapping.string("description"),
            url = mapping.string("url"),
            licenses = pomEntries(mapping.value("licenses"), "settings.publishing.pom.licenses", lenient) {
                PomLicense(name = it.string("name"), url = it.string("url"))
            },
            developers = pomEntries(mapping.value("developers"), "settings.publishing.pom.developers", lenient) {
                PomDeveloper(
                    id = it.string("id"),
                    name = it.string("name"),
                    url = it.string("url"),
                    email = it.string("email"),
                    organization = it.string("organization"),
                    organizationUrl = it.string("organizationUrl"),
                )
            },
            scm = bindScm(mapping.value("scm")),
        )
    }

    private fun <T> pomEntries(
        node: Value?,
        path: String,
        lenient: Boolean,
        entry: (Value.Mapping) -> T,
    ): List<T> {
        if (node == null) return emptyList()
        val items = (node as? Value.Sequence)?.items
            ?: if (lenient) return emptyList() else throw ConversionException("$path must be a list")
        return items.mapIndexedNotNull { index, item ->
            val mapping = item as? Value.Mapping
                ?: if (lenient) return@mapIndexedNotNull null else throw ConversionException("$path[$index] must be an object")
            entry(mapping)
        }
    }

    /** Expands Toolchain's scalar SCM shorthand while binding its syntax. */
    private fun bindScm(node: Value?): PomScm? = when (node) {
        null -> null
        is Value.Mapping -> PomScm(
            url = node.string("url"),
            connection = node.string("connection") ?: node.string("url")?.let { "scm:git:$it" },
            developerConnection = node.string("developerConnection") ?: node.string("url")?.let { "scm:git:$it" },
        )
        else -> node.scalarOrNull()?.let { url ->
            PomScm(url = url, connection = "scm:git:$url", developerConnection = "scm:git:$url")
        }
    }

    private fun bindCompilerPlugins(node: Value?, lenient: Boolean): List<CompilerPluginSpec> {
        if (node == null) return emptyList()
        val items = (node as? Value.Sequence)?.items
            ?: if (lenient) return emptyList() else throw ConversionException("settings.kotlin.compilerPlugins must be a list")
        return items.mapIndexedNotNull { index, item ->
            val path = "settings.kotlin.compilerPlugins[$index]"
            val entry = item as? Value.Mapping
                ?: if (lenient) return@mapIndexedNotNull null else throw ConversionException("$path must be an object")
            val id = entry.string("id")
            val dependency = entry.string("dependency")
            if (id == null || dependency == null) {
                if (lenient) return@mapIndexedNotNull null
                throw ConversionException("$path.${if (id == null) "id" else "dependency"} is required")
            }
            CompilerPluginSpec(
                id = id,
                dependency = dependency,
                options = compilerPluginOptions(entry.value("options"), "$path.options", lenient),
            )
        }
    }

    /** Refuses non-string plugin options instead of coercing them to behavior-changing empty values. */
    private fun compilerPluginOptions(node: Value?, path: String, lenient: Boolean): Map<String, String> {
        if (node == null) return emptyMap()
        val mapping = node as? Value.Mapping
            ?: if (lenient) return emptyMap() else throw ConversionException("$path must be an object")
        return buildMap {
            for ((key, value) in mapping.entries) {
                val text = value.scalarOrNull()
                    ?: if (lenient) continue else throw ConversionException("$path.$key must be a string")
                put(key, text)
            }
        }
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
                errors?.getOrPut(Region.SERIALIZATION) {
                    "settings.kotlin.serialization must be a string or object"
                }
            }
            null
        }
    }

    private fun Value.Mapping.stringList(path: String, prefix: String, lenient: Boolean): List<String> = if (lenient) {
        (value(path) as? Value.Sequence)?.items?.mapNotNull(Value::scalarOrNull).orEmpty()
    } else {
        value(path).asSequence("$prefix$path").mapIndexed { index, item ->
            item.scalarOrNull() ?: throw ConversionException("Expected a string at $prefix$path[$index]")
        }
    }

    /**
     * Validates integer literals before unquoted Gradle emission and normalizes spellings such as
     * `036` to `36`. [atLeast] applies Toolchain's schema floor; [lenient] drops invalid qualified
     * values instead of raising.
     */
    private fun Value.Mapping.integer(path: String, prefix: String, lenient: Boolean, atLeast: Int? = null): String? {
        val node = value(path)
        if (node == null || node is Value.Null) return null
        val text = node.scalarOrNull()
        text?.toIntOrNull()?.let { number ->
            if (atLeast != null && number < atLeast) {
                if (lenient) return null
                throw ConversionException("$prefix$path must be at least $atLeast, but was $number")
            }
            return number.toString()
        }
        if (lenient) return null
        val actual = if (text == null) "" else ", but was '$text'"
        throw ConversionException("$prefix$path must be an integer$actual")
    }

    /** Refuses non-scalar map values; qualified sections drop and report them through their key walk. */
    private fun Value.Mapping.stringMap(path: String, prefix: String, lenient: Boolean): Map<String, String> {
        val mapping = value(path) as? Value.Mapping ?: return emptyMap()
        return buildMap {
            for ((key, item) in mapping.entries) {
                val text = item.scalarOrNull()
                    ?: if (lenient) continue else throw ConversionException("Expected a string at $prefix$path.$key")
                put(key, text)
            }
        }
    }

    private const val JDK_FLOOR = 17

    private const val ANDROID_FLOOR = 21

    private const val SETTINGS_PREFIX = "settings@"

    private const val TEST_SETTINGS_PREFIX = "test-settings@"

    private val SCOPE_SUFFIX = Regex("^(.*):\\s+(all|compile-only|runtime-only|exported)$")

    internal val UNSUPPORTED_KEYS = listOf("plugins", "mavenPlugins")

    private val UNSUPPORTED_SETTINGS = listOf(
        "settings.compose", "settings.springBoot", "settings.lombok", "settings.kotlin.ksp",
        "settings.kotlin.rpc", "settings.kotlin.dataframe",
    )
}
