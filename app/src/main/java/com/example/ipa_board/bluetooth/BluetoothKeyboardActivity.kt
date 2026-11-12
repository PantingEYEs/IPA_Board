package com.example.ipa_board.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.PopupMenu
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.ipa_board.R
import com.example.ipa_board.bluetooth.BluetoothKeyboardSession.Phase
import com.example.ipa_board.bluetooth.BluetoothKeyboardSession.State
import com.example.ipa_board.bluetooth.HidKeyboardBackend.Failure
import com.example.ipa_board.bluetooth.HidKeyboardBackend.Host
import java.util.UUID

/** A local composition field using the user's current IME, with explicit remote transmission. */
class BluetoothKeyboardActivity : Activity() {
    private lateinit var session: BluetoothKeyboardSession
    private lateinit var editor: RemoteKeyboardEditText
    private lateinit var status: TextView
    private lateinit var feedback: TextView
    private lateinit var locks: TextView
    private lateinit var modeHelp: TextView
    private lateinit var immediate: SwitchCompat
    private lateinit var hostPicker: Spinner
    private lateinit var startButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var connectButton: Button
    private lateinit var refreshButton: Button
    private lateinit var discoverButton: Button
    private lateinit var sendButton: Button
    private lateinit var clearButton: Button
    private lateinit var controlButton: Button
    private lateinit var showImeButton: Button
    private lateinit var chooseImeButton: Button
    private lateinit var modifiers: List<Pair<CheckBox, Int>>
    private var hosts = emptyList<Host>()
    private var resumed = false
    private var updatingMode = false
    private var lastPhase: Phase? = null
    private var markerToken = ""
    private var pendingAction: PendingAction? = null
    private val handler = Handler(Looper.getMainLooper())
    private var sendGeneration = 0L
    private var pendingSend: SendJob? = null
    private val observer: (State) -> Unit = { render(it) }
    private enum class PendingAction { START, DISCOVERABLE }

    private class SendJob(
        val generation: Long,
        val hostAddress: String,
        val strokes: List<HidTextEncoder.Stroke>,
        val modifiers: Int,
        val complete: (Boolean) -> Unit
    ) {
        var index = 0
        var attempted = false
        var task: Runnable? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bluetooth_keyboard)
        session = BluetoothKeyboardRuntime.session(applicationContext)
        editor = findViewById(R.id.bluetooth_keyboard_editor)
        status = findViewById(R.id.bluetooth_keyboard_status)
        feedback = findViewById(R.id.bluetooth_keyboard_feedback)
        locks = findViewById(R.id.bluetooth_keyboard_leds)
        modeHelp = findViewById(R.id.bluetooth_keyboard_mode_help)
        immediate = findViewById(R.id.bluetooth_keyboard_immediate)
        hostPicker = findViewById(R.id.bluetooth_keyboard_hosts)
        startButton = findViewById(R.id.bluetooth_keyboard_start)
        disconnectButton = findViewById(R.id.bluetooth_keyboard_disconnect)
        connectButton = findViewById(R.id.bluetooth_keyboard_connect)
        refreshButton = findViewById(R.id.bluetooth_keyboard_refresh)
        discoverButton = findViewById(R.id.bluetooth_keyboard_discoverable)
        sendButton = findViewById(R.id.bluetooth_keyboard_send)
        clearButton = findViewById(R.id.bluetooth_keyboard_clear)
        controlButton = findViewById(R.id.bluetooth_keyboard_control_keys)
        showImeButton = findViewById(R.id.bluetooth_keyboard_show_ime)
        chooseImeButton = findViewById(R.id.bluetooth_keyboard_choose_ime)
        modifiers = listOf(
            findViewById<CheckBox>(R.id.bluetooth_keyboard_ctrl) to HidKeyboardReport.LEFT_CTRL,
            findViewById<CheckBox>(R.id.bluetooth_keyboard_alt) to HidKeyboardReport.LEFT_ALT,
            findViewById<CheckBox>(R.id.bluetooth_keyboard_shift) to HidKeyboardReport.LEFT_SHIFT,
            findViewById<CheckBox>(R.id.bluetooth_keyboard_command) to HidKeyboardReport.LEFT_GUI
        )
        markerToken = savedInstanceState?.getString(STATE_MARKER) ?: UUID.randomUUID().toString()
        editor.privateImeOptions = "$REMOTE_IME_MARKER$markerToken"
        editor.tag = TEST_TAG_EDITOR
        editor.mode = if (savedInstanceState?.getBoolean(STATE_IMMEDIATE) == true) {
            RemoteKeyboardEditText.Mode.IMMEDIATE
        } else RemoteKeyboardEditText.Mode.MANUAL
        immediate.isChecked = editor.mode == RemoteKeyboardEditText.Mode.IMMEDIATE
        editor.transmitText = ::transmitText
        editor.transmitKey = ::transmitControl
        editor.onSendingChanged = { updateControls() }
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(text: Editable?) { updateControls() }
        })
        immediate.setOnCheckedChangeListener { _, checked ->
            if (updatingMode) return@setOnCheckedChangeListener
            if (!editor.text.isNullOrEmpty() || editor.isSending) {
                updatingMode = true
                immediate.isChecked = editor.mode == RemoteKeyboardEditText.Mode.IMMEDIATE
                updatingMode = false
                showMessage(R.string.bluetooth_keyboard_switch_empty)
            } else {
                editor.mode = if (checked) RemoteKeyboardEditText.Mode.IMMEDIATE else RemoteKeyboardEditText.Mode.MANUAL
                updateModeHelp()
            }
        }
        findViewById<View>(R.id.bluetooth_keyboard_back).setOnClickListener { finish() }
        startButton.setOnClickListener { requestStart() }
        disconnectButton.setOnClickListener {
            if (!ownsInput()) return@setOnClickListener
            cancelSend()
            clearModifiers()
            session.stop()
        }
        connectButton.setOnClickListener {
            if (!ownsInput()) return@setOnClickListener
            hosts.getOrNull(hostPicker.selectedItemPosition)?.let { host ->
                feedback.text = ""
                session.connect(host)
            }
        }
        refreshButton.setOnClickListener { refreshHosts() }
        discoverButton.setOnClickListener { requestDiscoverable() }
        sendButton.setOnClickListener { editor.sendDraft() }
        clearButton.setOnClickListener {
            if (!editor.text.isNullOrEmpty() && !editor.isSending) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.bluetooth_keyboard_clear_title)
                    .setMessage(R.string.bluetooth_keyboard_clear_message)
                    .setNegativeButton(R.string.bluetooth_keyboard_cancel, null)
                    .setPositiveButton(R.string.bluetooth_keyboard_clear) { _, _ ->
                        if (!editor.isSending && pendingSend == null) editor.clearDraft()
                    }
                    .show()
            }
        }
        controlButton.setOnClickListener { showControlMenu() }
        showImeButton.setOnClickListener {
            editor.requestFocus()
            editor.post {
                if (ownsInput() && !editor.isSending) {
                    getSystemService(InputMethodManager::class.java)?.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
        chooseImeButton.setOnClickListener {
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
        }
        val root = findViewById<View>(R.id.bluetooth_keyboard_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        ViewCompat.requestApplyInsets(root)
        feedback.text = savedInstanceState?.getString(STATE_FEEDBACK).orEmpty()
        updateModeHelp()
        showHosts(emptyList())
        render(session.state)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        BluetoothKeyboardRuntime.observer = observer
        // A revoked permission cannot leave a restored screen displaying a usable connection.
        if (!hasConnectPermission() && session.state.phase !in listOf(Phase.IDLE, Phase.ERROR)) {
            session.stop()
            showMessage(R.string.bluetooth_keyboard_permission)
        }
        session.setInputEnabled(true)
        editor.resumeInput()
        render(session.state)
        if (session.state.phase == Phase.READY || session.state.phase == Phase.CONNECTED) refreshHosts()
        pendingAction?.let {
            pendingAction = null
            continueAction(it)
        }
    }

    override fun onPause() {
        val ownedSession = BluetoothKeyboardRuntime.observer === observer
        resumed = false
        cancelSend()
        editor.suspendInput()
        editor.inputAllowed = false
        clearModifiers()
        if (ownedSession) session.setInputEnabled(false)
        updateControls()
        super.onPause()
    }

    override fun onStop() {
        if (BluetoothKeyboardRuntime.observer === observer) BluetoothKeyboardRuntime.observer = null
        super.onStop()
    }

    override fun onDestroy() {
        pendingAction = null
        cancelSend()
        if (BluetoothKeyboardRuntime.observer === observer) BluetoothKeyboardRuntime.observer = null
        editor.transmitText = { _, complete -> complete(false) }
        editor.transmitKey = { _, _ -> false }
        editor.onSendingChanged = { }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_MARKER, markerToken)
        outState.putBoolean(STATE_IMMEDIATE, editor.mode == RemoteKeyboardEditText.Mode.IMMEDIATE)
        outState.putString(STATE_FEEDBACK, pendingSend?.let {
            getString(if (it.attempted) R.string.bluetooth_keyboard_send_interrupted else R.string.bluetooth_keyboard_send_cancelled)
        } ?: feedback.text.toString())
        super.onSaveInstanceState(outState)
    }

    private fun requestStart() {
        if (!ownsInput()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            showMessage(R.string.bluetooth_keyboard_unsupported)
            return
        }
        if (!hasConnectPermission()) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_CONNECT_PERMISSION)
            return
        }
        startWithPermission()
    }

    @SuppressLint("MissingPermission") // Explicit runtime check and catch cover revocation races.
    private fun startWithPermission() {
        if (!hasConnectPermission()) {
            showMessage(R.string.bluetooth_keyboard_permission)
            return
        }
        try {
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null) {
                showMessage(R.string.bluetooth_keyboard_unsupported)
            } else if (!adapter.isEnabled) {
                startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE)
            } else {
                feedback.text = ""
                session.start()
            }
        } catch (_: SecurityException) {
            showMessage(R.string.bluetooth_keyboard_permission)
        } catch (_: ActivityNotFoundException) {
            showMessage(R.string.bluetooth_keyboard_system_dialog_unavailable)
        } catch (_: RuntimeException) {
            showMessage(R.string.bluetooth_keyboard_service_unavailable)
        }
    }

    private fun requestDiscoverable() {
        if (!ownsInput() || session.state.phase != Phase.READY) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE), REQUEST_ADVERTISE_PERMISSION)
            return
        }
        openDiscoverableDialog()
    }

    @SuppressLint("MissingPermission") // Called after checking ADVERTISE; the system owns consent and pairing.
    private fun openDiscoverableDialog() {
        if (session.state.phase != Phase.READY) return
        try {
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
            }, REQUEST_DISCOVERABLE)
        } catch (_: SecurityException) {
            showMessage(R.string.bluetooth_keyboard_advertise_permission)
        } catch (_: ActivityNotFoundException) {
            showMessage(R.string.bluetooth_keyboard_system_dialog_unavailable)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_CONNECT_PERMISSION -> if (grantResults.isNotEmpty() && hasConnectPermission()) {
                continueAction(PendingAction.START)
            } else showMessage(R.string.bluetooth_keyboard_permission)
            REQUEST_ADVERTISE_PERMISSION -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                grantResults.isNotEmpty() && checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED) {
                continueAction(PendingAction.DISCOVERABLE)
            } else showMessage(R.string.bluetooth_keyboard_advertise_permission)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_ENABLE -> if (resultCode == RESULT_OK) continueAction(PendingAction.START) else showMessage(R.string.bluetooth_keyboard_off)
            REQUEST_DISCOVERABLE -> if (resultCode > 0) {
                feedback.text = resources.getQuantityString(R.plurals.bluetooth_keyboard_visible, resultCode, resultCode)
            } else showMessage(R.string.bluetooth_keyboard_discovery_cancelled)
        }
    }

    private fun hasConnectPermission() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun continueAction(action: PendingAction) {
        if (!ownsInput()) {
            pendingAction = action
            return
        }
        when (action) {
            PendingAction.START -> startWithPermission()
            PendingAction.DISCOVERABLE -> openDiscoverableDialog()
        }
    }

    private fun render(state: State) {
        val job = pendingSend
        if (job != null && (state.phase != Phase.CONNECTED || state.host?.address != job.hostAddress)) cancelSend()
        status.text = when (state.phase) {
            Phase.IDLE -> getString(if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) R.string.bluetooth_keyboard_unsupported else R.string.bluetooth_keyboard_idle)
            Phase.STARTING -> getString(R.string.bluetooth_keyboard_starting)
            Phase.READY -> getString(R.string.bluetooth_keyboard_ready)
            Phase.CONNECTING -> getString(R.string.bluetooth_keyboard_connecting, state.host?.name.orEmpty())
            Phase.CONNECTED -> getString(R.string.bluetooth_keyboard_connected, state.host?.name.orEmpty())
            Phase.ERROR -> getString(failureMessage(state.failure))
        }
        locks.visibility = if (state.phase == Phase.CONNECTED) View.VISIBLE else View.GONE
        fun lock(bit: Int) = getString(if (state.leds and bit != 0) R.string.bluetooth_keyboard_lock_on else R.string.bluetooth_keyboard_lock_off)
        locks.text = getString(R.string.bluetooth_keyboard_lock_status, lock(1), lock(2), lock(4))
        val phaseChanged = lastPhase != state.phase
        lastPhase = state.phase
        if (phaseChanged && (state.phase == Phase.READY || state.phase == Phase.CONNECTED)) refreshHosts()
        if (state.phase == Phase.IDLE || state.phase == Phase.ERROR) showHosts(emptyList())
        updateControls()
    }

    private fun refreshHosts() {
        if (session.state.phase != Phase.READY && session.state.phase != Phase.CONNECTED) return
        showHosts(session.bondedDevices())
        updateControls()
    }

    private fun showHosts(next: List<Host>) {
        val selectedAddress = hosts.getOrNull(hostPicker.selectedItemPosition)?.address
        hosts = next
        val labels = if (hosts.isEmpty()) listOf(getString(
            if (session.state.phase == Phase.IDLE) R.string.bluetooth_keyboard_choose_after_start else R.string.bluetooth_keyboard_no_devices
        )) else hosts.map { getString(R.string.bluetooth_keyboard_host_item, it.name, it.address) }
        hostPicker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        hosts.indexOfFirst { it.address == selectedAddress }.takeIf { it >= 0 }?.let { hostPicker.setSelection(it) }
    }

    private fun updateControls() {
        val state = session.state
        val busy = pendingSend != null || editor.isSending
        val connected = ownsInput() && state.phase == Phase.CONNECTED
        editor.inputAllowed = connected
        editor.isEnabled = connected
        startButton.isEnabled = ownsInput() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            (state.phase == Phase.IDLE || state.phase == Phase.ERROR)
        disconnectButton.isEnabled = ownsInput() && state.phase != Phase.IDLE
        val ready = ownsInput() && state.phase == Phase.READY
        hostPicker.isEnabled = ready && hosts.isNotEmpty()
        connectButton.isEnabled = ready && hosts.isNotEmpty()
        refreshButton.isEnabled = resumed && (state.phase == Phase.READY || state.phase == Phase.CONNECTED) && !busy
        discoverButton.isEnabled = ready
        sendButton.isEnabled = connected && !busy && !editor.text.isNullOrEmpty()
        clearButton.isEnabled = !busy && !editor.text.isNullOrEmpty()
        immediate.isEnabled = resumed && !busy
        controlButton.isEnabled = connected && !busy
        modifiers.forEach { (view, _) -> view.isEnabled = connected && !busy }
        showImeButton.isEnabled = connected && !busy
        chooseImeButton.isEnabled = resumed && !busy
    }

    private fun updateModeHelp() {
        modeHelp.setText(if (editor.mode == RemoteKeyboardEditText.Mode.IMMEDIATE) {
            R.string.bluetooth_keyboard_immediate_help
        } else R.string.bluetooth_keyboard_manual_help)
    }

    private fun transmitText(text: String, complete: (Boolean) -> Unit) {
        val modifierMask = takeModifiers()
        val state = session.state
        if (!ownsInput() || pendingSend != null || state.phase != Phase.CONNECTED || state.host == null) {
            showMessage(R.string.bluetooth_keyboard_input_unavailable)
            complete(false)
            return
        }
        if (text.length > MAX_TEXT_LENGTH) {
            showMessage(R.string.bluetooth_keyboard_too_long)
            complete(false)
            return
        }
        val strokes = HidTextEncoder.strokes(text, capsLock = state.leds and 2 != 0)
        if (strokes == null) {
            showMessage(R.string.bluetooth_keyboard_unsupported_text)
            complete(false)
            return
        }
        if (strokes.isEmpty()) {
            complete(true)
            return
        }
        val job = SendJob(++sendGeneration, state.host.address, strokes, modifierMask, complete)
        pendingSend = job
        feedback.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
        feedback.text = getString(R.string.bluetooth_keyboard_sending, 0, strokes.size)
        updateControls()
        scheduleStroke(job)
    }

    private fun scheduleStroke(job: SendJob) {
        val task = Runnable {
            if (pendingSend !== job || sendGeneration != job.generation) return@Runnable
            val state = session.state
            if (!ownsInput() || state.phase != Phase.CONNECTED || state.host?.address != job.hostAddress) {
                finishSend(job, false)
                return@Runnable
            }
            val stroke = job.strokes[job.index]
            job.attempted = true
            val accepted = session.tap(stroke.usage, stroke.modifiers or job.modifiers)
            // A synchronous transport failure can already have cancelled this job through render().
            if (pendingSend !== job || sendGeneration != job.generation) return@Runnable
            if (!accepted) {
                finishSend(job, false)
                return@Runnable
            }
            ++job.index
            if (job.index % 8 == 0 || job.index == job.strokes.size) {
                feedback.text = getString(R.string.bluetooth_keyboard_sending, job.index, job.strokes.size)
            }
            if (job.index == job.strokes.size) finishSend(job, true) else scheduleStroke(job)
        }
        job.task = task
        handler.postDelayed(task, STROKE_INTERVAL_MS)
    }

    private fun finishSend(job: SendJob, success: Boolean) {
        if (pendingSend !== job) return
        pendingSend = null
        ++sendGeneration
        job.task?.let { handler.removeCallbacks(it) }
        feedback.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        showMessage(if (success) R.string.bluetooth_keyboard_sent else if (job.attempted) {
            R.string.bluetooth_keyboard_send_interrupted
        } else R.string.bluetooth_keyboard_send_cancelled)
        job.complete(success)
        updateControls()
    }

    private fun cancelSend() {
        pendingSend?.let { finishSend(it, false) }
    }

    private fun transmitControl(usage: Int, modifiers: Int): Boolean {
        val mask = modifiers or takeModifiers()
        if (!ownsInput() || pendingSend != null || session.state.phase != Phase.CONNECTED) {
            showMessage(R.string.bluetooth_keyboard_input_unavailable)
            return false
        }
        return session.tap(usage, mask).also {
            if (!it) showMessage(R.string.bluetooth_keyboard_send_failed)
        }
    }

    private fun showControlMenu() {
        val menu = PopupMenu(this, controlButton)
        val keys = listOf(
            R.string.bluetooth_keyboard_key_enter to 0x28,
            R.string.bluetooth_keyboard_key_tab to 0x2B,
            R.string.bluetooth_keyboard_key_escape to 0x29,
            R.string.bluetooth_keyboard_key_backspace to 0x2A,
            R.string.bluetooth_keyboard_key_delete to 0x4C,
            R.string.bluetooth_keyboard_key_left to 0x50,
            R.string.bluetooth_keyboard_key_right to 0x4F,
            R.string.bluetooth_keyboard_key_up to 0x52,
            R.string.bluetooth_keyboard_key_down to 0x51,
            R.string.bluetooth_keyboard_key_home to 0x4A,
            R.string.bluetooth_keyboard_key_end to 0x4D,
            R.string.bluetooth_keyboard_key_page_up to 0x4B,
            R.string.bluetooth_keyboard_key_page_down to 0x4E
        )
        keys.forEachIndexed { index, (label, _) -> menu.menu.add(0, index, index, label) }
        menu.setOnMenuItemClickListener { item ->
            keys.getOrNull(item.itemId)?.let { transmitControl(it.second, 0) }
            true
        }
        menu.show()
    }

    private fun takeModifiers(): Int = modifiers.fold(0) { mask, (view, bit) ->
        mask or if (view.isChecked) bit else 0
    }.also { clearModifiers() }

    private fun clearModifiers() { modifiers.forEach { (view, _) -> view.isChecked = false } }
    private fun ownsInput() = resumed && BluetoothKeyboardRuntime.observer === observer
    private fun showMessage(message: Int) { feedback.setText(message) }

    private fun failureMessage(failure: Failure?) = when (failure) {
        Failure.UNSUPPORTED -> R.string.bluetooth_keyboard_unsupported
        Failure.PERMISSION -> R.string.bluetooth_keyboard_permission
        Failure.BLUETOOTH_OFF -> R.string.bluetooth_keyboard_off
        Failure.REGISTRATION_FAILED -> R.string.bluetooth_keyboard_registration_failed
        Failure.CONNECTION_FAILED -> R.string.bluetooth_keyboard_connection_failed
        Failure.SEND_FAILED -> R.string.bluetooth_keyboard_send_failed
        Failure.TIMEOUT -> R.string.bluetooth_keyboard_timeout
        Failure.SERVICE_UNAVAILABLE, null -> R.string.bluetooth_keyboard_service_unavailable
    }

    companion object {
        const val TEST_TAG_EDITOR = "bluetooth-remote-editor"
        const val REMOTE_IME_MARKER = "com.example.ipa_board.bluetooth_remote:"
        private const val MAX_TEXT_LENGTH = 256
        private const val STROKE_INTERVAL_MS = 16L
        private const val STATE_MARKER = "remote_ime_marker"
        private const val STATE_IMMEDIATE = "remote_immediate"
        private const val STATE_FEEDBACK = "remote_feedback"
        private const val REQUEST_CONNECT_PERMISSION = 3501
        private const val REQUEST_ADVERTISE_PERMISSION = 3502
        private const val REQUEST_ENABLE = 3503
        private const val REQUEST_DISCOVERABLE = 3504
    }
}
