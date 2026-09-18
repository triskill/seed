package com.seed.app.ui.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Smoke test for SettingsScreen action surfaces.
 *
 * Pins the smallest meaningful contract: the Login and Save buttons exist on
 * the screen with their stable `testTag`s, so existing instrumented UI tests
 * (and any future Compose rule-based tests) can locate them.
 *
 * The plan deferred full Compose UI testing (login round-trip, save validate,
 * retry visibility, provider dropdown input) to technical debt because each
 * requires either a fake BackendApi/catalog, a Compose test rule (which needs
 * Robolectric on the JVM, not present in this module), or a refactor of the
 * VM's private state setters. See `docs/plans/2026-09-16-finish-model-selection.md`
 * Task 4 for the deferred scope.
 *
 * This is a structural / source-presence check, not a behaviour check. It
 * prevents accidental removal of the tags that the instrumented tests depend
 * on, but does not exercise any Compose runtime.
 */
class SettingsScreenTest {

    private fun settingsScreenSource(): String {
        // `gradlew :app:testDebugUnitTest` runs the test JVM with the module
        // directory (`android/app/`) as the working directory, but the
        // current Gradle version or a test runner wrapper could move cwd to
        // the package directory or up to `android/`. Probe the candidates
        // in order so the test is robust across these layouts.
        val candidates = listOf(
            "src/main/java/com/seed/app/ui/settings/SettingsScreen.kt",
            "app/src/main/java/com/seed/app/ui/settings/SettingsScreen.kt",
            "../app/src/main/java/com/seed/app/ui/settings/SettingsScreen.kt",
        )
        for (path in candidates) {
            val f = File(path)
            if (f.exists()) return f.readText()
        }
        error("SettingsScreen.kt not found in: $candidates (cwd=${File(".").absolutePath})")
    }

    @Test
    fun settingsScreenSourceDeclaresLoginAndSaveTestTags() {
        val source = settingsScreenSource()
        assertTrue(
            "settings-login testTag missing from SettingsScreen.kt",
            source.contains("\"settings-login\""),
        )
        assertTrue(
            "settings-save testTag missing from SettingsScreen.kt",
            source.contains("\"settings-save\""),
        )
    }
}
