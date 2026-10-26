package org.omarchy.music.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The app's icons, drawn on a 24×24 grid (no icon font: they come out the same on every phone). */
enum class Ico { PLAY, PAUSE, NEXT, PREV, SHUFFLE, REPEAT, REPEAT_ONE, HEART, HEART_LINE, STAR, STAR_LINE, EQ, LYRICS, VIS,
    QUEUE, MORE, BACK, DOWN, SEARCH, MENU, NOTE, PERSON, CLOCK, CHART, MOOD, SKIP, COPY, DISC, PLAYLIST, SPARKLE, LIBRARY,
    FOLDER, CHECK, CLOSE, PLUS, DOWNLOAD, CLOUD, ART }

@Composable
fun Icon(ico: Ico, color: Color = C.fg, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { drawIco(ico, color) }
}

fun DrawScope.drawIco(ico: Ico, color: Color) {
    val u = size.minDimension / 24f
    fun p(x: Float, y: Float) = Offset(x * u, y * u)
    val stroke = Stroke(width = 2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun line(vararg pts: Float) {
        val path = Path().apply { moveTo(pts[0] * u, pts[1] * u); var i = 2; while (i < pts.size) { lineTo(pts[i] * u, pts[i + 1] * u); i += 2 } }
        drawPath(path, color, style = stroke)
    }
    fun fill(vararg pts: Float) {
        val path = Path().apply { moveTo(pts[0] * u, pts[1] * u); var i = 2; while (i < pts.size) { lineTo(pts[i] * u, pts[i + 1] * u); i += 2 }; close() }
        drawPath(path, color)
        drawPath(path, color, style = Stroke(width = 1.2f * u, join = StrokeJoin.Round))
    }
    fun rect(x: Float, y: Float, w: Float, h: Float, r: Float = 1.2f) =
        drawRoundRect(color, p(x, y), Size(w * u, h * u), CornerRadius(r * u))
    fun ring(x: Float, y: Float, r: Float) = drawCircle(color, r * u, p(x, y), style = stroke)
    fun dot(x: Float, y: Float, r: Float) = drawCircle(color, r * u, p(x, y))
    fun heart(): Path = Path().apply {
        moveTo(12f * u, 20.5f * u)
        cubicTo(5f * u, 15f * u, 2f * u, 11.5f * u, 2f * u, 8.2f * u)
        cubicTo(2f * u, 5.5f * u, 4.2f * u, 3.5f * u, 6.8f * u, 3.5f * u)
        cubicTo(8.8f * u, 3.5f * u, 10.8f * u, 4.6f * u, 12f * u, 6.5f * u)
        cubicTo(13.2f * u, 4.6f * u, 15.2f * u, 3.5f * u, 17.2f * u, 3.5f * u)
        cubicTo(19.8f * u, 3.5f * u, 22f * u, 5.5f * u, 22f * u, 8.2f * u)
        cubicTo(22f * u, 11.5f * u, 19f * u, 15f * u, 12f * u, 20.5f * u)
        close()
    }
    fun star(): Path = Path().apply {
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) 10f else 4.3f
            val a = -PI / 2 + i * PI / 5
            val x = 12f + r * cos(a).toFloat(); val y = 12.6f + r * sin(a).toFloat()
            if (i == 0) moveTo(x * u, y * u) else lineTo(x * u, y * u)
        }
        close()
    }
    when (ico) {
        Ico.PLAY -> fill(7f, 4.5f, 19.5f, 12f, 7f, 19.5f)
        Ico.PAUSE -> { rect(6f, 4.5f, 4f, 15f); rect(14f, 4.5f, 4f, 15f) }
        Ico.NEXT -> { fill(4.5f, 5.5f, 14.5f, 12f, 4.5f, 18.5f); rect(16.5f, 5.5f, 3f, 13f) }
        Ico.PREV -> { fill(19.5f, 5.5f, 9.5f, 12f, 19.5f, 18.5f); rect(4.5f, 5.5f, 3f, 13f) }
        Ico.SKIP -> { line(5f, 6f, 11f, 12f, 5f, 18f); line(12f, 6f, 18f, 12f, 12f, 18f); line(20f, 6f, 20f, 18f) }
        Ico.SHUFFLE -> {
            line(3f, 7f, 7.5f, 7f, 15.5f, 17f, 20f, 17f); line(3f, 17f, 7.5f, 17f, 15.5f, 7f, 20f, 7f)
            line(17.5f, 4.5f, 20f, 7f, 17.5f, 9.5f); line(17.5f, 14.5f, 20f, 17f, 17.5f, 19.5f)
        }
        Ico.REPEAT, Ico.REPEAT_ONE -> {
            line(4f, 11f, 4f, 7f, 19.5f, 7f); line(17f, 4.5f, 19.5f, 7f, 17f, 9.5f)
            line(20f, 13f, 20f, 17f, 4.5f, 17f); line(7f, 14.5f, 4.5f, 17f, 7f, 19.5f)
            if (ico == Ico.REPEAT_ONE) line(11f, 10.5f, 12.5f, 9.8f, 12.5f, 14.2f)
        }
        Ico.HEART -> drawPath(heart(), color)
        Ico.HEART_LINE -> drawPath(heart(), color, style = stroke)
        Ico.STAR -> drawPath(star(), color)
        Ico.STAR_LINE -> drawPath(star(), color, style = Stroke(width = 1.8f * u, join = StrokeJoin.Round))
        Ico.EQ -> {
            line(6f, 4f, 6f, 20f); line(12f, 4f, 12f, 20f); line(18f, 4f, 18f, 20f)
            dot(6f, 14f, 2.6f); dot(12f, 8f, 2.6f); dot(18f, 15.5f, 2.6f)
        }
        Ico.LYRICS -> { line(4f, 6f, 20f, 6f); line(4f, 11f, 17f, 11f); line(4f, 16f, 11f, 16f); dot(17f, 18f, 2.2f); line(19f, 18f, 19f, 12f, 21f, 12.6f) }
        Ico.VIS -> { rect(3.5f, 12f, 3f, 8f, 1f); rect(8.5f, 6f, 3f, 14f, 1f); rect(13.5f, 9.5f, 3f, 10.5f, 1f); rect(18.5f, 3.5f, 3f, 16.5f, 1f) }
        Ico.QUEUE -> { line(4f, 6f, 16f, 6f); line(4f, 11f, 16f, 11f); line(4f, 16f, 10.5f, 16f); fill(14f, 13.5f, 20.5f, 17f, 14f, 20.5f) }
        Ico.MORE -> { dot(12f, 5f, 2f); dot(12f, 12f, 2f); dot(12f, 19f, 2f) }
        Ico.BACK -> line(15f, 4.5f, 7.5f, 12f, 15f, 19.5f)
        Ico.DOWN -> line(5f, 9f, 12f, 16f, 19f, 9f)
        Ico.SEARCH -> { ring(10.5f, 10.5f, 6.5f); line(15.5f, 15.5f, 20.5f, 20.5f) }
        Ico.MENU -> { line(4f, 6.5f, 20f, 6.5f); line(4f, 12f, 20f, 12f); line(4f, 17.5f, 20f, 17.5f) }
        Ico.NOTE -> { dot(7.5f, 17.5f, 3f); dot(17.5f, 15.5f, 3f); line(10.2f, 17.5f, 10.2f, 5.5f, 20.2f, 3.5f, 20.2f, 15.5f) }
        Ico.PERSON -> {
            ring(12f, 8f, 4f)
            drawArc(color, 180f, 180f, false, p(4.5f, 14f), Size(15f * u, 13f * u), style = stroke)
        }
        Ico.CLOCK -> { ring(12f, 12f, 9f); line(12f, 7f, 12f, 12f, 15.5f, 14f) }
        Ico.CHART -> { rect(4f, 13f, 4f, 7f); rect(10f, 8f, 4f, 12f); rect(16f, 3.5f, 4f, 16.5f) }
        Ico.MOOD -> {
            ring(12f, 12f, 9f); dot(9f, 10f, 1.4f); dot(15f, 10f, 1.4f)
            drawArc(color, 20f, 140f, false, p(7.5f, 9f), Size(9f * u, 7f * u), style = stroke)
        }
        Ico.COPY -> {
            drawRoundRect(color, p(8f, 8f), Size(12f * u, 12f * u), CornerRadius(2f * u), style = stroke)
            line(16f, 4.5f, 6f, 4.5f, 4f, 6.5f, 4f, 16f)
        }
        Ico.DISC -> { ring(12f, 12f, 9f); dot(12f, 12f, 2.2f); drawArc(color, 200f, 60f, false, p(6f, 6f), Size(12f * u, 12f * u), style = stroke) }
        Ico.PLAYLIST -> { line(4f, 6f, 15f, 6f); line(4f, 11f, 15f, 11f); line(4f, 16f, 10f, 16f); dot(15.5f, 18f, 2.4f); line(17.9f, 18f, 17.9f, 9f, 21f, 10f) }
        Ico.SPARKLE -> { fill(12f, 2.5f, 14f, 10f, 21.5f, 12f, 14f, 14f, 12f, 21.5f, 10f, 14f, 2.5f, 12f, 10f, 10f); dot(19f, 5f, 1.4f) }
        Ico.LIBRARY -> { line(5f, 4f, 5f, 20f); line(10f, 4f, 10f, 20f); line(14.5f, 5f, 19.5f, 19.5f) }
        Ico.FOLDER -> line(3f, 18.5f, 3f, 5.5f, 9f, 5.5f, 11f, 8f, 21f, 8f, 21f, 18.5f, 3f, 18.5f)
        Ico.CHECK -> line(4.5f, 12.5f, 9.5f, 17.5f, 19.5f, 6.5f)
        Ico.CLOSE -> { line(5.5f, 5.5f, 18.5f, 18.5f); line(18.5f, 5.5f, 5.5f, 18.5f) }
        Ico.PLUS -> { line(12f, 4.5f, 12f, 19.5f); line(4.5f, 12f, 19.5f, 12f) }
        Ico.DOWNLOAD -> { line(12f, 3.5f, 12f, 15f); line(7f, 10.5f, 12f, 15.5f, 17f, 10.5f); line(4.5f, 20f, 19.5f, 20f) }
        Ico.CLOUD -> {
            val path = Path().apply {
                moveTo(7f * u, 18.5f * u)
                cubicTo(3.8f * u, 18.5f * u, 2.5f * u, 15.8f * u, 3.4f * u, 13.6f * u)
                cubicTo(4.1f * u, 11.9f * u, 5.6f * u, 11.2f * u, 7f * u, 11.4f * u)
                cubicTo(7.5f * u, 7.8f * u, 10f * u, 5.5f * u, 13f * u, 5.5f * u)
                cubicTo(16.5f * u, 5.5f * u, 18.6f * u, 8.2f * u, 18.8f * u, 10.8f * u)
                cubicTo(21f * u, 11.2f * u, 22f * u, 13.2f * u, 21.6f * u, 15.2f * u)
                cubicTo(21.2f * u, 17.2f * u, 19.6f * u, 18.5f * u, 17.5f * u, 18.5f * u)
                close()
            }
            drawPath(path, color, style = stroke)
        }
        Ico.ART -> { drawRoundRect(color, p(3.5f, 3.5f), Size(17f * u, 17f * u), CornerRadius(3f * u), style = stroke); ring(12f, 12f, 4f); dot(12f, 12f, 1.2f) }
    }
}
