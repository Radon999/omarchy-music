package org.omarchy.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The byte ranges the desktop's player asks the phone for while it seeks. */
class PhoneShareTest {
    @Test
    fun ranges() {
        val size = 1000L
        assertEquals(0L to 999L, PhoneShare.span("bytes=0-", size))
        assertEquals(100L to 199L, PhoneShare.span("bytes=100-199", size))
        assertEquals(900L to 999L, PhoneShare.span("bytes=900-5000", size))
        assertEquals(500L to 999L, PhoneShare.span("bytes=-500", size))
        assertEquals(0L to 999L, PhoneShare.span("bytes=-5000", size))
        assertNull(PhoneShare.span("bytes=1000-", size))
        assertNull(PhoneShare.span("bytes=200-100", size))
        assertNull(PhoneShare.span("bytes=-", size))
        assertNull(PhoneShare.span("items=0-1", size))
    }
}
