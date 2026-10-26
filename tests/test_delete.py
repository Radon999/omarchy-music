"""Deleting songs' files (MusicApp.delete_files, delete_songs)."""
import types
import unittest
from unittest import mock

from app import TMP, load

om = load()


def fake_app(lib, local, queue, lists):
    app = types.SimpleNamespace(lib=lib, local=local, queue=queue, lists=lists, index=0, win=None, toasts=[])
    app.toast = app.toasts.append
    app.current_track = lambda: queue[app.index] if queue else None
    app.unqueue_many = lambda idx: om.MusicApp.unqueue_many(app, idx)
    app.after_queue_change = lambda: None
    app.delete_files = lambda tracks: om.MusicApp.delete_files(app, tracks)
    return app


class DeleteTest(unittest.TestCase):
    def setUp(self):
        om.LIBRARY.unlink(missing_ok=True)
        om.PLAYLISTS.unlink(missing_ok=True)
        self.dl = TMP / "dl.flac"
        self.mine = TMP / "mine.flac"
        self.dl.write_bytes(b"x")
        self.mine.write_bytes(b"y")
        self.lib = om.Library()
        self.lib.items = {"yt1": {"file": str(self.dl), "title": "Saved", "artist": "A"},
                          "td1": {"file": str(self.dl), "title": "Saved", "artist": "A"}}   # one file, two services
        mine = om.track_from_file(self.mine)
        self.local = types.SimpleNamespace(files={mine["file"]: {"mtime": 0, "size": 1, "track": mine}})
        self.lists = om.Playlists()
        pl = self.lists.create("P")
        self.lists.add(pl["id"], [mine, {"id": "yt1", "title": "Saved", "artist": "A"}])
        self.playing = {"id": "now", "title": "Now", "artist": "B"}
        self.app = fake_app(self.lib, self.local, [self.playing, mine], self.lists)
        self.mine_track = mine

    def test_deletes_downloads_and_trashes_your_files(self):
        trashed = []
        with mock.patch.object(om.Gio.File, "new_for_path",
                               lambda p: types.SimpleNamespace(trash=lambda _c: trashed.append(p))):
            n = om.MusicApp.delete_songs(self.app, [{"id": "yt1", "title": "Saved"}, self.mine_track, self.playing])
        self.assertEqual(n, 2)
        self.assertFalse(self.dl.exists())                 # gone for good, for both services' entries
        self.assertEqual(self.lib.items, {})
        self.assertEqual(trashed, [self.mine_track["file"]])
        self.assertEqual(self.local.files, {})
        self.assertEqual([t["id"] for t in self.app.queue], ["now"])           # the file left the queue
        self.assertEqual([t["id"] for t in self.lists.items[0]["tracks"]], ["yt1"])   # and playlists; a stream stays
        self.assertIn("The playing song is left as it is: skip it first", self.app.toasts)

    def test_a_file_that_wont_go_is_said(self):
        def fail(_c):
            raise OSError("read-only")
        with mock.patch.object(om.Gio.File, "new_for_path", lambda p: types.SimpleNamespace(trash=fail)):
            self.assertEqual(om.MusicApp.delete_songs(self.app, [self.mine_track]), 0)
        self.assertTrue(self.mine.exists())
        self.assertTrue(any("read-only" in t for t in self.app.toasts))


if __name__ == "__main__":
    unittest.main()
