package ru.myt2lite.reminder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Настройка напоминаний о пополнении (на экране баланса, под «Следующим списанием»):
 * вкл/выкл, с какого часа напоминать, статус разрешения на уведомления, «Проверить сейчас».
 */
@Composable
fun TopUpReminderCard() {
    val context = LocalContext.current
    val app = context.applicationContext
    val settings by ReminderStore.flow(app).collectAsState()
    val scope = rememberCoroutineScope()

    var canNotify by remember { mutableStateOf(TopUpReminder.canNotify(app)) }
    var status by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var hourMenu by remember { mutableStateOf(false) }
    var askedAt by remember { mutableLongStateOf(0L) }

    val tick = ReminderUi.resumeTick
    LaunchedEffect(tick) { canNotify = TopUpReminder.canNotify(app) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        canNotify = TopUpReminder.canNotify(app)
        // Система не показала диалог (запрещено навсегда) — ответ приходит сразу; ведём в настройки.
        if (!granted && askedAt > 0 && SystemClock.elapsedRealtime() - askedAt < 500) openNotificationSettings(context)
        askedAt = 0L
    }

    // Напоминания включены по умолчанию — один раз спрашиваем разрешение (Android 13+).
    LaunchedEffect(settings.enabled) {
        TopUpReminder.prepare(app)
        if (settings.enabled && !TopUpReminder.hasPermission(app) && !ReminderStore.askedPermission(app)) {
            ReminderStore.setAskedPermission(app)
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = settings.enabled,
                        role = Role.Switch,
                        onValueChange = { on ->
                            status = null
                            TopUpReminder.setEnabled(app, on)
                        }
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Напоминать о пополнении", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Каждый день за ${ReminderSettings.DAYS_BEFORE} дня до списания, если денег на счёте не хватает",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = settings.enabled, onCheckedChange = null)
            }

            if (settings.enabled) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Не раньше", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Box {
                        TextButton(onClick = { hourMenu = true }) { Text("%d:00 ▾".format(settings.hour)) }
                        DropdownMenu(expanded = hourMenu, onDismissRequest = { hourMenu = false }) {
                            ReminderSettings.HOURS.forEach { h ->
                                DropdownMenuItem(
                                    text = { Text("%d:00".format(h)) },
                                    onClick = { hourMenu = false; TopUpReminder.setHour(app, h) }
                                )
                            }
                        }
                    }
                }

                if (!canNotify) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Уведомления для t2 Lite запрещены — напоминания не придут",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(onClick = {
                        if (!TopUpReminder.hasPermission(app)) {
                            askedAt = SystemClock.elapsedRealtime()
                            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            openNotificationSettings(context)
                        }
                    }) { Text("Разрешить уведомления") }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        enabled = !checking,
                        onClick = {
                            checking = true
                            scope.launch {
                                status = TopUpReminder.check(app, force = true)
                                canNotify = TopUpReminder.canNotify(app)
                                checking = false
                            }
                        }
                    ) { Text("Проверить сейчас") }
                    if (checking) CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                }
                status?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
