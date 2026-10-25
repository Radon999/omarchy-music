package org.omarchy.music

import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.URLDecoder

class PicturesTest {
    private val geniusArtists = """{"response":{"sections":[{"hits":[
        {"result":{"id":1,"name":"Joji","image_url":"https://images.genius.com/joji.png","url":"https://genius.com/artists/Joji"}},
        {"result":{"id":2,"name":"Jojinooo","image_url":"https://assets.genius.com/images/default_cover_image.png"}}]}]}}"""
    private val geniusBio = """{"response":{"artist":{"description":{"plain":"Joji makes music."}}}}"""
    private val geniusAlbums = """{"response":{"sections":[{"hits":[
        {"result":{"name":"Nectar","cover_art_url":"https://images.genius.com/nectar.png","artist":{"name":"Joji"}}},
        {"result":{"name":"Joji - Nectar (Traducción al Español)","cover_art_url":"https://images.genius.com/es.png",
                   "artist":{"name":"Genius Traducciones al Español"}}}]}]}}"""
    private val wikiArtist = """{"query":{"pages":{
        "9":{"index":2,"title":"Joji (disambiguation)","pageprops":{"disambiguation":""}},
        "7":{"index":1,"title":"Joji (musician)","extract":"George Miller, known as Joji, is a singer.",
             "original":{"source":"https://upload.wikimedia.org/joji.png"},
             "pageprops":{"wikibase-shortdesc":"Japanese-Australian musician"}}}}}"""
    private val wikiAlbum = """{"query":{"pages":{"5":{"index":1,"title":"Nectar (Joji album)","extract":"Nectar is an album.",
        "pageprops":{"wikibase-shortdesc":"2020 studio album by Joji","page_image":"Joji_-_Nectar.png"}}}}}"""
    private val wikiFile = """{"query":{"pages":{"-1":{"imageinfo":[{"url":"https://upload.wikimedia.org/nectar.png"}]}}}}"""
    private val google = """{"items":[{"link":"https://example.com/g1.jpg","image":{"thumbnailLink":"https://example.com/t1.jpg"}},
        {"link":"https://example.com/g2.jpg"}]}"""

    private var down = setOf<String>()
    private val calls = ArrayList<String>()
    private var keys = mapOf<String, String>()

    private fun answer(url: String): JsonElement {
        calls += url
        if (down.any { it in url.substringAfter("//").substringBefore("/") }) throw IOException("unreachable")
        val u = URLDecoder.decode(url, "UTF-8")
        return JSON.parseToJsonElement(when {
            "googleapis" in url -> google
            "/api/search/artist" in url -> geniusArtists
            "/api/search/album" in url -> geniusAlbums
            "/api/artists/" in url -> geniusBio
            "prop=imageinfo" in u -> wikiFile
            Regex("gsrsearch=[^&]*album").containsMatchIn(u) -> wikiAlbum
            else -> wikiArtist
        })
    }

    @Before fun setUp() {
        Pictures.fetch = { url, _ -> answer(url) }
        Pictures.keys = { keys }
    }

    @Test fun withoutAGoogleKeyGeniusThenWikipedia() {
        val a = Pictures.artist("Joji", refresh = true)
        assertEquals("https://images.genius.com/joji.png" to "Genius", a.image to a.imageFrom)
        assertEquals("Wikipedia", a.bioFrom)
        assertTrue(a.bio.startsWith("George Miller"))
        assertTrue(calls.none { "googleapis" in it })
    }

    @Test fun googleFirstWithAKey() {
        keys = mapOf("google_key" to "k", "google_cx" to "cx")
        val a = Pictures.artist("Joji", refresh = true)
        assertEquals("https://example.com/g1.jpg" to "Google", a.image to a.imageFrom)
    }

    @Test fun aServiceThatIsDownIsSkipped() {
        keys = mapOf("google_key" to "k", "google_cx" to "cx")
        down = setOf("googleapis", "genius")
        val a = Pictures.artist("Joji", refresh = true)
        assertEquals("https://upload.wikimedia.org/joji.png" to "Wikipedia", a.image to a.imageFrom)
        down = setOf("wikipedia")
        val b = Pictures.artist("Joji", refresh = true)
        assertEquals("Joji makes music." to "Genius", b.bio to b.bioFrom)
    }

    @Test fun keptAndAskedOnce() {
        Pictures.artist("Kept Artist", refresh = true)
        val n = calls.size
        Pictures.artist("KEPT artist")
        assertEquals(n, calls.size)
    }

    @Test fun coverChoicesInOrder() {
        keys = mapOf("google_key" to "k", "google_cx" to "cx")
        val got = Pictures.coverChoices("Joji", "Nectar (Deluxe)")
        assertEquals(listOf("Google" to "https://example.com/g1.jpg", "Google" to "https://example.com/g2.jpg",
            "Genius" to "https://images.genius.com/nectar.png", "Wikipedia" to "https://upload.wikimedia.org/nectar.png"),
            got.map { it.from to it.url })
        Pictures.chooseCover("Joji, Rei", "Nectar", got[2].url)
        assertEquals("https://images.genius.com/nectar.png", Pictures.cover("joji", "Nectar (Deluxe Edition)"))
    }

    @Test fun biosLosePronunciationGuides() {
        assertEquals("Björk is a singer.\nMore.", Pictures.wikiText("Björk  ( BYURK, Icelandic: [pjœr̥k]; born 1965) is a singer.\nMore."))
        assertEquals("Tame Impala is a project.", Pictures.wikiText("Tame Impala ( TAYM im-PAHL-ə) is a project."))
        val keep = "Mac Miller (January 19, 1992 – September 7, 2018) was a rapper."
        assertEquals(keep, Pictures.wikiText(keep))
    }
}
