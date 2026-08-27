package io.heapy.ktctogradle

class ConversionException(message: String) : RuntimeException(message)

data class Diagnostic(
    val severity: Severity,
    val message: String,
) {
    enum class Severity { WARNING, INFO }
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

/**
 * Collects the diagnostics of a single conversion run.
 *
 * One collector belongs to one run and is passed down the call graph, so nothing that outlives a
 * run holds diagnostics and two runs can never see each other's.
 */
internal class DiagnosticCollector {
    private val entries = mutableListOf<Diagnostic>()

    fun warn(message: String) {
        entries += Diagnostic(Diagnostic.Severity.WARNING, message)
    }

    fun info(message: String) {
        entries += Diagnostic(Diagnostic.Severity.INFO, message)
    }

    /** Everything collected so far, in the order it was reported, as a snapshot later calls cannot change. */
    fun drain(): List<Diagnostic> = entries.toList()
}
