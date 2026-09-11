package com.seed.app.runtime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalViewClient
import android.view.KeyEvent
import android.view.MotionEvent
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bridges Termux's [TerminalSessionClient] and [TerminalViewClient]
 * callback interfaces to Android system facilities.
 *
 * **Lifecycle invariant:** the client must NOT hold a strong reference
 * to any [View] that could retain a destroyed activity context.
 * The [TerminalView] is attached/detached through
 * [SeedTerminalManager.attachView] / [detachView], so we avoid
 * callback-driven retention entirely.
 *
 * **Clipboard.** Read/writes the system clipboard via
 * [Context.CLIPBOARD_SERVICE]. Terminal selection copies;
 * [TerminalViewClient.onPasteTextFromClipboard] writes it back
 * through [TerminalSession.write].
 *
 * **Modifier keys.** Termux supports sticky Ctrl/Alt/Shift/Fn
 * keys for composing escape sequences. These callbacks let the
 * shell interpret them correctly — currently all return `false`
 * so modifier keys behave like their non-sticky form.
 * A future extra-keys bar can set these to `true`.
 *
 * **Char-based input.** `shouldEnforceCharBasedInput()` returns `true`
 * so that [TerminalSession.write] validates input as Unicode
 * characters rather than raw bytes — this fixes IME input that
 * would otherwise be sent as the wrong encoding.
 */
class SeedTerminalClient(
    private val context: Context,
) : TerminalSessionClient, TerminalViewClient {

    // -- View reference (weak) --

    /**
     * Weak reference to the currently-attached [TerminalView].
     * Used by [onTextChanged] to post screen updates back to the view.
     * Cleared by [detachView] to break the reference cycle.
     */
    private var terminalView: WeakReference<com.termux.view.TerminalView> =
        WeakReference(null)

    /** One-shot Ctrl state exposed to the Compose extra-keys row. */
    private val _controlKeyActive = MutableStateFlow(false)
    val controlKeyActive: StateFlow<Boolean> = _controlKeyActive.asStateFlow()

    /**
     * Called by [SeedTerminalManager] when a new view is attached.
     * Stores a weak reference so [onTextChanged] can post UI updates
     * without retaining a strong reference that could leak the
     * enclosing activity context.
     */
    fun attachView(view: com.termux.view.TerminalView) {
        terminalView = WeakReference(view)
    }

    /**
     * Called by [SeedTerminalManager] when a view is detached.
     * Clears the weak reference so stale callbacks no longer
     * attempt to update a destroyed view.
     */
    fun detachView(view: com.termux.view.TerminalView) {
        if (terminalView.get() === view) {
            terminalView.clear()
        }
    }

    // -- TerminalSessionClient callbacks --

    override fun onTextChanged(changedSession: TerminalSession) {
        // Inform the TerminalView that its screen content has changed,
        // triggering invalidation and scroll-update logic.
        terminalView.get()?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        // No-op: we don't expose the session title
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        // No-op: the manager will be notified separately
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip ?: return
        val item = clip.getItemAt(0) ?: return
        val text = item.coerceToText(context).toString()
        session.write(text)
    }

    override fun onBell(session: TerminalSession) {
        // No-op: no audible notification needed
    }

    override fun onColorsChanged(session: TerminalSession) {
        // No-op: color changes are handled internally by the view
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
        // No-op: cursor state handled internally
    }

    override fun getTerminalCursorStyle(): Int = 0

    // Logging stubs (required by both TerminalSessionClient and TerminalViewClient)
    // Kotlin's Unit return type satisfies both (Void in Java)
    override fun logError(tag: String, message: String) {}
    override fun logWarn(tag: String, message: String) {}
    override fun logInfo(tag: String, message: String) {}
    override fun logDebug(tag: String, message: String) {}
    override fun logVerbose(tag: String, message: String) {}
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
    override fun logStackTrace(tag: String, e: Exception) {}

    // -- TerminalViewClient callbacks --

    // -- Gesture/Scale handling --
    override fun onScale(scale: Float): Float = 1.0f
    override fun onSingleTapUp(e: android.view.MotionEvent) {
        // No-op: handled by the view
    }

    // -- Copy mode --
    override fun copyModeChanged(copyMode: Boolean) {
        // No-op: copy mode handled internally
    }

    // -- Key events --
    override fun onKeyDown(keyCode: Int, e: android.view.KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: android.view.KeyEvent): Boolean = false
    override fun onLongPress(event: android.view.MotionEvent): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false

    // -- Emulator setup --
    override fun onEmulatorSet() {
        // No-op: the terminal emulator is set up internally
    }

    // -- Modifier keys (one-shot callbacks) --

    /**
     * Arm or disarm the next Ctrl-modified terminal key.
     *
     * Once Termux consumes this state while processing a character or key code,
     * it is cleared immediately so Ctrl behaves like a one-shot modifier rather
     * than a latched switch. A second tap before a key is entered still cancels
     * the armed modifier.
     */
    @Synchronized
    fun toggleControlKey(): Boolean {
        val active = !_controlKeyActive.value
        _controlKeyActive.value = active
        return active
    }

    @Synchronized
    private fun consumeControlKey(): Boolean {
        val active = _controlKeyActive.value
        if (active) _controlKeyActive.value = false
        return active
    }

    override fun readControlKey(): Boolean = consumeControlKey()
    override fun readAltKey(): Boolean = false
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    // -- TerminalView selection state --

    /**
     * Whether the TerminalView is currently selected by the user
     * (for gesture recognition: long-press starts selection).
     * Always returns true because the terminal tab owns exclusive
     * terminal interaction — no other view competes for gesture space.
     */
    override fun isTerminalViewSelected(): Boolean = true
}
