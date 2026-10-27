package org.omarchy.music.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.exoplayer.ExoPlayer
import org.omarchy.music.Music
import org.omarchy.music.Spectrum

/** The Visualizer tab's looks: Bars is the one it had from the start (and the default). */
val VIS_PRESETS: List<Pair<String, String>> = listOf("bars" to "Bars", "mirror" to "Mirror", "wave" to "Wave", "circle" to "Circle",
    "led" to "LED", "glow" to "Glow") + Looks.NEON.map { (k, v) -> k to v.first }

/** The Visualizer tab: what's playing as bars rising and falling with its sound, from Android's
 *  Visualizer on the player's own audio session (so only this app's sound, never the mic). */
@Composable
fun VisualizerTab() {
    val ctx = LocalContext.current
    var allowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    if (!allowed) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.material3.Text("Android's visualizer reads the music this app plays, and for that it asks for " +
                "the microphone permission. The microphone itself is never used.", style = T.sub, textAlign = TextAlign.Center)
            Box(Modifier.padding(top = 14.dp)) { Pill("Allow it") { ask.launch(Manifest.permission.RECORD_AUDIO) } }
        }
        return
    }
    val session = (Music.player as? ExoPlayer)?.audioSessionId ?: 0
    val target = remember { FloatArray(Spectrum.BANDS) }
    val levels = remember { FloatArray(Spectrum.BANDS) }
    var frame by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf<String?>(null) }
    DisposableEffect(session) {
        val vis = runCatching {
            Visualizer(session).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer, wave: ByteArray, rate: Int) {}
                    override fun onFftDataCapture(v: Visualizer, fft: ByteArray, rate: Int) {
                        Spectrum.bands(fft, rate / 1000).copyInto(target)   // rate comes in millihertz
                    }
                }, Visualizer.getMaxCaptureRate(), false, true)
                enabled = true
            }
        }.onFailure { failed = it.message ?: "Android's visualizer isn't available" }.getOrNull()
        onDispose { vis?.runCatching { enabled = false; release() } }
    }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos {
            for (b in levels.indices) {
                val want = if (Music.paused) 0f else target[b]
                levels[b] = if (want > levels[b]) want else levels[b] - (levels[b] - want) * 0.18f   // up at once, down gently
            }
            frame++
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            VIS_PRESETS.forEach { (k, label) -> Chip(label, Music.visPreset == k) { Music.chooseVisPreset(k) } }
        }
        val accent = C.accent
        val fg = C.fg
        val preset = Music.visPreset
        val neon = Looks.NEON[preset]?.second
        Canvas(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
            @Suppress("UNUSED_EXPRESSION") frame   // redraw each frame
            val n = Spectrum.BANDS
            val gap = size.width / n * 0.25f
            val bw = size.width / n - gap
            val base = size.height * 0.8f
            when {
                neon != null -> {   // black, the bars glowing in one colour, a hot top on the loud ones
                    drawRect(Color.Black, Offset.Zero, size)
                    drawRect(neon.copy(alpha = 0.35f), Offset(0f, base + 1f), Size(size.width, 1.5f))
                    for (b in 0 until n) {
                        val lv = levels[b]; val h = maxOf(2f, lv * base * 0.92f); val x = b * (bw + gap)
                        for ((spread, a) in listOf(7f to 0.06f, 4f to 0.10f, 2f to 0.18f))
                            drawRect(neon.copy(alpha = a * (0.5f + lv)), Offset(x - spread, base - h - spread), Size(bw + 2 * spread, h + spread))
                        val hot = lerp(neon, Color.White, 0.45f + 0.4f * lv)
                        drawRect(Brush.verticalGradient(0f to hot, 0.25f to neon, 1f to lerp(neon, Color.Black, 0.5f),
                            startY = base - h, endY = base), Offset(x, base - h), Size(bw, h))
                        drawRect(neon.copy(alpha = 0.16f * (0.4f + lv)), Offset(x, base + 4f), Size(bw, h * 0.22f))
                    }
                }
                preset == "mirror" -> {
                    val mid = size.height / 2
                    for (b in 0 until n) {
                        val lv = levels[b]; val h = maxOf(1f, lv * mid * 0.9f)
                        drawRect(accent.copy(alpha = 0.3f + 0.7f * lv), Offset(b * (bw + gap), mid - h), Size(bw, h * 2))
                    }
                }
                preset == "wave" -> {
                    val path = Path()
                    val pts = (0 until n).map { i -> Offset(i * size.width / (n - 1), base - levels[i] * base * 0.85f) }
                    path.moveTo(0f, base); path.lineTo(pts[0].x, pts[0].y)
                    for (i in 1 until n) { val a = pts[i - 1]; val c = pts[i]; val mx = (a.x + c.x) / 2; path.cubicTo(mx, a.y, mx, c.y, c.x, c.y) }
                    path.lineTo(size.width, base); path.close()
                    drawPath(path, Brush.verticalGradient(listOf(accent.copy(alpha = 0.75f), accent.copy(alpha = 0.1f)), startY = base * 0.15f, endY = base))
                    drawPath(path, accent, style = Stroke(width = 5f))
                }
                preset == "circle" -> {
                    val c = Offset(size.width / 2, size.height / 2)
                    val bass = levels.take(maxOf(1, n / 6)).average().toFloat()
                    val r0 = minOf(size.width, size.height) * (0.16f + 0.05f * bass)
                    val reach = minOf(size.width, size.height) * 0.3f
                    for (side in listOf(1, -1)) for (b in 0 until n) {
                        val lv = levels[b]
                        val ang = (-Math.PI / 2 + side * (b + 0.5) / n * Math.PI).toFloat()
                        val dir = Offset(kotlin.math.cos(ang), kotlin.math.sin(ang))
                        drawLine(accent.copy(alpha = 0.35f + 0.65f * lv), c + dir * r0, c + dir * (r0 + 3f + lv * reach),
                            strokeWidth = maxOf(3f, (2 * Math.PI * r0 / (n * 2) * 0.7).toFloat()), cap = StrokeCap.Round)
                    }
                    drawCircle(accent.copy(alpha = 0.18f + 0.4f * bass), r0 * 0.85f, c)
                }
                preset == "led" -> {
                    val rows = 18; val cell = size.height * 0.8f / rows; val hotC = Color(0xFFF7768E)
                    for (b in 0 until n) {
                        val lit = Math.round(levels[b] * rows); val x = b * (bw + gap)
                        for (r in 0 until rows) {
                            val col = if (r < lit) (if (r >= rows * 0.8f) hotC else accent).copy(alpha = 0.95f) else fg.copy(alpha = 0.06f)
                            drawRect(col, Offset(x, base - (r + 1) * cell + 2f), Size(bw, cell - 4f))
                        }
                    }
                }
                preset == "glow" -> {
                    for (b in 0 until n) {
                        val lv = levels[b]; val h = maxOf(2f, lv * base * 0.95f); val x = b * (bw + gap)
                        val col = lerp(Color(0xFF7DCFFF), Color(0xFFBB9AF7), b / (n - 1f))
                        drawRect(col.copy(alpha = 0.10f + 0.25f * lv), Offset(x - gap, base - h - 6f), Size(bw + 2 * gap, h + 6f))
                        drawRect(Brush.verticalGradient(listOf(col, accent.copy(alpha = 0.25f)), startY = base - h, endY = base), Offset(x, base - h), Size(bw, h))
                    }
                }
                else -> for (b in 0 until n) {   // Bars: the one it had from the start
                    val lv = levels[b]
                    val h = maxOf(2f, lv * base * 0.95f)
                    val x = b * (bw + gap)
                    drawRect(accent.copy(alpha = 0.35f + 0.65f * lv), Offset(x, base - h), Size(bw, h))
                    drawRect(accent.copy(alpha = 0.12f * (0.4f + lv)), Offset(x, base + 3f), Size(bw, h * 0.25f))
                }
            }
        }
        androidx.compose.material3.Text(failed ?: Music.current()?.title ?: "Nothing playing", style = T.sub,
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), textAlign = TextAlign.Center)
    }
}
