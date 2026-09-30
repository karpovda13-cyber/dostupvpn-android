package com.dostupvpn.app.vpn

import android.content.Context
import android.content.SharedPreferences
import com.dostupvpn.app.diag.AppError

/** Persistent local state used to restore the UI after the Activity is recreated/minimized. */
class VpnSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("vpn_session", Context.MODE_PRIVATE)

    fun connectedAt(): Long = prefs.getLong(KEY_CONNECTED_AT, 0L)

    /** Длительность последнего завершённого подключения, секунды (0 — ещё не было). */
    fun lastSessionSec(): Long = prefs.getLong(KEY_LAST_SEC, 0L)

    fun error(): AppError? {
        val code = prefs.getString(KEY_ERR_CODE, null) ?: return null
        return AppError(
            code = code,
            title = prefs.getString(KEY_ERR_TITLE, "").orEmpty(),
            hint = prefs.getString(KEY_ERR_HINT, "").orEmpty(),
            detail = prefs.getString(KEY_ERR_DETAIL, "").orEmpty(),
        )
    }

    fun markConnected(at: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putLong(KEY_CONNECTED_AT, at)
            .removeError()
            .apply()
    }

    fun markFailed(error: AppError) {
        rememberDuration(prefs.edit())
            .remove(KEY_CONNECTED_AT)
            .putString(KEY_ERR_CODE, error.code)
            .putString(KEY_ERR_TITLE, error.title)
            .putString(KEY_ERR_HINT, error.hint)
            .putString(KEY_ERR_DETAIL, error.detail)
            .apply()
    }

    fun clear() {
        rememberDuration(prefs.edit())
            .remove(KEY_CONNECTED_AT)
            .removeError()
            .apply()
    }

    /** Подписка на изменения: служба пишет сюда из другого компонента, UI должен это увидеть сразу. */
    fun register(l: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.registerOnSharedPreferenceChangeListener(l)
    fun unregister(l: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.unregisterOnSharedPreferenceChangeListener(l)

    private fun rememberDuration(e: SharedPreferences.Editor): SharedPreferences.Editor {
        val at = connectedAt()
        if (at > 0L) e.putLong(KEY_LAST_SEC, ((System.currentTimeMillis() - at) / 1000L).coerceAtLeast(0L))
        return e
    }

    private fun SharedPreferences.Editor.removeError(): SharedPreferences.Editor =
        remove(KEY_ERR_CODE).remove(KEY_ERR_TITLE).remove(KEY_ERR_HINT).remove(KEY_ERR_DETAIL)

    private companion object {
        const val KEY_CONNECTED_AT = "connected_at"
        const val KEY_LAST_SEC = "last_session_sec"
        const val KEY_ERR_CODE = "err_code"
        const val KEY_ERR_TITLE = "err_title"
        const val KEY_ERR_HINT = "err_hint"
        const val KEY_ERR_DETAIL = "err_detail"
    }
}
