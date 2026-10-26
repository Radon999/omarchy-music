"""The source bar's Local: your own songs, the search looks in them, and back to a service after."""
import types
import unittest

from app import load

om = load()


def app(**kw):
    a = types.SimpleNamespace(**{"source": "tidal", "browse_local": False, "search_in": "tidal",
                                 "search_before_local": "tidal", "win": None, "saved": 0, **kw})
    a.save_state = lambda: setattr(a, "saved", a.saved + 1)
    return a


class BrowseTest(unittest.TestCase):
    def test_local_and_back(self):
        a = app(search_in="ytm")
        om.MusicApp.browse(a, "local")
        self.assertEqual((a.browse_local, a.search_in, a.search_before_local), (True, "local", "ytm"))
        om.MusicApp.browse(a, "apple")   # a service again: its library, and the search where it was
        self.assertEqual((a.browse_local, a.source, a.search_in), (False, "apple", "ytm"))

    def test_a_search_picked_while_local_stays(self):
        a = app()
        om.MusicApp.browse(a, "local")
        a.search_in = "tidal"   # picked in the dropdown while on Local
        om.MusicApp.browse(a, "ytm")
        self.assertEqual(a.search_in, "tidal")

    def test_nothing_to_do(self):
        a = app()
        om.MusicApp.browse(a, "tidal")
        om.MusicApp.browse(a, "nonsense")
        self.assertEqual(a.saved, 0)


if __name__ == "__main__":
    unittest.main()
