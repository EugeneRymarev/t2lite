package ru.myt2lite

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import ru.myt2lite.reminder.ReminderUi
import ru.myt2lite.reminder.TopUpReminder
import ru.myt2lite.ui.AppRoot
import ru.myt2lite.ui.AuthViewModel
import ru.myt2lite.ui.MainTabs
import ru.myt2lite.ui.Step
import ru.myt2lite.ui.theme.T2LiteTheme

class MainActivity : ComponentActivity() {

    private val vm: AuthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        TopUpReminder.schedule(this) // ежечасная проверка «пора пополнить» (если напоминания включены)
        setContent {
            T2LiteTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val auth by vm.state.collectAsState()
                    if (auth.step == Step.BALANCE) {
                        // После входа — вкладки внизу: Главная, Маркет, Связь, Настройки (ui/MainTabs.kt).
                        MainTabs(vm)
                    } else {
                        // Вход: номер → код из SMS (ui/Screens.kt).
                        AppRoot(vm)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ReminderUi.resumeTick++ // перепроверить разрешение на уведомления после возврата из настроек
    }
}
