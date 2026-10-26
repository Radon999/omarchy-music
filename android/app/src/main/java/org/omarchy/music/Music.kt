package org.omarchy.music

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/** The app: your queue and what's playing, your music, the services and the settings.
 *  The phone's counterpart of the desktop's MusicApp, with the same rules (what plays
 *  next, what counts as played, what downloads when). Lives as long as the process;
 *  everything here runs on the main thread, slow work goes to [bg]. */
@SuppressLint("StaticFieldLeak")
object Music {
    lateinit var ctx: Context
        private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var lib: Library
    lateinit var plays: Plays
    lateinit var lists: Playlists
    lateinit var playlistFolders: PlaylistFolders   // Library folders of playlists
    lateinit var speeds: SongSpeeds
    lateinit var local: LocalFiles
    lateinit var tidal: Tidal
    lateinit var dl: Downloader
    val eqProcessor = EqProcessor()

    /** The player, while the playback service runs. */
    var player: Player? = null
        set(value) {
            field = value
            if (value != null) pendingLoad?.let { pendingLoad = null; it() }
        }
    private var pendingLoad: (() -> Unit)? = null

    // ---- what the screens show ----
    var queue by mutableStateOf(listOf<Track>())
    var index by mutableStateOf(-1)
    var paused by mutableStateOf(true)
    var position by mutableStateOf(0.0)
    var duration by mutableStateOf(0.0)
    var repeat by mutableStateOf("off")
    var source by mutableStateOf("ytm")
    var status by mutableStateOf("")
    var liked by mutableStateOf(setOf<String>())
    var musicVersion by mutableStateOf(0)      // bumps when your songs, playlists or plays change
    var savedVersion by mutableStateOf(0)      // bumps when downloads change
    var accountsVersion by mutableStateOf(0)   // bumps when a sign-in changes
    var syncSources = mutableStateMapOf<String, Boolean>()
    var keepPlayed by mutableStateOf(false)
    var artColors by mutableStateOf(true)
    var folders by mutableStateOf(listOf("Music/"))
    var eq by mutableStateOf(EqSettings())
    var lyricsMode by mutableStateOf("genius")
    var musicSort by mutableStateOf("title")   // how the Music tab's All is sorted: title, artist, album, added
    var searchIn by mutableStateOf("tidal")    // where the search bar searches: tidal, ytm, local
    var apiKeys by mutableStateOf(mapOf<String, String>())   // Settings: keys of yours (see API_KEYS)
    var shuffled = false   // the queue was shuffled: repeat-all shuffles it again
    var playlistSort by mutableStateOf("order")   // how a playlist's songs are listed (PLAYLIST_SORTS)
    var browseLocal by mutableStateOf(false)   // Local picked in the source bar: your own songs, no Library tab
    private var searchBeforeLocal = "tidal"     // where the search looked before
    var importing by mutableStateOf<String?>(null)   // an .m3u8 being read: how far
    var streaming by mutableStateOf(false)
    var downloads by mutableStateOf(listOf<Track>())
    var syncing by mutableStateOf(false)
    var syncTracks by mutableStateOf(listOf<Track>())
    val toasts = Channel<String>(Channel.BUFFERED)
    var shared by mutableStateOf<String?>(null)   // a link shared to the app, for Search
    var dj by mutableStateOf(Dj.State())          // the DJ: on, its sets' kinds and lines, the one shown
    var djBusy by mutableStateOf<String?>(null)   // the DJ picking a set: what it's doing

    val sources = mutableStateMapOf<String, Source>()
    val sourceErrors = mutableStateMapOf<String, String>()
    private val likes = HashMap<String, Set<String>>()

    private var radioFrom: String? = null
    private var context = 0
    private var loadSeq = 0
    private var streamRetry = -1
    private var ended = false
    private var restorePending = false
    private var downloading = false
    var downloadNote by mutableStateOf("")   // "Downloading in FLAC · 3 of 40" while a Download runs
        private set
    private var syncAgain = false

    private class Listen(val id: String, val track: Track, var played: Double = 0.0, var counted: Boolean = false,
                         var sent: Boolean = true, val start: Double = now())
    private var listen: Listen? = null

    fun init(c: Context) {
        if (::ctx.isInitialized) return
        ctx = c.applicationContext
        lib = Library(ctx)
        plays = Plays(ctx)
        lists = Playlists(ctx)
        playlistFolders = PlaylistFolders(File(ctx.filesDir, "playlist-folders.json"))
        speeds = SongSpeeds(File(ctx.filesDir, "song-speeds.json"))
        local = LocalFiles(ctx)
        tidal = Tidal(ctx)
        dl = Downloader(ctx, lib, tidal)
        loadState()
        if (tidal.connected) sources["tidal"] = tidal
        bg({ lib.prune() }) { _, _ -> savedVersion++ }
        rescan()
        scope.launch {
            while (true) {   // the position, and what counts as played
                val p = player
                if (p != null && p.isPlaying) {
                    val now = p.currentPosition / 1000.0
                    val step = now - position
                    position = now
                    if (step in 0.0..1.5) listened(step)
                    p.duration.takeIf { it > 0 }?.let { duration = it / 1000.0 }
                }
                delay(500)
            }
        }
    }

    fun toast(text: String) { toasts.trySend(text) }

    /** Run work off the main thread; hand (result, error) to done on it. */
    fun <T> bg(work: () -> T, done: (T?, Throwable?) -> Unit = { _, _ -> }) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching(work) }
            done(r.getOrNull(), r.exceptionOrNull())
        }
    }

    // ---- remembering ----
    @Serializable
    private data class State(
        val queue: List<Track> = emptyList(), val index: Int = -1, val radio_from: String? = null,
        val repeat: String = "off", val eq: EqSettings = EqSettings(), val source: String = "ytm",
        val sync_sources: Map<String, Boolean> = emptyMap(), val lyrics_mode: String = "genius",
        val folders: List<String> = listOf("Music/"), val art_colors: Boolean = true, val keep_played: Boolean = false,
        val music_sort: String = "title", val search_in: String = "tidal", val dj: Dj.State = Dj.State(),
        val api_keys: Map<String, String> = emptyMap(), val shuffled: Boolean = false, val playlist_sort: String = "order",
        val browse_local: Boolean = false, val search_before_local: String = "tidal",
    )

    private val stateFile get() = File(ctx.filesDir, "state.json")

    private fun loadState() {
        val st = readText(stateFile)?.let { runCatching { JSON.decodeFromString(State.serializer(), it) }.getOrNull() } ?: State()
        queue = st.queue
        index = st.index.coerceAtMost(queue.size - 1)
        radioFrom = st.radio_from
        repeat = st.repeat.takeIf { it in setOf("off", "all", "one") } ?: "off"
        source = st.source.takeIf { it in SOURCES } ?: "ytm"
        // Nothing downloads on its own until you switch a service's sync on.
        SOURCES.keys.forEach { syncSources[it] = st.sync_sources[it] ?: false }
        lyricsMode = st.lyrics_mode
        folders = st.folders
        artColors = st.art_colors
        keepPlayed = st.keep_played
        musicSort = st.music_sort
        searchIn = st.search_in.takeIf { it in SEARCH_IN } ?: "tidal"
        dj = st.dj
        apiKeys = st.api_keys.filterKeys { it in API_KEYS }
        shuffled = st.shuffled
        playlistSort = st.playlist_sort.takeIf { it in PLAYLIST_SORTS } ?: "order"
        browseLocal = st.browse_local
        searchBeforeLocal = st.search_before_local.takeIf { it in SEARCH_IN && it != "local" } ?: "tidal"
        eq = st.eq
        eqProcessor.update(eq)
        val t = current()
        if (t != null && (lib.has(t.id) || t.isFile)) loadCurrent(autoplay = false)
        else if (t != null) restorePending = true   // it has to come from Tidal: wait for the player and the login
    }

    fun saveState() {
        val st = State(queue, index, radioFrom, repeat, eq, source, syncSources.toMap(), lyricsMode, folders, artColors, keepPlayed, musicSort, searchIn, dj, apiKeys, shuffled, playlistSort,
            browseLocal, searchBeforeLocal)
        bg({ writeAtomic(stateFile, JSON.encodeToString(State.serializer(), st)) })
    }

    fun saveApiKeys(keys: Map<String, String>) {
        apiKeys = keys.mapValues { it.value.trim() }.filter { it.key in API_KEYS && it.value.isNotEmpty() }
        saveState()
    }

    /** Called once the playback service is up: a song restored from Tidal can load now. */
    fun playerReady() {
        if (restorePending) {
            restorePending = false
            loadCurrent(autoplay = false)
        }
    }

    // ---- your music ----
    /** Every song you have: downloaded here (once each) and in your folders. */
    fun mySongs(): List<Track> {
        val seen = HashSet<String>()
        val saved = lib.items.values.sortedByDescending { it.added }.filter { seen.add(it.file) }.map { it.track() }
        return saved + local.files.filter { it.file !in seen }
    }

    /** Your songs by the artist called name: saved, in your folders or playlists, or played. */
    fun songsBy(name: String): List<Track> {
        val want = norm(name)
        val seen = HashSet<Pair<String, String>>()
        return (mySongs() + lists.items.flatMap { it.tracks } + plays.recent(2000)).filter { t ->
            want in splitArtists(t.artist).map { norm(it) } && seen.add(songKey(t))
        }
    }

    /** The artist's songs you played or have, each with how often you played it (every play added
     *  up: see [Stats]): the counted ones, then the rest of yours. */
    fun artistSongs(name: String): List<Track> {
        val mine = songIndex()
        val seen = HashSet<Pair<String, String>>()
        val out = ArrayList<Track>()
        for (s in Stats.artistSongs(name)) {
            val t = fmSong(s.title, s.artist, s.thumb, mine).copy(plays = s.plays)
            if (seen.add(songKey(t))) out += t
        }
        val here = plays.items.values.associate { songKey(it.track) to it.plays }
        for (t in songsBy(name)) if (seen.add(songKey(t))) out += t.copy(plays = here[songKey(t)] ?: 0)
        return out
    }

    var artistsVersion by mutableStateOf(0)   // bumps when an artist's picture or bio comes in
        private set
    private val lookingUp = HashSet<String>()

    /** Look up the pictures and bios of the artists named, one after another off the main thread. */
    fun lookUpArtists(names: List<String>) {
        val todo = names.filter { Pictures.known(it) == null && synchronized(lookingUp) { lookingUp.add(norm(it)) } }
        if (todo.isEmpty()) return
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            for (n in todo) {
                runCatching { Pictures.artist(n) }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { artistsVersion++ }
            }
        }
    }

    /** Keep source (a picture's web address, or a content:// one you picked) as the artist's picture. */
    fun setArtistPicture(name: String, source: String) {
        bg({
            val url = if (source.startsWith("content://")) {
                val f = File(File(ctx.filesDir, "artists").apply { mkdirs() }, "${randomHex(8)}.jpg")
                ctx.contentResolver.openInputStream(android.net.Uri.parse(source))!!.use { input -> f.outputStream().use { input.copyTo(it) } }
                f.path
            } else source
            Pictures.chooseArtist(name, url)
        }) { _, err ->
            toast(if (err != null) "Couldn't use it: ${err.message?.take(100)}" else "New picture for $name")
            artistsVersion++
        }
    }

    /** (blocking) The artist's page from the first service that knows them. */
    fun artistInfo(name: String, prefer: String?): ArtistInfo? {
        var info: ArtistInfo? = null
        var last: Throwable? = null
        val names = listOf(name) + (if (" & " in name) listOf(name.split(" & ")[0]) else emptyList())
        for (key in listOfNotNull(prefer, source, "ytm", "tidal", "apple", "spotify").distinct()) {
            val src = sources[key] ?: continue
            if (key in setOf("tidal", "spotify") && !src.signedIn) continue
            var found: ArtistInfo? = null
            for (n in names) {
                found = try { src.artist(n) } catch (e: Exception) { last = e; null }
                if (found != null) break
            }
            found ?: continue
            if (info == null) info = found.copy(source = key, topSource = key)
            else if (found.top.isNotEmpty()) info = info.copy(top = found.top, topSource = key)
            if (info.top.isNotEmpty()) break
        }
        if (info == null && last != null) throw last!!
        return info
    }

    // ---- most played ----
    private var mostPlayedFor: List<Stats.Song>? = null
    private var mostPlayedMade = listOf<Track>()

    /** The Music tab's Most played: every play added up (see [Stats]): Last.fm's, your Spotify
     *  history's and, without Last.fm, the plays here. Until the counts are in, the plays here stand in. */
    fun mostPlayed(): List<Track> {
        val counts = Stats.songs
        if (counts.isEmpty()) return plays.top()
        if (counts !== mostPlayedFor) {
            val mine = songIndex()
            mostPlayedMade = counts.take(500).map { fmSong(it.title, it.artist, it.thumb, mine).copy(plays = it.plays) }
            mostPlayedFor = counts
        }
        return mostPlayedMade
    }
    val mostPlayedFromStats get() = Stats.songs.isNotEmpty() && (LastFm.connected || SpotifyHistory.count > 0)

    /** Song key to your copy of it: saved, in your folders, or played here. */
    fun songIndex(): Map<Pair<String, String>, Track> {
        val mine = HashMap<Pair<String, String>, Track>()
        for (t in plays.items.values.map { it.track } + mySongs()) mine[songKey(t)] = t   // yours on disk win over ones only played
        return mine
    }

    /** A song Last.fm named: yours (mine: song key to song) when you have it, else one to be
     *  found on Tidal or YouTube Music when it plays. */
    fun fmSong(name: String, artist: String, image: String, mine: Map<Pair<String, String>, Track>): Track =
        mine[songKey(Track("", title = name, artist = artist))] ?: run {
            val sha = java.security.MessageDigest.getInstance("SHA-1").digest("${norm(artist)}|${norm(name)}".toByteArray())
            Track("lfm-" + sha.joinToString("") { "%02x".format(it) }.take(16), "lastfm", name, artist,
                thumb = image.takeIf { LASTFM_BLANK !in it } ?: "")
        }

    // ---- the DJ ----
    /** What the DJ screen shows: the phone's DJ, or the desktop's while it's the remote. */
    fun shownDj(): Dj.Shown {
        if (Remote.active) return Remote.status.dj
        val shown = dj.sets[dj.shown].takeIf { dj.on }
        return Dj.Shown(dj.on, djBusy ?: "", shown?.kind ?: "", shown?.line ?: "", dj.sets.mapValues { it.value.kind }, djFromName(dj.from))
    }

    /** What the DJ plays from, as [Dj.From] keeps it: (name, songs), or null if it's gone. */
    fun djSource(f: Dj.From?): Pair<String, List<Track>>? = when (f?.kind) {
        "playlist" -> lists.get(f.id)?.let { it.name to it.tracks }
        "folder" -> djFromName(f) to mySongs().filter { (local.paths[it.file] ?: "").startsWith(f.path) }
        "mine" -> "your music" to mySongs()
        "songs" -> djFromName(f) to f.songs
        else -> null
    }

    fun djFromName(f: Dj.From?): String = when (f?.kind) {
        null, "all" -> ""
        "playlist" -> lists.get(f.id)?.name ?: "a playlist that's gone"
        "folder" -> f.path.trimEnd('/').substringAfterLast('/').ifEmpty { f.path }
        "mine" -> "your music"
        else -> f.name.ifEmpty { "these songs" }
    }

    /** The DJ playing from what dj.from says (after a restart, say). */
    private fun djReady() {
        if (dj.from == null || Dj.scope != null) return
        djSource(dj.from)?.takeIf { it.second.isNotEmpty() }?.let { Dj.playFrom(it.first, it.second) }
    }

    private fun coming() = queue.drop(maxOf(0, index)).map { songKey(it) }.toSet()

    /** Start the DJ: a first set of what you've had on repeat (or whatever it can make). from: what it
     *  plays from; Dj.From("all") for everything; null for what it played from last. */
    fun djStart(from: Dj.From? = null) {
        if (Remote.active) { Remote.djStart(from); return }
        if (djBusy != null) return
        val src = (from ?: dj.from)?.takeIf { it.kind != "all" }
        if (src != null) {
            val got = djSource(src)
            if (got == null || got.second.isEmpty()) {
                toast("There are no songs in ${got?.first ?: "that"} for the DJ to play")
                return
            }
            Dj.playFrom(got.first, got.second)
        } else Dj.playFrom(null, null)
        djBusy = Dj.scope?.let { "Picking your first set from ${it.first}…" } ?: "Picking your first set…"
        bg({ Dj.build(first = true) }) { res, err ->
            djBusy = null
            if (res == null) {
                toast(if (err == null) "The DJ couldn't find songs yet. Play a few, or connect Last.fm." else "The DJ couldn't start: ${err.message?.take(100)}")
                return@bg
            }
            dj = Dj.State(from = src)
            playFrom(djTag(res), 0, radio = false)
            dj = dj.copy(on = true)
            djAnnounce(current())
        }
    }

    fun djStop() {
        if (Remote.active) { Remote.cmd("dj", "do" to "stop"); return }
        dj = dj.copy(on = false)
        saveState()
    }

    /** The set's songs marked as set n of the DJ's (n: the next number), with its line. */
    private fun djTag(b: Dj.Built): List<Track> {
        val n = (dj.sets.keys.mapNotNull { it.toIntOrNull() }.maxOrNull() ?: 0) + 1
        var sets = dj.sets + (n.toString() to Dj.Info(b.kind, b.line))
        if (sets.size > 30) sets = sets - sets.keys.minBy { it.toInt() }
        dj = dj.copy(sets = sets)
        Dj.used += b.songs.map { songKey(it) }
        return b.songs.map { it.copy(dj = n) }
    }

    /** A set begins: its line, where you see it. */
    private fun djAnnounce(t: Track?) {
        val n = t?.dj?.toString() ?: return
        val set = dj.sets[n]
        if (!dj.on || set == null || n == dj.shown) return
        dj = dj.copy(shown = n)
        toast("DJ · ${set.line}")
    }

    /** Near the end of the queue: the DJ's next set after it. */
    private fun djMore() {
        if (djBusy != null) return
        djReady()
        djBusy = "Picking the next set…"
        val ctxNow = context
        val avoid = djKind()
        val coming = coming()
        bg({ Dj.build(avoid = avoid, coming = coming) }) { res, _ ->
            djBusy = null
            if (res != null && ctxNow == context && dj.on) {
                queue = queue + djTag(res)
                afterQueueChange()
            }
        }
    }

    private fun djKind() = dj.sets[current()?.dj.toString()]?.kind

    /** Switch it up (or play what you asked for): a new set now, in place of the rest of this one. */
    fun djSwitch(ask: String? = null) {
        if (Remote.active) { if (ask != null) Remote.cmd("dj", "do" to "ask", "text" to ask) else Remote.cmd("dj", "do" to "switch"); return }
        if (djBusy != null) return
        if (!dj.on && ask == null) { djStart(); return }
        djReady()
        djBusy = if (ask != null) "Looking for ${ask.trim()}…" else "Switching it up…"
        val avoid = djKind()
        val coming = coming()
        bg({ if (ask != null) Dj.ask(ask) else Dj.build(avoid = avoid, coming = coming) }) { res, err ->
            djBusy = null
            if (res == null) {
                toast(if (ask != null) "The DJ found nothing for “${ask.trim()}”. Try a mood, a decade, a genre or an artist."
                      else "The DJ has nothing else right now" + (err?.let { ": ${it.message?.take(80)}" } ?: "."))
                return@bg
            }
            if (!dj.on) dj = Dj.State(on = true, from = dj.from)
            val songs = djTag(res)
            val queued = queue.drop(index + 1).filter { it.queued }
            val keep = if (current() != null) queue.take(index + 1) else emptyList()
            queue = keep + queued + songs
            index = keep.size + queued.size
            radioFrom = null
            context++
            loadCurrent()
        }
    }

    // ---- importing an .m3u8 playlist ----
    /** (blocking) The songs of the .m3u/.m3u8 playlist at uri: (name, songs, names not found).
     *  Each is a file on the phone (where the playlist says, else one by the same name in the
     *  same folder), else the same song in your music, else the best match on Tidal (YouTube
     *  Music without it). */
    fun importM3u(uri: android.net.Uri): Triple<String, List<Track>, List<String>> {
        val cr = ctx.contentResolver
        val fileName = runCatching {
            cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Playlist"
        val entries = readM3u(cr.openInputStream(uri)?.use { it.readBytes() } ?: error("couldn't open that file"))
        if (entries.isEmpty()) error("no songs in $fileName")
        // The playlist's own folder, for the paths in it that start from there ("../Album/01.flac").
        val docId = runCatching { android.provider.DocumentsContract.getDocumentId(uri) }.getOrNull() ?: ""
        val here = if (uri.authority == "com.android.externalstorage.documents") docId.substringAfter(':').substringBeforeLast('/', "") else ""
        val phone = runCatching { local.everywhere() }.getOrDefault(emptyMap())
        val byPath = phone.mapKeys { it.key.lowercase() }
        val tails = HashMap<String, Track>()
        for ((p, t) in phone) {
            val parts = p.lowercase().split('/').filter { it.isNotEmpty() }
            tails.putIfAbsent(parts.takeLast(2).joinToString("/"), t)
            tails.putIfAbsent(parts.last(), t)
        }
        val mine = mySongs()
        val byKey = HashMap<Pair<String, String>, Track>()
        for (t in phone.values + mine) byKey.putIfAbsent(songKey(t), t)
        val src = sources["tidal"]?.takeIf { it.signedIn } ?: sources["ytm"]
        val out = ArrayList<Track>()
        val missed = ArrayList<String>()
        for ((i, e) in entries.withIndex()) {
            importing = "Finding the songs of $fileName… ${i + 1} of ${entries.size}"
            var t: Track? = null
            var artist = e.artist
            var title = e.title
            if (Regex("^https?://").containsMatchIn(e.loc)) {
                val from = linkSource(e.loc)?.let { sources[it] }
                if (from != null) t = runCatching { from.search(e.loc).firstOrNull() }.getOrNull()
            } else {
                var where = (if (e.loc.startsWith("file://")) android.net.Uri.decode(e.loc.substring(7)) else e.loc).replace('\\', '/')
                where = where.replace(Regex("^(/storage/emulated/\\d+|/sdcard|/storage/self/primary)/"), "")
                val full = if (where.startsWith("/") || Regex("^[A-Za-z]:/").containsMatchIn(where)) where
                    else java.io.File("/$here", where).normalize().path.removePrefix("/")
                t = byPath[full.lowercase()]
                if (t == null) {
                    val parts = where.lowercase().split('/').filter { it.isNotEmpty() }
                    t = tails[parts.takeLast(2).joinToString("/")] ?: tails[parts.lastOrNull() ?: ""]
                }
                if (title.isEmpty()) { val (a, b) = namesFromFile(where); artist = a; title = b }   // no #EXTINF
            }
            if (t == null && title.isNotEmpty()) t = byKey[songKey(Track("", title = title, artist = artist))]
            if (t == null && title.isNotEmpty() && src != null) {
                val hits = runCatching { src.search("$artist ${bareTitle(title)}".trim()).take(8) }.getOrDefault(emptyList())
                t = bestMatch(hits, artist, title, e.seconds)
            }
            if (t != null) out += t.slim() else missed += if (artist.isNotEmpty()) "$artist - $title" else title.ifEmpty { e.loc }
        }
        return Triple(fileName.substringBeforeLast('.'), out, missed)
    }

    /** Make a playlist of yours from the .m3u8 at uri, named after the file. */
    fun importPlaylist(uri: android.net.Uri) {
        if (importing != null) { toast("Still importing the last one…"); return }
        importing = "Reading the playlist…"
        bg({ importM3u(uri) }) { res, err ->
            importing = null
            if (res == null) { toast("Couldn't import it: ${err?.message?.take(100)}"); return@bg }
            val (name, tracks, missed) = res
            if (tracks.isEmpty()) { toast("None of the ${missed.size} songs in “$name” were found"); return@bg }
            val id = lists.create(name).id
            lists.add(id, tracks)
            musicVersion++
            missed.forEach { android.util.Log.i("omarchy-music", "import $name: not found: $it") }
            toast("Imported “$name”: ${tracks.size} songs" + if (missed.isNotEmpty()) " (${missed.size} not found)" else "")
        }
    }

    private var rescanAgain = false

    fun rescan() {
        if (local.scanning) { rescanAgain = true; return }   // the folders changed mid-scan: read again after
        local.scanning = true
        bg({ local.scan(folders, lib.items.values.mapNotNullTo(HashSet()) { LocalFiles.mediaId(it.file) }) }) { changed, err ->
            local.scanning = false
            if (rescanAgain) { rescanAgain = false; rescan() }
            if (err is SecurityException) Unit   // no permission yet: the Music tab asks
            else if (err != null) toast("Couldn't read your music: ${err.message?.take(80)}")
            if (changed == true) musicVersion++
            accountsVersion++
        }
    }

    fun rescanSoon() { if (now() - local.scanned > 120) rescan() }

    /** Your folders become list; one inside another is dropped, since the outer one has its songs. */
    @JvmName("chooseFolders")
    fun setFolders(list: List<String>) {
        val clean = list.distinct().filter { f -> list.none { it != f && f.startsWith(it) } }.sorted()
        if (clean == folders) return
        folders = clean
        saveState()
        rescan()
    }

    fun removeFolder(path: String) = setFolders(folders - path)

    // ---- your playlists ----
    fun newPlaylist(name: String): String {
        val id = lists.create(name).id
        toast("Made “$name”")
        musicVersion++
        return id
    }

    fun addToPlaylist(id: String, tracks: List<Track>) {
        val pl = lists.get(id) ?: return
        if (tracks.isEmpty()) return
        val n = lists.add(id, tracks)
        toast(when {
            n == 0 -> "Already in “${pl.name}”"
            tracks.size == 1 -> "Added to “${pl.name}”"
            else -> "$n songs added to “${pl.name}”" + if (n < tracks.size) " (${tracks.size - n} were in it)" else ""
        })
        musicVersion++
    }

    fun removeFromPlaylist(id: String, ids: Set<String>) { lists.remove(id, ids); musicVersion++ }
    fun renamePlaylist(id: String, name: String) { lists.rename(id, name); musicVersion++ }
    // ---- folders of playlists ----
    fun newFolder(name: String, item: FolderItem? = null) {
        val f = playlistFolders.create(name)
        if (item != null) { playlistFolders.add(f.id, item); toast("Put “${item.title}” in the new folder “$name”") }
        musicVersion++
    }

    fun renameFolder(id: String, name: String) { playlistFolders.rename(id, name); musicVersion++ }

    fun deleteFolder(id: String) {
        playlistFolders.get(id)?.let { toast("Deleted the folder “${it.name}”") }
        playlistFolders.delete(id)
        musicVersion++
    }

    fun addToFolder(id: String, item: FolderItem) {
        val f = playlistFolders.get(id) ?: return
        toast(if (playlistFolders.add(id, item)) "Put “${item.title}” in “${f.name}”" else "“${item.title}” is in “${f.name}” already")
        musicVersion++
    }

    fun removeFromFolder(id: String, item: FolderItem) { playlistFolders.remove(id, item.key); musicVersion++ }

    fun deletePlaylist(id: String) {
        lists.get(id)?.let { toast("Deleted “${it.name}”") }
        playlistFolders.forget("mine::$id")
        lists.delete(id)
        musicVersion++
    }

    fun playList(tracks: List<Track>, shuffled: Boolean = false) {
        val t = if (shuffled) shuffleSongs(tracks) else tracks
        if (t.isNotEmpty()) { playFrom(t, 0, radio = false); this.shuffled = shuffled }
    }

    // ---- downloads ----
    fun download(tracks: List<Track>) {
        val waiting = downloads.map { it.id }.toSet()
        val new = tracks.filter { !it.isFile && !lib.has(it.id) && it.id !in waiting }
        if (new.isEmpty()) { toast("Those are downloaded already"); return }
        if (!tidal.connected) { toast("Connect Tidal in the accounts menu first: downloads come from there, in FLAC"); return }
        downloads = downloads + new
        toast("Downloading ${new.size} song${if (new.size != 1) "s" else ""} in FLAC" + if (Remote.active) " to this phone" else "")
        savedVersion++
        if (!downloading) downloadNext()
    }

    private fun downloadNext() {
        if (downloads.isEmpty()) { downloading = false; downloadNote = ""; savedVersion++; musicVersion++; return }
        downloading = true
        val batch = downloads
        downloadNote = "Downloading in FLAC · 0 of ${batch.size}"
        bg({ dl.sync(batch, force = true, workers = Downloader.SONGS_AT_ONCE) { n -> scope.launch { savedVersion++; downloadNote = "Downloading in FLAC · $n of ${batch.size}" } } }) { out, err ->
            downloads = downloads.filter { d -> batch.none { it.id == d.id } }
            toast(out?.summary() ?: "Couldn't download: ${err?.message?.take(90)}")
            downloadNext()
        }
    }

    fun saveCurrent() { current()?.let { download(listOf(it)) } }

    /** Make source (a picture's web address, or a content:// one you picked) the cover of tracks' album:
     *  into its downloaded FLACs (all but the playing one's), and shown for all of its songs. */
    fun setCover(tracks: List<Track>, source: String, done: () -> Unit = {}) {
        val t = tracks.firstOrNull() ?: return
        val playing = current()?.id
        bg({
            val raw = if (source.startsWith("content://")) ctx.contentResolver.openInputStream(android.net.Uri.parse(source))!!.use { it.readBytes() }
                else httpBytes(source)
            val jpeg = Covers.jpeg(raw)
            val key = Covers.albumKey(t)
            val saved = File(File(ctx.filesDir, "covers").apply { mkdirs() }, "${randomHex(8)}.jpg").apply { writeBytes(jpeg) }
            val album = lib.items.values.filter { Covers.albumKey(it.track()) == key || it.id in tracks.map { x -> x.id } }
            var written = 0
            val failed = ArrayList<String>()
            for (e in album.distinctBy { it.file }) {
                if (e.id == playing || e.format != "flac" || !e.file.startsWith("content://")) continue
                try { Covers.write(ctx, e.file, jpeg); written++ } catch (x: Exception) { failed += "${e.title}: ${x.message}" }
            }
            for (e in album) {   // a new file each time: the old picture stays in the image cache under the old name
                val art = File(lib.artDir, "${safeName(e.id)}-${randomHex(4)}.jpg").apply { writeBytes(jpeg) }
                val old = e.art
                lib.update(e.id) { it.copy(art = art.path) }
                if (old.isNotEmpty() && lib.items.values.none { it.art == old }) File(old).delete()
            }
            if (t.album.isNotEmpty()) Pictures.chooseCover(t.artist, t.album, saved.path)
            else for (x in tracks) Pictures.chooseCover(x.artist, x.title, saved.path)
            written to failed
        }) { res, err ->
            if (err != null || res?.second?.isNotEmpty() == true) toast("Couldn't use that cover: ${(err?.message ?: res!!.second[0]).take(100)}")
            if (res != null) toast("New cover for “${t.album.ifEmpty { t.title }}”" + if (res.first > 0) " in ${res.first} file${if (res.first != 1) "s" else ""}" else "")
            savedVersion++; musicVersion++
            done()
        }
    }

    private fun playbackOf(t: Track) = speeds.get(t).let { s ->
        androidx.media3.common.PlaybackParameters(s.speed.toFloat(), if (s.pitch) s.speed.toFloat() else 1f)
    }

    /** Play tracks at speed from now on (the playing one too, at once). */
    fun setSpeed(tracks: List<Track>, speed: Double, pitch: Boolean) {
        if (tracks.isEmpty()) return
        speeds.set(tracks, speed, pitch)
        val cur = current()
        if (cur != null && tracks.any { SongSpeeds.key(it) == SongSpeeds.key(cur) }) player?.playbackParameters = playbackOf(cur)
        val s = speeds.get(tracks[0]).speed
        val what = if (tracks.size == 1) "“${tracks[0].title}”" else "${tracks.size} songs"
        toast(if (s == 1.0) "$what back to normal speed" else "$what at ${fmtSpeed(s)}")
        musicVersion++
    }

    /** Write changes {field: value} into the tags of tracks' downloaded FLACs, and show the new
     *  names wherever the songs are. The playing song is left alone: its file is being read. */
    fun editTags(tracks: List<Track>, changes: Map<String, String>) {
        val playing = current()?.id
        val todo = tracks.filter { Tags.editable(it) != null && it.id != playing }
        if (todo.size < tracks.count { Tags.editable(it) != null }) toast("The playing song is left as it is: edit it once it's over")
        if (todo.isEmpty() || changes.isEmpty()) return
        bg({
            val renamed = HashMap<String, Triple<String, String, String>>()
            val failed = ArrayList<String>()
            for (t in todo) {
                val e = lib.items[t.id] ?: continue
                val c = try { Tags.write(ctx, e.file, changes) } catch (x: Exception) { failed += "${t.title}: ${x.message}"; continue }
                val title = c["TITLE"]?.firstOrNull() ?: e.title
                val artist = c["ARTIST"]?.joinToString(", ")?.ifEmpty { null } ?: "Unknown artist"
                val album = c["ALBUM"]?.firstOrNull() ?: ""
                lib.items.values.filter { it.file == e.file }.forEach { same ->
                    lib.update(same.id) { it.copy(title = title, artist = artist, album = album, genre = c["GENRE"]?.firstOrNull() ?: "") }
                    renamed[same.id] = Triple(title, artist, album)
                }
            }
            lists.retitle(renamed.keys) { x -> renamed[x.id]!!.let { (a, b, c) -> x.copy(title = a, artist = b, album = c) } }
            renamed to failed
        }) { res, err ->
            val (renamed, failed) = res ?: (emptyMap<String, Triple<String, String, String>>() to listOf(err?.message ?: "?"))
            queue = queue.map { x -> renamed[x.id]?.let { (a, b, c) -> x.copy(title = a, artist = b, album = c) } ?: x }
            if (failed.isNotEmpty()) toast("Couldn't write the tags: ${failed[0].take(100)}")
            val n = todo.size - failed.size
            if (n > 0) toast("Tags written to $n song${if (n != 1) "s" else ""}")
            savedVersion++; musicVersion++
        }
    }

    /** A song on the phone of another app's (yours) that Android can be asked to delete: its address, or null. */
    fun phoneFile(t: Track): String? =
        t.file?.takeIf { t.isFile && !lib.has(t.id) && it.startsWith("content://media/") && android.os.Build.VERSION.SDK_INT >= 30 }

    fun deletable(t: Track) = lib.has(t.id) || phoneFile(t) != null

    /** Delete the downloads among tracks (the app's own files, no asking Android); the phone's
     *  songs are deleted by Android once you say yes to it, then [phoneFilesDeleted]. */
    fun deleteDownloads(tracks: List<Track>) {
        val playing = current()?.id
        val ids = tracks.filter { lib.has(it.id) && it.id != playing }.map { it.id }
        if (tracks.any { it.id == playing && lib.has(it.id) }) toast("The playing song is left as it is: skip it first")
        if (ids.isEmpty()) return
        bg({ ids.forEach { lib.remove(it) } }) { _, err ->
            toast(if (err != null) "Couldn't delete them: ${err.message}" else "Deleted ${ids.size} download${if (ids.size != 1) "s" else ""}")
            savedVersion++; musicVersion++
        }
    }

    /** The phone's songs tracks were deleted: out of your music, the queue and your playlists. */
    fun phoneFilesDeleted(tracks: List<Track>) {
        val ids = tracks.map { it.id }.toSet()
        local.files = local.files.filter { it.id !in ids }
        unqueueMany(queue.indices.filter { queue[it].id in ids })
        lists.all().filter { p -> p.tracks.any { it.id in ids } }.forEach { lists.remove(it.id, ids) }
        toast("Deleted ${ids.size} song${if (ids.size != 1) "s" else ""}")
        musicVersion++
    }

    fun removeDownload(id: String) {
        if (current()?.id == id) { toast("That song is playing; skip it first"); return }
        bg({ lib.remove(id) }) { _, _ -> savedVersion++; musicVersion++ }
    }

    @JvmName("chooseKeepPlayed")
    fun setKeepPlayed(on: Boolean) { keepPlayed = on; saveState() }

    // ---- services ----
    @JvmName("chooseSource")
    fun setSource(key: String) { source = key; saveState() }

    /** The source bar: a service (its library in the Library tab, its search) or "local": your own
     *  songs, which the search looks in, with the Library tab hidden until you pick a service. */
    fun browse(key: String) {
        when {
            key == "local" -> {
                if (browseLocal) return
                browseLocal = true
                if (searchIn != "local") { searchBeforeLocal = searchIn; searchIn = "local" }
            }
            key in SOURCES -> {
                if (!browseLocal && key == source) return
                if (browseLocal && searchIn == "local") searchIn = searchBeforeLocal
                browseLocal = false
                source = key
            }
            else -> return
        }
        saveState()
    }

    @JvmName("chooseSearchIn")
    fun setSearchIn(key: String) { searchIn = key; saveState() }

    fun setSyncSource(key: String, on: Boolean) {
        syncSources[key] = on
        saveState()
        if (on) syncLibrary()
    }

    fun sourceReady(key: String, src: Source?, err: Throwable?) {
        if (src != null) {
            sources[key] = src
            sourceErrors.remove(key)
            if (src.signedIn) bg({ src.likedIds() }) { ids, _ -> if (ids != null) { likes[key] = ids; liked = likes.values.flatten().toSet() } }
            else if (likes.remove(key) != null) liked = likes.values.flatten().toSet()
        } else if (key !in sources) sourceErrors[key] = err?.message ?: "unavailable"
        accountsVersion++
        if (key == "tidal") playerReady()
        if (key == "spotify" && src?.signedIn == true) bg({ SpotifyHistory.sync() })   // its last plays, for your Wrapped
        syncLibrary()
    }

    fun tidalLoggedIn(redirect: String) {
        bg({ tidal.finishLogin(redirect) }) { _, err ->
            if (err != null) { toast("Tidal didn't accept that: ${err.message?.take(80)}"); return@bg }
            toast("Tidal connected: everything plays in FLAC")
            sourceReady("tidal", tidal, null)
        }
    }

    fun tidalDisconnect() {
        tidal.disconnect()
        sources.remove("tidal")
        accountsVersion++
    }

    /** Download every song in your playlists and favourites, on each service you sync, in FLAC. */
    fun syncLibrary() {
        if (!tidal.connected) return
        val srcs = sources.filter { (k, s) -> s.signedIn && syncSources[k] == true }.values.toList()
        if (srcs.isEmpty()) return
        if (syncing) { syncAgain = true; return }
        syncing = true
        syncAgain = false
        bg({
            val tracks = ArrayList<Track>()
            val seen = HashSet<String>()
            for (s in srcs) {
                val found = runCatching { s.libraryTracks() }.getOrNull() ?: continue
                for (t in found) if (seen.add(t.id)) tracks += t
                scope.launch { syncTracks = tracks.toList() }
            }
            dl.sync(tracks) { scope.launch { savedVersion++ } }
        }) { _, err ->
            syncing = false
            if (err != null) toast("FLAC sync stopped: ${err.message?.take(80)}")
            savedVersion++
            musicVersion++
            if (syncAgain) syncLibrary()
        }
    }

    // ---- what counts as played ----
    private fun beginListen(t: Track) {
        listen = Listen(t.id, t, sent = !LastFm.connected)
        if (LastFm.connected) bg({ LastFm.nowPlaying(t) })
    }

    /** A song counts as played (Recent, Most played) after PLAY_AFTER seconds; Last.fm gets
     *  it once you've listened to half of it (or four minutes). */
    private fun listened(seconds: Double) {
        val ls = listen ?: return
        if (ls.sent && ls.counted) return
        ls.played += seconds
        val dur = duration.takeIf { it > 0 } ?: ls.track.duration
        if (!ls.counted && ls.played >= minOf(PLAY_AFTER.toDouble(), if (dur > 0) dur / 2 else PLAY_AFTER.toDouble())) {
            ls.counted = true
            plays.add(ls.track)
            lib.played(ls.id)
            musicVersion++
        }
        if (!ls.sent && dur > 30 && ls.played >= minOf(dur / 2, 240.0)) {
            ls.sent = true
            val start = ls.start
            bg({ LastFm.scrobble(ls.track, start) })
        }
    }

    // ---- the queue ----
    fun current(): Track? = queue.getOrNull(index)
    fun isRadio() = radioFrom != null

    // What the screens show as playing: the phone's, or the desktop's while it's the remote.
    fun shownQueue() = if (Remote.active) Remote.status.queue else queue
    fun shownIndex() = if (Remote.active) Remote.status.index else index
    fun shownTrack() = if (Remote.active) Remote.status.track else current()
    fun shownRadio() = if (Remote.active) Remote.status.radio else isRadio()
    fun livePosition(): Double = if (Remote.active) Remote.livePosition() else player?.let { it.currentPosition / 1000.0 } ?: position

    /** Play tracks[i], then the rest of tracks; the songs you queued come first. */
    fun playFrom(tracks: List<Track>, i: Int, radio: Boolean) {
        if (Remote.active) { Remote.playFrom(tracks, i, radio); return }
        val queued = queue.drop(index + 1).filter { it.queued }
        queue = tracks.take(i + 1) + queued + tracks.drop(i + 1)
        index = i
        radioFrom = null
        shuffled = false
        if (dj.on) dj = dj.copy(on = false)   // you picked other songs: the DJ stops
        context++
        loadCurrent()
        if (radio) extendRadio(queue[i], keepUntil = i)
    }

    /** "next" plays the songs right after this one, "queue" after the songs you queued before. */
    fun enqueue(tracks: List<Track>, how: String = "queue") {
        if (Remote.active) { Remote.enqueue(tracks, how); return }
        val ts = tracks.map { it.copy(queued = true) }
        if (ts.isEmpty()) return
        if (current() == null) { playFrom(ts, 0, false); return }
        var pos = index + 1
        if (how != "next") while (pos < queue.size && queue[pos].queued) pos++
        queue = queue.take(pos) + ts + queue.drop(pos)
        afterQueueChange()
        val many = if (ts.size > 1) "${ts.size} songs" else "“${ts[0].title}”"
        toast(if (ts.size > 1) "$many ${if (how == "next") "play next" else "added to the queue"}"
              else "${if (how == "next") "Playing next" else "Added to the queue"}: $many")
        if (ended) next()
    }

    fun unqueueMany(indices: Collection<Int>) {
        if (Remote.active) { Remote.cmd("unqueue", "indices" to indices.toList()); return }
        val drop = indices.filter { it in queue.indices && it != index }.toSet()
        val before = drop.count { it < index }
        queue = queue.filterIndexed { i, _ -> i !in drop }
        index -= before
        afterQueueChange()
    }

    private fun radioTracks(t: Track): List<Track> {
        var last: Throwable? = null
        for (key in listOf(t.source, source, "ytm", "tidal").distinct()) {
            val src = sources[key] ?: continue
            if (key in setOf("apple", "spotify") || (key == "tidal" && !src.signedIn)) continue
            val found = try { src.radio(t) } catch (e: Exception) { last = e; continue }
            if (found.isNotEmpty()) return found
        }
        last?.let { throw it }
        return emptyList()
    }

    private fun extendRadio(t: Track, keepUntil: Int? = null) {
        radioFrom = t.id
        val ctxNow = context
        bg({ radioTracks(t) }) { res, err ->
            if (ctxNow != context || err != null || res.isNullOrEmpty()) return@bg
            var q = queue
            if (keepUntil != null) {
                // A song picked from results: its radio replaces the rest of the results (not your queue).
                val cut = maxOf(keepUntil, index)
                q = q.take(cut + 1) + q.drop(cut + 1).filter { it.queued }
            }
            val ids = q.map { it.id }.toMutableSet()
            val songs = q.map { songKey(it) }.toMutableSet()
            q = q + res.filter { ids.add(it.id) && songs.add(songKey(it)) }
            queue = q
            afterQueueChange()
        }
    }

    fun playIndex(i: Int) {
        if (Remote.active) { Remote.cmd("play_index", "index" to i); return }
        if (i in queue.indices) { index = i; loadCurrent() }
    }

    fun next() {
        if (Remote.active) { Remote.cmd("next"); return }
        when {
            index + 1 < queue.size -> { index++; loadCurrent() }
            repeat == "all" && queue.isNotEmpty() -> {
                if (shuffled) { queue = shuffleSongs(queue, after = current()); afterQueueChange() }   // round again in a new order
                index = 0; loadCurrent()
            }
            else -> { ended = true; player?.pause() }
        }
    }

    fun previous() {
        if (Remote.active) { Remote.cmd("prev"); return }
        if (position > 4 || index <= 0) {
            player?.seekTo(0)
            val t = current()
            if (t != null && listen?.let { it.sent || it.counted } == true) beginListen(t)   // listening again counts again
        } else { index--; loadCurrent() }
    }

    fun trackEnded() {
        if (streaming && duration > 0 && position < duration - 5) { streamBroke(); return }   // the network went away mid-song
        if (repeat == "one") loadCurrent() else next()
    }

    fun cycleRepeat() {
        if (Remote.active) { Remote.cmd("repeat"); return }
        repeat = mapOf("off" to "all", "all" to "one", "one" to "off")[repeat]!!
        saveState()
    }

    /** Shuffle what's coming up; the songs you queued stay first, in your order. */
    fun shuffle() {
        if (Remote.active) { Remote.cmd("shuffle"); return }
        val rest = queue.drop(index + 1)
        queue = queue.take(index + 1) + rest.filter { it.queued } + shuffleSongs(rest.filter { !it.queued })
        shuffled = true
        afterQueueChange()
    }

    fun togglePause() {
        if (Remote.active) { Remote.cmd(if (Remote.status.paused) "play" else "pause"); return }
        val p = player ?: return
        if (current() == null) return
        if (p.isPlaying) p.pause() else {
            if (p.playbackState == Player.STATE_IDLE || p.mediaItemCount == 0) loadCurrent() else p.play()
        }
    }

    fun seek(seconds: Double) {
        if (Remote.active) { Remote.cmd("seek", "position" to seconds); return }
        player?.seekTo((seconds * 1000).toLong())
        position = seconds
    }

    fun toggleLike() {
        if (Remote.active) { Remote.cmd("like"); return }
        val t = current() ?: return
        val src = sources[t.source] ?: return
        if (!src.signedIn) return
        val like = t.id !in liked
        liked = if (like) liked + t.id else liked - t.id
        bg({ src.rate(t, like); if (LastFm.connected) runCatching { LastFm.love(t, like) } }) { _, err ->
            if (err != null) {
                liked = if (like) liked - t.id else liked + t.id
                toast("Couldn't ${if (like) "like" else "unlike"} it: ${err.message?.take(80)}")
            }
        }
    }

    private fun afterQueueChange() {
        saveState()
        prefetch()
    }

    fun loadCurrent(autoplay: Boolean = true) {
        val t0 = current() ?: return
        val t = t0.copy(queued = false)   // playing now, not waiting in your queue
        if (t0.queued) queue = queue.toMutableList().also { it[index] = t }
        restorePending = false
        ended = false
        listen = null
        val seq = ++loadSeq
        position = 0.0
        duration = t.duration
        val onDisk = lib.has(t.id) || t.isFile
        streaming = !(onDisk || keepPlayed)
        status = if (onDisk) "" else if (keepPlayed) "Downloading FLAC…" else "Loading the stream…"
        val work: () -> Downloader.Ready = if (streaming || onDisk) ({ dl.stream(t) })
            else ({ try { dl.download(t) } catch (_: Exception) {}; dl.stream(t) })   // no FLAC to keep (or saving failed): stream what there is
        bg(work) { ready, err ->
            if (seq != loadSeq) return@bg   // you moved on while it loaded
            status = ""
            if (err != null || ready == null) {
                toast(if (err is NoLosslessMatch) "Skipped “${t.title}”: ${err.message}" else "Couldn't get “${t.title}”: ${err?.message}")
                if (autoplay) scope.launch { delay(1500); if (seq == loadSeq) next() } else paused = true
                return@bg
            }
            if (!ready.onDisk) streaming = true   // Keep found no FLAC to save: it streams
            val start = { play(t, ready, autoplay) }
            if (player == null) pendingLoad = start else start()
            if (autoplay) beginListen(t)
            savedVersion++
            prefetch()
        }
        saveState()
        // Keep a radio going: near the end of the queue, ask for more like the last song.
        if (radioFrom != null && index >= queue.size - 2) extendRadio(queue.last())
        if (dj.on) {
            djAnnounce(t)
            if (index >= queue.size - 2) djMore()
        }
    }

    private fun play(t: Track, ready: Downloader.Ready, autoplay: Boolean, startAt: Double = 0.0) {
        val p = player ?: return
        val art = lib.items[t.id]?.art?.takeIf { it.isNotEmpty() }?.let { android.net.Uri.fromFile(File(it)) }
            ?: t.thumb.takeIf { it.isNotEmpty() }?.let { android.net.Uri.parse(sizedThumb(it, 544)) }
        val item = MediaItem.Builder().setUri(ready.uri).setMediaId(t.id)
            .apply { if (ready.isDash) setMimeType(MimeTypes.APPLICATION_MPD) }
            .setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album)
                .setArtworkUri(art).build())
            .build()
        p.setMediaItem(item, (startAt * 1000).toLong())
        p.playbackParameters = playbackOf(t)
        p.prepare()
        p.playWhenReady = autoplay
    }

    private fun prefetch() {
        val nxt = queue.getOrNull(index + 1) ?: return
        if (lib.has(nxt.id) || nxt.isFile) return
        if (keepPlayed) bg({ dl.download(nxt) }) { _, _ -> savedVersion++ } else bg({ dl.stream(nxt) })
    }

    /** Tidal's stream stopped short: look it up again and carry on from the same place, once per song. */
    fun streamBroke() {
        val t = current() ?: return
        if (!streaming) return
        val seq = loadSeq
        val pos = position
        val wasPlaying = player?.playWhenReady == true
        if (streamRetry == seq) {
            toast("Lost the stream of “${t.title}”")
            if (wasPlaying) next()
            return
        }
        streamRetry = seq
        dl.forgetStream(t.id)
        bg({ dl.stream(t) }) { ready, err ->
            if (seq != loadSeq) return@bg
            if (err != null || ready == null) { toast("Couldn't get “${t.title}” back: ${err?.message}"); return@bg }
            play(t, ready, wasPlaying, pos)
        }
    }

    // ---- the equalizer ----
    fun setEq(f: (EqSettings) -> EqSettings) {
        eq = f(eq)
        eqProcessor.update(eq)
        saveState()
    }

    fun setEqOn(on: Boolean) = setEq { it.copy(on = on) }
    fun setEqHeadphones(key: String) = setEq { it.copy(headphones = key, on = true) }
    fun setEqPreset(key: String) = setEq { it.copy(preset = key, bands = Eq.PRESETS[key]?.second ?: it.custom, on = true) }
    fun setEqBand(i: Int, gain: Double) = setEq {
        val b = it.bands.toMutableList().also { l -> l[i] = Eq.clamp(gain) }
        it.copy(bands = b, custom = b, preset = "custom", on = true)
    }
    fun setEqTone(name: String, gain: Double) = setEq {
        if (name == "bass") it.copy(bass = Eq.clamp(gain), on = true) else it.copy(treble = Eq.clamp(gain), on = true)
    }

    @JvmName("chooseArtColors")
    fun setArtColors(on: Boolean) { artColors = on; saveState() }
    @JvmName("chooseLyricsMode")
    fun setLyricsMode(mode: String) { lyricsMode = mode; saveState() }
    @JvmName("chooseMusicSort")
    fun setMusicSort(sort: String) { musicSort = sort; saveState() }
    fun pickPlaylistSort(sort: String) { playlistSort = sort; saveState() }

    /** song key -> how often you played it: the stats (Last.fm and Spotify too), else the plays here. */
    fun playCounts(): Map<Pair<String, String>, Int> {
        val out = HashMap<Pair<String, String>, Int>()
        plays.items.values.forEach { out[songKey(it.track)] = it.plays }
        Stats.songs.forEach { s -> out.merge(songKey(Track("", title = s.title, artist = s.artist)), s.plays) { a, b -> maxOf(a, b) } }
        return out
    }

    /** A playlist's songs as the sort chips say. */
    fun sortedPlaylist(tracks: List<Track>) = sortSongs(tracks, playlistSort, if (playlistSort == "plays") playCounts() else emptyMap())
}
