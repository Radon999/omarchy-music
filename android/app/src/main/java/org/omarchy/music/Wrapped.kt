package org.omarchy.music

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.PairSerializer
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Wrapped: your year in music (or all of it) from your Last.fm history, the desktop's
 *  numbers worked out the same way. A big year takes a minute the first time (Last.fm
 *  hands out 200 scrobbles a request); then it's kept and only the new ones are read. */
object Wrapped {
    private const val LASTFM_BLANK = "2a96cbd8b46e442fc41c2b86b821562f"   // Last.fm's grey star, for "no picture"
    private val NOT_GENRES = setOf("seen live", "favorites", "favourites", "favorite", "favourite", "my favorites", "albums i own",
        "female vocalists", "male vocalists", "female vocalist", "male vocalist", "female", "male",
        "under 2000 listeners", "spotify", "beautiful", "awesome", "love", "amazing", "epic", "cool",
        "british", "american", "uk", "usa", "canadian", "australian", "dutch", "german", "french",
        "swedish", "norwegian", "korean", "japanese", "english", "all", "vocal", "singer-songwriter",
        "masterpiece", "best", "good", "great", "favorite songs", "catchy", "sexy", "fun")
    private val GENRE_NAMES = mapOf("edm" to "EDM", "idm" to "IDM", "r&b" to "R&B", "rnb" to "R&B", "dnb" to "DnB",
        "uk garage" to "UK Garage", "lo-fi" to "Lo-fi", "lofi" to "Lo-fi", "k-pop" to "K-pop", "j-pop" to "J-pop",
        "hip-hop" to "Hip-hop", "hip hop" to "Hip-hop", "uk drill" to "UK Drill")
    val MONTHS = "Jan Feb Mar Apr May Jun Jul Aug Sep Oct Nov Dec".split(" ")
    val MONTH_NAMES = "January February March April May June July August September October November December".split(" ")
    val WEEKDAYS = "Mondays Tuesdays Wednesdays Thursdays Fridays Saturdays Sundays".split(" ")

    /** The year Wrapped opens on: this one, or last year until March, while this one has hardly begun. */
    fun year(): Int {
        val now = LocalDate.now()
        return if (now.monthValue >= 3) now.year else now.year - 1
    }

    data class Song(val title: String, val artist: String, val plays: Int, val art: String, val first: Long? = null)
    data class AlbumPlays(val name: String, val artist: String, val plays: Int, val art: String)
    data class Data(
        val period: Int?, val user: String, val years: List<Int>, val since: Int, val empty: Boolean = false,
        val scrobbles: Int = 0, val artistCount: Int = 0, val songCount: Int = 0,
        val artists: List<Pair<String, Int>> = emptyList(), val songs: List<Song> = emptyList(), val albums: List<AlbumPlays> = emptyList(),
        val topArtistSong: Pair<String, Int>? = null, val months: List<Int> = List(12) { 0 }, val hours: List<Int> = List(24) { 0 },
        val weekdays: List<Int> = List(7) { 0 }, val streak: Int = 0, val days: Int = 0, val busiest: LocalDate? = null,
        val busiestN: Int = 0, val first: LastFm.Played? = null, val minutes: Int = 0, val genres: List<String> = emptyList(),
        val artistImage: String = "", val yearsCount: List<Pair<Int, Int>> = emptyList(),
    )

    @Serializable
    private data class Row(val t: Long, val artist: String, val title: String, val album: String, val image: String)
    @Serializable
    private data class Saved(val rows: List<Row> = emptyList(), val until: Long = 0)

    private fun genreName(tag: String): String {
        val t = tag.lowercase().trim()
        return GENRE_NAMES[t] ?: t.split(" ").joinToString(" ") { w -> if (w == w.uppercase()) w else w.replaceFirstChar { it.uppercase() } }
    }

    private fun isGenre(tag: String, artist: String): Boolean {
        val t = tag.lowercase().trim()
        return t !in NOT_GENRES && t != artist.lowercase() && t.length <= 28 && !Regex("\\d+s?|\\d{2,4}s").matches(t) &&
            !Regex("seen|favou?rite|vocalist|listeners|albums? i|my |best of").containsMatchIn(t)
    }

    private fun picture(url: String?) = if (url.isNullOrEmpty() || LASTFM_BLANK in url || "/artist//" in url) "" else url
    private fun bigLastfm(url: String) = url.replace("/300x300/", "/770x0/").replace("/174s/", "/770x0/")

    private fun deezerArtistImage(name: String): String? {
        val data = httpJson("https://api.deezer.com/search/artist?q=${enc(name)}&limit=5")["data"].list
            .sortedByDescending { similar(norm(it["name"].str), norm(name)) }
        val best = data.firstOrNull() ?: return null
        return if (similar(norm(best["name"].str), norm(name)) > 0.85) best["picture_xl"].str ?: best["picture_big"].str else null
    }

    private fun deezerCover(artist: String, title: String): String? =
        httpJson("https://api.deezer.com/search?q=${enc("artist:\"$artist\" track:\"$title\"")}&limit=1")["data"][0].path("album", "cover_xl").str

    private fun local(t: Long) = LocalDateTime.ofInstant(Instant.ofEpochSecond(t), ZoneId.systemDefault())
    private fun startOf(y: Int) = LocalDate.of(y, 1, 1).atStartOfDay(ZoneId.systemDefault()).toEpochSecond()

    private fun yearStats(base: Data, rows: List<Row>): Data {
        val artists = LinkedHashMap<String, Int>(); val songs = LinkedHashMap<Pair<String, String>, Int>()
        val albums = LinkedHashMap<Pair<String, String>, Int>()
        val artistName = HashMap<String, String>(); val songName = HashMap<Pair<String, String>, Pair<String, String>>()
        val albumName = HashMap<Pair<String, String>, Pair<String, String>>()
        val art = HashMap<Pair<String, String>, String>(); val albumArt = HashMap<Pair<String, String>, String>()
        val firstPlay = HashMap<Pair<String, String>, Long>()
        val months = IntArray(12); val hours = IntArray(24); val weekdays = IntArray(7); val days = LinkedHashMap<LocalDate, Int>()
        for (r in rows) {
            val ka = r.artist.lowercase(); val ks = ka to r.title.lowercase()
            artists.merge(ka, 1, Int::plus); songs.merge(ks, 1, Int::plus)
            artistName.putIfAbsent(ka, r.artist); songName.putIfAbsent(ks, r.title to r.artist); firstPlay.putIfAbsent(ks, r.t)
            val img = picture(r.image)
            if (img.isNotEmpty()) art[ks] = img
            if (r.album.isNotEmpty()) {
                val kb = ka to r.album.lowercase()
                albums.merge(kb, 1, Int::plus); albumName.putIfAbsent(kb, r.album to r.artist)
                if (img.isNotEmpty()) albumArt[kb] = img
            }
            val d = local(r.t)
            months[d.monthValue - 1]++; hours[d.hour]++; weekdays[d.dayOfWeek.value - 1]++
            days.merge(d.toLocalDate(), 1, Int::plus)
        }
        var streak = 0; var run = 0; var prev: LocalDate? = null
        for (day in days.keys.sorted()) {
            run = if (prev != null && prev.plusDays(1) == day) run + 1 else 1
            streak = maxOf(streak, run); prev = day
        }
        // Most common first; ties keep the order first seen, like Python's Counter.
        fun <K> common(m: Map<K, Int>) = m.entries.sortedByDescending { it.value }
        val busiest = common(days).first()
        val topArtist = common(artists).first().key
        val byTop = common(songs).firstOrNull { it.key.first == topArtist }
        val t0 = rows.first()
        return base.copy(
            scrobbles = rows.size, artistCount = artists.size, songCount = songs.size,
            artists = common(artists).take(30).map { artistName[it.key]!! to it.value },
            songs = common(songs).take(10).map { (k, n) -> Song(songName[k]!!.first, songName[k]!!.second, n, art[k] ?: "", firstPlay[k]) },
            albums = common(albums).take(5).map { (k, n) -> AlbumPlays(albumName[k]!!.first, albumName[k]!!.second, n, albumArt[k] ?: "") },
            topArtistSong = byTop?.let { songName[it.key]!!.first to it.value },
            months = months.toList(), hours = hours.toList(), weekdays = weekdays.toList(), streak = streak, days = days.size,
            busiest = busiest.key, busiestN = busiest.value, first = LastFm.Played(t0.t, t0.artist, t0.title, t0.album, t0.image),
        )
    }

    private val durSer = MapSerializer(String.serializer(), Double.serializer())
    private val tagSer = MapSerializer(String.serializer(), ListSerializer(PairSerializer(String.serializer(), Int.serializer())))

    /** Minutes of music in rows: each song's length (yours, else Last.fm's), the typical length for the few nobody knows. */
    private fun minutes(dir: File, rows: List<Row>): Int {
        val known = HashMap<String, Double>()
        for (e in Music.lib.items.values.toList()) if (e.duration > 0)
            known["${norm(e.artist.split(",")[0])}|${norm(cleanTitle(e.title))}"] = e.duration
        val file = File(dir, "durations.json")
        val cache = readText(file)?.let { runCatching { JSON.decodeFromString(durSer, it) }.getOrNull() }?.toMutableMap() ?: mutableMapOf()
        val plays = rows.groupingBy { it.artist to it.title }.eachCount()
        val lengths = HashMap<Pair<String, String>, Double>()
        var asked = 0
        for ((k, _) in plays.entries.sortedByDescending { it.value }) {
            val key = "${norm(k.first)}|${norm(cleanTitle(k.second))}"
            var sec = known[key] ?: cache[key]
            if (sec == null && asked < 80) {   // Last.fm, for your most played; one request each
                asked++
                sec = runCatching { LastFm.duration(k.first, k.second) }.getOrDefault(0.0)
                cache[key] = sec
            }
            if (sec != null && sec > 0) lengths[k] = sec
        }
        writeAtomic(file, JSON.encodeToString(durSer, cache))
        val typical = lengths.values.sorted().let { if (it.isEmpty()) 210.0 else it[it.size / 2] }
        return Math.round(plays.entries.sumOf { (k, n) -> n * (lengths[k] ?: typical) } / 60).toInt()
    }

    /** Your top genres: each top artist's Last.fm tags, weighted by how much you played them. */
    private fun genres(dir: File, artists: List<Pair<String, Int>>): List<String> {
        val file = File(dir, "tags.json")
        val cache = readText(file)?.let { runCatching { JSON.decodeFromString(tagSer, it) }.getOrNull() }?.toMutableMap() ?: mutableMapOf()
        val score = LinkedHashMap<String, Double>()
        for ((name, plays) in artists.take(30)) {
            val all = cache[name.lowercase()] ?: runCatching { LastFm.artistTags(name) }.getOrDefault(emptyList()).also { cache[name.lowercase()] = it }
            val tags = all.filter { isGenre(it.first, name) }.take(4)
            val total = tags.sumOf { it.second }.takeIf { it > 0 } ?: 1
            for ((t, c) in tags) score.merge(t.lowercase(), plays.toDouble() * c / total, Double::plus)
        }
        writeAtomic(file, JSON.encodeToString(tagSer, cache))
        return score.entries.sortedByDescending { it.value }.take(5).map { genreName(it.key) }
    }

    /** Everything Wrapped shows for a year (or null: all time). It blocks. */
    fun load(ctx: Context, period: Int?, progress: (String) -> Unit): Data {
        val dir = File(ctx.filesDir, "wrapped").apply { mkdirs() }
        progress("Opening your Last.fm…")
        val me = LastFm.info()
        val thisYear = LocalDate.now().year
        val firstYear = if (me.registered > 0) local(me.registered).year else thisYear
        val years = (thisYear downTo firstYear).toList()
        val base = Data(period, me.name, years, firstYear)
        var data: Data
        if (period == null) {
            val counts = years.reversed().map { y ->
                progress("Counting your scrobbles · $y")
                y to LastFm.count(startOf(y), startOf(y + 1))
            }
            progress("Your all-time favourites…")
            if (me.playcount == 0) return base.copy(empty = true)
            data = base.copy(scrobbles = me.playcount, yearsCount = counts,
                artists = LastFm.top("artist", 30).map { it.name to it.plays },
                songs = LastFm.top("track", 10).map { Song(it.name, it.artist, it.plays, it.image) },
                albums = LastFm.top("album", 5).map { AlbumPlays(it.name, it.artist, it.plays, it.image) },
                minutes = Math.round(me.playcount * 3.5).toInt())   // about three and a half minutes a song
        } else {
            val start = startOf(period)
            val end = minOf(startOf(period + 1), (now()).toLong())
            val file = File(dir, "${safeName(LastFm.user.lowercase())}-$period.json")
            val saved = readText(file)?.let { runCatching { JSON.decodeFromString(Saved.serializer(), it) }.getOrNull() } ?: Saved()
            var rows = saved.rows
            val since = if (saved.until > 0) saved.until else start
            if (since < end - 60) {
                val new = LastFm.recent(since, end) { p, n -> progress("Reading your $period scrobbles · page $p of $n") }
                val have = rows.map { it.t to it.title }.toHashSet()
                rows = (rows + new.filter { (it.time to it.title) !in have }.map { Row(it.time, it.artist, it.title, it.album, it.image) })
                    .sortedBy { it.t }
                writeAtomic(file, JSON.encodeToString(Saved.serializer(), Saved(rows, end)))
            }
            if (rows.isEmpty()) return base.copy(empty = true)
            data = yearStats(base, rows)
            progress("Adding up your minutes…")
            data = data.copy(minutes = minutes(dir, rows))
        }
        progress("Finding your genres…")
        data = data.copy(genres = genres(dir, data.artists))
        progress("Getting the pictures…")
        data = data.copy(
            artistImage = data.artists.firstOrNull()?.let { runCatching { deezerArtistImage(it.first) }.getOrNull() } ?: "",
            songs = data.songs.mapIndexed { i, s ->
                val img = picture(s.art).let { if (i == 0 && it.isNotEmpty()) bigLastfm(it) else it }
                s.copy(art = img.ifEmpty { runCatching { deezerCover(s.artist, s.title) }.getOrNull() ?: "" })
            },
            albums = data.albums.mapIndexed { i, a ->
                val img = picture(a.art).let { if (i == 0 && it.isNotEmpty()) bigLastfm(it) else it }
                a.copy(art = img.ifEmpty { runCatching { deezerCover(a.artist, a.name) }.getOrNull() ?: "" })
            },
        )
        return data
    }
}
