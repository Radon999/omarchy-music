package org.omarchy.music

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File

/** Lyrics into the songs' own files, so they stay with them: a FLAC's LYRICS comment, an MP3's
 *  ID3 lyrics frame (USLT). Only the words go in (no annotations, no timings). A file that has
 *  lyrics already is left as it is. Other kinds of files (M4A, OGG…) are left alone. */
object LyricsTags {
    /** The words of what was found: the plain lyrics, else the timed ones without their timings. */
    fun words(g: Lyrics.Got): String? =
        g.plain?.trim()?.takeIf { it.isNotEmpty() }
            ?: g.synced?.let { s -> Lyrics.parseLrc(s).joinToString("\n") { it.second }.trim().takeIf { it.isNotEmpty() } }

    /** Write text as the lyrics of the file at uri, if it has none (blocking). Returns whether it wrote. */
    fun write(ctx: Context, uri: String, text: String): Boolean {
        val cr = ctx.contentResolver
        val head = cr.openInputStream(Uri.parse(uri))!!.use { s -> ByteArray(10).also { b -> var n = 0; while (n < 10) { val r = s.read(b, n, 10 - n); if (r < 0) break; n += r } } }
        return when {
            String(head, 0, 4, Charsets.ISO_8859_1) == "fLaC" -> flac(ctx, uri)?.let { has -> if (has) false else { Tags.write(ctx, uri, mapOf("lyrics" to text)); true } } ?: false
            String(head, 0, 3, Charsets.ISO_8859_1) == "ID3" || (head[0].toInt() and 0xFF == 0xFF && head[1].toInt() and 0xE0 == 0xE0) -> mp3(ctx, uri, text)
            else -> false
        }
    }

    /** Whether the FLAC has lyrics already (null if unreadable). */
    private fun flac(ctx: Context, uri: String): Boolean? = runCatching {
        ctx.contentResolver.openInputStream(Uri.parse(uri))!!.buffered().use { val c = Flac.comments(Flac.readHeader(it))
            !c["LYRICS"].isNullOrEmpty() || !c["UNSYNCEDLYRICS"].isNullOrEmpty() }
    }.getOrNull()

    private fun syncsafe(b: ByteArray, at: Int) =
        ((b[at].toInt() and 0x7F) shl 21) or ((b[at + 1].toInt() and 0x7F) shl 14) or ((b[at + 2].toInt() and 0x7F) shl 7) or (b[at + 3].toInt() and 0x7F)
    private fun be32(b: ByteArray, at: Int) =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
    private fun syncsafeBytes(n: Int) = byteArrayOf(((n shr 21) and 0x7F).toByte(), ((n shr 14) and 0x7F).toByte(), ((n shr 7) and 0x7F).toByte(), (n and 0x7F).toByte())
    private fun be32Bytes(n: Int) = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())

    /** An MP3: its ID3v2 tag with a USLT frame added (a new ID3v2.3 tag if it had none). */
    private fun mp3(ctx: Context, uri: String, text: String): Boolean {
        val cr = ctx.contentResolver
        val all = cr.openInputStream(Uri.parse(uri))!!.use { it.readBytes() }
        var version = 3
        var frames = ByteArray(0)
        var audioFrom = 0
        if (all.size > 10 && String(all, 0, 3, Charsets.ISO_8859_1) == "ID3") {
            version = all[3].toInt()
            val flags = all[5].toInt() and 0xFF
            if (version !in 3..4 || flags and 0xD0 != 0) return false   // ID3v2.2, unsynchronised, extended header or footer: left alone
            val size = syncsafe(all, 6)
            audioFrom = 10 + size
            if (audioFrom > all.size) return false
            // Walk the frames to their end (then padding), checking for lyrics.
            var p = 10
            while (p + 10 <= audioFrom && all[p].toInt() != 0) {
                val id = String(all, p, 4, Charsets.ISO_8859_1)
                val fs = if (version == 4) syncsafe(all, p + 4) else be32(all, p + 4)
                if (fs < 0 || p + 10 + fs > audioFrom) break
                if (id == "USLT" || id == "SYLT") return false   // it has lyrics already
                p += 10 + fs
            }
            frames = all.copyOfRange(10, p)
        }
        // USLT: encoding, language, an empty description, the words.
        val body = ByteArrayOutputStream()
        if (version == 4) {
            body.write(3); body.write("eng".toByteArray(Charsets.ISO_8859_1)); body.write(0)
            body.write(text.toByteArray(Charsets.UTF_8))
        } else {
            body.write(1); body.write("eng".toByteArray(Charsets.ISO_8859_1))
            body.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0))   // the description: empty, UTF-16
            body.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte())); body.write(text.toByteArray(Charsets.UTF_16LE))
        }
        val b = body.toByteArray()
        val frame = ByteArrayOutputStream().apply {
            write("USLT".toByteArray(Charsets.ISO_8859_1))
            write(if (version == 4) syncsafeBytes(b.size) else be32Bytes(b.size))
            write(byteArrayOf(0, 0)); write(b)
        }.toByteArray()
        val tagBody = frames + frame + ByteArray(1024)   // some padding, for tags added later
        val out = ByteArrayOutputStream(all.size + tagBody.size + 10).apply {
            write("ID3".toByteArray(Charsets.ISO_8859_1)); write(byteArrayOf(version.toByte(), 0, 0))
            write(syncsafeBytes(tagBody.size)); write(tagBody)
            write(all, audioFrom, all.size - audioFrom)
        }.toByteArray()
        // Through a copy in the cache: a write that fails half way leaves the copy to put back.
        val tmp = File.createTempFile("lyrics", ".mp3", ctx.cacheDir).apply { writeBytes(all) }
        try {
            cr.openOutputStream(Uri.parse(uri), "wt")!!.use { it.write(out) }
        } catch (e: Exception) {
            runCatching { cr.openOutputStream(Uri.parse(uri), "wt")!!.use { it.write(tmp.readBytes()) } }
            throw e
        } finally {
            tmp.delete()
        }
        return true
    }
}
