package io.heapy.ktctogradle.load

import io.heapy.ktctogradle.ConversionException
import okio.FileSystem
import okio.Path

/**
 * Resolves the `apply:` graph of a module.yaml into the single configuration it stands for.
 *
 * A template may apply further templates, and the same template may be reached along more than one
 * path, so the graph is walked depth first with the applied nodes visited before the node that
 * applies them: a module overrides its templates, and a template overrides the ones it applies.
 * Sequences concatenate in that order, which is why a diamond contributes its shared template once.
 *
 * Scalars are treated separately from that merge. Two templates that neither applies the other and
 * that give the same path different scalars are a conflict rather than a last-one-wins, so scalar
 * declarations are collected with the node that made them and compared afterwards.
 */
internal class TemplateGraph(private val fileSystem: FileSystem) {
    /**
     * The merged configuration of [moduleFile], with [root] used only to phrase the messages and to
     * resolve `//`-prefixed references.
     */
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

    /**
     * Rewrites every repository credentials file to a path relative to the module that applies it.
     *
     * A template declares the file next to itself, but the generated script runs from the module
     * directory, so the path only survives the merge if it is rebased onto the consumer.
     */
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

    /**
     * [prefix] is the key path as a list of segments and never as a dotted string: a YAML key may
     * itself contain a dot, and joining the path would make that key indistinguishable from nesting.
     */
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

    /**
     * The key path as the module would have had to write it, for a message to quote.
     *
     * The segments are joined with a dot, so a segment that contains one is quoted: without that a
     * literal `my.app.mode` key and a three-level nesting print the same text, and the message would
     * point at a key the module never wrote. The quote and the backslash are escaped, and an empty
     * key is quoted too, for the same reason the dot is: a bare segment then contains none of the
     * three and is never empty, so no two paths can reach the same text.
     */
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
