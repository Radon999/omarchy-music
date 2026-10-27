package org.omarchy.music

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.PairSerializer
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Wrapped: your week, month or year in music (or all of it) from your Last.fm history, the desktop's
 *  numbers worked out the same way, with the Spotify plays you imported (SpotifyHistory)
 *  that Last.fm hasn't got. A big year takes a minute the first time (Last.fm hands out
 *  200 scrobbles a request); then it's kept and only the new ones are read. */
object Wrapped {
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

    /** What a Wrapped is of: a year, a month, a week (from its Monday) or all time. */
    sealed class Period {
        data class Year(val year: Int) : Period()
        data class Month(val month: YearMonth) : Period()
        data class Week(val monday: LocalDate) : Period()
        data object All : Period()

        val kind: String get() = when (this) { is Year -> "year"; is Month -> "month"; is Week -> "week"; All -> "all" }
        fun start(): LocalDate = when (this) {
            is Year -> LocalDate.of(year, 1, 1); is Month -> month.atDay(1); is Week -> monday; All -> LocalDate.of(1970, 1, 1)
        }
        fun end(): LocalDate = when (this) {
            is Year -> LocalDate.of(year + 1, 1, 1); is Month -> month.plusMonths(1).atDay(1)
            is Week -> monday.plusDays(7); All -> LocalDate.now().plusDays(1)
        }
        /** "2026", "October 2026", "Week of Oct 19–25", "All time". */
        val name: String get() = when (this) {
            is Year -> "$year"; is Month -> "${Wrapped.MONTH_NAMES[month.monthValue - 1]} ${month.year}"
            is Week -> "Week of ${Wrapped.weekSpan(monday)}"; All -> "All time"
        }
        /** The big word on the first slide: "2026", "October", "Oct 19–25". */
        val title: String get() = when (this) {
            is Year -> "$year"; is Month -> Wrapped.MONTH_NAMES[month.monthValue - 1]; is Week -> Wrapped.weekSpan(monday); All -> "ALL TIME"
        }
        /** For "your first song of …": "2026", "October", "the week". */
        val of: String get() = when (this) {
            is Year -> "$year"; is Month -> Wrapped.MONTH_NAMES[month.monthValue - 1]; is Week -> "the week"; All -> "all time"
        }
        /** Its kept scrobbles' file name (a year's is the same as before weeks and months). */
        val key: String get() = when (this) { is Year -> "$year"; is Month -> "m$month"; is Week -> "w$monday"; All -> "all" }

        companion object {
            fun current(kind: String): Period = when (kind) {
                "week" -> Week(LocalDate.now().with(DayOfWeek.MONDAY)); "month" -> Month(YearMonth.now())
                "year" -> Year(Wrapped.year()); else -> All
            }

            /** What the picker offers for a kind, the newest first: the last 12 weeks or months, or the years. */
            fun choices(kind: String, years: List<Int>): List<Pair<Period, String>> = when (kind) {
                "year" -> years.map { Year(it) to "$it" }
                "month" -> (0L until 12L).map { YearMonth.now().minusMonths(it) }
                    .map { Month(it) to "${Wrapped.MONTH_NAMES[it.monthValue - 1]} ${it.year}" }
                "week" -> (0L until 12L).map { i ->
                    val m = LocalDate.now().with(DayOfWeek.MONDAY).minusWeeks(i)
                    Week(m) to when (i) { 0L -> "This week"; 1L -> "Last week"; else -> Wrapped.weekSpan(m) }
                }
                else -> emptyList()
            }
        }
    }

    /** "Oct 19–25", or "Dec 28–Jan 3" across two months. */
    fun weekSpan(monday: LocalDate): String {
        val sunday = monday.plusDays(6)
        return if (monday.month == sunday.month) "${MONTHS[monday.monthValue - 1]} ${monday.dayOfMonth}–${sunday.dayOfMonth}"
        else "${MONTHS[monday.monthValue - 1]} ${monday.dayOfMonth}–${MONTHS[sunday.monthValue - 1]} ${sunday.dayOfMonth}"
    }

    data class Song(val title: String, val artist: String, val plays: Int, val art: String, val first: Long? = null)
    data class AlbumPlays(val name: String, val artist: String, val plays: Int, val art: String)
    data class Data(
        val period: Period, val user: String, val years: List<Int>, val since: Int, val empty: Boolean = false,
        val scrobbles: Int = 0, val artistCount: Int = 0, val songCount: Int = 0,
        val artists: List<Pair<String, Int>> = emptyList(), val songs: List<Song> = emptyList(), val albums: List<AlbumPlays> = emptyList(),
        val topArtistSong: Pair<String, Int>? = null, val months: List<Int> = List(12) { 0 }, val hours: List<Int> = List(24) { 0 },
        val weekdays: List<Int> = List(7) { 0 }, val streak: Int = 0, val days: Int = 0, val busiest: LocalDate? = null,
        val busiestN: Int = 0, val first: LastFm.Played? = null, val minutes: Int = 0, val genres: List<String> = emptyList(),
        val artistImage: String = "", val yearsCount: List<Pair<Int, Int>> = emptyList(),
        // weeks and months: plays a day; every period: how many days it has had so far, whether it's
        // still going, and how many scrobbles the same stretch before it had (to compare with)
        val daily: List<Int> = emptyList(), val spanDays: Int = 0, val partial: Boolean = false, val prevCount: Int? = null,
    )

    @Serializable
    private data class Row(val t: Long, val artist: String, val title: String, val album: String, val image: String,
                           val ms: Long = 0)   // how long it played, for Spotify's plays
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
    private fun epoch(d: LocalDate) = d.atStartOfDay(ZoneId.systemDefault()).toEpochSecond()

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

    /** Minutes of music in rows: how long Spotify says it played, else each song's length (yours,
     *  else Last.fm's), the typical length for the few nobody knows. */
    private fun minutes(dir: File, all: List<Row>): Int {
        val timed = all.sumOf { it.ms } / 1000.0
        val rows = all.filter { it.ms == 0L }
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
            if (sec == null && asked < 80 && LastFm.key.isNotEmpty()) {   // Last.fm, for your most played; one request each
                asked++
                sec = runCatching { LastFm.duration(k.first, k.second) }.getOrDefault(0.0)
                cache[key] = sec
            }
            if (sec != null && sec > 0) lengths[k] = sec
        }
        writeAtomic(file, JSON.encodeToString(durSer, cache))
        val typical = lengths.values.sorted().let { if (it.isEmpty()) 210.0 else it[it.size / 2] }
        return Math.round((timed + plays.entries.sumOf { (k, n) -> n * (lengths[k] ?: typical) }) / 60).toInt()
    }

    /** Your top genres: each top artist's Last.fm tags, weighted by how much you played them. */
    private fun genres(dir: File, artists: List<Pair<String, Int>>): List<String> {
        if (LastFm.key.isEmpty()) return emptyList()   // the tags come from Last.fm
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

    /** A period's scrobbles: kept, and only the new ones read from Last.fm. */
    private fun scrobbles(dir: File, period: Period, progress: (String) -> Unit): List<Row> {
        val start = epoch(period.start())
        val end = minOf(epoch(period.end()), now().toLong())
        if (end <= start) return emptyList()
        val file = File(dir, "${safeName(LastFm.user.lowercase())}-${period.key}.json")
        val saved = readText(file)?.let { runCatching { JSON.decodeFromString(Saved.serializer(), it) }.getOrNull() } ?: Saved()
        var rows = saved.rows
        val since = if (saved.until > 0) saved.until else start
        if (since < end - 60) {
            val new = LastFm.recent(since, end) { p, n -> progress("Reading your scrobbles · ${period.name} · page $p of $n") }
            val have = rows.map { it.t to it.title }.toHashSet()
            rows = (rows + new.filter { (it.time to it.title) !in have }.map { Row(it.time, it.artist, it.title, it.album, it.image) })
                .sortedBy { it.t }
            writeAtomic(file, JSON.encodeToString(Saved.serializer(), Saved(rows, end)))
        }
        return rows
    }

    /** Your scrobbles in year, as (time, title), from the cache Wrapped keeps (blocking: what's
     *  new is asked of Last.fm). For [Stats], to tell which Spotify plays Last.fm has too. */
    fun scrobbleTimes(ctx: Context, year: Int): List<Pair<Long, String>> =
        scrobbles(File(ctx.filesDir, "wrapped").apply { mkdirs() }, Period.Year(year)) {}.map { it.t to it.title }

    /** rows with your Spotify plays from start to end that Last.fm hasn't got. A play Last.fm
     *  has is the same song scrobbled from five minutes before it began until five after it stopped. */
    private fun withSpotify(rows: List<Row>, start: Long, end: Long): List<Row> {
        val plays = SpotifyHistory.plays.filter { it.t in start until end }
        if (plays.isEmpty()) return rows
        val scrobbled = HashMap<String, MutableList<Long>>()
        for (r in rows) scrobbled.getOrPut(cleanTitle(r.title)) { ArrayList() } += r.t
        val extra = plays.filter { p ->
            val times = scrobbled[cleanTitle(p.title)] ?: return@filter true
            val i = times.indexOfFirst { it >= p.t - 300 && it <= p.t + p.ms / 1000 + 300 }
            if (i >= 0) times.removeAt(i)   // each scrobble stands for one play
            i < 0
        }.map { Row(it.t, it.artist, it.title, it.album, "", it.ms) }
        return (rows + extra).sortedBy { it.t }
    }

    /** Everything Wrapped shows for a period. It blocks. */
    fun load(ctx: Context, period: Period, progress: (String) -> Unit): Data {
        val dir = File(ctx.filesDir, "wrapped").apply { mkdirs() }
        val fm = LastFm.connected
        val spotify = SpotifyHistory.plays
        if (!fm && spotify.isEmpty()) error("Connect Last.fm, or import your Spotify history, in Settings (⚙).")
        val me = if (fm) { progress("Opening your Last.fm…"); LastFm.info() } else null
        val thisYear = LocalDate.now().year
        var firstYear = me?.registered?.takeIf { it > 0 }?.let { local(it).year } ?: thisYear
        spotify.firstOrNull()?.let { firstYear = minOf(firstYear, local(it.t).year) }
        val years = (thisYear downTo firstYear).toList()
        val user = me?.name ?: (Music.sources["spotify"] as? Spotify)?.user?.ifEmpty { null } ?: "You"
        val base = Data(period, user, years, firstYear)
        var data: Data
        if (period == Period.All && spotify.isEmpty()) {   // Last.fm adds it all up: no need to read every scrobble
            val counts = years.reversed().map { y ->
                progress("Counting your scrobbles · $y")
                y to LastFm.count(startOf(y), startOf(y + 1))
            }
            progress("Your all-time favourites…")
            if (me!!.playcount == 0) return base.copy(empty = true)
            data = base.copy(scrobbles = me.playcount, yearsCount = counts,
                artists = LastFm.top("artist", 30).map { it.name to it.plays },
                songs = LastFm.top("track", 10).map { Song(it.name, it.artist, it.plays, it.image) },
                albums = LastFm.top("album", 5).map { AlbumPlays(it.name, it.artist, it.plays, it.image) },
                minutes = Math.round(me.playcount * 3.5).toInt())   // about three and a half minutes a song
        } else {
            val rows = if (period == Period.All)
                withSpotify(if (fm) years.reversed().flatMap { scrobbles(dir, Period.Year(it), progress) } else emptyList(), Long.MIN_VALUE, Long.MAX_VALUE)
            else withSpotify(if (fm) scrobbles(dir, period, progress) else emptyList(), epoch(period.start()), epoch(period.end()))
            if (rows.isEmpty()) return base.copy(empty = true)
            data = yearStats(base, rows)
            if (period != Period.All) {
                val start = epoch(period.start()); val naturalEnd = epoch(period.end())
                val end = minOf(naturalEnd, now().toLong())
                val first = period.start()
                val daily = if (period is Period.Year) emptyList() else {   // day by day, for a week or a month
                    val counts = IntArray(ChronoUnit.DAYS.between(first, period.end()).toInt())
                    for (r in rows) {
                        val i = ChronoUnit.DAYS.between(first, local(r.t).toLocalDate()).toInt()
                        if (i in counts.indices) counts[i]++
                    }
                    counts.toList()
                }
                progress("Comparing with before…")
                val length = naturalEnd - start   // as far into the period before as this one has gone
                val prev = if (fm) runCatching { LastFm.count(start - length, start - length + (end - start)) }.getOrNull() else null
                data = data.copy(daily = daily, spanDays = maxOf(1, ((end - start + 86399) / 86400).toInt()),
                    partial = end < naturalEnd - 60, prevCount = prev)
            }
            if (period == Period.All) {
                val perYear = rows.groupingBy { local(it.t).year }.eachCount()
                data = data.copy(yearsCount = years.reversed().map { it to (perYear[it] ?: 0) })
            }
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
