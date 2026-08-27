package io.heapy.ktctogradle.interpret

import io.heapy.ktctogradle.DiagnosticCollector
import io.heapy.ktctogradle.Versions
import io.heapy.ktctogradle.load.ToolchainModel
import io.heapy.ktctogradle.model.GradlePlugin
import io.heapy.ktctogradle.model.PluginDecl
import io.heapy.ktctogradle.model.PluginFamily

/**
 * Which Gradle plugins a module applies, and at which version the whole build loads each of them.
 *
 * Gradle loads a plugin once for every module, and the Kotlin plugins only work together when their
 * versions match, so a version is chosen per [PluginFamily] for the project and not per module. The
 * Android Gradle Plugin carries the same rule across modules.
 */
internal object PluginResolution {
    /** One version per plugin family, keyed by plugin id. */
    fun resolveVersions(models: List<ToolchainModel>, diagnostics: DiagnosticCollector): Map<String, String> {
        val requests = models.flatMap(::requestsOf)
        val resolved = mutableMapOf<String, String>()
        for ((family, familyRequests) in requests.groupBy { request -> request.family }) {
            val requested = familyRequests.map(PluginRequest::version).distinct()
            val candidates = familyRequests.filter(PluginRequest::explicit).map(PluginRequest::version).distinct()
                .ifEmpty { requested }
            val chosen = candidates.maxWithOrNull(versionOrder) ?: continue
            if (requested.size > 1) {
                diagnostics.warn(
                    "The ${family.displayName} plugin is requested at more than one version " +
                        "(${requested.sorted().joinToString(", ")}); Gradle loads it once for the whole " +
                        "build, so the generated build uses $chosen for every module",
                )
            }
            for (request in familyRequests) resolved[request.plugin.id] = chosen
        }
        return resolved
    }

    /**
     * The `plugins { }` lines of one module, in declaration order.
     *
     * Only a root build script prints versions ([declareVersions]); a subproject inherits them.
     * A [GradlePlugin.Builtin] never carries one either way.
     */
    fun declarationsFor(
        model: ToolchainModel,
        versions: Map<String, String>,
        declareVersions: Boolean,
    ): List<PluginDecl> {
        val requested = requestsOf(model).associate { request -> request.plugin.id to request.version }
        return pluginsOf(model).map { plugin ->
            PluginDecl(
                plugin = plugin,
                version = if (declareVersions && plugin.family != null) {
                    versions[plugin.id] ?: requested[plugin.id]
                } else {
                    null
                },
            )
        }
    }

    /**
     * The plugins only subprojects use, declared once in the root with `apply false`.
     *
     * A plugin the root applies itself is left out: it is already declared with a version there.
     */
    fun inheritedDeclarations(
        root: ToolchainModel?,
        subprojects: List<ToolchainModel>,
        versions: Map<String, String>,
    ): List<PluginDecl> {
        val rootOwnIds = root?.let { model -> requestsOf(model).map { request -> request.plugin.id } }
            .orEmpty()
            .toSet()
        return subprojects
            .flatMap(::requestsOf)
            .distinctBy { request -> request.plugin.id }
            .filterNot { request -> request.plugin.id in rootOwnIds }
            .map { request -> PluginDecl(request.plugin, versions[request.plugin.id] ?: request.version, apply = false) }
    }

    /**
     * Orders version strings the way a release train runs: numbers first, and a stable release
     * ahead of every pre-release that carries the same numbers. Equal versions fall back to the
     * text so the choice does not depend on the order the modules were read in.
     */
    val versionOrder: Comparator<String> = Comparator { left, right ->
        val a = numericVersionParts(left)
        val b = numericVersionParts(right)
        var result = 0
        var index = 0
        while (result == 0 && index < maxOf(a.size, b.size)) {
            result = a.getOrElse(index) { 0 }.compareTo(b.getOrElse(index) { 0 })
            index++
        }
        if (result != 0) return@Comparator result
        val leftQualifier = left.substringAfter('-', "")
        val rightQualifier = right.substringAfter('-', "")
        if (leftQualifier.isEmpty() != rightQualifier.isEmpty()) {
            return@Comparator if (leftQualifier.isEmpty()) 1 else -1
        }
        result = compareQualifiers(leftQualifier, rightQualifier)
        if (result != 0) result else left.compareTo(right)
    }

    /**
     * The plugins [model] applies, including the unversioned Gradle ones, in declaration order.
     *
     * A module whose `product` or serialization section failed to bind contributes what it can and
     * raises nothing: version resolution runs over every module of the project, so a failure has to
     * keep surfacing from the stage that renders the module.
     */
    fun pluginsOf(model: ToolchainModel): List<GradlePlugin> {
        if (PRODUCT_REGION in model.errors) return emptyList()
        val plugins = mutableListOf<GradlePlugin>()
        when (model.product.type) {
            "jvm/lib" -> plugins += GradlePlugin.Kotlin.JVM
            "jvm/app" -> {
                plugins += GradlePlugin.Kotlin.JVM
                plugins += GradlePlugin.Builtin.APPLICATION
            }
            "android/app" -> plugins += GradlePlugin.Android.APPLICATION
            "kmp/lib", "js/app", "wasm-js/app", "wasm-wasi/app",
            "linux/app", "macos/app", "windows/app",
            -> {
                plugins += GradlePlugin.Kotlin.MULTIPLATFORM
                if ("android" in model.product.platforms) plugins += GradlePlugin.Android.KMP_LIBRARY
            }
            else -> return emptyList()
        }
        if (Serialization.settingsOrNull(model) != null) plugins += GradlePlugin.Kotlin.SERIALIZATION
        return plugins
    }

    /**
     * The versioned plugins of [model].
     *
     * [PluginRequest.explicit] describes the request and not the plugin, which is why it stops here
     * and never reaches [PluginDecl]: an Android module warns that `settings.kotlin.version` does
     * not select its Kotlin compiler, so that pin must not become the version the rest of the build
     * is generated with.
     */
    private fun requestsOf(model: ToolchainModel): List<PluginRequest> {
        val pinned = model.settings.kotlin?.version
        val kotlinVersion = pinned ?: Versions.KOTLIN
        val kotlinPinCounts = pinned != null && model.product.type != "android/app"
        return pluginsOf(model).mapNotNull { plugin ->
            when (plugin.family) {
                PluginFamily.KOTLIN -> PluginRequest(plugin, PluginFamily.KOTLIN, kotlinVersion, kotlinPinCounts)
                PluginFamily.ANDROID -> PluginRequest(
                    plugin,
                    PluginFamily.ANDROID,
                    Versions.ANDROID_GRADLE_PLUGIN,
                    explicit = true,
                )
                null -> null
            }
        }
    }

    private fun numericVersionParts(version: String): List<Int> =
        version.substringBefore('-').split('.', '_').map { part -> part.toIntOrNull() ?: 0 }

    private fun compareQualifiers(left: String, right: String): Int {
        val a = qualifierTokens(left)
        val b = qualifierTokens(right)
        for (index in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrNull(index) ?: return -1
            val y = b.getOrNull(index) ?: return 1
            val numeric = x.toIntOrNull()?.let { left1 -> y.toIntOrNull()?.let { right1 -> left1.compareTo(right1) } }
            val result = numeric ?: x.compareTo(y)
            if (result != 0) return result
        }
        return 0
    }

    private fun qualifierTokens(qualifier: String): List<String> =
        Regex("\\d+|\\D+").findAll(qualifier).map { it.value }.toList()

    private data class PluginRequest(
        val plugin: GradlePlugin,
        val family: PluginFamily,
        val version: String,
        val explicit: Boolean,
    )

    private const val PRODUCT_REGION = "product"
}
