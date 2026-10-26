"""Editing songs' tags by hand (write_tags, read_edit_tags, MusicApp.edit_tags)."""
import shutil
import subprocess
import types
import unittest

from app import TMP, load

om = load()


def flac(name, title, artist):
    path = TMP / name
    subprocess.run(["ffmpeg", "-v", "quiet", "-y", "-f", "lavfi", "-i", "anullsrc=r=8000:cl=mono", "-t", "0.2",
                    "-metadata", f"title={title}", "-metadata", f"artist={artist}", "-metadata", "album=Same",
                    str(path)], check=True)
    return path


@unittest.skipUnless(shutil.which("ffmpeg"), "needs ffmpeg to make a FLAC")
class TagsTest(unittest.TestCase):
    def test_writes_and_reads_them(self):
        a, b = flac("a.flac", "One", "X"), flac("b.flac", "Two", "X")
        om.write_tags(a, {"artist": "X, Y", "genre": "Jazz", "album": ""})
        have = om.read_edit_tags([a, b])
        self.assertIsNone(have["title"])      # they disagree
        self.assertIsNone(have["artist"])
        self.assertIsNone(have["genre"])
        self.assertEqual(om.read_edit_tags([a])["artist"], "X, Y")
        self.assertEqual(om.read_edit_tags([a])["album"], "")   # taken off
        self.assertEqual(om.track_from_file(a)["artist"], "X, Y")

    def test_the_app_shows_the_new_names_everywhere(self):
        path = flac("c.flac", "Old", "Artist")
        t = om.track_from_file(path)
        lib = types.SimpleNamespace(items={}, lock=om.threading.Lock(), save=lambda: None)
        local = types.SimpleNamespace(files={t["file"]: {"mtime": 0, "size": 0, "track": dict(t, added=5)}})
        lists = types.SimpleNamespace(items=[{"id": "p", "tracks": [dict(t)]}], save=lambda: None)
        app = types.SimpleNamespace(lib=lib, local=local, lists=lists, queue=[dict(t)])
        self.assertEqual(om.MusicApp.edit_tags(app, [t, {"id": "x", "source": "tidal"}], {"title": "New"}), (1, []))
        self.assertEqual(local.files[t["file"]]["track"]["title"], "New")
        self.assertEqual(local.files[t["file"]]["track"]["added"], 5)
        self.assertEqual(lists.items[0]["tracks"][0]["title"], "New")
        self.assertEqual(app.queue[0]["title"], "New")
        self.assertEqual(om.track_from_file(path)["title"], "New")


if __name__ == "__main__":
    unittest.main()
