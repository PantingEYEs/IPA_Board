package com.example.ipa_board.diagnostics

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class DiagnosticReporterTest {
    @Test fun defaultOffStillRunsOperationsAndPreservesFailureControlFlow() {
        val events = mutableListOf<DiagnosticEvent>()
        val reporter = DiagnosticReporter({ 0L }, events::add)
        var operations = 0
        assertFalse(reporter.enabled)
        assertEquals("result", reporter.atStage(DiagnosticComponent.RIME, DiagnosticStage.INITIALIZE) {
            operations++
            "result"
        })
        val cause = IOException("private source")
        val failed = stageFailure {
            reporter.atStage(DiagnosticComponent.RIME, DiagnosticStage.ASSETS) {
                operations++
                throw cause
            }
        }
        assertSame(cause, failed.cause)
        assertEquals(DiagnosticKind.IO, failed.issue.kind)
        assertEquals(2, operations)
        assertTrue(events.isEmpty())
    }

    @Test fun switchingOffInsideAnOperationSilencesItsRemainingEventsImmediately() {
        val events = mutableListOf<DiagnosticEvent>()
        val reporter = DiagnosticReporter({ 0L }, events::add).apply { enabled = true }
        assertEquals(42, reporter.atStage(DiagnosticComponent.ENGLISH, DiagnosticStage.INITIALIZE) {
            reporter.enabled = false
            42
        })
        reporter.failure(DiagnosticComponent.ENGLISH, DiagnosticStage.QUERY, IOException("private"))
        reporter.atStage(DiagnosticComponent.ENGLISH, DiagnosticStage.INITIALIZE) { "still runs" }
        assertEquals(listOf(DiagnosticOutcome.START), events.map { it.outcome })
        assertFalse(reporter.enabled)
    }

    @Test fun optingInAgainDoesNotKeepFailuresFromThePreviousOptInSession() {
        val events = mutableListOf<DiagnosticEvent>()
        val reporter = DiagnosticReporter({ 5L }, events::add).apply { enabled = true }
        reporter.signal(DiagnosticComponent.MOZC, DiagnosticStage.BIND, DiagnosticKind.BIND_REJECTED)
        reporter.enabled = false
        reporter.signal(DiagnosticComponent.MOZC, DiagnosticStage.BIND, DiagnosticKind.BIND_REJECTED)
        reporter.enabled = true
        reporter.signal(DiagnosticComponent.MOZC, DiagnosticStage.BIND, DiagnosticKind.BIND_REJECTED)
        assertEquals(2, events.size)
    }

    @Test fun nestedStagesKeepTheInnermostFailureAndEmitItOnlyOnce() {
        val events = mutableListOf<DiagnosticEvent>()
        val reporter = DiagnosticReporter({ 1L }, events::add).apply { enabled = true }
        val cause = UnsatisfiedLinkError("private native path")
        var inner: DiagnosticStageException? = null
        val outer = stageFailure {
            reporter.atStage(DiagnosticComponent.RIME, DiagnosticStage.INITIALIZE) {
                try {
                    reporter.atStage(DiagnosticComponent.RIME, DiagnosticStage.LIBRARY) { throw cause }
                } catch (error: DiagnosticStageException) {
                    inner = error
                    throw error
                }
            }
        }
        assertSame(inner, outer)
        assertSame(cause, outer.cause)
        assertEquals(DiagnosticStage.LIBRARY, outer.issue.stage)
        assertEquals(DiagnosticKind.NATIVE_LINK, outer.issue.kind)
        assertEquals(listOf(outer.issue), events.filter { it.outcome == DiagnosticOutcome.FAILURE }
            .map { DiagnosticIssue(it.component, it.stage, it.kind!!) })
        assertFalse(events.any { it.outcome == DiagnosticOutcome.READY })
    }

    @Test fun nativeMemoryIoAndOtherFailuresUseFixedCategories() {
        val cases = listOf(
            UnsatisfiedLinkError("jni path") to DiagnosticKind.NATIVE_LINK,
            NoClassDefFoundError("jni symbol") to DiagnosticKind.NATIVE_LINK,
            OutOfMemoryError("allocation detail") to DiagnosticKind.MEMORY,
            IOException("file detail") to DiagnosticKind.IO,
            SecurityException("permission detail") to DiagnosticKind.SECURITY,
            IllegalArgumentException("input detail") to DiagnosticKind.INVALID_DATA,
            IllegalStateException("state detail") to DiagnosticKind.INVALID_DATA,
            RuntimeException("runtime detail") to DiagnosticKind.RUNTIME
        )
        val reporter = DiagnosticReporter({ 0L }, {})
        cases.forEach { (cause, expected) ->
            val error = stageFailure {
                reporter.atStage(DiagnosticComponent.MOZC, DiagnosticStage.QUERY) { throw cause }
            }
            assertEquals(cause.javaClass.simpleName, expected, error.issue.kind)
            assertSame(cause, error.cause)
        }
    }

    @Test fun errorOutputContainsOnlyFixedVocabularyAndNeverCauseOrUserContent() {
        val events = mutableListOf<DiagnosticEvent>()
        val reporter = DiagnosticReporter({ 0L }, events::add).apply { enabled = true }
        val raw = "private input 漢字"
        val path = "/private/customer/dictionary.bin"
        val cause = IOException("$raw at $path").apply {
            stackTrace = arrayOf(StackTraceElement("PrivateCustomer", "secretMethod", path, 77))
        }
        val error = stageFailure {
            reporter.atStage(DiagnosticComponent.ENGLISH, DiagnosticStage.QUERY) { throw cause }
        }
        val output = events.joinToString("\n") { it.line() }
        assertEquals("ENGLISH/QUERY/FAILURE/IO", output)
        listOf(raw, path, "PrivateCustomer", "secretMethod", "IOException", "77").forEach {
            assertFalse("Output contains private cause detail: $it", output.contains(it))
        }
        assertSame(cause, error.cause)
        assertEquals("ENGLISH/QUERY/IO", error.message)
    }

    @Test fun brokenSinksCannotChangeSuccessfulOperationsOrTheirFailureFallbacks() {
        val sinkFailures = listOf(
            IllegalStateException("sink unavailable"),
            UnsatisfiedLinkError("sink unavailable"),
            OutOfMemoryError("sink unavailable")
        )
        sinkFailures.forEach { sinkFailure ->
            val reporter = DiagnosticReporter({ 0L }, { throw sinkFailure }).apply { enabled = true }
            assertEquals("ready", reporter.atStage(DiagnosticComponent.RIME, DiagnosticStage.INITIALIZE) { "ready" })
            val operationFailure = IOException("operation failure")
            var fallbackCalls = 0
            val value = try {
                reporter.atStage(DiagnosticComponent.RIME, DiagnosticStage.QUERY) { throw operationFailure }
            } catch (error: DiagnosticStageException) {
                assertSame(operationFailure, error.cause)
                assertEquals(DiagnosticKind.IO, error.issue.kind)
                fallbackCalls++
                emptyList<String>()
            }
            assertTrue(value.isEmpty())
            assertEquals(1, fallbackCalls)
        }
    }

    @Test fun duplicateFailuresRespectTheClockBoundaryAndRecoverFromClockReversal() {
        val events = mutableListOf<DiagnosticEvent>()
        var now = 1_000L
        val reporter = DiagnosticReporter({ now }, events::add).apply { enabled = true }
        fun failBinding() = reporter.signal(DiagnosticComponent.RIME, DiagnosticStage.BIND, DiagnosticKind.BIND_REJECTED)
        failBinding()
        now = 60_999L
        failBinding()
        assertEquals(1, events.size)
        now = 61_000L
        failBinding()
        assertEquals(2, events.size)
        now = 60_999L
        failBinding()
        assertEquals(3, events.size)
    }

    @Test fun successfulRecoveryAllowsAnImmediateNewFailureAtTheSameStage() {
        val events = mutableListOf<DiagnosticEvent>()
        val reporter = DiagnosticReporter({ 10L }, events::add).apply { enabled = true }
        val first = reporter.failure(DiagnosticComponent.SEMANTIC, DiagnosticStage.INFERENCE, IOException("first"))
        assertEquals(listOf("candidate"), reporter.atStage(DiagnosticComponent.SEMANTIC, DiagnosticStage.INFERENCE) {
            listOf("candidate")
        })
        val second = reporter.failure(DiagnosticComponent.SEMANTIC, DiagnosticStage.INFERENCE, IOException("second"))
        assertEquals(first, second)
        assertEquals(2, events.count { it.outcome == DiagnosticOutcome.FAILURE })
    }

    @Test fun unrelatedRecoveryDoesNotEraseAnotherComponentsFailureThrottle() {
        val events = mutableListOf<DiagnosticEvent>()
        val reporter = DiagnosticReporter({ 10L }, events::add).apply { enabled = true }
        reporter.failure(DiagnosticComponent.RIME, DiagnosticStage.QUERY, IOException("first"))
        reporter.atStage(DiagnosticComponent.MOZC, DiagnosticStage.QUERY) { emptyList<String>() }
        reporter.atStage(DiagnosticComponent.RIME, DiagnosticStage.LOAD) { true }
        reporter.failure(DiagnosticComponent.RIME, DiagnosticStage.QUERY, IOException("duplicate"))
        assertEquals(1, events.count { it.outcome == DiagnosticOutcome.FAILURE })
    }

    @Test fun aDifferentFailureCategoryAtTheSameStageIsReportedIndependently() {
        val events = mutableListOf<DiagnosticEvent>()
        val reporter = DiagnosticReporter({ 10L }, events::add).apply { enabled = true }
        reporter.failure(DiagnosticComponent.ENGLISH, DiagnosticStage.QUERY, IOException("io"))
        reporter.failure(DiagnosticComponent.ENGLISH, DiagnosticStage.QUERY, OutOfMemoryError("memory"))
        assertEquals(listOf(DiagnosticKind.IO, DiagnosticKind.MEMORY), events.map { it.kind })
    }

    @Test fun anOrdinaryQueryWithNoCandidatesProducesNoErrorOrLifecycleNoise() {
        val events = mutableListOf<DiagnosticEvent>()
        val reporter = DiagnosticReporter({ 0L }, events::add).apply { enabled = true }
        val candidates = reporter.atStage(DiagnosticComponent.RIME, DiagnosticStage.QUERY) { emptyList<String>() }
        assertTrue(candidates.isEmpty())
        assertTrue(events.isEmpty())
    }

    private fun stageFailure(operation: () -> Unit): DiagnosticStageException {
        try {
            operation()
            fail("Expected a staged operation failure")
        } catch (error: DiagnosticStageException) {
            return error
        }
        throw AssertionError("Expected a staged operation failure")
    }
}
