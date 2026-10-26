"""Folders of playlists in the Library (PlaylistFolders)."""
import unittest

from app import load

om = load()


class PlaylistFoldersTest(unittest.TestCase):
    def setUp(self):
        om.PLAYLIST_FOLDERS.unlink(missing_ok=True)
        self.f = om.PlaylistFolders()

    def test_holds_yours_and_your_services(self):
        gym = self.f.create("Gym")
        mine = om.PlaylistFolders.item({"id": "abc", "name": "Mine", "tracks": []})
        tidal = om.PlaylistFolders.item({"id": "t1", "title": "Tidal Mix", "thumb": "x.jpg"}, "tidal")
        self.assertEqual((mine["kind"], mine["title"]), ("mine", "Mine"))
        self.assertEqual((tidal["kind"], tidal["source"], tidal["title"]), ("service", "tidal", "Tidal Mix"))
        self.assertTrue(self.f.add(gym["id"], mine))
        self.assertTrue(self.f.add(gym["id"], tidal))
        self.assertFalse(self.f.add(gym["id"], dict(tidal)))   # once
        again = om.PlaylistFolders()   # kept
        self.assertEqual([x["title"] for x in again.get(gym["id"])["playlists"]], ["Mine", "Tidal Mix"])

    def test_rename_remove_delete(self):
        a, b = self.f.create("b folder"), self.f.create("A folder")
        self.assertEqual([f["name"] for f in self.f.all()], ["A folder", "b folder"])   # by name
        item = om.PlaylistFolders.item({"id": "p", "title": "P"}, "ytm")
        self.f.add(a["id"], item)
        self.f.add(b["id"], item)
        self.f.remove(a["id"], om.PlaylistFolders.key(item))
        self.assertEqual(self.f.get(a["id"])["playlists"], [])
        self.f.rename(b["id"], "Z")
        self.f.delete(a["id"])
        self.assertEqual([f["name"] for f in self.f.all()], ["Z"])

    def test_a_deleted_playlist_leaves_every_folder(self):
        one, two = self.f.create("One"), self.f.create("Two")
        mine = om.PlaylistFolders.item({"id": "abc", "name": "Mine", "tracks": []})
        self.f.add(one["id"], mine)
        self.f.add(two["id"], mine)
        self.f.forget("mine", "", "abc")
        self.assertEqual([len(f["playlists"]) for f in self.f.all()], [0, 0])


if __name__ == "__main__":
    unittest.main()
