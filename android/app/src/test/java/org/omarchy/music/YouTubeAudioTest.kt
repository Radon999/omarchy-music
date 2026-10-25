package org.omarchy.music

import org.junit.Assert.assertNotNull
import org.junit.Test

/** Needs the network: YouTube still gives its audio to NewPipe's extractor. */
class YouTubeAudioTest {
    @Test
    fun findsAudio() {
        val got = YouTubeAudio.audio("dQw4w9WgXcQ")
        println("YouTube audio: ${got?.second} ${got?.first?.take(60)}")
        assertNotNull(got)
    }
}
