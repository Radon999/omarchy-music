package org.omarchy.music

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.text.Normalizer

// The desktop's 1.2 additions, on the phone: favorites, skips, a song's own volume, telling
// copies of a song apart, and where downloads go. The same files and rules as the desktop's.

/** Letters and digits only, run together: "That’s All I Need" and "Thats all I need" are one. */
fun squashKey(s: String?): String =
    Normalizer.normalize(s ?: "", Normalizer.Form.NFKD).filter { it.code < 128 }.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }

/** Each artist of t, squashed, without a leading "the". */
fun looseArtists(t: Track): Set<String> =
    t.artist.split(Regex("(?i),|&|\\bfeat\\.?|\\bft\\.?|\\bx\\b|\\band\\b")).map { squashKey(it).removePrefix("the") }
        .filter { it.isNotEmpty() }.toSet()

/** A song for finding duplicates: its title and lead artist, squashed (punctuation, accents,
 *  "the", feat. credits and (Remastered)-type notes don't count). */
fun looseKey(t: Track): Pair<String, String> =
    squashKey(cleanTitle(t.title)) to squashKey(t.artist.split(",")[0]).removePrefix("the")

/** Whether a and b are likely the same song, to ask about: the same or nearly the same title by an
 *  artist they share (or nearly the same name), or nearly the same title on the same album. Lengths
 *  and other tags aren't looked at: they differ between copies of one song. */
fun maybeSame(a: Track, b: Track): Boolean {
    val ta = squashKey(cleanTitle(a.title))
    val tb = squashKey(cleanTitle(b.title))
    if (ta.isEmpty() || tb.isEmpty()) return false
    val digits = Regex("\\d+")
    if (digits.findAll(ta).map { it.value }.toList() != digits.findAll(tb).map { it.value }.toList()) return false
    val title = if (ta == tb) 1.0 else similar(ta, tb)
    val inside = minOf(ta.length, tb.length) >= 4 && (ta in tb || tb in ta)
    if (title < 0.75 && !inside) return false
    val aa = looseArtists(a)
    val ab = looseArtists(b)
    val artist = aa.any { it in ab } || aa.any { x -> ab.any { y ->
        similar(x, y) >= 0.85 || (x.length >= 4 && y.length >= 4 && (x in y || y in x)) } }
    val album = squashKey(a.album).let { it.isNotEmpty() && it == squashKey(b.album) }
    return (artist && (title >= 0.85 || ta in tb || tb in ta)) || (album && title >= 0.75)
}

/** Whether Upgrade can swap t: a file of yours on the phone (not one of the app's downloads). */
fun upgradable(t: Track) = t.isFile && t.file?.startsWith("content://media/") == true

private fun <V> readMap(file: File, ser: kotlinx.serialization.KSerializer<V>): MutableMap<String, V> =
    readText(file)?.let { runCatching { JSON.decodeFromString(MapSerializer(String.serializer(), ser), it) }.getOrNull() }
        ?.toMutableMap() ?: mutableMapOf()

private fun songId(t: Track) = songKey(t).let { "${it.first}|${it.second}" }

@Serializable
data class Fav(val track: Track, val added: Double = 0.0)

/** The songs you starred, kept by song (the same song from any service is one favorite): the
 *  Favorites and Not favorited lists. The desktop's favorites.json. */
class Favorites(private val file: File) {
    private val items = readMap(file, Fav.serializer())

    fun has(t: Track) = songId(t) in items
    val size get() = items.size

    @Synchronized
    fun set(tracks: List<Track>, on: Boolean) {
        for (t in tracks) if (on) items.putIfAbsent(songId(t), Fav(t.slim(), now())) else items.remove(songId(t))
        writeAtomic(file, JSON.encodeToString(MapSerializer(String.serializer(), Fav.serializer()), items))
    }

    /** Your favorites, the last one starred first. */
    fun tracks(): List<Track> = items.values.sortedByDescending { it.added }.map { it.track }

    /** The ones starred between start and end (seconds). */
    fun addedBetween(start: Double, end: Double) = items.values.filter { it.added >= start && it.added < end }
        .sortedByDescending { it.added }.map { it.track }
}

@Serializable
data class SkipEntry(val skips: Int = 0, val full: Int = 0, val last_skip: Double = 0.0, val track: Track? = null,
                     val skips_at: List<Long> = emptyList(), val full_at: List<Long> = emptyList())

/** How you take each song: how often you skipped it (went on to another with more than SKIP_LEFT
 *  seconds of it left) and how often you heard it to the end. Kept by song. Last.fm keeps no
 *  skips, so they're counted from the day this came in. The desktop's skips file. */
class SongStats(private val file: File) {
    private val items = readMap(file, SkipEntry.serializer())

    private fun bump(t: Track, skip: Boolean) {
        val e = items[songId(t)] ?: SkipEntry()
        val at = now().toLong()
        items[songId(t)] = if (skip) e.copy(skips = e.skips + 1, last_skip = now(), track = t.slim(), skips_at = (e.skips_at + at).takeLast(300))
            else e.copy(full = e.full + 1, track = t.slim(), full_at = (e.full_at + at).takeLast(300))
        writeAtomic(file, JSON.encodeToString(MapSerializer(String.serializer(), SkipEntry.serializer()), items))
    }

    @Synchronized fun skipped(t: Track) = bump(t, true)
    @Synchronized fun heardOut(t: Track) = bump(t, false)

    fun get(t: Track) = items[songId(t)]?.let { it.skips to it.full } ?: (0 to 0)

    /** (track, skips, heard out) of every song you've skipped or heard out. */
    fun rows(): List<Triple<Track, Int, Int>> = items.values.mapNotNull { e -> e.track?.let { Triple(it, e.skips, e.full) } }

    data class Habits(val skips: Int, val full: Int, val never: List<Pair<Track, Int>>, val neverCount: Int,
                      val mostSkipped: List<Pair<Track, Int>>, val favsAdded: List<Track>, val favsTotal: Int)

    /** For Wrapped: how you took songs between start and end (seconds). */
    fun habits(start: Double, end: Double, favorites: Favorites?): Habits {
        val rows = items.values.mapNotNull { e ->
            val t = e.track ?: return@mapNotNull null
            val sk = e.skips_at.count { it >= start && it < end }
            val fu = e.full_at.count { it >= start && it < end }
            if (sk + fu > 0) Triple(t, sk, fu) else null
        }
        val never = rows.filter { it.second == 0 && it.third >= 2 }.sortedByDescending { it.third }
        val most = rows.filter { it.second > 0 }.sortedWith(compareByDescending<Triple<Track, Int, Int>> { it.second }.thenBy { it.third })
        return Habits(rows.sumOf { it.second }, rows.sumOf { it.third }, never.take(5).map { it.first to it.third }, never.size,
            most.take(5).map { it.first to it.second }, favorites?.addedBetween(start, end) ?: emptyList(), favorites?.size ?: 0)
    }

    companion object {
        const val SKIP_LEFT = 15   // seconds left: more than this, and going on was a skip
        fun rate(skips: Int, full: Int) = if (skips + full > 0) skips.toDouble() / (skips + full) else 0.0
    }
}

/** The volume boost you gave songs (Volume…), in dB: quiet unreleased songs, mostly. Kept by song.
 *  The desktop's song-volume.json. */
class SongGains(private val file: File) {
    private val items = readMap(file, Double.serializer())

    fun get(t: Track): Double = items[songId(t)] ?: 0.0

    @Synchronized
    fun set(tracks: List<Track>, db: Double) {
        val v = Math.round(db.coerceIn(LOW, HIGH) * 10) / 10.0
        for (t in tracks) if (v == 0.0) items.remove(songId(t)) else items[songId(t)] = v
        writeAtomic(file, JSON.encodeToString(MapSerializer(String.serializer(), Double.serializer()), items))
    }

    companion object {
        const val LOW = -12.0
        const val HIGH = 12.0
    }
}

/** name made safe as one folder or file name. */
fun pathPart(name: String?, fallback: String = "Unknown"): String =
    Regex("[<>:\"/\\\\|?*\\x00-\\x1f]").replace(name ?: "", "_").trim().trimEnd('.', ' ').ifEmpty { fallback }.take(120)

/** Where a download goes in a download folder of yours (a folder under Music/, as "Music/Mine/"):
 *  (its folder, its file name) as Artist/Albums|EPs|Singles/Album/, "NN Title.flac". */
fun downloadPlace(root: String, artist: String, album: String, kind: String?, number: Long?, title: String): Pair<String, String> {
    val sub = when (kind?.uppercase()) { "EP" -> "EPs"; "SINGLE" -> "Singles"; else -> "Albums" }
    val folder = root.trimEnd('/') + "/" + pathPart(artist, "Unknown artist") + "/" + sub + "/" + pathPart(album.ifEmpty { title }, "Unknown album") + "/"
    val name = (if (number != null && number > 0) "%02d ".format(number) else "") + pathPart(title, "Untitled") + ".flac"
    return folder to name
}

/** tracks as an .m3u8 playlist: a file path where the song is on the phone, else its page on its service. */
fun m3u8Text(tracks: List<Track>, fileOf: (Track) -> String?): String = buildString {
    append("#EXTM3U\n")
    for (t in tracks) {
        append("#EXTINF:${Math.round(t.duration)},${t.artist} - ${t.title}\n")
        append((fileOf(t) ?: songPage(t)) + "\n")
    }
}

/** The page of a song on its service, for a playlist file. */
fun songPage(t: Track): String = when (t.source) {
    "tidal" -> "https://tidal.com/browse/track/${t.tidal ?: t.id.removePrefix("td")}"
    "spotify" -> "https://open.spotify.com/track/${t.spotify ?: t.id}"
    "apple" -> "https://music.apple.com/song/${t.apple ?: t.id}"
    else -> "https://music.youtube.com/watch?v=${t.id}"
}
