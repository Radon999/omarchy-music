"""Auto EQ: AutoEq's index and profiles, and telling which headphones are on."""
import unittest

from app import load

om = load()

INDEX = """# Index
- [Sony WH-1000XM6](./Super%20Review/over-ear/Sony%20WH-1000XM6) by Super Review
- [Sony WH-1000XM6 (analog cable)](./Super%20Review/over-ear/Sony%20WH-1000XM6%20(analog%20cable)) by Super Review
- [Sony WH-1000XM5](./oratory1990/over-ear/Sony%20WH-1000XM5) by oratory1990
- [Apple AirPods Pro 2](./crinacle/711%20in-ear/Apple%20AirPods%20Pro%202) by crinacle on 711
"""
PARAMETRIC = """Preamp: -6.7 dB
Filter 1: ON LSC Fc 105 Hz Gain -1.5 dB Q 0.70
Filter 2: ON PK Fc 161 Hz Gain -7.5 dB Q 0.69
Filter 6: ON HSC Fc 10000 Hz Gain -1.5 dB Q 0.70
"""


class AutoEqTest(unittest.TestCase):
    def setUp(self):
        self.entries = om.autoeq_index_entries(INDEX)

    def test_reads_the_index(self):
        self.assertEqual(len(self.entries), 4)
        self.assertEqual(self.entries[3], {"name": "Apple AirPods Pro 2", "path": "crinacle/711%20in-ear/Apple%20AirPods%20Pro%202",
                                           "source": "crinacle"})

    def test_finds_headphones_by_name(self):
        names = [e["name"] for e in om.autoeq_search(self.entries, "xm6")]
        self.assertEqual(names, ["Sony WH-1000XM6", "Sony WH-1000XM6 (analog cable)"])
        self.assertEqual(om.autoeq_search(self.entries, "  "), [])

    def test_reads_a_profile_as_the_filters_the_eq_uses(self):
        self.assertEqual(om.parse_parametric(PARAMETRIC), [("ls", 105, -1.5, 0.7), ("pk", 161, -7.5, 0.69), ("hs", 10000, -1.5, 0.7)])
        self.assertEqual(om.parse_parametric(PARAMETRIC)[:2], [f[:4] for f in om.HEADPHONES["xm6-cable"][1][:2]])
        with self.assertRaises(ValueError):
            om.parse_parametric("404: Not Found")

    def test_a_graphic_eq_becomes_ten_bands(self):
        filters = om.graphic_to_filters("GraphicEQ: 20 -2.0; 1000 4.0; 20000 -8.0")
        self.assertEqual(len(filters), 10)
        self.assertEqual(filters[5], ("pk", 1000, 4.0, om.EQ_Q))
        self.assertTrue(-2.0 < filters[0][2] < 0)

    def test_knows_the_headphones_from_the_output_name(self):
        self.assertEqual(om.match_device(["WH-1000XM6"], self.entries)["name"], "Sony WH-1000XM6")   # Bluetooth's name
        self.assertIsNone(om.match_device(["AirPods"], self.entries))   # not enough to go on
        self.assertEqual(om.match_device(["Sony WH-1000XM6"], self.entries)["name"], "Sony WH-1000XM6")
        self.assertEqual(om.match_device(["Headphones (Apple AirPods Pro 2 Stereo)"], self.entries)["name"], "Apple AirPods Pro 2")
        self.assertIsNone(om.match_device(["Elgato Wave 3 Analog Stereo"], self.entries))


if __name__ == "__main__":
    unittest.main()
