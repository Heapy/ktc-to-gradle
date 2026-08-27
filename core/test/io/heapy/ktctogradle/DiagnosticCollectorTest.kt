package io.heapy.ktctogradle

import kotlin.test.Test
import kotlin.test.assertEquals

class DiagnosticCollectorTest {
    @Test
    fun keepsSeverityAndAppendOrder() {
        val collector = DiagnosticCollector()
        collector.warn("first")
        collector.info("second")
        collector.warn("third")

        assertEquals(
            listOf(
                Diagnostic(Diagnostic.Severity.WARNING, "first"),
                Diagnostic(Diagnostic.Severity.INFO, "second"),
                Diagnostic(Diagnostic.Severity.WARNING, "third"),
            ),
            collector.drain(),
        )
    }

    @Test
    fun drainReturnsAnImmutableSnapshot() {
        val collector = DiagnosticCollector()
        collector.warn("first")

        val snapshot = collector.drain()
        collector.warn("second")

        assertEquals(listOf(Diagnostic(Diagnostic.Severity.WARNING, "first")), snapshot)
        assertEquals(2, collector.drain().size)
    }

    @Test
    fun separateCollectorsDoNotShareDiagnostics() {
        val first = DiagnosticCollector()
        val second = DiagnosticCollector()
        first.warn("only mine")

        assertEquals(emptyList<Diagnostic>(), second.drain())
    }
}
