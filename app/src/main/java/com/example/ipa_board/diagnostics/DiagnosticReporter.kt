package com.example.ipa_board.diagnostics

import java.io.IOException

/** DIAGNOSTICS: fixed vocabulary only. Never add input, paths, messages or Throwable to events. */
enum class DiagnosticComponent { RIME, MOZC, ENGLISH, IPA, SEMANTIC, LAYOUT, EMOJI, KAOMOJI, SMS, VERSION_LOOKUP }

enum class DiagnosticStage(val label: String, val traceLifecycle: Boolean = true) {
    BIND("service binding"), UNBIND("service release"), CONNECT("service connection"),
    DISCONNECT("service connection"), SEND("request delivery", false), REPLY("response delivery", false),
    RESPONSE("response format", false), CANCEL("request cancellation", false),
    LIBRARY("native library"), API("native API"), ASSETS("assets"),
    INITIALIZE("runtime initialization"), MAINTENANCE("schema deployment"),
    SESSION("native session", false), SCHEMA("schema selection"), CONVERSION("script conversion", false),
    DICTIONARY("dictionary"), PROXIMITY("keyboard map"), PREDICTION_CORPUS("prediction corpus"),
    MANIFEST("asset manifest"), LOCK("asset lock"), MODEL_ASSEMBLY("model assembly"),
    INTEGRITY("asset integrity"), PUBLISH("asset publication"), TOKENIZER("tokenizer"),
    CUSTOM_OPS("tokenizer library"), MODEL("model"), CALIBRATION("model calibration"),
    QUERY("candidate query", false), INFERENCE("semantic inference", false), CLOSE("runtime cleanup"), CLEANUP("temporary cleanup", false),
    LOAD("data loading", false), SAVE("data saving", false), UPDATE("catalog update"),
    VERSION_CHECK("version lookup", false)
}

enum class DiagnosticKind {
    IO, SECURITY, INVALID_DATA, NATIVE_LINK, NATIVE_HANDLE, MEMORY, RUNTIME,
    IPC, BIND_REJECTED, DISCONNECTED, BINDING_DIED, NULL_BINDING, INVALID_RESPONSE
}

data class DiagnosticIssue(val component: DiagnosticComponent, val stage: DiagnosticStage, val kind: DiagnosticKind) {
    val code: String get() = "${component.name}/${stage.name}/${kind.name}"
    companion object {
        /** Reject untrusted IPC strings instead of displaying/logging them as error messages. */
        fun fromWire(value: String?): DiagnosticIssue? {
            if (value == null || value.length > 100) return null
            val parts = value.split('/')
            if (parts.size != 3) return null
            val component = DiagnosticComponent.entries.firstOrNull { it.name == parts[0] } ?: return null
            val stage = DiagnosticStage.entries.firstOrNull { it.name == parts[1] } ?: return null
            val kind = DiagnosticKind.entries.firstOrNull { it.name == parts[2] } ?: return null
            return DiagnosticIssue(component, stage, kind)
        }
    }
}

enum class DiagnosticOutcome { START, READY, FAILURE }
data class DiagnosticEvent(val component: DiagnosticComponent, val stage: DiagnosticStage,
    val outcome: DiagnosticOutcome, val kind: DiagnosticKind? = null) {
    fun line(): String = "${component.name}/${stage.name}/${outcome.name}" + (kind?.let { "/${it.name}" } ?: "")
}

/** Only the fixed issue is exposed to IPC/logging. The cause remains available locally for control flow. */
class DiagnosticStageException(val issue: DiagnosticIssue, cause: Throwable) : RuntimeException(issue.code, cause)

/** DIAGNOSTICS: optional, bounded, non-persistent sink; replacing emit with a no-op disables all logging. */
class DiagnosticReporter(private val clock: () -> Long, private val sink: (DiagnosticEvent) -> Unit) {
    private val recentFailures = LinkedHashMap<DiagnosticIssue, Long>()
    @Volatile var enabled = false
        @Synchronized set(value) { field = value; if (!value) recentFailures.clear() }

    fun <T> atStage(component: DiagnosticComponent, stage: DiagnosticStage, operation: () -> T): T {
        if (stage.traceLifecycle) emit(DiagnosticEvent(component, stage, DiagnosticOutcome.START))
        try {
            val value = operation()
            recovered(component, stage)
            if (stage.traceLifecycle) emit(DiagnosticEvent(component, stage, DiagnosticOutcome.READY))
            return value
        } catch (error: Exception) { throw staged(component, stage, error) }
        catch (error: LinkageError) { throw staged(component, stage, error) }
        catch (error: OutOfMemoryError) { throw staged(component, stage, error) }
    }

    fun failure(component: DiagnosticComponent, stage: DiagnosticStage, error: Throwable): DiagnosticIssue {
        val issue = (error as? DiagnosticStageException)?.issue ?: DiagnosticIssue(component, stage, kind(error))
        report(issue)
        return issue
    }

    fun signal(component: DiagnosticComponent, stage: DiagnosticStage, kind: DiagnosticKind): DiagnosticIssue =
        DiagnosticIssue(component, stage, kind).also(::report)

    private fun staged(component: DiagnosticComponent, stage: DiagnosticStage, error: Throwable): DiagnosticStageException {
        val issue = failure(component, stage, error)
        return error as? DiagnosticStageException ?: DiagnosticStageException(issue, error)
    }

    @Synchronized private fun recovered(component: DiagnosticComponent, stage: DiagnosticStage) {
        recentFailures.keys.removeAll { it.component == component && it.stage == stage }
    }

    @Synchronized private fun report(issue: DiagnosticIssue) {
        if (!enabled) return
        val now = clock()
        val previous = recentFailures[issue]
        if (previous != null && now >= previous && now - previous < 60_000L) return
        if (recentFailures.size >= 128 && issue !in recentFailures) recentFailures.remove(recentFailures.keys.first())
        recentFailures[issue] = now
        emit(DiagnosticEvent(issue.component, issue.stage, DiagnosticOutcome.FAILURE, issue.kind))
    }

    @Synchronized private fun emit(event: DiagnosticEvent) {
        if (!enabled) return
        // Diagnostic output must never break the original operation or its fallback.
        try { sink(event) } catch (_: Exception) { } catch (_: LinkageError) { } catch (_: OutOfMemoryError) { }
    }

    private fun kind(error: Throwable): DiagnosticKind = when (error) {
        is SecurityException -> DiagnosticKind.SECURITY
        is IOException -> DiagnosticKind.IO
        is LinkageError -> DiagnosticKind.NATIVE_LINK
        is OutOfMemoryError -> DiagnosticKind.MEMORY
        is IllegalArgumentException, is IllegalStateException -> DiagnosticKind.INVALID_DATA
        else -> DiagnosticKind.RUNTIME
    }
}
