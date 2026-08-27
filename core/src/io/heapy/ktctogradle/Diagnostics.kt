package io.heapy.ktctogradle

import io.heapy.ktctogradle.model.GeneratedFile

/**
 * A failure that stops the conversion.
 *
 * [diagnostics] carries what the run had already collected when it failed. The stage that raises a
 * failure has no collector to hand, so it is attached one level up, where the collector lives.
 */
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

/**
 * Collects the diagnostics of a single conversion run.
 *
 * One collector belongs to one run and is passed down the call graph, so nothing that outlives a
 * run holds diagnostics and two runs can never see each other's.
 */
internal class DiagnosticCollector {
    private val entries = mutableListOf<Diagnostic>()

    /**
     * Records something the conversion could not do.
     *
     * An error does not stop the run: the files that could be produced are still written, and the
     * CLI exits non-zero so a partial conversion is never reported as a success.
     */
    fun error(message: String) {
        entries += Diagnostic(Diagnostic.Severity.ERROR, message)
    }

    fun warn(message: String) {
        entries += Diagnostic(Diagnostic.Severity.WARNING, message)
    }

    fun info(message: String) {
        entries += Diagnostic(Diagnostic.Severity.INFO, message)
    }

    /** Everything collected so far, in the order it was reported, as a snapshot later calls cannot change. */
    fun collected(): List<Diagnostic> = entries.toList()
}
