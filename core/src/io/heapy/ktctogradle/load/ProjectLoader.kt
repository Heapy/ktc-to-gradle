package io.heapy.ktctogradle.load

import io.heapy.ktctogradle.ConversionException
import io.heapy.ktctogradle.ModulePath
import io.heapy.ktctogradle.interpret.Dependencies
import okio.FileSystem
import okio.Path

/**
 * Stage 1: reads a Kotlin Toolchain project off disk into a [ToolchainProject].
 *
 * This is the only stage that touches a [FileSystem]. Everything a later stage could want to look
 * up — the canonical directory of a module, which source directories exist, which main class was
 * detected — is recorded here instead.
 */
internal class ProjectLoader(private val fileSystem: FileSystem) {
    private val layoutProbe = ModuleLayoutProbe(fileSystem)
    private val templates = TemplateGraph(fileSystem)

    fun load(start: Path): ToolchainProject {
        val requested = fileSystem.canonicalize(start)
        val root = findRoot(fileSystem, requested)
        val projectFile = root / "project.yaml"
        val projectConfig = if (fileSystem.exists(projectFile)) readYaml(fileSystem, projectFile) else null
        val patterns = projectConfig?.strings("modules").orEmpty().map(::normalizeModulePattern)
        patterns.firstOrNull { "**" in it }?.let {
            throw ConversionException("project.yaml module glob '$it' uses unsupported recursive ** syntax")
        }
        val moduleFiles = findModuleFiles(fileSystem, root)
        val selected = moduleFiles.filter { file ->
            val relative = ModulePath.relativize(root, file.parent!!) ?: return@filter false
            relative.isRoot || projectConfig == null || patterns.any { globMatches(it, relative.notation) }
        }
        if (selected.isEmpty()) {
            throw ConversionException("No module.yaml files selected by ${projectFile.name}")
        }
        val modules = selected.map { moduleFile ->
            val directory = moduleFile.parent!!
            val path = ModulePath.relativize(root, directory)
                ?: throw ConversionException("Module directory $directory is outside project root $root")
            val displayName = if (path.isRoot) directory.name else path.notation
            ToolchainModule(
                path = path,
                directory = directory,
                canonicalDirectory = fileSystem.canonicalize(directory),
                model = YamlBinder.bind(templates.effectiveConfig(root, moduleFile), displayName),
                layout = layoutProbe.probe(directory),
            )
        }.sortedBy(ToolchainModule::path)
        Dependencies.validateLocal(modules)

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
}

internal fun readYaml(fileSystem: FileSystem, path: Path): Value.Mapping =
    parseYaml(fileSystem.read(path) { readUtf8() }, path.toString())

private val IGNORED_DIRECTORIES = setOf(".git", ".gradle", ".idea", "build", "out", "node_modules")

private fun findRoot(fileSystem: FileSystem, start: Path): Path {
    var current = if (fileSystem.metadata(start).isDirectory) start else start.parent!!
    var moduleCandidate: Path? = null
    while (true) {
        val projectFile = current / "project.yaml"
        if (fileSystem.exists(projectFile)) {
            if (moduleCandidate == null || selectsModule(fileSystem, projectFile, current, moduleCandidate)) return current
            return moduleCandidate
        }
        if (moduleCandidate == null && fileSystem.exists(current / "module.yaml")) moduleCandidate = current
        current = current.parent ?: break
    }
    return moduleCandidate ?: throw ConversionException(
        "No Kotlin Toolchain project found from $start (expected project.yaml or module.yaml)",
    )
}

/**
 * The upward walk reaches the filesystem root, so it can meet a project.yaml that has nothing to
 * do with the module it started from. Such a project would pull unrelated directories into the
 * conversion and write Gradle files next to it, so it only counts as the root when its module
 * globs actually select the module below.
 */
private fun selectsModule(fileSystem: FileSystem, projectFile: Path, root: Path, module: Path): Boolean {
    val patterns = runCatching {
        readYaml(fileSystem, projectFile).strings("modules").map(::normalizeModulePattern)
    }.getOrElse { return true }
    if (patterns.any { "**" in it }) return true
    val relative = ModulePath.relativize(root, module) ?: return false
    return relative.isRoot || patterns.any { globMatches(it, relative.notation) }
}

private fun findModuleFiles(fileSystem: FileSystem, root: Path): List<Path> {
    val result = mutableListOf<Path>()
    fun visit(directory: Path) {
        for (child in fileSystem.list(directory)) {
            if (child.name in IGNORED_DIRECTORIES) continue
            val metadata = fileSystem.metadata(child)
            if (metadata.isDirectory) visit(child)
            else if (child.name == "module.yaml") result += child
        }
    }
    visit(root)
    return result
}

private fun normalizeModulePattern(pattern: String): String =
    pattern.removePrefix("//").removePrefix("./").trimEnd('/')

/**
 * Matches one `modules:` glob against a module notation such as `libs/shared`.
 *
 * `*` and `?` stop at a path separator, so a pattern only ever selects the depth it was written
 * for; `**` never reaches here, because a recursive glob is rejected before selection starts.
 */
internal fun globMatches(pattern: String, path: String): Boolean {
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
