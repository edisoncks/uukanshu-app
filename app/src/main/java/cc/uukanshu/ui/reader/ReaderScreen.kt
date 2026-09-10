package cc.uukanshu.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.uukanshu.data.prefs.Prefs
import cc.uukanshu.ui.vmFactory
import kotlinx.coroutines.launch

/**
 * Reading screen: static Scaffold chrome (top bar + bottom bar), sticky
 * chapter header, selectable paragraphs. No tap-to-hide: it fights with
 * text selection long-press, and ships untested without Compose UI tests.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(bookId: String, position: Int, pageId: Long = 0L, onBack: () -> Unit) {
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
    val simplified by vm.simplified.collectAsState()
    val fontScale by vm.fontScale.collectAsState()
    val theme by vm.theme.collectAsState()
    val bookTitleRaw by vm.bookTitle.collectAsState()
    val snacks = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showSheet by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            ReaderTopBar(
                book = vm.display(bookTitleRaw),
                position = ui.position,
                total = ui.total,
                backLabel = vm.display("返回"),
                onBack = onBack,
            )
        },
        bottomBar = {
            ReaderBottomBar(
                ui = ui,
                display = vm::display,
                onSettings = { showSheet = true },
                onPrev = { vm.prev() },
                onNext = {
                    when (vm.next()) {
                        is ReaderViewModel.NextStep.AtEnd ->
                            scope.launch { snacks.showSnackbar(vm.display("已是最新一章")) }
                        is ReaderViewModel.NextStep.Started -> Unit
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snacks) },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            when (val s = ui) {
                is ReaderViewModel.Ui.Loading -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
                is ReaderViewModel.Ui.Error -> ReaderError(
                    message = vm.display(s.message),
                    retryLabel = vm.display("重試"),
                    backLabel = vm.display("回到目錄"),
                    isDeleted = s.kind == ReaderErrorKind.Deleted,
                    onRetry = { vm.load(s.position) },
                    onBack = onBack,
                )
                is ReaderViewModel.Ui.Content -> ReaderContent(
                    bookId = bookId,
                    // Content fields are already converted in the VM
                    // (render(raw, simplified)) — never display() twice.
                    position = s.position,
                    total = s.total,
                    title = s.title,
                    paragraphs = ReaderParagraphs.split(s.text),
                    fontScale = fontScale,
                )
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            ReaderSettingsSheet(
                simplified = simplified,
                fontScale = fontScale,
                theme = theme,
                display = vm::display,
                onSetSimplified = vm::setSimplified,
                onFont = vm::font,
                onSetTheme = vm::setTheme,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderTopBar(book: String, position: Int, total: Int, backLabel: String, onBack: () -> Unit) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backLabel)
            }
        },
        title = {
            Column {
                Text(
                    book.ifEmpty { "…" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
                if (total > 0) {
                    Text(
                        "$position / $total",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

@Composable
private fun ReaderContent(
    bookId: String,
    position: Int,
    total: Int,
    title: String,
    paragraphs: List<String>,
    fontScale: Float,
) {
    val scroll = rememberScrollState()
    // Paging chapters reuses this composition: jump to top on chapter change.
    LaunchedEffect(bookId, position) { runCatching { scroll.scrollTo(0) } }
    Column(Modifier.fillMaxSize()) {
        // Sticky header: stays put while body scrolls so position context
        // is never lost on long chapters.
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "$position/$total $title",
                style = MaterialTheme.typography.titleMedium,
            )
            HorizontalDivider(Modifier.padding(top = 8.dp))
        }
        SelectionContainer {
            Column(
                Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp),
            ) {
                paragraphs.forEach { p ->
                    Text(
                        p,
                        fontSize = (17 * fontScale).sp,
                        lineHeight = (28 * fontScale).sp,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderError(
    message: String,
    retryLabel: String,
    backLabel: String,
    isDeleted: Boolean,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message)
            if (isDeleted) {
                // -1 = stable pageId missed: retry loops on the same -1,
                // so offer back-to-detail instead.
                Button(onBack, Modifier.padding(top = 12.dp)) { Text(backLabel) }
            } else {
                Button(onRetry, Modifier.padding(top = 12.dp)) { Text(retryLabel) }
            }
        }
    }
}

@Composable
private fun ReaderBottomBar(
    ui: ReaderViewModel.Ui,
    display: (String) -> String,
    onSettings: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    // Single sticky bottom nav: settings in ⋯ (left, out of thumb way),
    // prev/next dominate the thumb zone.
    Surface(tonalElevation = 3.dp) {
        Column {
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onSettings) {
                    Text("⋯", fontSize = 20.sp)
                }
                Button(
                    onClick = onPrev,
                    enabled = !ui.isLoading && ui.position > 1,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(display("上一章"))
                }
                Button(
                    onClick = onNext,
                    enabled = !ui.isLoading,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(display("下一章"))
                }
            }
        }
    }
}

/**
 * Settings sheet: mirrors SettingsScreen rows (same selectable/radio/switch
 * density) so one setting never has two different widgets. All labels via
 * display() so 簡體 mode converts. Dumb: all state lives in the VM.
 */
@Composable
private fun ReaderSettingsSheet(
    simplified: Boolean,
    fontScale: Float,
    theme: String,
    display: (String) -> String,
    onSetSimplified: (Boolean) -> Unit,
    onFont: (Float) -> Unit,
    onSetTheme: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(display("閱讀設定"), style = MaterialTheme.typography.titleSmall)
        // Language: same Switch row as SettingsScreen language card.
        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    display(if (simplified) "簡體" else "繁體"),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Switch(
                    checked = simplified,
                    onCheckedChange = onSetSimplified,
                )
            }
        }
        // Font: stepper with bound-disable (not silent clamp) + live preview.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(display("字體"), style = MaterialTheme.typography.bodyLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { onFont(-0.1f) },
                            enabled = fontScale > Prefs.FONT_MIN,
                        ) { Text("A-") }
                        Text(
                            "${(fontScale * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(
                            onClick = { onFont(0.1f) },
                            enabled = fontScale < Prefs.FONT_MAX,
                        ) { Text("A+") }
                    }
                }
                Text(
                    display("永夜微涼，燈火未熄。"),
                    fontSize = (17 * fontScale).sp,
                    lineHeight = (28 * fontScale).sp,
                )
            }
        }
        // Theme: same 3-row selectable block as SettingsScreen appearance card.
        Card(Modifier.fillMaxWidth()) {
            Column {
                val options = listOf(
                    Prefs.SYSTEM to "自動",
                    Prefs.LIGHT to "淺色",
                    Prefs.DARK to "深色",
                )
                options.forEachIndexed { index, (value, name) ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(
                                selected = theme == value,
                                onClick = { onSetTheme(value) },
                                role = Role.RadioButton,
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = theme == value,
                            onClick = { onSetTheme(value) },
                        )
                        Text(
                            display(name),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    if (index < options.lastIndex) {
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
