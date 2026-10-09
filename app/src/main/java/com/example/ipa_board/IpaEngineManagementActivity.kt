package com.example.ipa_board

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import com.example.ipa_board.ipa.IpaFailure
import com.example.ipa_board.ipa.IpaPackageSnapshot
import com.example.ipa_board.ipa.IpaRemoteRelease
import com.example.ipa_board.ipa.IpaResourceException
import com.example.ipa_board.ipa.IpaResourceManager
import java.util.concurrent.Executors

/** App-owned controls for the development IPA package; resource work never runs on the UI thread. */
class IpaEngineManagementActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var destroyed = false
    private var busy = false
    private lateinit var backend: IpaManagementBackend
    private lateinit var buttons: List<Button>
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var localVersions: TextView
    private lateinit var localFiles: TextView
    private lateinit var remoteVersions: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ipa_engine_management)
        backend = backendFactory?.invoke(applicationContext) ?: ResourceBackend(applicationContext)
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
        val root = findViewById<View>(R.id.ipa_management_root).apply { tag = TEST_TAG_ROOT }
        root.setOnApplyWindowInsetsListener { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            windowInsets
        }
        root.requestApplyInsets()
        localVersions = findViewById<TextView>(R.id.ipa_management_local_versions).apply { tag = TEST_TAG_LOCAL_VERSIONS }
        localFiles = findViewById(R.id.ipa_management_local_files)
        remoteVersions = findViewById<TextView>(R.id.ipa_management_remote_versions).apply { tag = TEST_TAG_REMOTE_VERSIONS }
        status = findViewById<TextView>(R.id.ipa_management_operation_status).apply { tag = TEST_TAG_STATUS }
        progress = findViewById(R.id.ipa_management_progress)
        val check = findViewById<Button>(R.id.ipa_management_check).apply { tag = TEST_TAG_CHECK }
        val engine = findViewById<Button>(R.id.ipa_management_update_engine).apply { tag = TEST_TAG_UPDATE_ENGINE }
        val dictionary = findViewById<Button>(R.id.ipa_management_update_dictionary).apply { tag = TEST_TAG_UPDATE_DICTIONARY }
        buttons = listOf(check, engine, dictionary)
        findViewById<View>(R.id.ipa_management_abi_notice).visibility =
            if (Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }) View.GONE else View.VISIBLE

        check.setOnClickListener {
            runOperation(R.string.ipa_management_checking, R.string.ipa_management_checked) {
                Outcome(remote = backend.checkRemote())
            }
        }
        engine.setOnClickListener {
            runOperation(R.string.ipa_management_updating_engine, R.string.ipa_management_engine_updated) {
                Outcome(local = backend.updateEngine())
            }
        }
        dictionary.setOnClickListener {
            runOperation(R.string.ipa_management_updating_dictionary, R.string.ipa_management_dictionary_updated) { report ->
                Outcome(local = backend.updateDictionary(report))
            }
        }
        runOperation(R.string.ipa_management_installing, R.string.ipa_management_ready) {
            Outcome(local = backend.ensureInstalled())
        }
    }

    private fun runOperation(initialStatus: Int, successStatus: Int, action: ((String) -> Unit) -> Outcome) {
        if (busy || destroyed || isFinishing) return
        setBusy(true)
        status.setText(initialStatus)
        executor.execute {
            var lastProgressAt = 0L
            val report: (String) -> Unit = { text ->
                val now = SystemClock.elapsedRealtime()
                if (now - lastProgressAt >= 200L) {
                    lastProgressAt = now
                    postToActiveView { status.text = text }
                }
            }
            try {
                val outcome = action(report)
                postToActiveView {
                    outcome.local?.let(::showLocal)
                    outcome.remote?.let(::showRemote)
                    status.setText(successStatus)
                    setBusy(false)
                }
            } catch (error: Exception) {
                showFailure(error)
            } catch (error: LinkageError) {
                // A development native package can be incompatible; keep this failure in the resource page.
                showFailure(error)
            }
        }
    }

    private fun showFailure(error: Throwable) {
        val local = try { backend.snapshot() } catch (_: Exception) { null }
        postToActiveView {
            if (local != null) showLocal(local)
            else {
                localVersions.setText(R.string.ipa_management_local_missing)
                localFiles.text = ""
            }
            status.setText(failureMessage(error))
            setBusy(false)
        }
    }

    private fun postToActiveView(action: () -> Unit) {
        if (destroyed) return
        runOnUiThread {
            if (!destroyed && !isDestroyed && !isFinishing) action()
        }
    }

    private fun showLocal(snapshot: IpaPackageSnapshot) {
        localVersions.text = getString(R.string.ipa_management_local_versions, snapshot.engineVersion, snapshot.dictionaryVersion)
        fun availability(exists: Boolean) = getString(if (exists) R.string.ipa_management_available else R.string.ipa_management_missing)
        localFiles.text = getString(R.string.ipa_management_local_files,
            availability(snapshot.dictionaryFile.isFile))
    }

    private fun showRemote(release: IpaRemoteRelease) {
        remoteVersions.text = getString(R.string.ipa_management_remote_versions,
            release.engineVersion, release.dictionaryVersion, release.dictionaryKeys)
    }

    private fun setBusy(value: Boolean) {
        busy = value
        buttons.forEach { it.isEnabled = !value }
        progress.visibility = if (value) View.VISIBLE else View.GONE
    }

    private fun failureMessage(error: Throwable): Int = when {
        error is IpaResourceException -> when (error.reason) {
            IpaFailure.NETWORK -> R.string.ipa_management_failure_network
            IpaFailure.PACKAGE_INVALID, IpaFailure.VALIDATION -> R.string.ipa_management_failure_resources
            IpaFailure.INCOMPATIBLE_ENGINE, IpaFailure.UNSUPPORTED_ABI -> R.string.ipa_management_failure_unsupported
            IpaFailure.STORAGE -> R.string.ipa_management_failure_storage
            IpaFailure.BUSY -> R.string.ipa_management_failure_busy
        }
        error is LinkageError -> R.string.ipa_management_failure_unsupported
        else -> R.string.ipa_management_failure_other
    }

    override fun onDestroy() {
        destroyed = true
        // Finish resource publication/cleanup even when the page closes; never interrupt an active replacement.
        executor.shutdown()
        super.onDestroy()
    }

    private data class Outcome(val local: IpaPackageSnapshot? = null, val remote: IpaRemoteRelease? = null)

    private class ResourceBackend(context: Context) : IpaManagementBackend {
        private val manager = IpaResourceManager(context)
        override fun ensureInstalled() = manager.ensureInstalled()
        override fun snapshot() = manager.snapshot()
        override fun checkRemote() = manager.checkRemote()
        override fun updateEngine() = manager.updateEngine()
        override fun updateDictionary(progress: (String) -> Unit) = manager.updateDictionary(progress)
    }

    companion object {
        const val TEST_TAG_ENTRY = "ipa-management-entry"
        const val TEST_TAG_ROOT = "ipa-management-root"
        const val TEST_TAG_LOCAL_VERSIONS = "ipa-management-local-versions"
        const val TEST_TAG_REMOTE_VERSIONS = "ipa-management-remote-versions"
        const val TEST_TAG_STATUS = "ipa-management-status"
        const val TEST_TAG_CHECK = "ipa-management-check"
        const val TEST_TAG_UPDATE_ENGINE = "ipa-management-update-engine"
        const val TEST_TAG_UPDATE_DICTIONARY = "ipa-management-update-dictionary"

        @Volatile @VisibleForTesting
        var backendFactory: ((Context) -> IpaManagementBackend)? = null
    }
}

/** Injectable only to keep UI contracts independent of public-network requests. */
interface IpaManagementBackend {
    fun ensureInstalled(): IpaPackageSnapshot
    fun snapshot(): IpaPackageSnapshot?
    fun checkRemote(): IpaRemoteRelease
    fun updateEngine(): IpaPackageSnapshot
    fun updateDictionary(progress: (String) -> Unit): IpaPackageSnapshot
}
