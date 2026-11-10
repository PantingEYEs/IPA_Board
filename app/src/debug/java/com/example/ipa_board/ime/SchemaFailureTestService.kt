package com.example.ipa_board.ime

import com.example.ipa_board.diagnostics.DiagnosticComponent
import com.example.ipa_board.diagnostics.DiagnosticIssue
import com.example.ipa_board.diagnostics.DiagnosticKind
import com.example.ipa_board.diagnostics.DiagnosticStage

/** Debug-only owned engine: exercises the production service/diagnostic/IPC boundary without native data. */
class SchemaFailureTestService : EngineService() {
    override val language = "ZH"
    override val supportsPrediction = true
    override fun createEngine(): QueryEngine = object : QueryEngine {
        private var policy = EngineQueryPolicy()
        override fun configure(policy: EngineQueryPolicy) { this.policy = policy }
        override fun acceptsInput(raw: String) = raw.isNotEmpty()
        override fun query(raw: String, beforeCursor: String): List<Candidate> {
            if (raw.isEmpty() && beforeCursor == "测试上文" && policy.pinyinScheme == PinyinScheme.TERRA) {
                return listOf(Candidate("续写恢复", "繁", 0, CandidateKind.PREDICTION))
            }
            return query(raw)
        }
        override fun query(raw: String): List<Candidate> {
            if (raw == "fatal") throw RuntimeException("Owned generic query failure")
            if (raw == "ㄋㄧˇ") return listOf(Candidate("注音恢复", "繁", 0))
            if (policy.pinyinScheme == PinyinScheme.TERRA) {
                throw RimeSchemaUnavailableException(DiagnosticIssue(
                    DiagnosticComponent.RIME, DiagnosticStage.ASSETS, DiagnosticKind.IO))
            }
            return listOf(Candidate("朙月恢复", "繁", 0))
        }
    }
}
