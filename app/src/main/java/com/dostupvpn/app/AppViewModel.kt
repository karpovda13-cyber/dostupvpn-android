package com.dostupvpn.app

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dostupvpn.app.data.SecureStore
import com.dostupvpn.app.net.ApiClient
import com.dostupvpn.app.net.ApiException
import com.dostupvpn.app.net.Me
import com.dostupvpn.app.vpn.VpnController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

sealed interface Screen {
    data object Loading : Screen
    data object Login : Screen
    data class Home(val me: Me) : Screen
    data class Offline(val message: String) : Screen
}

data class UiState(
    val screen: Screen = Screen.Loading,
    val busy: Boolean = false,
    val error: String? = null,
    val vpn: VpnController.State = VpnController.State.Disconnected,
    /** Причина прошлого аварийного завершения (если было) — показывается, пока не начнётся новое подключение. */
    val crash: String? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SecureStore(app)
    private val api = ApiClient(store, app)
    private val vpnController = VpnController(app, api)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** Вызывается при каждом открытии приложения: если после принудительного освобождения
     *  слота в боте сервер ответит 401 — произойдёт автоматический выход. */
    fun onStart() {
        // Если прошлый запуск закончился аварийно — покажем причину вместо «молчаливого» вылета.
        val crash = DostupApplication.takeCrashReport(getApplication())
        if (store.deviceToken() == null) {
            if (_ui.value.screen !is Screen.Login) _ui.value = UiState(Screen.Login)
        } else {
            refresh()
        }
        if (crash != null) _ui.update { it.copy(crash = "Прошлый запуск завершился аварийно:\n$crash") }
    }

    fun refresh() {
        viewModelScope.launch {
            _ui.update {
                it.copy(
                    busy = true,
                    error = null,
                    screen = if (it.screen is Screen.Home) it.screen else Screen.Loading,
                )
            }
            try {
                val me = withContext(Dispatchers.IO) { api.me() }
                store.setEndpoints(me.endpoints)
                _ui.update { it.copy(screen = Screen.Home(me), busy = false, error = null, vpn = vpnController.currentState()) }
            } catch (e: ApiException) {
                onApiError(e)
            } catch (e: IOException) {
                showProblem(OFFLINE_MSG)
            } catch (e: Exception) {
                showProblem("Неожиданный ответ сервера. Повторите позже.")
            }
        }
    }

    fun login(token: String) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null) }
            val name = deviceName()
            try {
                val r = withContext(Dispatchers.IO) { api.login(token.trim(), name) }
                store.saveLogin(r.deviceToken, name)
                store.setEndpoints(r.endpoints)
                refresh()
            } catch (e: ApiException) {
                val msg = when (e.status) {
                    404 -> "Токен недействителен или истёк. Выпустите новый в боте."
                    429 -> "Слишком много попыток. Подождите минуту."
                    else -> e.message.ifBlank { "Ошибка сервера (${e.status})" }
                }
                _ui.update { it.copy(busy = false, error = msg) }
            } catch (e: IOException) {
                _ui.update { it.copy(busy = false, error = OFFLINE_MSG) }
            } catch (e: Exception) {
                _ui.update { it.copy(busy = false, error = "Неожиданный ответ сервера. Повторите позже.") }
            }
        }
    }

    /** Разрешение на VPN, если система его ещё не выдавала (иначе — null, можно подключаться сразу). */
    fun vpnPermissionIntent() = vpnController.permissionIntent()

    fun toggleVpn(enable: Boolean) {
        if (!enable) {
            vpnController.disconnect { state -> _ui.update { it.copy(vpn = state) } }
            return
        }
        _ui.update { it.copy(crash = null) }
        viewModelScope.launch {
            vpnController.connect(viewModelScope) { state -> _ui.update { it.copy(vpn = state) } }
        }
    }

    /** Выход освобождает слот на сервере. Без связи выйти нельзя — иначе слот остался бы занятым. */
    fun logout() {
        vpnController.disconnect { state -> _ui.update { it.copy(vpn = state) } }
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null) }
            try {
                withContext(Dispatchers.IO) { api.logout() }
                store.clear()
                _ui.value = UiState(Screen.Login)
            } catch (e: ApiException) {
                if (e.status == 401) {
                    store.clear()
                    _ui.value = UiState(Screen.Login)
                } else {
                    _ui.update { it.copy(busy = false, error = e.message.ifBlank { "Ошибка сервера (${e.status})" }) }
                }
            } catch (e: IOException) {
                _ui.update {
                    it.copy(busy = false, error = "Нет связи — выйти не удалось, слот не освобождён. Повторите позже.")
                }
            } catch (e: Exception) {
                _ui.update { it.copy(busy = false, error = "Неожиданный ответ сервера. Повторите позже.") }
            }
        }
    }

    private fun onApiError(e: ApiException) {
        if (e.status == 401) {
            store.clear()
            _ui.value = UiState(Screen.Login, error = "Сессия завершена. Выпустите новый токен в боте и войдите снова.")
        } else {
            showProblem(e.message.ifBlank { "Ошибка сервера (${e.status})" })
        }
    }

    /** Если уже показан главный экран — ошибка выводится над кнопками; иначе — экран «нет связи». */
    private fun showProblem(message: String) {
        _ui.update {
            if (it.screen is Screen.Home) it.copy(busy = false, error = message)
            else UiState(Screen.Offline(message))
        }
    }

    private fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(40)

    private companion object {
        const val OFFLINE_MSG = "Нет связи с сервером. Проверьте интернет и повторите."
    }
}
