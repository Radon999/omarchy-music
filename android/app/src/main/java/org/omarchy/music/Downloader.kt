package org.omarchy.music

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Gets songs ready to play: Tidal's stream looked up (nothing saved), or the song
 *  downloaded as tagged FLAC into Music/Omarchy Music/. Songs Tidal has no lossless copy
 *  of stream in what there is (Tidal's AAC, else YouTube's audio), but are never saved lossy. */
class Downloader(private val ctx: Context, private val lib: Library, val tidal: Tidal) {
    data class Ready(val uri: String, val isDash: Boolean = false, val quality: String = "", val onDisk: Boolean = false,
                     val lossyFrom: String? = null)   // "Tidal" or "YouTube": no FLAC of it

    private val streams = HashMap<String, Pair<Ready, Double>>()   // id -> (stream, when looked up)
    private val active = HashSet<String>()                          // being downloaded now
    var onSaved: ((String) -> Unit)? = null                         // the tagger takes it from there
    var folder: () -> String = { "" }                               // your download folder ("Music/Mine/"), "" for the app's own

    fun busy(id: String) = synchronized(active) { id in active }
    fun streamQuality(id: String) = synchronized(streams) { streams[id]?.first?.quality ?: "" }
    fun streamLossy(id: String) = synchronized(streams) { streams[id]?.first?.lossyFrom }
    fun forgetStream(id: String) = synchronized(streams) { streams.remove(id) }

    /** Play t without saving it (blocking): the file when it is on the phone, else Tidal's stream. */
    fun stream(t: Track): Ready {
        if (t.isFile) return Ready(t.file!!, onDisk = true)
        lib.items[t.id]?.let { return Ready(it.file, quality = it.quality, onDisk = true) }
        synchronized(streams) { streams[t.id]?.takeIf { now() - it.second < STREAM_FOR }?.let { return it.first } }
        val ready = find(t)
        synchronized(streams) { streams[t.id] = ready to now() }
        return ready
    }

    /** FLAC from Tidal; else Tidal's AAC; else YouTube's own audio (no Tidal: YouTube). */
    private fun find(t: Track): Ready {
        // Whatever goes wrong on Tidal (not there, not in your country, the login, the network),
        // the next one is tried: Tidal's FLAC, Tidal's AAC, YouTube's own audio.
        var trouble: Throwable? = null
        if (tidal.connected) {
            var match: kotlinx.serialization.json.JsonElement? = null
            try {
                match = tidal.resolve(t)
                if (match != null) {
                    lib.byTidal(match["id"].long ?: 0)?.let { return Ready(it.file, quality = it.quality, onDisk = true) }   // saved from another service
                    return tidal.stream(match).let { Ready(it.uri, it.isDash, it.quality) }
                }
            } catch (_: NoLosslessMatch) {
            } catch (e: Exception) { trouble = e }
            if (trouble == null) lib.markMissing(t.id)   // no FLAC to download: syncs leave it alone for a while
            try {
                (match ?: tidal.resolve(t, lossy = true))?.let { m ->
                    return tidal.stream(m, lossy = true).let { Ready(it.uri, it.isDash, it.quality, lossyFrom = "Tidal") }
                }
            } catch (e: Exception) { if (trouble == null) trouble = e }
        }
        val yt = try { YouTubeAudio.find(t) } catch (e: Exception) {
            throw IOException(if (trouble != null) "Tidal: ${trouble.message}; YouTube: ${e.message}" else "YouTube: ${e.message}", e)
        }
        if (yt == null) {
            if (trouble != null) throw IOException("not on YouTube Music, and Tidal: ${trouble.message}", trouble)
            throw NoLosslessMatch(if (tidal.connected) "not on Tidal or YouTube Music"
                else "not on YouTube Music (connect Tidal in the accounts menu for more)")
        }
        return Ready(yt.first, quality = yt.second, lossyFrom = "YouTube")
    }

    /** Save t as FLAC (blocking); returns its content:// address. */
    fun download(t: Track): String {
        lib.items[t.id]?.let { return it.file }
        synchronized(active) { if (!active.add(t.id)) error("already downloading") }
        try {
            if (!tidal.connected) error("connect Tidal in the accounts menu first")
            val match = tidal.resolve(t) ?: run { lib.markMissing(t.id); throw NoLosslessMatch("not on Tidal in lossless") }
            val tid = match["id"].long ?: 0
            lib.byTidal(tid)?.let { same ->
                // Already saved from another service: the same file serves both.
                lib.add(t, same.file, same.art, "flac", same.quality, tid, extra = same)
                lib.markMissing(t.id, false)
                return same.file
            }
            val st = try { tidal.stream(match) } catch (e: NoLosslessMatch) { lib.markMissing(t.id); throw e }
            val album = runCatching { tidal.albumJson(match.path("album", "id").long ?: 0) }.getOrNull()
            val cover = runCatching { httpBytes(Tidal.image(match.path("album", "cover").str, 1280)) }.getOrNull()
            val raw = File(ctx.cacheDir, "${safeName(t.id)}.part")
            try {
                if (st.isDash) fetchDash(st.manifest, raw) else raw.outputStream().use { o ->
                    HTTP.newCall(okhttp3.Request.Builder().url(st.uri).build()).execute().use { r ->
                        if (!r.isSuccessful) throw HttpError(r.code, "", st.uri)
                        r.body!!.byteStream().copyTo(o, 1 shl 16)
                    }
                }
                val (bits, rate) = raw.inputStream().use { Flac.format(Flac.readHeader(it)) }
                val (where, name) = folder().takeIf { it.isNotEmpty() }?.let { root ->
                    downloadPlace(root, album.path("artist", "name").str ?: t.artist.split(",")[0], match.path("album", "title").str ?: t.album,
                        album["type"].str, match["trackNumber"].long, Tidal.tidalTitle(match))
                } ?: (LocalFiles.DOWNLOADS to "${safeName(t.id)}.flac")
                val uri = saveToMusic(name, where) { out -> Flac.retag(raw, out, tags(t, match, album), cover) }
                val art = File(lib.artDir, "${safeName(t.id)}.jpg")
                val artPath = runCatching { art.writeBytes(cover ?: httpBytes(sizedThumb(t.thumb, 226))); art.path }.getOrNull()
                lib.add(t, uri, artPath, "flac", "$bits-bit/${"%g".format(rate / 1000.0)} kHz", tid, match["isrc"].str)
                lib.markMissing(t.id, false)
                onSaved?.invoke(t.id)
                return uri
            } finally {
                raw.delete()
            }
        } finally {
            synchronized(active) { active.remove(t.id) }
        }
    }

    /** How an Upgrade went: (your song, the download's id) swapped; songs Tidal hasn't got; kept as good already (why). */
    class Upgraded {
        val done = mutableListOf<Pair<Track, String>>()
        val missing = mutableListOf<Track>()
        val kept = mutableListOf<Pair<Track, String>>()
        val failed = mutableListOf<Pair<Track, String>>()
    }

    /** Your own files that Tidal has better (blocking): a lossy file (MP3, AAC…) for its FLAC, a
     *  16-bit one for its hi-res. force: every song Tidal has. The old files stay (the app puts them away). */
    fun upgrade(tracks: List<Track>, force: Boolean, stop: () -> Boolean, progress: (Int) -> Unit): Upgraded {
        val out = Upgraded()
        val next = AtomicInteger()
        val done = AtomicInteger()
        fun work() {
            while (!stop()) {
                val t = tracks.getOrNull(next.getAndIncrement()) ?: return
                try {
                    val (lossless, bits) = fileQuality(t.file!!)
                    if (!force && lossless && bits >= 24) synchronized(out) { out.kept += t to "yours is $bits-bit lossless: as good as Tidal gets" }
                    else {
                        val c = tidal.resolve(t)
                        if (c == null) synchronized(out) { out.missing += t }
                        else if (!force && lossless && "HIRES_LOSSLESS" !in c.path("mediaMetadata", "tags").list.map { it.str })
                            synchronized(out) { out.kept += t to "yours is ${if (bits > 0) "$bits-bit " else ""}lossless; Tidal's is 16-bit too" }
                        else {
                            val new = Tidal.trackFromTidal(c)
                            if (!lib.isFlac(new.id)) download(new)
                            synchronized(out) { out.done += t to new.id }
                        }
                    }
                } catch (_: NoLosslessMatch) {
                    synchronized(out) { out.missing += t }
                } catch (e: Exception) {
                    synchronized(out) { out.failed += t to (e.message ?: e.javaClass.simpleName) }
                }
                progress(done.incrementAndGet())
            }
        }
        (1..SONGS_AT_ONCE).map { thread { work() } }.forEach { it.join() }
        return out
    }

    /** (lossless, bits per sample) of a file on the phone: FLAC's header says, else its type. */
    private fun fileQuality(uri: String): Pair<Boolean, Int> {
        val cr = ctx.contentResolver
        val mime = runCatching { cr.getType(android.net.Uri.parse(uri)) }.getOrNull() ?: ""
        if ("flac" in mime) {
            val bits = runCatching { cr.openInputStream(android.net.Uri.parse(uri))!!.use { Flac.format(Flac.readHeader(it)).first } }.getOrNull()
            return true to (bits ?: 16)
        }
        val lossless = listOf("wav", "x-wav", "aiff", "x-aiff", "alac", "x-ape", "x-wavpack").any { mime.endsWith(it) }
        return lossless to (if (lossless) 16 else 0)
    }

    /** How a batch of downloads went. */
    class Outcome {
        var saved = 0
        val missing = mutableListOf<Track>()                  // Tidal has no lossless copy
        val failed = mutableListOf<Pair<Track, String>>()     // and why

        fun summary(): String = buildList {
            if (saved > 0) add("Saved $saved song${if (saved != 1) "s" else ""} in FLAC")
            if (missing.isNotEmpty()) add("${missing.size} ${if (missing.size == 1) "isn't" else "aren't"} on Tidal in lossless (" +
                missing.take(2).joinToString(", ") { it.title } + if (missing.size > 2) "…)" else ")")
            if (failed.isNotEmpty()) add("${failed.size} failed: ${failed.first().second.take(90)}")
        }.joinToString(". ").ifEmpty { "Nothing new to download" }
    }

    /** Save every song in tracks as FLAC, workers songs at a time (blocking); progress(done) runs
     *  after each song. Songs Tidal recently didn't have are left alone, unless force (you asked). */
    fun sync(tracks: List<Track>, force: Boolean = false, workers: Int = 1, progress: (Int) -> Unit): Outcome {
        val out = Outcome()
        val next = AtomicInteger()
        val done = AtomicInteger()
        fun work() {
            while (true) {
                val t = tracks.getOrNull(next.getAndIncrement()) ?: return
                if (!(lib.isFlac(t.id) || (!force && lib.recentlyMissing(t.id)) || busy(t.id) || t.isFile)) {
                    try {
                        download(t)
                        synchronized(out) { out.saved++ }
                    } catch (_: NoLosslessMatch) {
                        synchronized(out) { out.missing += t }
                    } catch (e: Exception) {
                        synchronized(out) { out.failed += t to (e.message ?: e.javaClass.simpleName) }
                        android.util.Log.w("omarchy-music", "download ${t.id} (${t.artist} - ${t.title}) failed", e)
                    }
                }
                progress(done.incrementAndGet())
            }
        }
        if (workers <= 1) work() else (1..workers).map { thread { work() } }.forEach { it.join() }
        return out
    }

    private fun tags(t: Track, c: JsonElement, album: JsonElement?): Map<String, List<String>> = linkedMapOf(
        "title" to listOf(Tidal.tidalTitle(c)),
        "artist" to c["artists"].list.mapNotNull { it["name"].str },
        "album" to listOfNotNull(c.path("album", "title").str),
        "albumartist" to listOfNotNull(album.path("artist", "name").str),
        "tracknumber" to listOfNotNull(c["trackNumber"].long?.takeIf { it > 0 }?.toString()),
        "discnumber" to listOfNotNull(c["volumeNumber"].long?.takeIf { it > 0 }?.toString()),
        "date" to listOfNotNull((album["releaseDate"].str ?: c["streamStartDate"].str)?.take(10)),
        "isrc" to listOfNotNull(c["isrc"].str),
        "copyright" to listOfNotNull(c["copyright"].str),
        "tidal_id" to listOf(c["id"].long.toString()),
        "youtube_music_id" to listOfNotNull(t.id.takeIf { t.source == "ytm" }),
        "apple_music_id" to listOfNotNull(t.apple.takeIf { t.source == "apple" }),
        "spotify_id" to listOfNotNull(t.spotify.takeIf { t.source == "spotify" }),
    ).filterValues { it.isNotEmpty() }

    /** Hi-res: the init segment and each media segment of the manifest, as one FLAC. */
    private fun fetchDash(mpd: String, dest: File) {
        val init = Regex("initialization=\"([^\"]+)\"").find(mpd)!!.groupValues[1].replace("&amp;", "&")
        val media = Regex("media=\"([^\"]+)\"").find(mpd)!!.groupValues[1].replace("&amp;", "&")
        val start = Regex("startNumber=\"(\\d+)\"").find(mpd)?.groupValues?.get(1)?.toInt() ?: 1
        var count = 0
        var samples = 0L
        for (m in Regex("<S\\s([^>]*)/>").findAll(mpd)) {
            val attrs = m.groupValues[1]
            val d = Regex("d=\"(\\d+)\"").find(attrs)?.groupValues?.get(1)?.toLong() ?: 0
            val r = Regex("r=\"(\\d+)\"").find(attrs)?.groupValues?.get(1)?.toInt() ?: 0
            count += r + 1
            samples += d * (r + 1)
        }
        val timescale = Regex("timescale=\"(\\d+)\"").find(mpd)?.groupValues?.get(1)?.toLong() ?: 0
        val rate = Regex("audioSamplingRate=\"(\\d+)\"").find(mpd)?.groupValues?.get(1)?.toLong() ?: timescale
        val total = if (timescale > 0) samples * rate / timescale else 0
        // The segments are a few seconds each: fetch several at once (one by one, each waited
        // for a round trip), and hand them on in order.
        val pool = Executors.newFixedThreadPool(SEGMENTS_AHEAD)
        try {
            val segments = sequence {
                val pending = ArrayDeque<Future<ByteArray>>()
                var n = start
                while (n < start + count || pending.isNotEmpty()) {
                    while (n < start + count && pending.size < SEGMENTS_AHEAD) {
                        val url = media.replace("\$Number\$", (n++).toString())
                        pending.addLast(pool.submit(Callable { httpBytes(url) }))
                    }
                    yield(try { pending.removeFirst().get() } catch (e: ExecutionException) { throw e.cause ?: e })
                }
            }
            dest.outputStream().buffered().use { out -> Flac.fromMp4(httpBytes(init), segments, out, total) }
        } finally {
            pool.shutdownNow()
        }
    }

    /** A new file in Music/Omarchy Music/, written by write; returns its content:// address. */
    private fun saveToMusic(name: String, where: String, write: (java.io.OutputStream) -> Unit): String {
        val cr = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/flac")
            put(MediaStore.Audio.Media.RELATIVE_PATH, where)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("couldn't make $name in Music/")
        try {
            cr.openOutputStream(uri)!!.buffered().use(write)
            cr.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            cr.delete(uri, null, null)
            throw e
        }
        return uri.toString()
    }

    companion object {
        const val STREAM_FOR = 15 * 60   // seconds a looked-up stream address is trusted for
        const val SEGMENTS_AHEAD = 8     // hi-res segments fetched at once
        const val SONGS_AT_ONCE = 3      // songs a Download you ask for saves at once
    }
}
