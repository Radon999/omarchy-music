"""Your songs' moods (Moods): Chartmetric, else Last.fm's tags, else a guess; and the DJ's use of them."""
import json
import unittest
from unittest import mock

from app import load

om = load()


def song(title, artist, **kw):
    return {"id": f"{artist}-{title}", "title": title, "artist": artist, **kw}


class FakeFM:
    def __init__(self, tracks=None, artists=None, key="k"):
        self.key, self.tracks, self.artists, self.calls = key, tracks or {}, artists or {}, []

    def call(self, method, **p):
        self.calls.append((method, p.get("track")))
        return {"toptags": {"tag": [{"name": n, "count": c} for n, c in self.tracks.get(p["track"], [])]}}

    def artist_tags(self, artist):
        self.calls.append(("artist", artist))
        return self.artists.get(artist, [])


class FakeLists:
    def __init__(self, lists=()):
        self.lists = list(lists)

    def all(self):
        return self.lists


class FakeApp:
    def __init__(self, songs, fm, keys=None):
        self.songs, self.lastfm, self.api_keys, self.lists = songs, fm, keys or {}, FakeLists()

    def my_songs(self):
        return list(self.songs)

    def most_played(self):
        return []


class MoodWordsTest(unittest.TestCase):
    def test_tags_to_moods(self):
        self.assertEqual(om.moods_of([("sad", 100), ("melancholic", 60), ("indie", 90), ("chill", 20)]), ["sad"])
        self.assertEqual(om.moods_of([("Chill", 100), ("lo-fi", 80), ("study", 70)]), ["chill", "focus"])
        self.assertEqual(om.moods_of([("metal", 100)]), ["angry", "energetic"])   # a genre, by what it usually means
        self.assertEqual(om.moods_of([("seen live", 100)]), [])
        self.assertEqual(om.moods_of([("british alternative rock", 10)]), ["energetic"])   # by the genre it ends in

    def test_guesses_from_genre_then_title(self):
        self.assertEqual(om.guess_moods(song("X", "A", genre="Drum and Bass")), ["energetic", "party"])
        self.assertEqual(om.guess_moods(song("X", "A", genre=["Hip-Hop/Rap"])), ["energetic"])
        self.assertEqual(om.guess_moods(song("Tears in the Rain (Remastered)", "A")), ["sad", "chill"])
        self.assertEqual(om.guess_moods(song("Untitled 3", "A")), [])

    def test_what_a_request_means(self):
        self.assertEqual(om.request_moods("something to chill to"), ["chill"])
        self.assertEqual(om.request_moods("gym hype"), ["energetic"])
        self.assertEqual(om.request_moods("80s rock"), [])


MB = {"Joji": [("lo-fi", 9), ("r&b", 5)], "Band": [("death metal", 4)]}


def musicbrainz(url, headers=None, data=None, method=None, timeout=20):
    """http_json answering MusicBrainz's artist search from MB."""
    name = url.split("artist%3A%22")[1].split("%22")[0].replace("+", " ")
    return {"artists": [{"name": name, "tags": [{"name": t, "count": c} for t, c in MB.get(name, [])]}]}


class MoodsTest(unittest.TestCase):
    def setUp(self):
        for p in (mock.patch.object(om, "http_json", musicbrainz), mock.patch.object(om, "MB_PACE", 0),
                  mock.patch.object(om, "MOOD_SCAN_PACE", 0)):
            p.start()
            self.addCleanup(p.stop)
        om.MOODS_FILE.unlink(missing_ok=True)
        self.songs = [song("Glimpse of Us", "Joji"), song("Levels", "Avicii"), song("Odd One", "Band", genre="metal")]
        self.fm = FakeFM(tracks={"Glimpse of Us": [("sad", 100), ("love", 40)], "Levels": [("house", 100), ("party", 80)]})
        self.app = FakeApp(self.songs, self.fm)

    def test_scans_each_song_once_and_keeps_it(self):
        with mock.patch.object(om, "MOOD_SCAN_PACE", 0):
            m = om.Moods(self.app)
            self.assertEqual(m.scan(), 3)
            self.assertEqual(m.of(self.songs[0]), ["sad", "romantic"])
            self.assertEqual(m.songs[m.key(self.songs[0])]["from"], "lastfm")
            self.assertEqual(m.of(self.songs[1]), ["party"])
            self.assertEqual((m.of(self.songs[2]), m.songs[m.key(self.songs[2])]["from"]), (["angry", "energetic"], "guess"))
            self.assertEqual(m.scan(), 0)   # nothing new
            self.app.songs.append(song("New", "Joji"))
            self.fm.artists["Joji"] = [("sad", 100), ("trip-hop", 50)]
            self.assertEqual(m.scan(), 1)   # only the new song; its artist's tags, since it has none
            self.assertEqual(m.of(self.app.songs[-1]), ["sad"])
        again = om.Moods(self.app)   # kept between starts
        self.assertEqual(again.of(self.songs[1]), ["party"])
        self.assertEqual(len(again.todo(again.pool(), again=True)), 1)   # "look again" reads the guessed ones

    def test_smart_playlists_strongest_first(self):
        with mock.patch.object(om, "MOOD_SCAN_PACE", 0):
            m = om.Moods(self.app)
            m.scan()
        m.songs[m.key(self.songs[1])]["moods"] = ["party", "sad"]
        self.assertEqual([t["title"] for t in m.songs_in("sad")], ["Glimpse of Us", "Levels"])
        self.assertEqual(dict(m.counts())["sad"], 2)

    def test_without_last_fm_it_guesses(self):
        self.app.lastfm = FakeFM(key="")
        m = om.Moods(self.app)
        m.scan()
        self.assertEqual(m.of(self.songs[0]), ["chill"])   # its artist's genres on MusicBrainz
        self.assertEqual(m.of(self.songs[2]), ["angry", "energetic"])
        self.assertEqual(self.fm.calls, [])


class ChartmetricTest(unittest.TestCase):
    def test_finds_the_track_and_reads_its_moods(self):
        seen = []

        def http_json(url, headers=None, data=None, method=None, timeout=20):
            seen.append((url.split("?")[0], (headers or {}).get("Authorization")))
            if url.endswith("/token"):
                self.assertEqual(json.loads(data), {"refreshtoken": "rt"})
                return {"token": "tok", "expires_in": 3600}
            if "/search" in url:
                return {"obj": {"tracks": [{"id": 9, "name": "Other", "artist_names": ["Joji"]},
                                           {"id": 7, "name": "Glimpse of Us", "artist_names": ["Joji"]}]}}
            return {"obj": {"moods": [{"name": "Melancholic"}, "Sad"], "activities": ["Sleep"]}}
        with mock.patch.object(om, "http_json", http_json):
            cm = om.Chartmetric("rt")
            self.assertEqual(om.moods_of(cm.moods("Joji", "Glimpse of Us")), ["sad", "chill"])
        self.assertEqual(seen[-1], ("https://api.chartmetric.com/api/track/7", "Bearer tok"))

    def test_scan_falls_back_when_chartmetric_fails(self):
        om.MOODS_FILE.unlink(missing_ok=True)
        app = FakeApp([song("Levels", "Avicii")], FakeFM(tracks={"Levels": [("party", 10)]}), keys={"chartmetric": "rt"})

        def down(*a, **k):
            raise OSError("unreachable")
        with mock.patch.object(om, "http_json", down), mock.patch.object(om, "MOOD_SCAN_PACE", 0):
            m = om.Moods(app)
            m.scan()
        self.assertEqual((m.of(app.songs[0]), m.songs[m.key(app.songs[0])]["from"]), (["party"], "lastfm"))


if __name__ == "__main__":
    unittest.main()
