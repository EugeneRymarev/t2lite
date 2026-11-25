package ru.myt2lite.help

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HelpUiState(
    val categories: List<QaCategory> = emptyList(),
    val loading: Boolean = false,
    /** Ошибка последней загрузки (если есть сохранённый список — он остаётся на экране). */
    val error: String? = null,
    /** Что набрано в поиске. */
    val query: String = "",
    val results: List<QaArticle> = emptyList(),
    /** Открытая тема (id) — или null, если на экране список тем. */
    val categoryId: String? = null,
    /** Открытая статья — или null. */
    val article: QaArticle? = null
) {
    val category: QaCategory? get() = categories.firstOrNull { it.id == categoryId }
    val searching: Boolean get() = query.isNotBlank()
}

class HelpViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = HelpRepository(app)
    private val _state = MutableStateFlow(HelpUiState())
    val state: StateFlow<HelpUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repo.cached()?.let { saved -> _state.update { it.copy(categories = saved) } }
            refresh()
        }
    }

    fun refresh() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = repo.load()) {
                is HelpResult.Ok -> _state.update {
                    it.copy(
                        loading = false,
                        categories = r.categories,
                        results = searchArticles(r.categories, it.query)
                    )
                }
                is HelpResult.Err -> _state.update { it.copy(loading = false, error = r.message) }
            }
        }
    }

    fun setQuery(q: String) = _state.update {
        it.copy(query = q, results = searchArticles(it.categories, q))
    }

    fun openCategory(c: QaCategory) = _state.update { it.copy(categoryId = c.id) }

    fun openArticle(a: QaArticle) = _state.update { it.copy(article = a) }

    /** «Назад»: статья → тема → список. false — закрывать раздел. */
    fun back(): Boolean {
        val s = _state.value
        return when {
            s.article != null -> { _state.update { it.copy(article = null) }; true }
            s.categoryId != null -> { _state.update { it.copy(categoryId = null) }; true }
            else -> false
        }
    }
}
