package org.omarchy.music

import android.content.Context
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.math.abs

/** Your Tidal account: finds each song in lossless, streams and downloads it in FLAC,
 *  and works as a library (favourites, playlists, search, radio). Signing in is
 *  Tidal's browser ("PKCE") login, the only one Tidal streams lossless to. */
class Tidal(ctx: Context) : Source {
    override val key = "tidal"
    override val name = "Tidal"
    override val hint = "Connect Tidal in the accounts menu\nto see your favourites and playlists."

    @Serializable
    data class Session(val access: String, val refresh: String, val type: String = "Bearer", val expiry: Double = 0.0,
                       val session: String = "", val country: String = "US", val user: Long = 0)

    private val file = File(ctx.filesDir, "tidal.json")
    @Volatile private var s: Session? = readText(file)?.let { runCatching { JSON.decodeFromString(Session.serializer(), it) }.getOrNull() }
    val connected get() = s != null
    override val signedIn get() = connected

    private fun save() {
        val cur = s
        if (cur == null) file.delete() else writeAtomic(file, JSON.encodeToString(Session.serializer(), cur))
    }

    // ---- signing in ----
    private var verifier = ""
    private var uniqueKey = ""

    /** Step 1: the page to sign in on. The app's web view watches for [REDIRECT]. */
    fun loginUrl(): String {
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        verifier = Base64.encodeToString(raw, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        uniqueKey = randomHex(8)
        return "https://login.tidal.com/authorize?response_type=code&redirect_uri=${enc(REDIRECT)}" +
            "&client_id=${BuildConfig.TIDAL_CLIENT_ID}&lang=EN&appMode=android&client_unique_key=$uniqueKey" +
            "&code_challenge=$challenge&code_challenge_method=S256&restrict_signup=true"
    }

    /** Step 2: the address Tidal sent the web view to, with the code in it. */
    fun finishLogin(redirected: String) {
        val code = android.net.Uri.parse(redirected).getQueryParameter("code") ?: error("Tidal sent no code back")
        val tok = httpJson("https://auth.tidal.com/v1/oauth2/token", body = form(
            "code" to code, "client_id" to BuildConfig.TIDAL_CLIENT_ID, "grant_type" to "authorization_code",
            "redirect_uri" to REDIRECT, "scope" to "r_usr+w_usr+w_sub", "code_verifier" to verifier,
            "client_unique_key" to uniqueKey))
        val access = tok["access_token"].str ?: error("Tidal didn't sign you in")
        val info = httpJson("https://api.tidal.com/v1/sessions", mapOf("authorization" to "Bearer $access"))
        s = Session(access, tok["refresh_token"].str ?: "", tok["token_type"].str ?: "Bearer",
            now() + (tok["expires_in"].dbl ?: 3600.0), info["sessionId"].str ?: "", info["countryCode"].str ?: "US",
            info["userId"].long ?: 0)
        save()
    }

    fun disconnect() {
        s = null
        save()
    }

    @Synchronized
    private fun refresh() {
        val cur = s ?: return
        val tok = httpJson("https://auth.tidal.com/v1/oauth2/token", body = form(
            "grant_type" to "refresh_token", "refresh_token" to cur.refresh, "client_id" to BuildConfig.TIDAL_CLIENT_ID,
            "client_secret" to BuildConfig.TIDAL_CLIENT_SECRET))
        s = cur.copy(access = tok["access_token"].str ?: return, expiry = now() + (tok["expires_in"].dbl ?: 3600.0))
        save()
    }

    private fun api(path: String, params: Map<String, Any?> = emptyMap(), method: String = "GET", body: okhttp3.RequestBody? = null,
                    base: String = V1): JsonElement {
        val cur = s ?: error("connect Tidal in the accounts menu first")
        if (now() > cur.expiry - 60) refresh()
        val q = (mapOf("countryCode" to s!!.country, "sessionId" to s!!.session) + params).filterValues { it != null }
            .entries.joinToString("&") { "${it.key}=${enc(it.value.toString())}" }
        val url = "$base$path?$q"
        val headers = { mapOf("authorization" to "${s!!.type} ${s!!.access}", "x-tidal-client-version" to "2025.7.16") }
        return try {
            httpJson(url, headers(), body, method)
        } catch (e: HttpError) {
            if (e.code != 401) throw e
            refresh()
            httpJson(url, headers(), body, method)
        }
    }

    // ---- finding the song ----
    private fun lossless(c: JsonElement?): Boolean = c != null && c["allowStreaming"].bool == true &&
        (c["audioQuality"].str in LOSSLESS || c.path("mediaMetadata", "tags").list.any { it.str == "LOSSLESS" })
    private fun ok(c: JsonElement?, lossy: Boolean) = if (lossy) c != null && c["allowStreaming"].bool == true else lossless(c)

    private fun trackJson(id: Long) = api("tracks/$id")
    fun albumJson(id: Long) = api("albums/$id")

    /** The Tidal track (its JSON) that is this song, in lossless (in any quality: lossy), or
     *  null: Tidal's own song, else the recording with the song's ISRC, else a search. */
    fun resolve(t: Track, lossy: Boolean = false): JsonElement? {
        t.tidal?.let { id -> runCatching { trackJson(id) }.getOrNull()?.takeIf { ok(it, lossy) }?.let { return it } }
        t.isrc?.let { isrc ->
            val found = runCatching {
                api("tracks", mapOf("filter[isrc]" to isrc), base = OPENAPI).get("data").list
                    .mapNotNull { d -> d["id"].long?.let { runCatching { trackJson(it) }.getOrNull() } }.filter { ok(it, lossy) }
            }.getOrDefault(emptyList())
            if (found.isNotEmpty()) {
                // One recording is often on the single and the album: take the release the
                // service listed, and hi-res where there is a choice.
                val album = norm(t.album)
                return found.sortedWith(compareBy<JsonElement> { norm(it.path("album", "title").str) != album }
                    .thenBy { "HIRES_LOSSLESS" !in it.path("mediaMetadata", "tags").list.map { x -> x.str } }).first()
            }
        }
        return match(t, lossy)
    }

    /** The Tidal track that is this song, available in lossless (any quality: lossy), or null. */
    fun match(t: Track, lossy: Boolean = false): JsonElement? {
        // (artists, title) readings of the song: as the service has it, and for uploads
        // titled "Artist - Song", the way the title has it.
        val readings = mutableListOf(t.artist.split(",") to t.title)
        Regex("(.+?)\\s[-–]\\s(.+)").matchEntire(t.title)?.let {
            readings += it.groupValues[1].split(Regex(",|&|\\bx\\b")) to it.groupValues[2]
        }
        val queries = mutableListOf<String>()
        for ((artists, title) in readings) for (q in listOf("${artists[0].trim()} ${cleanTitle(title)}", "${artists[0].trim()} ${bareTitle(title)}"))
            if (q !in queries) queries += q
        queries += cleanTitle(t.title)
        var best: JsonElement? = null
        var bestScore = 0.0
        val seen = HashSet<Long>()
        for (q in queries) {
            for (c in api("search", mapOf("query" to q, "types" to "TRACKS", "limit" to 20)).path("tracks", "items").list) {
                val id = c["id"].long ?: continue
                if (!seen.add(id)) continue
                val score = readings.maxOf { (a, ti) -> score(t, a, ti, c, lossy) }
                if (score > bestScore) { best = c; bestScore = score }
            }
            if (bestScore >= 0.9) break
        }
        return best
    }

    /** How sure we are that c is the song, 0 (it isn't) .. ~1. */
    private fun score(t: Track, artists: List<String>, title: String, c: JsonElement, lossy: Boolean): Double {
        if (!ok(c, lossy)) return 0.0
        val dur = t.duration
        val cdur = c["duration"].dbl ?: 0.0
        if (dur > 0 && abs(cdur - dur) > 6) return 0.0
        val name = tidalTitle(c)
        val album = c.path("album", "title").str ?: ""
        if (VERSION_WORDS.any { w -> hasWord("$title ${t.album}".lowercase(), w) != hasWord("$name $album".lowercase(), w) }) return 0.0
        val ts = similar(cleanTitle(title), cleanTitle(name))
        val theirs = artistNames(c["artists"].list.mapNotNull { it["name"].str }) + featured(name)
        fun found(a: String) = theirs.maxOfOrNull { similar(a, it) } ?: 0.0
        val lead = artistNames(artists.take(1)).maxOfOrNull { found(it) } ?: 0.0
        if (ts < 0.75 || lead < 0.8) return 0.0
        val ours = artistNames(artists) + featured(title)
        val credits = ours.sumOf { found(it) } / ours.size
        val closeness = if (dur > 0) 1 - abs(cdur - dur) / 6 else 0.5
        val sameAlbum = if (t.album.isNotEmpty() && similar(t.album.lowercase(), album.lowercase()) > 0.8) 0.05 else 0.0
        return 0.55 * ts + 0.2 * lead + 0.1 * credits + 0.15 * closeness + sameAlbum
    }

    data class Stream(val uri: String, val isDash: Boolean, val quality: String, val manifest: String = "")

    /** Tidal track c as something the player plays straight from Tidal, nothing saved.
     *  CD quality comes as one FLAC file; hi-res as FLAC in MP4 segments, listed by a
     *  DASH manifest (handed to the player inline, as a data: address). lossy: AAC will do
     *  when Tidal has no FLAC of it ("AAC 320 kbps"). */
    fun stream(c: JsonElement, lossy: Boolean = false): Stream {
        val st = api("tracks/${c["id"].long}/playbackinfopostpaywall",
            mapOf("playbackmode" to "STREAM", "audioquality" to QUALITY, "assetpresentation" to "FULL"))
        val manifest = String(Base64.decode(st["manifest"].str ?: "", Base64.DEFAULT))
        val mime = st["manifestMimeType"].str ?: ""
        val flac = st["audioQuality"].str in LOSSLESS && (if ("dash" in mime) Regex("codecs=\"flac\"", RegexOption.IGNORE_CASE).containsMatchIn(manifest)
            else (runCatching { JSON.parseToJsonElement(manifest)["codecs"].str }.getOrNull() ?: "").uppercase().startsWith("FLAC"))
        if (!flac) {
            if (!lossy) throw NoLosslessMatch("Tidal only has it lossy")
            val quality = LOSSY[st["audioQuality"].str] ?: "lossy"
            if ("dash" in mime) return Stream("data:application/dash+xml;base64,${Base64.encodeToString(manifest.toByteArray(), Base64.NO_WRAP)}", true, quality, manifest)
            val bts = JSON.parseToJsonElement(manifest)
            if ((bts["encryptionType"].str ?: "NONE") != "NONE") error("Tidal sent an encrypted stream")
            return Stream(bts["urls"][0].str ?: error("Tidal sent no stream"), false, quality)
        }
        var rate = st["sampleRate"].long?.toInt() ?: 44100
        var bits = st["bitDepth"].long?.toInt() ?: 16
        val out: Stream
        if ("dash" in mime) {
            if (!Regex("codecs=\"flac\"", RegexOption.IGNORE_CASE).containsMatchIn(manifest)) throw NoLosslessMatch("Tidal only has it lossy")
            Regex("audioSamplingRate=\"(\\d+)\"").find(manifest)?.let { rate = it.groupValues[1].toInt() }
            if (bits == 16 && rate > 48000) bits = 24   // Tidal leaves the depth out of hi-res answers
            val data = Base64.encodeToString(manifest.toByteArray(), Base64.NO_WRAP)
            out = Stream("data:application/dash+xml;base64,$data", true, "", manifest)
        } else {
            val bts = JSON.parseToJsonElement(manifest)
            if (!(bts["codecs"].str ?: "").uppercase().startsWith("FLAC")) throw NoLosslessMatch("Tidal only has it lossy")
            if ((bts["encryptionType"].str ?: "NONE") != "NONE") error("Tidal sent an encrypted stream")
            out = Stream(bts["urls"][0].str ?: error("Tidal sent no stream"), false, "")
        }
        return out.copy(quality = "$bits-bit/${"%g".format(rate / 1000.0)} kHz")
    }

    fun lyrics(tidalId: Long): Pair<String?, String?> {
        val ly = api("tracks/$tidalId/lyrics")
        return ly["subtitles"].str?.ifBlank { null } to ly["lyrics"].str?.ifBlank { null }
    }

    // ---- as a library ----
    private fun paged(path: String, page: Int, item: Boolean): List<JsonElement> {
        val out = ArrayList<JsonElement>()
        while (out.size < 10000) {
            val items = api(path, mapOf("limit" to page, "offset" to out.size))["items"].list
            out += if (item) items.mapNotNull { it["item"] } else items
            if (items.size < page) break
        }
        return out
    }

    private fun tracks(items: List<JsonElement>) = items.filter { it["allowStreaming"].bool == true }.map { trackFromTidal(it) }

    override fun search(q: String): List<Track> {
        val m = Regex("tidal\\.com/(?:browse/)?(track|playlist|album|mix)/([\\w-]+)").find(q)
            ?: return tracks(api("search", mapOf("query" to q, "types" to "TRACKS", "limit" to 30)).path("tracks", "items").list)
        val (kind, id) = m.destructured
        return when (kind) {
            "playlist" -> playlist(id)
            "track" -> tracks(listOf(trackJson(id.toLong())))
            "album" -> album(id)
            else -> tracks(api("mixes/$id/items")["items"].list.mapNotNull { it["item"] }.filter { it["duration"] != null })
        }
    }

    override fun find(q: String): Found {
        if (linkSource(q) != null) return Found(search(q))
        val r = api("search", mapOf("query" to q, "types" to "TRACKS,ARTISTS,ALBUMS", "limit" to 30))
        val artists = r.path("artists", "items").list.take(6).mapNotNull { a ->
            Album("ar-${a["id"].long}", a["name"].str ?: return@mapNotNull null, "Artist", image(a["picture"].str, 320))
        }
        val albums = r.path("albums", "items").list.take(10).map { al ->
            val by = al["artists"].list.firstOrNull()?.get("name").str ?: ""
            Album(al["id"].long.toString(), al["title"].str ?: "Album",
                listOf(by, (al["releaseDate"].str ?: "").take(4)).filter { it.isNotEmpty() }.joinToString(" · "), image(al["cover"].str, 320))
        }
        return Found(tracks(r.path("tracks", "items").list), artists, albums)
    }

    override fun radio(t: Track): List<Track> {
        val id = if (t.source == "tidal") t.tidal else resolve(t)?.get("id").long
        return tracks(api("tracks/${id ?: return emptyList()}/radio", mapOf("limit" to 40))["items"].list)
    }

    override fun myPlaylists(): List<Album> {
        val uid = s?.user ?: return emptyList()
        val lists = paged("users/$uid/playlists", 50, false) + paged("users/$uid/favorites/playlists", 50, true)
        val out = mutableListOf(Album("favorites", "Favorite tracks"))
        val seen = HashSet<String>()
        for (p in lists) {
            val id = p["uuid"].str ?: continue
            if (!seen.add(id)) continue
            val n = p["numberOfTracks"].long ?: -1
            out += Album(id, p["title"].str ?: "Playlist", "", image(p["squareImage"].str ?: p["image"].str, 320),
                if (n >= 0) n.toString() else "")
        }
        return out
    }

    override fun playlist(id: String): List<Track> =
        if (id == "favorites") tracks(paged("users/${s?.user}/favorites/tracks", 50, true))
        else tracks(paged("playlists/$id/tracks", 100, false))

    override fun likedIds(): Set<String> = paged("users/${s?.user}/favorites/tracks", 50, true).map { "td${it["id"].long}" }.toSet()

    override fun rate(t: Track, like: Boolean) {
        val uid = s?.user
        if (like) api("users/$uid/favorites/tracks", method = "POST", body = form("trackIds" to t.tidal.toString()))
        else api("users/$uid/favorites/tracks/${t.tidal}", method = "DELETE")
    }

    override fun artist(name: String): ArtistInfo? {
        val found = api("search", mapOf("query" to name, "types" to "ARTISTS", "limit" to 5)).path("artists", "items").list
        val a = bestNamed(found, name) { it["name"].str ?: "" } ?: return null
        val id = a["id"].long
        val top = tracks(api("artists/$id/toptracks", mapOf("limit" to 20))["items"].list)
        val albums = api("artists/$id/albums", mapOf("limit" to 50))["items"].list +
            api("artists/$id/albums", mapOf("limit" to 50, "filter" to "EPSANDSINGLES"))["items"].list
        val seen = HashSet<Pair<String, String>>()
        val out = albums.mapNotNull { al ->
            val year = (al["releaseDate"].str ?: "").take(4)
            // Tidal lists the same album twice (explicit and clean, say): once is enough.
            if (!seen.add(norm(al["title"].str) to year)) null
            else Album(al["id"].long.toString(), al["title"].str ?: "Album", year, image(al["cover"].str, 320))
        }
        return ArtistInfo(a["name"].str ?: name, image(a["picture"].str, 320), top, out)
    }

    override fun album(id: String): List<Track> = tracks(api("albums/$id/tracks", mapOf("limit" to 100))["items"].list)

    companion object {
        const val REDIRECT = "https://tidal.com/android/login/auth"
        const val V1 = "https://api.tidal.com/v1/"
        const val OPENAPI = "https://openapi.tidal.com/v2/"
        const val QUALITY = "HI_RES_LOSSLESS"
        val LOSSLESS = setOf("LOSSLESS", "HI_RES", "HI_RES_LOSSLESS")
        val LOSSY = mapOf("HIGH" to "AAC 320 kbps", "LOW" to "AAC 96 kbps")
        // Words that make a different recording: a match must agree on each of them.
        val VERSION_WORDS = listOf("live", "remix", "acoustic", "instrumental", "karaoke", "sped up", "slowed", "nightcore",
            "demo", "unplugged", "orchestral", "a cappella", "acapella", "radio edit", "extended")

        fun image(uuid: String?, px: Int) =
            if (uuid.isNullOrEmpty()) "" else "https://resources.tidal.com/images/${uuid.replace('-', '/')}/${px}x$px.jpg"

        fun tidalTitle(c: JsonElement): String {
            val v = c["version"].str
            val t = c["title"].str ?: ""
            return if (!v.isNullOrBlank() && v != "null") "$t ($v)" else t
        }

        fun trackFromTidal(c: JsonElement): Track {
            val id = c["id"].long ?: 0
            return Track(id = "td$id", source = "tidal", tidal = id, isrc = c["isrc"].str, title = tidalTitle(c),
                artist = c["artists"].list.mapNotNull { it["name"].str }.joinToString(", ").ifEmpty { "Unknown artist" },
                album = c.path("album", "title").str ?: "", duration = c["duration"].dbl ?: 0.0,
                thumb = image(c.path("album", "cover").str, 320))
        }
    }
}
