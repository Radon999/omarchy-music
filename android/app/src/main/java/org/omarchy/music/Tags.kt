package org.omarchy.music

import android.content.Context
import android.net.Uri
import java.io.File

/** Editing songs' tags by hand (Edit tags…), the desktop's TAG_FIELDS. Only the FLACs the app
 *  downloaded can be written here: they're the app's own in Music/. Songs of other apps'
 *  (your own files) would need Android to ask you for each one, so they're left alone. */
object Tags {
    val FIELDS = listOf("title" to "Title", "artist" to "Artist", "album" to "Album", "albumartist" to "Album artist",
        "genre" to "Genre", "date" to "Year", "tracknumber" to "Track")

    /** The FLAC of the song's that can be edited (a content:// address the app made), or null. */
    fun editable(t: Track): String? = Music.lib.items[t.id]?.takeIf { it.format == "flac" && it.file.startsWith("content://") }?.file

    /** What each field says in the files' comments: null where they disagree. */
    internal fun common(all: List<Map<String, List<String>>>): Map<String, String?> = FIELDS.associate { (k, _) ->
        val each = all.map { c -> (c[k.uppercase()] ?: emptyList()).let { if (k == "artist") it.joinToString(", ") else it.firstOrNull() ?: "" } }
        k to each.distinct().singleOrNull()
    }

    /** The comments to write for changes {field: value}: "" takes one off, artists are one comment each. */
    internal fun comments(changes: Map<String, String>): Map<String, List<String>> = changes.entries.associate { (k, v) ->
        k.uppercase() to when {
            v.isBlank() -> emptyList()
            k == "artist" -> v.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            else -> listOf(v.trim())
        }
    }

    fun read(ctx: Context, uris: List<String>): Map<String, String?> = common(uris.map { u ->
        runCatching { ctx.contentResolver.openInputStream(Uri.parse(u))!!.buffered().use { Flac.comments(Flac.readHeader(it)) } }
            .getOrDefault(emptyMap())
    })

    /** Write changes into the FLAC at uri (blocking); returns its comments as they are now. */
    fun write(ctx: Context, uri: String, changes: Map<String, String>): Map<String, List<String>> {
        val cr = ctx.contentResolver
        val tmp = File.createTempFile("tags", ".flac", ctx.cacheDir)
        try {
            cr.openInputStream(Uri.parse(uri))!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            cr.openOutputStream(Uri.parse(uri), "wt")!!.buffered().use { Flac.retag(tmp, it, comments(changes), null, keepOther = true) }
        } finally {
            tmp.delete()
        }
        return cr.openInputStream(Uri.parse(uri))!!.buffered().use { Flac.comments(Flac.readHeader(it)) }
    }
}

/** A new album cover (Cover…): written into the album's downloaded FLACs, and shown for every
 *  song of the album, the phone's own and streamed ones too (those files are other apps', so
 *  their own covers stay as they are). */
object Covers {
    /** Which album a song is from: its lead artist and album, tidied; a song without an album is one of its own. */
    internal fun albumKey(t: Track): Pair<String, String> =
        norm(t.artist.split(",")[0]) to norm(bareTitle(t.album)).ifEmpty { "#" + t.id }

    internal fun isJpeg(b: ByteArray) = b.size > 3 && b[0] == 0xff.toByte() && b[1] == 0xd8.toByte() && b[2] == 0xff.toByte()

    /** Picture bytes as a JPEG (FLAC covers here are JPEGs). */
    fun jpeg(b: ByteArray): ByteArray {
        if (isJpeg(b)) return b
        val bmp = android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size) ?: error("that isn't a picture")
        return java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
    }

    /** Put jpeg in the FLAC at uri (one the app made) as its cover, keeping its tags. */
    fun write(ctx: Context, uri: String, jpeg: ByteArray) {
        val cr = ctx.contentResolver
        val tmp = File.createTempFile("cover", ".flac", ctx.cacheDir)
        try {
            cr.openInputStream(Uri.parse(uri))!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            cr.openOutputStream(Uri.parse(uri), "wt")!!.buffered().use { Flac.retag(tmp, it, emptyMap(), jpeg, keepOther = true) }
        } finally {
            tmp.delete()
        }
    }
}
