package org.omarchy.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImportTest {
    @Test fun readsExtinfAndPaths() {
        val text = "﻿#EXTM3U\r\n#EXTINF:215,Daft Punk - One More Time\r\n../Daft Punk/Discovery/01 One More Time.flac\r\n" +
            "#EXTINF:-1,Just A Title\r\nfile:///storage/emulated/0/Music/x%20y.mp3\r\n\r\n/sdcard/Music/no info.flac\r\n"
        val e = readM3u(text.toByteArray())
        assertEquals(3, e.size)
        assertEquals(M3uEntry("../Daft Punk/Discovery/01 One More Time.flac", "Daft Punk", "One More Time", 215), e[0])
        assertEquals(M3uEntry("file:///storage/emulated/0/Music/x%20y.mp3", "", "Just A Title", 0), e[1])
        assertEquals(M3uEntry("/sdcard/Music/no info.flac", "", "", 0), e[2])
    }

    @Test fun readsLatin1() {
        val e = readM3u("#EXTINF:100,Beyoncé - Halo\nhalo.mp3\n".toByteArray(Charsets.ISO_8859_1))
        assertEquals("Beyoncé", e[0].artist)
    }

    @Test fun namesFromFileNames() {
        assertEquals("Daft Punk" to "One More Time", namesFromFile("a/b/Daft Punk - One More Time.flac"))
        assertEquals("" to "One More Time", namesFromFile("01 One More Time.flac"))
    }

    @Test fun linksGoToTheirService() {
        assertEquals("tidal", linkSource("https://tidal.com/browse/album/123"))
        assertEquals("ytm", linkSource("https://music.youtube.com/watch?v=abcdefghijk"))
        assertEquals("spotify", linkSource("https://open.spotify.com/track/x"))
        assertEquals("apple", linkSource("https://music.apple.com/us/album/x"))
        assertNull(linkSource("daft punk discovery"))
    }

    private fun t(id: String, title: String, artist: String, album: String = "", d: Double = 200.0) =
        Track(id, "tidal", title, artist, album, duration = d)

    @Test fun bestMatchWantsArtistAndLength() {
        val hits = listOf(t("a", "One More Time", "Someone Else"), t("b", "One More Time (Live)", "Daft Punk", d = 320.0),
            t("c", "One More Time", "Daft Punk", d = 320.0))
        assertEquals("c", bestMatch(hits, "Daft Punk", "One More Time", 320)?.id)
        assertNull(bestMatch(hits, "Daft Punk", "One More Time", 200))   // too long, all of them
        assertEquals("a", bestMatch(hits.take(1), "", "One More Time", 0)?.id)
    }

    @Test fun localFindsArtistsAndAlbums() {
        val songs = listOf(t("1", "Aerodynamic", "Daft Punk", "Discovery"), t("2", "Digital Love", "Daft Punk", "Discovery"),
            t("3", "Something", "The Beatles", "Abbey Road"))
        val f = localMatches(songs, "daft")
        assertEquals(listOf("1", "2"), f.songs.map { it.id })
        assertEquals(listOf("Daft Punk"), f.artists.map { it.title })
        assertEquals(listOf("Discovery"), f.albums.map { it.title })
        assertEquals("Daft Punk · 2 songs", f.albums[0].sub)
        assertEquals(2, f.albumTracks[f.albums[0].id]?.size)
        assertEquals(listOf("3"), localMatches(songs, "abbey road").songs.map { it.id })
        assertEquals(true, localMatches(songs, "  ").isEmpty)
    }

    @Test fun missedSongsGoBackWhereTheyWere() {
        fun m(n: Int, at: Int) = Missed(n, at, "", "x$n", 0, "", "not found")
        // a b [1] c [3] [4] e: songs found a, b, c, e; missed lines 2, 4, 5 (at 2, 3, 3)
        val two = m(2, 2); val four = m(4, 3); val five = m(5, 3)
        val list = mutableListOf("a", "b", "c", "e")
        val placed = ArrayList<Missed>()
        for (x in listOf(five, two, four)) { list.add(missedAt(x, placed), x.title); placed += x }
        assertEquals(listOf("a", "b", "x2", "c", "x4", "x5", "e"), list)
        assertEquals("Nobody - Lost", Missed(0, 0, "Nobody", "Lost", 0, "", "").name)
    }
}
