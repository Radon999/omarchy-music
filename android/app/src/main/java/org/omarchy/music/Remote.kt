package org.omarchy.music

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** Playing on the desktop app instead of the phone: the phone becomes its remote. The
 *  desktop's "Phone remote" (accounts menu) serves a small HTTP API on your network;
 *  the phone pairs once with the code it shows, then reads what's playing and sends
 *  what you tap. While on, the now-playing bar, Up next and every play or queue go there. */
object Remote {
    data class Status(
        val track: Track? = null, val paused: Boolean = true, val position: Double = 0.0, val duration: Double = 0.0,
        val repeat: String = "off", val liked: Boolean = false, val message: String = "", val index: Int = -1,
        val queue: List<Track> = emptyList(), val radio: Boolean = false,
    )

    @Serializable
    private data class Saved(val host: String = "", val token: String = "", val name: String = "", val on: Boolean = false)

    private lateinit var file: File
    var host by mutableStateOf("")
        private set
    var name by mutableStateOf("")
        private set
    private var token = ""
    var on by mutableStateOf(false)
        private set
    var status by mutableStateOf(Status())
        private set
    var reachable by mutableStateOf(false)
        private set
    private var statusAt = 0L
    private var qv = ""   // the version of the desktop's queue we have: it's sent again only when it changes
    val found = mutableStateListOf<Pair<String, String>>()   // (name, host:port) seen on the network
    val paired get() = token.isNotEmpty()
    val active get() = on && paired
    private var started = false

    fun init(ctx: Context) {
        if (started) return
        started = true
        file = File(ctx.filesDir, "remote.json")
        readText(file)?.let { runCatching { JSON.decodeFromString(Saved.serializer(), it) }.getOrNull() }?.let {
            host = it.host; token = it.token; name = it.name; on = it.on
        }
        Music.scope.launch {
            while (true) {
                if (active) refresh()
                delay(if (active) 1000 else 3000)
            }
        }
        discover(ctx)
    }

    private fun save() = writeAtomic(file, JSON.encodeToString(Saved.serializer(), Saved(host, token, name, on)))

    @JvmName("switchOn")
    fun setOn(v: Boolean) {
        on = v && paired
        save()
        if (on) refresh()
    }

    fun forget() {
        token = ""; on = false; reachable = false; qv = ""
        save()
    }

    /** Pair with the desktop at addr ("192.168.1.20:8897") using the code its accounts menu shows. */
    fun pair(addr: String, code: String, done: (Throwable?) -> Unit) {
        val h = addr.trim().removePrefix("http://").trimEnd('/').let { if (':' in it) it else "$it:$PORT" }
        Music.bg({
            httpJson("http://$h/pair", body = jsonBody(buildJsonObject {
                put("code", code.trim()); put("name", android.os.Build.MODEL)
            }))
        }) { res, err ->
            val tok = res["token"].str
            if (err == null && tok != null) {
                host = h; token = tok; name = res["name"].str ?: "Desktop"; on = true
                save()
                refresh()
            }
            done(err ?: if (tok == null) IllegalStateException("the desktop didn't answer with a pairing") else null)
        }
    }

    private fun headers() = mapOf("Authorization" to "Bearer $token")

    private fun parse(e: JsonElement?): List<Track> =
        e?.let { runCatching { JSON.decodeFromJsonElement(ListSerializer(Track.serializer()), it) }.getOrNull() } ?: emptyList()

    fun refresh() {
        if (!paired) return
        Music.bg({ httpJson("http://$host/status?qv=$qv", headers()) }) { s, err ->
            reachable = err == null
            if (err is HttpError && err.code == 401) { forget(); Music.toast("The desktop forgot this phone: pair again"); return@bg }
            if (s == null) return@bg
            val q = if (s["queue"] != null) parse(s["queue"]) else status.queue
            qv = s["qv"].str ?: ""
            val i = s["index"].long?.toInt() ?: -1
            statusAt = System.nanoTime()
            status = Status(q.getOrNull(i), s["paused"].bool ?: true, s["position"].dbl ?: 0.0, s["duration"].dbl ?: 0.0,
                s["repeat"].str ?: "off", s["liked"].bool ?: false, s["message"].str ?: "", i, q, s["radio"].bool ?: false)
        }
    }

    /** Where the desktop is in the song now, between its answers. */
    fun livePosition(): Double {
        val s = status
        if (s.paused || statusAt == 0L) return s.position
        val p = s.position + (System.nanoTime() - statusAt) / 1e9
        return if (s.duration > 0) minOf(p, s.duration) else p
    }

    fun cmd(verb: String, vararg args: Pair<String, Any?>) {
        val body = buildJsonObject {
            put("verb", verb)
            for ((k, v) in args) when (v) {
                is Number -> put(k, JsonPrimitive(v))
                is Boolean -> put(k, JsonPrimitive(v))
                is String -> put(k, JsonPrimitive(v))
                is JsonElement -> put(k, v)
                is List<*> -> put(k, if (v.firstOrNull() is Track) JSON.encodeToJsonElement(ListSerializer(Track.serializer()), v.filterIsInstance<Track>())
                    else kotlinx.serialization.json.JsonArray(v.map { JsonPrimitive(it as? Number) }))
                null -> Unit
            }
        }
        // Show it at once; the desktop's answer corrects it.
        when (verb) {
            "play" -> status = status.copy(paused = false)
            "pause" -> status = status.copy(paused = true)
        }
        Music.bg({ httpJson("http://$host/cmd", headers(), jsonBody(body)) }) { _, err ->
            if (err != null) Music.toast("The desktop didn't answer: ${err.message?.take(80)}")
            refresh()
        }
    }

    /** Songs the desktop can play: the ones on the phone itself stay here. */
    private fun playable(tracks: List<Track>): List<Track> {
        val ok = tracks.filter { it.file?.startsWith("content://") != true }
        if (ok.size < tracks.size) Music.toast(if (ok.isEmpty()) "Songs on the phone play only on the phone" else
            "${tracks.size - ok.size} song${if (tracks.size - ok.size > 1) "s" else ""} on the phone left out: the desktop can't reach ${if (tracks.size - ok.size > 1) "them" else "it"}")
        return ok
    }

    fun playFrom(tracks: List<Track>, i: Int, radio: Boolean) {
        val t = tracks.getOrNull(i)
        val ok = playable(tracks)
        if (ok.isEmpty()) return
        cmd("play_from", "tracks" to ok, "index" to maxOf(0, ok.indexOf(t)), "radio" to radio)
    }

    fun enqueue(tracks: List<Track>, how: String) {
        val ok = playable(tracks)
        if (ok.isNotEmpty()) cmd("enqueue", "tracks" to ok, "how" to how)
    }

    /** A desktop song's art: its picture on the web, or the desktop's copy (songs from its folders). */
    fun artModel(t: Track): Any? = if (t.thumb.isNotEmpty()) sizedThumb(t.thumb, 544)
        else coil.request.ImageRequest.Builder(Music.ctx).data("http://$host/art?id=${enc(t.id)}")
            .addHeader("Authorization", "Bearer $token").build()

    /** (blocking) The cover of a desktop song, for the notification and the lock screen. */
    fun artBytes(t: Track): ByteArray? = runCatching {
        if (t.thumb.isNotEmpty()) httpBytes(sizedThumb(t.thumb, 544)) else httpBytes("http://$host/art?id=${enc(t.id)}", headers())
    }.getOrNull()

    /** Desktops on this network with the remote on (they announce _omarchy-music._tcp). */
    private fun discover(ctx: Context) {
        val nsd = ctx.getSystemService(NsdManager::class.java) ?: return
        nsd.discoverServices("_omarchy-music._tcp", NsdManager.PROTOCOL_DNS_SD, object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, code: Int) {}
                    override fun onServiceResolved(i: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val addr = "${i.host.hostAddress}:${i.port}"
                        Music.scope.launch { if (found.none { it.second == addr }) found += i.serviceName to addr }
                    }
                })
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) {}
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
        })
    }

    const val PORT = 8897
}
