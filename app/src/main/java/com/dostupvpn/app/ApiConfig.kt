package com.dostupvpn.app

/**
 * ══ ЕДИНСТВЕННОЕ МЕСТО, где задаются адрес API и отпечатки ключей ══
 *
 * DEFAULT_API_URL нужен только для самого первого входа. После входа сервер присылает
 * актуальный список адресов (файл /etc/vpn/api_endpoints.json на сервере), приложение
 * его запоминает и пробует адреса по порядку. Поэтому, когда появится домен, достаточно
 * дописать его в файл на сервере — приложение обновлять не придётся.
 */
object ApiConfig {
    /** Адрес API по умолчанию. Формат: https://хост:порт (без слэша в конце). */
    const val DEFAULT_API_URL = "https://176.98.181.30:8443"

    /** "pinned" — сверка отпечатка ключа (самоподписанный сертификат); "system" — обычная проверка. */
    const val DEFAULT_TLS_MODE = "pinned"

    /** Отпечатки открытых ключей сертификата API (SPKI SHA-256, base64): основной и резервный. */
    val PINS: Set<String> = setOf(
        "eJnH4P4KUZuR2uEi6NW/1tz+ho9NtiWDl1rRxmXaoPI=", // основной
        "vrGfe61w6U/aetTSOayRjJa3Xsye3NGpKhK4NBaGfUo=", // резервный
    )

    /** Ссылка на бота (открывается кнопкой «Открыть бота»). */
    const val BOT_URL = "https://t.me/dostupvset_bot"
}
