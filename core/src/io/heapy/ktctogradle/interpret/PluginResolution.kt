package io.heapy.ktctogradle.interpret

import io.heapy.ktctogradle.DiagnosticCollector
import io.heapy.ktctogradle.Versions
import io.heapy.ktctogradle.load.ProductType
import io.heapy.ktctogradle.load.Region
import io.heapy.ktctogradle.load.ToolchainModel
import io.heapy.ktctogradle.model.GradlePlugin
import io.heapy.ktctogradle.model.PluginDecl
import io.heapy.ktctogradle.model.PluginFamily

/** Resolves one project-wide version per Gradle plugin family. */
internal object PluginResolution {
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

    /** Orders numeric releases before qualifiers, with stable releases ahead of matching prereleases. */
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

    /** Does not raise deferred model failures during project-wide version resolution. */
    fun pluginsOf(model: ToolchainModel): List<GradlePlugin> {
        if (Region.PRODUCT in model.errors) return emptyList()
        val plugins = mutableListOf<GradlePlugin>()
        when (model.product.type) {
            ProductType.JVM_LIB -> plugins += GradlePlugin.Kotlin.JVM
            ProductType.JVM_APP -> {
                plugins += GradlePlugin.Kotlin.JVM
                plugins += GradlePlugin.Builtin.APPLICATION
            }
            ProductType.ANDROID_APP -> plugins += GradlePlugin.Android.APPLICATION
            in ProductType.MULTIPLATFORM -> {
                plugins += GradlePlugin.Kotlin.MULTIPLATFORM
                if ("android" in model.product.platforms) plugins += GradlePlugin.Android.KMP_LIBRARY
            }
            else -> return emptyList()
        }
        if (Serialization.isEnabled(model)) plugins += GradlePlugin.Kotlin.SERIALIZATION
        plugins += Publishing.pluginsOf(model)
        return plugins
    }

    /** An Android Kotlin pin is not explicit because AGP, not the setting, selects its compiler. */
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
}
