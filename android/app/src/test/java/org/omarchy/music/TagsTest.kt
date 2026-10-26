package org.omarchy.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class TagsTest {
    /** A FLAC with an empty STREAMINFO, the comments given, and "audio" after its header. */
    private fun flac(tags: Map<String, List<String>>): File {
        val bare = File.createTempFile("bare", ".flac").apply {
            writeBytes("fLaC".toByteArray() + byteArrayOf(0x80.toByte(), 0, 0, 34) + ByteArray(34) + "audio".toByteArray())
        }
        val out = File.createTempFile("song", ".flac")
        out.outputStream().use { Flac.retag(bare, it, tags, byteArrayOf(7, 7)) }   // with a cover, as downloads have
        return out
    }

    private fun comments(f: File) = f.inputStream().buffered().use { Flac.comments(Flac.readHeader(it)) }

    @Test fun editsOnlyWhatChangedAndKeepsTheRest() {
        val src = flac(mapOf("title" to listOf("One"), "artist" to listOf("X"), "album" to listOf("A"), "isrc" to listOf("US1")))
        val out = ByteArrayOutputStream()
        Flac.retag(src, out, Tags.comments(mapOf("artist" to "X, Y ", "album" to "", "genre" to "Jazz")), null, keepOther = true)
        val res = File.createTempFile("res", ".flac").apply { writeBytes(out.toByteArray()) }
        val c = comments(res)
        assertEquals(listOf("One"), c["TITLE"])
        assertEquals(listOf("X", "Y"), c["ARTIST"])     // one comment each
        assertNull(c["ALBUM"])                          // taken off
        assertEquals(listOf("Jazz"), c["GENRE"])
        assertEquals(listOf("US1"), c["ISRC"])          // the rest kept
        assertEquals("audio", String(res.readBytes().takeLast(5).toByteArray()))
        assertEquals(listOf<Byte>(7, 7), res.inputStream().buffered().use { Flac.picture(Flac.readHeader(it)) }!!.toList())   // cover kept
    }

    @Test fun saysWhereSongsDisagree() {
        val have = Tags.common(listOf(mapOf("TITLE" to listOf("One"), "ARTIST" to listOf("X", "Y"), "GENRE" to listOf("Jazz")),
            mapOf("TITLE" to listOf("Two"), "ARTIST" to listOf("X", "Y"))))
        assertNull(have["title"])
        assertEquals("X, Y", have["artist"])
        assertNull(have["genre"])
        assertEquals("", have["date"])
    }
}
