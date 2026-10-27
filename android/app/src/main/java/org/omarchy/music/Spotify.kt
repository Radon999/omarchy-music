package org.omarchy.music

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/** A Spotify track (full, or an album's simplified one with its album given) as our Track. */
fun trackFromSpotify(t: JsonElement?, album: JsonElement? = null): Track? {
    if (t == null || (t["type"].str ?: "track") != "track") return null
    val id = t["id"].str ?: return null
    val al = t["album"] ?: album
    val thumb = al["images"].list.minByOrNull { Math.abs((it["width"].long ?: 300) - 300) }?.get("url").str ?: ""
    return Track(id = "sp$id", source = "spotify", spotify = id, isrc = t.path("external_ids", "isrc").str,
        title = t["name"].str ?: "Unknown",
        artist = t["artists"].list.mapNotNull { it["name"].str }.joinToString(", ").ifEmpty { "Unknown artist" },
        album = al["name"].str ?: "", duration = Math.round((t["duration_ms"].dbl ?: 0.0) / 1000).toDouble(), thumb = thumb)
}

/** Your Spotify library (liked songs, playlists, search, artists' albums) through a
 *  Spotify developer app of yours: Spotify hands its API only to those, and since 2026
 *  the app's owner needs Premium. Its audio is copy-protected, so its songs play from
 *  Tidal in FLAC, matched by ISRC. Spotify no longer gives apps like this radios or an
 *  artist's top songs: another service fills those in. The same redirect address as the
 *  desktop's, so one developer app serves both; here the sign-in page catches it. */
class Spotify(ctx: Context) : Source {
    override val key = "spotify"
    override val name = "Spotify"
    override val hint = "Connect Spotify in Settings (⚙).\nIt needs a Spotify developer app of yours."

    @Serializable
    private data class Auth(val client_id: String = "", val user: String = "", val access_token: String = "",
                            val refresh_token: String = "", val expires: Double = 0.0)

    private val file = File(ctx.filesDir, "spotify.json")
    @Volatile private var auth = readText(file)?.let { runCatching { JSON.decodeFromString(Auth.serializer(), it) }.getOrNull() } ?: Auth()
    private var verifier = ""
    private var state = ""

    val clientId get() = auth.client_id
    val user get() = auth.user
    override val signedIn get() = auth.refresh_token.isNotEmpty()

    private fun save() = writeAtomic(file, JSON.encodeToString(Auth.serializer(), auth))

    fun disconnect() { auth = Auth(client_id = clientId); save() }

    /** Remember your app's Client ID; false if it isn't one. */
    fun setClient(id: String): Boolean {
        val cid = id.trim()
        if (!Regex("[0-9a-f]{32}").matches(cid)) return false
        if (cid != clientId) { auth = Auth(client_id = cid); save() }
        return true
    }

    /** Spotify's consent page (PKCE: no secret). */
    fun loginUrl(): String {
        verifier = Base64.encodeToString(ByteArray(48).also { SecureRandom().nextBytes(it) }, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        state = randomHex(12)
        return "https://accounts.spotify.com/authorize?client_id=${enc(clientId)}&response_type=code&redirect_uri=${enc(REDIRECT)}" +
            "&state=$state&scope=${enc(SCOPES)}&code_challenge_method=S256&code_challenge=$challenge"
    }

    /** Swap the code the consent page came back with for a login (blocking). */
    fun finishLogin(redirected: String) {
        val u = Uri.parse(redirected)
        u.getQueryParameter("error")?.let { error(it) }
        if (u.getQueryParameter("state") != state) error("Spotify sent back a different sign-in")
        val code = u.getQueryParameter("code") ?: error("Spotify didn't send a login back")
        val tok = httpJson("https://accounts.spotify.com/api/token", body = form("grant_type" to "authorization_code", "code" to code,
            "redirect_uri" to REDIRECT, "client_id" to clientId, "code_verifier" to verifier))
        take(Auth(client_id = clientId), tok)
        val me = get("/me")
        auth = auth.copy(user = me["display_name"].str ?: me["id"].str ?: "")
        save()
    }

    private fun take(base: Auth, tok: JsonElement) {
        auth = base.copy(access_token = tok["access_token"].str ?: error("Spotify didn't sign you in"),
            expires = now() + (tok["expires_in"].dbl ?: 3600.0) - 60,
            refresh_token = tok["refresh_token"].str ?: base.refresh_token)   // Spotify may hand out a new one
    }

    @Synchronized private fun access(): String {
        if (auth.access_token.isNotEmpty() && auth.expires > now()) return auth.access_token
        if (!signedIn) error("Spotify isn't connected")
        take(auth, httpJson("https://accounts.spotify.com/api/token", body = form("grant_type" to "refresh_token",
            "refresh_token" to auth.refresh_token, "client_id" to clientId)))
        save()
        return auth.access_token
    }

    private fun request(path: String, params: Map<String, Any> = emptyMap(), method: String? = null): JsonElement {
        var url = if (path.startsWith("http")) path else API + path
        if (params.isNotEmpty()) url += (if ('?' in url) "&" else "?") + params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value.toString())}" }
        var attempt = 0
        while (true) {
            try {
                return httpJson(url, mapOf("Authorization" to "Bearer ${access()}"), method = method)
            } catch (e: HttpError) {
                when {
                    e.code == 429 && attempt < 2 -> Thread.sleep(2000)   // too fast
                    e.code == 401 && attempt == 0 -> auth = auth.copy(expires = 0.0)
                    else -> throw e
                }
            }
            attempt++
        }
    }

    private fun get(path: String, params: Map<String, Any> = emptyMap()) = request(path, params)

    /** Your last plays (up to 50, newest first) after a time in ms, for your Wrapped. A login
     *  from before the app asked to read them can't: connecting again lets it. */
    fun recentlyPlayed(afterMs: Long): List<JsonElement> = try {
        get("/me/player/recently-played", mapOf("limit" to 50) + if (afterMs > 0) mapOf("after" to afterMs) else emptyMap())["items"].list
    } catch (e: HttpError) {
        if (e.code == 401 || e.code == 403) error("connect Spotify again to let the app read your recent plays") else throw e
    }

    /** Every item of a list Spotify hands out a page at a time. */
    private fun pages(path: String, params: Map<String, Any> = emptyMap(), cap: Int = 10000): List<JsonElement> {
        val out = ArrayList<JsonElement>()
        var url: String? = path
        var ps = params
        while (url != null && out.size < cap) {
            val page = get(url, ps)
            ps = emptyMap()   // the next link carries them
            out += page["items"].list.filter { it.obj != null }
            url = page["next"].str
        }
        return out
    }

    override fun search(q: String): List<Track> {
        val m = Regex("open\\.spotify\\.com/(?:intl-[\\w-]+/)?(track|album|playlist)/(\\w+)").find(q)
            ?: Regex("spotify:(track|album|playlist):(\\w+)").find(q)
        if (m == null) {
            val found = ArrayList<JsonElement>()
            for (offset in listOf(0, 10, 20)) {   // ten a request for apps like this one
                val items = get("/search", mapOf("q" to q, "type" to "track", "limit" to 10, "offset" to offset)).path("tracks", "items").list
                found += items
                if (items.size < 10) break
            }
            return found.mapNotNull { trackFromSpotify(it) }
        }
        val (kind, ident) = m.destructured
        return when (kind) {
            "track" -> listOfNotNull(trackFromSpotify(get("/tracks/$ident")))
            "album" -> album(ident)
            else -> playlist(ident)
        }
    }

    override fun myPlaylists(): List<Album> = listOf(Album("liked", "Liked Songs")) +
        pages("/me/playlists", mapOf("limit" to 50)).mapNotNull { p ->
            Album(p["id"].str ?: return@mapNotNull null, p["name"].str ?: "Playlist", "",
                p["images"][0]["url"].str ?: "", (p["items"] ?: p["tracks"])["total"].long?.toString() ?: "")
        }

    override fun playlist(id: String): List<Track> {
        val items = if (id == "liked") pages("/me/tracks", mapOf("limit" to 50)) else try {
            pages("/playlists/$id/items", mapOf("limit" to 50))
        } catch (e: HttpError) {
            if (e.code == 403) throw IllegalStateException("Spotify lets apps like this open only playlists you made or collaborate on")
            if (e.code != 404) throw e
            pages("/playlists/$id/tracks", mapOf("limit" to 50))   // an app from before 2026
        }
        return items.mapNotNull { trackFromSpotify(it["item"] ?: it["track"]) }
    }

    override fun likedIds(): Set<String> = playlist("liked").map { it.id }.toSet()

    override fun rate(t: Track, like: Boolean) {
        val id = t.spotify ?: error("that song isn't on Spotify")
        val method = if (like) "PUT" else "DELETE"
        try {
            request("/me/library", mapOf("uris" to "spotify:track:$id"), method)
        } catch (e: HttpError) {
            if (e.code != 404 && e.code != 405) throw e
            request("/me/tracks", mapOf("ids" to id), method)   // an app from before 2026
        }
    }

    override fun artist(name: String): ArtistInfo? {
        val a = bestNamed(get("/search", mapOf("q" to name, "type" to "artist", "limit" to 5)).path("artists", "items").list, name) {
            it["name"].str ?: ""
        } ?: return null
        fun pic(imgs: List<JsonElement>) = (if (imgs.size > 1) imgs[imgs.size - 2] else imgs.firstOrNull())?.get("url").str ?: ""
        val albums = pages("/artists/${a["id"].str}/albums", mapOf("include_groups" to "album,single", "limit" to 10), cap = 60).mapNotNull { x ->
            Album(x["id"].str ?: return@mapNotNull null, x["name"].str ?: "Album",
                if (x["album_type"].str == "single") "Single" else (x["release_date"].str ?: "").take(4), pic(x["images"].list))
        }
        return ArtistInfo(a["name"].str ?: name, pic(a["images"].list), emptyList(), albums)
    }

    override fun album(id: String): List<Track> {
        val al = get("/albums/$id")
        val items = al.path("tracks", "items").list.toMutableList()
        al.path("tracks", "next").str?.let { items += pages(it) }
        return items.mapNotNull { trackFromSpotify(it, al) }
    }

    companion object {
        const val API = "https://api.spotify.com/v1"
        const val REDIRECT = "http://127.0.0.1:8898/callback"
        const val SCOPES = "user-library-read user-library-modify playlist-read-private playlist-read-collaborative user-read-recently-played"
    }
}
