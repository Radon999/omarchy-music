package org.omarchy.music.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.omarchy.music.Album
import org.omarchy.music.ArtistInfo
import org.omarchy.music.Music
import org.omarchy.music.Remote
import org.omarchy.music.SOURCES
import org.omarchy.music.Track
import org.omarchy.music.fmtTotal
import org.omarchy.music.norm

@Composable
fun SearchTab(ui: Ui) {
    val res = ui.results
    LazyColumn(Modifier.fillMaxSize()) {
        when {
            ui.searching -> item { Empty("Searching ${SOURCES[Music.source]}…") }
            res == null -> item { Empty("Search ${SOURCES[Music.source]}", "Type above, or paste a link to a song, an album or a playlist.") }
            res.isEmpty() -> item { Empty("No songs found", ui.query) }
            else -> tracks(res, ui, "search", radio = res.size > 1 && !ui.resultsAreList)
        }
    }
}

fun runSearch(ui: Ui) {
    val q = ui.query.trim()
    if (q.isEmpty()) return
    val key = Music.source
    val src = Music.sources[key]
    if (src == null) { Music.toast("Still connecting to ${SOURCES[key]}…"); return }
    if (!src.signedIn && key in setOf("tidal", "spotify", "apple")) { Music.toast("Connect ${SOURCES[key]} in the accounts menu first"); return }
    ui.tab = "search"
    ui.searching = true
    ui.resultsAreList = Regex("/(playlist|album|mix)/|list=").containsMatchIn(q)
    Music.bg({ src.search(q) }) { res, err ->
        ui.searching = false
        if (err != null) { ui.results = emptyList(); Music.toast("Search failed: ${err.message?.take(100)}") }
        else ui.results = res
    }
}

@Composable
fun LibraryTab(ui: Ui) {
    val key = Music.source
    Music.accountsVersion
    val src = Music.sources[key]
    val loaded = ui.playlistsBySource.firstOrNull { it.first == key }
    LaunchedEffect(key, src?.signedIn) {
        if (src != null && src.signedIn && loaded == null) {
            ui.playlistsBySource += key to null
            Music.bg({ src.myPlaylists() }) { res, err ->
                ui.playlistsBySource.removeAll { it.first == key }
                ui.playlistsBySource += key to (res ?: emptyList())
                if (err != null) Music.toast("Couldn't load your ${SOURCES[key]} playlists: ${err.message?.take(80)}")
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        val open = ui.openList
        when {
            src == null -> item {
                val err = Music.sourceErrors[key]
                if (err != null) Empty("${SOURCES[key]} isn't available", err.take(140))
                else Empty("Not signed in", "Open the accounts menu (☰) to sign in to ${SOURCES[key]}.")
                ActionRow("Open the accounts menu  ›") { ui.sheet = "accounts" }
            }
            !src.signedIn -> item {
                Empty("Not signed in", src.hint)
                ActionRow("Connect ${SOURCES[key]}  ›") { ui.sheet = "accounts" }
            }
            open != null -> {
                item { BackRow(open.first.title) { ui.openList = null } }
                val tracks = open.second
                if (tracks == null) item { Section("LOADING…") }
                else {
                    item { ListActions(tracks) }
                    tracks(tracks, ui, "list")
                }
            }
            loaded?.second == null -> item { Empty("Loading your library…") }
            else -> {
                item { SyncLine() }
                if (LastFmOn()) item { ActionRow("✦  Your ${org.omarchy.music.Wrapped.year()} Wrapped  ›") { ui.sheet = "wrapped" } }
                val lists = loaded.second!!
                if (lists.isEmpty()) item { Section("NO PLAYLISTS YET") }
                items(lists.size) { i ->
                    val pl = lists[i]
                    PlaylistRow(pl) {
                        ui.openList = pl to null
                        Music.bg({ src.playlist(pl.id) }) { res, err ->
                            if (ui.openList?.first == pl) ui.openList = pl to (res ?: emptyList())
                            if (err != null) Music.toast("Couldn't open it: ${err.message?.take(80)}")
                        }
                    }
                }
            }
        }
    }
}

private fun LastFmOn() = org.omarchy.music.LastFm.connected

@Composable
fun SyncLine() {
    Music.savedVersion
    val text = when {
        !Music.tidal.connected -> "Connect Tidal to play everything in FLAC  ›"
        Music.syncTracks.isEmpty() -> if (Music.syncing) "FLAC · reading your playlists…" else "FLAC · Tidal connected"
        else -> {
            val tr = Music.syncTracks
            val flac = tr.count { Music.lib.isFlac(it.id) }
            "FLAC · $flac of ${tr.size} songs saved" + if (Music.syncing) " · downloading…" else ""
        }
    }
    Text(text, style = T.sub.copy(color = C.accent), modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
}

@Composable
fun ListActions(tracks: List<Track>, extra: List<Pair<String, () -> Unit>> = emptyList()) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (tracks.isNotEmpty()) {
            Pill("Play") { Music.playList(tracks) }
            Pill("Shuffle") { Music.playList(tracks, shuffled = true) }
            if (tracks.any { !it.isFile && !Music.lib.has(it.id) }) Pill("Download", quiet = true) { Music.download(tracks) }
        }
        extra.forEach { (label, act) -> Pill(label, quiet = true, onClick = act) }
    }
    if (tracks.isNotEmpty()) Text("${tracks.size} songs · ${fmtTotal(tracks)}", style = T.section,
        modifier = Modifier.padding(start = 12.dp, bottom = 4.dp))
}

@Composable
fun MusicTab(ui: Ui) {
    val ctx = LocalContext.current
    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok) Music.rescan()
    }
    LaunchedEffect(Unit) { if (granted) Music.rescanSoon() }
    Music.musicVersion
    Music.savedVersion
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("all" to "All", "playlists" to "Playlists", "recent" to "Recent", "added" to "Added", "top" to "Most played").forEach { (k, l) ->
                Chip(l, ui.musicView == k) { ui.musicView = k; ui.openMine = null; ui.stopSelecting() }
            }
        }
        val state = rememberLazyListState()
        LazyColumn(Modifier.fillMaxSize(), state = state) {
            Music.musicVersion   // read here too: the list is built apart from the screen, and must follow a scan
            Music.savedVersion
            if (!granted && ui.musicView in setOf("all", "added")) item {
                ActionRow("Allow access to the music on this phone  ›") { ask.launch(perm) }
            }
            when (ui.musicView) {
                "playlists" -> myPlaylists(ui)
                else -> {
                    val tracks = when (ui.musicView) {
                        "all" -> Music.mySongs().sortedWith(compareBy({ norm(it.title) }, { norm(it.artist) }))
                        "added" -> Music.mySongs().sortedByDescending { it.added }.take(300)
                        "recent" -> Music.plays.recent()
                        else -> Music.plays.top()
                    }
                    if (tracks.isEmpty()) item {
                        val (t, b) = when (ui.musicView) {
                            "all" -> "No music yet" to "Songs you download, and the music in your folders\n(${Music.folders.joinToString()}), show up here."
                            "added" -> "Nothing added yet" to "Songs you download or put in your folders show up here, newest first."
                            "recent" -> "Nothing played yet" to "The songs you listen to show up here, the last one first."
                            else -> "Nothing played yet" to "The songs you play most show up here."
                        }
                        Empty(t, b)
                    } else {
                        item {
                            if (ui.musicView == "all") {
                                val dl = Music.lib.items.size
                                Section("${tracks.size} SONGS · $dl DOWNLOADED · ${fmtTotal(tracks).uppercase()}")
                            } else Section(mapOf("added" to "RECENTLY ADDED", "recent" to "RECENTLY PLAYED", "top" to "MOST PLAYED")[ui.musicView]!!)
                        }
                        tracks(tracks, ui, ui.musicView) { t -> if (ui.musicView == "top") "${t.plays} play${if (t.plays != 1) "s" else ""}" else "" }
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.myPlaylists(ui: Ui) {
    val open = ui.openMine?.let { Music.lists.get(it) }
    if (open == null) {
        item { ActionRow("＋  New playlist") { ui.askName = Triple("New playlist", "") { n -> Music.newPlaylist(n) } } }
        val lists = Music.lists.all()
        if (lists.isEmpty()) item {
            Section("NO PLAYLISTS YET")
            Text("Make one here, or long-press any song (or select a few) and pick Add to playlist.", style = T.sub,
                modifier = Modifier.padding(horizontal = 12.dp))
        }
        items(lists.size) { i ->
            val pl = lists[i]
            val cover = pl.tracks.firstOrNull { it.thumb.isNotEmpty() || it.isFile }
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlaylistRow(Album(pl.id, pl.name, "", "", pl.tracks.size.toString()).let { a ->
                    if (cover != null) a.copy(thumb = (artModel(cover) as? String) ?: "") else a
                }) { ui.openMine = pl.id }
            }
        }
        return
    }
    item { BackRow(open.name) { ui.openMine = null } }
    item {
        ListActions(open.tracks, listOf(
            "Rename" to { ui.askName = Triple("Rename playlist", open.name) { n -> Music.renamePlaylist(open.id, n) } },
            "Delete" to { ui.confirm = "Delete the playlist “${open.name}”? The songs stay where they are." to { Music.deletePlaylist(open.id); ui.openMine = null } },
        ))
    }
    if (open.tracks.isEmpty()) item { Empty("No songs in it yet", "Long-press a song anywhere and pick Add to playlist.") }
    else tracks(open.tracks, ui, "mine", playlistId = open.id)
}

@Composable
fun QueueTab(ui: Ui) {
    val q = Music.shownQueue()
    val idx = Music.shownIndex()
    LazyColumn(Modifier.fillMaxSize()) {
        if (q.isEmpty() || idx < 0) { item { Empty("Nothing queued", if (Remote.active) "Nothing is playing on the desktop. Play a song here and it plays there." else "Play a song, or long-press one and pick Add to the queue.") }; return@LazyColumn }
        item { Section("NOW PLAYING") }
        item { TrackRow(q[idx], RowPlace(q, idx, "queue", queueIndex = idx), ui) { } }
        val rest = (idx + 1 until q.size).toList()
        val mine = rest.filter { q[it].queued }
        val next = rest.filter { !q[it].queued }
        if (mine.isNotEmpty()) {
            item { Section("QUEUED BY YOU") }
            items(mine.size, key = { "q${mine[it]}" }) { j ->
                val i = mine[j]
                TrackRow(q[i], RowPlace(q, i, "queue", queueIndex = i), ui) { Music.playIndex(i) }
            }
        }
        if (next.isNotEmpty()) {
            item { Section(if (Music.shownRadio()) "UP NEXT · RADIO" else "UP NEXT") }
            items(next.size, key = { "n${next[it]}" }) { j ->
                val i = next[j]
                TrackRow(q[i], RowPlace(q, i, "queue", queueIndex = i), ui) { Music.playIndex(i) }
            }
        }
    }
}

@Composable
fun PageScreen(ui: Ui, page: Ui.Page) {
    when (page) {
        is Ui.Page.Artist -> ArtistPage(ui, page)
        is Ui.Page.AlbumPage -> AlbumPage(ui, page)
    }
}

@Composable
private fun ArtistPage(ui: Ui, page: Ui.Page.Artist) {
    var info by remember(page) { mutableStateOf<ArtistInfo?>(null) }
    var failed by remember(page) { mutableStateOf<String?>(null) }
    LaunchedEffect(page) {
        Music.bg({ Music.artistInfo(page.name, page.prefer) }) { res, err ->
            info = res
            failed = err?.message ?: if (res == null) "No service knows ${page.name}" else null
        }
    }
    val mine = remember(page) { Music.songsBy(page.name) }
    LazyColumn(Modifier.fillMaxSize()) {
        item { BackRow(page.name) { ui.pages.removeAt(ui.pages.lastIndex) } }
        val i = info
        if (i != null) item {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ArtImage(i.thumb.ifEmpty { null }, 72.dp)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(i.name, style = T.title.copy(fontSize = T.title.fontSize * 1.3f))
                    Text("from ${SOURCES[i.source]}", style = T.sub)
                }
            }
        }
        if (mine.isNotEmpty()) {
            item { Section("YOUR SONGS") }
            tracks(mine, ui, "artist")
        }
        when {
            i == null && failed == null -> item { Section("LOADING…") }
            i == null -> item { Empty("Couldn't find them", failed!!) }
            else -> {
                if (i.top.isNotEmpty()) {
                    item { Section("TOP SONGS" + if (i.topSource != i.source) " · ${SOURCES[i.topSource]?.uppercase()}" else "") }
                    tracks(i.top, ui, "artist", radio = true)
                }
                if (i.albums.isNotEmpty()) {
                    item { Section("ALBUMS AND SINGLES") }
                    items(i.albums.size) { j ->
                        val al = i.albums[j]
                        PlaylistRow(al) { ui.pages += Ui.Page.AlbumPage(al, i.source) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumPage(ui: Ui, page: Ui.Page.AlbumPage) {
    var tracks by remember(page) { mutableStateOf<List<Track>?>(null) }
    LaunchedEffect(page) {
        val src = Music.sources[page.source]
        Music.bg({ src?.album(page.album.id) ?: emptyList() }) { res, err ->
            tracks = res ?: emptyList()
            if (err != null) Music.toast("Couldn't open the album: ${err.message?.take(80)}")
        }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item { BackRow(page.album.title) { ui.pages.removeAt(ui.pages.lastIndex) } }
        item {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ArtImage(page.album.thumb.ifEmpty { null }, 96.dp)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(page.album.title, style = T.title.copy(fontSize = T.title.fontSize * 1.2f))
                    if (page.album.sub.isNotEmpty()) Text(page.album.sub, style = T.sub)
                }
            }
        }
        val t = tracks
        if (t == null) item { Section("LOADING…") } else {
            item { ListActions(t) }
            tracks(t, ui, "album")
        }
    }
}
