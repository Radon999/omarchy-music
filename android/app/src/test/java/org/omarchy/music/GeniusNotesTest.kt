package org.omarchy.music

import org.junit.Assert.assertEquals
import org.junit.Test

class GeniusNotesTest {
    private val lyrics = """[Verse 1]
Karma police, arrest this man
He talks in maths

[Chorus]
This is what you get
This is what you get
When you mess with us

[Chorus]
This is what you get
This is what you get
When you mess with us
And we have crashed her party"""

    private fun n(fragment: String) = Lyrics.Noted(fragment, "", listOf(Lyrics.Note("x")))

    @Test fun placesNotesOnTheirLines() {
        val notes = listOf(n("This is what you get\n This is what you get\n When you mess with us"), n("He talks in maths"),
            n("crashed her party"), n("Karma police, arrest this man\nHe talks in Maths"), n("Nothing like this at all"))
        assertEquals(mapOf(5 to 0, 6 to 0, 7 to 0, 10 to 0, 11 to 0, 12 to 0, 2 to 1, 13 to 2, 1 to 3), Lyrics.placeNotes(lyrics, notes))
        assertEquals(mapOf(0 to 0, 1 to 0), Lyrics.placeNotes("I’ve given ALL I can\nIt's not enough", listOf(n("I've given all I can\nits not enough"))))
    }

    @Test fun readsTheReferents() {
        val res = JSON.parseToJsonElement("""{"response": {"referents": [
            {"is_description": true, "fragment": "Karma Police", "annotations": [{"body": {"plain": "About the song"}}]},
            {"fragment": "He talks in maths", "url": "https://genius.com/1", "annotations": [
                {"body": {"plain": " Maths! "}, "votes_total": 12, "verified": false, "state": "accepted"}, {"body": {"plain": ""}}]},
            {"fragment": "Nobody wrote anything", "annotations": []}]}}""")
        val (notes, n) = Lyrics.parseNotes(res)
        assertEquals(3, n)
        assertEquals(listOf(Lyrics.Noted("He talks in maths", "https://genius.com/1", listOf(Lyrics.Note("Maths!", 12, false, "accepted")))), notes)
    }
}
