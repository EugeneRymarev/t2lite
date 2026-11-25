package ru.myt2lite.reminder

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.myt2lite.MainActivity
import ru.myt2lite.R
import ru.myt2lite.data.NextCharge
import ru.myt2lite.data.T2Repository
import ru.myt2lite.widget.WidgetSnapshotStore
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.TimeUnit
import ru.myt2lite.data.Result as T2Result

/**
 * Напоминания о пополнении баланса: каждый день, начиная за [ReminderSettings.DAYS_BEFORE] дня
 * до списания абонплаты, если на счёте меньше суммы списания (доплата из data/NextCharge.kt).
 *
 * Проверку раз в час делает WorkManager (только при наличии сети). Сеть дёргается редко:
 * до «окна» напоминаний — раз в сутки (узнать дату), в окне — пока не показали напоминание за сегодня.
 * Уведомления показываются не раньше выбранного часа и не позже [QUIET_FROM]:00.
 */
data class ReminderSettings(
    val enabled: Boolean = true,
    /** С какого часа можно показывать напоминание. */
    val hour: Int = 10
) {
    companion object {
        const val DAYS_BEFORE = 3
        val HOURS = listOf(8, 9, 10, 12, 15, 18, 20)
    }
}

/** Настройки и состояние напоминаний (SharedPreferences) + StateFlow для экрана. */
object ReminderStore {
    private const val PREFS = "t2lite_reminder"

    @Volatile private var state: MutableStateFlow<ReminderSettings>? = null

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun ensure(context: Context): MutableStateFlow<ReminderSettings> {
        state?.let { return it }
        synchronized(this) {
            state?.let { return it }
            val p = prefs(context)
            val d = ReminderSettings()
            val s = ReminderSettings(
                enabled = p.getBoolean("enabled", d.enabled),
                hour = p.getInt("hour", d.hour)
            )
            return MutableStateFlow(s).also { state = it }
        }
    }

    fun flow(context: Context): StateFlow<ReminderSettings> = ensure(context).asStateFlow()
    fun get(context: Context): ReminderSettings = ensure(context).value

    fun save(context: Context, s: ReminderSettings) {
        prefs(context).edit().putBoolean("enabled", s.enabled).putInt("hour", s.hour).apply()
        ensure(context).value = s
    }

    // ---- состояние ----
    fun lastNotifiedDay(c: Context): LocalDate? = date(c, "notifiedDay")
    fun lastCheckDay(c: Context): LocalDate? = date(c, "checkDay")
    fun lastChargeDate(c: Context): LocalDate? = date(c, "chargeDate")
    fun lastFee(c: Context): Double? =
        prefs(c).getString("fee", null)?.toDoubleOrNull()

    fun setNotifiedDay(c: Context, d: LocalDate) = prefs(c).edit().putString("notifiedDay", d.toString()).apply()
    fun setChecked(c: Context, day: LocalDate, chargeDate: LocalDate) =
        prefs(c).edit().putString("checkDay", day.toString()).putString("chargeDate", chargeDate.toString()).apply()
    fun setChargeDate(c: Context, d: LocalDate) = prefs(c).edit().putString("chargeDate", d.toString()).apply()
    fun setFee(c: Context, fee: Double) = prefs(c).edit().putString("fee", fee.toString()).apply()

    fun askedPermission(c: Context): Boolean = prefs(c).getBoolean("askedPermission", false)
    fun setAskedPermission(c: Context) = prefs(c).edit().putBoolean("askedPermission", true).apply()

    fun clearState(c: Context) =
        prefs(c).edit().remove("notifiedDay").remove("checkDay").remove("chargeDate").remove("fee").apply()

    private fun date(c: Context, key: String): LocalDate? =
        prefs(c).getString(key, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}

/** Для перепроверки разрешения на уведомления после возврата из системных настроек. */
object ReminderUi {
    var resumeTick by mutableIntStateOf(0)
}

object TopUpReminder {
    private const val TAG = "T2Reminder"
    private const val WORK = "t2lite_topup_reminder"
    const val CHANNEL_ID = "topup"
    private const val NOTIFICATION_ID = 3001
    /** После этого часа напоминания не показываются (не будить ночью). */
    private const val QUIET_FROM = 22

    private val mutex = Mutex()

    // ---- планирование ----

    /** Запланировать ежечасную проверку (или снять, если напоминания выключены). */
    fun schedule(context: Context, replace: Boolean = false) {
        val app = context.applicationContext
        if (!ReminderStore.get(app).enabled) { cancelWork(app); return }
        val request = PeriodicWorkRequestBuilder<TopUpReminderWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(
            WORK,
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private fun cancelWork(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        ReminderStore.save(app, ReminderStore.get(app).copy(enabled = enabled))
        if (enabled) schedule(app, replace = true) else { cancelWork(app); cancelNotification(app) }
    }

    fun setHour(context: Context, hour: Int) {
        val app = context.applicationContext
        ReminderStore.save(app, ReminderStore.get(app).copy(hour = hour))
    }

    // ---- хуки из приложения ----

    /** Приложение загрузило списание: запоминаем дату/сумму; если денег уже хватает — убираем напоминание. */
    fun onChargeLoaded(context: Context, charge: NextCharge) {
        val app = context.applicationContext
        charge.date?.let { ReminderStore.setChargeDate(app, it) }
        if (!charge.feeFromTariff) charge.fee?.let { ReminderStore.setFee(app, it) }
        val toPay = charge.toPay
        if (!charge.feeFromTariff && toPay != null && toPay <= 0.005) cancelNotification(app)
    }

    fun onLogout(context: Context) {
        val app = context.applicationContext
        cancelNotification(app)
        ReminderStore.clearState(app)
    }

    // ---- разрешение ----

    fun needsRuntimePermission(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun hasPermission(context: Context): Boolean =
        !needsRuntimePermission() || ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

    /** Можно ли сейчас показать уведомление: разрешение, уведомления приложения и канала включены. */
    fun canNotify(context: Context): Boolean {
        if (!hasPermission(context)) return false
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return false
        val ch = nm.getNotificationChannelCompat(CHANNEL_ID)
        return ch == null || ch.importance != NotificationManagerCompat.IMPORTANCE_NONE
    }

    // ---- проверка ----

    /**
     * Проверить, нужно ли напомнить, и показать уведомление.
     * force — кнопка «Проверить сейчас»: без ограничений по времени суток и «уже напоминали сегодня».
     * Возвращает пояснение для экрана/лога.
     */
    suspend fun check(context: Context, force: Boolean): String = mutex.withLock {
        val app = context.applicationContext
        val settings = ReminderStore.get(app)
        if (!settings.enabled && !force) return "Напоминания выключены"
        val now = LocalDateTime.now()
        val today = now.toLocalDate()

        if (!force) {
            if (now.hour < settings.hour || now.hour >= QUIET_FROM) return "Не время для напоминаний"
            if (ReminderStore.lastNotifiedDay(app) == today) return "Сегодня уже напомнили"
            val known = ReminderStore.lastChargeDate(app)
            if (known != null && ReminderStore.lastCheckDay(app) == today &&
                ChronoUnit.DAYS.between(today, known) > ReminderSettings.DAYS_BEFORE
            ) return "До списания больше ${ReminderSettings.DAYS_BEFORE} дней (проверено сегодня)"
        }

        val repo = T2Repository(app)
        if (!repo.isLoggedIn()) return "Не выполнен вход"

        val balance = when (val b = repo.balance()) {
            is T2Result.Ok -> b.data.value ?: return "Сервер не вернул баланс"
            is T2Result.Err -> return "Не удалось получить баланс: ${ru.myt2lite.data.Errors.text(b.message)}"
        }
        val charge = when (val c = repo.nextCharge(balance)) {
            is T2Result.Ok -> c.data
            is T2Result.Err -> return "Не удалось получить данные о списании"
        }
        val date = charge.date ?: return "Сервер не сообщил дату списания"
        ReminderStore.setChecked(app, today, date)

        // Сумма абонплаты: из rests (точная, со скидкой). Если пришла только из тарифа (там бывает
        // полная цена без скидки) — берём прежнюю точную сумму, а если её нет — не напоминаем наугад.
        val fee = if (!charge.feeFromTariff) {
            charge.fee?.also { ReminderStore.setFee(app, it) }
        } else {
            ReminderStore.lastFee(app) ?: WidgetSnapshotStore.get(app)?.fee
        } ?: return "Сумма списания пока неизвестна, проверю позже"
        val toPay = maxOf(0.0, fee - balance)

        val days = ChronoUnit.DAYS.between(today, date)
        val dateText = date.format(DateTimeFormatter.ofPattern("d MMMM", Locale("ru")))
        if (days < 0 || days > ReminderSettings.DAYS_BEFORE) {
            val from = date.minusDays(ReminderSettings.DAYS_BEFORE.toLong())
                .format(DateTimeFormatter.ofPattern("d MMMM", Locale("ru")))
            val need = if (toPay > 0.005) "сейчас не хватает ${money(toPay)}" else "сейчас денег хватает"
            return "Списание $dateText ($need). Напоминания — с $from, если денег не будет хватать."
        }
        if (toPay <= 0.005) {
            cancelNotification(app)
            return "Денег хватает: на счёте ${money(balance)}, списание ${money(fee)} — $dateText"
        }
        if (!canNotify(app)) return "Нужно пополнить на ${money(toPay)}, но уведомления запрещены"

        notify(app, toPay = toPay, fee = fee, balance = balance, date = date, days = days)
        ReminderStore.setNotifiedDay(app, today)
        Log.i(TAG, "напоминание: доплатить $toPay, списание $date (через $days дн.)")
        return "Напоминание показано: пополнить на ${money(toPay)}"
    }

    // ---- уведомление ----

    private fun ensureChannel(context: Context) {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName("Пополнение баланса")
            .setDescription("Напоминания за ${ReminderSettings.DAYS_BEFORE} дня до списания абонплаты, если денег не хватает")
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    /** Создать канал заранее, чтобы его можно было настроить в системе ещё до первого напоминания. */
    fun prepare(context: Context) = ensureChannel(context.applicationContext)

    @SuppressLint("MissingPermission")
    private fun notify(context: Context, toPay: Double, fee: Double, balance: Double, date: LocalDate, days: Long) {
        ensureChannel(context)
        val dateText = date.format(DateTimeFormatter.ofPattern("d MMMM", Locale("ru")))
        val whenText = when (days) {
            0L -> "Сегодня"
            1L -> "Завтра, $dateText,"
            else -> "Через $days ${daysWord(days)}, $dateText,"
        }
        val title = "Пополните баланс на ${money(toPay)}"
        val text = "$whenText списание абонплаты ${money(fee)}"
        val big = "$text. Сейчас на счёте ${money(balance)}."

        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // До конца суток: завтра будет новое напоминание со свежими суммами.
        val untilMidnight = java.time.Duration.between(
            LocalDateTime.now(), LocalDate.now().plusDays(1).atStartOfDay()
        ).toMillis().coerceAtLeast(60_000L)

        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_topup)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(big))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(untilMidnight)
            .build()
        if (!hasPermission(context)) return
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
        } catch (e: SecurityException) {
            Log.w(TAG, "нет разрешения на уведомления: ${e.message}")
        }
    }

    fun cancelNotification(context: Context) {
        NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID)
    }

    private fun daysWord(n: Long): String {
        val d10 = (n % 10).toInt()
        val d100 = (n % 100).toInt()
        return when {
            d10 == 1 && d100 != 11 -> "день"
            d10 in 2..4 && d100 !in 12..14 -> "дня"
            else -> "дней"
        }
    }

    fun money(v: Double): String = String.format(Locale("ru"), "%.2f ₽", v)
}

/** Ежечасная фоновая проверка (WorkManager). */
class TopUpReminderWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): ListenableWorker.Result {
        val r = runCatching { TopUpReminder.check(applicationContext, force = false) }
            .getOrElse { "ошибка: ${it.message}" }
        Log.i("T2Reminder", "проверка: $r")
        return ListenableWorker.Result.success()
    }
}
