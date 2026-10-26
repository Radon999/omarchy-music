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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.exoplayer.ExoPlayer
import org.omarchy.music.Music
import org.omarchy.music.Spectrum

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
        val accent = C.accent
        Canvas(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
            @Suppress("UNUSED_EXPRESSION") frame   // redraw each frame
            val n = Spectrum.BANDS
            val gap = size.width / n * 0.25f
            val bw = size.width / n - gap
            val base = size.height * 0.8f
            for (b in 0 until n) {
                val lv = levels[b]
                val h = maxOf(2f, lv * base * 0.95f)
                val x = b * (bw + gap)
                drawRect(accent.copy(alpha = 0.35f + 0.65f * lv), Offset(x, base - h), Size(bw, h))
                drawRect(accent.copy(alpha = 0.12f * (0.4f + lv)), Offset(x, base + 3f), Size(bw, h * 0.25f))
            }
        }
        androidx.compose.material3.Text(failed ?: Music.current()?.title ?: "Nothing playing", style = T.sub,
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), textAlign = TextAlign.Center)
    }
}
