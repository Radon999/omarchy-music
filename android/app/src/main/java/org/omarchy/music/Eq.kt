package org.omarchy.music

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// The equalizer: a correction for your headphones, then a 10-band EQ to taste, then bass
// and treble shelves; the same filters, presets and pre-gain as the desktop app.

/** A filter: "pk" a peak or dip, "ls" / "hs" a low / high shelf; Hz, dB, Q. */
data class Filter(val kind: String, val hz: Double, val gain: Double, val q: Double)

object Eq {
    // Corrections from AutoEq (github.com/jaakkopasanen/AutoEq, measured by Super Review)
    // bring the headphone to the Harman target, the sound most listeners prefer.
    val HEADPHONES: LinkedHashMap<String, Pair<String, List<Filter>>> = linkedMapOf(
        "off" to ("None" to emptyList()),
        "xm6-cable" to ("Sony WH-1000XM6 · cable" to listOf(
            Filter("ls", 105.0, -1.5, 0.70), Filter("pk", 161.0, -7.5, 0.69), Filter("pk", 2143.0, 4.3, 3.66),
            Filter("pk", 1019.0, -5.3, 1.74), Filter("pk", 8308.0, 5.3, 0.21), Filter("hs", 10000.0, -1.5, 0.70),
            Filter("pk", 57.0, -2.0, 2.27), Filter("pk", 463.0, 1.5, 2.87), Filter("pk", 103.0, 1.7, 2.95),
            Filter("pk", 4339.0, 0.9, 3.49))),
        "xm6-bluetooth" to ("Sony WH-1000XM6 · Bluetooth" to listOf(
            Filter("ls", 105.0, -8.3, 0.70), Filter("pk", 171.0, -4.9, 0.99), Filter("pk", 648.0, 2.5, 0.63),
            Filter("pk", 2499.0, -5.2, 1.50), Filter("pk", 5689.0, 4.1, 0.40), Filter("hs", 10000.0, 3.3, 0.70),
            Filter("pk", 4137.0, 1.3, 3.44), Filter("pk", 6747.0, -2.2, 3.84), Filter("pk", 1496.0, -1.5, 3.88),
            Filter("pk", 1151.0, 1.4, 4.07))),
    )
    val BANDS = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    const val Q = 1.41        // an octave wide, so neighbouring bands blend smoothly
    const val RANGE = 12.0    // dB, either way
    val PRESETS: LinkedHashMap<String, Pair<String, List<Double>?>> = linkedMapOf(
        "flat" to ("Flat" to List(10) { 0.0 }),
        "bass" to ("Bass boost" to listOf(5.0, 4.5, 3.5, 1.5, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)),
        "warm" to ("Warm" to listOf(2.0, 2.0, 1.5, 1.0, 0.0, 0.0, -0.5, -1.0, -1.5, -2.0)),
        "vocal" to ("Vocal" to listOf(-2.0, -2.0, -1.0, 0.0, 1.5, 2.5, 2.5, 1.5, 0.0, -1.0)),
        "bright" to ("Bright" to listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 2.5, 3.5, 4.0)),
        "v" to ("V-shape" to listOf(4.0, 3.5, 2.0, 0.0, -1.0, -1.0, 0.0, 1.5, 3.0, 3.5)),
        "custom" to ("Custom" to null),
    )

    fun clamp(g: Double) = (max(-RANGE, min(RANGE, g)) * 2).roundToInt() / 2.0

    /** Every filter the settings make, in order. */
    fun filters(s: EqSettings): List<Filter> =
        (HEADPHONES[s.headphones]?.second ?: emptyList()) +
            BANDS.zip(s.bands).map { (hz, g) -> Filter("pk", hz.toDouble(), g, Q) } +
            listOf(Filter("ls", 100.0, s.bass, 0.71), Filter("hs", 8000.0, s.treble, 0.71))

    /** RBJ cookbook coefficients (b0, b1, b2, a0, a1, a2), as libavfilter builds them. */
    fun coeffs(f: Filter, rate: Double): DoubleArray {
        val a = 10.0.pow(f.gain / 40)
        val w0 = 2 * PI * f.hz / rate
        val cw = cos(w0)
        val alpha = sin(w0) / (2 * f.q)
        return if (f.kind == "pk") doubleArrayOf(1 + alpha * a, -2 * cw, 1 - alpha * a, 1 + alpha / a, -2 * cw, 1 - alpha / a)
        else {
            val sa = 2 * sqrt(a) * alpha
            val s = if (f.kind == "ls") 1 else -1   // the high shelf is the low one mirrored
            doubleArrayOf(a * ((a + 1) - s * (a - 1) * cw + sa), s * 2 * a * ((a - 1) - s * (a + 1) * cw),
                a * ((a + 1) - s * (a - 1) * cw - sa), (a + 1) + s * (a - 1) * cw + sa,
                -s * 2 * ((a - 1) + s * (a + 1) * cw), (a + 1) + s * (a - 1) * cw - sa)
        }
    }

    /** The gain in dB of one filter at f Hz. */
    fun gainAt(flt: Filter, f: Double, rate: Double = 48000.0): Double {
        val c = coeffs(flt, rate)
        val w = -2 * PI * f / rate
        // z = e^{jw}: numerator and denominator as complex numbers
        val (zr, zi) = cos(w) to sin(w)
        val (z2r, z2i) = cos(2 * w) to sin(2 * w)
        val nr = c[0] + c[1] * zr + c[2] * z2r
        val ni = c[1] * zi + c[2] * z2i
        val dr = c[3] + c[4] * zr + c[5] * z2r
        val di = c[4] * zi + c[5] * z2i
        return 20 * log10(hypot(nr, ni) / hypot(dr, di))
    }

    /** The cut that keeps the EQ's biggest boost from clipping: dB, 0 or below. */
    fun preamp(filters: List<Filter>): Double {
        val peak = (0 until 300).maxOf { i -> val f = 20 * 1000.0.pow(i / 299.0); filters.sumOf { gainAt(it, f) } }
        return -ceil(max(0.0, peak) * 10) / 10
    }

    fun fmtDb(g: Double) = if (g == 0.0) "0" else (if (g > 0) "+" else "−") + "%.1f".format(kotlin.math.abs(g)).removeSuffix(".0")
}

@Serializable
data class EqSettings(
    val on: Boolean = false, val headphones: String = "off", val preset: String = "flat",
    val bands: List<Double> = List(10) { 0.0 }, val custom: List<Double> = List(10) { 0.0 },
    val bass: Double = 0.0, val treble: Double = 0.0,
)

/** The EQ in the player: a cascade of biquads per channel, in double precision. New
 *  settings swap the coefficients in without resetting the filters, so there's no gap. */
class EqProcessor : BaseAudioProcessor() {
    private class Stage(val c: DoubleArray, val state: Array<DoubleArray>)

    @Volatile private var settings = EqSettings()
    @Volatile private var stages: List<Stage> = emptyList()
    @Volatile private var gain = 1.0
    private var rate = 48000.0
    private var channels = 2

    fun update(s: EqSettings) {
        settings = s
        rebuild()
    }

    private fun rebuild() {
        val s = settings
        if (!s.on) { stages = emptyList(); gain = 1.0; return }
        val fs = Eq.filters(s)
        val old = stages
        stages = fs.mapIndexed { i, f ->
            val c = Eq.coeffs(f, rate).let { k -> DoubleArray(5) { j -> (if (j < 3) k[j] else k[j + 1]) / k[3] } }
            Stage(c, old.getOrNull(i)?.state?.takeIf { it.size == channels } ?: Array(channels) { DoubleArray(2) })
        }
        gain = 10.0.pow(Eq.preamp(fs) / 20)
    }

    override fun onConfigure(input: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (input.encoding != C.ENCODING_PCM_16BIT && input.encoding != C.ENCODING_PCM_FLOAT)
            throw AudioProcessor.UnhandledAudioFormatException(input)
        rate = input.sampleRate.toDouble()
        channels = input.channelCount
        stages = emptyList()
        rebuild()
        return input
    }

    override fun queueInput(input: ByteBuffer) {
        val n = input.remaining()
        if (n == 0) return
        val out = replaceOutputBuffer(n)
        val st = stages
        val float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        input.order(ByteOrder.nativeOrder())
        if (st.isEmpty()) {
            out.put(input)
        } else {
            val g = gain
            var ch = 0
            while (input.hasRemaining()) {
                var x = (if (float) input.float.toDouble() else input.short / 32768.0) * g
                for (s in st) {
                    val z = s.state[ch]
                    val c = s.c
                    val y = c[0] * x + z[0]
                    z[0] = c[1] * x - c[3] * y + z[1]
                    z[1] = c[2] * x - c[4] * y
                    x = y
                }
                if (float) out.putFloat(x.toFloat())
                else out.putShort((x * 32768.0).roundToInt().coerceIn(-32768, 32767).toShort())
                ch = (ch + 1) % channels
            }
        }
        out.flip()
    }

    override fun onFlush() {
        stages.forEach { s -> s.state.forEach { it.fill(0.0) } }
    }
}
