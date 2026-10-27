"""The Stellio update: EQ band counts, queues kept per list, lyrics put into files."""
import shutil
import subprocess
import tempfile
import types
import unittest
from pathlib import Path

from app import load

om = load()


class EqBandsTest(unittest.TestCase):
    def test_counts(self):
        for n in om.EQ_COUNTS:
            self.assertEqual(len(om.eq_bands_for(n)), n)

    def test_resample_keeps_the_curve(self):
        bass = list(om.EQ_PRESETS["bass"][1])
        five = om.eq_resample(bass, om.EQ_BANDS, 5)
        self.assertEqual(len(five), 5)
        self.assertGreater(five[0], 3)
        self.assertEqual(five[-1], 0)
        self.assertEqual(om.eq_resample(bass, om.EQ_BANDS, 10), bass)

    def test_graph_has_a_filter_per_band(self):
        g = om.eq_graph((), [0.0] * 31, 0.0, {})
        self.assertEqual(g.count("equalizer@g"), 31)


def queue_app():
    a = types.SimpleNamespace(queue=[], index=-1, queues=[], queue_id=None, queue_name_s="", multi_queue=True,
                              position=0.0, radio_from=None, shuffled=False, resume_at=0.0, context=0, win=None,
                              loaded=[], toasts=[])
    for name in ("snapshot_queue", "start_queue", "switch_queue", "delete_queue", "remove_other_queues"):
        setattr(a, name, getattr(om.MusicApp, name).__get__(a))
    a.load_current = lambda: a.loaded.append((a.queue[a.index]["id"], a.resume_at))
    a.toast = a.toasts.append
    a.queues_changed = lambda: None
    return a


class QueuesTest(unittest.TestCase):
    def test_a_queue_per_list_and_back(self):
        a = queue_app()
        a.start_queue("Album A")
        a.queue, a.index, a.position = [{"id": "a1"}, {"id": "a2"}], 1, 42.0
        self.assertTrue(a.start_queue("Artist B"))   # another list: another queue
        first = a.queues[0]
        self.assertEqual((first["name"], first["index"], first["position"]), ("Album A", 1, 42.0))
        a.queue, a.index = [{"id": "b1"}], 0
        a.switch_queue(first["id"])
        self.assertEqual(a.queue_name_s, "Album A")
        self.assertEqual(a.loaded[-1], ("a2", 42.0))   # where you left it
        self.assertEqual({q["name"] for q in a.queues}, {"Album A", "Artist B"})

    def test_same_list_same_queue(self):
        a = queue_app()
        a.start_queue("Mix")
        a.queue, a.index = [{"id": "x"}], 0
        self.assertFalse(a.start_queue("Mix"))
        a.multi_queue = False
        self.assertFalse(a.start_queue("Other"))


@unittest.skipUnless(shutil.which("ffmpeg"), "needs ffmpeg")
class LyricsIntoFilesTest(unittest.TestCase):
    def test_each_kind(self):
        d = Path(tempfile.mkdtemp())
        for ext, codec in (("mp3", "libmp3lame"), ("flac", "flac"), ("m4a", "aac"), ("ogg", "libvorbis")):
            f = d / f"t.{ext}"
            subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "sine=d=1", "-c:a", codec, str(f)], check=True)
            self.assertTrue(om.put_lyrics(str(f), "one\ntwo"), ext)
            self.assertFalse(om.put_lyrics(str(f), "again"), ext)   # lyrics there already: left alone

    def test_words_without_times(self):
        self.assertEqual(om.Lyrics.words({"synced": "[00:01.00]one\n[00:02.00]two"}), "one\ntwo")
        self.assertEqual(om.Lyrics.words({"plain": " words ", "synced": "[00:01.00]x"}), "words")


if __name__ == "__main__":
    unittest.main()
