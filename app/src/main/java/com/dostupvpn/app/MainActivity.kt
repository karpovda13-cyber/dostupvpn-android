package com.dostupvpn.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dostupvpn.app.ui.AppBackground
import com.dostupvpn.app.ui.DostupTheme
import com.dostupvpn.app.ui.HomeScreen
import com.dostupvpn.app.ui.LocalPalette
import com.dostupvpn.app.ui.LoginScreen
import com.dostupvpn.app.ui.OfflineScreen
import com.dostupvpn.app.ui.ThemeMode
import com.dostupvpn.app.ui.resolveDark

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: AppViewModel = viewModel()
            val mode by vm.theme.collectAsStateWithLifecycle()
            val dark = resolveDark(mode)

            // Иконки системных панелей должны соответствовать теме приложения, а не только системной.
            DisposableEffect(dark) {
                val transparent = android.graphics.Color.TRANSPARENT
                val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }

            DostupTheme(dark) {
                AppBackground { AppRoot(vm, dark, mode) }
            }
        }
    }
}

@Composable
private fun AppRoot(vm: AppViewModel, dark: Boolean, themeMode: ThemeMode) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingEnable by remember { mutableStateOf(false) }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (pendingEnable && result.resultCode == Activity.RESULT_OK) vm.toggleVpn(true)
        pendingEnable = false
    }

    fun requestVpn() {
        val permission = vm.vpnPermissionIntent()
        if (permission != null) {
            pendingEnable = true
            vpnPermissionLauncher.launch(permission)
        } else {
            vm.toggleVpn(true)
        }
    }

    // Android 13+: без этого разрешения уведомление VPN (а с ним и кнопка «Отключить») не показывается.
    // Отказ не мешает подключению — просто продолжаем.
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { requestVpn() }

    fun requestToggle(enable: Boolean) {
        if (!enable) {
            vm.toggleVpn(false)
            return
        }
        val needNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needNotificationPermission) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else requestVpn()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.onStart() }

    when (val s = ui.screen) {
        Screen.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = LocalPalette.current.accent)
        }
        Screen.Login -> LoginScreen(
            busy = ui.busy,
            error = ui.error ?: ui.crash,
            onLogin = vm::login,
            onDismissError = vm::dismissError,
            buildReport = vm::buildReport,
        )
        is Screen.Home -> HomeScreen(
            me = s.me,
            vpn = ui.vpn,
            lastSessionSec = ui.lastSessionSec,
            failStreak = ui.failStreak,
            busy = ui.busy,
            error = ui.error ?: ui.crash,
            dark = dark,
            themeMode = themeMode,
            onToggleVpn = ::requestToggle,
            onToggleTheme = { vm.setThemeMode(if (dark) ThemeMode.LIGHT else ThemeMode.DARK) },
            onSetTheme = vm::setThemeMode,
            onLogout = vm::logout,
            onDismissError = vm::dismissError,
            buildReport = vm::buildReport,
        )
        is Screen.Offline -> OfflineScreen(error = s.error, onRetry = vm::refresh, buildReport = vm::buildReport)
    }
}
