package org.omarchy.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DjTest {
    private fun song(title: String, artist: String = "A") = Track(title, "ytm", title, artist)

    @Test fun tagsOfMoodsDecadesAndGenres() {
        assertEquals(listOf("chill", "chillout"), Dj.tags("something chill"))
        assertEquals(listOf("2010s", "dance"), Dj.tags("2010s dance music"))
        assertEquals(listOf("drum and bass"), Dj.tags("play some drum and bass music"))
        assertEquals(listOf("k-pop"), Dj.tags("K-pop"))
        assertEquals(emptyList<String>(), Dj.tags("play some music"))
    }

    @Test fun pickSkipsUsedAndRepeats() {
        val pool = (1..20).map { song("s$it") } + song("s1")
        val used = setOf(songKey(song("s2")))
        val got = Dj.pick(pool, 6, used, Random(1))
        assertEquals(6, got.size)
        assertEquals(6, got.map { songKey(it) }.toSet().size)
        assertTrue(got.none { it.title == "s2" })
        assertEquals(3, Dj.pick(pool.take(3), 6, emptySet(), Random(2)).size)
    }

    @Test fun pickLeansToTheTop() {
        val pool = (1..100).map { song("s$it") }
        val rng = Random(7)
        val firsts = (1..400).map { pool.indexOf(Dj.pick(pool, 1, emptySet(), rng)[0]) }
        assertTrue(firsts.average() < 40)   // about 32; 49.5 if every song were as likely
    }

    @Test fun namesOfLeadArtists() {
        assertEquals("Daft Punk", Dj.names(listOf(song("a", "Daft Punk, Pharrell"), song("b", "Daft Punk"))))
        assertEquals("A and B", Dj.names(listOf(song("a", "A"), song("b", "B"), song("c", "C"))))
        assertEquals("A, B and C", Dj.names(listOf(song("a", "A"), song("b", "B"), song("c", "C")), 3))
    }

    @Test fun greetingByHour() {
        assertEquals("Good morning", Dj.greeting(8))
        assertEquals("Good afternoon", Dj.greeting(13))
        assertEquals("Good evening", Dj.greeting(20))
        assertEquals("Hey, night owl", Dj.greeting(2))
    }
}
