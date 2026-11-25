package ru.myt2lite.chat

import ru.myt2lite.data.Errors
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.myt2lite.data.Result
import ru.myt2lite.data.isDebugBuild
import ru.webim.android.sdk.Message
import ru.webim.android.sdk.MessageListener
import ru.webim.android.sdk.MessageStream
import ru.webim.android.sdk.MessageTracker
import ru.webim.android.sdk.Webim
import ru.webim.android.sdk.WebimError
import ru.webim.android.sdk.WebimSession

data class ChatMessage(
    val id: Message.Id,
    val text: String,
    val fromVisitor: Boolean,
    val isSystem: Boolean,
    val sender: String,
    val time: Long,
    val sending: Boolean,
    val fileName: String?,
    val fileUrl: String?,
    val serverId: String?,
    /** Кнопки бота (KEYBOARD): пары id → текст, активны, пока клавиатура ждёт ответа. */
    val buttons: List<Pair<String, String>> = emptyList()
)

data class ChatUiState(
    val connecting: Boolean = true,
    val messages: List<ChatMessage> = emptyList(),
    val chatState: String = "",
    val operator: String? = null,
    val operatorTyping: Boolean = false,
    val input: String = "",
    val error: String? = null,
    /** Есть ли ещё более ранняя история на сервере. */
    val canLoadMore: Boolean = true,
    /** Идёт подгрузка ранних сообщений. */
    val loadingHistory: Boolean = false
)

/**
 * Чат поддержки t2 через Webim SDK (как в оригинале: e00/i = WebimChatRepository).
 * Сессия Webim создаётся и используется только на главном потоке (требование SDK),
 * viewModelScope работает на Dispatchers.Main, так что это соблюдается.
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val loader = ChatConfigLoader(app)

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var session: WebimSession? = null
    private var tracker: MessageTracker? = null
    private var loadingMore = false
    /** Первый ответ getLastMessages получен: до этого getNextMessages звать нельзя (SDK бросит исключение). */
    private var initialLoaded = false

    fun connect() {
        destroySession()
        _state.value = ChatUiState(connecting = true, input = _state.value.input)
        viewModelScope.launch {
            when (val r = loader.load()) {
                is Result.Err -> _state.update { it.copy(connecting = false, error = r.message) }
                is Result.Ok -> startSession(r.data)
            }
        }
    }

    private fun startSession(cfg: ChatConfig) {
        try {
            val builder = Webim.newSessionBuilder()
                .setContext(getApplication<Application>())
                .setAccountName(cfg.accountName)
                .setLocation(cfg.location)
                .setAppVersion("6.52.0")
                .setPushSystem(Webim.PushSystem.NONE)
                .setStoreHistoryLocally(false)
                .setErrorHandler { e -> onFatal(e) }
                .setNotFatalErrorHandler { e ->
                    _state.update { it.copy(error = webimText(e, fatal = false)) }
                }
            // Подробный журнал Webim (в нём переписка и данные абонента) — только в отладочной сборке.
            if (isDebugBuild(getApplication<Application>())) {
                builder.setLogger({ Log.d("t2lite-webim", it) }, Webim.SessionBuilder.WebimLogVerbosityLevel.VERBOSE)
            }
            cfg.visitorFieldsJson?.let { builder.setVisitorFieldsJson(it) }

            val s = builder.build(object : WebimSession.SessionCallback {
                override fun onSuccess() {
                    _state.update { it.copy(connecting = false, error = null) }
                }
                override fun onFailure(sessionError: WebimError<WebimSession.SessionCallback.SessionError>) {
                    _state.update {
                        it.copy(connecting = false, error = webimText(sessionError, fatal = true))
                    }
                }
            }) ?: run {
                _state.update {
                    it.copy(connecting = false, error = Errors.build(CHAT_FAILED, "Webim: неверные параметры сессии, account=${cfg.accountName}"))
                }
                return
            }
            session = s
            val stream = s.stream
            stream.setChatStateListener { _, new -> _state.update { it.copy(chatState = describe(new), error = it.error.clearIfReconnecting()) } }
            stream.setCurrentOperatorChangeListener { _, op -> _state.update { it.copy(operator = op?.name) } }
            stream.setOperatorTypingListener { typing -> _state.update { it.copy(operatorTyping = typing) } }

            tracker = stream.newMessageTracker(listener)
            s.resume()
            _state.update { it.copy(chatState = describe(stream.chatState), operator = stream.currentOperator?.name) }
            _state.update { it.copy(loadingHistory = true) }
            tracker?.getLastMessages(PAGE) { list ->
                initialLoaded = true
                _state.update { st ->
                    val loaded = list.map(::toUi)
                    val known = loaded.map { it.id }.toSet()
                    st.copy(
                        connecting = false,
                        loadingHistory = false,
                        messages = loaded + st.messages.filterNot { it.id in known },
                        // getLastMessages отдаёт только текущий чат; прошлые обращения
                        // приходят через getNextMessages, поэтому «ещё есть» по умолчанию.
                        canLoadMore = list.isNotEmpty()
                    )
                }
                markRead()
                // Если текущий чат короткий — сразу дотягиваем страницу истории,
                // чтобы экран не был пустым и прокрутка вверх работала.
                if (list.isNotEmpty() && list.size < PAGE) loadMore()
            }
        } catch (e: Exception) {
            _state.update { it.copy(connecting = false, error = Errors.build(CHAT_FAILED, "Webim: ${e.javaClass.simpleName}: ${e.message}")) }
        }
    }

    private val listener = object : MessageListener {
        override fun messageAdded(before: Message?, message: Message) {
            _state.update { it.copy(error = it.error.clearIfReconnecting()) }
            _state.update { st ->
                val ui = toUi(message)
                val list = st.messages.filterNot { it.id == ui.id }.toMutableList()
                val idx = before?.let { b -> list.indexOfFirst { it.id == b.clientSideId } } ?: -1
                if (idx >= 0) list.add(idx, ui) else list.add(ui)
                st.copy(messages = list)
            }
            markRead()
        }

        override fun messageRemoved(message: Message) {
            _state.update { st -> st.copy(messages = st.messages.filterNot { it.id == message.clientSideId }) }
        }

        override fun messageChanged(from: Message, to: Message) {
            _state.update { st ->
                st.copy(messages = st.messages.map { if (it.id == from.clientSideId) toUi(to) else it })
            }
        }

        override fun allMessagesRemoved() {
            _state.update { it.copy(messages = emptyList()) }
        }
    }

    /** Подгрузить предыдущую страницу истории (старые обращения в поддержку). */
    fun loadMore() {
        val t = tracker ?: return
        if (!initialLoaded || loadingMore || !_state.value.canLoadMore) return
        loadingMore = true
        _state.update { it.copy(loadingHistory = true) }
        try {
            t.getNextMessages(PAGE) { list ->
                loadingMore = false
                _state.update { st ->
                    val known = st.messages.map { it.id }.toSet()
                    st.copy(
                        messages = list.map(::toUi).filterNot { it.id in known } + st.messages,
                        canLoadMore = list.isNotEmpty(),
                        loadingHistory = false
                    )
                }
            }
        } catch (e: Exception) {
            // SDK ещё грузит предыдущую порцию — просто попробуем при следующей прокрутке.
            loadingMore = false
            _state.update { it.copy(loadingHistory = false) }
        }
    }

    fun onInput(v: String) = _state.update { it.copy(input = v) }

    fun send() {
        val text = _state.value.input.trim()
        val s = session ?: return
        if (text.isEmpty()) return
        try {
            s.stream.sendMessage(text)
            _state.update { it.copy(input = "") }
            s.stream.setVisitorTyping(null)
        } catch (e: Exception) {
            _state.update { it.copy(error = Errors.build("Сообщение не отправилось. Проверьте интернет и попробуйте ещё раз.", "${e.javaClass.simpleName}: ${e.message}")) }
        }
    }

    fun pressButton(msg: ChatMessage, buttonId: String) {
        val sid = msg.serverId ?: return
        try {
            session?.stream?.sendKeyboardRequest(sid, buttonId, null)
        } catch (e: Exception) {
            _state.update { it.copy(error = Errors.build("Кнопка не сработала. Попробуйте ещё раз.", "${e.javaClass.simpleName}: ${e.message}")) }
        }
    }

    fun onTyping(draft: String) {
        runCatching { session?.stream?.setVisitorTyping(draft.ifBlank { null }) }
    }

    fun closeChat() {
        runCatching { session?.stream?.closeChat() }
    }

    fun pause() { runCatching { session?.pause() } }
    fun resume() { runCatching { session?.resume() } }

    private fun markRead() {
        runCatching { session?.stream?.setChatRead() }
    }

    private fun onFatal(e: WebimError<*>) {
        _state.update { it.copy(connecting = false, error = webimText(e, fatal = true)) }
    }

    /** Ошибки Webim по-человечески (как в оригинале: chat_no_internet_error, chat_error_restoring_connection, chat_smth_wrong_error). */
    private fun webimText(e: WebimError<*>, fatal: Boolean): String {
        val type = e.errorType.toString().uppercase()
        val text = when {
            "NO_NETWORK" in type || "SOCKET_TIMEOUT" in type || "SERVER_IS_NOT_AVAILABLE" in type ->
                if (fatal) "Нет связи с чатом. Проверьте интернет и обновите страницу."
                else RECONNECTING
            "VISITOR_BANNED" in type -> "Чат поддержки для вас закрыт. Позвоните в поддержку t2: 611."
            "ACCOUNT_BLOCKED" in type -> "Чат поддержки t2 сейчас не работает. Попробуйте позже."
            "PROVIDED_VISITOR_EXPIRED" in type || "WRONG_PROVIDED_VISITOR_HASH" in type ->
                "Чат не узнал вас. Закройте его и откройте снова."
            else -> "$CHAT_FAILED Обновите экран или попробуйте позже."
        }
        return Errors.build(text, "Webim: ${e.errorType} ${e.errorString ?: ""}".trim())
    }

    private fun destroySession() {
        runCatching { tracker?.destroy() }
        runCatching { session?.destroy() }
        tracker = null
        session = null
        loadingMore = false
        initialLoaded = false
    }

    override fun onCleared() {
        destroySession()
        super.onCleared()
    }

    private fun toUi(m: Message): ChatMessage {
        val file = m.attachment?.fileInfo
        val fromVisitor = m.type == Message.Type.VISITOR || m.type == Message.Type.FILE_FROM_VISITOR ||
            m.type == Message.Type.STICKER_VISITOR || m.type == Message.Type.KEYBOARD_RESPONSE
        val system = m.type == Message.Type.INFO || m.type == Message.Type.ACTION_REQUEST ||
            m.type == Message.Type.OPERATOR_BUSY || m.type == Message.Type.CONTACT_REQUEST
        val kb = if (m.type == Message.Type.KEYBOARD) m.keyboard else null
        val allButtons = kb?.buttons.orEmpty().flatten()
        val buttons = if (kb != null && kb.state == Message.Keyboard.State.PENDING)
            allButtons.map { it.id to it.text } else emptyList()
        val chosenId = kb?.keyboardResponse?.buttonId
        val chosen = allButtons.firstOrNull { it.id == chosenId }?.text
        val text = listOfNotNull(
            m.text.takeIf { it.isNotBlank() },
            chosen?.let { "Выбрано: $it" }
        ).joinToString("\n")
        return ChatMessage(
            id = m.clientSideId,
            text = text,
            fromVisitor = fromVisitor,
            isSystem = system,
            sender = m.senderName,
            time = m.time,
            sending = m.sendStatus == Message.SendStatus.SENDING,
            fileName = file?.fileName,
            fileUrl = file?.url,
            serverId = m.serverSideId,
            buttons = buttons
        )
    }

    private fun describe(s: MessageStream.ChatState?): String = when (s) {
        MessageStream.ChatState.CHATTING -> "Оператор в чате"
        MessageStream.ChatState.CHATTING_WITH_ROBOT -> "Отвечает бот"
        MessageStream.ChatState.QUEUE -> "Ожидание оператора"
        MessageStream.ChatState.CLOSED_BY_OPERATOR -> "Оператор закрыл чат"
        MessageStream.ChatState.CLOSED_BY_VISITOR -> "Чат закрыт"
        MessageStream.ChatState.INVITATION -> "Приглашение в чат"
        MessageStream.ChatState.DELETED -> "Чат удалён"
        MessageStream.ChatState.NONE -> "Напишите, чтобы начать чат"
        else -> ""
    }

    init { connect() }

    companion object { private const val PAGE = 25 }
}

private const val RECONNECTING = "Подождите, восстанавливаем подключение…"
private const val CHAT_FAILED = "Не получилось подключиться к чату поддержки."

/** Связь вернулась — убираем «восстанавливаем подключение». */
private fun String?.clearIfReconnecting(): String? =
    if (this != null && Errors.text(this) == RECONNECTING) null else this
