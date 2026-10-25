package org.omarchy.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RegexTest {
    /** Android's ICU regex throws on (?U) (and UNICODE_CHARACTER_CLASS), though the JVM these tests
     *  run on takes it: a pattern with it broke every YouTube download, so none may come back. */
    @Test fun noUnicodeFlag() {
        val bad = File("src/main/java").walk().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                if (Regex("""\(\?[a-zA-Z]*U[a-zA-Z]*[):]|UNICODE_CHARACTER_CLASS""").containsMatchIn(line)) "${f.name}:${i + 1}" else null
            }
        }.toList()
        assertTrue("(?U) doesn't work on Android: $bad", bad.isEmpty())
    }

    @Test fun featuredArtists() {
        assertEquals(setOf("a", "b"), featured("Song (feat. A & B)"))
        assertEquals(setOf("bryant barnes"), featured("Juliet [FT. Bryant Barnes]"))
        assertEquals(setOf("emilio piano"), featured("juliet (with Emilio Piano)"))
        assertEquals(emptySet<String>(), featured("Without You"))
    }
}
