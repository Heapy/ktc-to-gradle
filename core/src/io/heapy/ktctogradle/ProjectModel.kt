package io.heapy.ktctogradle

import okio.FileSystem
import okio.Path

internal data class ToolchainProject(
    val root: Path,
    val name: String,
    val modules: List<ToolchainModule>,
    val catalogPath: Path?,
)

internal data class ToolchainModule(
    val path: String,
    val directory: Path,
    val config: Value.Mapping,
) {
    val gradlePath: String = if (path.isEmpty()) ":" else ":${path.replace('/', ':')}"
    val displayName: String = if (path.isEmpty()) directory.name else path
}

internal data class Product(val type: String, val platforms: List<String>)

internal class ProjectLoader(private val fileSystem: FileSystem) {
    fun load(start: Path): ToolchainProject {
        val requested = fileSystem.canonicalize(start)
        val root = findRoot(requested)
        val projectFile = root / "project.yaml"
        val projectConfig = if (fileSystem.exists(projectFile)) readYaml(projectFile) else null
        val patterns = projectConfig?.strings("modules").orEmpty()
        patterns.firstOrNull { "**" in it }?.let {
            throw ConversionException("project.yaml module glob '$it' uses unsupported recursive ** syntax")
        }
        val moduleFiles = findModuleFiles(root)
        val selected = moduleFiles.filter { file ->
            val relative = relativePath(root, file.parent!!)
            relative.isEmpty() || projectConfig == null || patterns.any { globMatches(it, relative) }
        }
        if (selected.isEmpty()) {
            throw ConversionException("No module.yaml files selected by ${projectFile.name}")
        }
        val modules = selected.map { moduleFile ->
            val directory = moduleFile.parent!!
            val relative = relativePath(root, directory)
            ToolchainModule(relative, directory, loadEffectiveConfig(root, moduleFile))
        }.sortedBy(ToolchainModule::path)
        validateLocalDependencies(modules)

        val rootCatalog = root / "libs.versions.toml"
        val gradleCatalog = root / "gradle" / "libs.versions.toml"
        if (fileSystem.exists(rootCatalog) && fileSystem.exists(gradleCatalog)) {
            throw ConversionException("Both libs.versions.toml locations exist; Kotlin Toolchain allows only one")
        }
        return ToolchainProject(
            root = root,
            name = root.name.ifBlank { "converted-project" },
            modules = modules,
            catalogPath = listOf(rootCatalog, gradleCatalog).firstOrNull(fileSystem::exists),
        )
    }

    private fun findRoot(start: Path): Path {
        var current = if (fileSystem.metadata(start).isDirectory) start else start.parent!!
        var moduleCandidate: Path? = null
        while (true) {
            if (fileSystem.exists(current / "project.yaml")) return current
            if (moduleCandidate == null && fileSystem.exists(current / "module.yaml")) moduleCandidate = current
            current = current.parent ?: break
        }
        return moduleCandidate ?: throw ConversionException(
            "No Kotlin Toolchain project found from $start (expected project.yaml or module.yaml)",
        )
    }

    private fun findModuleFiles(root: Path): List<Path> {
        val result = mutableListOf<Path>()
        fun visit(directory: Path) {
            for (child in fileSystem.list(directory)) {
                if (child.name in ignoredDirectories) continue
                val metadata = fileSystem.metadata(child)
                if (metadata.isDirectory) visit(child)
                else if (child.name == "module.yaml") result += child
            }
        }
        visit(root)
        return result
    }

    private fun loadEffectiveConfig(root: Path, file: Path): Value.Mapping {
        val cache = mutableMapOf<Path, ConfigNode>()
        val graphRoot = loadConfigGraph(root, file, cache, mutableSetOf())
        val ordered = mutableListOf<ConfigNode>()
        val visited = mutableSetOf<Path>()
        fun visit(node: ConfigNode) {
            if (!visited.add(node.file)) return
            node.applied.sortedBy { it.file.toString() }.forEach(::visit)
            ordered += node
        }
        visit(graphRoot)

        var effective: Value = Value.Mapping(emptyMap())
        for (node in ordered) effective = mergeValues(effective, node.own)

        val declarations = buildMap<String, MutableList<ScalarDeclaration>> {
            for (node in ordered) collectScalarDeclarations(node.own, node, "", this)
        }
        var resolved = effective.asMapping(file.toString())
        for ((path, candidates) in declarations) {
            val maximal = candidates.filter { candidate ->
                candidates.none { other -> other.node !== candidate.node && reaches(other.node, candidate.node) }
            }
            val distinct = maximal.map(ScalarDeclaration::value).distinct()
            if (distinct.size > 1) {
                val sources = maximal.joinToString { it.node.file.relativeTo(root).toString() }
                throw ConversionException("Conflicting template values for '$path' in $sources")
            }
            resolved = setScalar(resolved, path.split('.'), maximal.first().value)
        }
        return resolved
    }

    private fun loadConfigGraph(
        root: Path,
        file: Path,
        cache: MutableMap<Path, ConfigNode>,
        active: MutableSet<Path>,
    ): ConfigNode {
        val canonical = fileSystem.canonicalize(file)
        cache[canonical]?.let { return it }
        if (!active.add(canonical)) {
            throw ConversionException("Template cycle detected at ${canonical.relativeTo(root)}")
        }
        val config = readYaml(canonical)
        val applied = config.strings("apply").map { reference ->
            val template = resolveReference(root, canonical.parent!!, reference)
            if (!fileSystem.exists(template)) {
                throw ConversionException("Template '$reference' referenced by ${canonical.relativeTo(root)} does not exist")
            }
            loadConfigGraph(root, template, cache, active)
        }
        active.remove(canonical)
        return ConfigNode(canonical, config.without("apply"), applied).also { cache[canonical] = it }
    }

    private fun collectScalarDeclarations(
        value: Value,
        node: ConfigNode,
        prefix: String,
        destination: MutableMap<String, MutableList<ScalarDeclaration>>,
    ) {
        when (value) {
            is Value.Mapping -> value.entries.forEach { (key, child) ->
                collectScalarDeclarations(child, node, if (prefix.isEmpty()) key else "$prefix.$key", destination)
            }
            is Value.Scalar, Value.Null -> destination.getOrPut(prefix) { mutableListOf() }
                .add(ScalarDeclaration(node, value))
            is Value.Sequence -> Unit
        }
    }

    private fun reaches(higher: ConfigNode, lower: ConfigNode): Boolean {
        if (higher === lower) return true
        val visited = mutableSetOf<Path>()
        fun walk(node: ConfigNode): Boolean =
            visited.add(node.file) && node.applied.any { it === lower || walk(it) }
        return walk(higher)
    }

    private fun setScalar(mapping: Value.Mapping, path: List<String>, value: Value): Value.Mapping {
        val key = path.first()
        if (path.size == 1) return Value.Mapping(mapping.entries + (key to value))
        val child = mapping.entries[key] as? Value.Mapping ?: Value.Mapping(emptyMap())
        return Value.Mapping(mapping.entries + (key to setScalar(child, path.drop(1), value)))
    }

    private fun resolveReference(root: Path, declaringDirectory: Path, reference: String): Path =
        if (reference.startsWith("//")) root / reference.removePrefix("//")
        else declaringDirectory / reference.removePrefix("./")

    private fun readYaml(path: Path): Value.Mapping =
        parseYaml(fileSystem.read(path) { readUtf8() }, path.toString())

    private fun validateLocalDependencies(modules: List<ToolchainModule>) {
        val paths = modules.map(ToolchainModule::path).toSet()
        for (module in modules) {
            for ((key, value) in module.config.entries) {
                if (!key.startsWith("dependencies") && !key.startsWith("test-dependencies")) continue
                for (item in value.asSequence("${module.displayName}.$key")) {
                    val notation = dependencyNotation(item)
                    val local = when {
                        notation.startsWith("//") -> notation.removePrefix("//").trimEnd('/')
                        notation.startsWith("./") || notation.startsWith("../") -> {
                            val absolute = (module.directory / notation).normalized()
                            absolute.relativeTo(modules.first().directory.let { rootOf(modules) }).toString()
                        }
                        else -> null
                    }
                    if (local != null && local !in paths) {
                        throw ConversionException("${module.displayName} depends on unknown module '$notation'")
                    }
                }
            }
        }
    }

    private fun rootOf(modules: List<ToolchainModule>): Path {
        val rootModule = modules.firstOrNull { it.path.isEmpty() }
        if (rootModule != null) return rootModule.directory
        var root = modules.first().directory
        repeat(modules.first().path.split('/').size) { root = root.parent!! }
        return root
    }

    companion object {
        private val ignoredDirectories = setOf(".git", ".gradle", ".idea", "build", "out", "node_modules")

        private fun relativePath(root: Path, child: Path): String =
            child.relativeTo(root).toString().let { if (it == ".") "" else it }
    }

    private data class ConfigNode(
        val file: Path,
        val own: Value.Mapping,
        val applied: List<ConfigNode>,
    )

    private data class ScalarDeclaration(
        val node: ConfigNode,
        val value: Value,
    )
}

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

internal fun dependencyNotation(value: Value): String = when (value) {
    is Value.Scalar -> Regex("^(.*):\\s+(all|compile-only|runtime-only|exported)$")
        .matchEntire(value.text)?.groupValues?.get(1) ?: value.text
    is Value.Mapping -> value.entries.keys.singleOrNull()
        ?: throw ConversionException("A dependency object must have exactly one coordinate")
    else -> throw ConversionException("Dependency entries must be strings or objects")
}

private fun globMatches(pattern: String, path: String): Boolean {
    val regex = buildString {
        append('^')
        var index = 0
        while (index < pattern.length) {
            when (val char = pattern[index]) {
                '*' -> append("[^/]*")
                '?' -> append("[^/]")
                '.', '(', ')', '+', '|', '^', '$', '\\' -> append('\\').append(char)
                '{' -> {
                    val end = pattern.indexOf('}', index)
                    if (end < 0) append("\\{") else {
                        append("(?:").append(pattern.substring(index + 1, end).split(',').joinToString("|", transform = Regex::escape)).append(')')
                        index = end
                    }
                }
                '[' -> {
                    val end = pattern.indexOf(']', index)
                    if (end < 0) append("\\[") else {
                        val content = pattern.substring(index + 1, end)
                        append('[')
                        if (content.startsWith('!')) append('^').append(content.drop(1)) else append(content)
                        append(']')
                        index = end
                    }
                }
                else -> append(char)
            }
            index++
        }
        append('$')
    }
    return Regex(regex).matches(path)
}
