"""Songs' speeds (SongSpeeds) and how mpv is told (Player.load)."""
import types
import unittest

from app import load

om = load()


class SongSpeedsTest(unittest.TestCase):
    def setUp(self):
        om.SONG_SPEEDS.unlink(missing_ok=True)

    def test_kept_by_song_on_any_service(self):
        s = om.SongSpeeds()
        yt = {"id": "yt1", "title": "Song (Remastered)", "artist": "A, B"}
        td = {"id": "td9", "title": "Song", "artist": "A"}
        self.assertEqual(s.get(yt), (1.0, False))
        s.set([yt], 1.25, pitch=True)
        self.assertEqual(om.SongSpeeds().get(td), (1.25, True))   # the same song, kept
        s.set([td], 9)
        self.assertEqual(s.get(yt), (2.0, False))                  # no faster than twice
        s.set([yt], 1.0)
        self.assertEqual(s.items, {})                               # normal: forgotten

    def test_every_file_gets_its_speed(self):
        sent = []
        p = types.SimpleNamespace(cmd=lambda *a: sent.append(a))
        om.Player.load(p, "/a.flac", paused=True, speed=1.25, pitch=True)
        om.Player.load(p, "/b.flac")
        self.assertEqual(sent[0], ("loadfile", "/a.flac", "replace", -1, "pause=yes,speed=1.25,audio-pitch-correction=no"))
        self.assertEqual(sent[1][4], "pause=no,speed=1,audio-pitch-correction=yes")   # back to normal for the next


if __name__ == "__main__":
    unittest.main()
