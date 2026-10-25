package org.omarchy.music

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.io.File

/** Artists' pictures and album covers from Google's image search (with a key of yours, in
 *  Settings), else Genius, else Wikipedia; artists' bios from Wikipedia, else Genius. Each is
 *  looked for once and kept in pictures.json; the desktop does the same. Everything here blocks:
 *  call it from [Music.bg]. A service that can't be reached is skipped. */
object Pictures {
    @Serializable
    data class Artist(val image: String = "", val imageFrom: String = "", val bio: String = "", val bioFrom: String = "",
                      val bioUrl: String = "", val chosen: Boolean = false, val t: Double = 0.0)

    /** A picture to pick from, and where it's from ("Google", "Genius", "Wikipedia"). */
    data class Choice(val url: String, val thumb: String, val from: String)

    @Serializable
    private data class Saved(val artists: Map<String, Artist> = emptyMap(), val covers: Map<String, String> = emptyMap())

    private const val RETRY = 14 * 86400   // an artist nothing was found for is looked for again after this long
    private const val WIKI_API = "https://en.wikipedia.org/w/api.php"
    private const val GENIUS_BLANK = "assets.genius.com/images/default_"   // Genius's grey placeholder
    private val MUSIC_WORDS = Regex("singer|band|music|rapper|group|producer|\\bdj\\b|composer|songwriter|duo|trio|artist|" +
        "vocalist|orchestra|ensemble|guitarist|pianist|drummer", RegexOption.IGNORE_CASE)
    private val ALBUM_WORDS = Regex("album|\\bep\\b|mixtape|soundtrack|compilation|record", RegexOption.IGNORE_CASE)

    /** The keys from Settings (google_key, google_cx). */
    internal var keys: () -> Map<String, String> = { Music.apiKeys }
    internal var fetch: (String, Map<String, String>) -> JsonElement = { url, h -> httpJson(url, h) }   // tests swap it
    private val artists = HashMap<String, Artist>()
    private val covers = HashMap<String, String>()
    private var file: File? = null

    fun init(c: Context) {
        val f = File(c.applicationContext.filesDir, "pictures.json")
        file = f
        Music.bg({ readText(f)?.let { runCatching { JSON.decodeFromString(Saved.serializer(), it) }.getOrNull() } }) { s, _ ->
            if (s != null) synchronized(this) { s.artists.forEach { (k, v) -> artists.putIfAbsent(k, v) }; s.covers.forEach { (k, v) -> covers.putIfAbsent(k, v) } }
        }
    }

    private fun save() {
        val f = file ?: return
        val text = synchronized(this) { JSON.encodeToString(Saved.serializer(), Saved(artists.toMap(), covers.toMap())) }
        writeAtomic(f, text)
    }

    private fun <T> tried(what: String, work: () -> T): T? = try { work() } catch (e: Exception) {
        runCatching { android.util.Log.w("omarchy-music", "pictures: $what: $e") }   // (not on the JVM tests' fake Android)
        null
    }

    // ---- the services ----
    internal fun google(q: String, n: Int = 6): List<Choice> {
        val k = keys()
        val key = k["google_key"].orEmpty(); val cx = k["google_cx"].orEmpty()
        if (key.isEmpty() || cx.isEmpty()) return emptyList()
        val res = fetch("https://www.googleapis.com/customsearch/v1?key=${enc(key)}&cx=${enc(cx)}&q=${enc(q)}" +
            "&searchType=image&num=$n&imgSize=large&safe=active", emptyMap())
        return res["items"].list.mapNotNull { i ->
            val link = i["link"].str ?: return@mapNotNull null
            Choice(link, i.path("image", "thumbnailLink").str ?: link, "Google")
        }
    }

    private data class GeniusArtist(val id: Long, val name: String, val image: String, val url: String)

    private fun geniusArtist(name: String): GeniusArtist? {
        val hits = fetch("https://genius.com/api/search/artist?q=${enc(name)}&per_page=5", emptyMap())
            .path("response", "sections", 0, "hits").list.map { it["result"] }.filter { it["name"].str != null }
        val best = bestNamed(hits, name) { it["name"].str ?: "" } ?: return null
        val img = best["image_url"].str ?: ""
        return GeniusArtist(best["id"].long ?: 0, best["name"].str ?: name, if (GENIUS_BLANK in img) "" else img, best["url"].str ?: "")
    }

    private fun geniusBio(id: Long): String {
        val text = fetch("https://genius.com/api/artists/$id?text_format=plain", emptyMap())
            .path("response", "artist", "description", "plain").str?.trim() ?: ""
        return if (text == "?") "" else text
    }

    private fun geniusCovers(artist: String, album: String): List<Choice> =
        fetch("https://genius.com/api/search/album?q=${enc("$artist $album")}&per_page=5", emptyMap())
            .path("response", "sections", 0, "hits").list.mapNotNull { h ->
                val r = h["result"]
                val img = r["cover_art_url"].str ?: ""
                val who = norm(r.path("artist", "name").str)
                if (img.isEmpty() || GENIUS_BLANK in img || who.startsWith("genius") ||   // its translations
                    similar(norm(bareTitle(r["name"].str ?: "")), norm(bareTitle(album))) < 0.6) null
                else Choice(img, r["cover_art_thumbnail_url"].str ?: img, "Genius")
            }

    private data class WikiPage(val bio: String, val image: String, val url: String)

    /** The article a search for q finds that's titled name (near enough) and whose short description has one of words. */
    private fun wikipedia(q: String, name: String, words: Regex): WikiPage? {
        val hdr = mapOf("User-Agent" to APP_UA)
        val res = fetch("$WIKI_API?action=query&format=json&generator=search&gsrsearch=${enc(q)}&gsrlimit=5" +
            "&prop=extracts%7Cpageimages%7Cpageprops&exintro=1&explaintext=1&exlimit=max&piprop=original" +
            "&ppprop=wikibase-shortdesc%7Cpage_image%7Cdisambiguation&redirects=1", hdr)
        val pages = res.path("query", "pages").obj?.values.orEmpty().sortedBy { it["index"].long ?: 99 }
        for (p in pages) {
            val props = p["pageprops"]
            val title = (p["title"].str ?: "").replace(Regex("\\s*\\([^)]*\\)$"), "")
            val about = props["wikibase-shortdesc"].str ?: (p["extract"].str ?: "").take(200)
            if (props["disambiguation"] != null || similar(norm(title), norm(name)) < 0.85 || !words.containsMatchIn(about)) continue
            var image = p.path("original", "source").str ?: ""
            val file = props["page_image"].str
            if (image.isEmpty() && file != null)   // a cover that isn't free: pageimages leaves it out
                image = fetch("$WIKI_API?action=query&format=json&prop=imageinfo&iiprop=url&titles=${enc("File:$file")}", hdr)
                    .path("query", "pages").obj?.values?.firstNotNullOfOrNull { it.path("imageinfo", 0, "url").str } ?: ""
            val t = p["title"].str ?: ""
            return WikiPage(wikiText(p["extract"].str ?: ""), image, "https://en.wikipedia.org/wiki/" + enc(t.replace(' ', '_')))
        }
        return null
    }

    /** An article's opening without its pronunciation guides ("(BYURK; Icelandic: [pjœr̥k])"). */
    internal fun wikiText(text: String): String {
        val head = text.substringBefore("\n")
        val rest = if ("\n" in text) text.substringAfter("\n") else ""
        var cut = head
        repeat(2) { cut = Regex("\\s*\\((?:\\s|[^()]*[\\[/;])[^()]*\\)").find(cut)?.let { m -> cut.removeRange(m.range) } ?: cut }
        return (cut + if (rest.isNotEmpty()) "\n$rest" else "").replace(Regex("  +"), " ").trim()
    }

    private fun wikiArtist(name: String) = wikipedia("$name musician", name, MUSIC_WORDS) ?: wikipedia(name, name, MUSIC_WORDS)

    private fun wikiAlbum(artist: String, album: String) = wikipedia("${bareTitle(album)} $artist album", bareTitle(album), ALBUM_WORDS)

    // ---- what the app asks for ----
    /** What's known of the artist, if it was looked up already (no network). */
    fun known(name: String): Artist? = synchronized(this) { artists[norm(name)] }

    /** The artist's picture and bio ("" where none was found), looked up the first time. */
    fun artist(name: String, refresh: Boolean = false): Artist {
        val k = norm(name)
        val have = known(name)
        if (have != null && !refresh && (have.image.isNotEmpty() || have.bio.isNotEmpty() || now() - have.t < RETRY)) return have
        var out = Artist(t = now())
        if (have?.chosen == true) out = out.copy(image = have.image, imageFrom = have.imageFrom, chosen = true)   // a picture you picked stays
        if (out.image.isEmpty()) tried("google") { google("$name musician", 1) }?.firstOrNull()?.let { out = out.copy(image = it.url, imageFrom = "Google") }
        val genius = tried("genius") { geniusArtist(name) }
        if (out.image.isEmpty() && !genius?.image.isNullOrEmpty()) out = out.copy(image = genius!!.image, imageFrom = "Genius")
        val wiki = tried("wikipedia") { wikiArtist(name) }
        if (out.image.isEmpty() && !wiki?.image.isNullOrEmpty()) out = out.copy(image = wiki!!.image, imageFrom = "Wikipedia")
        if (wiki != null && wiki.bio.isNotEmpty()) out = out.copy(bio = wiki.bio, bioFrom = "Wikipedia", bioUrl = wiki.url)
        else if (genius != null && genius.id > 0)
            tried("genius bio") { geniusBio(genius.id) }?.takeIf { it.isNotEmpty() }?.let { out = out.copy(bio = it, bioFrom = "Genius", bioUrl = genius.url) }
        synchronized(this) { artists[k] = out }
        save()
        return out
    }

    /** Pictures of the artist to pick from: Google's, Genius's and Wikipedia's. */
    fun artistChoices(name: String): List<Choice> {
        val out = ArrayList(tried("google") { google("$name musician") }.orEmpty())
        tried("genius") { geniusArtist(name) }?.image?.takeIf { it.isNotEmpty() }?.let { out += Choice(it, it, "Genius") }
        tried("wikipedia") { wikiArtist(name) }?.image?.takeIf { it.isNotEmpty() }?.let { out += Choice(it, it, "Wikipedia") }
        return out.distinctBy { it.url }
    }

    /** Covers for artist's album to pick from: Google's, then Genius's, then Wikipedia's. */
    fun coverChoices(artist: String, album: String): List<Choice> {
        val out = ArrayList(tried("google") { google("$artist ${bareTitle(album)} album cover") }.orEmpty())
        out += tried("genius") { geniusCovers(artist, album) }.orEmpty()
        tried("wikipedia") { wikiAlbum(artist, album) }?.image?.takeIf { it.isNotEmpty() }?.let { out += Choice(it, it, "Wikipedia") }
        return out.distinctBy { it.url }
    }

    /** Keep url (a web address or a file's) as the artist's picture. */
    fun chooseArtist(name: String, url: String, from: String = "you") {
        synchronized(this) { artists[norm(name)] = (artists[norm(name)] ?: Artist(t = now())).copy(image = url, imageFrom = from, chosen = true) }
        save()
    }

    private fun coverKey(artist: String, album: String) = "${norm(artist.split(",")[0])}|${norm(bareTitle(album))}"

    /** The cover you picked for artist's album, or "". */
    fun cover(artist: String, album: String): String = synchronized(this) { covers[coverKey(artist, album)] ?: "" }

    fun chooseCover(artist: String, album: String, url: String) {
        synchronized(this) { covers[coverKey(artist, album)] = url }
        save()
    }
}
