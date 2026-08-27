package io.heapy.ktctogradle.load

import io.heapy.ktctogradle.ConversionException
import io.heapy.ktctogradle.ModulePath
import okio.Path

internal data class ToolchainProject(
    val root: Path,
    val name: String,
    val modules: List<ToolchainModule>,
    val catalogPath: Path?,
)

/**
 * One module.yaml of the project, as the load stage recorded it.
 *
 * The untyped YAML tree stops here: a module reaches the interpret stage as [model] plus the file
 * system facts the later stages may not go and look up themselves.
 */
internal data class ToolchainModule(
    val path: ModulePath,
    val directory: Path,
    /**
     * [directory] with every symlink resolved.
     *
     * Recorded here so that resolving a `./` or `../` dependency to a module never needs a file
     * system outside the load stage.
     */
    val canonicalDirectory: Path,
    /** The merged module.yaml as typed data. */
    val model: ToolchainModel,
    val layout: ModuleLayout,
) {
    val gradlePath: String = path.gradlePath
    val displayName: String = if (path.isRoot) directory.name else path.notation
}

internal data class Product(val type: String, val platforms: List<String>)

internal fun product(config: Value.Mapping): Product {
    val node = config.value("product") ?: throw ConversionException("Every module must declare product")
    return when (node) {
        is Value.Scalar -> Product(node.text, defaultPlatforms(node.text))
        is Value.Mapping -> {
            val type = node.string("type") ?: throw ConversionException("product.type is required")
            val platforms = node.strings("platforms").ifEmpty { defaultPlatforms(type) }
            Product(type, platforms)
        }
        else -> throw ConversionException("product must be a string or object")
    }
}

private fun defaultPlatforms(type: String): List<String> = when (type) {
    "jvm/app", "jvm/lib", "jvm/amper-plugin" -> listOf("jvm")
    "android/app" -> listOf("android")
    "ios/app" -> listOf("iosArm64", "iosSimulatorArm64")
    "js/app" -> listOf("js")
    "wasm-js/app" -> listOf("wasmJs")
    "wasm-wasi/app" -> listOf("wasmWasi")
    "linux/app" -> listOf("linuxX64", "linuxArm64")
    "macos/app" -> listOf("macosArm64")
    "windows/app" -> listOf("mingwX64")
    "kmp/lib" -> throw ConversionException("kmp/lib requires product.platforms")
    else -> throw ConversionException("Unsupported product type '$type'")
}
