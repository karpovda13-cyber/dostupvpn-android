package com.dostupvpn.app.vpn

import android.content.Context

/** Persistent local state used to restore the UI after the Activity is recreated/minimized. */
class VpnSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("vpn_session", Context.MODE_PRIVATE)

    fun connectedAt(): Long = prefs.getLong(KEY_CONNECTED_AT, 0L)

    fun error(): String? = prefs.getString(KEY_ERROR, null)

    fun markConnected(at: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putLong(KEY_CONNECTED_AT, at)
            .remove(KEY_ERROR)
            .apply()
    }

    fun markFailed(message: String) {
        prefs.edit()
            .remove(KEY_CONNECTED_AT)
            .putString(KEY_ERROR, message)
            .apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_CONNECTED_AT).remove(KEY_ERROR).apply()
    }

    private companion object {
        const val KEY_CONNECTED_AT = "connected_at"
        const val KEY_ERROR = "error"
    }
}
