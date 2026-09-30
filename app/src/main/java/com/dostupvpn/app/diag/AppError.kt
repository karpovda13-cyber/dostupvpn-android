package com.dostupvpn.app.diag

import com.dostupvpn.app.net.ApiException
import java.io.IOException
import javax.net.ssl.SSLException

/**
 * Ошибка для пользователя. [code] короткий и стабильный: его называют в поддержке,
 * по нему проще искать причину, чем по тексту. [detail] — технические подробности (идут в отчёт).
 */
data class AppError(
    val code: String,
    val title: String,
    val hint: String = "",
    val detail: String = "",
)

object Errors {

    val TIMEOUT = AppError(
        "E-TIMEOUT", "VPN не успел запуститься", "Повторите подключение. Если повторится — отправьте отчёт.",
    )

    fun from(e: Throwable): AppError = when (e) {
        is ApiException -> fromApi(e)
        // SSLException — подкласс IOException, поэтому проверяется раньше.
        is SSLException -> AppError(
            "E-TLS", "Не удалось проверить сертификат сервера",
            "Обновите приложение. Если ошибка повторяется — отправьте отчёт.", e.toString(),
        )
        is IOException -> AppError(
            "E-NET", "Нет связи с сервером", "Проверьте интернет и повторите.", e.toString(),
        )
        else -> AppError(
            "E-APP", "Непредвиденная ошибка", "Повторите действие. Если повторится — отправьте отчёт.", e.toString(),
        )
    }

    fun fromApi(e: ApiException): AppError {
        val detail = "HTTP ${e.status} ${e.code}: ${e.message}"
        return when (e.status) {
            401 -> AppError("E-401", "Сессия завершена", "Выпустите новый токен в боте и войдите снова.", detail)
            402 -> AppError("E-402", "Подписка не активна", "Продлите подписку в боте.", detail)
            404 -> AppError("E-404", "Токен недействителен или истёк", "Выпустите новый токен в боте.", detail)
            429 -> AppError("E-429", "Слишком много попыток", "Подождите минуту и повторите.", detail)
            in 500..599 -> AppError("E-${e.status}", "Сервер временно недоступен", "Повторите позже.", detail)
            else -> AppError(
                "E-API-${e.status}", e.message.ifBlank { "Ошибка сервера (${e.status})" }, "Повторите позже.", detail,
            )
        }
    }

    /** Ошибки запуска туннеля (Android VPN + ядро Xray). */
    fun vpn(e: Throwable): AppError {
        val m = e.message.orEmpty()
        return when {
            "missing VPN permission" in m -> AppError(
                "E-VPN-PERM", "Нет разрешения на VPN", "Подтвердите системный запрос на создание VPN и повторите.", m,
            )
            "VPN not prepared or revoked" in m -> AppError(
                "E-VPN-REVOKED", "Android отозвал разрешение на VPN", "Повторите подключение и подтвердите запрос.", m,
            )
            else -> AppError(
                "E-CORE", "Не удалось запустить туннель",
                "Повторите подключение. Если ошибка повторяется — отправьте отчёт.", e.toString(),
            )
        }
    }

    fun config(e: Throwable) = AppError(
        "E-CFG", "Не удалось подготовить настройки подключения",
        "Повторите подключение. Если ошибка повторяется — отправьте отчёт.", e.toString(),
    )

    fun crash(text: String) = AppError(
        "E-CRASH", "Прошлый запуск завершился аварийно", "Отправьте отчёт — это поможет исправить.", text,
    )
}

/** Убирает из текста то, что нельзя отправлять: токены, UUID, ключи. */
object Redact {
    private val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val TOKEN = Regex("\\b[A-Z0-9]{4}(-[A-Z0-9]{4}){3}\\b")
    private val KEY = Regex("\\b[A-Za-z0-9_-]{43}\\b")

    fun apply(s: String): String = s
        .replace(UUID, "<uuid>")
        .replace(TOKEN, "<token>")
        .replace(KEY, "<key>")
}
