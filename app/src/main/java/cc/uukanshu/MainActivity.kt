package cc.uukanshu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cc.uukanshu.data.convert.T2S
import cc.uukanshu.data.prefs.Prefs
import cc.uukanshu.ui.detail.DetailScreen
import cc.uukanshu.ui.home.HomeScreen
import cc.uukanshu.ui.library.LibraryScreen
import cc.uukanshu.ui.search.SearchScreen
import cc.uukanshu.ui.reader.ReaderScreen
import cc.uukanshu.ui.settings.SettingsScreen
import cc.uukanshu.ui.update.UpdateDialog
import cc.uukanshu.ui.update.UpdateViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { UukanshuApp() }
    }
}

private data class Tab(val route: String, val label: String, val icon: @Composable () -> Unit)

@Composable
fun UukanshuApp() {
    val app = LocalContext.current.applicationContext as App
    val prefs = remember { Prefs(app) }
    val t2s = remember { T2S(app) }
    // Global display prefs: bottom nav + theme follow them live.
    val simplified by prefs.simplified.collectAsState(initial = false)
    val theme by prefs.theme.collectAsState(initial = Prefs.SYSTEM)
    fun display(raw: String): String = if (simplified) t2s.convert(raw) else raw
    // minSdk 31 guarantees dynamic color; plain schemes as fallback.
    val dark = when (theme) {
        Prefs.LIGHT -> false
        Prefs.DARK -> true
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    MaterialTheme(
        colorScheme = if (dark) {
            runCatching { dynamicDarkColorScheme(context) }.getOrElse { darkColorScheme() }
        } else {
            runCatching { dynamicLightColorScheme(context) }.getOrElse { lightColorScheme() }
        },
    ) {
        val nav = rememberNavController()
        val tabs = listOf(
            Tab("home", display("首頁")) { Icon(Icons.Filled.Home, contentDescription = null) },
            Tab("search", display("搜索")) { Icon(Icons.Filled.Search, contentDescription = null) },
            Tab("library", display("書架")) { Icon(Icons.Filled.List, contentDescription = null) },
            Tab("settings", display("設定")) { Icon(Icons.Filled.Settings, contentDescription = null) },
        )
        // Shared update state: auto-check fires once per day whatever tab is open;
        // the manual check button lives in Settings, the dialog overlays any tab.
        val updateVm: UpdateViewModel = viewModel(factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                UpdateViewModel(app, prefs) as T
        })
        val updateUi by updateVm.ui.collectAsState()
        LaunchedEffect(Unit) { updateVm.autoCheck() }
        Scaffold(
            bottomBar = {
                val entry by nav.currentBackStackEntryAsState()
                val route = entry?.destination?.route
                // Full-screen pages hide the bottom bar (Detail/Reader).
                if (route in tabs.map { it.route }) {
                    NavigationBar {
                        tabs.forEach { tab ->
                            NavigationBarItem(
                                selected = route == tab.route,
                                // Single-top + state restore: double-taps never stack
                                // duplicate tab destinations.
                                onClick = { nav.navigate(tab.route) {
                                    launchSingleTop = true
                                    popUpTo(nav.graph.startDestinationId) { saveState = true }
                                    restoreState = true
                                } },
                                icon = tab.icon,
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            },
        ) { inner ->
            NavHost(nav, startDestination = "home", Modifier.padding(inner)) {
                // launchSingleTop: rapid double-taps on the same book must
                // not push duplicate detail destinations.
                composable("home") { HomeScreen(onBook = { id -> nav.navigate("detail/$id") { launchSingleTop = true } }) }
                composable("search") { SearchScreen(onBook = { id -> nav.navigate("detail/$id") { launchSingleTop = true } }) }
                composable("library") { LibraryScreen(onBook = { id -> nav.navigate("detail/$id") { launchSingleTop = true } }) }
                composable("settings") { SettingsScreen(updateVm) }
                composable(
                    "detail/{bookId}",
                    arguments = listOf(navArgument("bookId") { type = NavType.StringType }),
                ) { entry ->
                    DetailScreen(
                        bookId = entry.arguments?.getString("bookId").orEmpty(),
                        // One reader per book: opening another chapter pops the
                        // previous reader above this detail (launchSingleTop
                        // alone only dedups the identical route, so ch.1 then
                        // ch.5 would stack). Paging within the reader reuses
                        // the ViewModel via load(), not navigation. The reader
                        // is only ever opened from here, so the popUpTo target
                        // always exists.
                        onChapter = { id, pos ->
                            nav.navigate("reader/$id/$pos") {
                                launchSingleTop = true
                                popUpTo("detail/$id") { inclusive = false }
                            }
                        },
                    )
                }
                composable(
                    "reader/{bookId}/{position}",
                    arguments = listOf(
                        navArgument("bookId") { type = NavType.StringType },
                        navArgument("position") { type = NavType.IntType },
                    ),
                ) { entry ->
                    ReaderScreen(
                        bookId = entry.arguments?.getString("bookId").orEmpty(),
                        position = entry.arguments?.getInt("position") ?: 1,
                    )
                }
            }
            UpdateDialog(
                ui = updateUi,
                display = ::display,
                onDownload = { updateVm.startDownload() },
                onInstall = { updateVm.install() },
                onCancelDownload = { updateVm.cancelDownload() },
                onSkip = { updateVm.skipVersion() },
                onDismiss = { updateVm.dismiss() },
                onRetry = { updateVm.manualCheck() },
                onBrowser = { updateVm.openInBrowser() },
                onOpenUnknownSources = { updateVm.openUnknownSources() },
            )
        }
    }
}
