package com.dostupvpn.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dostupvpn.app.net.Me
import com.dostupvpn.app.vpn.VpnController
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DostupTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                        AppRoot()
                    }
                }
            }
        }
    }
}

@Composable
private fun DostupTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun AppRoot(vm: AppViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var pendingEnable by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (pendingEnable && result.resultCode == Activity.RESULT_OK) vm.toggleVpn(true)
        pendingEnable = false
    }

    fun requestToggle(enable: Boolean) {
        if (!enable) {
            vm.toggleVpn(false)
            return
        }
        val permission = vm.vpnPermissionIntent()
        if (permission != null) {
            pendingEnable = true
            permissionLauncher.launch(permission)
        } else {
            vm.toggleVpn(true)
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.onStart() }

    when (val s = ui.screen) {
        Screen.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        Screen.Login -> LoginScreen(busy = ui.busy, error = ui.error ?: ui.crash, onLogin = vm::login)
        is Screen.Home -> HomeScreen(
            me = s.me,
            vpn = ui.vpn,
            busy = ui.busy,
            error = ui.error ?: ui.crash,
            onToggleVpn = ::requestToggle,
            onLogout = vm::logout,
        )
        is Screen.Offline -> OfflineScreen(message = s.message, onRetry = vm::refresh)
    }
}

@Composable
private fun LoginScreen(busy: Boolean, error: String?, onLogin: (String) -> Unit) {
    val context = LocalContext.current
    var token by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("DostupVPN", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Введите токен из бота: «📱 Приложение» → «Выпустить токен».",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = token,
            onValueChange = { token = it.uppercase() },
            label = { Text("Токен") },
            placeholder = { Text("XXXX-XXXX-XXXX-XXXX") },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { if (token.isNotBlank() && !busy) onLogin(token) }),
            modifier = Modifier.fillMaxWidth(),
        )
        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onLogin(token) },
            enabled = !busy && token.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text("Войти")
        }
        TextButton(onClick = { openUrl(context, ApiConfig.BOT_URL) }) { Text("Открыть бота") }
    }
}

@Composable
private fun HomeScreen(
    me: Me,
    vpn: VpnController.State,
    busy: Boolean,
    error: String?,
    onToggleVpn: (Boolean) -> Unit,
    onLogout: () -> Unit,
) {
    val context = LocalContext.current
    var confirmLogout by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("DostupVPN", style = MaterialTheme.typography.headlineMedium)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (me.active) {
                    Text("Подписка активна", style = MaterialTheme.typography.titleMedium)
                    Text("до ${formatDate(me.expireDate)} · осталось ${me.daysLeft} ${daysWord(me.daysLeft)}")
                } else {
                    Text(
                        "Подписка не активна",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text("Продлите подписку в боте, чтобы включить VPN.")
                    Button(onClick = { openUrl(context, me.botUrl) }) { Text("Открыть бота") }
                }
                Text("Устройство: ${me.deviceName}", style = MaterialTheme.typography.bodySmall)
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("VPN", style = MaterialTheme.typography.titleMedium)
                    Text(vpnStatusText(vpn), style = MaterialTheme.typography.bodySmall)
                    if (vpn is VpnController.State.Connected) {
                        ConnectedTimeText(vpn.connectedAt)
                    }
                }
                if (vpn is VpnController.State.Connecting) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Switch(
                        checked = vpn is VpnController.State.Connected,
                        onCheckedChange = { onToggleVpn(it) },
                        enabled = me.active,
                    )
                }
            }
        }

        if (error != null) Text(error, color = MaterialTheme.colorScheme.error)

        OutlinedButton(
            onClick = { confirmLogout = true },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Выйти") }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Выйти из аккаунта?") },
            text = { Text("Слот освободится, а для нового входа понадобится новый токен из бота.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    onLogout()
                }) { Text("Выйти") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun OfflineScreen(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Повторить") }
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Exception) {
        // нет приложения для открытия ссылки — ничего не делаем
    }
}

private fun formatDate(iso: String?): String {
    if (iso == null) return "—"
    return try {
        LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
    } catch (_: Exception) {
        iso
    }
}

@Composable
private fun ConnectedTimeText(connectedAt: Long) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(connectedAt) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    val elapsed = ((now - connectedAt).coerceAtLeast(0L)) / 1000L
    Text("Подключено ${formatDuration(elapsed)}", style = MaterialTheme.typography.bodySmall)
}

private fun formatDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

private fun vpnStatusText(vpn: VpnController.State): String = when (vpn) {
    VpnController.State.Disconnected -> "Отключено"
    VpnController.State.Connecting -> "Подключение…"
    is VpnController.State.Connected -> "Включено"
    is VpnController.State.Failed -> vpn.message
}

private fun daysWord(n: Int): String {
    val m100 = n % 100
    val m10 = n % 10
    return when {
        m100 in 11..14 -> "дней"
        m10 == 1 -> "день"
        m10 in 2..4 -> "дня"
        else -> "дней"
    }
}
