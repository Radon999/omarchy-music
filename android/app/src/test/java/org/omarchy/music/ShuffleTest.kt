package org.omarchy.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ShuffleTest {
    private fun songs(n: Int) = (0 until n).map { Track("$it", title = "Song $it", artist = "A") }

    @Test fun everyOrderEquallyLikely() {
        val rng = Random(7)
        val runs = 60_000
        val seen = HashMap<String, Int>()
        repeat(runs) { seen.merge(shuffleSongs(songs(4), rng = rng).joinToString("") { it.id }, 1, Int::plus) }
        assertEquals(24, seen.size)   // all 4! orders turn up
        val expected = runs / 24.0
        val chi2 = seen.values.sumOf { (it - expected) * (it - expected) / expected }
        assertTrue("chi-squared $chi2", chi2 < 49.7)   // 23 degrees of freedom, p = 0.001
    }

    @Test fun theSongThatJustPlayedIsNotFirst() {
        val rng = Random(1)
        val last = songs(5)[2]
        repeat(500) {
            val out = shuffleSongs(songs(5), after = last, rng = rng)
            assertNotEquals("2", out[0].id)
            assertEquals(listOf("0", "1", "2", "3", "4"), out.map { it.id }.sorted())
        }
        assertEquals(listOf("0"), shuffleSongs(songs(1), after = songs(1)[0]).map { it.id })
    }
}
