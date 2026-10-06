package cz.trety.seed.runtime

/**
 * The high-level app boot state observed by [MainActivity].
 *
 * - [NeedsExtraction] — `filesDir/linux/.version` is missing or
 *   doesn't match the asset version. UI should show a
 *   "Preparing runtime…" placeholder (we don't yet have extraction
 *   progress to render).
 * - [Extracting] — extraction is in progress; `progress` is the
 *   latest [ExtractionProgress] event from the flow.
 * - [Failed] — preparation failed; show a generic message and manual Retry.
 * - [Ready] — extraction succeeded (or wasn't needed) and the
 *   normal app UI (`SeedNav`) should be shown.
 */
sealed class BootState {
    data object NeedsExtraction : BootState()
    data class Extracting(val progress: ExtractionProgress) : BootState()
    data object Failed : BootState()
    data object Ready : BootState()
}
