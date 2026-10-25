package org.omarchy.music

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/** The songs in your own folders on the phone (Music/ to start; more in the accounts
 *  menu), from Android's media library. The app's own downloads folder is left out:
 *  those songs are in the library already. A song that turns up after the first scan
 *  was added now; on the first scan, when it was last written. */
class LocalFiles(private val ctx: Context) {
    private val index = File(ctx.cacheDir, "local.json")
    private val ser = MapSerializer(String.serializer(), Seen.serializer())

    @Serializable
    data class Seen(val modified: Long, val added: Double)

    @Volatile var files: List<Track> = emptyList()
        private set
    @Volatile var scanning = false
    var scanned = 0.0

    /** How many songs sit right in each folder on the phone, as "Music/…/": for picking. */
    fun folderCounts(): Map<String, Int> {
        val out = HashMap<String, Int>()
        query { _, _, path, _ ->
            val p = path.trimEnd('/') + "/"
            if (!p.startsWith(DOWNLOADS)) out[p] = (out[p] ?: 0) + 1
        }
        return out
    }

    /** Read the media library again (blocking). Returns whether anything changed. */
    fun scan(folders: List<String>): Boolean {
        val seen = readText(index)?.let { runCatching { JSON.decodeFromString(ser, it) }.getOrNull() } ?: emptyMap()
        val first = seen.isEmpty()
        val keep = HashMap<String, Seen>()
        val out = ArrayList<Track>()
        query { c, uri, path, modified ->
            if (path.startsWith(DOWNLOADS) || folders.none { path.startsWith(it) }) return@query
            val old = seen[uri]
            val added = old?.added ?: if (first) modified.toDouble() else now()
            keep[uri] = Seen(modified, added)
            val title = c.getString(c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)) ?: "Unknown"
            val artist = c.getString(c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST))
                ?.takeIf { it != MediaStore.UNKNOWN_STRING } ?: "Unknown artist"
            val album = c.getString(c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)) ?: ""
            val dur = c.getLong(c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)) / 1000.0
            out += Track(id = "f" + uri.hashCode().toUInt().toString(16), source = "file", title = title, artist = artist,
                album = album, duration = dur, file = uri, added = added)
        }
        val changed = out.map { it.file } != files.map { it.file }
        files = out
        if (keep.keys != seen.keys) writeAtomic(index, JSON.encodeToString(ser, keep))
        scanned = now()
        return changed
    }

    private fun query(each: (android.database.Cursor, String, String, Long) -> Unit) {
        val cols = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DATE_MODIFIED)
        ctx.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE")?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val pathCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
            val modCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            while (c.moveToNext()) {
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(idCol)).toString()
                each(c, uri, c.getString(pathCol) ?: "", c.getLong(modCol))
            }
        }
    }

    companion object {
        const val DOWNLOADS = "Music/Omarchy Music/"   // where this app saves songs
    }
}
