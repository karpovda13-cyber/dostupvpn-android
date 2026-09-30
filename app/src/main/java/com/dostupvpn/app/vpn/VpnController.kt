package com.dostupvpn.app.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.dostupvpn.app.diag.AppError
import com.dostupvpn.app.diag.Errors
import com.dostupvpn.app.diag.EventLog
import com.dostupvpn.app.net.ApiClient
import com.dostupvpn.app.net.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Starts/stops the foreground VPN service. The service itself owns the server heartbeat,
 * so minimizing the Activity does not cancel the session.
 */
class VpnController(private val context: Context, private val api: ApiClient) {

    sealed interface State {
        data object Disconnected : State
        /** [stage] — что происходит сейчас («Запрос сессии…», «Запуск туннеля…»). */
        data class Connecting(val stage: String) : State
        data class Connected(val connectedAt: Long) : State
        data class Failed(val error: AppError) : State
    }

    private val sessionStore = VpnSessionStore(context)

    /** null, если разрешение на VPN уже выдано системой. */
    fun permissionIntent(): Intent? = VpnService.prepare(context)

    fun currentState(): State {
        if (sessionStore.connectedAt() > 0L && !DostupVpnService.running) {
            // Процесс убит (например, оболочкой телефона) — туннеля больше нет, показывать «Подключен» нельзя.
            EventLog.add(context, "Служба VPN была остановлена системой — состояние сброшено")
            sessionStore.clearStale()
        }
        return stateFromStore()
    }

    private fun stateFromStore(): State = when {
        sessionStore.connectedAt() > 0L && sessionStore.isReconnecting() -> State.Connecting("Восстановление соединения…")
        sessionStore.connectedAt() > 0L -> State.Connected(sessionStore.connectedAt())
        sessionStore.error() != null -> State.Failed(sessionStore.error()!!)
        else -> State.Disconnected
    }

    fun lastSessionSec(): Long = sessionStore.lastSessionSec()

    fun failStreak(): Int = sessionStore.failStreak()

    fun observe(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) = sessionStore.register(listener)
    fun stopObserving(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) = sessionStore.unregister(listener)

    /** Убирает показанную ошибку (пользователь закрыл карточку). */
    fun dismissError() = sessionStore.clear()

    suspend fun connect(onState: (State) -> Unit) {
        fun stage(text: String) {
            EventLog.add(context, text)
            onState(State.Connecting(text))
        }
        fun failed(error: AppError) {
            sessionStore.recordFailure()
            EventLog.add(context, "ОШИБКА ${error.code}: ${error.title}${if (error.detail.isBlank()) "" else " | ${error.detail}"}")
            onState(State.Failed(error))
        }

        EventLog.add(context, "── подключение ──")
        stage("Запрос сессии…")
        sessionStore.clear()
        try {
            val session = withContext(Dispatchers.IO) { api.sessionStart() }
            EventLog.add(context, "Сессия создана (heartbeat ${session.heartbeatSec} с)")

            // Правила админа: сервер уже доступен (сессия создана). Ошибка не критична —
            // используем то, что закешировано с прошлого раза.
            stage("Загрузка правил…")
            withContext(Dispatchers.IO) {
                runCatching { AdminRulesStore.update(context, api.rules(AdminRulesStore.version(context))) }
            }
            EventLog.add(context, "Правила маршрутизации: v${AdminRulesStore.version(context)}")

            val config = try {
                withContext(Dispatchers.IO) { XrayConfig.build(context, session) }
            } catch (e: Exception) {
                // Сервер уже создал временный конфиг — освобождаем, иначе он «зависнет» до таймаута.
                withContext(Dispatchers.IO) { runCatching { api.sessionStop() } }
                failed(Errors.config(e))
                return
            }

            stage("Запуск туннеля…")
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
            failed(Errors.TIMEOUT)
        } catch (e: ApiException) {
            failed(Errors.fromApi(e))
        } catch (e: Exception) {
            failed(Errors.from(e))
        }
    }

    fun disconnect(onState: (State) -> Unit) {
        EventLog.add(context, "Отключено пользователем")
        context.startService(Intent(context, DostupVpnService::class.java).setAction(DostupVpnService.ACTION_STOP))
        sessionStore.clear()
        onState(State.Disconnected)
    }
}
