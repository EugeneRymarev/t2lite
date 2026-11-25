package ru.myt2lite.issues

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class IssuesUiState(
    val loading: Boolean = false,
    /** null — ещё ни разу не загрузили. */
    val issues: List<Issue>? = null,
    val error: String? = null
)

class IssuesViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = IssuesRepository(app)

    private val _state = MutableStateFlow(IssuesUiState())
    val state: StateFlow<IssuesUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = repo.issues()) {
                is IssuesResult.Ok -> _state.update { it.copy(loading = false, issues = r.data, error = null) }
                is IssuesResult.Err -> _state.update { it.copy(loading = false, error = r.message) }
            }
        }
    }
}
