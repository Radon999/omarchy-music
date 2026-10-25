package org.omarchy.music.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.imageLoader
import org.omarchy.music.Music
import org.omarchy.music.Remote
import org.omarchy.music.SEARCH_IN
import org.omarchy.music.SOURCES
import org.omarchy.music.fmtTime

@Composable
fun App() {
    val ui = remember { Ui() }
    val snack = remember { SnackbarHostState() }
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { for (t in Music.toasts) { snack.currentSnackbarData?.dismiss(); snack.showSnackbar(t) } }

    // The window in the colour of what's playing's cover.
    val cur = if (Remote.active) Remote.status.track else Music.current()
    var tint by remember { mutableStateOf<Pair<Color, Color>?>(null) }
    LaunchedEffect(cur?.id, Music.artColors, Music.savedVersion) {
        tint = if (Music.artColors && cur != null) Tint.of(ctx, ctx.imageLoader, if (Remote.active) Remote.artModel(cur) else artModel(cur)) else null
    }
    // A link shared from another app: search it on the service it's from.
    LaunchedEffect(Music.shared) {
        val text = Music.shared ?: return@LaunchedEffect
        Music.shared = null
        val link = Regex("https?://\\S+").find(text)?.value ?: text
        listOf("spotify" to "spotify.com", "apple" to "music.apple.com", "tidal" to "tidal.com", "ytm" to "youtu")
            .firstOrNull { (k, host) -> host in link && Music.sources.containsKey(k) }?.let { Music.setSource(it.first) }
        ui.pages.clear(); ui.tab = "search"; ui.query = link
        runSearch(ui)
    }
    val bg by animateColorAsState(tint?.first ?: C.bg, tween(700), label = "bg")
    val bar by animateColorAsState(tint?.second ?: C.darker, tween(700), label = "bar")

    BackHandler(enabled = ui.selecting || ui.pages.isNotEmpty() || ui.openMine != null || ui.openList != null) {
        when {
            ui.selecting -> ui.stopSelecting()
            ui.pages.isNotEmpty() -> ui.pages.removeAt(ui.pages.lastIndex)
            ui.openMine != null -> ui.openMine = null
            else -> ui.openList = null
        }
    }

    Box(Modifier.fillMaxSize().background(bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TopBar(ui)
            SearchField(ui)
            Tabs(ui)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val page = ui.pages.lastOrNull()
                if (page != null) PageScreen(ui, page)
                else when (ui.tab) {
                    "search" -> SearchTab(ui)
                    "library" -> LibraryTab(ui)
                    "music" -> MusicTab(ui)
                    "queue" -> QueueTab(ui)
                    "lyrics" -> LyricsTab(ui)
                }
            }
            if (ui.selecting) SelectBar(ui, bar)
            NowPlaying(ui, bar)
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = 150.dp))
        Sheets(ui)
    }
}

@Composable
private fun TopBar(ui: Ui) {
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("▶ ", style = T.brand.copy(color = C.accent))
        Column {
            Text("MUSIC", style = T.brand)
            // Playing on the desktop: said under the name, so the services and ☰ keep their room.
            if (Remote.active) Text("ON DESKTOP", style = T.section.copy(color = C.accent, fontSize = 9.sp),
                modifier = Modifier.clickable { ui.sheet = "accounts" })
        }
        // The services scroll in the room left; ☰ always stays on screen.
        Row(Modifier.weight(1f).padding(start = 8.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.End)) {
            SOURCES.forEach { (k, name) ->
                Chip(if (k == "ytm") "YouTube" else name.removeSuffix(" Music"), Music.source == k) {
                    if (Music.source != k) {
                        Music.setSource(k)
                        ui.openList = null
                    }
                }
            }
        }
        Text("☰", style = T.title.copy(fontSize = 20.sp), modifier = Modifier.clickable { ui.sheet = "accounts" }.padding(10.dp))
    }
}

@Composable
private fun SearchField(ui: Ui) {
    val focus = LocalFocusManager.current
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)
        .border(1.dp, C.line).background(C.fg.copy(alpha = 0.04f)), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).padding(start = 12.dp, top = 10.dp, bottom = 10.dp)) {
            if (ui.query.isEmpty()) Text("Songs, artists, albums or a link", style = T.body.copy(color = C.muted), maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            BasicTextField(ui.query, { ui.query = it; if (it.isNotBlank()) ui.tab = "search" }, singleLine = true,
                textStyle = T.body, cursorBrush = SolidColor(C.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); ui.pages.clear(); runSearch(ui) }),
                modifier = Modifier.fillMaxWidth())
        }
        // Where to search: Tidal, YouTube Music or your own songs.
        Box {
            Text("${if (Music.searchIn == "ytm") "YouTube" else SEARCH_IN[Music.searchIn]}  ▾", style = T.tab.copy(color = C.accent),
                modifier = Modifier.clickable { menu = true }.padding(horizontal = 12.dp, vertical = 10.dp))
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                SEARCH_IN.forEach { (k, name) ->
                    DropdownMenuItem(text = { Text(name, style = T.body.copy(color = if (k == Music.searchIn) C.accent else C.fg)) }, onClick = {
                        menu = false
                        if (k != Music.searchIn) {
                            Music.setSearchIn(k)
                            if (ui.query.isNotBlank() && ui.tab == "search") { ui.pages.clear(); runSearch(ui) }
                        }
                    })
                }
            }
        }
    }
}

@Composable
private fun Tabs(ui: Ui) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("search" to "Search", "library" to "Library", "music" to "Music", "queue" to "Up next", "lyrics" to "Lyrics").forEach { (k, l) ->
            Chip(l, ui.tab == k && ui.pages.isEmpty()) { ui.tab = k; ui.pages.clear(); ui.stopSelecting() }
        }
    }
}

@Composable
private fun SelectBar(ui: Ui, bar: Color) {
    val n = ui.selected.size
    val place = ui.selectPlace
    Row(Modifier.fillMaxWidth().background(bar).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("$n", style = T.title.copy(color = C.accent), modifier = Modifier.padding(horizontal = 6.dp))
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val picked = { ui.selectedTracks() }
            if (place?.kind == "queue") Pill("Remove") {
                val ids = ui.selected.toSet()
                Music.unqueueMany(Music.queue.indices.filter { Music.queue[it].id in ids }); ui.stopSelecting()
            } else Pill("Queue") { Music.enqueue(picked()); ui.stopSelecting() }
            Pill("+ Playlist") { ui.pickPlaylistFor = picked(); ui.stopSelecting() }
            if (place?.playlistId != null) Pill("Remove", quiet = true) {
                Music.removeFromPlaylist(place.playlistId, ui.selected.toSet()); ui.stopSelecting()
            }
            Pill("Download", quiet = true) { Music.download(picked()); ui.stopSelecting() }
            Pill("All", quiet = true) { ui.selected.clear(); ui.selected.addAll(ui.selectPool.map { it.id }.distinct()) }
        }
        Text("✕", style = T.title, modifier = Modifier.clickable { ui.stopSelecting() }.padding(10.dp))
    }
}

@Composable
private fun NowPlaying(ui: Ui, bar: Color) {
    val remote = Remote.active
    val t = if (remote) Remote.status.track else Music.current()
    val paused = if (remote) Remote.status.paused else Music.paused
    val pos = if (remote) Remote.status.position else Music.position
    val dur = if (remote) Remote.status.duration else Music.duration
    Column(Modifier.fillMaxWidth().background(bar).navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ArtImage(t?.let { if (remote) Remote.artModel(it) else artModel(it, 544) }, 56.dp,
                Modifier.clickable { ui.tab = "lyrics"; ui.pages.clear() })
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(t?.title ?: "Nothing playing", style = T.title.copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (t != null) ArtistLinks(t.artist, ui, t.source, suffix = if (t.album.isNotEmpty()) " · ${t.album}" else "")
                else Text("Search for a song to start", style = T.sub)
                val status = if (remote) Remote.status.message else Music.status
                if (status.isNotEmpty()) Text(status, style = T.time.copy(color = C.accent))
            }
            val src = t?.let { Music.sources[it.source] }
            if (t != null && (remote || src?.signedIn == true)) {
                val liked = if (remote) Remote.status.liked else t.id in Music.liked
                Text(if (liked) "♥" else "♡", style = T.title.copy(fontSize = 22.sp, color = if (liked) C.accent else C.fg),
                    modifier = Modifier.clickable { Music.toggleLike() }.padding(8.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(fmtTime(pos), style = T.time)
            var dragging by remember { mutableStateOf<Float?>(null) }
            Slider(value = dragging ?: if (dur > 0) (pos / dur).toFloat().coerceIn(0f, 1f) else 0f,
                onValueChange = { dragging = it }, onValueChangeFinished = { dragging?.let { Music.seek(it * dur) }; dragging = null },
                colors = SliderDefaults.colors(thumbColor = C.accent, activeTrackColor = C.accent, inactiveTrackColor = C.fg.copy(alpha = 0.10f)),
                modifier = Modifier.weight(1f).height(28.dp).padding(horizontal = 8.dp))
            Text(fmtTime(dur), style = T.time)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Ctl("⤮") { Music.shuffle() }
            val rep = if (remote) Remote.status.repeat else Music.repeat
            Ctl(if (rep == "one") "↻¹" else "↻", on = rep != "off") { Music.cycleRepeat() }
            Spacer(Modifier.weight(1f))
            Ctl("⏮") { Music.previous() }
            Box(Modifier.padding(horizontal = 6.dp).size(46.dp).background(C.accent).clickable { Music.togglePause() },
                contentAlignment = Alignment.Center) {
                if (paused) Text("▶", style = T.title.copy(color = C.darker, fontSize = 20.sp))
                else Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {   // drawn: ⏸ comes out as an emoji
                    repeat(2) { Box(Modifier.size(5.dp, 18.dp).background(C.darker)) }
                }
            }
            Ctl("⏭") { Music.next() }
            Spacer(Modifier.weight(1f))
            if (!remote) SaveMark(ui)
            Text("EQ", style = T.tab.copy(color = if (Music.eq.on) C.accent else C.fg, fontSize = 12.sp),
                modifier = Modifier.clickable { ui.sheet = "eq" }.padding(horizontal = 8.dp, vertical = 10.dp))
        }
    }
}

@Composable
private fun Ctl(glyph: String, on: Boolean = false, onClick: () -> Unit) {
    Text(glyph, style = T.title.copy(fontSize = 20.sp, color = if (on) C.accent else C.fg),
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp).width(26.dp))
}

/** The FLAC mark by the controls: on disk, being saved, or streaming (tap to download). */
@Composable
private fun SaveMark(ui: Ui) {
    Music.savedVersion
    val t = Music.current() ?: return
    val saved = Music.lib.items[t.id]
    val saving = Music.dl.busy(t.id) || Music.downloads.any { it.id == t.id }
    val stream = saved == null && !t.isFile
    val lossy = if (stream) Music.dl.streamLossy(t.id) else null   // Tidal has no FLAC of it: nothing to download
    val label = when {
        saved?.format == "flac" -> "FLAC"
        saved != null -> "✓ offline"
        saving -> "Saving…"
        lossy != null -> Music.dl.streamQuality(t.id).substringBefore(' ').ifEmpty { "lossy" }
        stream -> "⤓ FLAC"
        else -> ""
    }
    if (label.isEmpty()) return
    Text(label, style = T.time.copy(color = if (saved != null) C.accent else C.muted),
        modifier = Modifier.clickable(enabled = stream && !saving) {
            if (lossy != null) Music.toast("Streaming ${Music.dl.streamQuality(t.id)} from $lossy: Tidal has no FLAC of this song")
            else Music.saveCurrent()
        }.padding(horizontal = 6.dp, vertical = 10.dp))
}
