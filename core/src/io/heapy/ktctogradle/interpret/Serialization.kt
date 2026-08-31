package io.heapy.ktctogradle.interpret

import io.heapy.ktctogradle.ConversionException
import io.heapy.ktctogradle.load.Region
import io.heapy.ktctogradle.load.ToolchainModel
import io.heapy.ktctogradle.load.raiseDeferred

internal data class SerializationSettings(
    val version: String,
    val format: String? = null,
)

/** Applies serialization defaults and raises its deferred failure only when the module is interpreted. */
internal object Serialization {
    fun of(model: ToolchainModel): SerializationSettings? {
        model.raiseDeferred(Region.SERIALIZATION)
        val spec = model.settings.kotlin?.serialization ?: return null
        return SerializationSettings(spec.version ?: Defaults.SERIALIZATION, spec.format)
    }

    /** Non-raising query used during project-wide plugin resolution. */
    fun isEnabled(model: ToolchainModel): Boolean =
        Region.SERIALIZATION !in model.errors && of(model) != null

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
