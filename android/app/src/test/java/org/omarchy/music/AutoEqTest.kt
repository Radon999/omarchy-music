package org.omarchy.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoEqTest {
    private val index = """# Index
- [Sony WH-1000XM6](./Super%20Review/over-ear/Sony%20WH-1000XM6) by Super Review
- [Sony WH-1000XM6 (analog cable)](./Super%20Review/over-ear/Sony%20WH-1000XM6%20(analog%20cable)) by Super Review
- [Apple AirPods Pro 2](./crinacle/711%20in-ear/Apple%20AirPods%20Pro%202) by crinacle on 711
"""
    private val entries = AutoEq.indexEntries(index)

    @Test fun readsTheIndexAndFinds() {
        assertEquals(3, entries.size)
        assertEquals(AutoEq.Entry("Apple AirPods Pro 2", "crinacle", "crinacle/711%20in-ear/Apple%20AirPods%20Pro%202"), entries[2])
        assertEquals(listOf("Sony WH-1000XM6", "Sony WH-1000XM6 (analog cable)"), AutoEq.search(entries, "xm6").map { it.name })
    }

    @Test fun readsAProfileLikeTheBuiltInOne() {
        val f = AutoEq.parseParametric("Preamp: -6.7 dB\nFilter 1: ON LSC Fc 105 Hz Gain -1.5 dB Q 0.70\nFilter 2: ON PK Fc 161 Hz Gain -7.5 dB Q 0.69\n")
        assertEquals(Eq.HEADPHONES["xm6-cable"]!!.second.take(2), f)
        val g = AutoEq.graphicToFilters("GraphicEQ: 20 -2.0; 1000 4.0; 20000 -8.0")
        assertEquals(Filter("pk", 1000.0, 4.0, Eq.Q), g[5])
    }

    @Test fun knowsTheHeadphonesFromTheirName() {
        assertEquals("Sony WH-1000XM6", AutoEq.matchDevice(listOf("WH-1000XM6"), entries)?.name)   // Bluetooth's name
        assertEquals("Apple AirPods Pro 2", AutoEq.matchDevice(listOf("Apple AirPods Pro 2"), entries)?.name)
        assertNull(AutoEq.matchDevice(listOf("AirPods"), entries))
    }

    @Test fun keepsAProfile() {
        val f = Eq.HEADPHONES["xm6-cable"]!!.second
        val s = EqSettings(profiles = mapOf("ae:x" to AutoEq.profileOf("X", f)))
        assertEquals(f, Eq.headphones(s)["ae:x"]!!.second)
    }
}
