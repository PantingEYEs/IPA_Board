package com.example.ipa_board.ipa

import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import com.example.ipa_board.R
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Page-scoped, cancellable lookup. No input text, result text or private path is logged. */
internal class IpaLookupPanel(
    private val activity: Activity,
    private val snapshot: () -> IpaPackageSnapshot?,
    private val lookup: (IpaPackageSnapshot, String) -> IpaLookupResult = { resources, text ->
        IpaDictionaryLookup(resources.dictionaryFile).use { it.lookup(text) }
    }
) : AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor()
    private val input = activity.findViewById<EditText>(R.id.ipa_lookup_input)
    private val button = activity.findViewById<Button>(R.id.ipa_lookup_button)
    private val progress = activity.findViewById<ProgressBar>(R.id.ipa_lookup_progress)
    private val status = activity.findViewById<TextView>(R.id.ipa_lookup_status)
    private val results = activity.findViewById<TextView>(R.id.ipa_lookup_results)
    private var revision = 0L
    private var pending: Future<*>? = null
    private var closed = false

    init {
        button.setOnClickListener { submit() }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { invalidate() }
            override fun afterTextChanged(s: Editable?) {}
        })
    }
    fun invalidate() {
        revision++; pending?.cancel(true); pending = null
        progress.visibility = View.GONE
        status.text = ""; results.text = ""
    }
    private fun submit() {
        invalidate()
        val text = input.text.toString() // Exact entry: never trim, fold case, or transliterate.
        if (text.isEmpty()) { status.setText(R.string.ipa_lookup_empty); return }
        if (text.length > 2000) { status.setText(R.string.ipa_lookup_too_long); return }
        val request = revision
        progress.visibility = View.VISIBLE
        status.setText(R.string.ipa_lookup_searching)
        pending = worker.submit task@{
            try {
                val resources = snapshot()
                if (resources == null) { publish(request) { status.setText(R.string.ipa_lookup_unavailable) }; return@task }
                val result = lookup(resources, text)
                // Format on the worker too, including region grouping and up to 500 readings.
                val rendered = IpaDictionaryLookup.REGIONS.mapNotNull { region ->
                    val readings = result.readings.filter { it.region == region }
                    if (readings.isEmpty()) null else region + "\n" + readings.joinToString("\n") {
                        it.ipa + if (it.derived) " · " + activity.getString(R.string.ipa_lookup_derived) else ""
                    }
                }.joinToString("\n\n")
                publish(request) {
                    // A generation can change after scanning but before delivery. Only this small
                    // atomic snapshot read occurs here; dictionary reading/formatting stays off UI.
                    val current = snapshot()
                    if (current?.generation != resources.generation || current.dictionaryFile != resources.dictionaryFile) {
                        status.setText(R.string.ipa_lookup_changed)
                    } else {
                        results.text = rendered
                        status.text = if (result.readings.isEmpty()) activity.getString(R.string.ipa_lookup_not_found)
                        else activity.getString(if (result.truncated) R.string.ipa_lookup_truncated else R.string.ipa_lookup_count,
                            result.readings.size, resources.dictionaryVersion)
                    }
                }
            } catch (_: InterruptedException) { /* A newer input or resource generation owns the page now. */ }
            catch (_: UnsupportedIpaDictionary) { fail(request, R.string.ipa_lookup_unsupported) }
            catch (_: IOException) { fail(request, R.string.ipa_lookup_read_failed) }
            catch (_: Exception) { fail(request, R.string.ipa_lookup_invalid) }
            catch (_: LinkageError) { fail(request, R.string.ipa_lookup_unsupported) }
            catch (_: OutOfMemoryError) { fail(request, R.string.ipa_lookup_read_failed) }
        }
    }
    private fun fail(request: Long, message: Int) = publish(request) { status.setText(message) }
    private fun publish(request: Long, action: () -> Unit) {
        activity.runOnUiThread {
            if (!closed && !activity.isFinishing && !activity.isDestroyed && request == revision) {
                progress.visibility = View.GONE
                action()
            }
        }
    }
    override fun close() { closed = true; invalidate(); worker.shutdownNow() }
}
