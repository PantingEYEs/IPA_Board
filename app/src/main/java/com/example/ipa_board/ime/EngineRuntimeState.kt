package com.example.ipa_board.ime

/** Native instance state, separate from feature switches and from candidate query revisions. */
internal enum class EngineRuntimeState { UNKNOWN, CONNECTING, UNINITIALIZED, INITIALIZING, READY, BUSY, UNAVAILABLE }
internal enum class EngineBackend { RIME, MOZC, LATINIME, IPA, SEMANTIC }

/** Main-thread owned. Reading/subscribing never binds, queries or releases an engine. */
internal class EngineRuntimeStates {
    private data class Observation(var epoch: Long = 0, var instance: String? = null, var sequence: Long = -1,
        var state: EngineRuntimeState = EngineRuntimeState.UNKNOWN)
    private val observations = EngineBackend.entries.associateWith { Observation() }
    private val listeners = linkedSetOf<() -> Unit>()
    fun snapshot(): Map<EngineBackend, EngineRuntimeState> = observations.mapValues { it.value.state }
    fun observe(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        listener()
        return { listeners.remove(listener) }
    }
    fun connecting(backend: EngineBackend): Long {
        val observation = observations.getValue(backend)
        observation.epoch++; observation.instance = null; observation.sequence = -1
        update(observation, EngineRuntimeState.CONNECTING)
        return observation.epoch
    }
    fun disconnected(backend: EngineBackend, unavailable: Boolean = false) {
        val observation = observations.getValue(backend)
        observation.epoch++; observation.instance = null; observation.sequence = -1
        update(observation, if (unavailable) EngineRuntimeState.UNAVAILABLE else EngineRuntimeState.UNKNOWN)
    }
    fun accept(backend: EngineBackend, epoch: Long, instance: String, sequence: Long, state: EngineRuntimeState): Boolean {
        val observation = observations.getValue(backend)
        if (epoch != observation.epoch || instance.isEmpty() || instance.length > 128 || sequence < 0 ||
            sequence <= observation.sequence || (observation.instance != null && instance != observation.instance)) return false
        observation.instance = instance; observation.sequence = sequence
        update(observation, state)
        return true
    }
    private fun update(observation: Observation, state: EngineRuntimeState) {
        if (observation.state == state) return
        observation.state = state
        listeners.toList().forEach { it() }
    }
}
