package com.seed.app.data

import com.seed.app.ui.settings.SettingsForm

/**
 * Persistence boundary for the Settings tab.
 *
 * Phase 5.7 introduces this interface so the
 * [com.seed.app.ui.settings.SettingsViewModel]
 * doesn't have to know whether the form is held in
 * memory or in a `DataStore`. The implementations are:
 *
 *   - [AndroidSettingsRepo] — the real one, backed
 *     by `DataStore-Preferences` for app preferences.
 *     Legacy encrypted credentials are read only for migration.
 *   - [SettingsRepo.InMemory] — a stateless no-op
 *     for tests, Compose previews, and the
 *     no-arg-constructor convenience overload of
 *     the ViewModel.
 *
 * **Contract:**
 *   - [load] returns the most recently saved
 *     [SettingsForm], or `null` if nothing has ever
 *     been saved. (A fresh install returns `null`.)
 *   - [save] persists non-secret fields only; credentials are stored
 *     by the backend, not in Android preferences.
 *
 * A `Flow<SettingsForm>`-based design was
 * considered and rejected: the ViewModel only needs
 * a one-shot hydration on init and a write on save.
 * A continuous stream would either (a) re-emit on
 * every save, fighting the in-memory form, or (b)
 * need a sentinel for "no persisted form yet",
 * complicating the type. Suspend functions let the
 * `DataStore` impl do its async IO cleanly and the
 * `InMemory` impl stay trivially sync.
 */
interface SettingsRepo {
    /**
     * Read the persisted form, or `null` on a fresh
     * install (nothing has ever been saved).
     *
     * Suspends to support async I/O on the
     * DataStore-backed impl; the [InMemory] impl
     * returns synchronously.
     */
    suspend fun load(): SettingsForm?

    /**
     * Persist non-secret fields from [form]. A legacy credential remains
     * untouched until [clearLegacyCredential] follows a successful backend save.
     */
    suspend fun save(form: SettingsForm)

    /** Remove the obsolete Android credential after successful backend import. */
    suspend fun clearLegacyCredential() = Unit

    companion object {
        /**
         * Stateless no-op [SettingsRepo] for tests,
         * Compose previews, and the
         * no-arg-constructor convenience overload of
         * [com.seed.app.ui.settings.SettingsViewModel].
         *
         * `load()` always returns `null` (a fresh
         * install), and `save()` is a no-op — the
         * form does NOT round-trip through the
         * in-memory store. The ViewModel owns the
         * in-memory form; this repo is just a sink
         * for the persistence call.
         *
         * The [SettingsViewModel] tests
         * (`SettingsViewModelTest.inMemoryRepoBehavesLikePhase56`)
         * pin this contract.
         */
        val InMemory: SettingsRepo = object : SettingsRepo {
            override suspend fun load(): SettingsForm? = null

            override suspend fun save(form: SettingsForm) = Unit
        }
    }
}
