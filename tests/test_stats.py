"""Your Spotify history and the counts that add it to Last.fm's (Stats)."""
import io
import json
import time
import unittest
import zipfile

from app import TMP, load

om = load()


def play(t, artist, title, ms=200_000, album=""):
    return (t, artist, title, album, ms)


class SpotifyHistoryTest(unittest.TestCase):
    def setUp(self):
        om.SPOTIFY_HISTORY.unlink(missing_ok=True)
        self.h = om.SpotifyHistory()

    def test_parses_both_kinds_of_file(self):
        extended = [{"ts": "2024-03-01T10:03:20Z", "ms_played": 200000, "master_metadata_track_name": "Song",
                     "master_metadata_album_artist_name": "Artist", "master_metadata_album_album_name": "Album"},
                    {"ts": "2024-03-01T10:10:00Z", "ms_played": 5000, "master_metadata_track_name": "Skipped",
                     "master_metadata_album_artist_name": "Artist"},
                    {"ts": "2024-03-01T11:00:00Z", "ms_played": 900000, "episode_name": "A podcast"}]
        year = [{"endTime": "2024-01-31 18:02", "artistName": "Other", "trackName": "Tune", "msPlayed": 60000},
                {"endTime": "2024-01-31 18:05", "artistName": "Unknown Artist", "trackName": "Who", "msPlayed": 60000}]
        got = om.SpotifyHistory.parse(extended) + om.SpotifyHistory.parse(year)
        self.assertEqual([(p[1], p[2]) for p in got], [("Artist", "Song"), ("Other", "Tune")])
        start, ms = got[0][0], got[0][4]
        self.assertEqual(start + ms // 1000, 1709287400)   # it began 200 s before it stopped
        self.assertEqual(got[0][3], "Album")
        self.assertEqual(om.SpotifyHistory.parse({"not": "a list"}), [])

    def test_picks_the_history_files(self):
        self.assertTrue(om.SpotifyHistory.wanted("Spotify Extended Streaming History/Streaming_History_Audio_2023.json"))
        self.assertTrue(om.SpotifyHistory.wanted("MyData/StreamingHistory_music_0.json"))
        self.assertFalse(om.SpotifyHistory.wanted("MyData/StreamingHistory_podcast_0.json"))
        self.assertFalse(om.SpotifyHistory.wanted("Spotify Extended Streaming History/Streaming_History_Video_2023.json"))
        self.assertFalse(om.SpotifyHistory.wanted("MyData/Playlist1.json"))

    def test_adds_each_play_once(self):
        self.assertEqual(self.h.add([play(1000, "A", "Song"), play(5000, "A", "Song")]), 2)
        self.assertEqual(self.h.add([play(1060, "A", "Song (Remastered)"), play(9000, "A", "Song")]), 1)
        self.assertEqual(len(om.SpotifyHistory().plays), 3)   # kept

    def test_imports_a_zip(self):
        path = TMP / "my_spotify_data.zip"
        with zipfile.ZipFile(path, "w") as z:
            z.writestr("Spotify Extended Streaming History/Streaming_History_Audio_2024.json", json.dumps(
                [{"ts": "2024-03-01T10:03:20Z", "ms_played": 200000, "master_metadata_track_name": "Song",
                  "master_metadata_album_artist_name": "Artist"}]))
            z.writestr("Spotify Extended Streaming History/ReadMeFirst.pdf", "x")
        self.assertEqual(self.h.import_file(path), 1)
        self.assertEqual(self.h.import_file(path), 0)

    def test_says_what_is_wrong_with_a_zip(self):
        path = TMP / "other.zip"
        with zipfile.ZipFile(path, "w") as z:
            z.writestr("photos/a.json", "[]")
        with self.assertRaisesRegex(ValueError, "no streaming history"):
            self.h.import_file(path)


class NotScrobbledTest(unittest.TestCase):
    def test_a_play_last_fm_has_counts_once(self):
        rows = [(1100, "A", "Song", "", ""), (1150, "A", "Song", "", "")]   # two scrobbles while one play
        plays = [play(1000, "A", "Song"), play(1000 + 200, "A", "Song"), play(50_000, "A", "Song"), play(1000, "B", "Other")]
        left = om.not_scrobbled(plays, rows)
        self.assertEqual([(p[0], p[2]) for p in left], [(50_000, "Song"), (1000, "Other")])


class FakeLastFM:
    connected, user = True, "me"

    def __init__(self, tracks, artists, rows):
        self.tracks, self.artists, self.rows = tracks, artists, rows

    def top(self, kind, limit=10, period="overall", page=1):
        items = self.tracks if kind == "track" else self.artists
        return items[(page - 1) * limit:page * limit]

    def info(self):
        return {"registered": 0, "name": "me", "playcount": 0}

    def recent(self, start, end, progress=None):
        return [r for r in self.rows if start <= r[0] < end]


class FakePlays:
    def __init__(self, items=None):
        self.items = items or {}


class FakeApp:
    def __init__(self, fm, history, plays=None):
        self.lastfm, self.spotify_history, self.plays = fm, history, plays or FakePlays()


class StatsTest(unittest.TestCase):
    def setUp(self):
        om.STATS.unlink(missing_ok=True)
        om.SPOTIFY_HISTORY.unlink(missing_ok=True)
        for f in om.WRAPPED.glob("*.json") if om.WRAPPED.exists() else []:
            f.unlink()

    def test_adds_spotify_to_last_fm_without_counting_a_play_twice(self):
        t0 = int(time.time()) - 86400 * 3
        fm = FakeLastFM(tracks=[("Song", "Artist", 10, ""), ("Other", "Artist", 2, "")],
                        artists=[("Artist", "", 12, "")],
                        rows=[(t0 + 100, "Artist", "Song", "", "")])   # Spotify scrobbled this one to Last.fm
        h = om.SpotifyHistory()
        h.add([play(t0, "Artist", "Song"), play(t0 + 5000, "Artist", "Song"), play(t0 + 9000, "Band", "Hit")])
        stats = om.Stats(FakeApp(fm, h))
        stats.build()
        by = {(s["title"], s["artist"]): s for s in stats.songs}
        self.assertEqual(by[("Song", "Artist")]["plays"], 11)   # 10 scrobbles + 1 Spotify play Last.fm missed
        self.assertEqual(by[("Song", "Artist")]["lastfm"], 10)
        self.assertEqual(by[("Song", "Artist")]["spotify"], 1)
        self.assertEqual(by[("Hit", "Band")]["plays"], 1)
        self.assertEqual(stats.songs[0]["title"], "Song")   # most played first
        artists = {a["name"]: a["plays"] for a in stats.artists}
        self.assertEqual(artists, {"Artist": 13, "Band": 1})
        self.assertEqual(stats.sources, ["Last.fm", "Spotify"])
        again = om.Stats(FakeApp(fm, h))   # kept between starts
        self.assertEqual(len(again.songs), 3)

    def test_without_last_fm_counts_the_plays_here(self):
        fm = FakeLastFM([], [], [])
        fm.connected = False
        plays = FakePlays({"x": {"plays": 4, "track": {"title": "Here", "artist": "Me, You", "album": "A"}}})
        stats = om.Stats(FakeApp(fm, om.SpotifyHistory(), plays))
        stats.build()
        self.assertEqual([(s["title"], s["here"]) for s in stats.songs], [("Here", 4)])
        self.assertEqual([(a["name"], a["plays"]) for a in stats.artists], [("Me", 4)])

    def test_finds_an_artists_songs(self):
        stats = om.Stats(FakeApp(FakeLastFM([], [], []), om.SpotifyHistory()))
        stats.songs = [{"title": "A", "artist": "Joji, Rei Brown", "plays": 5}, {"title": "B", "artist": "Jojii", "plays": 9},
                       {"title": "C", "artist": "Diplo feat. Joji", "plays": 1}]
        self.assertEqual([s["title"] for s in stats.artist_songs("joji")], ["A", "C"])


class ArtistSongsTest(unittest.TestCase):
    def test_counted_songs_then_the_rest_of_yours(self):
        import types
        mine = {"id": "f1", "source": "file", "file": "/m/a.flac", "title": "Hit", "artist": "Joji"}
        quiet = {"id": "yt9", "title": "B-side", "artist": "Joji"}
        app = types.SimpleNamespace(
            stats=types.SimpleNamespace(artist_songs=lambda n: [{"title": "Hit", "artist": "Joji", "plays": 40},
                                                                {"title": "Gone", "artist": "Joji", "plays": 3}]),
            song_index=lambda: {om.song_key(mine): mine}, fm_song=om.MusicApp.fm_song,
            plays=types.SimpleNamespace(items={"yt9": {"plays": 2, "track": quiet}}),
            songs_by=lambda n: [mine, quiet])
        got = om.MusicApp.artist_songs(app, "Joji")
        self.assertEqual([(t["title"], t["plays"]) for t in got], [("Hit", 40), ("Gone", 3), ("B-side", 2)])
        self.assertEqual(got[0]["id"], "f1")             # yours plays from your file
        self.assertEqual(got[1]["source"], "lastfm")     # one you don't have is found when it plays


if __name__ == "__main__":
    unittest.main()
