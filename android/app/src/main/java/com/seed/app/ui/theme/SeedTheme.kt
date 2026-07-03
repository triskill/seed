package com.seed.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// --- Colour tokens ----------------------------------------------------------
//
// Mirrors `res/values/colors.xml` (which is used by
// the platform-level `Theme.Seed` style for the
// splash background and the launcher icon).
// Keeping the values in sync here and in the XML
// means the platform splash, the launcher icon, and
// the Compose surface all use the same green; the
// Compose values are the source of truth for the
// in-app palette, and the XML ones are the source
// of truth for the launcher icon / window flash.

/** Material Green 800 — light-theme primary. */
private val SeedGreen = Color(0xFF2E7D32)

/** Material Green 200 — light-theme primaryContainer; dark-theme primary. */
private val SeedGreenContainer = Color(0xFFA5D6A7)

/** Material Green 900 — dark-theme primaryContainer. */
private val SeedGreenDark = Color(0xFF1B5E20)

/** Dark green text on the light container. */
private val SeedOnGreenContainer = Color(0xFF0B2E0E)

/**
 * Seed v0.1 app theme.
 *
 * Wraps [MaterialTheme] with:
 *   - a green primary palette (the "seed" colour
 *     from `res/values/colors.xml`), in both
 *     light and dark variants;
 *   - automatic light/dark switching based on the
 *     system setting;
 *   - a `SideEffect` that flips the status-bar and
 *     navigation-bar icon appearance to match the
 *     active scheme (so the system-bar icons stay
 *     legible on the translucent bars we get from
 *     `enableEdgeToEdge()`).
 *
 * **Why a custom scheme instead of the M3
 * defaults?** "Seed" is a self-improving app, and
 * a recognisable brand colour helps the user feel
 * they're driving it. The green is intentionally
 * warm and organic (Material Green 800/200) so the
 * UI doesn't read as another generic dev tool.
 *
 * **Why `SideEffect` and not `DisposableEffect`?**
 * the work is a one-shot write to the window's
 * insets controller; it doesn't own any resources
 * to release. `SideEffect` runs after every
 * successful recomposition, which is exactly when
 * we want the bar style to track the theme.
 *
 * **Why check `isInEditMode`?** the IDE's
 * `@Preview` composables run in a special host
 * that doesn't have a real `Activity.window` —
 * dereferencing it would NPE. Skipping the
 * `SideEffect` in preview mode keeps `@Preview`s
 * rendering.
 */
@Composable
fun SeedTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val insetsController = WindowCompat.getInsetsController(window, view)
            // Light status bar = dark icons (for
            // light backgrounds). When darkTheme is
            // true, the bars are translucent over a
            // dark surface, so we want LIGHT icons.
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}

/**
 * Light theme palette. The primary / onPrimary /
 * primaryContainer / onPrimaryContainer tokens are
 * the "seed green" family (matches
 * `res/values/colors.xml`). The other tokens
 * (secondary, tertiary, error, surface, etc.) are
 * left at the M3 light defaults, which Compose
 * derives from a baseline purple. We only override
 * the colours we care about for v0.1; the rest
 * stays default so the four screens don't all need
 * custom colour decisions.
 */
private val LightColors = lightColorScheme(
    primary = SeedGreen,
    onPrimary = Color.White,
    primaryContainer = SeedGreenContainer,
    onPrimaryContainer = SeedOnGreenContainer,
)

/**
 * Dark theme palette. M3 dark themes invert the
 * lightness relationship between `primary` and
 * `primaryContainer` (the container becomes the
 * "low-emphasis" surface, the primary becomes the
 * "accent" — see the M3 colour system spec).
 * "Seed green dark" is the dark-theme primary
 * container, and "seed green container" (a light
 * green) becomes the dark-theme primary so it has
 * enough contrast against the dark surface.
 */
private val DarkColors = darkColorScheme(
    primary = SeedGreenContainer,
    onPrimary = SeedOnGreenContainer,
    primaryContainer = SeedGreenDark,
    onPrimaryContainer = SeedGreenContainer,
)
