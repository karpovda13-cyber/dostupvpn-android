package com.dostupvpn.app.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.dostupvpn.app.net.ApiClient
import com.dostupvpn.app.net.ApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Starts/stops the foreground VPN service. The service itself owns the server heartbeat,
 * so minimizing the Activity does not cancel the session.
 */
class VpnController(private val context: Context, private val api: ApiClient) {

    sealed interface State {
        data object Disconnected : State
        data object Connecting : State
        data class Connected(val connectedAt: Long) : State
        data class Failed(val message: String) : State
    }

    private val sessionStore = VpnSessionStore(context)

    /** null, если разрешение на VPN уже выдано системой. */
    fun permissionIntent(): Intent? = VpnService.prepare(context)

    fun currentState(): State = when {
        sessionStore.connectedAt() > 0L -> State.Connected(sessionStore.connectedAt())
        sessionStore.error() != null -> State.Failed(sessionStore.error()!!)
        else -> State.Disconnected
    }

    suspend fun connect(scope: CoroutineScope, onState: (State) -> Unit) {
        onState(State.Connecting)
        sessionStore.clear()
        try {
            val session = withContext(Dispatchers.IO) { api.sessionStart() }
            val config = try {
                withContext(Dispatchers.IO) { XrayConfig.build(context, session) }
            } catch (e: Exception) {
                // Сервер уже создал временный конфиг — освобождаем, иначе он «зависнет» до таймаута.
                withContext(Dispatchers.IO) { runCatching { api.sessionStop() } }
                throw e
            }
            context.startService(
                Intent(context, DostupVpnService::class.java)
                    .putExtra(DostupVpnService.EXTRA_CONFIG, config)
                    .putExtra(DostupVpnService.EXTRA_HEARTBEAT_SEC, session.heartbeatSec),
            )

            // startService() is asynchronous; wait until the foreground service reports
            // that Xray actually started (or failed) instead of showing a false "on" state.
            repeat(100) {
                delay(100)
                when (val state = currentState()) {
                    is State.Connected, is State.Failed -> {
                        onState(state)
                        return
                    }
                    else -> Unit
                }
            }
            onState(State.Failed("VPN не успел запуститься. Повторите подключение."))
        } catch (e: ApiException) {
            onState(State.Failed(apiErrorMessage(e)))
        } catch (e: IOException) {
            onState(State.Failed("Ошибка подготовки VPN: ${e.message ?: "нет связи с сетью"}"))
        } catch (e: Exception) {
            onState(State.Failed("Ошибка подготовки VPN: ${e.message ?: "неизвестная ошибка"}"))
        }
    }

    fun disconnect(onState: (State) -> Unit) {
        context.startService(Intent(context, DostupVpnService::class.java).setAction(DostupVpnService.ACTION_STOP))
        sessionStore.clear()
        onState(State.Disconnected)
    }

    private fun apiErrorMessage(e: ApiException): String = when (e.status) {
        402 -> "Подписка не активна. Продлите её в боте."
        else -> e.message.ifBlank { "Ошибка сервера (${e.status})" }
    }
}
