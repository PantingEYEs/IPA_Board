package com.example.ipa_board

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import com.example.ipa_board.ime.EngineVersionLookup
import com.example.ipa_board.ime.EngineVersionSource

/** The host workflow creates the verified external snapshot before issuing this capability. */
class RegressionTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle) {
        val snapshot = arguments.getString("regressionSnapshot")
        if (snapshot == null || !snapshot.matches(Regex("[a-fA-F0-9-]{32,36}"))) {
            finish(1, Bundle().apply {
                putString("stream", "Device tests require a verified backup. Run python3 tools/regression.py run; direct instrumentation is disabled.")
                putString("Error", "Missing regression snapshot")
            })
            return
        }
        // UI regression must exercise version presentation without depending on
        // public endpoints. Lookup contract tests use their own injected loaders.
        EngineVersionLookup.metadataLoaderOverride = { source ->
            if (source == EngineVersionSource.E5) {
                """{"sha":"0000000000000000000000000000000000000000"}"""
            } else {
                """{"tag_name":"v-test","draft":false,"prerelease":false}"""
            }
        }
        super.onCreate(arguments)
    }
}
