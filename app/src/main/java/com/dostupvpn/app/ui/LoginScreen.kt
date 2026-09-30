package com.dostupvpn.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dostupvpn.app.ApiConfig
import com.dostupvpn.app.diag.AppError
import com.dostupvpn.app.diag.Report
import com.dostupvpn.app.diag.canReport

@Composable
fun LoginScreen(
    busy: Boolean,
    error: AppError?,
    onLogin: (String) -> Unit,
    onDismissError: () -> Unit,
    buildReport: () -> String,
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    var token by rememberSaveable { mutableStateOf("") }

    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(84.dp).border(3.dp, p.ringA, CircleShape),
                contentAlignment = Alignment.Center,
            ) { PowerIcon(p.accent, 40.dp) }
            Spacer(Modifier.height(16.dp))
            Text("DostupVPN", color = lerp(p.text, p.accent, 0.35f), fontSize = 34.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.height(8.dp))
            Text(
                "Введите токен из бота: «📱 Приложение» → «Выпустить токен».",
                color = p.textDim, fontSize = 15.sp, textAlign = TextAlign.Center,
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
                ErrorCard(
                    error = error,
                    // Ошибки входа исправляются самим пользователем; отчёт — только после сбоя приложения.
                    showReport = error.canReport(0),
                    onReport = { Report.share(context, buildReport()) },
                    onRetry = null,
                    onDismiss = onDismissError,
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onLogin(token) },
                enabled = !busy && token.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text("Войти")
            }
            TextButton(onClick = { openUrl(context, ApiConfig.BOT_URL) }) { Text("Открыть бота", color = p.accent) }
        }
    }
}
