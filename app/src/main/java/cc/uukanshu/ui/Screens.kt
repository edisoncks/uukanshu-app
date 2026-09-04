package cc.uukanshu.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun PlaceholderScreen(label: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable fun HomeScreen() = PlaceholderScreen("首頁 / Home — recent + categories (milestone 3)")
@Composable fun SearchScreen() = PlaceholderScreen("搜索 / Search (milestone 4)")
@Composable fun LibraryScreen() = PlaceholderScreen("書架 / Library — cached novels (milestone 7)")
@Composable fun DetailScreen() = PlaceholderScreen("書籍詳情 / Detail + chapters (milestone 5)")
@Composable fun ReaderScreen() = PlaceholderScreen("閱讀 / Reader (milestone 6)")
