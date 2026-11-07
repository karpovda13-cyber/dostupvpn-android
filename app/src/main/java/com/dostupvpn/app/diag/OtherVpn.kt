package com.dostupvpn.app.diag

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.dostupvpn.app.vpn.DostupVpnService

/**
 * Одновременно в Android работает только один VPN. Если на телефоне включён чужой (например, v2rayTun),
 * запросы нашего приложения к серверу идут через него, и связь может не работать.
 */
object OtherVpn {

    /** true, если активная сеть — чужой VPN (наш собственный не запущен). */
    fun isActive(context: Context): Boolean {
        if (DostupVpnService.running) return false   // свой VPN: приложение исключено из него и ходит напрямую
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    /** Если сетевая ошибка случилась при чужом VPN — называем причину, а не просто «нет связи». */
    fun refine(context: Context, error: AppError): AppError {
        if (error.code != "E-NET" && error.code != "E-TLS") return error
        if (!isActive(context)) return error
        return AppError(
            code = "E-OTHERVPN",
            title = "Включён другой VPN",
            hint = "Отключите его и повторите: одновременно работает только один VPN, а приложению нужна прямая связь с сервером.",
            detail = "${error.code}: ${error.title}" + if (error.detail.isBlank()) "" else " | ${error.detail}",
        )
    }
}
