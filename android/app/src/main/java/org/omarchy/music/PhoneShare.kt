package org.omarchy.music

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/** The songs on the phone, played on the desktop while the phone is its remote: the
 *  desktop streams each from here over your network. Every address carries a code made
 *  with a key that never leaves the phone, so only a song the phone sent can be read,
 *  and a code still works after the app restarts. It serves only while the remote is on. */
object PhoneShare {
    const val PORT = 8899

    private lateinit var ctx: Context
    private lateinit var key: ByteArray
    @Volatile private var server: ServerSocket? = null

    fun init(c: Context) {
        ctx = c.applicationContext
        val f = File(ctx.filesDir, "share.key")
        key = runCatching { f.readBytes() }.getOrNull()?.takeIf { it.size == 32 }
            ?: ByteArray(32).also { SecureRandom().nextBytes(it); f.writeBytes(it) }
    }

    private fun code(what: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        return Base64.encodeToString(mac.doFinal(what.toByteArray()), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP).take(22)
    }

    /** This phone's address on the network the desktop is on (no packet is sent). */
    private fun address(desktop: String): String? = runCatching {
        DatagramSocket().use { s -> s.connect(InetAddress.getByName(desktop.substringBeforeLast(':')), 9); s.localAddress.hostAddress }
    }.getOrNull()?.takeIf { it != "0.0.0.0" && it != "::" }

    /** t as the desktop can play it: from this phone, its cover too. Null if it can't. */
    fun forDesktop(t: Track, desktop: String): Track? {
        val id = LocalFiles.mediaId(t.file ?: return null) ?: return null
        val me = address(desktop) ?: return null
        start()
        val base = "http://$me:$PORT"
        return t.copy(id = "ph$id", file = "$base/song/$id?k=${code("song/$id")}", thumb = "$base/art/$id?k=${code("art/$id")}")
    }

    @Synchronized fun start() {
        if (server != null) return
        val s = runCatching { ServerSocket(PORT) }.getOrNull() ?: return
        server = s
        thread(isDaemon = true, name = "phone-share") {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { serve(c) }; runCatching { c.close() } }
            }
        }
    }

    @Synchronized fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun answer(c: Socket, code: Int, reason: String, headers: Map<String, Any> = emptyMap(), body: ByteArray? = null) {
        val out = c.getOutputStream()
        val head = StringBuilder("HTTP/1.1 $code $reason\r\nConnection: close\r\n")
        headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
        if (body != null) head.append("Content-Length: ${body.size}\r\n")
        out.write(head.append("\r\n").toString().toByteArray())
        if (body != null) out.write(body)
        out.flush()
    }

    /** The bytes a Range header asks for, first to last, in a file of size bytes; null if none
     *  of them are there. "bytes=100-", "bytes=100-199", "bytes=-500" (the last 500). */
    internal fun span(range: String, size: Long): Pair<Long, Long>? {
        val m = Regex("bytes=(\\d*)-(\\d*)").matchEntire(range.trim()) ?: return null
        val (a, b) = m.destructured
        val (from, to) = when {
            a.isEmpty() && b.isEmpty() -> return null
            a.isEmpty() -> maxOf(0, size - b.toLong()) to size - 1
            else -> a.toLong() to if (b.isEmpty()) size - 1 else minOf(b.toLong(), size - 1)
        }
        return if (from >= size || from > to) null else from to to
    }

    private fun serve(c: Socket) {
        c.soTimeout = 30_000
        val r = BufferedReader(InputStreamReader(c.getInputStream(), Charsets.ISO_8859_1))
        val line = r.readLine() ?: return
        val (method, target) = line.split(" ").let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        var range: String? = null
        while (true) {
            val h = r.readLine() ?: break
            if (h.isEmpty()) break
            if (h.startsWith("range:", ignoreCase = true)) range = h.substringAfter(':').trim()
        }
        val u = Uri.parse(target)
        val parts = u.pathSegments
        if (method !in setOf("GET", "HEAD") || parts.size != 2 || parts[0] !in setOf("song", "art") ||
            !parts[1].all { it.isDigit() } || u.getQueryParameter("k") != code("${parts[0]}/${parts[1]}"))
            return answer(c, 404, "Not Found", body = ByteArray(0))
        val media = Uri.parse("content://media/external/audio/media/${parts[1]}")
        if (parts[0] == "art") {
            val pic = runCatching {
                MediaMetadataRetriever().run { try { setDataSource(ctx, media); embeddedPicture } finally { release() } }
            }.getOrNull() ?: return answer(c, 404, "Not Found", body = ByteArray(0))
            return answer(c, 200, "OK", mapOf("Content-Type" to "image/jpeg"), if (method == "HEAD") ByteArray(0) else pic)
        }
        val fd = runCatching { ctx.contentResolver.openFileDescriptor(media, "r") }.getOrNull()
            ?: return answer(c, 404, "Not Found", body = ByteArray(0))
        fd.use {
            FileInputStream(fd.fileDescriptor).use { input ->
                val size = fd.statSize
                val type = ctx.contentResolver.getType(media) ?: "application/octet-stream"
                val part = range?.let { span(it, size) }
                if (range != null && part == null)
                    return answer(c, 416, "Range Not Satisfiable", mapOf("Content-Range" to "bytes */$size"), ByteArray(0))
                val (from, to) = part ?: (0L to size - 1)
                val length = to - from + 1
                val head = mutableMapOf<String, Any>("Content-Type" to type, "Accept-Ranges" to "bytes", "Content-Length" to length)
                if (part != null) head["Content-Range"] = "bytes $from-$to/$size"
                answer(c, if (part != null) 206 else 200, if (part != null) "Partial Content" else "OK", head)
                if (method == "HEAD") return
                input.channel.position(from)
                val out = c.getOutputStream()
                val buf = ByteArray(1 shl 16)
                var left = length
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    left -= n
                }
                out.flush()
            }
        }
    }
}
