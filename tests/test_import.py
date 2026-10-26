"""An imported .m3u8's songs that weren't found, and putting them back where they were."""
import types
import unittest

from app import TMP, load

om = load()


class FakeSource:
    signed_in = True

    def __init__(self, hits=None, fail=False):
        self.hits, self.fail = hits or {}, fail

    def search(self, q):
        if self.fail:
            raise OSError("offline")
        return [h for k, h in self.hits.items() if k in q.lower()]


def song(i, title, artist, secs=200):
    return {"id": i, "title": title, "artist": artist, "duration": secs}


class ImportReportTest(unittest.TestCase):
    def app(self, src):
        return types.SimpleNamespace(my_songs=lambda: [], sources={"tidal": src}, wait_source=lambda k: src,
                                     resolve_query=lambda q: [], match_song=om.MusicApp.match_song)

    def m3u(self, *lines):
        path = TMP / "list.m3u8"
        path.write_text("#EXTM3U\n" + "\n".join(lines) + "\n")
        return path

    def test_keeps_what_was_missed_and_where_it_goes(self):
        src = FakeSource({"one": song("t1", "One", "U2"), "three": song("t3", "Three", "Band")})
        path = self.m3u("#EXTINF:200,U2 - One", "/x/one.flac", "#EXTINF:180,Nobody - Lost Song", "/x/lost.flac",
                        "#EXTINF:200,Band - Three", "/x/three.flac", "/x/04 Nameless.flac")
        name, out, missed = om.MusicApp.import_m3u(self.app(src), path)
        self.assertEqual(name, "list")
        self.assertEqual([t["id"] for t in out], ["t1", "t3"])
        self.assertEqual([(m["n"], m["at"], m["artist"], m["title"]) for m in missed],
                         [(1, 1, "Nobody", "Lost Song"), (3, 2, "", "Nameless")])
        self.assertEqual(missed[0]["why"], "not found")
        self.assertEqual(missed[0]["secs"], 180)
        self.assertEqual(om.missed_name(missed[0]), "Nobody - Lost Song")

    def test_says_when_the_search_failed(self):
        path = self.m3u("#EXTINF:200,U2 - One", "/x/one.flac")
        _name, out, missed = om.MusicApp.import_m3u(self.app(FakeSource(fail=True)), path)
        self.assertEqual(out, [])
        self.assertIn("Tidal didn't answer (offline)", missed[0]["why"])

    def test_match_needs_the_artist_and_the_length(self):
        src = FakeSource({"one": song("t1", "One", "U2", 276)})
        self.assertEqual(om.MusicApp.match_song(src, "U2", "One", 275)[0]["id"], "t1")
        self.assertIsNone(om.MusicApp.match_song(src, "U2", "One", 200)[0])         # too long
        self.assertIsNone(om.MusicApp.match_song(src, "Metallica", "One", 0)[0])   # someone else's
        self.assertEqual(len(om.MusicApp.match_song(src, "Metallica", "One", 0)[1]), 1)   # still shown


class PlaceMissedTest(unittest.TestCase):
    def test_goes_back_where_it_was(self):
        om.PLAYLISTS.unlink(missing_ok=True)
        lists = om.Playlists()
        pid = lists.create("Mix")["id"]
        lists.add(pid, [song("a", "A", "x"), song("c", "C", "x"), song("e", "E", "x")])
        app = types.SimpleNamespace(lists=lists, playlists_changed=lambda: None)
        placed = []
        d = {"n": 4, "at": 2}   # between C and E
        b = {"n": 1, "at": 1}   # between A and C, put back second
        self.assertTrue(om.MusicApp.place_missed(app, pid, d, song("d", "D", "x"), placed))
        self.assertTrue(om.MusicApp.place_missed(app, pid, b, song("b", "B", "x"), placed))
        self.assertEqual([t["id"] for t in lists.get(pid)["tracks"]], ["a", "b", "c", "d", "e"])
        self.assertFalse(om.MusicApp.place_missed(app, pid, {"n": 9, "at": 3}, song("a", "A", "x"), placed))   # in it already


if __name__ == "__main__":
    unittest.main()
