package org.omarchy.music

import java.security.SecureRandom
import java.text.Normalizer
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.asKotlinRandom

// Helpers shared by every part of the app, ported from the desktop omarchy-music so
// both agree on what counts as the same song.

val SOURCES = linkedMapOf("ytm" to "YouTube Music", "tidal" to "Tidal", "apple" to "Apple Music", "spotify" to "Spotify")

/** The keys Settings (in the accounts sheet) asks for: none is needed, each makes something better. */
val API_KEYS = linkedMapOf(
    "google_key" to ("Google API key" to "Custom Search JSON API key from console.cloud.google.com: pictures and covers from Google"),
    "google_cx" to ("Google search engine ID" to "A Programmable Search Engine set to search the whole web"),
    "chartmetric" to ("Chartmetric refresh token" to "From your Chartmetric API account: songs' moods from Chartmetric"),
    "autoeq_api" to ("AutoEq server" to "The address of an AutoEqApi server of your own"),
)

const val LASTFM_BLANK = "2a96cbd8b46e442fc41c2b86b821562f"   // Last.fm's grey star, for "no picture"

/** Where the search bar's dropdown can search. */
val SEARCH_IN = linkedMapOf("tidal" to "Tidal", "ytm" to "YouTube Music", "local" to "Your music")

/** How long (ms) after the last key to search as you type, or null not to: two letters at least;
 *  a moment for your own songs, a little longer for a service; a pasted link at once. */
fun liveSearchWait(q: String, where: String): Long? = when {
    linkSource(q.trim()) != null -> 50
    q.trim().length < 2 -> null
    where == "local" -> 150
    else -> 450
}

/** The service a pasted link is from, or null for words to search. */
fun linkSource(q: String): String? = when {
    "music.apple.com" in q -> "apple"
    "tidal.com" in q -> "tidal"
    Regex("open\\.spotify\\.com/|^spotify:").containsMatchIn(q) -> "spotify"
    Regex("youtube\\.com/|youtu\\.be/").containsMatchIn(q) -> "ytm"
    else -> null
}

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
// No Unicode flag ("?U"): Android's ICU regex rejects it, and its \b knows Unicode letters anyway.
private val FEATURING = Regex("\\b(?:feat|ft|with)\\b\\.?\\s*([^)\\]]+)", RegexOption.IGNORE_CASE)

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
    val m = FEATURING.find(title) ?: return emptySet()
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
/** How a playlist's songs can be listed: in the playlist's own order, or sorted. */
val PLAYLIST_SORTS = linkedMapOf("order" to "Order", "title" to "Title", "artist" to "Artist", "album" to "Album",
    "plays" to "Most played", "length" to "Length")

/** tracks sorted by sort (one of PLAYLIST_SORTS; "order" leaves them be). plays: song key -> plays. */
fun sortSongs(tracks: List<Track>, sort: String, plays: Map<Pair<String, String>, Int> = emptyMap()): List<Track> {
    val lead = { t: Track -> norm(t.artist.split(",")[0]).ifEmpty { "~" } }   // no artist: last
    val tie = compareBy<Track> { norm(it.title) }
    return when (sort) {
        "title" -> tracks.sortedWith(compareBy<Track>({ norm(it.title) }, { lead(it) }))
        "artist" -> tracks.sortedWith(compareBy<Track>({ lead(it) }, { norm(it.album).ifEmpty { "~" } }).then(tie))
        "album" -> tracks.sortedWith(compareBy<Track>({ norm(it.album).ifEmpty { "~" } }, { lead(it) }).then(tie))
        "plays" -> tracks.sortedByDescending { plays[songKey(it)] ?: 0 }
        "length" -> tracks.sortedByDescending { it.duration }
        else -> tracks
    }
}

/** Shuffle: every order equally likely (Fisher-Yates), from the system's secure random source,
 *  so no order of even a long playlist is out of reach the way it can be with a seeded one. */
val SHUFFLE_RANDOM: kotlin.random.Random = SecureRandom().asKotlinRandom()

/** tracks in a new random order; after (the song that just played) isn't first. */
fun shuffleSongs(tracks: List<Track>, after: Track? = null, rng: kotlin.random.Random = SHUFFLE_RANDOM): List<Track> {
    val out = tracks.shuffled(rng).toMutableList()
    if (after != null && out.size > 1 && songKey(out[0]) == songKey(after)) {
        val j = rng.nextInt(1, out.size)
        out[0] = out[j].also { out[j] = out[0] }
    }
    return out
}

fun <T> bestNamed(items: List<T>, name: String, get: (T) -> String): T? {
    val best = items.maxByOrNull { similar(norm(get(it)), norm(name)) } ?: return null
    return if (similar(norm(get(best)), norm(name)) >= 0.8) best else null
}

/** The same song on any service: its name and lead artist, tidied. */
fun songKey(t: Track) = norm(cleanTitle(t.title)) to norm(t.artist.split(",")[0])

/** A speed as the app shows it: "1.25×", "1×". */
fun fmtSpeed(s: Double) = (if (s == Math.floor(s)) s.toLong().toString() else s.toString().trimEnd('0')) + "×"

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

/** A song in an .m3u or .m3u8 playlist: where it says the file is, as written, and the names
 *  and length from its #EXTINF line (empty and 0 where it has none). */
data class M3uEntry(val loc: String, val artist: String, val title: String, val seconds: Int)

fun readM3u(raw: ByteArray): List<M3uEntry> {
    val text = runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(raw)).toString() }
        .getOrElse { String(raw, Charsets.ISO_8859_1) }.removePrefix("\uFEFF")   // an old .m3u
    val out = ArrayList<M3uEntry>()
    var info: Triple<String, String, Int>? = null
    for (line0 in text.lines()) {
        val line = line0.trim()
        if (line.startsWith("#EXTINF:")) {
            val head = line.substring(8).substringBefore(',')
            val name = line.substring(8).substringAfter(',', "").trim()
            val secs = Regex("^-?\\d+(\\.\\d+)?").find(head.trim())?.value?.toDouble()?.toInt()?.coerceAtLeast(0) ?: 0
            info = if (" - " in name) Triple(name.substringBefore(" - ").trim(), name.substringAfter(" - ").trim(), secs) else Triple("", name, secs)
        } else if (line.isNotEmpty() && !line.startsWith("#")) {
            out += M3uEntry(line, info?.first ?: "", info?.second ?: "", info?.third ?: 0)
            info = null
        }
    }
    return out
}

/** A song an imported playlist's import didn't find: its line in the file (n), where among the
 *  songs that were found it goes (at), and why it wasn't found. */
data class Missed(val n: Int, val at: Int, val artist: String, val title: String, val seconds: Int, val loc: String, val why: String) {
    val name get() = if (artist.isNotEmpty()) "$artist - $title" else title
}

/** Where m goes back in its playlist: its place, after the ones of placed (put back already) that were before it. */
fun missedAt(m: Missed, placed: List<Missed>) = m.at + placed.count { it.n < m.n }

/** (artist, title) from a song file's name: "Artist - Title.flac", "01 Title.flac". */
fun namesFromFile(path: String): Pair<String, String> {
    val stem = Regex("^\\d{1,3}[\\s.\\-_]+").replace(path.substringAfterLast('/').substringBeforeLast('.'), "")
    return if (" - " in stem) stem.substringBefore(" - ") to stem.substringAfter(" - ") else "" to stem
}

/** The search hit that is the song called title by artist, about seconds long (0: unknown), or null. */
fun bestMatch(hits: List<Track>, artist: String, title: String, seconds: Int): Track? {
    var best: Track? = null
    var bestScore = 0.0
    for (h in hits) {
        if (seconds > 0 && h.duration > 0 && Math.abs(h.duration - seconds) > 8) continue
        var score = similar(cleanTitle(title), cleanTitle(h.title))
        if (artist.isNotEmpty()) {
            val lead = artistNames(listOf(artist)).maxOfOrNull { x -> artistNames(h.artist.split(",")).maxOfOrNull { y -> similar(x, y) } ?: 0.0 } ?: 0.0
            if (lead < 0.6) continue
            score = 0.6 * score + 0.4 * lead
        }
        if (score >= 0.7 && score > bestScore) { best = h; bestScore = score }
    }
    return best
}

/** Your songs, artists and albums whose names have every word of q in them. */
fun localMatches(songs: List<Track>, q: String): Found {
    val words = norm(q).split(" ").filter { it.isNotEmpty() }
    if (words.isEmpty()) return Found(emptyList())
    fun has(text: String) = norm(text).let { t -> words.all { it in t } }
    val hits = songs.filter { has("${it.title} ${it.artist} ${it.album}") }
    val artists = sortedMapOf<String, String>()
    val albums = LinkedHashMap<String, Pair<Album, MutableList<Track>>>()
    for (t in songs) {
        for (name in splitArtists(t.artist)) if (has(name)) artists.putIfAbsent(norm(name), name)
        if (t.album.isNotEmpty() && has("${t.album} ${t.artist}")) {
            val lead = t.artist.split(",")[0].trim()
            val id = "local-" + norm("${t.album} $lead")
            albums.getOrPut(id) { Album(id, t.album, lead) to ArrayList() }.second += t
        }
    }
    val shown = albums.values.take(12).map { (al, tr) ->
        al.copy(sub = listOf(al.sub, "${tr.size} song${if (tr.size != 1) "s" else ""}").filter { it.isNotEmpty() }.joinToString(" · ")) to tr
    }
    return Found(hits, artists.values.take(8).map { Album("ar-" + norm(it), it, "Artist") }, shown.map { it.first },
        shown.associate { it.first.id to it.second })
}
