package org.omarchy.music

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** YouTube Music through the API its own web player uses (what ytmusicapi speaks), signed
 *  in with the cookies of the app's sign-in page. Its songs play from Tidal in FLAC. The
 *  answers are read by looking for the song and playlist entries wherever they sit, so a
 *  reshuffle of YouTube's layout doesn't lose them. */
class YouTubeMusic(private val cookie: String?) : Source {
    override val key = "ytm"
    override val name = "YouTube Music"
    override val hint = "Sign in to YouTube Music in Settings (⚙)\nto see your playlists and liked songs."
    private val sapisid = cookie?.let { Regex("(?:^|;\\s*)__Secure-3PAPISID=([^;]+)").find(it)?.groupValues?.get(1) }
    override val signedIn get() = sapisid != null

    private fun call(endpoint: String, body: JsonObject, extra: String = ""): JsonElement {
        val version = "1." + SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date()) + ".01.00"
        val payload = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") { put("clientName", "WEB_REMIX"); put("clientVersion", version); put("hl", "en"); put("gl", "US") }
                putJsonObject("user") {}
            }
            body.forEach { (k, v) -> put(k, v) }
        }
        val headers = mutableMapOf("User-Agent" to DESKTOP_UA, "Origin" to ORIGIN, "X-Origin" to ORIGIN, "Referer" to "$ORIGIN/",
            "Content-Type" to "application/json")
        if (sapisid != null && cookie != null) {
            val ts = System.currentTimeMillis() / 1000
            val sha = MessageDigest.getInstance("SHA-1").digest("$ts $sapisid $ORIGIN".toByteArray()).joinToString("") { "%02x".format(it) }
            headers += mapOf("Cookie" to cookie, "Authorization" to "SAPISIDHASH ${ts}_$sha", "X-Goog-AuthUser" to "0")
        }
        return httpJson("$ORIGIN/youtubei/v1/$endpoint?alt=json&prettyPrint=false$extra", headers, jsonBody(payload))
    }

    private fun browse(id: String, params: String? = null) = call("browse", buildJsonObject {
        put("browseId", id); if (params != null) put("params", params)
    })

    /** Every page of a browse: its continuations followed (playlists of over 100 songs). */
    private fun browseAll(id: String, limit: Int = 5000): List<JsonElement> {
        val pages = mutableListOf(browse(id))
        var token = continuation(pages.last())
        val seen = HashSet<String>()
        while (token != null && seen.add(token) && pages.size < limit / 50) {
            pages += call("browse", buildJsonObject { put("continuation", token!!) })
            token = continuation(pages.last())
        }
        return pages
    }

    // ---- reading the answers ----
    private fun walk(e: JsonElement?, key: String, out: MutableList<JsonElement>) {
        when (e) {
            is JsonObject -> for ((k, v) in e) if (k == key) out += v else walk(v, key, out)
            is JsonArray -> e.forEach { walk(it, key, out) }
            else -> {}
        }
    }

    private fun find(e: JsonElement?, key: String) = mutableListOf<JsonElement>().also { walk(e, key, it) }

    private fun continuation(e: JsonElement): String? =
        find(e, "continuationCommand").firstNotNullOfOrNull { it["token"].str }
            ?: find(e, "nextContinuationData").firstNotNullOfOrNull { it["continuation"].str }

    private fun runs(e: JsonElement?) = e["runs"].list
    private fun text(e: JsonElement?) = runs(e).joinToString("") { it["text"].str ?: "" }.ifEmpty { e["simpleText"].str ?: "" }
    private fun thumb(e: JsonElement?) = find(e, "thumbnails").firstOrNull().list.lastOrNull()?.get("url").str ?: ""
    private fun pageType(run: JsonElement) = run.path("navigationEndpoint", "browseEndpoint", "browseEndpointContextSupportedConfigs",
        "browseEndpointContextMusicConfig", "pageType").str ?: ""

    private fun seconds(s: String): Double {
        if (!Regex("^\\d+(:\\d+)+$").matches(s.trim())) return 0.0
        return s.trim().split(":").fold(0.0) { acc, p -> acc * 60 + p.toInt() }
    }

    /** (artists, album) from a line of runs: "Artist, Artist • Album • 3:12". */
    private fun credits(rs: List<JsonElement>): Triple<String, String, Double> {
        val artists = rs.filter { pageType(it) == "MUSIC_PAGE_TYPE_ARTIST" || pageType(it) == "MUSIC_PAGE_TYPE_USER_CHANNEL" }
            .mapNotNull { it["text"].str }
        val album = rs.firstOrNull { pageType(it) == "MUSIC_PAGE_TYPE_ALBUM" }?.get("text").str ?: ""
        val dur = rs.mapNotNull { it["text"].str }.map { seconds(it) }.firstOrNull { it > 0 } ?: 0.0
        val plain = if (artists.isEmpty()) {
            // Not linked (uploads, some album pages): the first part, before " • ".
            rs.mapNotNull { it["text"].str }.joinToString("").split(" • ").map { it.trim() }
                .firstOrNull { it.isNotEmpty() && it !in setOf("Song", "Video", "Single", "EP", "Album") && seconds(it) == 0.0 } ?: ""
        } else artists.joinToString(", ")
        return Triple(plain, album, dur)
    }

    private fun fromListItem(r: JsonElement, fallbackArtist: String = "", fallbackThumb: String = ""): Track? {
        val cols = r["flexColumns"].list.map { it["musicResponsiveListItemFlexColumnRenderer"]["text"] }
        val vid = r.path("playlistItemData", "videoId").str
            ?: find(r["overlay"], "watchEndpoint").firstNotNullOfOrNull { it["videoId"].str }
            ?: runs(cols.firstOrNull()).firstNotNullOfOrNull { it.path("navigationEndpoint", "watchEndpoint", "videoId").str }
            ?: return null
        val title = runs(cols.firstOrNull()).firstOrNull()?.get("text").str ?: return null
        val (artist, album, d) = credits(cols.drop(1).flatMap { runs(it) })
        val fixed = r["fixedColumns"].list.firstNotNullOfOrNull { seconds(text(it["musicResponsiveListItemFixedColumnRenderer"]["text"])).takeIf { s -> s > 0 } }
        return Track(id = vid, source = "ytm", title = title, artist = artist.ifEmpty { fallbackArtist.ifEmpty { "Unknown artist" } },
            album = album, duration = fixed ?: d, thumb = thumb(r["thumbnail"]).ifEmpty { fallbackThumb })
    }

    private fun fromPanel(r: JsonElement): Track? {
        val vid = r["videoId"].str ?: return null
        val (artist, album, _) = credits(runs(r["longBylineText"]))
        return Track(id = vid, source = "ytm", title = text(r["title"]).ifEmpty { "Unknown" }, artist = artist.ifEmpty { "Unknown artist" },
            album = album, duration = seconds(text(r["lengthText"])), thumb = thumb(r["thumbnail"]))
    }

    private fun songs(e: JsonElement?, fallbackArtist: String = "", fallbackThumb: String = ""): List<Track> {
        val seen = HashSet<String>()
        return (find(e, "musicResponsiveListItemRenderer").mapNotNull { fromListItem(it, fallbackArtist, fallbackThumb) } +
            find(e, "playlistPanelVideoRenderer").mapNotNull { fromPanel(it) }).filter { seen.add(it.id) }
    }

    private fun twoRow(r: JsonElement): Album? {
        val id = r.path("navigationEndpoint", "browseEndpoint", "browseId").str ?: return null
        return Album(id, text(r["title"]).ifEmpty { "Playlist" }, text(r["subtitle"]), thumb(r["thumbnailRenderer"]))
    }

    // ---- as a library ----
    override fun search(q: String): List<Track> {
        Regex("[?&]list=([\\w-]+)").find(q)?.let { return playlist(it.groupValues[1]) }
        Regex("(?:v=|youtu\\.be/)([\\w-]{11})").find(q)?.let { return radioOf(it.groupValues[1]).take(1) }
        return songs(call("search", buildJsonObject { put("query", q); put("params", SONGS) })).take(30)
    }

    override fun find(q: String): Found {
        if (linkSource(q) != null) return Found(search(q))
        fun rows(params: String) = find(call("search", buildJsonObject { put("query", q); put("params", params) }), "musicResponsiveListItemRenderer")
        fun cols(r: JsonElement) = r["flexColumns"].list.map { it["musicResponsiveListItemFlexColumnRenderer"]["text"] }
        val artists = rows(ARTISTS).mapNotNull { r ->
            r.path("navigationEndpoint", "browseEndpoint", "browseId").str ?: return@mapNotNull null
            val name = runs(cols(r).firstOrNull()).firstOrNull()?.get("text").str ?: return@mapNotNull null
            Album("ar-" + norm(name), name, "Artist", thumb(r["thumbnail"]))
        }.take(6)
        // An album's line: "Album • Artist • 2019".
        val albums = rows(ALBUMS).mapNotNull { r ->
            val id = r.path("navigationEndpoint", "browseEndpoint", "browseId").str ?: return@mapNotNull null
            val c = cols(r)
            val rs = c.drop(1).flatMap { runs(it) }
            val by = rs.filter { pageType(it) == "MUSIC_PAGE_TYPE_ARTIST" }.mapNotNull { it["text"].str }.joinToString(", ")
            val year = Regex("\\b(19|20)\\d\\d\\b").find(rs.joinToString("") { it["text"].str ?: "" })?.value ?: ""
            Album(id, text(c.firstOrNull()).ifEmpty { "Album" }, listOf(by, year).filter { it.isNotEmpty() }.joinToString(" · "), thumb(r["thumbnail"]))
        }.take(10)
        return Found(search(q), artists, albums)
    }

    override fun mixFor(q: String): List<Track> {
        val lists = find(call("search", buildJsonObject { put("query", q); put("params", PLAYLISTS) }), "musicResponsiveListItemRenderer")
            .mapNotNull { it.path("navigationEndpoint", "browseEndpoint", "browseId").str }
        for (id in lists.take(3)) {
            val found = songs(browse("VL${id.removePrefix("VL")}")).take(80)
            if (found.size >= 8) return found
        }
        return emptyList()
    }

    private fun radioOf(vid: String): List<Track> = songs(call("next", buildJsonObject {
        put("videoId", vid); put("playlistId", "RDAMVM$vid"); put("isAudioOnly", true)
        put("enablePersistentPlaylistPanel", true); put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL")
    })).take(40)

    /** A radio of songs like t (a song from any service). */
    override fun radio(t: Track): List<Track> {
        val vid = if (t.source == "ytm") t.id else search("${t.artist.split(",")[0]} ${bareTitle(t.title)}").firstOrNull()?.id ?: return emptyList()
        return radioOf(vid)
    }

    override fun myPlaylists(): List<Album> = find(browse("FEmusic_liked_playlists"), "musicTwoRowItemRenderer")
        .mapNotNull { twoRow(it) }.filter { it.id.startsWith("VL") }
        .map { it.copy(id = it.id.removePrefix("VL"), count = Regex("(\\d[\\d,]*) (?:songs|tracks)").find(it.sub)?.groupValues?.get(1)?.replace(",", "") ?: "", sub = "") }

    override fun playlist(id: String): List<Track> = browseAll("VL${id.removePrefix("VL")}").flatMap { songs(it) }.distinctBy { it.id }

    override fun likedIds(): Set<String> = playlist("LM").map { it.id }.toSet()

    override fun rate(t: Track, like: Boolean) {
        call(if (like) "like/like" else "like/removelike", buildJsonObject { putJsonObject("target") { put("videoId", t.id) } })
    }

    override fun artist(name: String): ArtistInfo? {
        val found = find(call("search", buildJsonObject { put("query", name); put("params", ARTISTS) }), "musicResponsiveListItemRenderer")
            .mapNotNull { r ->
                val id = r.path("navigationEndpoint", "browseEndpoint", "browseId").str ?: return@mapNotNull null
                id to (runs(r["flexColumns"][0]["musicResponsiveListItemFlexColumnRenderer"]["text"]).firstOrNull()?.get("text").str ?: "")
            }
        val (bid, _) = bestNamed(found, name) { it.second } ?: return null
        val page = browse(bid)
        val header = page["header"]
        val artistName = text(find(header, "title").firstOrNull()).ifEmpty { name }
        val shelves = find(page, "musicShelfRenderer")
        val songShelf = shelves.firstOrNull { text(it["title"]).contains("Songs", true) } ?: shelves.firstOrNull()
        val allSongs = songShelf?.let { s -> find(s["title"], "browseId").firstOrNull().str ?: find(s["bottomEndpoint"], "browseId").firstOrNull().str }
        val top = allSongs?.let { runCatching { browseAll(it, 100).flatMap { p -> songs(p, artistName) }.distinctBy { t -> t.id }.take(25) }.getOrNull() }
            ?.takeIf { it.isNotEmpty() } ?: songs(songShelf, artistName)
        val albums = find(page, "musicCarouselShelfRenderer").filter {
            val h = text(find(it["header"], "title").firstOrNull())
            h.contains("Album", true) || h.contains("Single", true) || h.contains("EP", false)
        }.flatMap { c ->
            val singles = text(find(c["header"], "title").firstOrNull()).contains("Single", true)
            find(c, "musicTwoRowItemRenderer").mapNotNull { twoRow(it) }.map { a ->
                val year = Regex("\\b(19|20)\\d\\d\\b").find(a.sub)?.value ?: if (singles) "Single" else ""
                a.copy(sub = year)
            }
        }.distinctBy { it.id }
        return ArtistInfo(artistName, thumb(header), top.take(20), albums)
    }

    override fun album(id: String): List<Track> {
        val page = browse(id)
        val header = find(page, "musicResponsiveHeaderRenderer").firstOrNull() ?: find(page, "musicDetailHeaderRenderer").firstOrNull()
        val title = text(header?.get("title"))
        val artist = text(header?.get("straplineTextOne")).ifEmpty { credits(runs(header?.get("subtitle"))).first }
        val cover = thumb(header?.get("thumbnail"))
        return songs(page, artist, cover).map { it.copy(album = it.album.ifEmpty { title }) }
    }

    override fun libraryTracks(): List<Track> {
        val ids = myPlaylists().map { it.id }.toMutableList()
        if ("LM" !in ids) ids.add(0, "LM")   // liked music
        val seen = HashSet<String>()
        return ids.flatMap { runCatching { playlist(it) }.getOrDefault(emptyList()) }.filter { seen.add(it.id) }
    }

    companion object {
        const val ORIGIN = "https://music.youtube.com"
        const val SONGS = "EgWKAQIIAWoMEA4QChADEAQQCRAF"
        const val ARTISTS = "EgWKAQIgAWoMEA4QChADEAQQCRAF"
        const val ALBUMS = "EgWKAQIYAWoMEA4QChADEAQQCRAF"
        const val PLAYLISTS = "Eg-KAQwIABAAGAAgACgBMABqChAEEAMQCRAFEAo="
    }
}
