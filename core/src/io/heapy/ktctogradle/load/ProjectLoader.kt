package io.heapy.ktctogradle.load

import io.heapy.ktctogradle.ConversionException
import io.heapy.ktctogradle.ModulePath
import okio.FileSystem
import okio.IOException
import okio.Path

/** Loads and canonicalizes all filesystem state needed by the pure conversion stages. */
internal class ProjectLoader(private val fileSystem: FileSystem) {
    private val layoutProbe = ModuleLayoutProbe(fileSystem)
    private val templates = TemplateGraph(fileSystem)

    fun load(start: Path): ToolchainProject {
        val requested = fileSystem.canonicalize(start)
        val root = findRoot(fileSystem, requested)
        val projectFile = root / "project.yaml"
        val projectConfig = if (fileSystem.exists(projectFile)) readProjectYaml(fileSystem, projectFile) else null
        val patterns = projectConfig?.strings("modules").orEmpty().map(::normalizeModulePattern)
        patterns.firstOrNull { "**" in it }?.let {
            throw ConversionException("project.yaml module glob '$it' uses unsupported recursive ** syntax")
        }
        // Without project.yaml, Toolchain builds only the module at the discovered root.
        val selected = if (projectConfig == null) {
            listOf(root / "module.yaml")
        } else {
            findModuleFiles(fileSystem, root).filter { file ->
                val relative = ModulePath.relativize(root, file.parent!!) ?: return@filter false
                relative.isRoot || patterns.any { globMatches(it, relative.notation) }
            }
        }
        if (selected.isEmpty()) {
            throw ConversionException("No modules found in $root: it has no module.yaml, and project.yaml selects none")
        }
        selected.firstOrNull { !isRegularFile(fileSystem, it) }?.let { moduleFile ->
            throw ConversionException("Module file $moduleFile is not a regular file")
        }
        val modules = selected.map { moduleFile ->
            val directory = moduleFile.parent!!
            val path = ModulePath.relativize(root, directory)
                ?: throw ConversionException("Module directory $directory is outside project root $root")
            val displayName = if (path.isRoot) directory.name else path.notation
            ToolchainModule(
                path = path,
                directory = directory,
                model = YamlBinder.bind(templates.effectiveConfig(root, moduleFile), displayName),
                layout = layoutProbe.probe(directory),
            )
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
}

internal fun readYaml(fileSystem: FileSystem, path: Path): Value.Mapping =
    parseYaml(fileSystem.read(path) { readUtf8() }, path.toString())

/** Kotlin Toolchain treats an empty project.yaml as an empty project mapping. */
private fun readProjectYaml(fileSystem: FileSystem, path: Path): Value.Mapping =
    parseYamlAllowingAnEmptyDocument(fileSystem.read(path) { readUtf8() }, path.toString())

/** Follows a module-file symlink before checking regular-file metadata. */
private fun isRegularFile(fileSystem: FileSystem, path: Path): Boolean =
    try {
        fileSystem.metadata(fileSystem.canonicalize(path)).isRegularFile
    } catch (_: IOException) {
        false
    }

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

/** Accepts an ancestor project only when its globs select the module the upward walk found. */
private fun selectsModule(fileSystem: FileSystem, projectFile: Path, root: Path, module: Path): Boolean {
    val patterns = runCatching {
        readProjectYaml(fileSystem, projectFile).strings("modules").map(::normalizeModulePattern)
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

/** Matches non-recursive project globs; `*` and `?` never cross `/`. */
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
