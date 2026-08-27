package io.heapy.ktctogradle.render

/**
 * Emits Gradle Kotlin DSL text and owns the indentation, so a renderer never passes an indent
 * string around.
 */
internal class KtsWriter {
    private val builder = StringBuilder()
    private var level = 0

    fun line(text: String) {
        repeat(level) { builder.append(INDENT) }
        builder.append(text).append('\n')
    }

    fun blank() {
        builder.append('\n')
    }

    fun block(header: String, body: KtsWriter.() -> Unit) {
        line("$header {")
        level++
        body()
        level--
        line("}")
    }

    fun build(): String = builder.toString()

    private companion object {
        const val INDENT = "    "
    }
}

internal fun quote(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")}\""
