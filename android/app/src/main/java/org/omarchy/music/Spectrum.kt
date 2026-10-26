package org.omarchy.music

import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** The Visualizer tab's bars from Android's Visualizer FFT: [BANDS] bands spaced evenly in pitch
 *  (like the desktop's), each 0..1 on a decibel scale. */
object Spectrum {
    const val BANDS = 48
    private const val LOW_HZ = 30.0
    private const val HIGH_HZ = 16000.0
    private const val FLOOR_DB = -60.0   // this quiet and below: an empty bar

    /** fft: Visualizer's capture (DC, Nyquist, then real/imaginary byte pairs); rate: its sampling rate in Hz. */
    fun bands(fft: ByteArray, rate: Int): FloatArray {
        val n = fft.size / 2                   // bins
        val hzPerBin = rate / 2.0 / n
        val out = FloatArray(BANDS)
        for (b in 0 until BANDS) {
            val lo = LOW_HZ * (HIGH_HZ / LOW_HZ).pow(b.toDouble() / BANDS)
            val hi = LOW_HZ * (HIGH_HZ / LOW_HZ).pow((b + 1.0) / BANDS)
            val from = max(1, (lo / hzPerBin).toInt())
            val to = min(n - 1, max(from, (hi / hzPerBin).toInt()))
            var peak = 0.0
            for (k in from..to) peak = max(peak, hypot(fft[2 * k].toDouble(), fft[2 * k + 1].toDouble()))
            val db = 20 * ln(max(peak, 1e-3) / 128.0) / ln(10.0)
            out[b] = ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0.0, 1.0).toFloat()
        }
        return out
    }
}
