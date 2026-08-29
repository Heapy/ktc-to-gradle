package io.heapy.ktctogradle.load

import com.charleskorn.kaml.EmptyYamlDocumentException
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import io.heapy.ktctogradle.ConversionException

internal sealed interface Value {
    data class Scalar(val text: String) : Value
    data class Mapping(val entries: Map<String, Value>) : Value
    data class Sequence(val items: List<Value>) : Value
    data object Null : Value
}

internal fun parseYaml(text: String, source: String): Value.Mapping {
    val node = try {
        Yaml.default.parseToYamlNode(text)
    } catch (error: Exception) {
        throw ConversionException("Cannot parse $source: ${error.message}")
    }
    return node.toValue().asMapping(source)
}

/**
 * Parses a document that is allowed to carry nothing, and reads that as an empty mapping.
 *
 * kaml refuses a document with no content node — a zero-byte or comment-only file — outright, while
 * the Toolchain reads every top-level value it cannot find as absent and carries on. Every other
 * malformed document still fails, and so does a document that spells out `null`: the Toolchain
 * rejects that one too.
 */
internal fun parseYamlAllowingAnEmptyDocument(text: String, source: String): Value.Mapping {
    val node = try {
        Yaml.default.parseToYamlNode(text)
    } catch (_: EmptyYamlDocumentException) {
        return Value.Mapping(emptyMap())
    } catch (error: Exception) {
        throw ConversionException("Cannot parse $source: ${error.message}")
    }
    return node.toValue().asMapping(source)
}

private fun YamlNode.toValue(): Value = when (this) {
    is YamlMap -> Value.Mapping(entries.mapKeys { (key, _) -> key.content }.mapValues { (_, value) -> value.toValue() })
    is YamlList -> Value.Sequence(items.map(YamlNode::toValue))
    is YamlScalar -> Value.Scalar(content)
    is YamlNull -> Value.Null
    else -> throw ConversionException("Unsupported YAML node: ${this::class.simpleName}")
}

internal fun Value?.asMapping(context: String): Value.Mapping =
    this as? Value.Mapping ?: throw ConversionException("Expected an object at $context")

internal fun Value?.asSequence(context: String): List<Value> = when (this) {
    null -> emptyList()
    is Value.Sequence -> items
    else -> throw ConversionException("Expected a list at $context")
}

internal fun Value?.scalarOrNull(): String? = when (this) {
    is Value.Scalar -> text
    Value.Null, null -> null
    else -> null
}

internal fun Value.Mapping.value(path: String): Value? {
    var current: Value = this
    for (part in path.split('.')) {
        current = (current as? Value.Mapping)?.entries?.get(part) ?: return null
    }
    return current
}

internal fun Value.Mapping.string(path: String): String? = value(path).scalarOrNull()

internal fun Value.Mapping.boolean(path: String): Boolean? = string(path)?.let {
    when (it.lowercase()) {
        "true", "enabled" -> true
        "false", "disabled" -> false
        else -> null
    }
}

internal fun Value.Mapping.strings(path: String): List<String> =
    value(path).asSequence(path).mapIndexed { index, item ->
        item.scalarOrNull() ?: throw ConversionException("Expected a string at $path[$index]")
    }

internal fun Value.Mapping.without(key: String): Value.Mapping = Value.Mapping(entries - key)

internal fun mergeValues(lower: Value, higher: Value): Value = when {
    lower is Value.Mapping && higher is Value.Mapping -> Value.Mapping(
        buildMap {
            putAll(lower.entries)
            for ((key, value) in higher.entries) {
                put(key, lower.entries[key]?.let { mergeValues(it, value) } ?: value)
            }
        },
    )
    lower is Value.Sequence && higher is Value.Sequence -> Value.Sequence(lower.items + higher.items)
    else -> higher
}

