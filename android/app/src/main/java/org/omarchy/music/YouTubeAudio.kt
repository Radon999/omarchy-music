package org.omarchy.music

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo

/** A song's audio on YouTube Music, for when Tidal has no FLAC of it (NewPipe's extractor
 *  finds the address). A song from another service is found by a search first. */
object YouTubeAudio {
    private var started = false

    private object Net : Downloader() {
        override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): Response {
            val body = request.dataToSend()?.toRequestBody()
            val b = Request.Builder().url(request.url()).method(request.httpMethod(), body)
            for ((k, values) in request.headers()) { b.removeHeader(k); values.forEach { b.addHeader(k, it) } }
            if (request.headers().keys.none { it.equals("User-Agent", true) }) b.header("User-Agent", DESKTOP_UA)
            HTTP.newCall(b.build()).execute().use { r ->
                return Response(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
            }
        }
    }

    /** (blocking) (address, "Opus 160 kbps"), or null if YouTube Music hasn't got it. */
    fun find(t: Track): Pair<String, String>? {
        val vid = if (t.source == "ytm") t.id else search(t) ?: return null
        return audio(vid)
    }

    /** (blocking) The audio of YouTube video vid: (address, "Opus 160 kbps"), or null. */
    fun audio(vid: String): Pair<String, String>? {
        synchronized(this) { if (!started) { NewPipe.init(Net); started = true } }
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$vid")
        val a = info.audioStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.content.isNotEmpty() }
            .maxByOrNull { it.averageBitrate } ?: return null
        // YouTube's address answers only a moment after it's made.
        repeat(20) {
            val ok = runCatching {
                HTTP.newCall(Request.Builder().url(a.content).header("Range", "bytes=0-1").build()).execute().use { it.code in 200..206 }
            }.getOrDefault(false)
            if (ok) {
                val codec = a.codec?.substringBefore('.')?.let { mapOf("opus" to "Opus", "mp4a" to "AAC")[it] ?: it } ?: a.format?.name ?: ""
                return a.content to "$codec ${a.averageBitrate} kbps".trim()
            }
            Thread.sleep(500)
        }
        error("YouTube wouldn't send the audio")
    }

    private fun search(t: Track): String? {
        val ytm = Music.sources["ytm"] ?: return null
        val lead = t.artist.split(",")[0].trim()
        return ytm.search("$lead ${bareTitle(t.title)}").filter { c ->
            t.duration <= 0 || c.duration <= 0 || kotlin.math.abs(c.duration - t.duration) <= 6
        }.map { c ->
            val title = similar(cleanTitle(t.title), cleanTitle(c.title))
            val artist = artistNames(listOf(lead)).flatMap { x -> artistNames(c.artist.split(",")).map { y -> similar(x, y) } }.maxOrNull() ?: 0.0
            c to (if (title >= 0.75 && artist >= 0.8) 0.6 * title + 0.4 * artist else 0.0)
        }.filter { it.second > 0 }.maxByOrNull { it.second }?.first?.id
    }
}
