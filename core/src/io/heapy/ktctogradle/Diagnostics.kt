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

