package org.omarchy.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Both kinds of Spotify download read the same way; short plays, podcasts and videos left out. */
class SpotifyHistoryTest {
    @Test
    fun readsTheExtendedHistory() {
        val json = """[
          {"ts":"2021-03-04T20:15:30Z","platform":"android","ms_played":201000,"conn_country":"US",
           "master_metadata_track_name":"Bang!","master_metadata_album_artist_name":"AJR",
           "master_metadata_album_album_name":"OK ORCHESTRA","spotify_track_uri":"spotify:track:x","episode_name":null,
           "reason_start":"trackdone","shuffle":false,"skipped":null,"offline":false,"incognito_mode":false},
          {"ts":"2021-03-04T20:16:00Z","ms_played":12000,"master_metadata_track_name":"Skipped","master_metadata_album_artist_name":"AJR"},
          {"ts":"2021-03-04T21:00:00Z","ms_played":1800000,"master_metadata_track_name":null,"episode_name":"A podcast"}
        ]"""
        val out = ArrayList<SpotifyHistory.Play>()
        SpotifyHistory.parse(json.byteInputStream(), out)
        assertEquals(1, out.size)
        val p = out[0]
        assertEquals("Bang!", p.title); assertEquals("AJR", p.artist); assertEquals("OK ORCHESTRA", p.album)
        assertEquals(1614888930L - 201, p.t)   // it started when it stopped, less how long it played
        assertEquals(201000L, p.ms)
    }

    @Test
    fun readsThePastYear() {
        val json = """[{"endTime":"2024-01-31 18:02","artistName":"Wallows","trackName":"Are You Bored Yet?","msPlayed":178000},
                       {"endTime":"2024-01-31 18:03","artistName":"Unknown Artist","trackName":"Unknown Track","msPlayed":60000}]"""
        val out = ArrayList<SpotifyHistory.Play>()
        SpotifyHistory.parse(json.byteInputStream(), out)
        assertEquals(listOf("Are You Bored Yet?"), out.map { it.title })
        assertEquals(1706724120L - 178, out[0].t)
    }

    @Test
    fun picksTheHistoryFiles() {
        assertTrue(SpotifyHistory.wanted("Spotify Extended Streaming History/Streaming_History_Audio_2019-2021_0.json"))
        assertTrue(SpotifyHistory.wanted("MyData/StreamingHistory_music_0.json"))
        assertTrue(SpotifyHistory.wanted("MyData/StreamingHistory0.json"))
        assertFalse(SpotifyHistory.wanted("MyData/StreamingHistory_podcast_0.json"))
        assertFalse(SpotifyHistory.wanted("Spotify Extended Streaming History/Streaming_History_Video_2021.json"))
        assertFalse(SpotifyHistory.wanted("MyData/Userdata.json"))
    }
}
