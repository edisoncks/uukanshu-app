package cc.uukanshu.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.uukanshu.core.Display
import cc.uukanshu.data.convert.T2S
import cc.uukanshu.data.download.BookDownloadManager
import cc.uukanshu.data.parse.Parser
import cc.uukanshu.data.repo.BookRepo
import cc.uukanshu.data.prefs.Prefs
import cc.uukanshu.core.Errors
import cc.uukanshu.ui.vmFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Composable
fun DetailScreen(bookId: String, onChapter: (bookId: String, position: Int, pageId: Long) -> Unit) {
    val container = cc.uukanshu.di.LocalContainer.current
    val vm: DetailViewModel = viewModel(
        key = bookId,
        factory = vmFactory {
            DetailViewModel(container.repo, container.prefs, container.t2s, bookId, container.downloads)
        },
    )
    val ui by vm.ui.collectAsState()

    when (val load = ui.load) {
        is DetailViewModel.Load.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is DetailViewModel.Load.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Error text renders through display() like everything else
                // (see Display): friendly() is Traditional-only.
                Text(vm.displayTitle(load.message))
                Button({ vm.refresh() }, Modifier.padding(top = 12.dp)) { Text(vm.displayTitle("重試")) }
            }
        }
        is DetailViewModel.Load.Ready -> LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
            item {
                val m = load.meta
                if (load.refreshing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
                }
                Text(vm.displayTitle(m.title), style = MaterialTheme.typography.headlineSmall)
                if (load.offline) {
                    Text(
                        vm.displayTitle("離線模式 · 緩存版本"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                Text(
                    "作者：${vm.displayTitle(m.author)}  ${vm.displayTitle(m.status)}  ${m.words}".trim(),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (m.category.isNotEmpty()) Text(vm.displayTitle(m.category), style = MaterialTheme.typography.bodySmall)
                if (m.intro.isNotEmpty()) Text(
                    vm.displayTitle(m.intro),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                val bookmarked = vm.continueChapter(load.chapters)
                if (ui.downloading) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (bookmarked != null) {
                            Button(
                                onClick = { onChapter(bookId, bookmarked.position, bookmarked.pageId) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    vm.displayTitle("繼續閱讀：第 ${bookmarked.position} 章 ${bookmarked.title}"),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        LinearProgressIndicator(
                            progress = {
                                val total = vm.progressTotal(load.chapters.size).coerceAtLeast(1)
                                (ui.done.toFloat() / total).coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(vm.displayTitle("下載中") + " ${ui.done}/${vm.progressTotal(load.chapters.size)}", style = MaterialTheme.typography.bodySmall)
                        Button({ vm.cancelDownload() }, Modifier.fillMaxWidth()) { Text(vm.displayTitle("取消")) }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (bookmarked != null) {
                            Button(
                                onClick = { onChapter(bookId, bookmarked.position, bookmarked.pageId) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    vm.displayTitle("繼續閱讀：第 ${bookmarked.position} 章 ${bookmarked.title}"),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        // fullyCached survives process restart (manager done/total
                        // don't); the 已緩存 count line below shows the same source.
                        val fullyCached = load.chapters.isNotEmpty() && ui.cached.size >= load.chapters.size
                        val progressTotal = vm.progressTotal(load.chapters.size)
                        Button({ vm.downloadAll() }, Modifier.fillMaxWidth()) {
                            Text(if (fullyCached || (ui.done > 0 && progressTotal > 0 && ui.done >= progressTotal)) vm.displayTitle("重新下載整本") else vm.displayTitle("下載整本"))
                        }
                    }
                }
                ui.downloadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(
                    "${vm.displayTitle("共")} ${load.chapters.size} ${vm.displayTitle("章")} · ${vm.displayTitle("已緩存")} ${ui.cached.size} ${vm.displayTitle("章")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
                HorizontalDivider()
            }
            items(load.chapters, key = { it.pageId }) { c ->
                Row(
                    Modifier.fillMaxWidth().clickable { onChapter(bookId, c.position, c.pageId) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${c.position}. ${vm.displayTitle(c.title)}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        color = if (vm.isBookmarked(c)) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    if (vm.isBookmarked(c)) {
                        Text(
                            "▶ ${vm.displayTitle("繼續")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                    if (c.pageId in ui.cached) {
                        Text(
                            "✓ ${vm.displayTitle("已緩存")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
