package cc.uukanshu.ui.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
            val (pos, total, title) = when (val s = ui) {
                is ReaderViewModel.Ui.Content -> Triple(s.position, s.total, s.title)
                else -> Triple(ui.position, ui.total, "")
            }
            ReaderTopBar(
                book = vm.display(bookTitleRaw),
                line2 = if (total > 0) {
                    // Title already converted in VM — never display() twice.
                    // Loading/Error have no title: progress alone keeps height constant.
                    if (title.isEmpty()) "${pos} / ${total}" else ReaderHeader.line2(pos, total, title)
                } else "…",
                backLabel = vm.display("返回"),
                onBack = onBack,
            )
        },
        bottomBar = {
            ReaderBottomBar(
                ui = ui,
                display = vm::display,
                onSettings = { showSheet = true },
                onPrev = { vm.prev(); Unit },
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
                    position = s.position,
                    // Text already converted in the VM — never display() twice.
                    paragraphs = ReaderParagraphs.split(s.text),
                    fontScale = fontScale,
                )
            }
        }
    }

    if (showSheet) {
        // Fresh per open: hoisting reuses half-expanded state across opens.
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
                onSetFontScale = vm::setFontScale,
                onSetTheme = vm::setTheme,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderTopBar(book: String, line2: String, backLabel: String, onBack: () -> Unit) {
    // Static divider under the bar (replaces the one lost with the sticky
    // header; mirrors the bottom bar). Not scroll-linked: hoisting scroll
    // state for elevation costs recomposes for zero gain on a static bar.
    Column {
    TopAppBar(
        // Outer NavHost padding already carries status height on this route;
        // M3 default would apply it twice. Detail has no bar and needs it.
        windowInsets = WindowInsets(0),
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
                Text(
                    line2,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        },
    )
        HorizontalDivider()
    }
}

@Composable
private fun ReaderContent(
    bookId: String,
    // Position keys scroll-reset only (header is gone): same chapter keeps
    // scroll, new chapter jumps to top. Never display it here — TopBar owns it.
    position: Int,
    paragraphs: List<String>,
    fontScale: Float,
) {
    val scroll = rememberScrollState()
    // Paging chapters reuses this composition: jump to top on chapter change.
    LaunchedEffect(bookId, position) { runCatching { scroll.scrollTo(0) } }
    SelectionContainer {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 8.dp),
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
    // Match TopBar: surface + single divider (no tonal slab), tonal nav
    // keeps focus on text. Settings in IconButton (left, out of thumb way),
    // prev/next dominate the thumb zone.
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Column {
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onSettings) {
                    Icon(Icons.Filled.Tune, contentDescription = display("閱讀設定"))
                }
                FilledTonalButton(
                    onClick = onPrev,
                    enabled = !ui.isLoading && ui.position > 1,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(display("上一章"))
                }
                FilledTonalButton(
                    onClick = onNext,
                    enabled = !ui.isLoading,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(display("下一章"))
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                }
            }
        }
    }
}

/**
 * Settings sheet: reader-optimized variants of the same Prefs/VM source used
 * by SettingsScreen (same keys, same VM). Widgets differ on purpose for
 * in-reading use: segmented language (both options visible), slider + stepper
 * with WYSIWYG preview, tonal theme tiles in one row. All labels via
 * display() so 簡體 mode converts. Dumb: all state lives in the VM.
 *
 * Visual contract (matches ReaderTopBar/BottomBar): surface cards, 16dp/8dp
 * rhythm, titleMedium sheet title, titleSmall + onSurfaceVariant section
 * headers, dynamic colorScheme tokens only (no hardcoded day/night colors).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSettingsSheet(
    simplified: Boolean,
    fontScale: Float,
    theme: String,
    display: (String) -> String,
    onSetSimplified: (Boolean) -> Unit,
    onSetFontScale: (Float) -> Unit,
    onSetTheme: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(display("閱讀設定"), style = MaterialTheme.typography.titleMedium)
        // Language: segmented so both options stay visible (a Switch label
        // would flip with state and hide what OFF means).
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                display("語言"),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Card(Modifier.fillMaxWidth()) {
                SingleChoiceSegmentedButtonRow(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    SegmentedButton(
                        selected = !simplified,
                        onClick = { onSetSimplified(false) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        label = { Text(display("繁體")) },
                    )
                    SegmentedButton(
                        selected = simplified,
                        onClick = { onSetSimplified(true) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        label = { Text(display("簡體")) },
                    )
                }
            }
        }
        // Font size: stepper + slider share one absolute setter (VM coerces),
        // bound-disable at ends (not silent clamp) + live preview using the
        // same 17/28sp formula as ReaderContent.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                display("文字大小"),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = { onSetFontScale(fontScale - 0.1f) },
                            enabled = fontScale > Prefs.FONT_MIN,
                        ) {
                            Icon(Icons.Filled.Remove, contentDescription = display("減小字號"))
                        }
                        Slider(
                            value = fontScale,
                            onValueChange = onSetFontScale,
                            valueRange = Prefs.FONT_MIN..Prefs.FONT_MAX,
                            steps = 7,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { onSetFontScale(fontScale + 0.1f) },
                            enabled = fontScale < Prefs.FONT_MAX,
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = display("增大字號"))
                        }
                    }
                    Text(
                        "${(fontScale * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        display("永夜微涼，燈火未熄。"),
                        fontSize = (17 * fontScale).sp,
                        lineHeight = (28 * fontScale).sp,
                    )
                }
            }
        }
        // Appearance: one row of tonal tiles (same 12dp rhythm as the bottom
        // bar actions). Tonal surfaces follow dynamic color + dark mode;
        // hardcoded white/black previews would lie under Material You.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                display("外觀"),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val options = listOf(
                        Triple(Prefs.SYSTEM, "自動", "跟隨系統"),
                        Triple(Prefs.LIGHT, "淺色", null),
                        Triple(Prefs.DARK, "深色", null),
                    )
                    options.forEach { (value, name, sub) ->
                        val selected = theme == value
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier.weight(1f)
                                .selectable(
                                    selected = selected,
                                    onClick = { onSetTheme(value) },
                                    role = Role.RadioButton,
                                ),
                        ) {
                            Column(
                                Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text("Aa", style = MaterialTheme.typography.titleLarge)
                                    if (selected) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                                Text(display(name), style = MaterialTheme.typography.bodyLarge)
                                if (sub != null) {
                                    Text(display(sub), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
