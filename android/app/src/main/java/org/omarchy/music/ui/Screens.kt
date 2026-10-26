package org.omarchy.music.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.omarchy.music.Album
import org.omarchy.music.ArtistInfo
import org.omarchy.music.Dj
import org.omarchy.music.LastFm
import org.omarchy.music.Moods
import org.omarchy.music.SpotifyHistory
import org.omarchy.music.Stats
import org.omarchy.music.Found
import org.omarchy.music.Music
import org.omarchy.music.Remote
import org.omarchy.music.SOURCES
import org.omarchy.music.Track
import org.omarchy.music.fmtTotal
import org.omarchy.music.linkSource
import org.omarchy.music.localMatches
import org.omarchy.music.norm

private fun searchName(key: String) = if (key == "local") "your music" else SOURCES[key] ?: ""

@Composable
fun SearchTab(ui: Ui) {
    val res = ui.found
    val where = searchName(ui.foundIn.ifEmpty { Music.searchIn })
    LazyColumn(Modifier.fillMaxSize()) {
        when {
            ui.searching -> item { Empty("Searching $where…") }
            res == null -> item { Empty("Search ${searchName(Music.searchIn)}", "Songs, artists or albums: pick where with the menu by the search bar. Or paste a link to a song, an album or a playlist.") }
            res.isEmpty -> item { Empty("Nothing found", "${ui.query}\nin $where") }
            else -> {
                if (res.artists.isNotEmpty()) {
                    item { Section("ARTISTS") }
                    items(res.artists.size) { i ->
                        val a = res.artists[i]
                        PlaylistRow(a) { ui.pages += Ui.Page.Artist(a.title, ui.foundIn.takeIf { it in SOURCES }) }
                    }
                }
                if (res.albums.isNotEmpty()) {
                    item { Section("ALBUMS") }
                    items(res.albums.size) { i ->
                        val al = res.albums[i]
                        val mine = res.albumTracks[al.id]
                        PlaylistRow(al, mine?.firstOrNull()?.let { artModel(it) }) { ui.pages += Ui.Page.AlbumPage(al, ui.foundIn, mine) }
                    }
                }
                if (res.songs.isNotEmpty()) {
                    if (res.artists.isNotEmpty() || res.albums.isNotEmpty()) item { Section("SONGS") }
                    tracks(res.songs, ui, "search", radio = res.songs.size > 1 && !ui.resultsAreList)
                }
            }
        }
    }
}

/** Search where the search bar's menu says (a pasted link: on its own service), for songs, artists and albums. */
fun runSearch(ui: Ui) {
    val q = ui.query.trim()
    if (q.isEmpty()) return
    val link = linkSource(q)
    var key = link ?: Music.searchIn
    if (key == "tidal" && link == null && Music.sources["tidal"]?.signedIn != true) {
        Music.toast("Tidal isn't connected: searching YouTube Music")
        key = "ytm"
    }
    val seq = ++ui.searchSeq
    ui.tab = "search"
    ui.resultsAreList = Regex("/(playlist|album|mix)/|list=").containsMatchIn(q)
    if (key == "local") {
        ui.searching = false
        ui.foundIn = key
        ui.found = localMatches(Music.mySongs(), q)
        return
    }
    val src = Music.sources[key]
    if (src == null) { Music.toast("Still connecting to ${SOURCES[key]}…"); return }
    if (!src.signedIn && key in setOf("tidal", "spotify", "apple")) { Music.toast("Connect ${SOURCES[key]} in the accounts menu first"); return }
    ui.searching = true
    ui.foundIn = key
    Music.bg({ if (link != null) Found(src.search(q)) else src.find(q) }) { res, err ->
        if (seq != ui.searchSeq) return@bg   // a newer search is on its way
        ui.searching = false
        if (err != null) { ui.found = Found(emptyList()); Music.toast("Search failed: ${err.message?.take(100)}") }
        else ui.found = res
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
                    item { ListActions(tracks, onDj = { ui.djFrom(Dj.From("songs", name = open.first.title, songs = tracks)) }) }
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
fun ListActions(tracks: List<Track>, extra: List<Pair<String, () -> Unit>> = emptyList(), onDj: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (tracks.isNotEmpty()) {
            Pill("Play") { Music.playList(tracks) }
            Pill("Shuffle") { Music.playList(tracks, shuffled = true) }
            if (onDj != null) Pill("DJ", quiet = true, onClick = onDj)
            if (tracks.any { !it.isFile && !Music.lib.has(it.id) }) Pill("Download", quiet = true) { Music.download(tracks) }
        }
        extra.forEach { (label, act) -> Pill(label, quiet = true, onClick = act) }
    }
    if (tracks.isNotEmpty()) Text("${tracks.size} songs · ${fmtTotal(tracks)}" +
        if (Music.downloadNote.isNotEmpty() && tracks.any { t -> Music.downloads.any { it.id == t.id } }) " · ${Music.downloadNote}" else "",
        style = T.section, modifier = Modifier.padding(start = 12.dp, bottom = 4.dp))
}

@Composable
fun MusicTab(ui: Ui) {
    val access = rememberMusicAccess()
    val granted = access.granted
    var choosing by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf("") }
    if (choosing) FolderBrowser { choosing = false }
    LaunchedEffect(Unit) { if (granted) Music.rescanSoon() }
    LaunchedEffect(ui.musicView, Music.accountsVersion, SpotifyHistory.count) { if (ui.musicView == "top") Stats.refresh() }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) Music.importPlaylist(uri) }
    Music.musicVersion
    Music.savedVersion
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("all" to "All", "playlists" to "Playlists", "recent" to "Recent", "top" to "Most played", "moods" to "Moods", "dj" to "DJ").forEach { (k, l) ->
                Chip(l, ui.musicView == k) { ui.musicView = k; ui.openMine = null; ui.stopSelecting() }
            }
        }
        val state = rememberLazyListState()
        LazyColumn(Modifier.fillMaxSize(), state = state) {
            Music.musicVersion   // read here too: the list is built apart from the screen, and must follow a scan
            Music.savedVersion
            if (!granted && ui.musicView == "all") item {
                ActionRow("Allow access to the music on this phone  ›") { access.ask() }
            } else if (ui.musicView == "all") item {
                val from = if (Music.folders.isEmpty()) "no folders" else Music.folders.joinToString { it.trimEnd('/') }
                ActionRow("From $from  ·  Choose folders  ›") { choosing = true }
            }
            when (ui.musicView) {
                "playlists" -> myPlaylists(ui) { importer.launch(arrayOf("*/*")) }
                "dj" -> djView(ui, ask) { ask = it }
                "moods" -> moodsView(ui)
                else -> {
                    val tracks = when (ui.musicView) {
                        "all" -> sorted(Music.mySongs(), Music.musicSort)
                        "recent" -> Music.plays.recent()
                        else -> Music.mostPlayed()
                    }
                    if (tracks.isEmpty()) item {
                        val (t, b) = when (ui.musicView) {
                            "all" -> "No music yet" to "Songs you download, and the music in your folders\n(${Music.folders.joinToString()}), show up here."
                            "recent" -> "Nothing played yet" to "The songs you listen to show up here, the last one first."
                            else -> "Nothing played yet" to "The songs you play most show up here."
                        }
                        Empty(t, b)
                    } else {
                        item {
                            if (ui.musicView == "all") {
                                val dl = Music.lib.items.size
                                Section("${tracks.size} SONGS · $dl DOWNLOADED · ${fmtTotal(tracks).uppercase()}")
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("SORT", style = T.section, modifier = Modifier.padding(end = 2.dp))
                                    listOf("title" to "Title", "artist" to "Artist", "album" to "Album", "added" to "Added").forEach { (k, l) ->
                                        Chip(l, Music.musicSort == k) { Music.setMusicSort(k) }
                                    }
                                }
                            } else Section(if (ui.musicView == "recent") "RECENTLY PLAYED"
                                           else if (Music.mostPlayedFromStats) "MOST PLAYED · STATS" else "MOST PLAYED")
                        }
                        if (ui.musicView == "all" && Music.musicSort in setOf("artist", "album")) grouped(tracks, ui, Music.musicSort == "artist")
                        else tracks(tracks, ui, ui.musicView) { t -> if (ui.musicView == "top") "${t.plays} play${if (t.plays != 1) "s" else ""}" else "" }
                    }
                }
            }
        }
    }
}

/** Moods: a playlist of each mood your songs are in, made by itself from the mood scan. */
private fun androidx.compose.foundation.lazy.LazyListScope.moodsView(ui: Ui) {
    Moods.version
    val pool = Moods.pool()
    val todo = Moods.todo(pool).size
    val counts = Moods.counts(pool)
    item(key = "moods-head") {
        if (Moods.scanning) {
            Section(Moods.status.uppercase())
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) { Pill("Stop", quiet = true) { Moods.stop = true } }
        } else {
            val where = if (Music.apiKeys["chartmetric"] != null) "Chartmetric, then Last.fm"
                else if (LastFm.key.isNotEmpty()) "Last.fm's tags" else "the songs' genres (connect Last.fm for better ones)"
            Section((if (todo > 0) "$todo SONG${if (todo != 1) "S" else ""} NOT SCANNED YET" else "ALL ${pool.size} SONGS SCANNED") +
                " · MOODS FROM ${where.uppercase()}")
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (todo > 0) Pill("Scan") { Moods.scanSoon() }
                Pill("Look again", quiet = true) { Moods.scanSoon(again = true) }
            }
        }
        if (counts.isNotEmpty()) Section("YOUR MOODS")
    }
    items(counts.size, key = { "mood-${counts[it].first}" }) { i ->
        val (mood, n) = counts[i]
        val songs = Moods.songsIn(mood, pool)
        val al = Album("mood-$mood", mood.replaceFirstChar { it.uppercase() }, "Mood · $n song${if (n != 1) "s" else ""}", "", "")
        PlaylistRow(al, songs.firstOrNull { it.thumb.isNotEmpty() || it.isFile }?.let { artModel(it) }) {
            ui.pages += Ui.Page.AlbumPage(al, "", songs)
        }
    }
}

/** The DJ: what it said last, Start or Switch it up, a box to ask it for something, and its songs to come. */
private fun androidx.compose.foundation.lazy.LazyListScope.djView(ui: Ui, ask: String, setAsk: (String) -> Unit) {
    val d = Music.shownDj()
    item(key = "dj") {
        val focus = LocalFocusManager.current
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("DJ" + (Dj.KINDS[d.kind]?.let { " · ${it.uppercase()}" } ?: ""), style = T.section)
            Text(d.line.ifEmpty {
                "Your own DJ: a mix of the songs you love, ones you haven't heard in a while and new ones from " +
                    "Tidal and YouTube Music, a few at a time. Switch it up for something else, or ask for a mood, " +
                    "a decade, a genre or an artist."
            }, style = if (d.line.isNotEmpty()) T.title else T.body)
            if (d.busy.isNotEmpty()) Text(d.busy, style = T.sub)
            Box {
                var menu by remember { mutableStateOf(false) }
                Pill("From: ${d.from.ifEmpty { "everything" }}  ▾", quiet = true) { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    val pick = { f: Dj.From -> menu = false; ui.djFrom(f) }
                    val item = @Composable { label: String, f: Dj.From ->
                        DropdownMenuItem(text = { Text(label, style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = { pick(f) })
                    }
                    item("Everything: your history and new songs", Dj.From("all"))
                    item("All your music · ${Music.mySongs().size}", Dj.From("mine"))
                    // The folders your songs are in, under the ones you picked, with how many each holds.
                    val counts = Music.local.paths.values.groupingBy { it }.eachCount()
                    val tops = Music.folders.filter { f -> counts.keys.any { it.startsWith(f) } }
                    for (top in tops) {
                        item("${top.trimEnd('/')} · ${counts.filterKeys { it.startsWith(top) }.values.sum()}", Dj.From("folder", path = top))
                        val subs = counts.keys.filter { it.startsWith(top) && it != top }.map { top + it.removePrefix(top).substringBefore('/') + "/" }.distinct().sorted()
                        for (sub in subs) item("      ${sub.removePrefix(top).trimEnd('/')} · ${counts.filterKeys { it.startsWith(sub) }.values.sum()}", Dj.From("folder", path = sub))
                    }
                    Music.lists.all().filter { it.tracks.isNotEmpty() }.forEach { item("${it.name} · ${it.tracks.size}", Dj.From("playlist", id = it.id)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (d.on) {
                    Pill("↻  Switch it up") { Music.djSwitch() }
                    Pill("Stop the DJ", quiet = true) { Music.djStop() }
                } else Pill("▶  Start the DJ") { Music.djStart() }
            }
            Box(Modifier.fillMaxWidth().border(1.dp, C.line).background(C.fg.copy(alpha = 0.04f)).padding(horizontal = 10.dp, vertical = 10.dp)) {
                if (ask.isEmpty()) Text("Ask the DJ: something chill, 2010s, house, Daft Punk…", style = T.body.copy(color = C.muted), maxLines = 1)
                BasicTextField(ask, setAsk, singleLine = true, textStyle = T.body, cursorBrush = SolidColor(C.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (ask.isNotBlank()) { focus.clearFocus(); Music.djSwitch(ask); setAsk("") }
                    }),
                    modifier = Modifier.fillMaxWidth())
            }
        }
    }
    if (!d.on) return
    val q = Music.shownQueue()
    val coming = (maxOf(0, Music.shownIndex()) until q.size).filter { q[it].dj > 0 }
    if (coming.isEmpty()) return
    item { Section("FROM THE DJ") }
    items(coming.size, key = { "dj${coming[it]}" }) { j ->
        val i = coming[j]
        TrackRow(q[i], RowPlace(q, i, "queue", queueIndex = i), ui, Dj.KINDS[d.kinds[q[i].dj.toString()]] ?: "") { Music.playIndex(i) }
    }
}

private fun leadArtist(t: Track) = t.artist.split(",")[0].trim().ifEmpty { "Unknown artist" }

/** Your songs, sorted as the Music tab's chips say: by title, artist, album, or newest first. */
private fun sorted(songs: List<Track>, sort: String): List<Track> = when (sort) {
    "artist" -> songs.sortedWith(compareBy({ norm(leadArtist(it)).ifEmpty { "~" } }, { norm(it.album) }, { norm(it.title) }))
    "album" -> songs.sortedWith(compareBy({ norm(it.album).ifEmpty { "~" } }, { norm(leadArtist(it)) }, { norm(it.title) }))
    "added" -> songs.sortedByDescending { it.added }
    else -> songs.sortedWith(compareBy({ norm(it.title) }, { norm(it.artist) }))
}

/** The songs under a heading per artist (tap it for their page) or per album. */
private fun androidx.compose.foundation.lazy.LazyListScope.grouped(list: List<Track>, ui: Ui, byArtist: Boolean) {
    var start = 0
    while (start < list.size) {
        val key = { t: Track -> if (byArtist) norm(leadArtist(t)) else norm(t.album) }
        val k = key(list[start])
        var end = start
        while (end < list.size && key(list[end]) == k) end++
        val first = list[start]
        val from = start
        item(key = "head#$from") {
            Column(Modifier.fillMaxWidth().then(if (byArtist) Modifier.clickable { ui.pages += Ui.Page.Artist(leadArtist(first), first.source) } else Modifier)
                .padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 4.dp)) {
                Text(if (byArtist) leadArtist(first) else first.album.ifEmpty { "No album" }, style = T.title, maxLines = 1)
                if (!byArtist) Text(leadArtist(first), style = T.sub, maxLines = 1)
            }
        }
        items(end - start, key = { "${list[from + it].id}#${from + it}" }) { j ->
            val i = from + j
            TrackRow(list[i], RowPlace(list, i, "all"), ui, "") { Music.playFrom(list, i, false) }
        }
        start = end
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.myPlaylists(ui: Ui, import: () -> Unit) {
    val open = ui.openMine?.let { Music.lists.get(it) }
    if (open == null) {
        item { ActionRow("＋  New playlist") { ui.askName = Triple("New playlist", "") { n -> Music.newPlaylist(n) } } }
        item {
            val busy = Music.importing
            if (busy != null) Text(busy, style = T.sub.copy(color = C.accent), modifier = Modifier.padding(12.dp))
            else ActionRow("⤓  Import a playlist (.m3u8)") { import() }
        }
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
                PlaylistRow(Album(pl.id, pl.name, "", "", pl.tracks.size.toString()), cover?.let { artModel(it) }) { ui.openMine = pl.id }
            }
        }
        return
    }
    item { BackRow(open.name) { ui.openMine = null } }
    item {
        ListActions(open.tracks, onDj = { ui.djFrom(Dj.From("playlist", id = open.id)) }, extra = listOf(
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
            item { Section(if (Music.shownRadio()) "UP NEXT · RADIO" else if (Music.shownDj().on) "UP NEXT · DJ" else "UP NEXT") }
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
        if (page.tracks != null) { tracks = page.tracks; return@LaunchedEffect }
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
                ArtImage(page.album.thumb.ifEmpty { null } ?: page.tracks?.firstOrNull()?.let { artModel(it) }, 96.dp)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(page.album.title, style = T.title.copy(fontSize = T.title.fontSize * 1.2f))
                    if (page.album.sub.isNotEmpty()) Text(page.album.sub, style = T.sub)
                }
            }
        }
        val t = tracks
        if (t == null) item { Section("LOADING…") } else {
            item { ListActions(t, onDj = { ui.djFrom(Dj.From("songs", name = page.album.title, songs = t)) }) }
            tracks(t, ui, "album")
        }
    }
}
