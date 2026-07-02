package com.seed.app.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.seed.app.R
import com.seed.app.ui.app.AppScreen
import com.seed.app.ui.chat.ChatScreen
import com.seed.app.ui.settings.SettingsScreen
import com.seed.app.ui.shell.ShellScreen

/**
 * Top-level navigation for Seed v0.1.
 *
 * Phase 5.2 ships the minimum: a `NavHost` with four
 * destinations (App / Chat / Shell / Settings) and a
 * Material 3 `NavigationBar` at the bottom. Each screen
 * is a placeholder — 5.3 (App/WebView), 5.4 (Chat),
 * 5.5 (Shell), and 5.6 (Settings) replace them with the
 * real composables in their own tasks.
 *
 * Why a single `NavHost` in a `Scaffold` (not a per-screen
 * `Scaffold`): the bottom bar is shared chrome, so it has
 * to live above the `NavHost` so all four screens can
 * switch the bar's selected state in one place. The
 * `NavHost` body gets the `innerPadding` so each screen
 * doesn't draw under the bar.
 *
 * The bottom-bar tap behaviour follows the standard
 * Compose Navigation idiom: pop up to the start
 * destination, save/restore state, and use
 * `launchSingleTop` so re-tapping a tab doesn't stack
 * copies. This is what users expect from Android's
 * bottom-nav pattern.
 */
@Composable
fun SeedNav() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tabs.forEach { tab ->
                    val selected = currentRoute == tab.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            // No-op if already on this tab —
                            // avoids an extra back-stack
                            // entry from `navigate`.
                            if (!selected) {
                                navController.navigate(tab.route) {
                                    popUpTo(
                                        navController.graph.findStartDestination().id,
                                    ) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.APP,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.APP) { AppScreen() }
            composable(Routes.CHAT) { ChatScreen() }
            composable(Routes.SHELL) { ShellScreen() }
            composable(Routes.SETTINGS) { SettingsScreen() }
        }
    }
}

/**
 * Route IDs. Kept as plain strings (not a sealed class)
 * because the navigation graph only references them as
 * map keys; a sealed class would add ceremony without
 * any extra type-safety benefit at this scale.
 */
private object Routes {
    const val APP = "app"
    const val CHAT = "chat"
    const val SHELL = "shell"
    const val SETTINGS = "settings"
}

/**
 * Bottom-bar tab metadata. The list order is the visual
 * order, left to right. `labelRes` resolves to a
 * localised string at render time (so the bar adapts to
 * the system locale once we ship translations).
 */
private data class TabSpec(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
)

private val Tabs = listOf(
    TabSpec(Routes.APP, R.string.tab_app, Icons.Filled.Apps),
    TabSpec(Routes.CHAT, R.string.tab_chat, Icons.AutoMirrored.Filled.Chat),
    TabSpec(Routes.SHELL, R.string.tab_shell, Icons.Filled.Terminal),
    TabSpec(Routes.SETTINGS, R.string.tab_settings, Icons.Filled.Settings),
)
