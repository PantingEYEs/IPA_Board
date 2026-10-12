### 🚀 Issue Resolved & Next Steps

The candidate disappearance bug has been successfully resolved and verified in commit [`79500cf`](https://github.com/PantingEYEs/IPA_Board/commit/79500cf60108be243227dad1e0d0ec3798893697).

---

#### 🛠️ Fix Summary (`79500cf`)
- **Root Cause**: When the keyboard was reopened in the same input field, Android invoked `onStartInputView(editorInfo, restarting = true)` without re-invoking `onStartInput()`. Because `composition.start()` was previously only called inside `onStartInput()`, `CompositionController.active` remained `false` after `onFinishInputView()`, causing candidate responses from all engines to be rejected by `acceptResults()`.
- **Resolution**: Invoked `composition.start()` inside `IpaBoardService.onStartInputView()`, ensuring `CompositionController` is re-activated whenever the input view becomes active or restarts. On-device testing verified that candidate suggestions continue to display normally upon reopening the keyboard.

---

#### 📊 Planned Logging & Diagnostics Enhancements
Before opening the Pull Request for this issue, we are enhancing our internal logging and diagnostics subsystem to prevent similar lifecycle edge cases in the future:

1. **Log Management in Engine Settings**:
   - Display the internal log file size directly in **Engine Management**.
   - Allow users to export and clear the diagnostic log file on demand for troubleshooting.

2. **IME Lifecycle Event Monitoring**:
   - Add diagnostic instrumentation across core IME lifecycle callbacks (`onStartInput`, `onStartInputView`, `onFinishInputView`, `onFinishInput`).
   - Monitor composition state transitions and input session restarts to catch any lifecycle anomalies early across different Android OEMs and OS versions.

---

#### 🔄 PR Status
Once the diagnostic logging and export capabilities are completed on this branch, the full Pull Request will be submitted.
