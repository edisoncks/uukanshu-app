package cc.uukanshu.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.uukanshu.core.Display
import cc.uukanshu.data.convert.T2S
import cc.uukanshu.data.parse.Parser
import cc.uukanshu.data.prefs.Prefs
import cc.uukanshu.data.repo.BookRepo
import cc.uukanshu.core.Errors
import cc.uukanshu.ui.vmFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(onBook: (String) -> Unit) {
    val container = cc.uukanshu.di.LocalContainer.current
    val vm: SearchViewModel = viewModel(factory = vmFactory {
        SearchViewModel(container.repo, container.prefs, container.t2s)
    })
    val ui by vm.ui.collectAsState()
    var text by rememberSaveable { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; vm.query(it) },
            label = { Text(vm.display("書名搜索…")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        ui.totalOrNull?.let { total ->
            Text(
                "${vm.display("共")} $total ${vm.display("條結果")}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        when (val s = ui) {
            // Stale-while-revalidate (see Detail/Library): a new query keeps
            // the last results under a thin bar; spinner only with no rows.
            is SearchViewModel.Ui.Loading -> if (s.books.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    ResultList(s.books, vm::display, onBook)
                }
            }
            is SearchViewModel.Ui.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(vm.display(s.message))
                    Button({ vm.query(text) }, Modifier.padding(top = 12.dp)) { Text(vm.display("重試")) }
                }
            }
            is SearchViewModel.Ui.Success -> if (s.books.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(vm.display("沒有結果"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else ResultList(s.books, vm::display, onBook)
            // Never searched / cleared: blank, matching the old empty-list branch.
            is SearchViewModel.Ui.Idle -> Box(Modifier.fillMaxSize()) {}
        }
    }
}

/** Shared result rows for Success and stale-Loading (see above). */
@Composable
private fun ResultList(
    books: List<Parser.BookItem>,
    display: (String) -> String,
    onBook: (String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(books, key = { it.id }) { b ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onBook(b.id) }) {
                Column(Modifier.padding(12.dp)) {
                    Text(display(b.title), style = MaterialTheme.typography.titleMedium)
                    if (b.author.isNotEmpty()) Text(
                        "${display("作者")}：${display(b.author)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (b.latestChapterTitle.isNotEmpty()) Text(
                        "${display("更新到")}：${display(b.latestChapterTitle)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
