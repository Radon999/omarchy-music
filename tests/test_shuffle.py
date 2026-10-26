"""Shuffle: every order equally likely, and repeat-all shuffles again instead of replaying the same order."""
import itertools
import random
import types
import unittest
from collections import Counter

from app import load

om = load()


def songs(n):
    return [{"id": str(i), "title": f"Song {i}", "artist": "A"} for i in range(n)]


class ShuffleTest(unittest.TestCase):
    def test_every_order_equally_likely(self):
        rng = random.Random(7)
        runs = 60_000
        seen = Counter(tuple(t["id"] for t in om.shuffle_songs(songs(4), rng=rng)) for _ in range(runs))
        self.assertEqual(len(seen), 24)   # all 4! orders turn up
        expected = runs / 24
        chi2 = sum((seen[p] - expected) ** 2 / expected for p in itertools.permutations("0123"))
        self.assertLess(chi2, 49.7)   # chi-squared, 23 degrees of freedom, p = 0.001

    def test_uses_the_system_random_source(self):
        self.assertIsInstance(om.SHUFFLE_RANDOM, random.SystemRandom)

    def test_the_song_that_just_played_is_not_first(self):
        rng = random.Random(1)
        last = songs(5)[2]
        for _ in range(500):
            out = om.shuffle_songs(songs(5), after=last, rng=rng)
            self.assertNotEqual(out[0]["id"], "2")
            self.assertEqual(sorted(t["id"] for t in out), ["0", "1", "2", "3", "4"])
        self.assertEqual([t["id"] for t in om.shuffle_songs(songs(1), after=songs(1)[0])], ["0"])

    def test_repeat_all_shuffles_again_at_the_end(self):
        app = types.SimpleNamespace(queue=songs(6), index=5, repeat="all", shuffled=True, loaded=0, changed=0)
        app.current_track = lambda: app.queue[app.index]
        app.load_current = lambda: setattr(app, "loaded", app.loaded + 1)
        app.after_queue_change = lambda: setattr(app, "changed", app.changed + 1)
        orders = set()
        for _ in range(20):
            app.index = len(app.queue) - 1
            last = app.queue[-1]["id"]
            om.MusicApp.next(app)
            self.assertEqual(app.index, 0)
            self.assertNotEqual(app.queue[0]["id"], last)
            orders.add(tuple(t["id"] for t in app.queue))
        self.assertGreater(len(orders), 10)   # a new order each time round
        app.shuffled, before = False, list(app.queue)
        app.index = 5
        om.MusicApp.next(app)
        self.assertEqual(app.queue, before)   # not shuffled: the same order again


if __name__ == "__main__":
    unittest.main()
