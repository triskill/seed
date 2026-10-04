package com.seed.app.ui.app

import org.junit.Assert.*
import org.junit.Test

class BrowsingRestorePolicyTest {
    private fun historyAllowed(urls: List<String?>, index: Int): Boolean =
        BrowsingRestorePolicy.isTrustedHistory(urls, index)

    @Test fun preservesNestedQueryFragmentAndBackStack() {
        assertTrue(historyAllowed(listOf("http://127.0.0.1:7778/", "http://127.0.0.1:7778/nested?x=1#detail"), 1))
    }
    @Test fun rejectsAnyUntrustedHistoryEvenWhenCurrentIsSafe() {
        assertFalse(historyAllowed(listOf("https://evil.example/", "http://127.0.0.1:7778/nested"), 1))
        assertFalse(historyAllowed(listOf("http://127.0.0.1:7778/", "about:blank"), 0))
    }
    @Test fun rejectsMissingCurrentAndEmptyHistory() {
        assertFalse(historyAllowed(emptyList(), 0))
        assertFalse(historyAllowed(listOf(null), 0))
        assertFalse(historyAllowed(listOf("http://127.0.0.1:7778/"), -1))
        assertFalse(historyAllowed(listOf("http://127.0.0.1:7778/"), 1))
    }
}
