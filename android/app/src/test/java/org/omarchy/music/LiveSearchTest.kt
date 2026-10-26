package org.omarchy.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveSearchTest {
    @Test fun whenToSearch() {
        assertNull(liveSearchWait("", "tidal"))
        assertNull(liveSearchWait("a ", "tidal"))   // one letter: not yet
        assertEquals(150L, liveSearchWait("jo", "local"))   // your own songs: right away
        assertEquals(450L, liveSearchWait("joji", "tidal"))   // a service: once you pause
        assertEquals(50L, liveSearchWait("https://tidal.com/browse/track/1", "local"))   // a pasted link: at once
    }
}
