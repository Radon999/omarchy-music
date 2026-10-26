"""A new album cover (cover_image, write_cover, MusicApp.set_cover)."""
import shutil
import subprocess
import types
import unittest

from app import TMP, load

om = load()
JPEG = b"\xff\xd8\xff\xe0" + b"0" * 100
PNG = b"\x89PNG\r\n\x1a\n" + b"0" * 100


def song(name, album, artist="Band", ext="flac"):
    path = TMP / f"{name}.{ext}"
    subprocess.run(["ffmpeg", "-v", "quiet", "-y", "-f", "lavfi", "-i", "anullsrc=r=8000:cl=mono", "-t", "0.2",
                    "-metadata", f"title={name}", "-metadata", f"artist={artist}", "-metadata", f"album={album}",
                    str(path)], check=True)
    return path


class CoverImageTest(unittest.TestCase):
    def test_keeps_jpeg_and_png_and_refuses_the_rest(self):
        self.assertEqual(om.cover_image(JPEG), (JPEG, "image/jpeg"))
        self.assertEqual(om.cover_image(PNG)[1], "image/png")
        with self.assertRaises(ValueError):
            om.cover_image(b"<html>not found</html>")


@unittest.skipUnless(shutil.which("ffmpeg"), "needs ffmpeg to make songs")
class SetCoverTest(unittest.TestCase):
    def test_writes_flac_mp3_and_m4a(self):
        for ext in ("flac", "mp3", "m4a"):
            path = song(f"w-{ext}", "A", ext=ext)
            om.write_cover(path, JPEG, "image/jpeg")
            self.assertEqual(om.file_cover(path), JPEG, ext)
            self.assertFalse(any(p.name.endswith(".cover") for p in TMP.iterdir()))   # the copy is gone

    def test_goes_on_the_whole_album(self):
        one, two, other = song("one", "Album"), song("two", "Album (Deluxe Edition)"), song("three", "Other")
        tracks = {p: om.track_from_file(p) for p in (one, two, other)}
        local = types.SimpleNamespace(files={t["file"]: {"track": t} for t in tracks.values()})
        lib = types.SimpleNamespace(items={}, lock=om.threading.Lock(), save=lambda: None)
        chosen = []
        pictures = types.SimpleNamespace(choose_cover=lambda *a: chosen.append(a))
        app = types.SimpleNamespace(lib=lib, local=local, pictures=pictures)
        app.album_files = lambda t: om.MusicApp.album_files(app, t)
        art = TMP / "picked.png"   # (not cover.png: that one is any folder song's cover)
        art.write_bytes(PNG)
        ids, written, failed = om.MusicApp.set_cover(app, [tracks[one]], str(art))
        self.assertEqual((written, failed), (2, []))
        self.assertEqual(om.file_cover(two), PNG)                 # the same album, deluxe or not
        self.assertNotEqual(om.file_cover(other), PNG)
        self.assertEqual(ids, {tracks[one]["id"], tracks[two]["id"]})
        self.assertEqual((om.THUMBS / f"{tracks[two]['id']}.jpg").read_bytes(), PNG)   # shown
        self.assertEqual(chosen[0][:2], ("Band", "Album"))


if __name__ == "__main__":
    unittest.main()
