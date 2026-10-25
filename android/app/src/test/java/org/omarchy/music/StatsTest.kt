package org.omarchy.music

import org.junit.Assert.assertEquals
import org.junit.Test

class StatsTest {
    private fun play(t: Long, artist: String, title: String) = SpotifyHistory.Play(t, artist, title, "", 200_000)

    @Test fun aPlayLastFmHasCountsOnce() {
        val scrobbles = listOf(1100L to "Song", 1150L to "Song")   // two scrobbles while one play
        val left = Stats.notScrobbled(listOf(play(1000, "A", "Song"), play(1200, "A", "Song"), play(50_000, "A", "Song"),
            play(1000, "B", "Other")), scrobbles)
        assertEquals(listOf(50_000L to "Song", 1000L to "Other"), left.map { it.t to it.title })
    }

    @Test fun addsSpotifyToLastFm() {
        val (songs, artists) = Stats.combine(
            listOf(LastFm.Top("Song", "Artist", 10, ""), LastFm.Top("Other", "Artist", 2, "")),
            listOf(LastFm.Top("Artist", "", 12, "")),
            listOf(play(1, "Artist", "Song (Remastered)"), play(2, "Band", "Hit")), emptyList(), fm = true)
        assertEquals(listOf("Song" to 11, "Other" to 2, "Hit" to 1), songs.map { it.title to it.plays })
        assertEquals(10, songs[0].lastfm)
        assertEquals(1, songs[0].spotify)
        assertEquals(listOf("Artist" to 13, "Band" to 1), artists.map { it.name to it.plays })
    }

    @Test fun withoutLastFmCountsThePlaysHere() {
        val (songs, artists) = Stats.combine(emptyList(), emptyList(), emptyList(),
            listOf(Track("x", title = "Here", artist = "Me, You") to 4), fm = false)
        assertEquals(listOf("Here" to 4), songs.map { it.title to it.here })
        assertEquals(listOf("Me" to 4), artists.map { it.name to it.plays })
    }
}
