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
 * The binder defers a malformed section instead of throwing, so the failure is raised here, at the
 * point that reads the section: a module whose serialization settings are wrong has to fail while
 * its own build is being interpreted and not while some other module's is.
 */
internal object Serialization {
    fun of(model: ToolchainModel): SerializationSettings? {
        model.raiseDeferred(Region.SERIALIZATION)
        val spec = model.settings.kotlin?.serialization ?: return null
        return SerializationSettings(spec.version ?: Defaults.SERIALIZATION, spec.format)
    }

    /**
     * Whether the module enables serialization, with a malformed section read as "it does not".
     *
     * Plugin resolution runs for every module of the project, including ones the render stage never
     * reaches, so it must not raise a module's failure on its behalf. [of] raises; this does not.
     */
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
