package com.dostupvpn.app.diag

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import com.dostupvpn.app.BuildConfig
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Отчёт для поддержки: параметры, последняя ошибка, события и хвост журнала ядра. */
object Report {

    fun build(context: Context, vpnState: String, error: AppError?, subscription: String?): String {
        fun tailOf(name: String, lines: Int): String = runCatching {
            File(context.filesDir, name).readLines().takeLast(lines).joinToString("\n")
        }.getOrDefault("").ifBlank { "(пусто)" }

        val now = ZonedDateTime.now(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss (xxx)"))
        val text = buildString {
            appendLine("DostupVPN — отчёт для поддержки")
            appendLine("Время: $now")
            appendLine("Приложение: ${BuildConfig.VERSION_NAME} (код ${BuildConfig.VERSION_CODE})${if (BuildConfig.DEBUG) " debug" else ""}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Состояние VPN: $vpnState")
            if (subscription != null) appendLine("Подписка: $subscription")
            if (error != null) {
                appendLine()
                appendLine("Ошибка: ${error.code} — ${error.title}")
                if (error.detail.isNotBlank()) appendLine("Подробности: ${error.detail}")
            }
            appendLine()
            appendLine("── Параметры сессии ──")
            appendLine(tailOf("session-debug.txt", 20))
            appendLine()
            appendLine("── События ──")
            appendLine(EventLog.tail(context, 60).ifBlank { "(пусто)" })
            appendLine()
            appendLine("── Журнал ядра (последние строки) ──")
            appendLine(tailOf("xray.log", 60))
        }
        return Redact.apply(text).take(40_000)
    }

    fun copy(context: Context, text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("DostupVPN report", text))
        Toast.makeText(context, "Отчёт скопирован", Toast.LENGTH_SHORT).show()
    }

    fun share(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "DostupVPN — отчёт")
            .putExtra(Intent.EXTRA_TEXT, text)
        runCatching {
            context.startActivity(Intent.createChooser(send, "Отправить отчёт").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
