package com.dostupvpn.app.diag

import android.content.Context
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Короткий журнал событий подключения («запрос сессии», «туннель создан», «ошибка E-…»).
 * Не содержит секретов; последние строки попадают в отчёт для поддержки.
 */
object EventLog {
    private const val FILE = "events.log"
    private const val MAX_BYTES = 48 * 1024
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss")

    @Synchronized
    fun add(context: Context, message: String) {
        runCatching {
            val f = File(context.filesDir, FILE)
            if (f.length() > MAX_BYTES) {
                f.writeText(f.readLines().takeLast(150).joinToString("\n") + "\n")
            }
            f.appendText("${LocalTime.now().format(time)} $message\n")
        }
    }

    @Synchronized
    fun tail(context: Context, lines: Int = 60): String = runCatching {
        File(context.filesDir, FILE).readLines().takeLast(lines).joinToString("\n")
    }.getOrDefault("")
}
