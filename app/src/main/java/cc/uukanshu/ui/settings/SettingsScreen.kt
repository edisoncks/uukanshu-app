package cc.uukanshu.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cc.uukanshu.app
import cc.uukanshu.core.Display
import cc.uukanshu.data.prefs.Prefs
import cc.uukanshu.data.update.UpdateDownloader
import cc.uukanshu.ui.update.UpdateViewModel
import kotlinx.coroutines.launch

/**
 * Settings tab: theme, Traditional/Simplified, update check + version.
 * The three controls used to live in the Home top bar; they now live here
 * so the Home bar stays a plain title and the update flow is testable from
 * one place. Theme/simplified write to [Prefs] so Home/Search/Library/Reader
 * follow live; the update section drives the shared [UpdateViewModel].
 *
 * Layout: three cards (appearance / language / update) with identical row
 * density — 48dp rows, 16dp horizontal padding — so sections share one rhythm
 * instead of floating loose in a flat column.
 */
@Composable
fun SettingsScreen(updateVm: UpdateViewModel) {
    val ctx = LocalContext.current
    val app = ctx.app()
    val prefs = remember { app.prefs }
    val t2s = remember { app.t2s }
    val scope = rememberCoroutineScope()
    val simplified by prefs.simplified.collectAsState(initial = false)
    val theme by prefs.theme.collectAsState(initial = Prefs.SYSTEM)
    fun display(raw: String): String = Display.text(t2s, raw, simplified)
    val updateUi by updateVm.ui.collectAsState()
    val currentVersion = remember(ctx) { UpdateDownloader.currentVersion(ctx) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("uukanshu", style = MaterialTheme.typography.titleMedium)

        // Appearance card: three compact radio rows, dividers between.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader(display("外觀"))
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
                                    onClick = { scope.launch { prefs.setTheme(value) } },
                                    role = Role.RadioButton,
                                )
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = theme == value,
                                onClick = { scope.launch { prefs.setTheme(value) } },
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

        // Language card: single row, same density as theme rows.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader(display("語言"))
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
                        onCheckedChange = { scope.launch { prefs.setSimplified(it) } },
                    )
                }
            }
        }

        // Update card: version + check + status all in one place.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader(display("更新"))
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        if (currentVersion.isNotEmpty()) display("目前版本 $currentVersion")
                        else display("目前版本未知"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { updateVm.manualCheck() },
                        enabled = !updateUi.checking && !updateUi.downloading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(display("檢查更新"))
                    }
                    if (updateUi.checking) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(
                                display("檢查更新中"),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    if (updateUi.upToDate) {
                        Text(
                            display("目前已是最新版本，無需更新。"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (updateUi.error != null && updateUi.info == null) {
                        Text(
                            updateUi.error!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    // Dismissed-but-available update: tonal button reopens the dialog.
                    if (updateUi.info != null && !updateUi.visible) {
                        FilledTonalButton(
                            onClick = { updateVm.reopen() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (updateUi.fileReady) display("更新已下載完成，點擊立即安裝")
                                else display("發現新版本 ${updateUi.info!!.tag}，點擊查看"),
                            )
                        }
                    }
                    // Skip-version stays discoverable without leaving the card.
                    if (updateUi.info != null && !updateUi.downloading && !updateUi.fileReady) {
                        TextButton(onClick = { updateVm.skipVersion() }) {
                            Text(display("跳過此版本"))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
