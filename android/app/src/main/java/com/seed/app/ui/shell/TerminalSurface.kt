package com.seed.app.ui.shell

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import com.termux.view.TerminalView

/**
 * Hosts Termux's final [TerminalView] and explicitly renders its emulator.
 *
 * Compose's AndroidView host can skip the embedded view's protected `onDraw`,
 * leaving an active PTY behind an empty surface. Drawing the public Termux
 * renderer from this container keeps input on the child while ensuring each
 * terminal invalidation paints the transcript.
 */
internal class TerminalSurface(context: Context) : FrameLayout(context) {
    val terminalView = TerminalView(context, null).apply {
        setWillNotDraw(false)
        // TerminalView expects physical pixels, not sp. Match Termux's 12dp
        // default so text remains readable on high-density devices.
        setTextSize((12f * context.resources.displayMetrics.density).toInt())
        isFocusable = true
        isFocusableInTouchMode = true
    }

    init {
        setBackgroundColor(Color.BLACK)
        setWillNotDraw(false)
        addView(
            terminalView,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    /** Focus the terminal and request the same soft keyboard used by text inputs. */
    fun requestKeyboard() {
        terminalView.requestFocus()
        terminalView.post {
            val inputMethodManager = context.getSystemService(
                Context.INPUT_METHOD_SERVICE,
            ) as InputMethodManager
            inputMethodManager.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** Send a non-text terminal key (for example an arrow or Escape) and retain IME focus. */
    fun sendKey(keyCode: Int) {
        terminalView.requestFocus()
        terminalView.handleKeyCode(keyCode, 0)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)

        val emulator = terminalView.mEmulator ?: return
        canvas.save()
        canvas.translate(terminalView.left.toFloat(), terminalView.top.toFloat())
        terminalView.mRenderer.render(
            emulator,
            canvas,
            terminalView.topRow,
            -1,
            -1,
            -1,
            -1,
        )
        canvas.restore()
    }
}
