package io.heapy.ktctogradle.render

import io.heapy.ktctogradle.model.GradlePlugin
import io.heapy.ktctogradle.model.PluginDecl

internal fun KtsWriter.appendPluginBlock(plugins: List<PluginDecl>) {
    block("plugins") {
        for (plugin in plugins) line(plugin.declaration())
    }
}

internal fun PluginDecl.declaration(): String = buildString {
    append(plugin.dsl())
    if (version != null) append(" version ${quote(version)}")
    if (!apply) append(" apply false")
}

/** The only place that knows how a [GradlePlugin] is spelled in the Gradle Kotlin DSL. */
internal fun GradlePlugin.dsl(): String = when (this) {
    is GradlePlugin.Kotlin -> "kotlin(${quote(shortName)})"
    is GradlePlugin.Builtin -> id
    is GradlePlugin.Android,
    is GradlePlugin.Other,
    -> "id(${quote(id)})"
}
