package ru.myt2lite.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.myt2lite.data.Balance
import ru.myt2lite.data.NextCharge
import ru.myt2lite.data.Result
import ru.myt2lite.data.T2Repository
import ru.myt2lite.data.auth.AuthManager
import ru.myt2lite.ui.market.MarketNav
import ru.myt2lite.widget.WidgetUpdater
import ru.myt2lite.reminder.TopUpReminder

enum class Step { PHONE, CODE, BALANCE }

data class UiState(
    val step: Step = Step.PHONE,
    val phone: String = "",
    val code: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val info: String? = null,
    val balance: Balance? = null,
    val nextCharge: NextCharge? = null,
    val nextChargeError: String? = null
)

class AuthViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = T2Repository(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        // refresh_token отклонён сервером — возвращаемся на вход, номер подставляем.
        viewModelScope.launch {
            AuthManager.sessionExpired.collect {
                MarketNav.isOpen = false
                val phone = repo.tokenStore.phone().orEmpty()
                _state.value = UiState(phone = phone, error = "Сессия истекла, войдите заново по SMS")
            }
        }
        viewModelScope.launch {
            if (repo.isLoggedIn()) {
                _state.value = _state.value.copy(step = Step.BALANCE, phone = repo.tokenStore.phone().orEmpty())
                loadBalance()
            }
        }
    }

    fun onPhoneChange(v: String) { _state.value = _state.value.copy(phone = v, error = null) }
    fun onCodeChange(v: String) { _state.value = _state.value.copy(code = v, error = null) }

    fun sendCode() {
        val norm = repo.normalizePhone(_state.value.phone)
        if (norm == null) {
            _state.value = _state.value.copy(error = "Введите корректный номер (11 цифр)")
            return
        }
        _state.value = _state.value.copy(loading = true, error = null, info = null)
        viewModelScope.launch {
            when (val r = repo.requestSms(norm)) {
                is Result.Ok -> _state.value = _state.value.copy(
                    loading = false, step = Step.CODE, phone = norm, info = r.data
                )
                is Result.Err -> _state.value = _state.value.copy(loading = false, error = r.message)
            }
        }
    }

    fun login() {
        val code = _state.value.code.trim()
        if (code.isEmpty()) {
            _state.value = _state.value.copy(error = "Введите код из SMS")
            return
        }
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            when (val r = repo.login(_state.value.phone, code)) {
                is Result.Ok -> {
                    _state.value = _state.value.copy(loading = false, step = Step.BALANCE, info = null)
                    loadBalance()
                }
                is Result.Err -> _state.value = _state.value.copy(loading = false, error = r.message)
            }
        }
    }

    fun loadBalance() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            when (val r = repo.balance()) {
                is Result.Ok -> _state.value = _state.value.copy(loading = false, balance = r.data)
                is Result.Err -> _state.value = _state.value.copy(loading = false, error = r.message)
            }
            loadNextCharge()
            WidgetUpdater.onAppRefreshed(getApplication<Application>()) // виджет: подтянуть данные, если устарели
        }
    }

    private suspend fun loadNextCharge() {
        when (val r = repo.nextCharge(_state.value.balance?.value)) {
            is Result.Ok -> {
                _state.value = _state.value.copy(nextCharge = r.data, nextChargeError = null)
                TopUpReminder.onChargeLoaded(getApplication<Application>(), r.data) // пополнили — убрать напоминание
            }
            is Result.Err -> _state.value = _state.value.copy(nextCharge = null, nextChargeError = r.message)
        }
    }

    fun logout() {
        viewModelScope.launch {
            repo.logout()
            WidgetUpdater.onLogout(getApplication<Application>()) // виджет: убрать данные
            TopUpReminder.onLogout(getApplication<Application>()) // напоминания: убрать уведомление и кэш
            _state.value = UiState()
        }
    }

    fun backToPhone() {
        _state.value = _state.value.copy(step = Step.PHONE, code = "", error = null, info = null)
    }
}
