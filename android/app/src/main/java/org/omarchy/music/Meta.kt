package org.omarchy.music

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream

/** A FLAC file's Vorbis comments (keys lowercased), from a path or a content:// address; empty if unreadable. */
fun fileTags(ctx: Context, path: String?): Map<String, List<String>> {
    if (path.isNullOrEmpty()) return emptyMap()
    return runCatching {
        val input: InputStream = if (path.startsWith("/")) File(path).inputStream()
            else ctx.contentResolver.openInputStream(Uri.parse(path)) ?: return emptyMap()
        input.buffered().use { Flac.comments(Flac.readHeader(it)) }.mapKeys { it.key.lowercase() }
    }.getOrDefault(emptyMap())
}

/** A title without what YouTube adds, keeping its case. */
fun tidyTitle(s: String): String =
    Regex("(?i)\\s*[(\\[][^)\\]]*\\b(official|video|audio|lyrics?|visuali[sz]er|hd|4k|mv)\\b[^)\\]]*[)\\]]").replace(s, "").trim().ifEmpty { s }

/** What a song is, as its saved file's tags tell it (else its service): for Last.fm, lyrics and the tagger. */
data class SongMeta(val title: String, val artist: String, val artists: List<String>, val album: String,
                    val albumArtist: String, val duration: Double, val tidal: Long?, val mbid: String,
                    val file: String?, val tags: Map<String, List<String>>)

fun songMeta(ctx: Context, lib: Library, t: Track): SongMeta {
    val entry = lib.items[t.id]
    val path = if (entry?.format == "flac") entry.file else t.file
    val tags = fileTags(ctx, path)
    fun one(k: String) = tags[k]?.firstOrNull() ?: ""
    var title = one("title").ifEmpty { t.title }
    val artists = tags["artist"] ?: t.artist.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    if (tags.isEmpty() && t.source == "ytm") title = tidyTitle(title)
    return SongMeta(title, artists.firstOrNull() ?: "", artists, one("album").ifEmpty { t.album }, one("albumartist"),
        entry?.duration?.takeIf { it > 0 } ?: t.duration, one("tidal_id").toLongOrNull() ?: entry?.tidal ?: t.tidal,
        one("musicbrainz_trackid"), path, tags)
}
