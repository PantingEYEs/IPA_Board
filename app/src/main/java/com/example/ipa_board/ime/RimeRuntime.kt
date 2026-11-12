package com.example.ipa_board.ime

import com.example.ipa_board.diagnostics.AppDiagnostics
import com.example.ipa_board.diagnostics.DiagnosticComponent
import com.example.ipa_board.diagnostics.DiagnosticStage
import org.ipaboard.rime.RimeNative
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** One owned runtime; native handles and lazy schema sessions stay behind this boundary. */
internal interface RimeRuntime : AutoCloseable {
    fun query(schema: String, input: String, limit: Int): ByteArray
    fun convert(text: String, direction: RimeConversion): String
    override fun close()
    companion object { const val MAX_INPUT_BYTES = 256 }
}

internal enum class RimeConversion(val nativeValue: Int) { TO_SIMPLIFIED(0), TO_TRADITIONAL(1) }

/** The AAR, packet reader and recorded source build must describe the same runtime. */
internal object RimeBuildIdentity {
    fun validate(abiVersion: Int, identity: ByteArray) {
        check(abiVersion == RimeBundle.ABI_VERSION) { "Rime native ABI mismatch" }
        val expected = "ipa-rime;abi=${RimeBundle.ABI_VERSION};packet=${RimeBundle.PACKET_VERSION};" +
            "librime=${RimeBundle.UPSTREAM_VERSION};opencc=${RimeBundle.OPENCC_VERSION};build=${RimeBundle.BUILD_ID}"
        // Byte equality also rejects malformed UTF-8, duplicate fields and embedded NULs.
        check(identity.contentEquals(expected.toByteArray(Charsets.UTF_8))) { "Rime native build identity mismatch" }
    }
}

internal class RimeNativeRuntime private constructor(private var owner: Long) : RimeRuntime {
    companion object {
        fun open(shared: File, user: File): RimeRuntime {
            val abi = AppDiagnostics.atStage(DiagnosticComponent.RIME, DiagnosticStage.LIBRARY) { RimeNative.abiVersion() }
            AppDiagnostics.atStage(DiagnosticComponent.RIME, DiagnosticStage.API) {
                RimeBuildIdentity.validate(abi, RimeNative.identity())
            }
            return AppDiagnostics.atStage(DiagnosticComponent.RIME, DiagnosticStage.INITIALIZE) {
                val handle = try {
                    RimeNative.open(utf8(shared.absolutePath), utf8(user.absolutePath),
                        utf8(File(shared, "opencc/t2s.json").absolutePath),
                        utf8(File(shared, "opencc/s2t.json").absolutePath))
                } catch (error: IllegalStateException) {
                    if (error.message == "IPA_RIME_CONVERSION_OPEN_FAILED") {
                        AppDiagnostics.atStage(DiagnosticComponent.RIME, DiagnosticStage.CONVERSION) { throw error }
                    }
                    throw error
                }
                try {
                    check(handle > 0L) { "Rime native owner unavailable" }
                    RimeNativeRuntime(handle)
                } catch (error: Throwable) {
                    if (handle > 0L) runCatching { RimeNative.close(handle) }
                    throw error
                }
            }
        }

        private fun utf8(text: String): ByteArray {
            val buffer = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text))
            return ByteArray(buffer.remaining()).also { buffer.get(it) }
        }
    }

    @Synchronized override fun query(schema: String, input: String, limit: Int): ByteArray {
        check(owner != 0L) { "Rime runtime is closed" }
        return RimeNative.query(owner, utf8(schema), utf8(input), limit)
    }

    @Synchronized override fun convert(text: String, direction: RimeConversion): String {
        check(owner != 0L) { "Rime runtime is closed" }
        val result = RimeNative.convert(owner, utf8(text), direction.nativeValue)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(result)).toString()
    }

    @Synchronized override fun close() {
        val handle = owner
        if (handle == 0L) return
        try { RimeNative.close(handle) } finally { owner = 0L }
    }
}
