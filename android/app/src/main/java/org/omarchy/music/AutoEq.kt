package org.omarchy.music

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlinx.serialization.Serializable
import java.io.File
import kotlin.math.ln

/** Auto EQ: any headphones' correction from the AutoEq project's results on GitHub (an index of
 *  every profile, and each one's ParametricEQ.txt), the desktop's too. AutoEqApi
 *  (github.com/timschneeb/AutoEqApi) serves the same data but answers only RootlessJamesDSP; a
 *  copy you host yourself (with that check taken out) can be used instead: Settings › AutoEq server. */
object AutoEq {
    const val RESULTS = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/"
    private const val INDEX_FOR = 7 * 86400L   // seconds the index is kept before it's fetched again
    private val TYPES = mapOf("PK" to "pk", "LSC" to "ls", "HSC" to "hs", "LS" to "ls", "HS" to "hs")

    /** A profile: from the index (path), or from your own AutoEqApi (id). */
    data class Entry(val name: String, val source: String, val path: String = "", val id: Long? = null)

    @Serializable
    data class Profile(val name: String, val filters: List<List<Double>>, val kinds: List<String>) {
        fun toFilters() = kinds.zip(filters).map { (k, f) -> Filter(k, f[0], f[1], f[2]) }
    }

    fun profileOf(name: String, filters: List<Filter>) =
        Profile(name, filters.map { listOf(it.hz, it.gain, it.q) }, filters.map { it.kind })

    internal fun indexEntries(text: String): List<Entry> =
        Regex("^- \\[(.+?)]\\(\\./(.+?)\\) by (.+?)\\s*$", RegexOption.MULTILINE).findAll(text).map { m ->
            val (name, path, source) = m.destructured
            Entry(name, source.replace(Regex(" on .*$"), ""), path)
        }.toList()

    /** (blocking) AutoEq's profiles, from the copy kept here when it's fresh enough. */
    fun index(ctx: Context): List<Entry> {
        val f = File(ctx.cacheDir, "autoeq-index.md")
        if (!f.exists() || now() - f.lastModified() / 1000.0 > INDEX_FOR) {
            try { writeAtomic(f, http(RESULTS + "INDEX.md")) } catch (e: Exception) { if (!f.exists()) throw e }
        }
        return indexEntries(f.readText())
    }

    /** The profiles whose name has every word of query, the closest names first. */
    fun search(entries: List<Entry>, query: String, n: Int = 12): List<Entry> {
        val words = norm(query).split(" ").filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return entries.filter { e -> norm(e.name).let { nm -> words.all { it in nm } } }
            .sortedWith(compareByDescending<Entry> { similar(norm(query), norm(it.name)) }.thenBy { it.name }).take(n)
    }

    internal fun parseParametric(text: String): List<Filter> {
        val out = Regex("Filter\\s*\\d*:\\s*ON\\s+([A-Z]+)\\s+Fc\\s+([\\d.]+)\\s*Hz\\s+Gain\\s+(-?[\\d.]+)\\s*dB\\s+Q\\s+([\\d.]+)")
            .findAll(text).mapNotNull { m ->
                val (k, hz, g, q) = m.destructured
                TYPES[k]?.let { Filter(it, Math.round(hz.toDouble()).toDouble(), g.toDouble(), q.toDouble()) }
            }.toList()
        require(out.isNotEmpty()) { "no filters in that profile" }
        return out
    }

    /** A GraphicEQ (what AutoEqApi gives) as a peak an octave wide at each of the ten bands: close, not exact. */
    internal fun graphicToFilters(text: String): List<Filter> {
        val pts = Regex("([\\d.]+)\\s+(-?[\\d.]+)").findAll(text.substringAfter(":")).map {
            it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }.sortedBy { it.first }.toList()
        require(pts.isNotEmpty()) { "no curve in that profile" }
        fun at(hz: Double): Double {
            val lo = pts.lastOrNull { it.first <= hz } ?: pts.first()
            val hi = pts.firstOrNull { it.first >= hz } ?: pts.last()
            if (hi.first == lo.first) return lo.second
            return lo.second + (hi.second - lo.second) * ln(hz / lo.first) / ln(hi.first / lo.first)
        }
        return Eq.BANDS.map { Filter("pk", it.toDouble(), Math.round(at(it.toDouble()) * 10) / 10.0, Eq.Q) }
    }

    /** (blocking) An entry's filters. */
    fun profile(e: Entry, api: String = ""): List<Filter> =
        if (e.id != null) graphicToFilters(http("${api.trimEnd('/')}/results/${e.id}", mapOf("User-Agent" to APP_UA)))
        else parseParametric(http("$RESULTS${e.path}/${e.path.substringAfterLast('/')}%20ParametricEQ.txt"))

    fun apiSearch(api: String, query: String): List<Entry> =
        httpJson("${api.trimEnd('/')}/results/search/${enc(query).replace("+", "%20")}", mapOf("User-Agent" to APP_UA)).list.map {
            Entry(it["n"].str ?: "", it["s"].str ?: "", id = it["i"].long)
        }

    /** The profile of the headphones an output's names name, or null: every word of the profile's
     *  name has to be there, but the brand may be left out of a model with a number ("WH-1000XM6"). */
    fun matchDevice(names: List<String>, entries: List<Entry>): Entry? {
        var best: Entry? = null
        var bestScore = 0.0
        for (dev in names) {
            val d = norm(dev)
            for (e in entries) {
                val model = norm(e.name.replace(Regex("\\(.*?\\)"), ""))
                val words = model.split(" ").filter { it.isNotEmpty() }
                if (words.size < 2 && model.length < 6) continue
                val has = words.map { it in d }
                val rest = words.drop(1).joinToString(" ")
                var score = when {
                    has.all { it } -> model.length + 1.0
                    !has[0] && has.drop(1).all { it } && rest.length >= 5 && rest.any { it.isDigit() } -> rest.length.toDouble()
                    else -> continue
                }
                if ("(" !in e.name) score += 0.5
                if (score > bestScore) { best = e; bestScore = score }
            }
        }
        return best
    }

    private val HEADPHONE_TYPES = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE)

    /** Where the sound goes now: the headphones connected (Bluetooth, USB, wired), else "". */
    fun output(ctx: Context): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.type in HEADPHONE_TYPES }
        val d = outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET } ?: outs.firstOrNull()
        return d?.let { it.productName?.toString()?.takeIf { n -> n.isNotBlank() && n != android.os.Build.MODEL } ?: if (
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET) "Wired headphones" else "" } ?: ""
    }
}
