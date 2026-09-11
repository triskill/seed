package com.seed.app.ui.shell

import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import com.termux.view.TerminalView

/**
 * Hosts Termux's final [TerminalView].
 *
 * [TerminalView] owns its renderer and draws itself as this FrameLayout's
 * child. Rendering it again from the parent duplicates every transcript row,
 * producing a visible text overlay each time the terminal is invalidated.
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
}
