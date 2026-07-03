package com.seed.app.data

import com.seed.app.ui.settings.SettingsForm
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Unit tests for [SettingsRepo].
 *
 * Phase 5.7 adds the persistence layer. The repo
 * abstraction has two implementations:
 *
 *   - [SettingsRepo.InMemory] — a stateless no-op for
 *     tests, previews, and the in-memory ViewModel
 *     fallback.
 *   - `AndroidSettingsRepo` — the production
 *     DataStore-Preferences + EncryptedSharedPreferences
 *     implementation, which depends on the Android
 *     framework and is not unit-testable on the JVM.
 *
 * The contract is: `load()` returns the persisted
 * form, or `null` if nothing has ever been saved;
 * `save(form)` persists it. The `InMemory` impl is
 * stateless — `load()` always returns `null` and
 * `save()` is a no-op. The production impl is
 * responsible for round-tripping the form through
 * disk. (See `SettingsViewModelTest` for the
 * end-to-end ViewModel ↔ repo contract tests, which
 * use a [RecordingSettingsRepo] to verify the
 * ViewModel calls `load` and `save` correctly.)
 */
class SettingsRepoTest {

    /**
     * The `InMemory` companion is a singleton — two
     * references must point at the same object so
     * tests and previews can rely on shared identity.
     */
    @Test
    fun inMemoryIsAStableSingleton() {
        assertSame(SettingsRepo.InMemory, SettingsRepo.InMemory)
    }

    /**
     * A fresh install has no persisted form, so
     * `load()` returns `null`. The `InMemory` impl
     * never persists anything, so it always behaves
     * like a fresh install.
     */
    @Test
    fun inMemoryLoadReturnsNull() = runTest {
        assertNull(SettingsRepo.InMemory.load())
    }

    /**
     * `save()` on the `InMemory` impl is a no-op —
     * it does not mutate the in-memory state. The
     * `InMemory` repo is meant to back the
     * ViewModel's in-memory form fallback (where
     * the ViewModel itself owns the form and the
     * repo is just a sink). A subsequent `load()`
     * still returns `null`, confirming the no-op
     * semantics.
     */
    @Test
    fun inMemorySaveIsANoOp() = runTest {
        val repo = SettingsRepo.InMemory
        repo.save(
            SettingsForm(
                provider = "anthropic",
                model = "claude-sonnet-4-5",
                apiKey = "sk-test",
            ),
        )
        assertNull(repo.load())
    }
}
