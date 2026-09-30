package com.dostupvpn.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** AUTO — как в системе (в т.ч. по расписанию «тёмной темы» в настройках Android). */
enum class ThemeMode { AUTO, LIGHT, DARK }

@Immutable
data class Palette(
    val dark: Boolean,
    val bgTop: Color,
    val bgBottom: Color,
    val text: Color,
    val textDim: Color,
    val accent: Color,
    val ringA: Color,
    val ringB: Color,
    val disc: Color,
    val surface: Color,
    val sheet: Color,
    val border: Color,
    val ok: Color,
    val warn: Color,
    val danger: Color,
)

val DarkPalette = Palette(
    dark = true,
    bgTop = Color(0xFF071024), bgBottom = Color(0xFF030713),
    text = Color(0xFFDCE7FF), textDim = Color(0xFF8FA3C7),
    accent = Color(0xFF5AA9FF), ringA = Color(0xFF63B3FF), ringB = Color(0xFF2F5BFF),
    disc = Color(0xFF0B1630),
    surface = Color(0x990E1A33), sheet = Color(0xFF0B1730), border = Color(0xFF243F6E),
    ok = Color(0xFF34D399), warn = Color(0xFFFBBF24), danger = Color(0xFFF87171),
)

val LightPalette = Palette(
    dark = false,
    bgTop = Color(0xFFF5F9FF), bgBottom = Color(0xFFE4EDFB),
    text = Color(0xFF0F1B3D), textDim = Color(0xFF5B6B8C),
    accent = Color(0xFF2F6BFF), ringA = Color(0xFF4C8DFF), ringB = Color(0xFF2350E0),
    disc = Color(0xFFFFFFFF),
    surface = Color(0xCCFFFFFF), sheet = Color(0xFFFFFFFF), border = Color(0xFFC5D5F0),
    ok = Color(0xFF059669), warn = Color(0xFFB45309), danger = Color(0xFFDC2626),
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

/** Тёмная ли тема сейчас (нужно и Activity — для цвета иконок системных панелей). */
@Composable
fun resolveDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.AUTO -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun DostupTheme(dark: Boolean, content: @Composable () -> Unit) {
    val p = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) {
        darkColorScheme(
            primary = p.accent, onPrimary = Color(0xFF03122E), background = p.bgBottom, onBackground = p.text,
            surface = p.sheet, onSurface = p.text, surfaceVariant = p.surface, onSurfaceVariant = p.textDim,
            outline = p.border, error = p.danger,
        )
    } else {
        lightColorScheme(
            primary = p.accent, onPrimary = Color.White, background = p.bgBottom, onBackground = p.text,
            surface = p.sheet, onSurface = p.text, surfaceVariant = p.surface, onSurfaceVariant = p.textDim,
            outline = p.border, error = p.danger,
        )
    }
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Фон всего экрана: мягкий вертикальный градиент (в тёмной теме — глубокий синий, как в макете). */
@Composable
fun AppBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(p.bgTop, p.bgBottom))),
    ) { content() }
}
