"""Searching as you type: when to (live_search_wait)."""
import unittest

from app import load

om = load()


class LiveSearchTest(unittest.TestCase):
    def test_when_to_search(self):
        self.assertIsNone(om.live_search_wait("", "tidal"))
        self.assertIsNone(om.live_search_wait("a ", "tidal"))   # one letter: not yet
        self.assertEqual(om.live_search_wait("jo", "local"), 150)   # your own songs: right away
        self.assertEqual(om.live_search_wait("joji", "tidal"), 450)   # a service: once you pause
        self.assertEqual(om.live_search_wait("https://tidal.com/browse/track/1", "local"), 50)   # a pasted link: at once


if __name__ == "__main__":
    unittest.main()
