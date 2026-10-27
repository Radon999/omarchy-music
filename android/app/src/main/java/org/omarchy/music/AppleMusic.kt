package org.omarchy.music

import android.content.Context
import android.util.Base64
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File

const val APPLE_API = "https://amp-api.music.apple.com"

private fun jwtExp(token: String): Long = runCatching {
    val part = token.split(".")[1]
    val claims = JSON.parseToJsonElement(String(Base64.decode(part, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)))
    if (claims["iss"].str == null) 0L else claims["exp"].long ?: 0L
}.getOrDefault(0L)

/** The token music.apple.com's player sends to Apple's API. It is in the player's
 *  script and lasts a couple of months; kept until it expires. */
fun appleWebToken(ctx: Context, refresh: Boolean = false): String {
    val file = File(ctx.filesDir, "apple-token.json")
    val saved = readText(file)?.let { runCatching { JSON.parseToJsonElement(it) }.getOrNull() }
    val tok = saved["token"].str
    if (!refresh && tok != null && (saved["exp"].long ?: 0) > now() + 86400) return tok
    val page = http("https://music.apple.com/us/browse", mapOf("User-Agent" to DESKTOP_UA))
    for (path in Regex("(/assets/[^\"'\\s]+\\.js)").findAll(page).map { it.value }.distinct()) {
        val script = runCatching { http("https://music.apple.com$path", mapOf("User-Agent" to DESKTOP_UA)) }.getOrNull() ?: continue
        for (m in Regex("eyJ[\\w-]{10,}\\.[\\w-]{10,}\\.[\\w-]{10,}").findAll(script)) {
            val exp = jwtExp(m.value)
            if (exp > now()) {
                writeAtomic(file, buildJsonObject { put("token", m.value); put("exp", exp) }.toString())
                return m.value
            }
        }
    }
    error("couldn't find Apple Music's web token")
}

fun appleArt(url: String?, px: Int) =
    (url ?: "").replace("{w}", "$px").replace("{h}", "$px").replace("{f}", "jpg").replace("{c}", "bb")

/** An Apple Music song, from the catalogue or your library, as our Track. */
fun trackFromApple(res: JsonElement): Track {
    val a = res["attributes"]
    val cat = res.path("relationships", "catalog", "data", 0)
    val cid = a.path("playParams", "catalogId").str ?: cat["id"].str ?: res["id"].str?.takeIf { res["type"].str == "songs" }
    return Track(id = "am${cid ?: res["id"].str}", source = "apple", apple = cid,
        isrc = a["isrc"].str ?: cat.path("attributes", "isrc").str,
        title = a["name"].str ?: "Unknown", artist = a["artistName"].str ?: "Unknown artist",
        album = a["albumName"].str ?: "", duration = Math.round((a["durationInMillis"].dbl ?: 0.0) / 1000).toDouble(),
        thumb = appleArt((a["artwork"] ?: cat.path("attributes", "artwork"))["url"].str, 226))
}

/** Your Apple Music library: playlists, favourites and search, signed in with the cookie
 *  of the app's music.apple.com sign-in page. Without it, the catalogue still searches.
 *  Its songs play from their Tidal match (by ISRC), in FLAC. */
class AppleMusic(private val ctx: Context, cookie: String?) : Source {
    override val key = "apple"
    override val name = "Apple Music"
    override val hint = "Sign in to Apple Music in Settings (⚙)\nto see your playlists and favourites."
    private var token = appleWebToken(ctx)
    private var userToken = cookie?.let { Regex("(?:^|;\\s*)media-user-token=([^;]+)").find(it)?.groupValues?.get(1) }
    private var storefront = "us"
    override var signedIn = false
        private set

    init {
        if (userToken != null) runCatching {
            storefront = get("/v1/me/storefront")["data"][0]["id"].str ?: "us"
            signedIn = true
        }.onFailure { userToken = null }   // an expired login: the catalogue only
    }

    private fun headers(): Map<String, String> = buildMap {
        put("Authorization", "Bearer $token"); put("Origin", "https://music.apple.com"); put("Referer", "https://music.apple.com/")
        put("User-Agent", DESKTOP_UA)
        userToken?.let { put("media-user-token", it) }
    }

    private fun request(path: String, params: Map<String, Any> = emptyMap(), method: String? = null, body: JsonElement? = null): JsonElement {
        var url = if (path.startsWith("http")) path else APPLE_API + path
        if (params.isNotEmpty()) url += (if ('?' in url) "&" else "?") + params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value.toString())}" }
        return try {
            httpJson(url, headers(), body?.let { jsonBody(it) }, method)
        } catch (e: HttpError) {
            if (e.code != 401) throw e
            token = appleWebToken(ctx, refresh = true)   // Apple moved to a new token
            httpJson(url, headers(), body?.let { jsonBody(it) }, method)
        }
    }

    private fun get(path: String, params: Map<String, Any> = emptyMap()) = request(path, params)

    /** Every item of a list Apple hands out a page at a time. */
    private fun pages(path: String, params: Map<String, Any> = emptyMap(), cap: Int = 5000): List<JsonElement> {
        val out = ArrayList<JsonElement>()
        var url: String? = path
        var ps = params
        while (url != null && out.size < cap) {
            val page = get(url, ps)
            out += page["data"].list
            url = page["next"].str
            ps = ps - "offset"   // the next link has its own
        }
        return out
    }

    override fun search(q: String): List<Track> {
        val m = Regex("music\\.apple\\.com/(?:(\\w\\w)/)?(song|album|playlist)/(?:[^/?#]+/)?([\\w.-]+)(?:\\?i=(\\d+))?").find(q)
            ?: return get("/v1/catalog/$storefront/search", mapOf("term" to q, "types" to "songs", "limit" to 25))
                .path("results", "songs", "data").list.map { trackFromApple(it) }
        val (sfRaw, kind, ident, song) = m.destructured
        val sf = sfRaw.ifEmpty { storefront }
        if (song.isNotEmpty() || kind == "song") return get("/v1/catalog/$sf/songs/${song.ifEmpty { ident }}")["data"].list.map { trackFromApple(it) }
        if (kind == "album") return fillIsrcs(pages("/v1/catalog/$sf/albums/$ident/tracks").map { trackFromApple(it) })
        return playlist(ident)
    }

    // Apple's stations stream only to its own players: another service fills radios in.

    override fun myPlaylists(): List<Album> = pages("/v1/me/library/playlists", mapOf("limit" to 100)).mapNotNull { p ->
        val a = p["attributes"]
        Album(p["id"].str ?: return@mapNotNull null, a["name"].str ?: "Playlist", "", appleArt(a.path("artwork", "url").str, 226))
    }

    override fun playlist(id: String): List<Track> {
        val items = if (id.startsWith("p.")) pages("/v1/me/library/playlists/$id/tracks", mapOf("limit" to 100, "include" to "catalog"))
            else pages("/v1/catalog/$storefront/playlists/$id/tracks", mapOf("limit" to 100))   // a public one, from a link
        return fillIsrcs(items.map { trackFromApple(it) })
    }

    /** Library songs often come without their ISRC: ask the catalogue, 100 at a time. */
    private fun fillIsrcs(tracks: List<Track>): List<Track> {
        val need = tracks.filter { it.apple != null && it.isrc == null }
        if (need.isEmpty()) return tracks
        val isrcs = HashMap<String, String>()
        for (chunk in need.chunked(100)) runCatching {
            get("/v1/catalog/$storefront/songs", mapOf("ids" to chunk.joinToString(",") { it.apple!! }))["data"].list.forEach { s ->
                val id = s["id"].str; val isrc = s.path("attributes", "isrc").str
                if (id != null && isrc != null) isrcs[id] = isrc
            }
        }   // the rest match by name instead
        return tracks.map { t -> if (t.isrc == null && t.apple != null) t.copy(isrc = isrcs[t.apple]) else t }
    }

    /** Apple Music keeps the songs you favourite in a playlist of their own. */
    override fun likedIds(): Set<String> = myPlaylists().firstOrNull { norm(it.title) in setOf("favorite songs", "favourite songs") }
        ?.let { p -> playlist(p.id).map { it.id }.toSet() } ?: emptySet()

    override fun rate(t: Track, like: Boolean) {
        val id = t.apple ?: error("that song isn't in Apple's catalogue")
        if (like) request("/v1/me/ratings/songs/$id", method = "PUT", body = buildJsonObject {
            put("type", "rating"); putJsonObject("attributes") { put("value", 1) }
        }) else request("/v1/me/ratings/songs/$id", method = "DELETE")
    }

    override fun artist(name: String): ArtistInfo? {
        val res = get("/v1/catalog/$storefront/search", mapOf("term" to name, "types" to "artists", "limit" to 5))
        val a = bestNamed(res.path("results", "artists", "data").list, name) { it.path("attributes", "name").str ?: "" } ?: return null
        val base = "/v1/catalog/$storefront/artists/${a["id"].str}"
        val top = get("$base/view/top-songs", mapOf("limit" to 20))["data"].list.map { trackFromApple(it) }
        val albums = pages("$base/albums", mapOf("limit" to 25), cap = 100).mapNotNull { x ->
            val at = x["attributes"]
            Album(x["id"].str ?: return@mapNotNull null, at["name"].str ?: "Album",
                if (at["isSingle"].bool == true) "Single" else (at["releaseDate"].str ?: "").take(4), appleArt(at.path("artwork", "url").str, 226))
        }
        val at = a["attributes"]
        return ArtistInfo(at["name"].str ?: name, appleArt(at.path("artwork", "url").str, 320), top, albums)
    }

    override fun album(id: String): List<Track> = fillIsrcs(pages("/v1/catalog/$storefront/albums/$id/tracks").map { trackFromApple(it) })

    /** Apple's catalogue entry for the recording with this ISRC, with its album, or null (for the tagger). */
    fun catalogSong(isrc: String, album: String = ""): JsonElement? =
        get("/v1/catalog/$storefront/songs", mapOf("filter[isrc]" to isrc, "include" to "albums"))["data"].list
            .sortedBy { norm(it.path("attributes", "albumName").str) != norm(album) }.firstOrNull()

}
