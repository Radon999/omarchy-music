package org.omarchy.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumTest {
    @Test fun aLowToneLightsTheLowBars() {
        val n = 512   // bins
        val rate = 44100
        val fft = ByteArray(2 * n)
        val bin = (100.0 / (rate / 2.0 / n)).toInt()   // 100 Hz
        fft[2 * bin] = 120; fft[2 * bin + 1] = 30
        val bands = Spectrum.bands(fft, rate)
        assertEquals(Spectrum.BANDS, bands.size)
        val low = bands.take(Spectrum.BANDS / 3).max()
        val high = bands.takeLast(Spectrum.BANDS / 3).max()
        assertTrue(low > 0.9f)
        assertEquals(0f, high, 0.01f)
    }
}
