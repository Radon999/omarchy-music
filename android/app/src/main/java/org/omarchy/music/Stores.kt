package org.omarchy.music

import android.content.Context
import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

// The app's files, in the same shapes as the desktop's: library.json (songs saved here),
// missing.json, playlists.json, plays.json and state.json, in the app's private folder.

fun writeAtomic(file: File, text: String) {
    file.parentFile?.mkdirs()
    val tmp = File(file.path + ".tmp")
    tmp.writeText(text)
    tmp.renameTo(file)
}

fun readText(file: File): String? = try { file.readText() } catch (_: Exception) { null }

/** Whether a content:// (or file) address still opens: songs deleted by hand drop out. */
fun uriExists(ctx: Context, uri: String): Boolean = try {
    if (uri.startsWith("/")) File(uri).exists()
    else ctx.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
} catch (_: Exception) {
    false
}

/** A saved song: the track, where its FLAC is, and what the tagger learnt. */
@Serializable
data class LibEntry(
    val id: String, val source: String = "ytm", val title: String = "", val artist: String = "", val album: String = "",
    val duration: Double = 0.0, val thumb: String = "", val isrc: String? = null, val apple: String? = null,
    val spotify: String? = null, val file: String = "", val art: String = "", val added: Double = 0.0,
    val format: String = "", val quality: String = "", val tidal: Long? = null, val plays: Int = 0,
    val last: Double = 0.0, val tagged: Int = 0, val genre: String = "",
) {
    fun track() = Track(id = id, source = source, title = title, artist = artist, album = album, duration = duration,
        thumb = thumb, isrc = isrc, tidal = tidal, apple = apple, spotify = spotify, added = added)
}

class Library(private val ctx: Context) {
    private val file = File(ctx.filesDir, "library.json")
    private val missingFile = File(ctx.filesDir, "missing.json")
    val artDir = File(ctx.filesDir, "art").apply { mkdirs() }
    private val ser = MapSerializer(String.serializer(), LibEntry.serializer())
    private val missingSer = MapSerializer(String.serializer(), Double.serializer())
    val items: MutableMap<String, LibEntry> =
        readText(file)?.let { runCatching { JSON.decodeFromString(ser, it) }.getOrNull() }?.toMutableMap() ?: mutableMapOf()
    private val missing: MutableMap<String, Double> =
        readText(missingFile)?.let { runCatching { JSON.decodeFromString(missingSer, it) }.getOrNull() }?.toMutableMap()
            ?: mutableMapOf()

    /** Drop the songs whose file was deleted by hand (off the main thread: it opens each). */
    @Synchronized
    fun prune() {
        val gone = items.filterValues { !uriExists(ctx, it.file) }.keys
        if (gone.isNotEmpty()) {
            gone.forEach { items.remove(it) }
            save()
        }
    }

    @Synchronized
    fun save() = writeAtomic(file, JSON.encodeToString(ser, items))

    fun has(id: String) = items.containsKey(id)
    fun file(id: String) = items[id]?.file
    fun isFlac(id: String) = items[id]?.format == "flac"

    @Synchronized
    fun add(t: Track, path: String, art: String?, format: String, quality: String = "", tidal: Long? = null,
            isrc: String? = null, extra: LibEntry? = null) {
        val old = items[t.id]
        items[t.id] = LibEntry(
            id = t.id, source = t.source, title = t.title, artist = t.artist, album = t.album, duration = t.duration,
            thumb = t.thumb, isrc = isrc ?: t.isrc, apple = t.apple, spotify = t.spotify, file = path, art = art ?: "",
            added = now(), format = format, quality = quality.ifEmpty { extra?.quality ?: "" }, tidal = tidal ?: t.tidal,
            plays = old?.plays ?: 0, last = old?.last ?: 0.0, tagged = extra?.tagged ?: 0, genre = extra?.genre ?: "",
        )
        save()
    }

    @Synchronized
    fun update(id: String, f: (LibEntry) -> LibEntry) {
        items[id]?.let { items[id] = f(it); save() }
    }

    /** The saved FLAC of Tidal track tidalId, whichever service it was saved from. */
    fun byTidal(tidalId: Long): LibEntry? = items.values.firstOrNull { it.tidal == tidalId && it.format == "flac" }

    @Synchronized
    fun played(id: String) {
        items[id]?.let { items[id] = it.copy(plays = it.plays + 1, last = now()); save() }
    }

    /** Forget a saved song and delete its file (unless another service's entry shares it). */
    @Synchronized
    fun remove(id: String) {
        val t = items.remove(id) ?: return
        val shared = items.values.flatMap { listOf(it.file, it.art) }.toSet()
        if (t.file !in shared) runCatching {
            if (t.file.startsWith("/")) File(t.file).delete() else ctx.contentResolver.delete(Uri.parse(t.file), null, null)
        }
        if (t.art.isNotEmpty() && t.art !in shared) File(t.art).delete()
        save()
    }

    @Synchronized
    fun markMissing(id: String, isMissing: Boolean = true) {
        if (isMissing) missing[id] = now() else if (missing.remove(id) == null) return
        writeAtomic(missingFile, JSON.encodeToString(missingSer, missing))
    }

    fun recentlyMissing(id: String) = now() - (missing[id] ?: 0.0) < RETRY_MISSING

    fun tracks(): List<LibEntry> = items.values.sortedWith(compareByDescending<LibEntry> { it.last }.thenByDescending { it.added })

    companion object {
        const val RETRY_MISSING = 7 * 86400   // look again for songs Tidal didn't have after a week
    }
}

/** Seconds of a song (or half of a shorter one) before it counts as played. */
const val PLAY_AFTER = 30

@Serializable
data class PlayEntry(val plays: Int = 0, val last: Double = 0.0, val track: Track)

/** What you've played here, when last and how often: Recently played and Most played. */
class Plays(ctx: Context) {
    private val file = File(ctx.filesDir, "plays.json")
    private val ser = MapSerializer(String.serializer(), PlayEntry.serializer())
    val items: MutableMap<String, PlayEntry> =
        readText(file)?.let { runCatching { JSON.decodeFromString(ser, it) }.getOrNull() }?.toMutableMap() ?: mutableMapOf()

    @Synchronized
    fun add(t: Track) {
        val e = items[t.id]
        items[t.id] = PlayEntry((e?.plays ?: 0) + 1, now(), t.slim())
        writeAtomic(file, JSON.encodeToString(ser, items))
    }

    fun recent(n: Int = 200) = items.values.sortedByDescending { it.last }.take(n).map { it.track }
    fun top(n: Int = 200) = items.values.sortedWith(compareByDescending<PlayEntry> { it.plays }.thenByDescending { it.last })
        .take(n).map { it.track.copy(plays = it.plays) }
}

@Serializable
data class Playlist(val id: String, val name: String, val created: Double, val updated: Double, val tracks: List<Track> = emptyList())

/** A playlist in a folder: one of yours (kind "mine") or a service's (kind "service", with the service's key). */
@Serializable
data class FolderItem(val kind: String, val source: String = "", val id: String, val title: String = "", val thumb: String = "") {
    val key get() = "$kind:$source:$id"

    companion object {
        fun mine(p: Playlist) = FolderItem("mine", "", p.id, p.name)
        fun service(a: Album, source: String) = FolderItem("service", source, a.id, a.title, a.thumb)
    }
}

@Serializable
data class PlaylistFolder(val id: String, val name: String, val playlists: List<FolderItem> = emptyList())

@Serializable
data class SongSpeed(val speed: Double = 1.0, val pitch: Boolean = false)

/** The speed you picked for songs (Speed…), kept by song so the same song from any service plays
 *  at it; the desktop's song-speeds.json. pitch: whether the pitch moves with the speed (the way a
 *  sped-up edit sounds) or stays where it is. */
class SongSpeeds(private val file: File) {
    private val ser = MapSerializer(String.serializer(), SongSpeed.serializer())
    var items: Map<String, SongSpeed> = readText(file)?.let { runCatching { JSON.decodeFromString(ser, it) }.getOrNull() } ?: emptyMap()
        private set

    fun get(t: Track): SongSpeed = items[key(t)] ?: SongSpeed()

    @Synchronized
    fun set(tracks: List<Track>, speed: Double, pitch: Boolean = false) {
        val s = Math.round(speed.coerceIn(LOW, HIGH) * 100) / 100.0
        items = items.toMutableMap().apply { tracks.forEach { if (s == 1.0) remove(key(it)) else put(key(it), SongSpeed(s, pitch)) } }
        writeAtomic(file, JSON.encodeToString(ser, items))
    }

    companion object {
        const val LOW = 0.5
        const val HIGH = 2.0
        fun key(t: Track) = songKey(t).let { (a, b) -> "$a|$b" }
    }
}

/** Folders you name in the Library, each holding playlists: yours and your services'. A playlist is in a folder once. */
class PlaylistFolders(private val file: File) {
    private val ser = ListSerializer(PlaylistFolder.serializer())
    var items: List<PlaylistFolder> = readText(file)?.let { runCatching { JSON.decodeFromString(ser, it) }.getOrNull() } ?: emptyList()
        private set

    private fun save() = writeAtomic(file, JSON.encodeToString(ser, items))
    fun all() = items.sortedBy { norm(it.name) }
    fun get(id: String) = items.firstOrNull { it.id == id }

    @Synchronized
    fun create(name: String): PlaylistFolder = PlaylistFolder(randomHex(6), name).also { items = items + it; save() }

    @Synchronized
    fun rename(id: String, name: String) { items = items.map { if (it.id == id) it.copy(name = name) else it }; save() }

    @Synchronized
    fun delete(id: String) { items = items.filter { it.id != id }; save() }

    /** Put a playlist in the folder; false if it's there already. */
    @Synchronized
    fun add(id: String, item: FolderItem): Boolean {
        val f = get(id) ?: return false
        if (f.playlists.any { it.key == item.key }) return false
        items = items.map { if (it.id == id) it.copy(playlists = it.playlists + item) else it }
        save()
        return true
    }

    @Synchronized
    fun remove(id: String, key: String) { items = items.map { if (it.id == id) it.copy(playlists = it.playlists.filter { p -> p.key != key }) else it }; save() }

    /** A playlist of yours was deleted: out of every folder. */
    @Synchronized
    fun forget(key: String) { items = items.map { it.copy(playlists = it.playlists.filter { p -> p.key != key }) }; save() }
}

/** The playlists you make here. Each song is in a playlist once; adding it again is a no-op. */
class Playlists(ctx: Context) {
    private val file = File(ctx.filesDir, "playlists.json")
    private val ser = ListSerializer(Playlist.serializer())
    var items: MutableList<Playlist> =
        readText(file)?.let { runCatching { JSON.decodeFromString(ser, it) }.getOrNull() }?.toMutableList() ?: mutableListOf()
        private set

    private fun save() = writeAtomic(file, JSON.encodeToString(ser, items))
    fun all() = items.sortedByDescending { it.updated }
    fun get(id: String) = items.firstOrNull { it.id == id }

    @Synchronized
    fun create(name: String): Playlist {
        val pl = Playlist(randomHex(6), name, now(), now())
        items += pl
        save()
        return pl
    }

    @Synchronized
    fun rename(id: String, name: String) = edit(id) { it.copy(name = name, updated = now()) }

    @Synchronized
    fun delete(id: String) {
        items = items.filter { it.id != id }.toMutableList()
        save()
    }

    /** Add tracks to the end; returns how many weren't in it already. */
    @Synchronized
    fun add(id: String, tracks: List<Track>): Int {
        val pl = get(id) ?: return 0
        val have = pl.tracks.map { it.id }.toMutableSet()
        val new = tracks.filter { have.add(it.id) }.map { it.slim() }
        if (new.isNotEmpty()) edit(id) { it.copy(tracks = it.tracks + new, updated = now()) }
        return new.size
    }

    @Synchronized
    fun remove(id: String, ids: Set<String>) = edit(id) { p -> p.copy(tracks = p.tracks.filter { it.id !in ids }, updated = now()) }

    /** Songs ids, renamed by f in every playlist they're in (their tags were edited). */
    @Synchronized
    fun retitle(ids: Set<String>, f: (Track) -> Track) {
        if (items.none { p -> p.tracks.any { it.id in ids } }) return
        items = items.map { p -> p.copy(tracks = p.tracks.map { if (it.id in ids) f(it) else it }) }.toMutableList()
        save()
    }

    private fun edit(id: String, f: (Playlist) -> Playlist) {
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) {
            items[i] = f(items[i])
            save()
        }
    }
}
