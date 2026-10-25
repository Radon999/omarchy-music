package org.omarchy.music

import java.security.SecureRandom
import java.text.Normalizer
import kotlin.math.max
import kotlin.math.roundToInt

// Helpers shared by every part of the app, ported from the desktop omarchy-music so
// both agree on what counts as the same song.

val SOURCES = linkedMapOf("ytm" to "YouTube Music", "tidal" to "Tidal", "apple" to "Apple Music", "spotify" to "Spotify")

fun fmtTime(s: Double): String {
    val n = max(0, s.toInt())
    return "${n / 60}:${"%02d".format(n % 60)}"
}

/** How long a list of songs plays: "42 min", "2 h 5 min". */
fun fmtTotal(tracks: List<Track>): String {
    val m = (tracks.sumOf { it.duration } / 60).roundToInt()
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}

private val NON_WORD = Regex("[^\\w\\s]")
private val SPACES = Regex("\\s+")

private fun squash(s: String) = s.split(SPACES).filter { it.isNotEmpty() }.joinToString(" ")

/** Lowercase letters and digits only, accents dropped: for comparing names across services. */
fun norm(s: String?): String {
    val text = s ?: ""
    val ascii = Normalizer.normalize(text, Normalizer.Form.NFKD).filter { it.code < 128 }.ifEmpty { text }
    return squash(NON_WORD.replace(ascii.lowercase(), " "))
}

private val NOISE_BRACKETS = Regex(
    "[(\\[][^)\\]]*\\b(official|video|audio|lyrics?|visuali[sz]er|hd|4k|mv|remaster(ed)?|feat|ft|with" +
        "|original|album version|explicit|clean|single version)\\b[^)\\]]*[)\\]]"
)
private val NOISE_DASH = Regex("\\s[-–]\\s[^-–]*\\b(official|video|audio|lyrics?|remaster(ed)?)\\b.*$")
private val FEAT_TAIL = Regex("\\b(feat|ft)\\b\\.?\\s.*$")

/** A title without what YouTube adds: (Official Video), feat. credits, remaster notes, punctuation. */
fun cleanTitle(s: String): String {
    var t = s.lowercase()
    t = NOISE_BRACKETS.replace(t, " ")
    t = NOISE_DASH.replace(t, " ")
    t = FEAT_TAIL.replace(t, " ")
    return squash(NON_WORD.replace(t, " "))
}

/** Just the song's name: no brackets, nothing after " - ". */
fun bareTitle(s: String) = cleanTitle(Regex("[(\\[][^)\\]]*[)\\]]|\\s[-–]\\s.*$").replace(s, " "))

/** Artists credited in a title: "Song (feat. A & B)" -> {a, b}. */
fun featured(title: String): Set<String> {
    val m = Regex("(?iU)\\b(?:feat|ft|with)\\b\\.?\\s*([^)\\]]+)").find(title) ?: return emptySet()
    return artistNames(listOf(m.groupValues[1]))
}

/** Each artist in names, lowercased; splits "A, B & C" and drops "- Topic". */
fun artistNames(names: Iterable<String>): Set<String> {
    val out = linkedSetOf<String>()
    for (n0 in names) {
        val n = Regex("\\s*-\\s*topic$|vevo$").replace(n0.lowercase().trim(), "")
        for (part in n.split(Regex(",|&|\\bx\\b|\\band\\b|\\bfeat\\b\\.?|\\bft\\b\\.?"))) {
            val p = squash(NON_WORD.replace(part, " "))
            if (p.isNotEmpty()) out += p
        }
    }
    return out
}

/** The artists in a credit, as written: "A, B feat. C" -> [A, B, C]. "A & B" may be a
 *  duo's name, so it is offered whole and as each of them. */
fun splitArtists(text: String?): List<String> {
    val out = linkedSetOf<String>()
    for (part0 in (text ?: "").split(Regex("(?i),\\s*|\\s+(?:feat\\.?|ft\\.?|featuring|with)\\s+"))) {
        val part = part0.trim()
        if (part.isEmpty() || part.lowercase() == "unknown artist") continue
        out += part
        if (" & " in part) part.split(" & ").map { it.trim() }.filter { it.isNotEmpty() }.forEach { out += it }
    }
    return out.toList()
}

fun hasWord(text: String, word: String) = Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(text)

/** difflib.SequenceMatcher(None, a, b).ratio(), the measure the desktop app matches songs with. */
fun similar(a: String, b: String): Double {
    if (a.isEmpty() && b.isEmpty()) return 1.0
    val b2j = HashMap<Char, MutableList<Int>>()
    b.forEachIndexed { j, c -> b2j.getOrPut(c) { mutableListOf() } += j }
    var matches = 0
    val stack = ArrayDeque<IntArray>().apply { add(intArrayOf(0, a.length, 0, b.length)) }
    while (stack.isNotEmpty()) {
        val (alo, ahi, blo, bhi) = stack.removeLast().let { listOf(it[0], it[1], it[2], it[3]) }
        var besti = alo
        var bestj = blo
        var bestsize = 0
        var j2len = HashMap<Int, Int>()
        for (i in alo until ahi) {
            val newj2len = HashMap<Int, Int>()
            for (j in b2j[a[i]] ?: emptyList()) {
                if (j < blo) continue
                if (j >= bhi) break
                val k = (j2len[j - 1] ?: 0) + 1
                newj2len[j] = k
                if (k > bestsize) {
                    besti = i - k + 1
                    bestj = j - k + 1
                    bestsize = k
                }
            }
            j2len = newj2len
        }
        if (bestsize > 0) {
            matches += bestsize
            if (alo < besti && blo < bestj) stack.add(intArrayOf(alo, besti, blo, bestj))
            if (besti + bestsize < ahi && bestj + bestsize < bhi)
                stack.add(intArrayOf(besti + bestsize, ahi, bestj + bestsize, bhi))
        }
    }
    return 2.0 * matches / (a.length + b.length)
}

/** The item whose name is name, or near enough; null if none is. */
fun <T> bestNamed(items: List<T>, name: String, get: (T) -> String): T? {
    val best = items.maxByOrNull { similar(norm(get(it)), norm(name)) } ?: return null
    return if (similar(norm(get(best)), norm(name)) >= 0.8) best else null
}

/** The same song on any service: its name and lead artist, tidied. */
fun songKey(t: Track) = norm(cleanTitle(t.title)) to norm(t.artist.split(",")[0])

fun safeName(id: String) = Regex("[^\\w.-]").replace(id, "_")

fun randomHex(bytes: Int): String {
    val b = ByteArray(bytes)
    SecureRandom().nextBytes(b)
    return b.joinToString("") { "%02x".format(it) }
}

/** YouTube Music art URLs end in =w120-h120-...; Apple's in /226x226bb.jpg: ask for the size we draw. */
fun sizedThumb(url: String, px: Int): String = when {
    "googleusercontent" in url -> Regex("=w\\d+-h\\d+").replace(url, "=w$px-h$px")
    "mzstatic.com" in url -> Regex("/\\d+x\\d+(bb|cc)?\\.(jpg|webp|png)$").replace(url, "/${px}x${px}bb.jpg")
    else -> url
}

fun now() = System.currentTimeMillis() / 1000.0
