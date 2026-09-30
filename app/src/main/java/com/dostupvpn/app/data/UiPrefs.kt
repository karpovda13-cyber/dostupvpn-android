package com.dostupvpn.app.data

import android.content.Context
import com.dostupvpn.app.ui.ThemeMode

/** Настройки интерфейса (не секретные). */
class UiPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    fun themeMode(): ThemeMode =
        runCatching { ThemeMode.valueOf(prefs.getString("theme", null) ?: "AUTO") }.getOrDefault(ThemeMode.AUTO)

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString("theme", mode.name).apply()
    }
}
