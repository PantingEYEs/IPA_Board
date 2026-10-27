package com.example.ipa_board.ime

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.SystemClock

/**
 * TIMING PROBE: opt-in instrumentation only; remove this file and the marked hooks to remove it.
 * Monotonic nanoseconds are comparable across Android processes. Never includes input/candidates.
 * No preference, log file or global observer: normal queries carry no timing envelope.
 */
internal data class CandidateTimingEvent(
    val requestId: Long, val revision: Long, val engine: String, val stage: String,
    val startNs: Long, val endNs: Long, val count: Int = 0, val outcome: String = "ok"
)

internal class CandidateTiming(private val receive: (CandidateTimingEvent) -> Unit) {
    fun record(requestId: Long, revision: Long, engine: String, stage: String,
               start: Long, end: Long = now(), count: Int = 0, outcome: String = "ok") {
        // A diagnostic consumer must never break candidate delivery.
        try { receive(CandidateTimingEvent(requestId, revision, engine, stage, start, end, count, outcome)) }
        catch (_: Exception) { }
    }

    companion object {
        const val KEY = "candidateTiming"
        fun now(): Long = SystemClock.elapsedRealtimeNanos()
    }
}

/** Only debug packages honor the envelope, even when a caller sends it to a release service. */
internal class EngineQueryTiming private constructor(private val stamps: Bundle) {
    fun mark(name: String) { stamps.putLong(name, CandidateTiming.now()) }
    fun reply(result: Bundle) { mark("reply"); result.putBundle(CandidateTiming.KEY, stamps) }

    companion object {
        fun begin(context: Context, request: Bundle): EngineQueryTiming? {
            val sent = request.getLong(CandidateTiming.KEY)
            if (sent <= 0L) return null
            if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
            return EngineQueryTiming(Bundle().apply { putLong("sent", sent) }).also { it.mark("received") }
        }
    }
}
