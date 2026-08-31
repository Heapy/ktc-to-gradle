package io.heapy.ktctogradle

import io.heapy.ktctogradle.model.GeneratedFile

/** A terminal failure plus diagnostics already collected by the abandoned stage. */
class ConversionException(
    message: String,
    val diagnostics: List<Diagnostic> = emptyList(),
) : RuntimeException(message)

data class Diagnostic(
    val severity: Severity,
    val message: String,
) {
    enum class Severity { ERROR, WARNING, INFO }
}

data class ConversionResult(
    val root: String,
    val writtenFiles: List<String>,
    val diagnostics: List<Diagnostic>,
)

internal data class GenerationResult(
    val files: List<GeneratedFile>,
    val diagnostics: List<Diagnostic>,
)

/** Per-conversion state; collectors are never shared across runs. */
internal class DiagnosticCollector {
    private val entries = mutableListOf<Diagnostic>()

    /** Records a non-terminal failure; partial output is written but the CLI exits non-zero. */
    fun error(message: String) {
        entries += Diagnostic(Diagnostic.Severity.ERROR, message)
    }

    fun warn(message: String) {
        entries += Diagnostic(Diagnostic.Severity.WARNING, message)
    }

    fun info(message: String) {
        entries += Diagnostic(Diagnostic.Severity.INFO, message)
    }

    fun collected(): List<Diagnostic> = entries.toList()
}
