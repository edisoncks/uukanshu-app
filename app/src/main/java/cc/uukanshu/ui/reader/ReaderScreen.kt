package cc.uukanshu.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.uukanshu.core.Display
import cc.uukanshu.data.convert.T2S
import cc.uukanshu.data.parse.Parser
import cc.uukanshu.data.prefs.Prefs
import cc.uukanshu.data.repo.BookRepo
import cc.uukanshu.app
import cc.uukanshu.core.Errors
import cc.uukanshu.ui.ThemeIconButton
import cc.uukanshu.ui.vmFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Composable
fun ReaderScreen(bookId: String, position: Int, pageId: Long = 0L) {
    val container = cc.uukanshu.di.LocalContainer.current
    // Keyed on bookId only: paging reuses this VM via load(), and the nav
    // graph holds at most one reader per book, so position/pageId are just
    // the initial load arguments, not an identity.
    val vm: ReaderViewModel = viewModel(
        key = "reader-$bookId",
        factory = vmFactory {
            ReaderViewModel(container.repo, container.t2s, container.prefs, bookId, position, pageId)
        },
    )
    val ui by vm.ui.collectAsState()
    val snacks = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        // Reading content flexes; single sticky bottom bar (Option C:
        // [⋯ | prev | next]) stays pinned so no duplicate nav row is needed.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val s = ui) {
                is ReaderViewModel.Ui.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is ReaderViewModel.Ui.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(s.message)
                        Button({ vm.load(s.position) }, Modifier.padding(top = 12.dp)) { Text(vm.display("重試")) }
                    }
                }
                is ReaderViewModel.Ui.Content -> {
                    val scroll = rememberScrollState()
                    // Paging chapters reuses this composition: jump to top.
                    LaunchedEffect(s.position) { runCatching { scroll.scrollTo(0) } }
                    Column(
                        Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
                    ) {
                        if (s.book.isNotEmpty()) Text(s.book, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${s.position}/${s.total} ${s.title}",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                        Text(s.text, fontSize = (17 * s.fontScale).sp, lineHeight = (28 * s.fontScale).sp)
                    }
                }
            }
            SnackbarHost(snacks, Modifier.align(Alignment.BottomCenter).padding(8.dp))
        }
        // Single sticky bottom nav: settings in ⋯ (left, out of thumb way),
        // prev/next dominate the thumb zone.
        Surface(tonalElevation = 3.dp) {
            Column {
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        TextButton(onClick = { menu = true }) {
                            Text("⋯", fontSize = 20.sp)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(if (ui.simplified) vm.display("簡體 ✓") else vm.display("繁體 ✓")) },
                                onClick = { vm.toggleSimplified(); menu = false },
                            )
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(vm.display("字體"))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { vm.font(-0.1f) }) { Text("A-") }
                                    TextButton(onClick = { vm.font(0.1f) }) { Text("A+") }
                                }
                            }
                            DropdownMenuItem(
                                text = { Text(vm.themeLabel()) },
                                trailingIcon = {
                                    ThemeIconButton(ui.theme, { vm.cycleTheme() }, vm::display)
                                },
                                onClick = { vm.cycleTheme() },
                            )
                        }
                    }
                    Button(
                        onClick = { vm.load(ui.position - 1) },
                        enabled = !ui.isLoading && ui.position > 1,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(vm.display("上一章"))
                    }
                    Button(
                        onClick = {
                            if (ui.position >= ui.total && ui.total > 0) {
                                scope.launch { snacks.showSnackbar("已是最新一章 / end of book") }
                            } else vm.load(ui.position + 1)
                        },
                        enabled = !ui.isLoading,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(vm.display("下一章"))
                    }
                }
            }
        }
    }
}
