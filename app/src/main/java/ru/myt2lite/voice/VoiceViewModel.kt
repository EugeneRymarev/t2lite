package ru.myt2lite.voice

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class VoiceUiState(
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val items: List<VoiceMessageDto> = emptyList(),
    val page: Int = 0,
    val total: Int? = null,
    val endReached: Boolean = false,
    val unread: Int? = null,
    val assistantName: String? = null,
    val error: String? = null,
    /** Открытый звонок (id) или null — показан список. */
    val openedId: String? = null,
    val audioLoading: Boolean = false,
    val audioError: String? = null,
    val deleting: Boolean = false,
    /** Короткое сообщение внизу экрана (удалено / ошибка действия). */
    val toast: String? = null,
    /** Имена из телефонной книги: номер (как пришёл с сервера) → имя. Нет ключа — номера нет в книге. */
    val contactNames: Map<String, String> = emptyMap(),
    /** Есть ли разрешение READ_CONTACTS. */
    val contactsAllowed: Boolean = true
) {
    fun nameFor(number: String?): String? = number?.let { contactNames[it] }

    val opened: VoiceMessageDto? get() = openedId?.let { id -> items.firstOrNull { it.id == id } }
    val canLoadMore: Boolean get() = !endReached && items.isNotEmpty()
}

class VoiceViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = VoiceRepository(app)
    val player = VoicePlayer(viewModelScope)

    private val _state = MutableStateFlow(VoiceUiState())
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    private var audioJob: Job? = null

    /** Номера, которые уже искали в телефонной книге (найденные и нет). */
    private val lookedUp = HashSet<String>()

    init { refresh() }

    fun refresh() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            launch { repo.unreadCount()?.let { n -> _state.update { it.copy(unread = n) } } }
            launch { repo.assistantName()?.let { n -> _state.update { it.copy(assistantName = n) } } }
            when (val r = repo.messages(page = 1)) {
                is VoiceResult.Ok -> _state.update {
                    it.copy(
                        loading = false,
                        items = r.data.items,
                        page = 1,
                        total = r.data.total,
                        endReached = isEnd(r.data, r.data.items.size),
                        error = null
                    )
                }.also { resolveContacts() }
                is VoiceResult.Err -> _state.update { it.copy(loading = false, error = r.message) }
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        if (s.loading || s.loadingMore || s.endReached) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            val next = s.page + 1
            when (val r = repo.messages(page = next)) {
                is VoiceResult.Ok -> _state.update { cur ->
                    val known = cur.items.mapNotNull { it.id }.toHashSet()
                    val fresh = r.data.items.filter { it.id !in known }
                    val merged = cur.items + fresh
                    cur.copy(
                        loadingMore = false,
                        items = merged,
                        page = next,
                        total = r.data.total ?: cur.total,
                        // сервер вернул те же записи / пустую страницу — дальше нет
                        endReached = fresh.isEmpty() || isEnd(r.data, merged.size)
                    )
                }.also { resolveContacts() }
                is VoiceResult.Err -> _state.update { it.copy(loadingMore = false, toast = r.message) }
            }
        }
    }

    private fun isEnd(p: VoicePage, loaded: Int): Boolean =
        p.items.isEmpty() || (p.total != null && loaded >= p.total) || (p.total == null && p.items.size < p.perPage)

    fun open(id: String) {
        _state.update { it.copy(openedId = id, audioError = null) }
        val msg = _state.value.opened ?: return
        if (msg.isUnread) markRead(id)
        prepareAudio(msg, autoPlay = false)
    }

    fun close() {
        audioJob?.cancel()
        player.release()
        _state.update { it.copy(openedId = null, audioLoading = false, audioError = null) }
    }

    /** Кнопка ▶: если запись ещё не скачана — качает и сразу играет. */
    fun playPause() {
        val s = _state.value
        val msg = s.opened ?: return
        if (player.state.value.ready) { player.toggle(); return }
        if (!s.audioLoading) prepareAudio(msg, autoPlay = true)
    }

    private fun prepareAudio(msg: VoiceMessageDto, autoPlay: Boolean) {
        audioJob?.cancel()
        player.release()
        if (msg.audioSource == null) {
            _state.update { it.copy(audioLoading = false, audioError = null) }
            return
        }
        _state.update { it.copy(audioLoading = true, audioError = null) }
        audioJob = viewModelScope.launch {
            when (val r = repo.audioFile(msg)) {
                is VoiceResult.Ok -> {
                    if (_state.value.openedId != msg.id) return@launch
                    _state.update { it.copy(audioLoading = false) }
                    player.load(r.data, msg.duration, autoPlay)
                }
                is VoiceResult.Err -> _state.update { it.copy(audioLoading = false, audioError = r.message) }
            }
        }
    }

    private fun markRead(id: String) {
        viewModelScope.launch {
            if (repo.markRead(listOf(id)) is VoiceResult.Ok) {
                _state.update { s ->
                    s.copy(
                        items = s.items.map { if (it.id == id) it.copy(status = "READ") else it },
                        unread = s.unread?.let { (it - 1).coerceAtLeast(0) }
                    )
                }
            }
        }
    }

    fun delete(id: String) {
        if (_state.value.deleting) return
        _state.update { it.copy(deleting = true) }
        viewModelScope.launch {
            when (val r = repo.delete(id)) {
                is VoiceResult.Ok -> {
                    if (_state.value.openedId == id) { audioJob?.cancel(); player.release() }
                    _state.update { s ->
                        s.copy(
                            deleting = false,
                            openedId = if (s.openedId == id) null else s.openedId,
                            items = s.items.filter { it.id != id },
                            total = s.total?.let { (it - 1).coerceAtLeast(0) },
                            toast = "Звонок удалён"
                        )
                    }
                }
                is VoiceResult.Err -> _state.update { it.copy(deleting = false, toast = r.message) }
            }
        }
    }

    /**
     * Ищет имена в телефонной книге для номеров, которых ещё не искали.
     * [force] — заново по всем номерам (после выдачи разрешения).
     */
    fun resolveContacts(force: Boolean = false) {
        val app = getApplication<Application>()
        val allowed = ContactNames.hasPermission(app)
        _state.update { it.copy(contactsAllowed = allowed) }
        if (!allowed) return
        if (force) lookedUp.clear()
        val numbers = _state.value.items.mapNotNull { it.number?.takeIf { n -> n.isNotBlank() } }
            .distinct().filter { lookedUp.add(it) }
        if (numbers.isEmpty()) return
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                numbers.mapNotNull { n -> ContactNames.lookup(app, n)?.let { n to it } }.toMap()
            }
            if (found.isNotEmpty() || force) {
                _state.update { s -> s.copy(contactNames = if (force) found else s.contactNames + found) }
            }
        }
    }

    fun toastShown() = _state.update { it.copy(toast = null) }

    override fun onCleared() {
        player.release()
        repo.clearAudioCache()
        super.onCleared()
    }
}
