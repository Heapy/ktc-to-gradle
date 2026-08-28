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
     * [displayName] is the module name the deferred messages quote: a malformed `dependencies:` in
     * module `app` is reported against `app.dependencies`.
     */
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
        // First failure wins: a region fed by more than one key reports the key read first, which is
        // the one the pre-pipeline renderer reached first.
        errors.getOrPut(region) { error.message.orEmpty() }
        fallback
    }

    /**
     * [deferred] where the caller may have no error map, which is the case for a qualified section:
     * it binds leniently and raises nothing, so there is nothing to defer.
     */
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
     * The `<prefix>` section plus every `<prefix>@<qualifier>` section, keyed by qualifier with `""`
     * standing for the unqualified one. Each section defers its own failure, because a module can
     * name a bad dependency under one qualifier and a good one under another.
     *
     * A section is read twice, because its two classes of failure reach the user from two different
     * stages. The *shape* of a section — that it is a list of strings or one-coordinate objects — is
     * what the load stage gates on, for every declared section; whether an entry names a known scope
     * shorthand or a well-formed `bom` coordinate is only ever decided when a section is actually
     * read, so it defers under [Region.dependencyContent] instead and never fails a qualifier this
     * product ignores. When the content pass fails, the section still binds to the notations the
     * shape pass found, so the load stage can resolve their local references either way.
     *
     * A key that merely starts with the prefix — `dependencies-dev` — is bound under itself as the
     * qualifier: no product reads such a qualifier, but the load stage still checks the section.
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

    /**
     * The coordinate an entry names, checking only what the load stage checks.
     *
     * A `bom` entry names the coordinate `bom` here, not the coordinate it holds: the load stage has
     * never resolved a bom's notation, so an unknown module under a `bom:` is left to the stage that
     * reads it.
     */
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
     *
     * A `test-settings@` section binds under [Settings.test], exactly as the unqualified
     * `test-settings:` does, so the two forms of the same three keys read the same afterwards.
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
                    // A test-settings@ section contributes no compiler option, so a key it got wrong
                    // suppresses none either: it must not clear what a broader settings@ declared.
                    malformedOptions = if (test) emptySet() else malformedOptions(unsupportedKeys),
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

    /**
     * `settings@<qualifier>.jvm`, of which only the `test` node reaches a Gradle task.
     *
     * A `jvm` that is not an object keeps the whole-node wording the walk gives every other section,
     * because there is then no key underneath to point at.
     */
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

    /**
     * A `test-settings@<qualifier>` section, which carries the same three keys one level higher.
     *
     * `test-settings.jvm.release` is not among them: it reaches a *compilation* rather than a `Test`
     * task, and a qualified one has no per-target spelling yet, so it is reported and dropped.
     */
    private fun unsupportedTestSectionKeys(settings: Value.Mapping): List<UnsupportedKey> = buildList {
        for ((section, value) in settings.entries) {
            if (section == "jvm") {
                addAll(testSettingKeys("jvm", value))
                continue
            }
            for (path in leafPaths(section, value)) add(UnsupportedKey(path, UnsupportedKey.UNSUPPORTED))
        }
    }

    /**
     * The three keys a Gradle `Test` task takes, reported by shape rather than by name.
     *
     * A malformed one binds to nothing just as an absent one does, so it has to be named here or it
     * vanishes without a word.
     */
    private fun testSettingKeys(prefix: String, node: Value): List<UnsupportedKey> {
        val test = node as? Value.Mapping ?: return listOf(UnsupportedKey(prefix, "must be an object"))
        return buildList {
            for ((key, value) in test.entries) {
                when (key) {
                    "freeJvmArgs" -> if (value !is Value.Sequence) {
                        add(UnsupportedKey("$prefix.$key", "must be a list"))
                    }
                    "systemProperties", "extraEnvironment" -> if (value !is Value.Mapping) {
                        add(UnsupportedKey("$prefix.$key", "must be an object"))
                    }
                    else -> for (path in leafPaths("$prefix.$key", value)) {
                        add(UnsupportedKey(path, UnsupportedKey.UNSUPPORTED))
                    }
                }
            }
        }
    }

    /**
     * Which compiler options a section declared and got wrong, read off the same walk that reported
     * them.
     *
     * The walk names the option each key stands for, so this only collects them. Deriving it from
     * [UnsupportedKey.path] instead would read a literal `kotlin.languageVersion` key — one key of
     * the section, dropped like any other — as the module getting `languageVersion` wrong, and a
     * broader section's real value would be suppressed by a key that never contributed one.
     *
     * A dropped key that has no compiler option behind it — `settings@jvm.jvm.release`, an unknown
     * `kotlin.foo` — is not listed: it overrides nothing because it contributes nothing either way.
     */
    private fun malformedOptions(keys: List<UnsupportedKey>): Set<String> =
        keys.flatMapTo(mutableSetOf(), UnsupportedKey::options)

    /**
     * Every scalar, list or empty node under [prefix], as the dotted path that reaches it.
     *
     * An empty object has no leaf under it, so it stands for itself. Recursing into it instead
     * would return no path at all, and a key the module wrote would be dropped without a word —
     * which is the one outcome this walk exists to prevent.
     */
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
                    jdkVersion = present.integer("jvm.jdk.version", "settings.", lenient),
                    release = present.integer("jvm.release", "settings.", lenient),
                    mainClass = present.string("jvm.mainClass"),
                    // A malformed argument list defers under its own region, so it is raised by the
                    // interpreter that reads the test settings and not by an unrelated section.
                    testFreeJvmArgs = deferring(errors, Region.JVM_TEST_SETTINGS, emptyList()) {
                        present.stringList("jvm.test.freeJvmArgs", "settings.", lenient)
                    },
                    testSystemProperties = stringMap(present.value("jvm.test.systemProperties")),
                    testExtraEnvironment = stringMap(present.value("jvm.test.extraEnvironment")),
                )
            },
            android = present.value("android")?.let {
                AndroidSettings(
                    namespace = present.string("android.namespace"),
                    // The bare level and the nested `compileSdk: { apiLevel: }` form bind to one
                    // field, so which of the two the module wrote is decided before it is read: an
                    // object is the nested form and not a level that failed to be an integer.
                    compileSdk = if (present.value("android.compileSdk") is Value.Mapping) {
                        present.integer("android.compileSdk.apiLevel", "settings.", lenient)
                    } else {
                        present.integer("android.compileSdk", "settings.", lenient)
                    },
                    minSdk = present.integer("android.minSdk", "settings.", lenient),
                    targetSdk = present.integer("android.targetSdk", "settings.", lenient),
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
                    systemProperties = stringMap(it.value("jvm.systemProperties")),
                    extraEnvironment = stringMap(it.value("jvm.extraEnvironment")),
                    release = it.integer("jvm.release", "test-settings.", lenient),
                )
            },
        )
    }

    /**
     * `settings.publishing`, read whole.
     *
     * Nothing here defers: the section carries no value the converter has to reject, so a key it
     * cannot use is reported by the interpret stage rather than failing the load.
     */
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
        // The scalar form is the switch alone: `mavenCentral: enabled`.
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

    /**
     * `scm` is either the object or the URL alone.
     *
     * The Toolchain derives both connection strings from that URL as `scm:git:<url>`, so the
     * shorthand is expanded here rather than in the interpret stage: it is a spelling of the same
     * section and not a decision the converter makes.
     */
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

    /**
     * `settings.kotlin.compilerPlugins`, a list of third-party Kotlin compiler plugins.
     *
     * The Toolchain form has exactly three keys, so nothing here is dropped: `id` and `dependency`
     * are required, and `options` is a map of strings. A qualified section binds leniently and takes
     * the entries it can read, because it raises nothing.
     */
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

    /**
     * A compiler plugin's `options`, which the compiler takes as a flat map of strings.
     *
     * Anything else is refused rather than coerced: an option silently bound to `""` reaches the
     * compiler as a real setting, and the plugin behaves differently for a reason nothing names.
     */
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

    /**
     * A list of scalars. In a qualified section a malformed list is dropped instead of reported;
     * elsewhere it raises, quoting the path from the module root, hence [prefix].
     */
    private fun Value.Mapping.stringList(path: String, prefix: String, lenient: Boolean): List<String> = if (lenient) {
        (value(path) as? Value.Sequence)?.items?.mapNotNull(Value::scalarOrNull).orEmpty()
    } else {
        value(path).asSequence("$prefix$path").mapIndexed { index, item ->
            item.scalarOrNull() ?: throw ConversionException("Expected a string at $prefix$path[$index]")
        }
    }

    /**
     * A setting the Toolchain schema types as an integer, bound as the literal Kotlin spells it.
     *
     * The Gradle DSL takes these as bare integer literals, so anything else would be interpolated
     * into a build script that does not parse, and the converter would report success for a project
     * whose first `./gradlew` run fails on a syntax error. The Toolchain answers the same input with
     * "Expected: integer"; this says the same thing at the same point, and names the key.
     *
     * The value is re-spelled rather than passed through, because the two languages accept different
     * texts for the same number: the Toolchain reads `036` as `36`, and Kotlin rejects a leading zero
     * outright. Re-spelling is what the Toolchain prints back, so it is also what the module meant.
     *
     * A qualified section drops what it cannot read rather than raising, hence [lenient].
     */
    private fun Value.Mapping.integer(path: String, prefix: String, lenient: Boolean): String? {
        val node = value(path)
        if (node == null || node is Value.Null) return null
        val text = node.scalarOrNull()
        text?.toIntOrNull()?.let { return it.toString() }
        if (lenient) return null
        // A list or an object is reported without quoting a value, because there is no scalar the
        // module wrote to quote back at it.
        val actual = if (text == null) "" else ", but was '$text'"
        throw ConversionException("$prefix$path must be an integer$actual")
    }

    private fun stringMap(value: Value?): Map<String, String> =
        (value as? Value.Mapping)?.entries?.mapValues { (_, item) -> item.scalarOrNull().orEmpty() }.orEmpty()

    private const val SETTINGS_PREFIX = "settings@"

    private const val TEST_SETTINGS_PREFIX = "test-settings@"

    private val SCOPE_SUFFIX = Regex("^(.*):\\s+(all|compile-only|runtime-only|exported)$")

    /**
     * The top-level keys the converter refuses. `ProjectInterpreter` phrases these differently from
     * the `settings.` paths below, so it reads this list rather than restating it.
     */
    internal val UNSUPPORTED_KEYS = listOf("plugins", "mavenPlugins")

    private val UNSUPPORTED_SETTINGS = listOf(
        "settings.compose", "settings.springBoot", "settings.lombok", "settings.kotlin.ksp",
        "settings.kotlin.rpc", "settings.kotlin.dataframe",
    )
}
