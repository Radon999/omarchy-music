package org.omarchy.music

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** Every play added up, from wherever you played it: Last.fm's (all time: your 1,000 most
 *  played songs and 400 most played artists), your Spotify history's that Last.fm doesn't
 *  have (a Spotify play Last.fm scrobbled too counts once), and without Last.fm, the plays
 *  here. Most played, the Artists tab and the artist pages show these; the desktop adds
 *  them up the same way. Added up again in the background when they're older than [FOR]
 *  seconds or what goes into them changed, and kept between starts. */
object Stats {
    @Serializable
    data class Song(val title: String, val artist: String, val album: String = "", val thumb: String = "",
                    val plays: Int = 0, val lastfm: Int = 0, val spotify: Int = 0, val here: Int = 0)

    @Serializable
    data class ArtistPlays(val name: String, val plays: Int = 0, val lastfm: Int = 0, val spotify: Int = 0,
                           val here: Int = 0, val thumb: String = "")

    @Serializable
    private data class Saved(val songs: List<Song> = emptyList(), val artists: List<ArtistPlays> = emptyList(),
                             val built: Double = 0.0, val sig: String = "")

    const val FOR = 600          // seconds the counts are good for
    private const val TOP_PAGES = 5      // Last.fm's top songs, 200 a page
    private const val ARTIST_PAGES = 2   // and top artists

    var songs by mutableStateOf(listOf<Song>())            // most played first
        private set
    var artists by mutableStateOf(listOf<ArtistPlays>())   // most played first
        private set
    var busy by mutableStateOf(false)
        private set
    private var built = 0.0
    private var sig = ""
    private lateinit var ctx: Context
    private val file get() = File(ctx.filesDir, "stats.json")

    fun init(c: Context) {
        ctx = c.applicationContext
        Music.bg({ readText(file)?.let { runCatching { JSON.decodeFromString(Saved.serializer(), it) }.getOrNull() } }) { s, _ ->
            if (s != null && songs.isEmpty()) { songs = s.songs; artists = s.artists; built = s.built; sig = s.sig }
        }
    }

    private fun inputs() = listOf(if (LastFm.connected) LastFm.user else "", SpotifyHistory.count,
        if (LastFm.connected) 0 else Music.plays.items.values.sumOf { it.plays }).joinToString("|")

    /** Where the counts come from, for the label over Most played. */
    val sources: List<String> get() {
        val top = songs.take(200)
        return listOfNotNull("Last.fm".takeIf { top.any { it.lastfm > 0 } }, "Spotify".takeIf { top.any { it.spotify > 0 } },
            "here".takeIf { top.any { it.here > 0 } })
    }

    /** Add them up again, off the main thread, if they're stale. */
    fun refresh(force: Boolean = false) {
        if (!::ctx.isInitialized || busy || !(force || sig != inputs() || now() - built > FOR)) return
        busy = true
        Music.bg({ build() }) { s, err ->
            busy = false
            if (s != null) { songs = s.songs; artists = s.artists; built = s.built; sig = s.sig }
            else {
                built = now() - FOR + 120   // try again in two minutes
                android.util.Log.w("omarchy-music", "stats", err)
            }
            Music.musicVersion++
        }
    }

    private fun build(): Saved {
        val sig = inputs()
        val fm = LastFm.connected
        val tracks = ArrayList<LastFm.Top>()
        val fmArtists = ArrayList<LastFm.Top>()
        if (fm) {
            for (p in 1..TOP_PAGES) { val got = LastFm.top("track", 200, page = p); tracks += got; if (got.size < 200) break }
            for (p in 1..ARTIST_PAGES) { val got = LastFm.top("artist", 200, page = p); fmArtists += got; if (got.size < 200) break }
        }
        var extra = SpotifyHistory.plays
        if (extra.isNotEmpty() && fm) {
            val first = LastFm.info().registered.takeIf { it > 0 }?.let { year(it) } ?: Int.MAX_VALUE
            val years = extra.map { year(it.t) }.distinct().sorted().filter { it >= first }
            extra = notScrobbled(extra, years.flatMap { Wrapped.scrobbleTimes(ctx, it) })
        }
        val here = if (fm) emptyList() else Music.plays.items.values.map { it.track to it.plays }
        val (s, a) = combine(tracks, fmArtists, extra, here, fm)
        val out = Saved(s, a, now(), sig)
        writeAtomic(file, JSON.encodeToString(Saved.serializer(), out))
        return out
    }

    private fun year(t: Long) = Instant.ofEpochSecond(t).atZone(ZoneId.systemDefault()).year

    /** The Spotify plays Last.fm doesn't have among its scrobbles (time, title): a scrobble of the
     *  same song from five minutes before a play began until five after it stopped is that play,
     *  and each scrobble stands for one play. */
    internal fun notScrobbled(plays: List<SpotifyHistory.Play>, scrobbles: List<Pair<Long, String>>): List<SpotifyHistory.Play> {
        val at = HashMap<String, MutableList<Long>>()
        for ((t, title) in scrobbles) at.getOrPut(norm(cleanTitle(title))) { ArrayList() } += t
        return plays.filter { p ->
            val times = at[norm(cleanTitle(p.title))] ?: return@filter true
            val i = times.indexOfFirst { it >= p.t - 300 && it <= p.t + p.ms / 1000 + 300 }
            if (i >= 0) times.removeAt(i)
            i < 0
        }
    }

    private class Counts(val title: String, val artist: String, var album: String, var thumb: String) {
        var lastfm = 0; var spotify = 0; var here = 0
        val plays get() = lastfm + spotify + here
    }

    private fun MutableMap<Pair<String, String>, Counts>.count(title: String, artist: String, album: String, thumb: String,
                                                                 n: Int, source: String) {
        if (title.isBlank() || artist.isBlank() || n <= 0) return
        val c = getOrPut(songKey(Track("", title = title, artist = artist))) { Counts(title, artist, album, thumb) }
        when (source) { "lastfm" -> c.lastfm += n; "spotify" -> c.spotify += n; else -> c.here += n }
        if (c.album.isEmpty()) c.album = album
        if (c.thumb.isEmpty()) c.thumb = thumb
    }

    /** The counts from each source, put together: (songs, artists), most played first. */
    internal fun combine(tracks: List<LastFm.Top>, fmArtists: List<LastFm.Top>, spotify: List<SpotifyHistory.Play>,
                         here: List<Pair<Track, Int>>, fm: Boolean): Pair<List<Song>, List<ArtistPlays>> {
        val songs = LinkedHashMap<Pair<String, String>, Counts>()
        for (x in tracks) songs.count(x.name, x.artist, "", x.image.takeIf { LASTFM_BLANK !in it } ?: "", x.plays, "lastfm")
        for ((t, n) in here) songs.count(t.title, t.artist, t.album, t.thumb, n, "here")
        for (p in spotify) songs.count(p.title, p.artist, p.album, "", 1, "spotify")
        val artists = LinkedHashMap<Pair<String, String>, Counts>()
        for (x in fmArtists) artists.count(x.name, x.name, "", "", x.plays, "lastfm")   // Last.fm's own totals
        for (s in songs.values) {
            val lead = s.artist.split(",")[0].trim()
            if (!fm) artists.count(lead, lead, "", s.thumb, s.lastfm, "lastfm")
            artists.count(lead, lead, "", s.thumb, s.spotify, "spotify")
            artists.count(lead, lead, "", s.thumb, s.here, "here")
            artists[songKey(Track("", title = lead, artist = lead))]?.let { if (it.thumb.isEmpty()) it.thumb = s.thumb }
        }
        return songs.values.sortedByDescending { it.plays }.map { Song(it.title, it.artist, it.album, it.thumb, it.plays, it.lastfm, it.spotify, it.here) } to
            artists.values.sortedByDescending { it.plays }.map { ArtistPlays(it.title, it.plays, it.lastfm, it.spotify, it.here, it.thumb) }
    }

    /** Artist name's songs (theirs or with them), most played first. */
    fun artistSongs(name: String): List<Song> {
        val n = norm(name)
        return songs.filter { s -> s.artist.split(Regex(",|&| x | feat\\.? | ft\\.? ")).any { norm(it) == n } }
    }

    fun artist(name: String): ArtistPlays? = norm(name).let { n -> artists.firstOrNull { norm(it.name) == n } }
}
