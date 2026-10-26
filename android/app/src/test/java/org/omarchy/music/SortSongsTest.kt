package org.omarchy.music

import org.junit.Assert.assertEquals
import org.junit.Test

class SortSongsTest {
    private val songs = listOf(Track("1", title = "Zebra", artist = "Beta", album = "B", duration = 100.0),
        Track("2", title = "apple", artist = "Alpha, Gamma", album = "", duration = 300.0),
        Track("3", title = "Mango", artist = "", album = "A", duration = 200.0))

    private fun ids(sort: String, plays: Map<Pair<String, String>, Int> = emptyMap()) = sortSongs(songs, sort, plays).map { it.id }

    @Test fun eachSort() {
        assertEquals(listOf("1", "2", "3"), ids("order"))
        assertEquals(listOf("2", "3", "1"), ids("title"))
        assertEquals(listOf("2", "1", "3"), ids("artist"))   // by the lead artist; none: last
        assertEquals(listOf("3", "1", "2"), ids("album"))    // no album: last
        assertEquals(listOf("2", "3", "1"), ids("length"))   // longest first
        assertEquals(listOf("3", "1", "2"), ids("plays", mapOf(songKey(songs[2]) to 9, songKey(songs[0]) to 2)))
    }
}
