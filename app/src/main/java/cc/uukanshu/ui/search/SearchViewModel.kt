package cc.uukanshu.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.uukanshu.core.Display
import cc.uukanshu.data.convert.T2S
import cc.uukanshu.data.parse.Parser
import cc.uukanshu.di.PrefsApi
import cc.uukanshu.di.RepoApi
import cc.uukanshu.core.Errors
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

class SearchViewModel(
    private val repo: RepoApi,
    private val prefs: PrefsApi,
    private val t2s: T2S,
) : ViewModel() {
    /**
     * Sealed states: impossible combinations (loading + error, error +
     * books, spinner-stuck defaults) are unrepresentable. Every `when`
     * over Ui is compiler-checked as exhaustive — adding a state breaks
     * the build until the UI handles it.
     */
    sealed interface Ui {
        val simplified: Boolean
        val totalOrNull: Int?
        data class Idle(override val simplified: Boolean = false) : Ui {
            override val totalOrNull: Int? = null
        }
        data class Loading(
            override val simplified: Boolean,
            override val totalOrNull: Int? = null,
            /** Last results, kept visible under the spinner (stale-while-revalidate). */
            val books: List<Parser.BookItem> = emptyList(),
        ) : Ui
        data class Success(
            val books: List<Parser.BookItem>,
            val total: Int?,
            override val simplified: Boolean,
        ) : Ui {
            override val totalOrNull: Int? = total
        }
        data class Error(
            val message: String,
            override val totalOrNull: Int? = null,
            override val simplified: Boolean,
        ) : Ui
    }

    private fun Ui.withSimplified(v: Boolean): Ui = when (this) {
        is Ui.Idle -> copy(simplified = v)
        is Ui.Loading -> copy(simplified = v)
        is Ui.Success -> copy(simplified = v)
        is Ui.Error -> copy(simplified = v)
    }

    private val _ui = MutableStateFlow<Ui>(Ui.Idle())
    val ui: StateFlow<Ui> = _ui
    // Raw keystrokes, versioned; the pipeline below debounces + cancels superseded
    // searches, so no manual Job/activeQuery guard can be forgotten. The version
    // defeats StateFlow equality conflation: an identical-text retry (e.g. the
    // error-screen button) must refire instead of collapsing into no emission.
    private val queries = MutableStateFlow(0 to "")

    init {
        viewModelScope.launch {
            _ui.update { it.withSimplified(prefs.simplified.first()) }
        }
        // Settings owns the toggle now: follow it live so results
        // re-render when the user flips Simplified/Traditional there.
        viewModelScope.launch {
            prefs.simplified.collect { v -> _ui.update { it.withSimplified(v) } }
        }
        viewModelScope.launch {
            @OptIn(FlowPreview::class)
            queries.debounce(400).flatMapLatest { (_, raw) ->
                val q = raw.trim()
                if (q.isEmpty()) {
                    flowOf<Ui>(Ui.Idle(simplified = _ui.value.simplified))
                } else {
                    flow<Ui> {
                        val s = _ui.value
                        emit(
                            Ui.Loading(
                                simplified = s.simplified,
                                totalOrNull = s.totalOrNull,
                                books = (s as? Ui.Success)?.books.orEmpty(),
                            ),
                        )
                        try {
                            val res = repo.search(q)
                            // No stale-check needed: flatMapLatest cancels the
                            // previous flow, so a superseded result is never
                            // collected (the old code needed activeQuery because
                            // cancellation only bites at suspend points and
                            // nothing suspended past this point).
                            emit(
                                Ui.Success(
                                    books = dedupBooks(res.books), total = res.total,
                                    simplified = _ui.value.simplified,
                                ),
                            )
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            emit(
                                Ui.Error(
                                    message = Errors.friendly(e),
                                    totalOrNull = _ui.value.totalOrNull,
                                    simplified = _ui.value.simplified,
                                ),
                            )
                        }
                    }
                }
            }.collect { _ui.value = it }
        }
    }

    fun display(raw: String): String =
        Display.text(t2s, raw, _ui.value.simplified)

    companion object {
        /** Drop duplicate cards by stable book id (same live-shift dup source as Home). */
        fun dedupBooks(books: List<Parser.BookItem>): List<Parser.BookItem> =
            books.distinctBy { it.id }
    }

    fun query(q: String) {
        queries.update { (v, _) -> v + 1 to q }
        if (q.isBlank()) {
            // Clear instantly for snappy UX; the debounced pipeline re-emits
            // the same empty state idempotently.
            _ui.value = Ui.Idle(simplified = _ui.value.simplified)
        }
    }
}
