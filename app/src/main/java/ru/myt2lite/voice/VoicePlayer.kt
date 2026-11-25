package ru.myt2lite.voice

import ru.myt2lite.data.Errors
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** Состояние плеера записи звонка. */
data class PlayerState(
    val ready: Boolean = false,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val error: String? = null
)

/** Простой плеер поверх MediaPlayer: один файл, play/pause/seek, позиция обновляется 5 раз в секунду. */
class VoicePlayer(private val scope: CoroutineScope) {

    private var player: MediaPlayer? = null
    private var ticker: Job? = null
    private var file: File? = null

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    /** Подготовить файл. [fallbackDurationMs] — длительность из ответа сервера, пока плеер не знает свою. */
    fun load(f: File, fallbackDurationMs: Long?, autoPlay: Boolean) {
        if (file == f && player != null) { if (autoPlay) play(); return }
        release()
        file = f
        _state.value = PlayerState(durationMs = fallbackDurationMs ?: 0)
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(f.absolutePath)
            mp.setOnPreparedListener {
                val d = it.duration.toLong().takeIf { ms -> ms > 0 } ?: (fallbackDurationMs ?: 0)
                _state.value = _state.value.copy(ready = true, durationMs = d, error = null)
                if (autoPlay) play()
            }
            mp.setOnCompletionListener {
                stopTicker()
                _state.value = _state.value.copy(playing = false, positionMs = 0)
                runCatching { it.seekTo(0) }
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.w("T2Voice", "MediaPlayer error $what/$extra, файл ${f.name} (${f.length()} байт)")
                stopTicker()
                _state.value = _state.value.copy(playing = false, ready = false,
                    error = Errors.build("Не получилось включить запись.", "MediaPlayer $what/$extra"))
                f.delete() // возможно, скачался не аудиофайл — в следующий раз скачаем заново
                true
            }
            player = mp
            mp.prepareAsync()
        } catch (e: Exception) {
            _state.value = PlayerState(error = Errors.build("Невозможно прослушать запись.", "${e.javaClass.simpleName}: ${e.message}"))
            release()
        }
    }

    fun toggle() { if (_state.value.playing) pause() else play() }

    fun play() {
        val mp = player ?: return
        if (!_state.value.ready) return
        runCatching { mp.start() }.onFailure { return }
        _state.value = _state.value.copy(playing = true)
        startTicker()
    }

    fun pause() {
        val mp = player ?: return
        runCatching { if (mp.isPlaying) mp.pause() }
        stopTicker()
        _state.value = _state.value.copy(playing = false, positionMs = runCatching { mp.currentPosition.toLong() }.getOrDefault(0L))
    }

    fun seekTo(ms: Long) {
        val mp = player ?: return
        if (!_state.value.ready) return
        runCatching { mp.seekTo(ms.toInt()) }
        _state.value = _state.value.copy(positionMs = ms)
    }

    private fun startTicker() {
        stopTicker()
        ticker = scope.launch {
            while (isActive) {
                val mp = player ?: break
                val pos = runCatching { mp.currentPosition.toLong() }.getOrNull() ?: break
                _state.value = _state.value.copy(positionMs = pos)
                delay(200)
            }
        }
    }

    private fun stopTicker() { ticker?.cancel(); ticker = null }

    fun release() {
        stopTicker()
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        file = null
        _state.value = PlayerState()
    }
}
