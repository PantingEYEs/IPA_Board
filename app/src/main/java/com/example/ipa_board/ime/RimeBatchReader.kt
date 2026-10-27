package com.example.ipa_board.ime

import android.content.Context
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import java.io.File

/** The bridge links the already loaded, pinned Rime runtime. It never initializes another engine. */
internal class RimeBatchReader(context: Context) {
    private val library = NativeLibrary.getInstance(File(context.applicationInfo.nativeLibraryDir, "libipa_rime_batch.so").absolutePath)
    private val read = library.getFunction("ipa_rime_read_candidates")
    private val free = library.getFunction("ipa_rime_free_candidates")
    fun read(session: Long, input: String): List<RimeNativeCandidate> {
        Memory(4).use { length ->
            length.clear()
            val packet = checkNotNull(read.invokePointer(arrayOf(session, input, RimeCandidateBatch.LIMIT, length))) {
                "Rime batch allocation failed"
            }
            try {
                val size = length.getInt(0)
                check(size in 32..RimeCandidateBatch.MAX_BYTES) { "Invalid native Rime batch size" }
                return RimeCandidateBatch.decode(packet.getByteArray(0, size), input.toByteArray(Charsets.UTF_8).size)
            } finally { free.invokeVoid(arrayOf(packet)) }
        }
    }
}
