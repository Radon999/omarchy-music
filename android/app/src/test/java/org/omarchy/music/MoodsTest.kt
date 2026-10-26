package org.omarchy.music

import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.IOException

class MoodsTest {
    private fun song(title: String, artist: String) = Track("$artist-$title", title = title, artist = artist)
    private val songs = mutableListOf(song("Glimpse of Us", "Joji"), song("Levels", "Avicii"), song("Odd One", "Band"))
    private val trackTags = mutableMapOf("Glimpse of Us" to listOf("sad" to 100, "love" to 40), "Levels" to listOf("house" to 100, "party" to 80))
    private val artistTags = mutableMapOf<String, List<Pair<String, Int>>>()
    private var lastfm = true
    private val calls = ArrayList<String>()
    private val mb = mapOf("Joji" to listOf("lo-fi" to 9, "r&b" to 5), "Band" to listOf("death metal" to 4))

    @Before fun setUp() {
        Moods.clearForTests()
        Moods.pool = { songs.toList() }
        Moods.genreOf = { t -> if (t.title == "Odd One") "Metal" else "" }
        Moods.token = { "" }
        Moods.mbPace = 0
        Moods.lastfmTags = { _, title -> calls += title; if (lastfm) trackTags[title] ?: emptyList() else null }
        Moods.lastfmArtistTags = { a -> calls += "artist:$a"; artistTags[a] ?: emptyList() }
        Moods.fetch = { url, _ ->
            val name = java.net.URLDecoder.decode(url, "UTF-8").substringAfter("artist:\"").substringBefore("\"")
            JSON.parseToJsonElement("""{"artists":[{"name":"$name","tags":[${(mb[name] ?: emptyList()).joinToString(",") { """{"name":"${it.first}","count":${it.second}}""" }}]}]}""")
        }
    }

    @Test fun tagsToMoods() {
        assertEquals(listOf("sad"), Moods.moodsOf(listOf("sad" to 100, "melancholic" to 60, "indie" to 90, "chill" to 20)))
        assertEquals(listOf("chill", "focus"), Moods.moodsOf(listOf("Chill" to 100, "lo-fi" to 80, "study" to 70)))
        assertEquals(listOf("angry", "energetic"), Moods.moodsOf(listOf("metal" to 100)))
        assertEquals(listOf("energetic"), Moods.moodsOf(listOf("british alternative rock" to 10)))
        assertEquals(emptyList<String>(), Moods.moodsOf(listOf("seen live" to 100)))
    }

    @Test fun guessesAndRequests() {
        assertEquals(listOf("energetic"), Moods.genreMoods("Hip-Hop/Rap"))
        assertEquals(listOf("sad", "chill"), Moods.titleMoods("Tears in the Rain (Remastered)"))
        assertEquals(listOf("chill"), Moods.requestMoods("something to chill to"))
        assertEquals(listOf("energetic"), Moods.requestMoods("gym hype"))
        assertEquals(emptyList<String>(), Moods.requestMoods("80s rock"))
    }

    @Test fun scansEachSongOnce() {
        assertEquals(3, Moods.scan())
        assertEquals(listOf("sad", "romantic"), Moods.of(songs[0]))
        assertEquals("lastfm", Moods.from(songs[0]))
        assertEquals(listOf("party"), Moods.of(songs[1]))
        assertEquals(listOf("angry", "energetic") to "guess", Moods.of(songs[2]) to Moods.from(songs[2]))
        assertEquals(0, Moods.scan())
        songs += song("New", "Joji")
        artistTags["Joji"] = listOf("sad" to 100, "trip-hop" to 50)
        assertEquals(1, Moods.scan())   // only the new song; its artist's tags, since it has none
        assertEquals(listOf("sad"), Moods.of(songs.last()))
        assertEquals(1, Moods.todo(again = true).size)   // "look again" reads the guessed ones
    }

    @Test fun withoutLastFmItGuesses() {
        lastfm = false
        Moods.scan()
        assertEquals(listOf("chill"), Moods.of(songs[0]))   // its artist's genres on MusicBrainz
        assertEquals(listOf("angry", "energetic"), Moods.of(songs[2]))
    }

    @Test fun smartPlaylistsStrongestFirst() {
        Moods.scan()
        assertEquals(listOf("Glimpse of Us"), Moods.songsIn("sad").map { it.title })
        assertEquals(1, Moods.counts().toMap()["party"])
    }

    @Test fun chartmetricFirstAndSkippedWhenDown() {
        Moods.token = { "rt" }
        Moods.post = { _, body -> assertEquals("""{"refreshtoken":"rt"}""", body); JSON.parseToJsonElement("""{"token":"tok","expires_in":3600}""") }
        val mbFetch = Moods.fetch
        Moods.fetch = { url, h ->
            when {
                "/search" in url -> JSON.parseToJsonElement("""{"obj":{"tracks":[{"id":9,"name":"Other","artist_names":["Avicii"]},{"id":7,"name":"Levels","artist_names":["Avicii"]}]}}""")
                "/track/7" in url -> { assertEquals("Bearer tok", h["Authorization"]); JSON.parseToJsonElement("""{"obj":{"moods":[{"name":"Happy"},"Uplifting"],"activities":["Workout"]}}""") }
                "chartmetric" in url -> throw IOException("unreachable")
                else -> mbFetch(url, h)
            }
        }
        songs.retainAll { it.title == "Levels" }
        Moods.scan()
        assertEquals(listOf("happy", "energetic") to "chartmetric", Moods.of(songs[0]) to Moods.from(songs[0]))
        Moods.fetch = { url, h -> if ("chartmetric" in url) throw IOException("unreachable") else mbFetch(url, h) }
        Moods.scan(again = false)
        Moods.clearForTests()
        Moods.scan()
        assertEquals(listOf("party") to "lastfm", Moods.of(songs[0]) to Moods.from(songs[0]))
    }
}
