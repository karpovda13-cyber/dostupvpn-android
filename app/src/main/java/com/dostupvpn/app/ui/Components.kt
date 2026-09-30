package com.dostupvpn.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class PowerMode { OFF, CONNECTING, ON, ERROR }

/** Большая кнопка питания с неоновым кольцом (макет): цвет и анимация зависят от состояния. */
@Composable
fun PowerButton(
    mode: PowerMode,
    enabled: Boolean,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val inf = rememberInfiniteTransition(label = "power")
    val spin by inf.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "spin",
    )
    val pulse by inf.animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse",
    )
    val colorA by animateColorAsState(
        when (mode) { PowerMode.ON -> p.ok; PowerMode.ERROR -> p.danger; else -> p.ringA }, label = "colorA",
    )
    val colorB by animateColorAsState(
        when (mode) {
            PowerMode.ON -> lerp(p.ok, p.ringB, 0.35f)
            PowerMode.ERROR -> lerp(p.danger, p.ringB, 0.25f)
            else -> p.ringB
        },
        label = "colorB",
    )
    val glow = when (mode) {
        PowerMode.OFF -> 0.30f
        PowerMode.CONNECTING -> 0.20f + 0.20f * pulse
        PowerMode.ON -> 0.22f + 0.18f * pulse
        PowerMode.ERROR -> 0.32f
    }
    val iconColor = when (mode) { PowerMode.ON -> colorA; PowerMode.ERROR -> p.danger; else -> p.accent }
    val clickable = enabled && mode != PowerMode.CONNECTING

    Box(
        modifier = modifier
            .size(260.dp)
            .clip(CircleShape)
            .semantics { contentDescription = description }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null, enabled = clickable, role = Role.Button, onClick = onClick,
            ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val r = size.minDimension / 2f
            val alpha = if (enabled || mode == PowerMode.CONNECTING) 1f else 0.4f

            drawCircle(
                Brush.radialGradient(listOf(colorA.copy(alpha = glow * alpha), Color.Transparent), center = c, radius = r),
                radius = r, center = c,
            )
            val ringR = r * 0.72f
            val stroke = r * 0.075f
            if (mode == PowerMode.CONNECTING) {
                drawCircle(colorA.copy(alpha = 0.18f), radius = ringR, center = c, style = Stroke(stroke))
                drawArc(
                    color = colorA, startAngle = spin, sweepAngle = 110f, useCenter = false,
                    topLeft = Offset(c.x - ringR, c.y - ringR), size = Size(ringR * 2, ringR * 2),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            } else {
                rotate(45f, pivot = c) {
                    drawCircle(
                        Brush.sweepGradient(listOf(colorA, colorB, colorA), center = c),
                        radius = ringR, center = c, style = Stroke(stroke),
                        alpha = alpha,
                    )
                }
            }
            val discR = ringR - stroke * 1.1f
            drawCircle(
                Brush.radialGradient(listOf(lerp(p.disc, colorA, 0.14f), p.disc), center = c, radius = discR),
                radius = discR, center = c,
            )
            drawPower(iconColor.copy(alpha = alpha), strokeWidth = r * 0.085f, radius = r * 0.24f, center = c)
        }
    }
}

/** Переключатель «солнце / луна» (макет). Нажатие — ручной выбор противоположной темы. */
@Composable
fun ThemeToggle(dark: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val x by animateDpAsState(if (dark) 54.dp else 6.dp, label = "thumb")
    Box(
        modifier = modifier
            .size(96.dp, 44.dp)
            .clip(CircleShape)
            .background(p.surface)
            .border(1.dp, p.border.copy(alpha = 0.6f), CircleShape)
            .semantics { contentDescription = if (dark) "Тёмная тема. Переключить на светлую" else "Светлая тема. Переключить на тёмную" }
            .clickable(role = Role.Switch, onClick = onToggle),
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = x)
                .size(36.dp)
                .clip(CircleShape)
                .background(Brush.horizontalGradient(listOf(p.ringA, p.ringB))),
        )
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            SunIcon(if (!dark) Color.White else p.textDim, 20.dp)
            MoonIcon(if (dark) Color.White else p.textDim, 20.dp)
        }
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    border: Color? = null,
    tint: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(p.surface)
            .then(if (tint != null) Modifier.background(tint.copy(alpha = 0.10f)) else Modifier)
            .border(1.dp, border ?: p.border.copy(alpha = 0.7f), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        content = content,
    )
}

@Composable
fun StatusRow(mode: PowerMode, text: String) {
    val p = LocalPalette.current
    val dot = when (mode) {
        PowerMode.OFF -> p.accent
        PowerMode.CONNECTING -> p.warn
        PowerMode.ON -> p.ok
        PowerMode.ERROR -> p.danger
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(10.dp))
        Text(text, color = p.textDim, fontSize = 18.sp)
    }
}

@Composable
fun TimerPill(seconds: Long, active: Boolean) {
    val p = LocalPalette.current
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(p.accent.copy(alpha = if (active) 0.16f else 0.08f))
            .border(1.dp, p.border.copy(alpha = 0.8f), CircleShape)
            .padding(horizontal = 26.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ClockIcon(if (active) p.accent else p.textDim, 22.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = "%02d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60),
            color = if (active) p.accent else p.textDim,
            fontSize = 20.sp,
            style = TextStyle(fontFeatureSettings = "tnum"),
        )
    }
}

@Composable
fun CodeChip(code: String, color: Color) {
    Text(
        text = code,
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Кнопка-«таблетка» с контуром (как «Выход» в макете). */
@Composable
fun OutlinePillButton(text: String, icon: @Composable () -> Unit, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(CircleShape)
            .border(1.5.dp, p.accent.copy(alpha = if (enabled) 0.65f else 0.25f), CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(12.dp))
        Text(text, color = if (enabled) p.text else p.textDim, fontSize = 18.sp)
    }
}

/** Карточка ошибки: понятный текст, код для обратной связи и кнопка отчёта. */
@Composable
fun ErrorCard(
    error: com.dostupvpn.app.diag.AppError,
    showReport: Boolean,
    onReport: () -> Unit,
    onRetry: (() -> Unit)?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    GlassCard(modifier = modifier.fillMaxWidth(), border = p.danger.copy(alpha = 0.6f), tint = p.danger) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 2.dp)) { WarningIcon(p.danger, 22.dp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(error.title, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                if (error.hint.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(error.hint, color = p.textDim, fontSize = 14.sp)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CodeChip(error.code, p.danger)
                    Spacer(Modifier.width(4.dp))
                    if (showReport) {
                        TextButton(onClick = onReport) { Text("Отправить отчёт", color = p.accent) }
                    } else if (onRetry != null) {
                        TextButton(onClick = onRetry) { Text("Повторить", color = p.accent) }
                    }
                }
            }
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .semantics { contentDescription = "Скрыть" }
                    .clickable(role = Role.Button, onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) { CloseIcon(p.textDim, 14.dp) }
        }
    }
}
