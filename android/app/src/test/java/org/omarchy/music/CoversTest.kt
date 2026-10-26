package org.omarchy.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class CoversTest {
    @Test fun knowsAnAlbumAnyWayItsWritten() {
        val a = Track("1", title = "One", artist = "Band, Guest", album = "Album (Deluxe Edition)")
        val b = Track("2", source = "tidal", title = "Two", artist = "Band", album = "Album")
        assertEquals(Covers.albumKey(a), Covers.albumKey(b))
        val loose1 = Track("3", title = "Leak", artist = "Band")
        val loose2 = Track("4", title = "Other leak", artist = "Band")
        assertNotEquals(Covers.albumKey(loose1), Covers.albumKey(loose2))   // no album: each its own
    }

    @Test fun aNewCoverKeepsTheTags() {
        val bare = File.createTempFile("bare", ".flac").apply {
            writeBytes("fLaC".toByteArray() + byteArrayOf(0x80.toByte(), 0, 0, 34) + ByteArray(34) + "audio".toByteArray())
        }
        val tagged = File.createTempFile("tagged", ".flac")
        tagged.outputStream().use { Flac.retag(bare, it, mapOf("title" to listOf("One")), byteArrayOf(1, 2)) }
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 9)
        assertTrue(Covers.isJpeg(jpeg))
        assertFalse(Covers.isJpeg(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())))
        val out = ByteArrayOutputStream()
        Flac.retag(tagged, out, emptyMap(), jpeg, keepOther = true)   // what Covers.write does
        val blocks = Flac.readHeader(out.toByteArray().inputStream())
        assertEquals(listOf("One"), Flac.comments(blocks)["TITLE"])
        assertEquals(jpeg.toList(), Flac.picture(blocks)!!.toList())
    }
}
