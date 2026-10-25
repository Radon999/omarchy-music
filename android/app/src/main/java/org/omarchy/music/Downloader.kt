package org.omarchy.music

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import kotlinx.serialization.json.JsonElement
import java.io.File

/** Gets songs ready to play: Tidal's stream looked up (nothing saved), or the song
 *  downloaded as tagged FLAC into Music/Omarchy Music/. Songs Tidal has no lossless copy
 *  of are skipped, never saved lossy. */
class Downloader(private val ctx: Context, private val lib: Library, val tidal: Tidal) {
    data class Ready(val uri: String, val isDash: Boolean = false, val quality: String = "", val onDisk: Boolean = false)

    private val streams = HashMap<String, Pair<Ready, Double>>()   // id -> (stream, when looked up)
    private val active = HashSet<String>()                          // being downloaded now
    var onSaved: ((String) -> Unit)? = null                         // the tagger takes it from there

    fun busy(id: String) = synchronized(active) { id in active }
    fun streamQuality(id: String) = synchronized(streams) { streams[id]?.first?.quality ?: "" }
    fun forgetStream(id: String) = synchronized(streams) { streams.remove(id) }

    /** Play t without saving it (blocking): the file when it is on the phone, else Tidal's stream. */
    fun stream(t: Track): Ready {
        if (t.isFile) return Ready(t.file!!, onDisk = true)
        lib.items[t.id]?.let { return Ready(it.file, quality = it.quality, onDisk = true) }
        synchronized(streams) { streams[t.id]?.takeIf { now() - it.second < STREAM_FOR }?.let { return it.first } }
        if (!tidal.connected) error("connect Tidal in the accounts menu first")
        val match = tidal.resolve(t) ?: run { lib.markMissing(t.id); throw NoLosslessMatch("not on Tidal in lossless") }
        lib.byTidal(match["id"].long ?: 0)?.let { return Ready(it.file, quality = it.quality, onDisk = true) }   // saved from another service
        val st = try { tidal.stream(match) } catch (e: NoLosslessMatch) { lib.markMissing(t.id); throw e }
        val ready = Ready(st.uri, st.isDash, st.quality)
        synchronized(streams) { streams[t.id] = ready to now() }
        return ready
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
                val uri = saveToMusic("${safeName(t.id)}.flac") { out -> Flac.retag(raw, out, tags(t, match, album), cover) }
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

    /** Save every song in tracks as FLAC, one at a time (blocking). Songs Tidal recently
     *  didn't have are left alone; progress() runs after each song. */
    fun sync(tracks: List<Track>, progress: () -> Unit) {
        for (t in tracks) {
            if (lib.isFlac(t.id) || lib.recentlyMissing(t.id) || busy(t.id) || t.isFile) continue
            runCatching { download(t) }
            progress()
        }
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
        dest.outputStream().buffered().use { out ->
            Flac.fromMp4(httpBytes(init), (start until start + count).asSequence().map { n ->
                httpBytes(media.replace("\$Number\$", n.toString()))
            }, out, total)
        }
    }

    /** A new file in Music/Omarchy Music/, written by write; returns its content:// address. */
    private fun saveToMusic(name: String, write: (java.io.OutputStream) -> Unit): String {
        val cr = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/flac")
            put(MediaStore.Audio.Media.RELATIVE_PATH, LocalFiles.DOWNLOADS)
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
    }
}
