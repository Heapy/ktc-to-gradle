package io.heapy.ktctogradle.load

import io.heapy.ktctogradle.ConversionException
import okio.FileSystem
import okio.Path

/**
 * Resolves `apply` depth-first, dependencies before consumers and shared templates once. Sequences
 * concatenate in that order; conflicting scalar values from unrelated templates are rejected.
 */
internal class TemplateGraph(private val fileSystem: FileSystem) {
    fun effectiveConfig(root: Path, moduleFile: Path): Value.Mapping {
        val cache = mutableMapOf<Path, ConfigNode>()
        val graphRoot = loadConfigGraph(root, moduleFile, moduleFile.parent!!, cache, mutableSetOf())
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

        val declarations = buildMap<List<String>, MutableList<ScalarDeclaration>> {
            for (node in ordered) collectScalarDeclarations(node.own, node, emptyList(), this)
        }
        var resolved = effective.asMapping(moduleFile.toString())
        for ((path, candidates) in declarations) {
            val maximal = candidates.filter { candidate ->
                candidates.none { other -> other.node !== candidate.node && reaches(other.node, candidate.node) }
            }
            val distinct = maximal.map(ScalarDeclaration::value).distinct()
            if (distinct.size > 1) {
                val sources = maximal.joinToString { it.node.file.relativeTo(root).toString() }
                throw ConversionException(
                    "Conflicting template values for '${displayPath(path)}' in $sources",
                )
            }
            resolved = setScalar(resolved, path, maximal.first().value)
        }
        return resolved
    }

    private fun loadConfigGraph(
        root: Path,
        file: Path,
        consumerDirectory: Path,
        cache: MutableMap<Path, ConfigNode>,
        active: MutableSet<Path>,
    ): ConfigNode {
        val canonical = fileSystem.canonicalize(file)
        cache[canonical]?.let { return it }
        if (!active.add(canonical)) {
            throw ConversionException("Template cycle detected at ${canonical.relativeTo(root)}")
        }
        val config = normalizeRepositoryCredentialPaths(
            readYaml(fileSystem, canonical),
            root,
            canonical.parent!!,
            consumerDirectory,
        )
        val applied = config.strings("apply").map { reference ->
            val template = resolveReference(root, canonical.parent!!, reference)
            if (!fileSystem.exists(template)) {
                throw ConversionException("Template '$reference' referenced by ${canonical.relativeTo(root)} does not exist")
            }
            loadConfigGraph(root, template, consumerDirectory, cache, active)
        }
        active.remove(canonical)
        return ConfigNode(canonical, config.without("apply"), applied).also { cache[canonical] = it }
    }

    /** Rebases template-relative credential files onto the consuming module. */
    private fun normalizeRepositoryCredentialPaths(
        config: Value.Mapping,
        root: Path,
        declaringDirectory: Path,
        consumerDirectory: Path,
    ): Value.Mapping {
        val repositories = config.entries["repositories"] as? Value.Sequence ?: return config
        val normalized = repositories.items.map { repository ->
            val mapping = repository as? Value.Mapping ?: return@map repository
            val credentials = mapping.entries["credentials"] as? Value.Mapping ?: return@map repository
            val configuredFile = credentials.string("file") ?: return@map repository
            val absoluteFile = if (configuredFile.startsWith("//")) {
                root / configuredFile.removePrefix("//")
            } else {
                declaringDirectory / configuredFile.removePrefix("./")
            }.normalized()
            val consumerRelativeFile = absoluteFile.relativeTo(consumerDirectory).toString()
            Value.Mapping(
                mapping.entries + (
                    "credentials" to Value.Mapping(
                        credentials.entries + ("file" to Value.Scalar(consumerRelativeFile)),
                    )
                ),
            )
        }
        return Value.Mapping(config.entries + ("repositories" to Value.Sequence(normalized)))
    }

    /** Keeps key paths segmented because a YAML key may itself contain a dot. */
    private fun collectScalarDeclarations(
        value: Value,
        node: ConfigNode,
        prefix: List<String>,
        destination: MutableMap<List<String>, MutableList<ScalarDeclaration>>,
    ) {
        when (value) {
            is Value.Mapping -> value.entries.forEach { (key, child) ->
                collectScalarDeclarations(child, node, prefix + key, destination)
            }
            is Value.Scalar, Value.Null -> destination.getOrPut(prefix) { mutableListOf() }
                .add(ScalarDeclaration(node, value))
            is Value.Sequence -> Unit
        }
    }

    /** Quotes dotted, empty, or escaped segments so diagnostic paths remain unambiguous. */
    private fun displayPath(path: List<String>): String =
        path.joinToString(".") { segment ->
            if (segment.isEmpty() || segment.any { it == '.' || it == '"' || it == '\\' }) {
                "\"${segment.replace("\\", "\\\\").replace("\"", "\\\"")}\""
            } else {
                segment
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
