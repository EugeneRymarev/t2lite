package ru.myt2lite.widget

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Последние загруженные данные для виджета (кэш между запусками).
 * Все поля nullable: объект читается Gson'ом, который не знает про значения по умолчанию Kotlin.
 */
data class WidgetSnapshot(
    val loggedIn: Boolean? = null,
    val balance: Double? = null,
    /** Сколько пополнить до списания: max(0, абонплата − баланс). */
    val toPay: Double? = null,
    val fee: Double? = null,
    /** Дата списания, ISO yyyy-MM-dd. */
    val chargeDate: String? = null,
    val rests: List<WidgetRest>? = null,
    /** Время последней успешной загрузки, мс. */
    val updatedAt: Long? = null,
    /** Что не удалось обновить в последний раз (данные остались прежние). */
    val error: String? = null,
    val refreshing: Boolean? = null
)

/** Группа остатков (из data/Rests.kt: RestGroup). */
data class WidgetRest(
    /** RestKind.name: INTERNET / MINUTES / SMS / OTHER. */
    val kind: String? = null,
    val title: String? = null,
    val remain: Double? = null,
    val limit: Double? = null,
    val unit: String? = null,
    val unlimited: Boolean? = null
)

object WidgetSnapshotStore {
    private const val PREFS = "t2lite_widget_data"
    private const val KEY = "snapshot"
    private val gson = Gson()

    @Volatile private var state: MutableStateFlow<WidgetSnapshot?>? = null

    private fun ensure(context: Context): MutableStateFlow<WidgetSnapshot?> {
        state?.let { return it }
        synchronized(this) {
            state?.let { return it }
            return MutableStateFlow(read(context.applicationContext)).also { state = it }
        }
    }

    fun flow(context: Context): StateFlow<WidgetSnapshot?> = ensure(context).asStateFlow()

    fun get(context: Context): WidgetSnapshot? = ensure(context).value

    fun save(context: Context, s: WidgetSnapshot) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, gson.toJson(s))
            .apply()
        ensure(context).value = s
    }

    fun update(context: Context, change: (WidgetSnapshot) -> WidgetSnapshot) {
        synchronized(this) { save(context, change(get(context) ?: WidgetSnapshot())) }
    }

    private fun read(context: Context): WidgetSnapshot? {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        // Если процесс убили посреди обновления, флаг «обновляется» не должен залипнуть.
        return runCatching { gson.fromJson(json, WidgetSnapshot::class.java) }.getOrNull()?.copy(refreshing = false)
    }
}
