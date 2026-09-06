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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cc.uukanshu.core.Display
import cc.uukanshu.data.prefs.Prefs
import cc.uukanshu.ui.Routes
import cc.uukanshu.ui.navigateToBook
import cc.uukanshu.ui.navigateToChapter
import cc.uukanshu.ui.navigateToTab
import cc.uukanshu.ui.vmFactory
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
    val app = LocalContext.current.app()
    val container = remember(app) { cc.uukanshu.di.RealAppContainer(app) }
    val prefs = remember { container.prefs }
    val t2s = remember { container.t2s }
    // Global display prefs: bottom nav + theme follow them live.
    val simplified by prefs.simplified.collectAsState(initial = false)
    val theme by prefs.theme.collectAsState(initial = Prefs.SYSTEM)
    fun display(raw: String): String = Display.text(t2s, raw, simplified)
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
      CompositionLocalProvider(cc.uukanshu.di.LocalContainer provides container) {
        val nav = rememberNavController()
        val tabs = listOf(
            Tab(Routes.HOME, display("首頁")) { Icon(Icons.Filled.Home, contentDescription = null) },
            Tab(Routes.SEARCH, display("搜索")) { Icon(Icons.Filled.Search, contentDescription = null) },
            Tab(Routes.LIBRARY, display("書架")) { Icon(Icons.Filled.List, contentDescription = null) },
            Tab(Routes.SETTINGS, display("設定")) { Icon(Icons.Filled.Settings, contentDescription = null) },
        )
        // Shared update state: auto-check fires once per day whatever tab is open;
        // the manual check button lives in Settings, the dialog overlays any tab.
        val updateVm: UpdateViewModel = viewModel(factory = vmFactory {
            UpdateViewModel(app, prefs)
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
                                onClick = { nav.navigateToTab(tab.route) },
                                icon = tab.icon,
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            },
        ) { inner ->
            NavHost(nav, startDestination = Routes.HOME, Modifier.padding(inner)) {
                composable(Routes.HOME) { HomeScreen(onBook = { id -> nav.navigateToBook(id) }) }
                composable(Routes.SEARCH) { SearchScreen(onBook = { id -> nav.navigateToBook(id) }) }
                composable(Routes.LIBRARY) { LibraryScreen(onBook = { id -> nav.navigateToBook(id) }) }
                composable(Routes.SETTINGS) { SettingsScreen(updateVm) }
                composable(
                    Routes.DETAIL_PATTERN,
                    arguments = listOf(navArgument("bookId") { type = NavType.StringType }),
                ) { entry ->
                    DetailScreen(
                        bookId = entry.arguments?.getString("bookId").orEmpty(),
                        // Paging within the reader reuses the ViewModel via load(),
                        // not navigation (see Nav.kt for the dedup/pop rules).
                        onChapter = { id, pos, pageId -> nav.navigateToChapter(id, pos, pageId) },
                    )
                }
                composable(
                    Routes.READER_PATTERN,
                    arguments = listOf(
                        navArgument("bookId") { type = NavType.StringType },
                        navArgument("position") { type = NavType.IntType },
                        navArgument("pageId") { type = NavType.LongType; defaultValue = 0L },
                    ),
                ) { entry ->
                    ReaderScreen(
                        bookId = entry.arguments?.getString("bookId").orEmpty(),
                        position = entry.arguments?.getInt("position") ?: 1,
                        pageId = entry.arguments?.getLong("pageId") ?: 0L,
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
}
