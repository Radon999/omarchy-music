package org.omarchy.music

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.security.MessageDigest

class LastFmError(val code: Int, message: String) : RuntimeException("Last.fm: $message ($code)")

/** Your Last.fm account. Last.fm lets an app in only with an API account of its own: you
 *  make one (free, a minute) at last.fm/api/account/create and give the app its key and
 *  secret, then approve the app on Last.fm's site. Scrobbles wait on the phone while it's
 *  offline, for the two weeks Last.fm still takes them. */
@SuppressLint("StaticFieldLeak")
object LastFm {
    private const val API = "https://ws.audioscrobbler.com/2.0/"

    @Serializable
    private data class Saved(val key: String = "", val secret: String = "", val session: String = "", val user: String = "")

    @Serializable
    data class Scrobble(val artist: String, val title: String, val album: String = "", val albumartist: String = "",
                        val duration: Int = 0, val mbid: String = "", val timestamp: Long)

    private lateinit var ctx: Context
    private lateinit var file: File
    private lateinit var queueFile: File
    var key by mutableStateOf("")
        private set
    var secret by mutableStateOf("")
        private set
    var user by mutableStateOf("")
        private set
    private var session by mutableStateOf("")
    private var token: String? = null
    private var url = ""
    private val pending = ArrayList<Scrobble>()
    private val sendLock = Any()

    val connected get() = key.isNotEmpty() && secret.isNotEmpty() && session.isNotEmpty()

    fun init(c: Context) {
        if (::ctx.isInitialized) return
        ctx = c.applicationContext
        file = File(ctx.filesDir, "lastfm.json")
        queueFile = File(ctx.filesDir, "scrobbles.json")
        readText(file)?.let { runCatching { JSON.decodeFromString(Saved.serializer(), it) }.getOrNull() }?.let {
            key = it.key; secret = it.secret; session = it.session; user = it.user
        }
        readText(queueFile)?.let { runCatching { JSON.decodeFromString(ListSerializer(Scrobble.serializer()), it) }.getOrNull() }
            ?.let { pending += it }
        if (connected && pending.isNotEmpty()) Music.bg({ flush() }) { _, err -> result(err) }
    }

    private fun save() = writeAtomic(file, JSON.encodeToString(Saved.serializer(), Saved(key, secret, session, user)))
    private fun saveQueue() = synchronized(pending) { writeAtomic(queueFile, JSON.encodeToString(ListSerializer(Scrobble.serializer()), pending)) }

    fun call(method: String, signed: Boolean = false, post: Boolean = false, params: Map<String, Any?> = emptyMap()): JsonElement {
        val p = sortedMapOf<String, String>()
        params.forEach { (k, v) -> if (v != null && v.toString().isNotEmpty() && v != 0) p[k] = v.toString() }
        p["method"] = method
        p["api_key"] = key
        if (signed) {
            if (session.isNotEmpty() && !method.startsWith("auth.")) p["sk"] = session
            val sig = p.entries.joinToString("") { it.key + it.value } + secret
            p["api_sig"] = MessageDigest.getInstance("MD5").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }
        p["format"] = "json"
        val hdr = mapOf("User-Agent" to APP_UA)
        val res = try {
            if (post) httpJson(API, hdr, form(*p.map { it.key to it.value }.toTypedArray()))
            else httpJson(API + "?" + p.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }, hdr)
        } catch (e: HttpError) {   // Last.fm explains what went wrong in the body
            runCatching { JSON.parseToJsonElement(e.body) }.getOrNull()?.takeIf { it["error"] != null } ?: throw e
        }
        res["error"].long?.let { throw LastFmError(it.toInt(), res["message"].str ?: "") }
        return res
    }

    /** What to do with a failed call: Last.fm revoked the app (9) signs you out. */
    fun result(err: Throwable?) {
        if (err is LastFmError && err.code == 9) Music.scope.launch {
            disconnect()
            Music.toast("Last.fm signed you out: connect it again in the accounts menu")
        }
    }

    // ---- connecting ----
    /** Connecting, step 1: remember the API account and get the page to approve the app on. */
    fun begin(k: String, s: String, done: (Boolean) -> Unit) {
        val kk = k.trim(); val ss = s.trim()
        if (!Regex("[0-9a-f]{32}").matches(kk) || !Regex("[0-9a-f]{32}").matches(ss)) {
            Music.toast("That isn't a Last.fm API key and secret: 32 letters and digits each"); done(false); return
        }
        key = kk; secret = ss; session = ""; user = ""
        save()
        Music.bg({ call("auth.getToken", signed = true)["token"].str ?: error("no token") }) { tok, err ->
            if (err != null || tok == null) { Music.toast("Last.fm didn't take that key: ${err?.message?.take(80)}"); done(false); return@bg }
            token = tok
            url = "https://www.last.fm/api/auth/?api_key=$key&token=$tok"
            done(true)
        }
    }

    fun authUrl() = url

    /** The approval page is done: Last.fm says so, or goes on to the API account's callback. */
    fun authDone(u: String) = "/api/grantaccess" in u || ("token=" in u && !u.contains("last.fm/api/auth"))

    /** Step 2, once you approved it (quiet: the page was closed, maybe without approving). */
    fun finishLogin(quiet: Boolean = false) {
        val tok = token ?: return
        Music.bg({
            var s: JsonElement? = null
            for (attempt in 0 until 3) {
                try { s = call("auth.getSession", signed = true, params = mapOf("token" to tok))["session"]; break }
                catch (e: LastFmError) { if (e.code != 14 || attempt == 2) throw e; Thread.sleep(1500) }   // 14: not approved yet
            }
            s
        }) { s, err ->
            val sk = s["key"].str
            if (err != null || sk == null) {
                if (!quiet || (err as? LastFmError)?.code != 14) Music.toast(if ((err as? LastFmError)?.code == 14)
                    "Last.fm wasn't approved: connect again" else "Couldn't connect Last.fm: ${err?.message?.take(80)}")
                return@bg
            }
            token = null
            session = sk; user = s["name"].str ?: ""
            save()
            Music.toast("Last.fm connected: scrobbling as $user")
            Music.accountsVersion++
            Music.bg({ flush() }) { _, e -> result(e) }
        }
    }

    fun disconnect() {
        session = ""; user = ""
        save()
        Music.accountsVersion++
    }

    // ---- scrobbling ----
    private fun song(m: Scrobble, i: Int? = null): Map<String, Any?> {
        val n = if (i == null) "" else "[$i]"
        return mapOf("artist$n" to m.artist, "track$n" to m.title, "album$n" to m.album, "albumArtist$n" to m.albumartist,
            "duration$n" to m.duration, "mbid$n" to m.mbid)
    }

    private fun meta(t: Track, ts: Long = 0): Scrobble? {
        val m = songMeta(ctx, Music.lib, t)
        if (m.artist.isEmpty() || m.title.isEmpty()) return null
        return Scrobble(m.artist, m.title, m.album, m.albumArtist, m.duration.toInt(), m.mbid, ts)
    }

    fun nowPlaying(t: Track) {
        val m = meta(t) ?: return
        runCatching { call("track.updateNowPlaying", signed = true, post = true, params = song(m)) }.onFailure { result(it) }
    }

    fun scrobble(t: Track, start: Double) {
        val m = meta(t, start.toLong()) ?: return
        synchronized(pending) { pending += m }
        saveQueue()
        runCatching { flush() }.onFailure { result(it) }
    }

    /** Send the scrobbles waiting, 50 at a time; offline, they wait for next time. */
    fun flush() {
        if (!connected) return
        synchronized(sendLock) {
            synchronized(pending) {
                val cutoff = now() - 14 * 86400 + 3600
                if (pending.removeAll { it.timestamp < cutoff }) saveQueue()
            }
            while (true) {
                val batch = synchronized(pending) { pending.take(50) }
                if (batch.isEmpty()) return
                val params = HashMap<String, Any?>()
                batch.forEachIndexed { i, s -> params += song(s, i); params["timestamp[$i]"] = s.timestamp }
                try { call("track.scrobble", signed = true, post = true, params = params) }
                catch (e: LastFmError) { if (e.code != 6) throw e }   // 6: Last.fm won't take these at all; drop them, not the rest
                synchronized(pending) { repeat(batch.size) { if (pending.isNotEmpty()) pending.removeAt(0) } }
                saveQueue()
            }
        }
    }

    fun love(t: Track, on: Boolean) {
        val m = meta(t) ?: return
        call(if (on) "track.love" else "track.unlove", signed = true, post = true, params = mapOf("artist" to m.artist, "track" to m.title))
    }

    // ---- your history, for Wrapped ----
    data class Played(val time: Long, val artist: String, val title: String, val album: String, val image: String)
    data class Top(val name: String, val artist: String, val plays: Int, val image: String)
    data class Profile(val name: String, val playcount: Int, val registered: Long, val image: String)

    private fun image(e: JsonElement?) = e["image"].list.reversed().firstNotNullOfOrNull { it["#text"].str?.takeIf { s -> s.isNotEmpty() } } ?: ""
    private fun items(e: JsonElement?) = if (e is JsonObject) listOf(e) else e.list

    /** Every scrobble from start to end (unix times). */
    fun recent(start: Long, end: Long, progress: ((Int, Int) -> Unit)? = null): List<Played> {
        val out = ArrayList<Played>()
        var page = 1; var pages = 1
        while (page <= pages) {
            val rt = call("user.getRecentTracks", params = mapOf("user" to user, "limit" to 200, "page" to page, "from" to start, "to" to end))["recenttracks"]
            pages = rt.path("@attr", "totalPages").long?.toInt() ?: 1
            for (t in items(rt["track"])) {
                val ts = t.path("date", "uts").long ?: continue   // the song playing right now
                out += Played(ts, t.path("artist", "#text").str ?: "", t["name"].str ?: "", t.path("album", "#text").str ?: "", image(t))
            }
            progress?.invoke(page, pages)
            page++
        }
        return out
    }

    fun duration(artist: String, title: String): Double =
        (call("track.getInfo", params = mapOf("artist" to artist, "track" to title, "autocorrect" to 1)).path("track", "duration").dbl ?: 0.0) / 1000

    fun artistTags(artist: String): List<Pair<String, Int>> =
        call("artist.getTopTags", params = mapOf("artist" to artist, "autocorrect" to 1)).path("toptags", "tag").list.take(12)
            .map { (it["name"].str ?: "") to (it["count"].long?.toInt() ?: 0) }

    fun info(): Profile {
        val u = call("user.getInfo", params = mapOf("user" to user))["user"]
        val reg = u["registered"]
        return Profile(u["name"].str ?: user, u["playcount"].long?.toInt() ?: 0, reg["unixtime"].long ?: reg["#text"].long ?: 0, image(u))
    }

    /** How many scrobbles you have from start to end (one quick request). */
    fun count(start: Long, end: Long): Int =
        call("user.getRecentTracks", params = mapOf("user" to user, "limit" to 1, "from" to start, "to" to end)).path("recenttracks", "@attr", "total").long?.toInt() ?: 0

    /** Your top artists, tracks or albums (kind: "artist", "track", "album"). */
    fun top(kind: String, limit: Int = 10, period: String = "overall", page: Int = 1): List<Top> {
        val res = call("user.getTop${kind.replaceFirstChar { it.uppercase() }}s", params = mapOf("user" to user, "period" to period, "limit" to limit, "page" to page))
        return items(res.path("top${kind}s", kind)).map { x ->
            val a = x["artist"]
            Top(x["name"].str ?: "", (if (a is JsonObject) a["name"].str else null) ?: "", x["playcount"].long?.toInt() ?: 0, image(x))
        }
    }
}
