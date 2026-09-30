package com.dostupvpn.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Все иконки нарисованы вручную (тонкая линия, как в макете): не нужна библиотека иконок
 * и нет риска несовпадения версий.
 */

private fun DrawScope.line(color: Color, a: Offset, b: Offset, w: Float) =
    drawLine(color, a, b, strokeWidth = w, cap = StrokeCap.Round)

/** Значок питания: разомкнутое кольцо и вертикальная черта. Используется и в большой кнопке. */
fun DrawScope.drawPower(color: Color, strokeWidth: Float, radius: Float, center: Offset = this.center) {
    drawArc(
        color = color, startAngle = -50f, sweepAngle = 280f, useCenter = false,
        topLeft = Offset(center.x - radius, center.y - radius), size = Size(radius * 2, radius * 2),
        style = Stroke(strokeWidth, cap = StrokeCap.Round),
    )
    line(color, Offset(center.x, center.y - radius * 1.2f), Offset(center.x, center.y - radius * 0.1f), strokeWidth)
}

@Composable
fun PowerIcon(color: Color, size: Dp = 24.dp) =
    Canvas(Modifier.size(size)) { drawPower(color, this.size.minDimension * 0.09f, this.size.minDimension * 0.32f) }

@Composable
fun SunIcon(color: Color, size: Dp = 22.dp) = Canvas(Modifier.size(size)) {
    val u = this.size.minDimension
    val w = u * 0.08f
    drawCircle(color, radius = u * 0.19f, style = Stroke(w))
    for (i in 0 until 8) {
        val a = i * PI / 4
        val c = center
        line(
            color,
            Offset(c.x + (cos(a) * u * 0.32f).toFloat(), c.y + (sin(a) * u * 0.32f).toFloat()),
            Offset(c.x + (cos(a) * u * 0.45f).toFloat(), c.y + (sin(a) * u * 0.45f).toFloat()),
            w,
        )
    }
}

@Composable
fun MoonIcon(color: Color, size: Dp = 22.dp) = Canvas(Modifier.size(size)) {
    val u = this.size.minDimension
    val outer = Path().apply { addOval(Rect(center, u * 0.38f)) }
    val cut = Path().apply { addOval(Rect(Offset(center.x + u * 0.16f, center.y - u * 0.12f), u * 0.34f)) }
    val moon = Path.combine(PathOperation.Difference, outer, cut)
    drawPath(moon, color)
}

@Composable
fun GearIcon(color: Color, size: Dp = 26.dp) = Canvas(Modifier.size(size)) {
    val u = this.size.minDimension
    val w = u * 0.08f
    drawCircle(color, radius = u * 0.16f, style = Stroke(w))
    drawCircle(color, radius = u * 0.31f, style = Stroke(w))
    for (i in 0 until 8) {
        val a = i * PI / 4
        val c = center
        line(
            color,
            Offset(c.x + (cos(a) * u * 0.31f).toFloat(), c.y + (sin(a) * u * 0.31f).toFloat()),
            Offset(c.x + (cos(a) * u * 0.44f).toFloat(), c.y + (sin(a) * u * 0.44f).toFloat()),
            w * 1.5f,
        )
    }
}

@Composable
fun ClockIcon(color: Color, size: Dp = 22.dp) = Canvas(Modifier.size(size)) {
    val u = this.size.minDimension
    val w = u * 0.08f
    drawCircle(color, radius = u * 0.42f, style = Stroke(w))
    line(color, center, Offset(center.x, center.y - u * 0.24f), w)
    line(color, center, Offset(center.x + u * 0.18f, center.y + u * 0.08f), w)
}

@Composable
fun CalendarIcon(color: Color, size: Dp = 20.dp) = Canvas(Modifier.size(size)) {
    val u = this.size.minDimension
    val w = u * 0.08f
    drawRoundRect(
        color, topLeft = Offset(u * 0.12f, u * 0.2f), size = Size(u * 0.76f, u * 0.68f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(u * 0.12f), style = Stroke(w),
    )
    line(color, Offset(u * 0.12f, u * 0.42f), Offset(u * 0.88f, u * 0.42f), w)
    line(color, Offset(u * 0.32f, u * 0.1f), Offset(u * 0.32f, u * 0.28f), w)
    line(color, Offset(u * 0.68f, u * 0.1f), Offset(u * 0.68f, u * 0.28f), w)
}

@Composable
fun ExitIcon(color: Color, size: Dp = 22.dp) = Canvas(Modifier.size(size)) {
    val u = this.size.minDimension
    val w = u * 0.08f
    val door = Path().apply {
        moveTo(u * 0.5f, u * 0.16f); lineTo(u * 0.16f, u * 0.16f); lineTo(u * 0.16f, u * 0.84f); lineTo(u * 0.5f, u * 0.84f)
    }
    drawPath(door, color, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
    line(color, Offset(u * 0.38f, u * 0.5f), Offset(u * 0.86f, u * 0.5f), w)
    line(color, Offset(u * 0.7f, u * 0.32f), Offset(u * 0.88f, u * 0.5f), w)
    line(color, Offset(u * 0.7f, u * 0.68f), Offset(u * 0.88f, u * 0.5f), w)
}

@Composable
fun ChevronIcon(color: Color, size: Dp = 18.dp) = Canvas(Modifier.size(size)) {
    val u = this.size.minDimension
    val w = u * 0.11f
    line(color, Offset(u * 0.38f, u * 0.22f), Offset(u * 0.64f, u * 0.5f), w)
    line(color, Offset(u * 0.64f, u * 0.5f), Offset(u * 0.38f, u * 0.78f), w)
}

@Composable
fun WarningIcon(color: Color, size: Dp = 22.dp) = Canvas(Modifier.size(size)) {
    val u = this.size.minDimension
    val w = u * 0.08f
    val tri = Path().apply {
        moveTo(u * 0.5f, u * 0.12f); lineTo(u * 0.92f, u * 0.84f); lineTo(u * 0.08f, u * 0.84f); close()
    }
    drawPath(tri, color, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
    line(color, Offset(u * 0.5f, u * 0.4f), Offset(u * 0.5f, u * 0.6f), w)
    drawCircle(color, radius = w * 0.75f, center = Offset(u * 0.5f, u * 0.72f))
}

@Composable
fun CloseIcon(color: Color, size: Dp = 16.dp) = Canvas(Modifier.size(size)) {
    val u = this.size.minDimension
    val w = u * 0.1f
    line(color, Offset(u * 0.2f, u * 0.2f), Offset(u * 0.8f, u * 0.8f), w)
    line(color, Offset(u * 0.8f, u * 0.2f), Offset(u * 0.2f, u * 0.8f), w)
}
