"""Sorting a playlist's songs (sort_songs)."""
import unittest

from app import load

om = load()

SONGS = [{"id": "1", "title": "Zebra", "artist": "Beta", "album": "B", "duration": 100},
         {"id": "2", "title": "apple", "artist": "Alpha, Gamma", "album": "", "duration": 300},
         {"id": "3", "title": "Mango", "artist": "", "album": "A", "duration": 200}]


class SortSongsTest(unittest.TestCase):
    def ids(self, sort, plays=None):
        return [t["id"] for t in om.sort_songs(SONGS, sort, plays)]

    def test_each_sort(self):
        self.assertEqual(self.ids("order"), ["1", "2", "3"])
        self.assertEqual(self.ids("title"), ["2", "3", "1"])
        self.assertEqual(self.ids("artist"), ["2", "1", "3"])   # by the lead artist; none: last
        self.assertEqual(self.ids("album"), ["3", "1", "2"])    # no album: last
        self.assertEqual(self.ids("length"), ["2", "3", "1"])   # longest first
        self.assertEqual(self.ids("plays", {om.song_key(SONGS[2]): 9, om.song_key(SONGS[0]): 2}), ["3", "1", "2"])

    def test_leaves_the_playlist_alone(self):
        before = [t["id"] for t in SONGS]
        om.sort_songs(SONGS, "title")
        self.assertEqual([t["id"] for t in SONGS], before)


if __name__ == "__main__":
    unittest.main()
