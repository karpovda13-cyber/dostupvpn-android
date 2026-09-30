package com.dostupvpn.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dostupvpn.app.BuildConfig
import com.dostupvpn.app.diag.Report
import com.dostupvpn.app.vpn.AdminRulesStore
import com.dostupvpn.app.vpn.VpnController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionSheet(sub: SubUi, onDismiss: () -> Unit, onOpenBot: () -> Unit) {
    val p = LocalPalette.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.sheet,
        contentColor = p.text,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text("Подписка", color = p.text, fontSize = 22.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            val (label, color) = when (sub.level) {
                SubLevel.OK -> "Активна" to p.ok
                SubLevel.SOON -> "Скоро закончится" to p.warn
                SubLevel.EXPIRED -> "Не активна" to p.danger
            }
            Text(label, color = color, fontSize = 15.sp)
            Spacer(Modifier.height(16.dp))
            InfoRow("Действует до", if (sub.level == SubLevel.EXPIRED) "закончилась ${sub.dateText}" else sub.dateText)
            if (sub.level != SubLevel.EXPIRED) InfoRow("Осталось", "${sub.daysLeft} ${daysWord(sub.daysLeft)}")
            InfoRow("Это устройство", sub.deviceName)
            Spacer(Modifier.height(8.dp))
            Text("Продление и оплата — в Telegram-боте.", color = p.textDim, fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = onOpenBot, modifier = Modifier.fillMaxWidth()) {
                Text(if (sub.level == SubLevel.OK) "Открыть бота" else "Продлить в боте")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    themeMode: ThemeMode,
    vpn: VpnController.State,
    onSetTheme: (ThemeMode) -> Unit,
    buildReport: () -> String,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.sheet,
        contentColor = p.text,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            Text("Настройки", color = p.text, fontSize = 22.sp, fontWeight = FontWeight.Medium)

            Section("Тема")
            ThemeSegmented(themeMode, onSetTheme)
            Spacer(Modifier.height(4.dp))
            Text(
                "«Авто» — как в системе, в том числе по расписанию тёмной темы Android.",
                color = p.textDim, fontSize = 12.sp,
            )

            Section("Соединение")
            InfoRow(
                "Состояние",
                when (vpn) {
                    VpnController.State.Disconnected -> "отключено"
                    is VpnController.State.Connecting -> vpn.stage
                    is VpnController.State.Connected -> "подключено"
                    is VpnController.State.Failed -> "ошибка ${vpn.error.code}"
                },
            )
            InfoRow("Протокол", "VLESS + Reality")
            InfoRow("Маршрутизация", "РФ напрямую, остальное через VPN")
            InfoRow("Правила сервиса", "версия ${AdminRulesStore.version(context)}")

            Section("Работа в фоне")
            val unrestricted = Background.isUnrestricted(context)
            InfoRow("Ограничения батареи", if (unrestricted) "сняты" else "действуют")
            if (!unrestricted) {
                Text(
                    "Из-за них Android может отключать VPN, когда телефон долго заблокирован (например, ночью).",
                    color = p.textDim, fontSize = 13.sp,
                )
                TextButton(onClick = { Background.request(context) }) { Text("Разрешить работу в фоне", color = p.accent) }
            }

            Section("Обратная связь")
            Text(
                "Если что-то не работает, отправьте отчёт — так мы быстрее найдём причину. В нём только " +
                    "технические сведения: версия приложения, код ошибки и журнал подключения. " +
                    "Токены, пароли и адреса посещённых сайтов в отчёт не попадают.",
                color = p.textDim, fontSize = 13.sp,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { Report.share(context, buildReport()) }) { Text("Отправить отчёт") }
                TextButton(onClick = { Report.copy(context, buildReport()) }) { Text("Скопировать", color = p.accent) }
            }

            Section("О приложении")
            InfoRow("Версия", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    val p = LocalPalette.current
    Spacer(Modifier.height(22.dp))
    Text(title.uppercase(), color = p.textDim, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun InfoRow(label: String, value: String) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = p.textDim, fontSize = 15.sp, modifier = Modifier.padding(end = 16.dp))
        Text(value, color = p.text, fontSize = 15.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier)
    }
}

@Composable
private fun ThemeSegmented(mode: ThemeMode, onSet: (ThemeMode) -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.accent.copy(alpha = 0.08f))
            .border(1.dp, p.border, shape)
            .padding(4.dp),
    ) {
        listOf(ThemeMode.AUTO to "Авто", ThemeMode.LIGHT to "Светлая", ThemeMode.DARK to "Тёмная").forEach { (m, label) ->
            val selected = m == mode
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) p.accent else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onSet(m) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (selected) (if (p.dark) Color(0xFF03122E) else Color.White) else p.text,
                    fontSize = 14.sp,
                )
            }
        }
    }
}
