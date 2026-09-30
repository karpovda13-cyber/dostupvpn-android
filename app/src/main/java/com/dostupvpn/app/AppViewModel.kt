package com.dostupvpn.app

import android.app.Application
import android.content.SharedPreferences
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dostupvpn.app.data.SecureStore
import com.dostupvpn.app.data.UiPrefs
import com.dostupvpn.app.diag.AppError
import com.dostupvpn.app.diag.Errors
import com.dostupvpn.app.diag.Report
import com.dostupvpn.app.net.ApiClient
import com.dostupvpn.app.net.ApiException
import com.dostupvpn.app.net.Me
import com.dostupvpn.app.ui.ThemeMode
import com.dostupvpn.app.vpn.VpnController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Loading : Screen
    data object Login : Screen
    data class Home(val me: Me) : Screen
    data class Offline(val error: AppError) : Screen
}

data class UiState(
    val screen: Screen = Screen.Loading,
    val busy: Boolean = false,
    val error: AppError? = null,
    val vpn: VpnController.State = VpnController.State.Disconnected,
    /** Длительность последнего подключения, сек (для серого таймера в отключённом состоянии). */
    val lastSessionSec: Long = 0L,
    /** Сколько попыток подключения подряд закончились ошибкой: от этого зависит, предлагать ли отчёт. */
    val failStreak: Int = 0,
    /** Причина прошлого аварийного завершения (если было) — показывается, пока не начнётся новое подключение. */
    val crash: AppError? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SecureStore(app)
    private val api = ApiClient(store, app)
    private val vpnController = VpnController(app, api)
    private val prefs = UiPrefs(app)

    private val _ui = MutableStateFlow(
        UiState(lastSessionSec = vpnController.lastSessionSec(), failStreak = vpnController.failStreak()),
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _theme = MutableStateFlow(prefs.themeMode())
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    // Служба меняет состояние из другого компонента (ошибка, потеря сессии) — подхватываем сразу,
    // а не только при следующем открытии приложения. Ссылку держим в поле: SharedPreferences хранит слушателя слабо.
    // Пока идёт подключение по нажатию кнопки, состояние ведёт сам контроллер — не мешаем ему.
    @Volatile
    private var connectInProgress = false

    private val sessionListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (!connectInProgress) setVpn(vpnController.currentState())
    }

    init {
        vpnController.observe(sessionListener)
    }

    override fun onCleared() {
        vpnController.stopObserving(sessionListener)
    }

    private fun setVpn(state: VpnController.State) {
        _ui.update {
            it.copy(vpn = state, lastSessionSec = vpnController.lastSessionSec(), failStreak = vpnController.failStreak())
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.setThemeMode(mode)
        _theme.value = mode
    }

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
        if (crash != null) _ui.update { it.copy(crash = Errors.crash(crash)) }
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
                _ui.update {
                    it.copy(
                        screen = Screen.Home(me), busy = false, error = null,
                        vpn = vpnController.currentState(), lastSessionSec = vpnController.lastSessionSec(),
                        failStreak = vpnController.failStreak(),
                    )
                }
            } catch (e: ApiException) {
                onApiError(e)
            } catch (e: Exception) {
                showProblem(Errors.from(e))
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
            } catch (e: Exception) {
                _ui.update { it.copy(busy = false, error = Errors.from(e)) }
            }
        }
    }

    /** Разрешение на VPN, если система его ещё не выдавала (иначе — null, можно подключаться сразу). */
    fun vpnPermissionIntent() = vpnController.permissionIntent()

    fun toggleVpn(enable: Boolean) {
        if (!enable) {
            vpnController.disconnect(::setVpn)
            return
        }
        _ui.update { it.copy(crash = null, error = null) }
        connectInProgress = true
        viewModelScope.launch {
            try {
                vpnController.connect(::setVpn)
            } finally {
                connectInProgress = false
            }
        }
    }

    /** Пользователь закрыл карточку с ошибкой. */
    fun dismissError() {
        vpnController.dismissError()
        _ui.update {
            it.copy(
                error = null,
                crash = null,
                vpn = if (it.vpn is VpnController.State.Failed) VpnController.State.Disconnected else it.vpn,
            )
        }
    }

    /** Выход освобождает слот на сервере. Без связи выйти нельзя — иначе слот остался бы занятым. */
    fun logout() {
        vpnController.disconnect(::setVpn)
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
                    _ui.update { it.copy(busy = false, error = Errors.fromApi(e)) }
                }
            } catch (e: Exception) {
                val base = Errors.from(e)
                _ui.update {
                    it.copy(
                        busy = false,
                        error = if (e is java.io.IOException) {
                            base.copy(title = "Выйти не удалось — нет связи", hint = "Слот не освобождён. Повторите позже.")
                        } else base,
                    )
                }
            }
        }
    }

    /** Текст отчёта для поддержки (без токенов и ключей). */
    fun buildReport(): String {
        val s = _ui.value
        val vpnText = when (val v = s.vpn) {
            VpnController.State.Disconnected -> "отключено"
            is VpnController.State.Connecting -> "подключение (${v.stage})"
            is VpnController.State.Connected -> "подключено"
            is VpnController.State.Failed -> "ошибка ${v.error.code}"
        }
        val error = (s.vpn as? VpnController.State.Failed)?.error ?: s.error ?: s.crash
        val subscription = (s.screen as? Screen.Home)?.me?.let {
            if (it.active) "активна до ${it.expireDate ?: "—"}, осталось ${it.daysLeft} дн." else "не активна"
        }
        return Report.build(getApplication(), vpnText, error, subscription)
    }

    private fun onApiError(e: ApiException) {
        if (e.status == 401) {
            store.clear()
            _ui.value = UiState(Screen.Login, error = Errors.fromApi(e))
        } else {
            showProblem(Errors.fromApi(e))
        }
    }

    /** Если уже показан главный экран — ошибка выводится над кнопками; иначе — экран «нет связи». */
    private fun showProblem(error: AppError) {
        _ui.update {
            if (it.screen is Screen.Home) it.copy(busy = false, error = error)
            else UiState(Screen.Offline(error))
        }
    }

    private fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(40)
}
