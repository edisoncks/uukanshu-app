package cc.uukanshu.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * All update prompts in one dialog, driven by [UpdateViewModel.Ui].
 * [display] converts Traditional → Simplified when that mode is on.
 */
@Composable
fun UpdateDialog(
    ui: UpdateViewModel.Ui,
    display: (String) -> String,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onCancelDownload: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onBrowser: () -> Unit,
    onOpenUnknownSources: () -> Unit,
) {
    if (!ui.visible) return
    val info = ui.info

    when {
        ui.checking -> AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text(display("檢查更新中")) },
            text = { CircularProgressIndicator() },
        )

        ui.upToDate -> AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(display("關閉")) }
            },
            title = { Text(display("已是最新版本")) },
            text = { Text(display("目前已是最新版本，無需更新。")) },
        )

        info == null && ui.error != null -> AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = onRetry) { Text(display("重試")) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(display("關閉")) }
            },
            title = { Text(display("檢查更新失敗")) },
            text = { Text(ui.error) },
        )

        info != null -> {
            val title = when {
                ui.fileReady -> display("已下載完成")
                ui.downloading -> display("正在下載更新")
                else -> display("發現新版本 ${info.tag}")
            }
            AlertDialog(
                onDismissRequest = { if (!ui.downloading) onDismiss() },
                confirmButton = {
                    when {
                        ui.fileReady -> TextButton(onClick = onInstall) {
                            Text(display("立即安裝"))
                        }
                        ui.downloading -> TextButton(onClick = onCancelDownload) {
                            Text(display("取消"))
                        }
                        else -> TextButton(onClick = onDownload) {
                            Text(display("立即更新"))
                        }
                    }
                },
                dismissButton = {
                    when {
                        ui.downloading -> null
                        ui.fileReady -> TextButton(onClick = onDismiss) {
                            Text(display("稍後"))
                        }
                        else -> Column {
                            TextButton(onClick = onDismiss) { Text(display("稍後")) }
                        }
                    }
                },
                title = { Text(title) },
                text = {
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (ui.needsUnknownSources) {
                            Text(
                                display("需先允許「安裝未知應用」才能更新。請在系統設定中允許，然後返回重試。"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = onOpenUnknownSources) {
                                Text(display("前往設定"))
                            }
                        }
                        if (ui.downloading) {
                            if (ui.progress != null) {
                                LinearProgressIndicator(
                                    progress = { ui.progress },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    display("已下載 ${(ui.progress * 100).toInt()}%"),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            } else {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text(
                                    display("下載中…"),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        } else if (!ui.fileReady && info.changelog.isNotEmpty()) {
                            Text(
                                display(info.changelog.take(2000)),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .heightIn(max = 220.dp)
                                    .verticalScroll(rememberScrollState()),
                            )
                        } else if (ui.fileReady) {
                            Text(
                                display("${info.apkName} 已下載完成，點擊立即安裝（原有資料與快取會保留）。"),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (ui.error != null) {
                            Text(
                                ui.error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = onBrowser) {
                                Text(display("改用瀏覽器下載"))
                            }
                        }
                        if (!ui.downloading && !ui.fileReady) {
                            TextButton(
                                onClick = onSkip,
                                modifier = Modifier.padding(top = 4.dp),
                            ) { Text(display("跳過此版本")) }
                        }
                    }
                },
            )
        }
    }
}
