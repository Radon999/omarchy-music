package org.omarchy.music

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A song, the way the desktop app keeps one (same JSON keys), from any service or a file. */
@Serializable
data class Track(
    val id: String,
    val source: String = "ytm",
    val title: String = "Unknown",
    val artist: String = "Unknown artist",
    val album: String = "",
    val duration: Double = 0.0,
    val thumb: String = "",
    val isrc: String? = null,
    val tidal: Long? = null,
    val apple: String? = null,
    val spotify: String? = null,
    val file: String? = null,          // a song on the phone: its content:// address
    val queued: Boolean = false,       // waiting in your queue (you put it there)
    val added: Double = 0.0,           // when it came into your music
    val plays: Int = 0,                // Most played: how often
    val dj: Int = 0,                   // the DJ's set it is in (0: none)
) {
    /** As a playlist or the play history keeps it: enough to show and play it again. */
    fun slim() = copy(queued = false, plays = 0, added = 0.0, dj = 0)
    val isFile get() = source == "file"
}

/** An album on an artist's page, or a playlist of a service. */
@Serializable
data class Album(val id: String, val title: String, val sub: String = "", val thumb: String = "", val count: String = "")

/** What a search found: songs, and the artists and albums whose names you typed. Albums of
 *  your own songs carry their songs in albumTracks (by the album's id). */
data class Found(val songs: List<Track>, val artists: List<Album> = emptyList(), val albums: List<Album> = emptyList(),
                 val albumTracks: Map<String, List<Track>> = emptyMap()) {
    val isEmpty get() = songs.isEmpty() && artists.isEmpty() && albums.isEmpty()
}

/** An artist's page: their top songs and their albums, from whichever service knew them. */
data class ArtistInfo(val name: String, val thumb: String, val top: List<Track>, val albums: List<Album>,
                      val source: String = "", val topSource: String = "")

val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false; isLenient = true }

class NoLosslessMatch(msg: String) : RuntimeException(msg)
