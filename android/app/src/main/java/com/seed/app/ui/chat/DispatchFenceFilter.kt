package com.seed.app.ui.chat

/** Filters only line-start fenced JSON dispatches, independent of text_delta boundaries. */
internal class DispatchFenceFilter {
    private var hidden = false
    private var lineStart = true
    private var afterDelimiter = false
    private val candidate = StringBuilder()

    data class Result(val visible: String, val debug: String)

    fun accept(chunk: String): Result {
        val visible = StringBuilder()
        val debug = StringBuilder()
        fun record(text: String) {
            if (debug.length < 512) debug.append(text.take(512 - debug.length))
        }
        for (ch in chunk) {
            if (afterDelimiter) {
                afterDelimiter = false
                if (ch == '\n') {
                    lineStart = true
                    continue
                }
            }
            val delimiter = if (hidden) "```" else "```json"
            if (candidate.isNotEmpty() || (lineStart && ch == '`')) {
                candidate.append(ch)
                val value = candidate.toString()
                if (value == delimiter) {
                    record(value)
                    candidate.clear()
                    hidden = !hidden
                    afterDelimiter = true
                    lineStart = false
                    continue
                }
                if (delimiter.startsWith(value)) continue
                val rejected = candidate.toString()
                candidate.clear()
                if (hidden) record(rejected) else visible.append(rejected)
                lineStart = rejected.last() == '\n'
                continue
            }
            if (hidden) record(ch.toString()) else visible.append(ch)
            lineStart = ch == '\n'
        }
        return Result(visible.toString(), debug.toString())
    }
}
