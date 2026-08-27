package io.heapy.ktctogradle.interpret

import io.heapy.ktctogradle.ConversionException
import io.heapy.ktctogradle.load.Region
import io.heapy.ktctogradle.load.ToolchainModel
import io.heapy.ktctogradle.load.raiseDeferred

internal data class SerializationSettings(
    val version: String,
    val format: String? = null,
)

/**
 * `settings.kotlin.serialization`, with the default version applied.
 *
 * The binder defers a malformed section instead of throwing, so the failure is raised here — at the
 * point that reads the section, with the message and the position it has today.
 */
internal object Serialization {
    fun settings(model: ToolchainModel): SerializationSettings? {
        model.raiseDeferred(Region.SERIALIZATION)
        val spec = model.settings.kotlin?.serialization ?: return null
        return SerializationSettings(spec.version ?: Defaults.SERIALIZATION, spec.format)
    }

    /**
     * [settings] with a malformed section read as "no serialization".
     *
     * Plugin resolution runs for every module of the project, including ones the render stage never
     * reaches, so it must not raise a module's failure on its behalf.
     */
    fun settingsOrNull(model: ToolchainModel): SerializationSettings? =
        if (Region.SERIALIZATION in model.errors) null else settings(model)

    fun coordinate(key: String, version: String): String {
        val artifact = ARTIFACTS[key]
            ?: throw ConversionException("Unknown Kotlin serialization catalog alias '\$kotlin.serialization.$key'")
        return "org.jetbrains.kotlinx:$artifact:$version"
    }

    private val ARTIFACTS = mapOf(
        "core" to "kotlinx-serialization-core",
        "cbor" to "kotlinx-serialization-cbor",
        "hocon" to "kotlinx-serialization-hocon",
        "json" to "kotlinx-serialization-json",
        "json-io" to "kotlinx-serialization-json-io",
        "json-okio" to "kotlinx-serialization-json-okio",
        "properties" to "kotlinx-serialization-properties",
        "protobuf" to "kotlinx-serialization-protobuf",
    )
}
