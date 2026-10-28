package cc.uukanshu.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import cc.uukanshu.core.Display
import cc.uukanshu.data.prefs.Prefs
import cc.uukanshu.data.updatecheck.BookUpdateScheduler
import cc.uukanshu.data.updatecheck.UpdateChecker
import cc.uukanshu.data.update.UpdateDownloader
import cc.uukanshu.ui.ThemeOptionsCard
import cc.uukanshu.ui.update.UpdateViewModel
import kotlinx.coroutines.launch

/**
 * Settings tab: appearance, Traditional/Simplified, 追更 auto-check, app update.
 * Living here instead of the Home top bar keeps that bar a plain title and
 * keeps the update flow testable in one place. Controls write to [Prefs] so
 * Home/Search/Library/Reader follow live; the update card drives the injected
 * shared [UpdateViewModel] (this screen owns no ViewModel of its own).
 *
 * Layout: four cards (appearance / language / 追更 / update), each a
 * fillMaxWidth Card under a SectionHeader, so sections share one rhythm
 * instead of floating loose in a flat column. Controls stay one row tall:
 * the themes are [ThemeOptionsCard] rather than four radio
 * rows, and the 追更 card keeps its state and its last-check stamp on one line.
 *
 * The 追更 switch is named for the bookshelf so it cannot be read as the app
 * update check in the card below it.
 */
@Composable
fun SettingsScreen(updateVm: UpdateViewModel) {
    val ctx = LocalContext.current
    val container = cc.uukanshu.di.LocalContainer.current
    val prefs = remember { container.prefs }
    val t2s = remember { container.t2s }
    val scope = rememberCoroutineScope()
    val simplified by prefs.simplified.collectAsState(initial = false)
    val theme by prefs.theme.collectAsState(initial = Prefs.SYSTEM)
    fun display(raw: String): String = Display.text(t2s, raw, simplified)
    val updateUi by updateVm.ui.collectAsState()
    val currentVersion = remember(ctx) { UpdateDownloader.currentVersion(ctx) }
    val autoBookEnabled by prefs.autoBookCheckEnabled.collectAsState(initial = true)
    val lastBookCheck by prefs.lastBookCheck.collectAsState(initial = 0L)
    var notifGrantedState by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(ctx) {
        notifGrantedState = if (Build.VERSION.SDK_INT < 33) true
        else ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notifGrantedState = granted
    }
    fun notifGranted(): Boolean {
        notifGrantedState?.let { return it }
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("uukanshu", style = MaterialTheme.typography.titleMedium)

        // Appearance: ThemeOptionsCard, the very card the reader sheet shows.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader(display("外觀"))
            ThemeOptionsCard(
                selected = theme,
                display = ::display,
                onSelect = { scope.launch { prefs.setTheme(it) } },
            )
        }

        // Language card: single row, same density as the appearance row.
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

        // 追更 card: automatic update-check switch, same rhythm as other cards.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader(display("追更"))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(display("自動檢查書架更新"), style = MaterialTheme.typography.bodyLarge)
                        Switch(
                            checked = autoBookEnabled,
                            onCheckedChange = { on ->
                                scope.launch {
                                    prefs.setAutoBookCheckEnabled(on)
                                    if (on) {
                                        BookUpdateScheduler.scheduleUpdate(ctx)
                                        if (!notifGranted() && Build.VERSION.SDK_INT >= 33) {
                                            try {
                                                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                            } catch (e: Exception) {
                                                Log.w("Settings", "notification permission request failed", e)
                                            }
                                        }
                                    } else {
                                        BookUpdateScheduler.cancel(ctx)
                                    }
                                }
                            },
                        )
                    }
                    // State and last-check share one line: stacked they said the
                    // same thing in twice the height. A denied notification
                    // permission replaces the state text rather than adding a
                    // third line, because it is the more specific statement.
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            display(
                                when {
                                    !autoBookEnabled -> "已關閉，可在書架手動檢查"
                                    notifGrantedState == false -> "通知已關閉，改在書架顯示徽章"
                                    else -> "有更新時通知"
                                }
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            // formatLastCheck owns the "no check yet" wording; the
                            // prefix must not produce "上次檢查 從未檢查".
                            if (lastBookCheck > 0L) display("上次檢查 ${UpdateChecker.formatLastCheck(lastBookCheck)}")
                            else display("尚未檢查"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
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
                    // Snapshot nullable UI state into locals so smart-cast (not !!)
                    // guards the error/info renders below.
                    val updateError = updateUi.error
                    val updateInfo = updateUi.info
                    if (updateError != null && updateInfo == null) {
                        Text(
                            display(updateError),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    // Dismissed-but-available update: tonal button reopens the dialog.
                    if (updateInfo != null && !updateUi.visible) {
                        FilledTonalButton(
                            onClick = { updateVm.reopen() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (updateUi.fileReady) display("更新已下載完成，點擊立即安裝")
                                else display("發現新版本 ${updateInfo.tag}，點擊查看"),
                            )
                        }
                    }
                    // Skip-version stays discoverable without leaving the card.
                    if (updateInfo != null && !updateUi.downloading && !updateUi.fileReady) {
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
