package cc.uukanshu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cc.uukanshu.ui.detail.DetailScreen
import cc.uukanshu.ui.home.HomeScreen
import cc.uukanshu.ui.search.SearchScreen
import cc.uukanshu.ui.LibraryScreen
import cc.uukanshu.ui.ReaderScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { UukanshuApp() }
    }
}

private data class Tab(val route: String, val label: String, val icon: @Composable () -> Unit)

@Composable
fun UukanshuApp() {
    MaterialTheme {
        val nav = rememberNavController()
        val tabs = listOf(
            Tab("home", "首頁") { Icon(Icons.Filled.Home, contentDescription = null) },
            Tab("search", "搜索") { Icon(Icons.Filled.Search, contentDescription = null) },
            Tab("library", "書架") { Icon(Icons.Filled.List, contentDescription = null) },
        )
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
                                onClick = { nav.navigate(tab.route) { launchSingleTop = true } },
                                icon = tab.icon,
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            },
        ) { inner ->
            NavHost(nav, startDestination = "home", Modifier.padding(inner)) {
                composable("home") { HomeScreen(onBook = { id -> nav.navigate("detail/$id") }) }
                composable("search") { SearchScreen(onBook = { id -> nav.navigate("detail/$id") }) }
                composable("library") { LibraryScreen() }
                composable(
                    "detail/{bookId}",
                    arguments = listOf(navArgument("bookId") { type = NavType.StringType }),
                ) { entry ->
                    DetailScreen(
                        bookId = entry.arguments?.getString("bookId").orEmpty(),
                        onChapter = { id, pos -> nav.navigate("reader/$id/$pos") },
                    )
                }
                composable(
                    "reader/{bookId}/{position}",
                    arguments = listOf(
                        navArgument("bookId") { type = NavType.StringType },
                        navArgument("position") { type = NavType.IntType },
                    ),
                ) { ReaderScreen() }
            }
        }
    }
}
