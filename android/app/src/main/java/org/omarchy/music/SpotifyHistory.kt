package org.omarchy.music

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.decodeToSequence
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream

/** What you played on Spotify, for Wrapped: from the data download Spotify gives you
 *  (the zip from your account's privacy page, the extended streaming history or the
 *  past year's), and from your last plays through the Spotify connection, picked up
 *  each time the app starts. Spotify's API only remembers the last 50, so the zip is
 *  what brings the years back. A play counts from 30 seconds, as Spotify counts streams;
 *  podcasts and videos are left out. Nothing goes to Last.fm: it takes nothing older than
 *  two weeks. Wrapped puts these together with your scrobbles, each play once. */
object SpotifyHistory {
    @Serializable
    data class Play(val t: Long, val artist: String, val title: String, val album: String = "", val ms: Long = 0)

    @Serializable
    private data class Saved(val plays: List<Play> = emptyList(), val api_after: Long = 0)

    private lateinit var file: File
    private var saved: Saved? = null

    /** What's kept, read from its file the first time (blocking). */
    @Synchronized private fun stored(): Saved = saved ?: (readText(file)?.let {
        runCatching { JSON.decodeFromString(Saved.serializer(), it) }.getOrNull() } ?: Saved()).also { saved = it; count = it.plays.size }

    val plays: List<Play> get() = stored().plays
    var count by mutableStateOf(0)           // for Settings
        private set
    var busy by mutableStateOf<String?>(null)
        private set

    fun init(c: Context) {
        file = File(c.filesDir, "spotify-history.json")
        Music.bg({ stored() })
    }

    private fun keep(s: Saved) {
        saved = s
        writeAtomic(file, JSON.encodeToString(Saved.serializer(), s))
        count = s.plays.size
    }

    private val Play.end get() = t + ms / 1000

    /** Add plays, but not those you have already: the same song starting or stopping within
     *  two minutes (the API knows when a song stopped, not for how long it played). */
    @Synchronized private fun add(new: List<Play>, apiAfter: Long? = null): Int {
        val old = stored()
        val have = HashMap<String, MutableList<Play>>()
        for (p in old.plays) have.getOrPut(cleanTitle(p.title)) { ArrayList() } += p
        val fresh = new.filter { p ->
            val same = have.getOrPut(cleanTitle(p.title)) { ArrayList() }
            same.none { Math.abs(it.t - p.t) < 120 || Math.abs(it.end - p.end) < 120 }.also { if (it) same += p }
        }
        val after = apiAfter ?: old.api_after
        if (fresh.isNotEmpty() || after != old.api_after) keep(Saved((old.plays + fresh).sortedBy { it.t }, after))
        return fresh.size
    }

    @Synchronized fun clear() = keep(Saved(api_after = stored().api_after))

    // ---- the data download ----

    private val BASIC_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** A play in a history file. The extended history: ts (when it stopped, UTC), ms_played,
     *  master_metadata_*; the past year's: endTime ("2024-01-31 18:02", UTC), artistName, trackName, msPlayed. */
    @Serializable
    private data class Entry(
        val ts: String? = null, val ms_played: Long? = null, val master_metadata_track_name: String? = null,
        val master_metadata_album_artist_name: String? = null, val master_metadata_album_album_name: String? = null,
        val endTime: String? = null, val msPlayed: Long? = null, val trackName: String? = null, val artistName: String? = null,
    )

    /** One history file's plays, read a play at a time: the extended history's files run to 12 MB. */
    @OptIn(ExperimentalSerializationApi::class)
    internal fun parse(input: InputStream, out: MutableList<Play>) {
        for (e in JSON.decodeToSequence(input, Entry.serializer(), DecodeSequenceMode.ARRAY_WRAPPED)) {
            val end = e.ts?.let { runCatching { Instant.parse(it).epochSecond }.getOrNull() }
                ?: e.endTime?.let { runCatching { LocalDateTime.parse(it, BASIC_TIME).toEpochSecond(ZoneOffset.UTC) }.getOrNull() } ?: continue
            val ms = e.ms_played ?: e.msPlayed ?: 0
            val title = e.master_metadata_track_name ?: e.trackName
            val artist = e.master_metadata_album_artist_name ?: e.artistName
            if (ms >= 30_000 && !title.isNullOrBlank() && !artist.isNullOrBlank() && artist != "Unknown Artist")
                out += Play(end - ms / 1000, artist, title, e.master_metadata_album_album_name ?: "", ms)
        }
    }

    internal fun wanted(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return name.endsWith(".json") && (name.startsWith("Streaming_History_Audio") ||
            (name.startsWith("StreamingHistory") && "podcast" !in name.lowercase() && "video" !in name.lowercase()))
    }

    /** Read the zip (or one of its .json files) at uri; how many plays were new (blocking). */
    fun importFile(ctx: Context, uri: Uri): Int {
        val out = ArrayList<Play>()
        busy = "Reading your Spotify history…"
        try {
            val stream = BufferedInputStream(ctx.contentResolver.openInputStream(uri) ?: error("couldn't open that file"))
            stream.use { s ->
                s.mark(4)
                val zip = ByteArray(2).let { s.read(it) == 2 && it[0] == 'P'.code.toByte() && it[1] == 'K'.code.toByte() }
                s.reset()
                if (zip) {
                    val z = ZipInputStream(s)
                    var files = 0
                    while (true) {
                        val e = z.nextEntry ?: break
                        if (!e.isDirectory && wanted(e.name)) {
                            files++
                            busy = "Reading ${e.name.substringAfterLast('/')}…"
                            parse(object : java.io.FilterInputStream(z) { override fun close() {} }, out)
                        }
                    }
                    if (files == 0) error("there's no streaming history in that zip. Spotify's download has Streaming_History_Audio files (or StreamingHistory_music)")
                } else parse(s, out)
            }
            if (out.isEmpty()) error("no songs played for 30 seconds or more in it")
            busy = "Adding ${"%,d".format(out.size)} plays…"
            return add(out)
        } finally {
            busy = null
        }
    }

    // ---- the Spotify connection ----

    /** Your last plays since the last look (blocking); how many were new. 0 if Spotify isn't connected. */
    fun sync(): Int {
        val sp = Music.sources["spotify"] as? Spotify ?: return 0
        if (!sp.signedIn) return 0
        val after = stored().api_after
        val items = sp.recentlyPlayed(after)
        var newest = after
        val got = items.mapNotNull { it ->
            val at = runCatching { Instant.parse(it["played_at"].str) }.getOrNull() ?: return@mapNotNull null
            newest = maxOf(newest, at.toEpochMilli())
            val t = trackFromSpotify(it["track"]) ?: return@mapNotNull null
            val ms = Math.round(t.duration * 1000)
            Play(at.epochSecond - ms / 1000, t.artist.split(", ")[0], t.title, t.album, ms)   // played_at is when it ended
        }
        return add(got, newest)
    }
}
