package io.heapy.ktctogradle.interpret

import io.heapy.ktctogradle.ConversionException
import io.heapy.ktctogradle.DiagnosticCollector
import io.heapy.ktctogradle.load.ModuleIndex
import io.heapy.ktctogradle.load.QualifiedOption
import io.heapy.ktctogradle.load.Region
import io.heapy.ktctogradle.load.Settings
import io.heapy.ktctogradle.load.ToolchainModule
import io.heapy.ktctogradle.load.raiseDeferred
import io.heapy.ktctogradle.model.CompilerOptions
import io.heapy.ktctogradle.model.Dependency
import io.heapy.ktctogradle.model.DependencyTarget
import io.heapy.ktctogradle.model.JvmTestSettings
import io.heapy.ktctogradle.model.KmpSourceSet
import io.heapy.ktctogradle.model.KmpTarget
import io.heapy.ktctogradle.model.MultiplatformBuild
import io.heapy.ktctogradle.model.TargetKind
import io.heapy.ktctogradle.model.TestFramework

internal object MultiplatformInterpreter {
    fun interpret(index: ModuleIndex, module: ToolchainModule, diagnostics: DiagnosticCollector): MultiplatformBuild {
        val model = module.model
        val serialization = Serialization.of(model)
        val fragments = KmpFragments.of(model, module.displayName)
        val qualified = QualifiedSettings.of(module, fragments, JVM_BACKED_QUALIFIERS, diagnostics)
        val platforms = model.product.platforms
        val executable = model.product.type.endsWith("/app")
        val targets = platforms.map { platform ->
            target(
                module = module,
                platform = platform,
                executable = executable,
                compilerOptions = qualified.byPlatform[platform] ?: CompilerOptions.EMPTY,
                testSettings = qualified.testByPlatform[platform] ?: JvmTestSettings.EMPTY,
                diagnostics = diagnostics,
            )
        }
        model.raiseDeferred(Region.SETTINGS)
        val runsOnAJdk = targets.any { it.kind.runsOnAJdk }
        val testFramework = JvmInterpreter.testFramework(model)
        if (runsOnAJdk) {
            JvmInterpreter.warnAboutJunitNone(module, testFramework, diagnostics)
            JvmInterpreter.warnAboutJunitPlatformVersion(module, diagnostics)
        }
        return MultiplatformBuild(
            targets = targets,
            jvmToolchain = if (runsOnAJdk) {
                model.settings.jvm?.jdkVersion ?: Defaults.JVM_JDK
            } else {
                null
            },
            compilerOptions = JvmInterpreter.compilerOptions(model.settings.kotlin),
            qualifiedCompilerOptions = qualified.common,
            sourceSets = sourceSets(index, module, fragments, serialization, testFramework),
            testFramework = testFramework,
            testSettings = testSettings(module, runsOnAJdk, diagnostics) + qualified.commonTest,
        )
    }

    /** Reports and drops JVM test settings when the module has no Gradle `Test` task. */
    private fun testSettings(
        module: ToolchainModule,
        runsOnAJdk: Boolean,
        diagnostics: DiagnosticCollector,
    ): JvmTestSettings {
        if (runsOnAJdk) return JvmInterpreter.testSettings(module.model)
        val settings = JvmInterpreter.declaredTestSettings(module.model)
        if (settings.isEmpty) return settings
        val dropped = buildList {
            if (settings.freeJvmArgs.isNotEmpty()) add("freeJvmArgs")
            if (settings.systemProperties.isNotEmpty()) add("systemProperties")
            if (settings.environment.isNotEmpty()) add("extraEnvironment")
        }
        diagnostics.warn(
            "${module.displayName}: the JVM test settings ${dropped.joinToString()} name no JVM-backed " +
                "platform of this module and were dropped",
        )
        return JvmTestSettings.EMPTY
    }

    private fun target(
        module: ToolchainModule,
        platform: String,
        executable: Boolean,
        compilerOptions: CompilerOptions,
        testSettings: JvmTestSettings,
        diagnostics: DiagnosticCollector,
    ): KmpTarget {
        val model = module.model
        val kind = when (platform) {
            "jvm" -> TargetKind.Jvm(
                release = jvmRelease(model),
                testRelease = model.settings.test?.release,
                testSettings = testSettings,
            )
            "android" -> TargetKind.Android(AndroidInterpreter.libraryTarget(module, testSettings, diagnostics))
            "js" -> TargetKind.Js
            "wasmJs" -> TargetKind.WasmJs
            "wasmWasi" -> TargetKind.WasmWasi
            in KmpFragments.NATIVE_TARGETS -> TargetKind.Native
            else -> throw ConversionException("Unsupported Kotlin platform '$platform'")
        }
        return KmpTarget(
            name = platform,
            kind = kind,
            executable = executable,
            entryPoint = if (kind == TargetKind.Native) model.settings.native?.entryPoint else null,
            compilerOptions = compilerOptions,
        )
    }

    private fun sourceSets(
        index: ModuleIndex,
        module: ToolchainModule,
        fragments: List<KmpFragment>,
        serialization: SerializationSettings?,
        testFramework: TestFramework,
    ): List<KmpSourceSet> = buildList {
        add(
            KmpSourceSet(
                name = "commonMain",
                parents = emptyList(),
                test = false,
                builtIn = true,
                sourceDirs = listOf("src"),
                resourceDirs = listOf("resources"),
                dependencies = Dependencies.of(index, module, test = false, qualifiers = COMMON_QUALIFIERS) +
                    JvmInterpreter.implied(module.model, serialization),
            ),
        )
        add(
            KmpSourceSet(
                name = "commonTest",
                parents = emptyList(),
                test = true,
                builtIn = true,
                sourceDirs = listOf("test"),
                resourceDirs = listOf("testResources"),
                dependencies = listOf(Dependency(DependencyTarget.KotlinBuiltin("test"))) +
                    Dependencies.of(index, module, test = true, qualifiers = COMMON_QUALIFIERS),
            ),
        )
        for (fragment in fragments.filterNot { it.name == KmpFragments.COMMON }) {
            add(qualifiedSourceSet(index, module, fragment, test = false, testFramework = testFramework))
            add(qualifiedSourceSet(index, module, fragment, test = true, testFramework = testFramework))
        }
    }

    /**
     * Names the adapter that cannot live in `commonTest`. `junit: none` adds only the launcher;
     * AGP may still add its own adapter to `androidHostTest`, but that additive behavior is harmless.
     */
    private fun jvmTestFrameworkDependencies(framework: TestFramework): List<Dependency> = when (framework) {
        TestFramework.NONE -> JvmInterpreter.platformLauncher(framework)
        else -> listOf(Dependency(DependencyTarget.KotlinBuiltin(framework.library)))
    }

    private fun qualifiedSourceSet(
        index: ModuleIndex,
        module: ToolchainModule,
        fragment: KmpFragment,
        test: Boolean,
        testFramework: TestFramework,
    ): KmpSourceSet {
        val qualifier = fragment.name
        val prefix = if (test) "test" else "src"
        val resources = if (test) "testResources" else "resources"
        val frameworkDependencies = if (test && qualifier in JVM_BACKED_QUALIFIERS) {
            jvmTestFrameworkDependencies(testFramework)
        } else {
            emptyList()
        }
        val dependencies = frameworkDependencies + Dependencies.of(index, module, test, listOf(qualifier))
        val sourceDir = "$prefix@$qualifier"
        val resourceDir = "$resources@$qualifier"
        val suffix = if (test) "Test" else "Main"
        return KmpSourceSet(
            // AGP's unit-test source set is androidHostTest; androidTest is the on-device suite.
            name = if (qualifier == "android" && test) "androidHostTest" else "$qualifier$suffix",
            parents = fragment.parents.map { parent -> "$parent$suffix" },
            test = test,
            builtIn = false,
            sourceDirs = listOfNotNull(sourceDir.takeIf { it in module.layout.existingSourceDirs }),
            resourceDirs = listOfNotNull(resourceDir.takeIf { it in module.layout.existingSourceDirs }),
            dependencies = dependencies,
        )
    }

    private val COMMON_QUALIFIERS = listOf("")

    private val JVM_BACKED_QUALIFIERS = setOf("jvm", "android")
}

/** Resolves qualified settings; unsupported or inapplicable sections are reported and dropped. */
internal object QualifiedSettings {
    data class Resolved(
        val common: CompilerOptions,
        val byPlatform: Map<String, CompilerOptions>,
        val commonTest: JvmTestSettings,
        val testByPlatform: Map<String, JvmTestSettings>,
    )

    data class SinglePlatform(
        val options: CompilerOptions,
        val testSettings: JvmTestSettings,
    )

    /**
     * Splits common and per-platform contributions. [fragments] is broadest first and therefore also
     * defines override precedence; test settings that reach no `Test` task are reported.
     */
    fun of(
        module: ToolchainModule,
        fragments: List<KmpFragment>,
        jvmBackedPlatforms: Set<String>,
        diagnostics: DiagnosticCollector,
    ): Resolved {
        val resolved = resolve(module, fragments, jvmBackedPlatforms, diagnostics)
        return Resolved(
            common = resolved.common.options,
            byPlatform = resolved.byPlatform.mapValues { (_, contribution) -> contribution.options },
            commonTest = resolved.common.testSettings,
            testByPlatform = resolved.byPlatform.mapValues { (_, contribution) -> contribution.testSettings },
        )
    }

    private fun resolve(
        module: ToolchainModule,
        fragments: List<KmpFragment>,
        jvmBackedPlatforms: Set<String>,
        diagnostics: DiagnosticCollector,
    ): ResolvedContributions {
        val rank = fragments.withIndex().associate { (index, fragment) -> fragment.name to index }
        val platformsOf = fragments.associate { it.name to it.platforms }
        val sections = mutableListOf<RankedSection>()
        for (section in module.model.qualifiedSections) {
            val platforms = platformsOf[section.qualifier]
            if (platforms == null) {
                drop(diagnostics, module, section.key, "names no platform of this module")
                continue
            }
            val settings = section.settings
            if (settings == null) {
                drop(diagnostics, module, section.key, "must be an object")
                continue
            }
            for (unsupported in section.unsupportedKeys) {
                drop(diagnostics, module, "${section.key}.${unsupported.path}", unsupported.reason)
            }
            sections += RankedSection(
                rank = rank.getValue(section.qualifier),
                test = section.test,
                platforms = platforms,
                key = section.key,
                qualifier = section.qualifier,
                contribution = Contribution(
                    options = options(settings),
                    malformedOptions = section.malformedOptions,
                    testSettings = testSettings(
                        module = module,
                        key = section.key,
                        settings = settings,
                        platforms = platforms,
                        jvmBackedPlatforms = jvmBackedPlatforms,
                        diagnostics = diagnostics,
                    ),
                ),
            )
        }
        // A `test-settings@q` applies on top of `settings@q` whichever order the module wrote them
        // in, which is the rule the unqualified pair already follows. The sort is stable, so two
        // sections of the same qualifier and kind keep their declaration order.
        sections.sortWith(compareBy({ it.rank }, { it.test }))
        raiseOnConflictingValues(module, fragments, sections)

        var common = Contribution.EMPTY
        val byPlatform = mutableMapOf<String, Contribution>()
        for (section in sections) {
            val contribution = section.contribution
            if (section.qualifier == KmpFragments.COMMON) {
                common = merge(common, contribution)
                continue
            }
            for (platform in section.platforms) {
                byPlatform[platform] = byPlatform[platform]?.let { merge(it, contribution) } ?: contribution
            }
        }
        return ResolvedContributions(common, byPlatform)
    }

    /**
     * Refuses the value conflict the Toolchain refuses. For one leaf platform and one option the
     * Toolchain keeps only the sections nothing applicable refines, and fails when those disagree.
     * A section a narrower one resolves therefore never conflicts, which is why this cannot be a
     * plain scan of overlapping pairs: `settings@alpha` and `settings@zeta` may disagree over `jvm`
     * as long as `settings@jvm` settles it.
     *
     * The Toolchain separates two qualifiers covering the same leaves by the file each value was
     * written in, which the converter does not track. Where such a pair disagrees it cannot tell
     * which value survives to be compared with the rest, so it refuses nothing for that option on
     * that platform: a template a module overrides must not read as a conflict. Only declared scalar
     * compiler options can disagree; list options concatenate and qualified test settings merge by
     * their own rule.
     */
    private fun raiseOnConflictingValues(
        module: ToolchainModule,
        fragments: List<KmpFragment>,
        sections: List<RankedSection>,
    ) {
        val declaring = sections.filterNot(RankedSection::test)
        if (declaring.size < 2) return
        val leaves = KmpFragments.settingsLeaves(fragments)
        fun leavesOf(section: RankedSection) = leaves.getValue(section.qualifier)
        for (platform in declaring.flatMapTo(mutableSetOf()) { it.platforms }.sorted()) {
            val applicable = declaring.filter { platform in it.platforms }
            if (applicable.size < 2) continue
            for ((option, read) in CONFLICTING_OPTIONS) {
                val declared = applicable.filter { read(it.contribution.options) != null }
                if (declared.size < 2) continue
                val unrefined = declared
                    .filterNot { section -> declared.any { other -> narrows(leavesOf(other), leavesOf(section)) } }
                    .groupBy { section -> leavesOf(section) }
                    .values
                if (unrefined.any { group -> group.distinctBy { read(it.contribution.options) }.size > 1 }) continue
                val frontier = unrefined.map { group -> group.first() }
                for ((index, higher) in frontier.withIndex()) {
                    for (lower in frontier.subList(0, index)) {
                        val lowerValue = read(lower.contribution.options)
                        val higherValue = read(higher.contribution.options)
                        if (lowerValue == higherValue) continue
                        throw ConversionException(
                            "${module.displayName}: '${lower.key}' sets kotlin.$option to '$lowerValue' " +
                                "and '${higher.key}' sets it to '$higherValue' on platform '$platform'; " +
                                "neither section refines the other",
                        )
                    }
                }
            }
        }
    }

    /** True when [narrower] covers strictly fewer leaves than [broader] and so refines it. */
    private fun narrows(narrower: Set<String>, broader: Set<String>): Boolean =
        narrower.size < broader.size && broader.containsAll(narrower)

    private fun testSettings(
        module: ToolchainModule,
        key: String,
        settings: Settings,
        platforms: Set<String>,
        jvmBackedPlatforms: Set<String>,
        diagnostics: DiagnosticCollector,
    ): JvmTestSettings {
        val declared = JvmInterpreter.declaredTestSettings(settings)
        if (declared.isEmpty || platforms.any { it in jvmBackedPlatforms }) return declared
        diagnostics.warn(
            "${module.displayName}: the JVM test settings of '$key' name no JVM-backed platform of " +
                "this module and were dropped",
        )
        return JvmTestSettings.EMPTY
    }

    /** Merges common and platform sections for products that have no per-target block. */
    fun singlePlatform(
        module: ToolchainModule,
        platform: String,
        diagnostics: DiagnosticCollector,
    ): SinglePlatform {
        val resolved = resolve(module, KmpFragments.singlePlatform(platform), setOf(platform), diagnostics)
        val merged = merge(resolved.common, resolved.byPlatform[platform] ?: Contribution.EMPTY)
        return SinglePlatform(options = merged.options, testSettings = merged.testSettings)
    }

    private fun drop(diagnostics: DiagnosticCollector, module: ToolchainModule, path: String, reason: String) {
        diagnostics.warn("${module.displayName}: '$path' $reason and was dropped")
    }

    /** Carries only declared keys so explicit false values can override inherited options. */
    private fun options(settings: Settings): CompilerOptions {
        val kotlin = settings.kotlin ?: return CompilerOptions.EMPTY
        return CompilerOptions(
            languageVersion = kotlin.languageVersion,
            apiVersion = kotlin.apiVersion,
            allWarningsAsErrors = kotlin.allWarningsAsErrors,
            progressiveMode = kotlin.progressiveMode,
            freeArgs = kotlin.freeCompilerArgs,
            optIns = kotlin.optIns,
        )
    }

    /**
     * [higher] overrides scalar compiler options and appends list options. A malformed declared
     * compiler option suppresses its broader value; malformed test maps do not, because their keys
     * are module-defined and whole-map suppression would erase entries the section never named.
     */
    private fun merge(lower: Contribution, higher: Contribution): Contribution =
        Contribution(
            testSettings = lower.testSettings + higher.testSettings,
            options = CompilerOptions(
                languageVersion = higher.take(lower, QualifiedOption.LANGUAGE_VERSION) { it.languageVersion },
                apiVersion = higher.take(lower, QualifiedOption.API_VERSION) { it.apiVersion },
                jvmTarget = higher.options.jvmTarget ?: lower.options.jvmTarget,
                allWarningsAsErrors = higher.take(lower, QualifiedOption.ALL_WARNINGS_AS_ERRORS) {
                    it.allWarningsAsErrors
                },
                progressiveMode = higher.take(lower, QualifiedOption.PROGRESSIVE_MODE) { it.progressiveMode },
                freeArgs = higher.concat(lower, QualifiedOption.FREE_COMPILER_ARGS) { it.freeArgs },
                optIns = higher.concat(lower, QualifiedOption.OPT_INS) { it.optIns },
            ),
            // Only singlePlatform reads a merged contribution as higher, with common before platform.
            malformedOptions = lower.malformedOptions + higher.malformedOptions,
        )

    private fun <T> Contribution.take(lower: Contribution, option: String, read: (CompilerOptions) -> T?): T? =
        read(options) ?: lower.options.let(read).takeIf { option !in malformedOptions }

    private fun Contribution.concat(
        lower: Contribution,
        option: String,
        read: (CompilerOptions) -> List<String>,
    ): List<String> = if (option in malformedOptions) emptyList() else read(lower.options) + read(options)

    /** Tracks malformed options because binding represents both malformed and absent values as null. */
    private data class Contribution(
        val options: CompilerOptions,
        val malformedOptions: Set<String>,
        val testSettings: JvmTestSettings = JvmTestSettings.EMPTY,
    ) {
        companion object {
            val EMPTY = Contribution(CompilerOptions.EMPTY, emptySet())
        }
    }

    private data class RankedSection(
        val rank: Int,
        val test: Boolean,
        val platforms: Set<String>,
        val key: String,
        val qualifier: String,
        val contribution: Contribution,
    )

    private data class ResolvedContributions(
        val common: Contribution,
        val byPlatform: Map<String, Contribution>,
    )

    /** Rendered as text so one comparison covers options of different types. */
    private val CONFLICTING_OPTIONS: List<Pair<String, (CompilerOptions) -> String?>> = listOf(
        QualifiedOption.LANGUAGE_VERSION to { options -> options.languageVersion },
        QualifiedOption.API_VERSION to { options -> options.apiVersion },
        QualifiedOption.ALL_WARNINGS_AS_ERRORS to { options -> options.allWarningsAsErrors?.toString() },
        QualifiedOption.PROGRESSIVE_MODE to { options -> options.progressiveMode?.toString() },
    )
}
