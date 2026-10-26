package org.omarchy.music

import android.content.Context
import kotlinx.serialization.Serializable
import java.io.File

/** Lyrics: Genius's text, and lines timed to the music (Tidal's, which come from
 *  Musixmatch, else LRCLIB's) to follow along. Each song's are found once and kept;
 *  a saved song's own tags come first, so lyrics work offline too. */
object Lyrics {
    private const val RETRY = 7 * 86400   // look again for lyrics nobody had, after a week
    private const val NOTES_KEEP = 14 * 86400   // Genius's annotations are looked at again after two weeks

    @Serializable
    data class Got(val plain: String? = null, val plain_from: String? = null, val synced: String? = null,
                   val synced_from: String? = null, val url: String? = null, val instrumental: Boolean = false,
                   val time: Double = 0.0, val genius_id: Long? = null) {
        val has get() = plain != null || synced != null || instrumental
    }

    // Genius swaps a few letters of its lyrics for look-alikes from other alphabets (to spot
    // copies). In a word that is otherwise Latin, put the real letter back.
    private val HOMOGLYPHS = mapOf('а' to 'a', 'е' to 'e', 'о' to 'o', 'р' to 'p', 'с' to 'c', 'у' to 'y', 'х' to 'x', 'і' to 'i',
        'ј' to 'j', 'ѕ' to 's', 'һ' to 'h', 'ԁ' to 'd', 'ɡ' to 'g', 'А' to 'A', 'В' to 'B', 'Е' to 'E',
        'К' to 'K', 'М' to 'M', 'Н' to 'H', 'О' to 'O', 'Р' to 'P', 'С' to 'C', 'Т' to 'T', 'Х' to 'X',
        'І' to 'I', 'Ј' to 'J', 'Ѕ' to 'S', 'ο' to 'o', 'α' to 'a', 'ν' to 'v', 'Ο' to 'O', 'Α' to 'A')
    private val LRC_TIME = Regex("\\[(\\d+):(\\d+(?:[.:]\\d+)?)]")

    internal fun degenius(text: String): String {
        val t = text.replace(Regex("[  -   ]"), " ").replace(Regex("[​-‍⁠﻿]"), "")
        return Regex("\\w+").replace(t) { m ->
            val w = m.value
            val letters = w.filter { it.isLetter() }
            if (2 * letters.count { it.code < 128 } >= letters.length) w.map { HOMOGLYPHS[it] ?: it }.joinToString("") else w
        }
    }

    private fun unescape(s: String): String = Regex("&(#x[0-9a-fA-F]+|#\\d+|amp|lt|gt|quot|apos|nbsp);").replace(s) { m ->
        when (val e = m.groupValues[1]) {
            "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> " "
            else -> runCatching { String(Character.toChars(if (e[1] == 'x') e.drop(2).toInt(16) else e.drop(1).toInt())) }.getOrDefault(m.value)
        }
    }

    private val VOID = setOf("br", "img", "hr", "input", "meta", "link", "source", "wbr", "area", "col", "embed", "param", "track")

    /** The text of a Genius song page's lyrics, without the header Genius puts inside them. */
    private fun geniusText(html: String): String {
        val parts = StringBuilder()
        var depth = 0; var skip = 0
        for (m in Regex("<(/?)([a-zA-Z0-9]+)([^>]*)>|<!--.*?-->|([^<]+)", RegexOption.DOT_MATCHES_ALL).findAll(html)) {
            val data = m.groups[4]?.value
            if (data != null) { if (depth > 0 && skip == 0) parts.append(unescape(data)); continue }
            val tag = m.groupValues[2].lowercase()
            if (tag.isEmpty()) continue   // a comment
            val attrs = m.groupValues[3]
            if (m.groupValues[1] == "/") {
                if (depth > 0 && tag !in VOID) { depth--; if (skip > 0) skip-- }
                continue
            }
            if (tag in VOID || attrs.trimEnd().endsWith("/")) {
                if (depth > 0 && tag == "br" && skip == 0) parts.append('\n')
                continue
            }
            if (depth > 0) {
                depth++
                if (skip > 0 || "data-exclude-from-selection=\"true\"" in attrs) skip++
            } else if ("data-lyrics-container=\"true\"" in attrs) {
                depth = 1
                if (parts.isNotEmpty()) parts.append('\n')
            }
        }
        return parts.toString().replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /** (text, page address, song id) of the song on Genius, or null if Genius hasn't got it. */
    private fun genius(artist: String, title: String): Triple<String, String, Long?>? {
        val lead = artist.split(",")[0].trim()
        val want = cleanTitle(title)
        var best: kotlinx.serialization.json.JsonElement? = null
        var bestScore = 0.0
        for (q in linkedSetOf("$lead ${bareTitle(title)}", "$lead $want")) {
            val res = httpJson("https://genius.com/api/search/song?q=${enc(q)}&per_page=5")
            for (hit in res.path("response", "sections", 0, "hits").list) {
                val r = hit["result"]
                val names = artistNames(listOf(r["artist_names"].str ?: "", r.path("primary_artist", "name").str ?: ""))
                if (names.any { it.startsWith("genius") }) continue   // its translations and romanizations
                val t = similar(want, cleanTitle(r["title"].str ?: ""))
                val a = artistNames(listOf(lead)).flatMap { x -> names.map { y -> similar(x, y) } }.maxOrNull() ?: 0.0
                if (t >= 0.6 && a >= 0.7 && 0.6 * t + 0.4 * a > bestScore) { best = r; bestScore = 0.6 * t + 0.4 * a }
            }
            if (bestScore >= 0.9) break
        }
        val url = best["url"].str ?: return null
        val text = degenius(geniusText(http(url, mapOf("User-Agent" to DESKTOP_UA))))
        return if (text.isNotEmpty()) Triple(text, url, best["id"].long) else null
    }

    // ---- Genius's annotations of the words ----
    /** One annotation: its text, its votes, whether the artist wrote it, and Genius's "accepted" or not. */
    @Serializable
    data class Note(val text: String, val votes: Int = 0, val verified: Boolean = false, val state: String = "")

    /** The words of a song some annotations are about (fragment), and its page on Genius. */
    @Serializable
    data class Noted(val fragment: String, val url: String = "", val notes: List<Note>)

    @Serializable
    private data class SavedNotes(val time: Double, val notes: List<Noted>)

    /** A page of Genius's referents: (the annotated words, how many referents the page had). The
     *  description of the whole song isn't one of them. */
    internal fun parseNotes(res: kotlinx.serialization.json.JsonElement): Pair<List<Noted>, Int> {
        val refs = res.path("response", "referents").list
        return refs.mapNotNull { r ->
            val frag = r["fragment"].str?.trim().orEmpty()
            if (r["is_description"].bool == true || frag.isEmpty()) return@mapNotNull null
            val notes = r["annotations"].list.mapNotNull { a ->
                val text = a.path("body", "plain").str?.trim().orEmpty()
                if (text.isEmpty()) null else Note(degenius(text), (a["votes_total"].long ?: 0).toInt(), a["verified"].bool == true, a["state"].str ?: "")
            }
            if (notes.isEmpty()) null else Noted(degenius(frag), r["url"].str ?: "", notes)
        } to refs.size
    }

    /** Words to compare a line of lyrics and an annotation's by: no case, quotes or punctuation. */
    internal fun noteKey(text: String) =
        text.lowercase().replace("’", "").replace("'", "").replace(Regex("[^\\p{L}\\p{N}_]+"), " ").trim()

    /** Which lines of text (lyrics) each of notes is about: line index -> note index. The words of
     *  an annotation are whole lines, or part of one; a chorus sung again gets the note each time;
     *  a line two notes are about gets the first. (The desktop's place_notes.) */
    internal fun placeNotes(text: String, notes: List<Noted>): Map<Int, Int> {
        val keys = text.lines().map { noteKey(it) }
        val at = HashMap<Int, Int>()
        fun near(f: String, line: String) = f in line || (line.length >= 6 && line in f)
        for ((k, n) in notes.withIndex()) {
            val frag = n.fragment.lines().map { noteKey(it) }.filter { it.isNotEmpty() }
            if (frag.isEmpty()) continue
            for ((i, first) in keys.withIndex()) {
                if (first.isEmpty() || !near(frag[0], first)) continue
                val lines = mutableListOf(i)
                var j = i + 1
                var ok = true
                for (f in frag.drop(1)) {
                    while (j < keys.size && keys[j].isEmpty()) j++   // a blank line inside the words
                    if (j >= keys.size || !near(f, keys[j])) { ok = false; break }
                    lines += j; j++
                }
                if (ok) lines.forEach { at.putIfAbsent(it, k) }
            }
        }
        return at
    }

    /** (blocking) Genius's annotations of the song whose lyrics are got, empty if they aren't
     *  Genius's or it has none. Kept a while, each song's in a file. */
    fun notes(ctx: Context, got: Got): List<Noted> {
        val url = got.url ?: return emptyList()
        if (!url.startsWith("https://genius.com/")) return emptyList()
        val dir = File(ctx.cacheDir, "genius-notes").apply { mkdirs() }
        val file = File(dir, "${safeName(url.substringAfterLast('/'))}.json")
        val cached = readText(file)?.let { runCatching { JSON.decodeFromString(SavedNotes.serializer(), it) }.getOrNull() }
        if (cached != null && now() - cached.time < NOTES_KEEP) return cached.notes
        val notes = try {
            val id = got.genius_id ?: Regex("genius://songs/(\\d+)").find(http(url, mapOf("User-Agent" to DESKTOP_UA)))?.groupValues?.get(1)?.toLong()
            val out = ArrayList<Noted>()
            if (id != null) for (page in 1..4) {
                val (some, n) = parseNotes(httpJson("https://genius.com/api/referents?song_id=$id&text_format=plain&per_page=50&page=$page"))
                out += some
                if (n < 50) break
            }
            out
        } catch (e: Exception) { return cached?.notes ?: emptyList() }   // offline: what was found last time
        writeAtomic(file, JSON.encodeToString(SavedNotes.serializer(), SavedNotes(now(), notes)))
        return notes
    }

    /** (timed LRC text, plain text, instrumental) from LRCLIB, or null. */
    private fun lrclib(artist: String, title: String, album: String, duration: Double): Triple<String?, String?, Boolean>? {
        val hdr = mapOf("User-Agent" to APP_UA)
        val lead = artist.split(",")[0].trim()
        var found = try {
            httpJson("https://lrclib.net/api/get?artist_name=${enc(lead)}&track_name=${enc(title)}&album_name=${enc(album)}&duration=${duration.toInt()}", hdr)
        } catch (e: HttpError) { if (e.code != 404) throw e; null }
        if (found?.get("id") == null) {
            val hits = httpJson("https://lrclib.net/api/search?track_name=${enc(title)}&artist_name=${enc(lead)}", hdr).list
                .filter { duration <= 0 || Math.abs((it["duration"].dbl ?: 0.0) - duration) <= 3 }
                .sortedByDescending { similar(cleanTitle(title), cleanTitle(it["trackName"].str ?: "")) }
            found = hits.firstOrNull()?.takeIf { similar(cleanTitle(title), cleanTitle(it["trackName"].str ?: "")) > 0.7 }
        }
        val f = found ?: return null
        return Triple(f["syncedLyrics"].str?.ifEmpty { null }, f["plainLyrics"].str?.ifEmpty { null }, f["instrumental"].bool == true)
    }

    /** (seconds, line) from LRC text, in order; empty if it has no timed lines. */
    fun parseLrc(text: String?): List<Pair<Double, String>> {
        if (text == null) return emptyList()
        val offset = Regex("(?i)\\[offset:\\s*([+-]?\\d+)]").find(text)?.groupValues?.get(1)?.toInt()?.div(1000.0) ?: 0.0   // + shows the lines sooner
        val lines = ArrayList<Pair<Double, String>>()
        for (raw in text.lines()) {
            val stamps = LRC_TIME.findAll(raw).toList()
            if (stamps.isEmpty()) continue
            val words = Regex("<\\d+:\\d+(?:\\.\\d+)?>").replace(LRC_TIME.replace(raw, ""), "").trim()   // drops word timings
            for (s in stamps) lines += maxOf(0.0, s.groupValues[1].toInt() * 60 + s.groupValues[2].replace(':', '.').toDouble() - offset) to words
        }
        return lines.sortedBy { it.first }
    }

    private fun fromFile(m: SongMeta): Got? {
        var plain = m.tags["lyrics"]?.firstOrNull() ?: m.tags["unsyncedlyrics"]?.firstOrNull()
        var synced: String? = null
        if (plain != null && parseLrc(plain).isNotEmpty()) { synced = plain; plain = null }   // timed lines kept in the tag itself
        if (plain == null && synced == null) return null
        return Got(plain, "the file", synced, "the file")
    }

    /** The lyrics of track, or null if nobody has them. It blocks. */
    fun get(ctx: Context, t: Track, refresh: Boolean = false): Got? {
        val m = songMeta(ctx, Music.lib, t)
        if (m.file != null && !refresh) fromFile(m)?.let { return it }
        val dir = File(ctx.cacheDir, "lyrics").apply { mkdirs() }
        val file = File(dir, "${safeName(t.id)}.json")
        val cached = readText(file)?.let { runCatching { JSON.decodeFromString(Got.serializer(), it) }.getOrNull() }
        if (cached != null && !refresh && (cached.has || now() - cached.time < RETRY)) return cached.takeIf { it.has }
        val (got, reached) = fetch(m)
        if (reached) writeAtomic(file, JSON.encodeToString(Got.serializer(), got.copy(time = now())))   // offline: don't remember "no lyrics"
        return got.takeIf { it.has }
    }

    private fun fetch(m: SongMeta): Pair<Got, Boolean> {
        var got = Got()
        var reached = false
        runCatching { genius(m.artist, m.title) }.onSuccess { g ->
            reached = true
            if (g != null) got = got.copy(plain = g.first, plain_from = "Genius", url = g.second, genius_id = g.third)
        }   // offline, or Genius changed: the others may still have them
        val tidal = Music.tidal
        if (m.tidal != null && tidal.connected) runCatching { tidal.lyrics(m.tidal) }.onSuccess { (synced, plain) ->
            reached = true
            if (synced != null) got = got.copy(synced = synced, synced_from = "Musixmatch, through Tidal")
            if (plain != null && got.plain == null) got = got.copy(plain = plain, plain_from = "Musixmatch, through Tidal")
        }
        if (got.synced == null) runCatching { lrclib(m.artist, m.title, m.album, m.duration) }.onSuccess { r ->
            reached = true
            if (r != null) {
                val (synced, plain, instrumental) = r
                if (synced != null) got = got.copy(synced = synced, synced_from = "LRCLIB")
                if (plain != null && got.plain == null) got = got.copy(plain = plain, plain_from = "LRCLIB")
                got = got.copy(instrumental = instrumental && got.plain == null && got.synced == null)
            }
        }
        return got to reached
    }
}
