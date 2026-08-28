package io.heapy.ktctogradle.interpret

import io.heapy.ktctogradle.ConversionException
import io.heapy.ktctogradle.DiagnosticCollector
import io.heapy.ktctogradle.load.ModuleIndex
import io.heapy.ktctogradle.load.QualifiedOption
import io.heapy.ktctogradle.load.Region
import io.heapy.ktctogradle.load.Settings
import io.heapy.ktctogradle.load.ToolchainModule
import io.heapy.ktctogradle.load.UnsupportedKey
import io.heapy.ktctogradle.load.raiseDeferred
import io.heapy.ktctogradle.model.CompilerOptions
import io.heapy.ktctogradle.model.Dependency
import io.heapy.ktctogradle.model.DependencyTarget
import io.heapy.ktctogradle.model.KmpSourceSet
import io.heapy.ktctogradle.model.KmpTarget
import io.heapy.ktctogradle.model.MultiplatformBuild
import io.heapy.ktctogradle.model.TargetKind

/**
 * Turns a multiplatform module — `kmp/lib`, and every single-platform application Gradle builds
 * with the Kotlin Multiplatform plugin — into the Gradle build it stands for.
 *
 * Two things are decided here and nowhere else: which target DSL each declared platform needs, and
 * which fragment of the hierarchy each `settings@<qualifier>` section reaches.
 */
internal object MultiplatformInterpreter {
    fun interpret(index: ModuleIndex, module: ToolchainModule, diagnostics: DiagnosticCollector): MultiplatformBuild {
        val model = module.model
        val serialization = Serialization.of(model)
        val fragments = KmpFragments.of(model, module.displayName)
        val qualified = QualifiedSettings.of(module, fragments.map { it.name to it.platforms }, diagnostics)
        val platforms = model.product.platforms
        val executable = model.product.type.endsWith("/app")
        val targets = platforms.map { platform ->
            target(module, platform, executable, qualified.byPlatform[platform] ?: CompilerOptions.EMPTY, diagnostics)
        }
        // The module-wide options are the first thing read out of `settings:` itself, so a section
        // the binder could not read raises its message here rather than earlier.
        model.raiseDeferred(Region.SETTINGS)
        return MultiplatformBuild(
            targets = targets,
            // Both JVM-flavoured targets compile against a JDK, and both carry a release the JDK has
            // to be able to supply, so an android-only module needs the toolchain pin just as much.
            jvmToolchain = if (platforms.any(JVM_PLATFORMS::contains)) {
                model.settings.jvm?.jdkVersion ?: Defaults.JVM_JDK
            } else {
                null
            },
            compilerOptions = JvmInterpreter.compilerOptions(model.settings.kotlin),
            qualifiedCompilerOptions = qualified.common,
            sourceSets = sourceSets(index, module, fragments, serialization),
            testFramework = JvmInterpreter.testFramework(model),
        )
    }

    /** The platforms whose compilations run on a JDK, and therefore need `jvmToolchain`. */
    private val JVM_PLATFORMS = setOf("jvm", "android")

    private fun target(
        module: ToolchainModule,
        platform: String,
        executable: Boolean,
        compilerOptions: CompilerOptions,
        diagnostics: DiagnosticCollector,
    ): KmpTarget {
        val model = module.model
        val kind = when (platform) {
            "jvm" -> TargetKind.Jvm(release = jvmRelease(model))
            "android" -> TargetKind.Android(AndroidInterpreter.libraryTarget(module, diagnostics))
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

    /**
     * The source sets of the module: the two Kotlin always provides, then one main and one test set
     * per fragment, parents before children.
     */
    private fun sourceSets(
        index: ModuleIndex,
        module: ToolchainModule,
        fragments: List<KmpFragment>,
        serialization: SerializationSettings?,
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
                // Every multiplatform module gets the Kotlin test library: there is no JUnit
                // platform to choose between when the tests also run on native and on the web.
                dependencies = listOf(Dependency(DependencyTarget.KotlinBuiltin("test"))) +
                    Dependencies.of(index, module, test = true, qualifiers = COMMON_QUALIFIERS),
            ),
        )
        for (fragment in fragments.filterNot { it.name == KmpFragments.COMMON }) {
            add(qualifiedSourceSet(index, module, fragment, test = false))
            add(qualifiedSourceSet(index, module, fragment, test = true))
        }
    }

    private fun qualifiedSourceSet(
        index: ModuleIndex,
        module: ToolchainModule,
        fragment: KmpFragment,
        test: Boolean,
    ): KmpSourceSet {
        val qualifier = fragment.name
        val prefix = if (test) "test" else "src"
        val resources = if (test) "testResources" else "resources"
        val dependencies = Dependencies.of(index, module, test, listOf(qualifier))
        val sourceDir = "$prefix@$qualifier"
        val resourceDir = "$resources@$qualifier"
        val suffix = if (test) "Test" else "Main"
        return KmpSourceSet(
            // The Android Gradle Plugin calls the unit-test source set androidHostTest;
            // androidTest is its on-device suite, so tests placed there never run.
            name = if (qualifier == "android" && test) "androidHostTest" else "$qualifier$suffix",
            parents = fragment.parents.map { parent -> "$parent$suffix" },
            test = test,
            builtIn = false,
            sourceDirs = listOfNotNull(sourceDir.takeIf { it in module.layout.existingSourceDirs }),
            resourceDirs = listOfNotNull(resourceDir.takeIf { it in module.layout.existingSourceDirs }),
            dependencies = dependencies,
        )
    }

    /** Only the unqualified section: a multiplatform module reads its qualified ones per fragment. */
    private val COMMON_QUALIFIERS = listOf("")
}

/**
 * The `settings@<qualifier>` sections of a module, resolved against its fragments.
 *
 * A section that names no fragment, or that the converter cannot carry, is reported and dropped
 * rather than raised: a module stays convertible when one of its qualified sections is wrong.
 */
internal object QualifiedSettings {
    /**
     * [common] applies to the whole module and [byPlatform] to single targets, which is the split
     * Gradle's DSL forces: `kotlin { compilerOptions { } }` versus the per-target block.
     */
    data class Resolved(
        val common: CompilerOptions,
        val byPlatform: Map<String, CompilerOptions>,
    )

    /**
     * Splits the qualified sections of a module into the part that applies to every platform and the
     * part that applies to single ones. [fragmentOrder] lists the qualifiers the module accepts,
     * broadest first, so a narrower section overrides a broader one even when both happen to cover
     * the same leaves.
     */
    fun of(
        module: ToolchainModule,
        fragmentOrder: List<Pair<String, Set<String>>>,
        diagnostics: DiagnosticCollector,
    ): Resolved {
        val resolved = resolve(module, fragmentOrder, diagnostics)
        return Resolved(
            common = resolved.common.options,
            byPlatform = resolved.byPlatform.mapValues { (_, contribution) -> contribution.options },
        )
    }

    private fun resolve(
        module: ToolchainModule,
        fragmentOrder: List<Pair<String, Set<String>>>,
        diagnostics: DiagnosticCollector,
    ): ResolvedContributions {
        val rank = fragmentOrder.withIndex().associate { (index, entry) -> entry.first to index }
        val platformsOf = fragmentOrder.toMap()
        val sections = mutableListOf<Triple<Int, Set<String>, Contribution>>()
        for (section in module.model.qualifiedSections) {
            if (section.test) {
                drop(diagnostics, module, section.key, UnsupportedKey.UNSUPPORTED)
                continue
            }
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
            sections += Triple(
                rank.getValue(section.qualifier),
                platforms,
                Contribution(options(settings), section.malformedOptions),
            )
        }
        sections.sortBy { (index, _, _) -> index }

        var common = Contribution.EMPTY
        val byPlatform = mutableMapOf<String, Contribution>()
        for ((index, platforms, contribution) in sections) {
            if (fragmentOrder[index].first == KmpFragments.COMMON) {
                common = merge(common, contribution)
                continue
            }
            for (platform in platforms) {
                byPlatform[platform] = byPlatform[platform]?.let { merge(it, contribution) } ?: contribution
            }
        }
        return ResolvedContributions(common, byPlatform)
    }

    /**
     * The qualified options of a product that has exactly one platform.
     *
     * Such a module has no per-target block to put them in, so `settings@common` and the platform's
     * own section are merged and join the module-wide options instead.
     */
    fun singlePlatform(
        module: ToolchainModule,
        platform: String,
        diagnostics: DiagnosticCollector,
    ): CompilerOptions {
        val resolved = resolve(module, KmpFragments.singlePlatform(platform), diagnostics)
        return merge(resolved.common, resolved.byPlatform[platform] ?: Contribution.EMPTY).options
    }

    private fun drop(diagnostics: DiagnosticCollector, module: ToolchainModule, path: String, reason: String) {
        diagnostics.warn("${module.displayName}: '$path' $reason and was dropped")
    }

    /**
     * What a qualified section contributes to a `compilerOptions { }` body.
     *
     * Only the keys the section declares are carried: a Gradle target inherits the module-wide
     * options and overrides what it restates, so a section that turns a flag off has to say so.
     * Malformed values were already reported when the section was read and bind to nothing.
     */
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
     * [higher] overrides every option it declares; free arguments and opt-ins add up instead.
     *
     * An option [higher] declared but got wrong overrides too, with nothing: the section said the
     * broader value does not apply here, and the fact that it then failed to say what does apply
     * cannot resurrect it. Dropping that distinction would make a broken `settings@jvm` silently
     * inherit `settings@common`'s value instead of clearing it.
     */
    private fun merge(lower: Contribution, higher: Contribution): Contribution =
        Contribution(
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
            // The union is enough because a merged contribution is only ever read as `higher` in
            // [singlePlatform], and there `byPlatform` holds a single section: the fragment order is
            // `common` then the platform itself, so nothing accumulates that a later section could
            // have made well-formed again.
            malformedOptions = lower.malformedOptions + higher.malformedOptions,
        )

    /** [higher]'s value of [option], or [lower]'s when [higher] neither declares nor suppresses it. */
    private fun <T> Contribution.take(lower: Contribution, option: String, read: (CompilerOptions) -> T?): T? =
        read(options) ?: lower.options.let(read).takeIf { option !in malformedOptions }

    /** As [take], except that two well-formed lists add up rather than overriding. */
    private fun Contribution.concat(
        lower: Contribution,
        option: String,
        read: (CompilerOptions) -> List<String>,
    ): List<String> = if (option in malformedOptions) emptyList() else read(lower.options) + read(options)

    /**
     * One qualified section's contribution to a `compilerOptions { }` body.
     *
     * [malformedOptions] is carried alongside [options] because a malformed value binds to the same
     * `null` an absent one does, and the two mean opposite things when sections are merged.
     */
    private data class Contribution(
        val options: CompilerOptions,
        val malformedOptions: Set<String>,
    ) {
        companion object {
            val EMPTY = Contribution(CompilerOptions.EMPTY, emptySet())
        }
    }

    /** [Resolved] before the merge markers are dropped, which only [singlePlatform] still needs. */
    private data class ResolvedContributions(
        val common: Contribution,
        val byPlatform: Map<String, Contribution>,
    )
}
