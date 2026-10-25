"""Artists' pictures and bios, and album covers (Pictures): Google, then Genius, then Wikipedia."""
import unittest
import urllib.parse
from unittest import mock

from app import load

om = load()

GENIUS_ARTISTS = {"response": {"sections": [{"hits": [
    {"result": {"id": 1, "name": "Joji", "image_url": "https://images.genius.com/joji.png", "url": "https://genius.com/artists/Joji"}},
    {"result": {"id": 2, "name": "Jojinooo", "image_url": "https://assets.genius.com/images/default_cover_image.png"}}]}]}}
GENIUS_BIO = {"response": {"artist": {"description": {"plain": "Joji makes music."}}}}
GENIUS_ALBUMS = {"response": {"sections": [{"hits": [
    {"result": {"name": "Nectar", "cover_art_url": "https://images.genius.com/nectar.png", "artist": {"name": "Joji"}}},
    {"result": {"name": "Joji - Nectar (Traducción al Español)", "cover_art_url": "https://images.genius.com/es.png",
                "artist": {"name": "Genius Traducciones al Español"}}}]}]}}
WIKI_ARTIST = {"query": {"pages": {
    "9": {"index": 2, "title": "Joji (disambiguation)", "pageprops": {"disambiguation": ""}},
    "7": {"index": 1, "title": "Joji (musician)", "extract": "George Miller, known as Joji, is a singer.",
          "original": {"source": "https://upload.wikimedia.org/joji.png"},
          "pageprops": {"wikibase-shortdesc": "Japanese-Australian musician"}}}}}
WIKI_ALBUM = {"query": {"pages": {"5": {"index": 1, "title": "Nectar (Joji album)", "extract": "Nectar is an album.",
                                        "pageprops": {"wikibase-shortdesc": "2020 studio album by Joji",
                                                      "page_image": "Joji_-_Nectar.png"}}}}}
WIKI_FILE = {"query": {"pages": {"-1": {"imageinfo": [{"url": "https://upload.wikimedia.org/nectar.png"}]}}}}
GOOGLE = {"items": [{"link": "https://example.com/g1.jpg", "image": {"thumbnailLink": "https://example.com/t1.jpg"}},
                    {"link": "https://example.com/g2.jpg"}]}


def fake(down=()):
    """http_json answering like the services do; the ones in down fail as if unreachable."""
    calls = []

    def http_json(url, headers=None, data=None, method=None, timeout=20):
        calls.append(url)
        host = urllib.parse.urlparse(url).netloc
        if any(d in host for d in down):
            raise OSError("unreachable")
        q = urllib.parse.parse_qs(urllib.parse.urlparse(url).query)
        if "googleapis" in host:
            return GOOGLE
        if "/api/search/artist" in url:
            return GENIUS_ARTISTS
        if "/api/search/album" in url:
            return GENIUS_ALBUMS
        if "/api/artists/" in url:
            return GENIUS_BIO
        if q.get("prop") == ["imageinfo"]:
            return WIKI_FILE
        return WIKI_ALBUM if "album" in q.get("gsrsearch", [""])[0] else WIKI_ARTIST
    return http_json, calls


class PicturesTest(unittest.TestCase):
    def setUp(self):
        om.PICTURES.unlink(missing_ok=True)
        self.keys = {}
        self.pics = om.Pictures(lambda: self.keys)

    def test_without_a_google_key_genius_then_wikipedia(self):
        http, calls = fake()
        with mock.patch.object(om, "http_json", http):
            a = self.pics.artist("Joji")
        self.assertEqual((a["image"], a["image_from"]), ("https://images.genius.com/joji.png", "Genius"))
        self.assertEqual((a["bio_from"], a["bio_url"]), ("Wikipedia", "https://en.wikipedia.org/wiki/Joji_%28musician%29"))
        self.assertTrue(a["bio"].startswith("George Miller"))
        self.assertFalse(any("googleapis" in c for c in calls))

    def test_google_first_with_a_key(self):
        self.keys.update(google_key="k", google_cx="cx")
        http, _ = fake()
        with mock.patch.object(om, "http_json", http):
            a = self.pics.artist("Joji")
        self.assertEqual((a["image"], a["image_from"]), ("https://example.com/g1.jpg", "Google"))

    def test_a_service_that_is_down_is_skipped(self):
        self.keys.update(google_key="k", google_cx="cx")
        http, _ = fake(down=("googleapis", "genius"))
        with mock.patch.object(om, "http_json", http):
            a = self.pics.artist("Joji")
        self.assertEqual((a["image"], a["image_from"]), ("https://upload.wikimedia.org/joji.png", "Wikipedia"))
        http, _ = fake(down=("wikipedia",))
        with mock.patch.object(om, "http_json", http):
            b = self.pics.artist("Joji", refresh=True)
        self.assertEqual((b["bio"], b["bio_from"]), ("Joji makes music.", "Genius"))   # Genius's bio, then

    def test_kept_and_asked_once(self):
        http, calls = fake()
        with mock.patch.object(om, "http_json", http):
            self.pics.artist("Joji")
            n = len(calls)
            self.pics.artist("JOJI")
            self.assertEqual(len(calls), n)
        self.assertEqual(om.Pictures(dict).artist("Joji")["image_from"], "Genius")   # kept between starts

    def test_a_picture_you_chose_stays(self):
        self.pics.choose_artist("Joji", "/home/me/joji.jpg")
        http, _ = fake()
        with mock.patch.object(om, "http_json", http):
            a = self.pics.artist("Joji", refresh=True)
        self.assertEqual((a["image"], a["image_from"]), ("/home/me/joji.jpg", "you"))
        self.assertEqual(a["bio_from"], "Wikipedia")

    def test_cover_choices_in_order(self):
        self.keys.update(google_key="k", google_cx="cx")
        http, _ = fake()
        with mock.patch.object(om, "http_json", http):
            got = self.pics.cover_choices("Joji", "Nectar (Deluxe)")
        self.assertEqual([(c["from"], c["url"]) for c in got], [
            ("Google", "https://example.com/g1.jpg"), ("Google", "https://example.com/g2.jpg"),
            ("Genius", "https://images.genius.com/nectar.png"),        # not its translation's
            ("Wikipedia", "https://upload.wikimedia.org/nectar.png")])  # the non-free cover, by its file
        self.pics.choose_cover("Joji, Rei", "Nectar", got[2]["url"])
        self.assertEqual(self.pics.cover("joji", "Nectar (Deluxe Edition)"), "https://images.genius.com/nectar.png")

    def test_bios_lose_pronunciation_guides(self):
        self.assertEqual(om.wiki_text("Björk Guðmundsdóttir  ( BYURK, Icelandic: [pjœr̥k]; born 1965) is a singer.\nMore."),
                         "Björk Guðmundsdóttir is a singer.\nMore.")
        self.assertEqual(om.wiki_text("Tame Impala ( TAYM im-PAHL-ə) is a project."), "Tame Impala is a project.")
        keep = "Mac Miller (January 19, 1992 – September 7, 2018) was a rapper."
        self.assertEqual(om.wiki_text(keep), keep)


if __name__ == "__main__":
    unittest.main()
