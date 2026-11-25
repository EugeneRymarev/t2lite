package ru.myt2lite.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Экран входа: номер → код из SMS. После входа MainActivity показывает вкладки (MainTabs.kt). */
@Composable
fun AppRoot(vm: AuthViewModel) {
    val state = vm.state.collectAsStateValue()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("t2 Lite", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(32.dp))

        when (state.step) {
            Step.PHONE -> PhoneStep(vm, state)
            Step.CODE -> CodeStep(vm, state)
            Step.BALANCE -> Unit // показывается в MainTabs
        }

        if (state.error != null) {
            Spacer(Modifier.height(16.dp))
            ErrorText(state.error, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PhoneStep(vm: AuthViewModel, state: UiState) {
    Text("Введите номер телефона")
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = state.phone,
        onValueChange = vm::onPhoneChange,
        label = { Text("+7 ...") },
        singleLine = true,
        enabled = !state.loading,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(16.dp))
    PrimaryButton("Получить код", state.loading) { vm.sendCode() }
}

@Composable
private fun CodeStep(vm: AuthViewModel, state: UiState) {
    Text("Код из SMS для ${formatPhone(state.phone)}")
    if (state.info != null) {
        Spacer(Modifier.height(8.dp))
        Text(state.info, style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = state.code,
        onValueChange = vm::onCodeChange,
        label = { Text("Код из SMS") },
        singleLine = true,
        enabled = !state.loading,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(16.dp))
    PrimaryButton("Войти", state.loading) { vm.login() }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = { vm.backToPhone() }, enabled = !state.loading) {
        Text("Изменить номер")
    }
}

@Composable
private fun PrimaryButton(text: String, loading: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.height(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
        } else {
            Text(text)
        }
    }
}
