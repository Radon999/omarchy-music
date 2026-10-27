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
    /** How many bands the EQ can have, and where they sit: 3, 5, 10, 15 (2/3 octave) or 31 (1/3 octave). */
    val COUNTS = listOf(3, 5, 10, 15, 31)
    fun bandsFor(n: Int): List<Int> = when (n) {
        3 -> listOf(100, 1000, 10000)
        5 -> listOf(60, 230, 910, 3600, 14000)
        15 -> listOf(25, 40, 63, 100, 160, 250, 400, 630, 1000, 1600, 2500, 4000, 6300, 10000, 16000)
        31 -> listOf(20, 25, 31, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800, 1000, 1250, 1600,
            2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000, 20000)
        else -> BANDS
    }
    /** Each band's width: narrower the more bands there are, so they still blend. */
    fun qFor(n: Int) = when (n) { 3 -> 0.45; 5 -> 0.75; 15 -> 2.15; 31 -> 4.32; else -> Q }
    fun label(hz: Int) = if (hz >= 1000) (if (hz % 1000 == 0) "${hz / 1000}k" else "%.1fk".format(hz / 1000.0)) else "$hz"

    /** gains (at the frequencies from) moved onto n bands: read off the curve, in log frequency. */
    fun resample(gains: List<Double>, from: List<Int>, n: Int): List<Double> {
        if (gains.size == n && from == bandsFor(n)) return gains
        val xs = from.map { kotlin.math.ln(it.toDouble()) }
        return bandsFor(n).map { hz ->
            val x = kotlin.math.ln(hz.toDouble())
            when {
                x <= xs.first() -> gains.first()
                x >= xs.last() -> gains.last()
                else -> {
                    val i = xs.indexOfLast { it <= x }.coerceAtMost(xs.size - 2)
                    val t = (x - xs[i]) / (xs[i + 1] - xs[i])
                    clamp(gains[i] + (gains[i + 1] - gains[i]) * t)
                }
            }
        }
    }

    /** A preset's gains on n bands (the presets are written for 10). */
    fun preset(key: String, n: Int): List<Double>? = PRESETS[key]?.second?.let { resample(it, BANDS, n) }
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

    /** Every correction there is to pick: the built-in ones, then the AutoEq ones you added. */
    fun headphones(s: EqSettings): Map<String, Pair<String, List<Filter>>> =
        HEADPHONES + s.profiles.mapValues { (_, p) -> p.name to p.toFilters() }

    /** Every filter the settings make, in order. */
    fun filters(s: EqSettings): List<Filter> =
        (headphones(s)[s.headphones]?.second ?: emptyList()) +
            bandsFor(s.count).zip(s.bands).map { (hz, g) -> Filter("pk", hz.toDouble(), g, qFor(s.count)) } +
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
    val profiles: Map<String, AutoEq.Profile> = emptyMap(),   // AutoEq corrections you added
    val devices: Map<String, String> = emptyMap(),            // an output's name: the correction you used on it
    val count: Int = 10,                                      // how many bands: 3, 5, 10, 15 or 31
    val perOutput: Boolean = true,                            // a separate EQ for each output (speaker, headphones, car…)
    val outputs: Map<String, OutputEq> = emptyMap(),          // each output's own EQ, by its key (see [Outputs])
    val output: String = "",                                  // the output these settings are for now
) {
    /** These settings as one output keeps them. */
    fun toOutput(label: String) = OutputEq(label, on, headphones, preset, bands, custom, bass, treble, count)
    /** These settings with an output's own EQ put in. */
    fun withOutput(key: String, o: OutputEq?) = if (o == null) copy(output = key, on = false, headphones = "off", preset = "flat",
        bands = List(count) { 0.0 }, custom = List(count) { 0.0 }, bass = 0.0, treble = 0.0)
        else copy(output = key, on = o.on, headphones = o.headphones, preset = o.preset, bands = o.bands, custom = o.custom,
            bass = o.bass, treble = o.treble, count = o.count)
}

/** One output's own EQ. */
@Serializable
data class OutputEq(val label: String = "", val on: Boolean = false, val headphones: String = "off", val preset: String = "flat",
                    val bands: List<Double> = List(10) { 0.0 }, val custom: List<Double> = List(10) { 0.0 },
                    val bass: Double = 0.0, val treble: Double = 0.0, val count: Int = 10)

/** Where the sound goes: the phone's speaker, wired headphones, each Bluetooth device by its name (your car,
 *  your earbuds), USB. Each is a key ("speaker", "wired", "bt:Name", "usb:Name") with a label and a kind. */
object Outputs {
    data class Out(val key: String, val label: String, val kind: String)

    fun now(ctx: android.content.Context): Out {
        val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val outs = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
        fun name(d: android.media.AudioDeviceInfo) = d.productName?.toString()?.takeIf { it.isNotBlank() && it != android.os.Build.MODEL }
        outs.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET ||
            it.type == android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER }?.let { d ->
            val n = name(d) ?: "Bluetooth"
            val car = Regex("(?i)car|auto|sync|uconnect|vw|toyota|ford|honda|bmw|audi|mazda|kia|hyundai|chevy|nissan|tesla|subaru|jeep|dodge|mercedes|lexus").containsMatchIn(n)
            return Out("bt:$n", n, if (car) "car" else "bluetooth")
        }
        outs.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET || it.type == android.media.AudioDeviceInfo.TYPE_USB_DEVICE }?.let { d ->
            val n = name(d) ?: "USB"
            return Out("usb:$n", "$n (USB)", "usb")
        }
        outs.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET }?.let { return Out("headset", "Headset (jack, with mic)", "wired") }
        outs.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES }?.let { return Out("wired", "Headphones (jack)", "wired") }
        return Out("speaker", "Built-in speaker", "speaker")
    }

    fun kindOf(key: String) = when {
        key == "speaker" -> "speaker"
        key == "wired" || key == "headset" -> "wired"
        key.startsWith("usb:") -> "usb"
        else -> "bluetooth"
    }
}

/** The EQ in the player: a cascade of biquads per channel, in double precision. New
 *  settings swap the coefficients in without resetting the filters, so there's no gap. */
class EqProcessor : BaseAudioProcessor() {
    private class Stage(val c: DoubleArray, val state: Array<DoubleArray>)

    @Volatile private var settings = EqSettings()
    @Volatile private var stages: List<Stage> = emptyList()
    @Volatile private var gain = 1.0
    @Volatile var songGain = 1.0   // the playing song's own volume (Volume…), on top
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
        if (st.isEmpty() && songGain == 1.0) {
            out.put(input)
        } else {
            val g = (if (st.isEmpty()) 1.0 else gain) * songGain
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
