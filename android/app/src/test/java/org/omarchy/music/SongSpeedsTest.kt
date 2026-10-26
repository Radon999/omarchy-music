package org.omarchy.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SongSpeedsTest {
    private val file = File.createTempFile("speeds", ".json").apply { delete(); deleteOnExit() }

    @Test fun keptBySongOnAnyService() {
        val s = SongSpeeds(file)
        val yt = Track("yt1", title = "Song (Remastered)", artist = "A, B")
        val td = Track("td9", source = "tidal", title = "Song", artist = "A")
        assertEquals(SongSpeed(), s.get(yt))
        s.set(listOf(yt), 1.25, pitch = true)
        assertEquals(SongSpeed(1.25, true), SongSpeeds(file).get(td))   // the same song, kept
        s.set(listOf(td), 9.0)
        assertEquals(2.0, s.get(yt).speed, 0.0)                         // no faster than twice
        s.set(listOf(yt), 1.0)
        assertTrue(s.items.isEmpty())                                   // normal: forgotten
    }

    @Test fun showsSpeeds() {
        assertEquals("1×", fmtSpeed(1.0))
        assertEquals("1.25×", fmtSpeed(1.25))
        assertEquals("0.5×", fmtSpeed(0.5))
    }
}
