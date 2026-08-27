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
    /**
     * Where the module.yaml sits, as [ProjectLoader] walked to it.
     *
     * Already free of symlinks — the loader canonicalizes the start path and never descends into a
     * symlinked directory — so resolving a `./` or `../` dependency to a module never needs a file
     * system outside the load stage.
     */
    val directory: Path,
    /** The merged module.yaml as typed data. */
    val model: ToolchainModel,
    val layout: ModuleLayout,
) {
    val gradlePath: String = path.gradlePath
    val displayName: String = if (path.isRoot) directory.name else path.notation
}

internal fun product(config: Value.Mapping): ProductSpec {
    val node = config.value("product") ?: throw ConversionException("Every module must declare product")
    return when (node) {
        is Value.Scalar -> ProductSpec(node.text, defaultPlatforms(node.text))
        is Value.Mapping -> {
            val type = node.string("type") ?: throw ConversionException("product.type is required")
            val platforms = node.strings("platforms").ifEmpty { defaultPlatforms(type) }
            ProductSpec(type, platforms)
        }
        else -> throw ConversionException("product must be a string or object")
    }
}

private fun defaultPlatforms(type: String): List<String> = when (type) {
    ProductType.JVM_APP, ProductType.JVM_LIB, ProductType.JVM_AMPER_PLUGIN -> listOf("jvm")
    ProductType.ANDROID_APP -> listOf("android")
    ProductType.IOS_APP -> listOf("iosArm64", "iosSimulatorArm64")
    ProductType.JS_APP -> listOf("js")
    ProductType.WASM_JS_APP -> listOf("wasmJs")
    ProductType.WASM_WASI_APP -> listOf("wasmWasi")
    ProductType.LINUX_APP -> listOf("linuxX64", "linuxArm64")
    ProductType.MACOS_APP -> listOf("macosArm64")
    ProductType.WINDOWS_APP -> listOf("mingwX64")
    ProductType.KMP_LIB -> throw ConversionException("kmp/lib requires product.platforms")
    else -> throw ConversionException("Unsupported product type '$type'")
}
