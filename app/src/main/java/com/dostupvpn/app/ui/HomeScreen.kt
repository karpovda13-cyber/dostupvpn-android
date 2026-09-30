package com.dostupvpn.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.dostupvpn.app.data.UiPrefs
import com.dostupvpn.app.diag.AppError
import com.dostupvpn.app.diag.Report
import com.dostupvpn.app.diag.canReport
import com.dostupvpn.app.net.Me
import com.dostupvpn.app.vpn.VpnController
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter

enum class SubLevel { OK, SOON, EXPIRED }

/** Подписка, подготовленная для показа. «Скоро» — когда осталось 7 дней и меньше. */
data class SubUi(val level: SubLevel, val dateText: String, val daysLeft: Int, val deviceName: String, val botUrl: String)

fun subscriptionUi(me: Me): SubUi = SubUi(
    level = when {
        !me.active -> SubLevel.EXPIRED
        me.daysLeft <= 7 -> SubLevel.SOON
        else -> SubLevel.OK
    },
    dateText = formatDate(me.expireDate),
    daysLeft = me.daysLeft,
    deviceName = me.deviceName,
    botUrl = me.botUrl,
)

@Composable
fun HomeScreen(
    me: Me,
    vpn: VpnController.State,
    lastSessionSec: Long,
    failStreak: Int,
    busy: Boolean,
    error: AppError?,
    dark: Boolean,
    themeMode: ThemeMode,
    onToggleVpn: (Boolean) -> Unit,
    onToggleTheme: () -> Unit,
    onSetTheme: (ThemeMode) -> Unit,
    onLogout: () -> Unit,
    onDismissError: () -> Unit,
    buildReport: () -> String,
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    var showSubscription by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }

    // Подсказка про работу в фоне: только во время подключения, один раз, закрывается навсегда.
    val uiPrefs = remember { UiPrefs(context) }
    var batteryHintDismissed by remember { mutableStateOf(uiPrefs.batteryHintDismissed()) }
    var unrestricted by remember { mutableStateOf(Background.isUnrestricted(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { unrestricted = Background.isUnrestricted(context) }

    val sub = subscriptionUi(me)
    val mode = when (vpn) {
        VpnController.State.Disconnected -> PowerMode.OFF
        is VpnController.State.Connecting -> PowerMode.CONNECTING
        is VpnController.State.Connected -> PowerMode.ON
        is VpnController.State.Failed -> PowerMode.ERROR
    }
    val shownError = (vpn as? VpnController.State.Failed)?.error ?: error
    val statusText = when (vpn) {
        VpnController.State.Disconnected -> "Отключен"
        is VpnController.State.Connecting -> "Подключение…"
        is VpnController.State.Connected -> "Подключен"
        is VpnController.State.Failed -> "Ошибка подключения"
    }
    val powerDescription = when (mode) {
        PowerMode.ON -> "Отключить VPN"
        PowerMode.CONNECTING -> "Идёт подключение"
        else -> "Включить VPN"
    }

    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                ThemeToggle(dark = dark, onToggle = onToggleTheme)
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .semantics { contentDescription = "Настройки" }
                        .clickable(role = Role.Button) { showSettings = true },
                    contentAlignment = Alignment.Center,
                ) { GearIcon(p.accent, 28.dp) }
            }

            Spacer(Modifier.height(24.dp))
            PowerButton(
                mode = mode,
                enabled = me.active && !busy,
                description = powerDescription,
                onClick = { onToggleVpn(vpn !is VpnController.State.Connected) },
            )
            Spacer(Modifier.height(12.dp))
            Text("DostupVPN", color = lerp(p.text, p.accent, 0.35f), fontSize = 36.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.height(8.dp))
            StatusRow(mode, statusText)
            if (vpn is VpnController.State.Connecting) {
                Spacer(Modifier.height(4.dp))
                Text(vpn.stage, color = p.textDim, fontSize = 13.sp)
            }

            Spacer(Modifier.height(18.dp))
            when {
                shownError != null -> ErrorCard(
                    error = shownError,
                    showReport = shownError.canReport(failStreak),
                    onReport = { Report.share(context, buildReport()) },
                    // «Повторить» — только для сбоя подключения (не для ошибки загрузки данных).
                    onRetry = if (vpn is VpnController.State.Failed && me.active) ({ onToggleVpn(true) }) else null,
                    onDismiss = onDismissError,
                )
                vpn is VpnController.State.Connected -> TimerPill(rememberElapsedSec(vpn.connectedAt), active = true)
                vpn is VpnController.State.Disconnected && lastSessionSec > 0L -> TimerPill(lastSessionSec, active = false)
            }

            if (vpn is VpnController.State.Connected && !unrestricted && !batteryHintDismissed) {
                Spacer(Modifier.height(16.dp))
                BatteryHint(
                    onAllow = { Background.request(context) },
                    onDismiss = { uiPrefs.dismissBatteryHint(); batteryHintDismissed = true },
                )
            }

            Spacer(Modifier.height(16.dp))
            SubscriptionCard(sub, onClick = { showSubscription = true }, onRenew = { openUrl(context, sub.botUrl) })

            Spacer(Modifier.height(20.dp))
            OutlinePillButton(
                text = "Выход",
                icon = { ExitIcon(p.accent, 22.dp) },
                onClick = { confirmLogout = true },
                enabled = !busy,
            )
            Spacer(Modifier.height(12.dp))
        }
    }

    if (showSubscription) {
        SubscriptionSheet(sub, onDismiss = { showSubscription = false }, onOpenBot = { openUrl(context, sub.botUrl) })
    }
    if (showSettings) {
        SettingsSheet(
            themeMode = themeMode,
            vpn = vpn,
            onSetTheme = onSetTheme,
            buildReport = buildReport,
            onDismiss = { showSettings = false },
        )
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            containerColor = p.sheet,
            title = { Text("Выйти из аккаунта?", color = p.text) },
            text = { Text("Слот освободится, а для нового входа понадобится новый токен из бота.", color = p.textDim) },
            confirmButton = {
                TextButton(onClick = { confirmLogout = false; onLogout() }) { Text("Выйти", color = p.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Отмена", color = p.accent) } },
        )
    }
}

/** Короткая подсказка: без неё Android может «усыплять» VPN, когда телефон долго заблокирован. */
@Composable
private fun BatteryHint(onAllow: () -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Чтобы соединение не обрывалось, пока телефон заблокирован, разрешите приложению " +
                        "работать без ограничений батареи.",
                    color = p.textDim, fontSize = 13.sp,
                )
                TextButton(onClick = onAllow) { Text("Разрешить", color = p.accent) }
            }
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .semantics { contentDescription = "Скрыть подсказку" }
                    .clickable(role = Role.Button, onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) { CloseIcon(p.textDim, 14.dp) }
        }
    }
}

/** Ненавязчивая карточка подписки: тихая, пока всё в порядке; цвет и текст меняются, только когда пора продлять. */
@Composable
private fun SubscriptionCard(sub: SubUi, onClick: () -> Unit, onRenew: () -> Unit) {
    val p = LocalPalette.current
    val accent = when (sub.level) {
        SubLevel.OK -> p.textDim
        SubLevel.SOON -> p.warn
        SubLevel.EXPIRED -> p.danger
    }
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        border = if (sub.level == SubLevel.OK) null else accent.copy(alpha = 0.6f),
        tint = if (sub.level == SubLevel.OK) null else accent,
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CalendarIcon(accent, 22.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (sub.level == SubLevel.EXPIRED) "Подписка не активна" else "Подписка до ${sub.dateText}",
                    color = p.text, fontSize = 16.sp,
                )
                Text(
                    when (sub.level) {
                        SubLevel.OK -> "осталось ${sub.daysLeft} ${daysWord(sub.daysLeft)}"
                        SubLevel.SOON -> "Скоро закончится · осталось ${sub.daysLeft} ${daysWord(sub.daysLeft)}"
                        SubLevel.EXPIRED -> "Продлите, чтобы включить VPN"
                    },
                    color = if (sub.level == SubLevel.OK) p.textDim else accent, fontSize = 13.sp,
                )
            }
            if (sub.level == SubLevel.EXPIRED) {
                TextButton(onClick = onRenew) { Text("Продлить", color = p.accent) }
            } else {
                ChevronIcon(p.textDim, 18.dp)
            }
        }
    }
}

@Composable
private fun rememberElapsedSec(connectedAt: Long): Long {
    var now by remember(connectedAt) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(connectedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    return ((now - connectedAt) / 1000L).coerceAtLeast(0L)
}

@Composable
fun OfflineScreen(error: AppError, onRetry: () -> Unit, buildReport: () -> String) {
    val p = LocalPalette.current
    val context = LocalContext.current
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 460.dp).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ErrorCard(error = error, showReport = false, onReport = {}, onRetry = null, onDismiss = {})
            OutlinePillButton(text = "Повторить", icon = { PowerIcon(p.accent, 22.dp) }, onClick = onRetry)
        }
    }
}

internal fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        // нет приложения для открытия ссылки — ничего не делаем
    }
}

internal fun formatDate(iso: String?): String {
    if (iso == null) return "—"
    return try {
        LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
    } catch (_: Exception) {
        iso
    }
}

internal fun daysWord(n: Int): String {
    val m100 = n % 100
    val m10 = n % 10
    return when {
        m100 in 11..14 -> "дней"
        m10 == 1 -> "день"
        m10 in 2..4 -> "дня"
        else -> "дней"
    }
}
