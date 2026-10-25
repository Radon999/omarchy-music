package org.omarchy.music

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** FLAC files without ffmpeg: Tidal's hi-res FLAC-in-MP4 taken out to a plain .flac
 *  (the audio frames copied untouched), and tags written the way the desktop's mutagen
 *  writes them (Vorbis comments and a front cover). */
object Flac {
    data class Block(val type: Int, val data: ByteArray)

    // ---- MP4 → FLAC ----

    private class Box(val type: String, val start: Int, val end: Int, val body: Int)

    private fun boxes(b: ByteArray, from: Int, to: Int): List<Box> {
        val out = ArrayList<Box>()
        var p = from
        while (p + 8 <= to) {
            var size = ByteBuffer.wrap(b, p, 4).int.toLong() and 0xffffffffL
            val type = String(b, p + 4, 4, Charsets.ISO_8859_1)
            var body = p + 8
            if (size == 1L) {
                size = ByteBuffer.wrap(b, p + 8, 8).long
                body = p + 16
            } else if (size == 0L) size = (to - p).toLong()
            if (size < 8 || p + size > to) break
            out += Box(type, p, (p + size).toInt(), body)
            p += size.toInt()
        }
        return out
    }

    private fun child(b: ByteArray, box: Box, type: String, skip: Int = 0) = boxes(b, box.body + skip, box.end).firstOrNull { it.type == type }

    /** The FLAC metadata blocks (STREAMINFO first) from an MP4 init segment's dfLa box. */
    fun dfLaBlocks(init: ByteArray): List<Block> {
        val moov = boxes(init, 0, init.size).first { it.type == "moov" }
        val trak = child(init, moov, "trak")!!
        val stbl = child(init, child(init, child(init, trak, "mdia")!!, "minf")!!, "stbl")!!
        val stsd = child(init, stbl, "stsd")!!
        val entry = boxes(init, stsd.body + 8, stsd.end).first { it.type == "fLaC" }
        val dfla = boxes(init, entry.body + 28, entry.end).first { it.type == "dfLa" }
        return readBlocks(init.copyOfRange(dfla.body + 4, dfla.end))
    }

    /** The audio in an MP4 segment: its mdat payloads, which for FLAC are whole frames. */
    fun mdat(seg: ByteArray): List<ByteArray> =
        boxes(seg, 0, seg.size).filter { it.type == "mdat" }.map { seg.copyOfRange(it.body, it.end) }

    /** A FLAC file from FLAC-in-MP4: init segment, then each media segment. totalSamples
     *  goes into STREAMINFO (MP4 leaves it 0, and players need it to seek). */
    fun fromMp4(init: ByteArray, segments: Sequence<ByteArray>, out: OutputStream, totalSamples: Long = 0) {
        val blocks = dfLaBlocks(init).toMutableList()
        if (totalSamples > 0) blocks[0] = Block(0, withTotalSamples(blocks[0].data, totalSamples))
        writeHeader(out, blocks)
        for (seg in segments) for (frames in mdat(seg)) out.write(frames)
    }

    private fun withTotalSamples(si: ByteArray, n: Long): ByteArray {
        val o = si.copyOf()
        o[13] = ((o[13].toInt() and 0xf0) or ((n shr 32).toInt() and 0x0f)).toByte()
        o[14] = (n shr 24).toByte(); o[15] = (n shr 16).toByte(); o[16] = (n shr 8).toByte(); o[17] = n.toByte()
        return o
    }

    // ---- reading and writing metadata ----

    private fun readBlocks(b: ByteArray): List<Block> {
        val out = ArrayList<Block>()
        var p = 0
        while (p + 4 <= b.size) {
            val last = b[p].toInt() and 0x80 != 0
            val type = b[p].toInt() and 0x7f
            val len = ((b[p + 1].toInt() and 0xff) shl 16) or ((b[p + 2].toInt() and 0xff) shl 8) or (b[p + 3].toInt() and 0xff)
            out += Block(type, b.copyOfRange(p + 4, p + 4 + len))
            p += 4 + len
            if (last) break
        }
        return out
    }

    private fun writeHeader(out: OutputStream, blocks: List<Block>) {
        out.write("fLaC".toByteArray())
        blocks.forEachIndexed { i, blk ->
            val len = blk.data.size
            out.write((blk.type and 0x7f) or (if (i == blocks.lastIndex) 0x80 else 0))
            out.write(byteArrayOf((len shr 16).toByte(), (len shr 8).toByte(), len.toByte()))
            out.write(blk.data)
        }
    }

    /** A FLAC file's metadata blocks; the stream is left at the first audio frame. */
    fun readHeader(input: InputStream): List<Block> {
        val d = DataInputStream(input)
        val magic = ByteArray(4).also { d.readFully(it) }
        require(String(magic) == "fLaC") { "not a FLAC file" }
        val out = ArrayList<Block>()
        while (true) {
            val h = d.readUnsignedByte()
            val len = (d.readUnsignedByte() shl 16) or (d.readUnsignedByte() shl 8) or d.readUnsignedByte()
            out += Block(h and 0x7f, ByteArray(len).also { d.readFully(it) })
            if (h and 0x80 != 0) break
        }
        return out
    }

    /** The Vorbis comments of a FLAC's blocks, keys uppercased. */
    fun comments(blocks: List<Block>): Map<String, List<String>> {
        val vc = blocks.firstOrNull { it.type == 4 }?.data ?: return emptyMap()
        val bb = ByteBuffer.wrap(vc).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(4 + bb.int)
        val n = bb.int
        val out = LinkedHashMap<String, MutableList<String>>()
        repeat(n) {
            val len = bb.int
            val kv = String(vc, bb.position(), len, Charsets.UTF_8)
            bb.position(bb.position() + len)
            val k = kv.substringBefore('=').uppercase()
            out.getOrPut(k) { mutableListOf() } += kv.substringAfter('=')
        }
        return out
    }

    fun picture(blocks: List<Block>): ByteArray? {
        val p = blocks.firstOrNull { it.type == 6 }?.data ?: return null
        val bb = ByteBuffer.wrap(p)
        bb.int
        bb.position(bb.position() + bb.int)       // mime
        bb.position(bb.position() + bb.int)       // description
        bb.position(bb.position() + 16)           // size, depth, colours
        val len = bb.int
        return p.copyOfRange(bb.position(), bb.position() + len)
    }

    private fun vorbisComment(tags: Map<String, List<String>>): ByteArray {
        val o = ByteArrayOutputStream()
        val le = { n: Int -> o.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array()) }
        val vendor = "Omarchy Music".toByteArray()
        le(vendor.size); o.write(vendor)
        val entries = tags.flatMap { (k, vs) -> vs.filter { it.isNotEmpty() }.map { "${k.lowercase()}=$it".toByteArray() } }
        le(entries.size)
        entries.forEach { le(it.size); o.write(it) }
        return o.toByteArray()
    }

    private fun pictureBlock(jpeg: ByteArray): ByteArray {
        val mime = "image/jpeg".toByteArray()
        val bb = ByteBuffer.allocate(32 + mime.size + jpeg.size)
        bb.putInt(3)                     // front cover
        bb.putInt(mime.size).put(mime)
        bb.putInt(0)                     // no description
        bb.putInt(0).putInt(0).putInt(0).putInt(0)
        bb.putInt(jpeg.size).put(jpeg)
        return bb.array()
    }

    /** Copy the FLAC at src to out with these tags (replacing its own) and cover. */
    fun retag(src: File, out: OutputStream, tags: Map<String, List<String>>, cover: ByteArray?, keepOther: Boolean = false) {
        src.inputStream().buffered().use { input ->
            val blocks = readHeader(input)
            val merged = if (keepOther) comments(blocks).toMutableMap().apply { putAll(tags) } else tags
            val keep = blocks.filter { it.type == 0 || it.type == 3 || it.type == 2 }   // STREAMINFO, SEEKTABLE, APPLICATION
            val pic = cover ?: if (keepOther) picture(blocks) else null
            writeHeader(out, keep + Block(4, vorbisComment(merged)) + listOfNotNull(pic?.let { Block(6, pictureBlock(it)) }))
            input.copyTo(out, 1 shl 16)
        }
    }

    /** The sample format of a FLAC's STREAMINFO: (bits, sample rate). */
    fun format(blocks: List<Block>): Pair<Int, Int> {
        val si = blocks.first { it.type == 0 }.data
        val rate = ((si[10].toInt() and 0xff) shl 12) or ((si[11].toInt() and 0xff) shl 4) or ((si[12].toInt() and 0xf0) shr 4)
        val bits = (((si[12].toInt() and 0x01) shl 4) or ((si[13].toInt() and 0xf0) shr 4)) + 1
        return bits to rate
    }
}
