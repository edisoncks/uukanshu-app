package cc.uukanshu.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import cc.uukanshu.App
import cc.uukanshu.data.convert.T2S
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
 */
@Composable
fun SettingsScreen(updateVm: UpdateViewModel) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as App
    val prefs = remember { Prefs(app) }
    val t2s = remember { T2S(app) }
    val scope = rememberCoroutineScope()
    val simplified by prefs.simplified.collectAsState(initial = false)
    val theme by prefs.theme.collectAsState(initial = Prefs.SYSTEM)
    fun display(raw: String): String = if (simplified) t2s.convert(raw) else raw
    val updateUi by updateVm.ui.collectAsState()
    val currentVersion = remember(ctx) { UpdateDownloader.currentVersion(ctx) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("uukanshu", style = MaterialTheme.typography.titleMedium)

        // Appearance.
        Text(display("外觀"), style = MaterialTheme.typography.titleSmall)
        listOf(
            Prefs.SYSTEM to "自動",
            Prefs.LIGHT to "淺色",
            Prefs.DARK to "深色",
        ).forEach { (value, name) ->
            Row(
                Modifier.fillMaxWidth()
                    .selectable(
                        selected = theme == value,
                        onClick = { scope.launch { prefs.setTheme(value) } },
                        role = Role.RadioButton,
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = theme == value,
                    onClick = { scope.launch { prefs.setTheme(value) } },
                )
                Text(display("主題：$name"), modifier = Modifier.padding(start = 8.dp))
            }
        }

        // Language.
        Text(display("語言"), style = MaterialTheme.typography.titleSmall)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(display(if (simplified) "簡體" else "繁體"))
            Switch(
                checked = simplified,
                onCheckedChange = { scope.launch { prefs.setSimplified(it) } },
            )
        }

        // Update.
        Text(display("更新"), style = MaterialTheme.typography.titleSmall)
        Text(
            if (currentVersion.isNotEmpty()) display("目前版本 $currentVersion")
            else display("目前版本未知"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = { updateVm.manualCheck() },
            enabled = !updateUi.checking && !updateUi.downloading,
        ) {
            Text(display("檢查更新"))
        }
        if (updateUi.checking) {
            CircularProgressIndicator()
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
        // Dismissed-but-available update: one-line banner to reopen the dialog.
        if (updateUi.info != null && !updateUi.visible) {
            Card(
                onClick = { updateVm.reopen() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (updateUi.fileReady) display("更新已下載完成，點擊立即安裝")
                    else display("發現新版本 ${updateUi.info!!.tag}，點擊查看"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        // Skip-version hint keeps the dialog's "skip" discoverable.
        if (updateUi.info != null && !updateUi.downloading && !updateUi.fileReady) {
            TextButton(onClick = { updateVm.skipVersion() }) {
                Text(display("跳過此版本"))
            }
        }
    }
}
