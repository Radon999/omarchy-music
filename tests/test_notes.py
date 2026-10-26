"""Genius's annotations, and which lines of the lyrics they're about."""
import unittest

from app import load

om = load()

LYRICS = """[Verse 1]
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


class PlaceNotesTest(unittest.TestCase):
    def test_lines_and_repeats(self):
        notes = [{"fragment": "This is what you get\n This is what you get\n When you mess with us"},
                 {"fragment": "He talks in maths"},
                 {"fragment": "crashed her party"},          # part of a line
                 {"fragment": "Karma police, arrest this man\nHe talks in Maths"},   # (its 2nd line has a note already)
                 {"fragment": "Nothing like this at all"}]
        at = om.place_notes(LYRICS, notes)
        self.assertEqual(at, {5: 0, 6: 0, 7: 0, 10: 0, 11: 0, 12: 0, 2: 1, 13: 2, 1: 3})

    def test_quotes_and_case_dont_matter(self):
        at = om.place_notes("I’ve given ALL I can\nIt's not enough", [{"fragment": "I've given all I can, it’s"}])
        self.assertEqual(at, {0: 0})   # it runs on past the line: that line's the one
        at = om.place_notes("I’ve given ALL I can\nIt's not enough", [{"fragment": "I've given all I can\nits not enough"}])
        self.assertEqual(at, {0: 0, 1: 0})


class GeniusNotesTest(unittest.TestCase):
    def test_reads_the_referents(self):
        pages = []

        def fake(url, *a, **k):
            pages.append(url)
            return {"response": {"referents": [
                {"is_description": True, "fragment": "Karma Police", "annotations": [{"body": {"plain": "About the song"}}]},
                {"fragment": "He talks in maths", "url": "https://genius.com/1", "annotations": [
                    {"body": {"plain": " Maths! "}, "votes_total": 12, "verified": False, "state": "accepted"},
                    {"body": {"plain": ""}}]},
                {"fragment": "Nobody wrote anything", "annotations": []}]}}
        real, om.http_json = om.http_json, fake
        try:
            notes = om.genius_notes(64344)
        finally:
            om.http_json = real
        self.assertEqual(notes, [{"fragment": "He talks in maths", "url": "https://genius.com/1",
                                  "notes": [{"text": "Maths!", "votes": 12, "verified": False, "state": "accepted"}]}])
        self.assertEqual(len(pages), 1)   # fewer than a page's worth: no more pages
        self.assertIn("song_id=64344", pages[0])


if __name__ == "__main__":
    unittest.main()
