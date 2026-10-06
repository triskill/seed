package cz.trety.seed.ui.app

/** Saved history must be wholly navigable, not merely its current entry. */
internal object BrowsingRestorePolicy {
    fun isTrustedHistory(urls: List<String?>, currentIndex: Int): Boolean =
        currentIndex in urls.indices && urls.all { WebViewConfig.isAllowedUrl(it) }
}
