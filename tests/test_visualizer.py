"""The visualizer's spectrum (spectrum_cmd, spectrum_levels)."""
import shutil
import subprocess
import unittest

from app import TMP, load

om = load()


class SpectrumTest(unittest.TestCase):
    def test_levels_from_a_frame(self):
        row = lambda lit: bytes([255] * lit + [0] * (om.VIS_STEPS - lit))
        frame = row(om.VIS_STEPS) + row(om.VIS_FLOOR) + row(0) * (om.VIS_BANDS - 1)
        lv = om.spectrum_levels(frame)
        self.assertEqual(len(lv), om.VIS_BANDS)
        self.assertEqual(lv[:3], [1.0, 0.0, 0.0])

    @unittest.skipUnless(shutil.which("ffmpeg"), "needs ffmpeg")
    def test_a_low_tone_lights_the_low_bars(self):
        path = TMP / "tone.flac"
        subprocess.run(["ffmpeg", "-v", "quiet", "-y", "-f", "lavfi", "-i", "sine=frequency=100:duration=2", str(path)], check=True)
        out = subprocess.run(om.spectrum_cmd(str(path)), capture_output=True, check=True).stdout
        size = (om.VIS_BANDS + 1) * om.VIS_STEPS
        self.assertAlmostEqual(len(out) // size, 2 * om.VIS_FPS, delta=2)   # 30 a second
        lv = om.spectrum_levels(out[size * 30:size * 31])
        low, high = max(lv[:om.VIS_BANDS // 3]), max(lv[-om.VIS_BANDS // 3:])
        self.assertGreater(low, high)


if __name__ == "__main__":
    unittest.main()
