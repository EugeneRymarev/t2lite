package ru.myt2lite.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.myt2lite.data.RestsSource
import ru.myt2lite.data.T2Repository
import java.util.concurrent.TimeUnit
import ru.myt2lite.data.Result as T2Result

/**
 * Загрузка данных для виджета: баланс, следующее списание (data/NextCharge.kt) и
 * остатки (data/Rests.kt). Токен — через AuthManager (внутри T2Repository/RestsSource),
 * так что фоновое обновление само продлевает сессию по refresh_token.
 */
object WidgetRefresher {
    private const val TAG = "T2Widget"
    private val mutex = Mutex()

    suspend fun refresh(context: Context) = mutex.withLock {
        val app = context.applicationContext
        val prev = WidgetSnapshotStore.get(app)
        WidgetSnapshotStore.update(app) { it.copy(refreshing = true) }
        redraw(app)
        val next = try {
            load(app, prev)
        } catch (e: Exception) {
            Log.w(TAG, "обновление виджета: ${e.message}")
            (prev ?: WidgetSnapshot()).copy(error = "не удалось обновить")
        }
        WidgetSnapshotStore.save(app, next.copy(refreshing = false))
        redraw(app)
    }

    suspend fun redraw(context: Context) {
        runCatching { T2Widget().updateAll(context.applicationContext) }
            .onFailure { Log.w(TAG, "updateAll: ${it.message}") }
    }

    private suspend fun load(app: Context, prev: WidgetSnapshot?): WidgetSnapshot {
        val repo = T2Repository(app)
        val now = System.currentTimeMillis()
        if (!repo.isLoggedIn()) return WidgetSnapshot(loggedIn = false, updatedAt = now)

        // Баланс параллельно с остатками; списание (оно тоже читает rests) — после, чтобы не дёргать rests дважды разом.
        val (balanceRes, restsRes) = coroutineScope {
            val b = async { repo.balance() }
            val r = async { RestsSource(app).load() }
            b.await() to r.await()
        }
        val failed = mutableListOf<String>()

        val balance = when (balanceRes) {
            is T2Result.Ok -> balanceRes.data.value
            is T2Result.Err -> { failed += "баланс"; prev?.balance }
        }

        var toPay = prev?.toPay
        var fee = prev?.fee
        var chargeDate = prev?.chargeDate
        val chargeRes = repo.nextCharge(balance)
        when (chargeRes) {
            is T2Result.Ok -> {
                val c = chargeRes.data
                val prevFee = prev?.fee
                if (c.feeFromTariff && prevFee != null && balance != null) {
                    // rests не ответил, сумма из тарифа — там бывает полная цена без скидки.
                    // Оставляем прежнюю абонплату и пересчитываем доплату от свежего баланса.
                    fee = prevFee
                    toPay = maxOf(0.0, prevFee - balance)
                } else {
                    toPay = c.toPay ?: toPay
                    fee = c.fee ?: fee
                }
                chargeDate = c.date?.toString() ?: chargeDate
            }
            is T2Result.Err -> failed += "списание"
        }

        val rests = when (restsRes) {
            is T2Result.Ok -> restsRes.data.groups.map { g ->
                WidgetRest(
                    kind = g.kind.name,
                    title = g.kind.title,
                    remain = g.remain,
                    limit = g.limit,
                    unit = g.unit,
                    unlimited = g.unlimited
                )
            }
            is T2Result.Err -> { failed += "остатки"; prev?.rests }
        }

        // Пока шли запросы, сессия могла закончиться (refresh_token отклонён) — тогда просим войти.
        if (!repo.isLoggedIn()) return WidgetSnapshot(loggedIn = false, updatedAt = now)

        val allFailed = balanceRes is T2Result.Err && chargeRes is T2Result.Err && restsRes is T2Result.Err
        return WidgetSnapshot(
            loggedIn = true,
            balance = balance,
            toPay = toPay,
            fee = fee,
            chargeDate = chargeDate,
            rests = rests,
            // Если не загрузилось ничего — время оставляем прежним, чтобы было видно, насколько данные старые.
            updatedAt = if (allFailed) prev?.updatedAt else now,
            error = if (failed.isEmpty()) null else "не обновлено: " + failed.joinToString(", ")
        )
    }
}

/** Фоновая задача WorkManager: обновить данные и перерисовать виджеты. */
class WidgetRefreshWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): ListenableWorker.Result {
        WidgetRefresher.refresh(applicationContext)
        return ListenableWorker.Result.success()
    }
}

/** Планирование обновлений виджета. */
object WidgetUpdater {
    private const val PERIODIC = "t2lite_widget_periodic"
    private const val ONCE = "t2lite_widget_once"
    /** Данные старше этого считаются устаревшими (при открытии приложения/настроек обновим). */
    private const val STALE_MS = 15 * 60_000L

    /** Периодическое обновление (только при наличии сети). replace=true — после смены интервала. */
    fun schedule(context: Context, replace: Boolean = false) {
        val minutes = WidgetSettingsStore.get(context).intervalMinutes.coerceAtLeast(15).toLong()
        val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(minutes, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC,
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun cancel(context: Context) {
        val wm = WorkManager.getInstance(context.applicationContext)
        wm.cancelUniqueWork(PERIODIC)
        wm.cancelUniqueWork(ONCE)
    }

    /** Обновить сейчас (кнопка ↻, настройки). Без ограничения по сети — чтобы сразу показать ошибку, если её нет. */
    fun refreshNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>().build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP, request)
    }

    fun isStale(context: Context): Boolean {
        val at = WidgetSnapshotStore.get(context)?.updatedAt ?: return true
        return System.currentTimeMillis() - at > STALE_MS
    }

    fun refreshIfStale(context: Context) {
        if (isStale(context)) refreshNow(context)
    }

    fun hasWidgets(context: Context): Boolean = runCatching {
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, T2WidgetReceiver::class.java))
            .isNotEmpty()
    }.getOrDefault(false)

    /** Приложение загрузило баланс (после входа или при запуске) — подтянуть виджет, если он устарел или просит войти. */
    fun onAppRefreshed(context: Context) {
        if (!hasWidgets(context)) return
        val s = WidgetSnapshotStore.get(context)
        if (s?.loggedIn != true || isStale(context)) refreshNow(context)
    }

    /** Выход из аккаунта — убрать данные с виджета. */
    fun onLogout(context: Context) {
        if (hasWidgets(context)) refreshNow(context)
    }
}
