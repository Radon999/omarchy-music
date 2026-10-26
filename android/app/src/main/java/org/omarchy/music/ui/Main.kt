package org.omarchy.music.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import coil.compose.AsyncImage
import coil.imageLoader
import kotlinx.coroutines.flow.drop
import org.omarchy.music.LastFm
import org.omarchy.music.Music
import org.omarchy.music.Remote
import org.omarchy.music.SEARCH_IN
import org.omarchy.music.SOURCES
import org.omarchy.music.Track
import org.omarchy.music.fmtTime
import org.omarchy.music.liveSearchWait

// The layout, after Poweramp: the library is a list of what's in it (songs, playlists, artists,
// favorites, skips…), each opening on its own screen; a mini player sits at the bottom, and a tap
// on it opens the full-screen player (big cover, a waveform to seek on, the controls, and the cover
// swapping for the lyrics, the visualizer or the queue). Every feature is where it was before, a tap
// or two away: long-press or ⋮ on a song for its menu, ☰ for the accounts and settings.

@Composable
fun App() {
    val ui = remember { Ui() }
    val snack = remember { SnackbarHostState() }
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { for (t in Music.toasts) { snack.currentSnackbarData?.dismiss(); snack.showSnackbar(t) } }

    // The window in the colour of what's playing's cover.
    val cur = if (Remote.active) Remote.status.track else Music.current()
    var tint by remember { mutableStateOf<Pair<Color, Color>?>(null) }
    LaunchedEffect(cur?.id, Music.artColors, Music.savedVersion, Music.look) {
        tint = if (Music.artColors && cur != null && !C.isLight && !C.glow) Tint.of(ctx, ctx.imageLoader, if (Remote.active) Remote.artModel(cur) else artModel(cur)) else null
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
    // Going somewhere in the library (an artist from the player, the DJ…) closes the player.
    LaunchedEffect(Unit) {
        snapshotFlow { Triple(ui.tab, ui.pages.size, ui.musicView) }.drop(1).collect { ui.player = false }
    }
    val bg by animateColorAsState(tint?.first ?: C.bg, tween(700), label = "bg")
    val bar by animateColorAsState(tint?.second ?: C.darker, tween(700), label = "bar")

    BackHandler(enabled = ui.selecting || ui.player || ui.pages.isNotEmpty() || ui.openMine != null || ui.openList != null ||
        ui.openFolder != null || ui.tab != "home") {
        when {
            ui.selecting -> ui.stopSelecting()
            ui.player && ui.pane != "art" -> ui.pane = "art"
            ui.player -> ui.player = false
            ui.pages.isNotEmpty() -> ui.pages.removeAt(ui.pages.lastIndex)
            ui.openMine != null -> ui.openMine = null
            ui.openList != null -> ui.openList = null
            ui.openFolder != null -> ui.openFolder = null
            else -> goHome(ui)
        }
    }

    Box(Modifier.fillMaxSize().background(bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            val page = ui.pages.lastOrNull()
            if (page == null && ui.tab == "home") HomeHeader(ui) else ScreenHeader(ui, page)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (page != null) PageScreen(ui, page)
                else when (ui.tab) {
                    "home" -> Home(ui)
                    "search" -> SearchTab(ui)
                    "library" -> if (Music.browseLocal) MusicTab(ui) else LibraryTab(ui)   // Local has no Library
                    "music" -> MusicTab(ui)
                    "artists" -> ArtistsTab(ui)
                    "queue" -> QueueTab(ui)
                    "lyrics" -> LyricsTab(ui)
                    "visualizer" -> VisualizerTab()
                }
            }
            if (ui.selecting) SelectBar(ui, bar)
            MiniPlayer(ui, bar)
        }
        AnimatedVisibility(ui.player, enter = slideInVertically(tween(320)) { it } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(280)) { it } + fadeOut(tween(200))) {
            Player(ui)
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = if (ui.player) 40.dp else 96.dp))
        DesktopVolume(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp))
        Sheets(ui)
    }
}

private fun goHome(ui: Ui) {
    ui.tab = "home"; ui.pages.clear(); ui.openMine = null; ui.openList = null; ui.openFolder = null; ui.stopSelecting()
}

private fun goTo(ui: Ui, tab: String, view: String? = null) {
    ui.stopSelecting(); ui.pages.clear(); ui.openMine = null; ui.openList = null; ui.openFolder = null
    if (view != null) ui.musicView = view
    ui.tab = tab
}

/** A round icon button. */
@Composable
fun IconButton(ico: Ico, color: Color = C.fg, size: Dp = 24.dp, pad: Dp = 10.dp, desc: String = DESC[ico] ?: "", onClick: () -> Unit) {
    Box(Modifier.clip(R.pill).clickable(onClick = onClick).semantics { contentDescription = desc }.padding(pad),
        contentAlignment = Alignment.Center) { Icon(ico, color, size) }
}

/** What an icon button says to TalkBack. */
private val DESC = mapOf(Ico.MENU to "Menu", Ico.SEARCH to "Search", Ico.BACK to "Back", Ico.DOWN to "Close the player",
    Ico.MORE to "More", Ico.PLAY to "Play", Ico.PAUSE to "Pause", Ico.NEXT to "Next", Ico.PREV to "Previous",
    Ico.SHUFFLE to "Shuffle", Ico.REPEAT to "Repeat", Ico.REPEAT_ONE to "Repeat one", Ico.CLOSE to "Close")

// ───────────────────────────── The library ─────────────────────────────

@Composable
private fun HomeHeader(ui: Ui) {
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Library", style = T.brand.copy(shadow = C.shadow))
                // Playing on the desktop: said under the name.
                if (Remote.active) Text("PLAYING ON THE DESKTOP", style = T.section.copy(color = C.accent),
                    modifier = Modifier.clickable { ui.sheet = "accounts" })
            }
            IconButton(Ico.SEARCH) { goTo(ui, "search") }
            IconButton(Ico.MENU) { ui.sheet = "accounts" }
        }
        SourceChips(ui)
        SearchField(ui)
    }
}

/** Where you browse: a service, or your own music. */
@Composable
private fun SourceChips(ui: Ui) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("My music", Music.browseLocal) {   // your own songs: no Library tab, the search looks in them
            Music.browse("local")
            if (ui.tab == "library") goHome(ui)
        }
        SOURCES.forEach { (k, name) ->
            Chip(if (k == "ytm") "YouTube" else name.removeSuffix(" Music"), Music.source == k && !Music.browseLocal) {
                if (Music.source != k || Music.browseLocal) {
                    Music.browse(k)
                    ui.openList = null
                }
            }
        }
    }
}

/** A screen's header: back, its name, search and ☰. */
@Composable
private fun ScreenHeader(ui: Ui, page: Ui.Page?) {
    val title = when (page) {
        is Ui.Page.Artist -> page.name
        is Ui.Page.AlbumPage -> page.album.title
        null -> titleOf(ui)
    }
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 6.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(Ico.BACK) {
                when {
                    ui.pages.isNotEmpty() -> ui.pages.removeAt(ui.pages.lastIndex)
                    ui.openMine != null -> ui.openMine = null
                    ui.openList != null -> ui.openList = null
                    ui.openFolder != null -> ui.openFolder = null
                    else -> goHome(ui)
                }
            }
            Text(title, style = T.head, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 2.dp))
            if (ui.tab != "search" || page != null) IconButton(Ico.SEARCH) { goTo(ui, "search") }
            IconButton(Ico.MENU) { ui.sheet = "accounts" }
        }
        if (ui.tab == "search" && page == null) SearchField(ui)
        if (ui.tab == "library" && page == null && !Music.browseLocal) SourceChips(ui)
    }
}

private fun titleOf(ui: Ui): String = when (ui.tab) {
    "search" -> "Search"
    "library" -> if (Music.browseLocal) "My music" else "${SOURCES[Music.source] ?: "Service"} library"
    "artists" -> "Artists"
    "queue" -> "Up next"
    "lyrics" -> "Lyrics"
    "visualizer" -> "Visualizer"
    "music" -> CATEGORIES.firstOrNull { it.view == ui.musicView }?.label ?: "My music"
    else -> "Library"
}

private class Category(val label: String, val ico: Ico, val tab: String, val view: String? = null)

private val CATEGORIES = listOf(
    Category("All songs", Ico.NOTE, "music", "all"),
    Category("Playlists", Ico.PLAYLIST, "music", "playlists"),
    Category("Artists", Ico.PERSON, "artists"),
    Category("Recently played", Ico.CLOCK, "music", "recent"),
    Category("Most played", Ico.CHART, "music", "top"),
    Category("Favorites", Ico.STAR, "music", "favorites"),
    Category("Not favorited", Ico.STAR_LINE, "music", "unfavorited"),
    Category("Moods", Ico.MOOD, "music", "moods"),
    Category("Skips", Ico.SKIP, "music", "skips"),
    Category("Duplicates", Ico.COPY, "music", "dupes"),
    Category("DJ", Ico.DISC, "music", "dj"),
    Category("Up next", Ico.QUEUE, "queue"),
)

/** The library's front page: what's in it, Poweramp-style, with counts. */
@Composable
private fun Home(ui: Ui) {
    Music.musicVersion
    Music.savedVersion
    val songs = remember(Music.musicVersion, Music.savedVersion) { runCatching { Music.mySongs().size }.getOrDefault(0) }
    val counts = mapOf(
        "all" to songs,
        "favorites" to Music.favorites.size,
        "dupes" to (Music.dupes?.size ?: -1),
    )
    LazyColumn(Modifier.fillMaxSize()) {
        if (!Music.browseLocal) item {
            val name = SOURCES[Music.source] ?: "Service"
            CategoryRow("$name library", Ico.CLOUD, sub = "Your playlists on $name") { goTo(ui, "library") }
        }
        if (LastFm.connected) item {
            CategoryRow("Your ${org.omarchy.music.Wrapped.year()} Wrapped", Ico.SPARKLE, sub = "Your year, week or month in music") { ui.sheet = "wrapped" }
        }
        item { Section("MY MUSIC") }
        CATEGORIES.forEach { c ->
            item {
                val n = c.view?.let { counts[it] }?.takeIf { it >= 0 }
                CategoryRow(c.label, c.ico, count = n) { goTo(ui, c.tab, c.view) }
            }
        }
        item { Section("NOW PLAYING") }
        item { CategoryRow("Lyrics", Ico.LYRICS) { ui.pane = "lyrics"; ui.player = true } }
        item { CategoryRow("Visualizer", Ico.VIS) { ui.pane = "visualizer"; ui.player = true } }
        item { CategoryRow("Equalizer", Ico.EQ, sub = if (Music.eq.on) "On" else "Off") { ui.sheet = "eq" } }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun CategoryRow(label: String, ico: Ico, count: Int? = null, sub: String = "", onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp).clip(R.card).clickable(onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(R.card).background(C.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(ico, C.accent, 22.dp)
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(label, style = T.title.copy(fontSize = 16.sp))
            if (sub.isNotEmpty()) Text(sub, style = T.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (count != null) Text("%,d".format(count), style = T.sub, modifier = Modifier.padding(end = 6.dp))
        Icon(Ico.BACK, C.muted, 14.dp, Modifier.graphicsLayer(rotationZ = 180f))
    }
}

@Composable
private fun SearchField(ui: Ui) {
    val focus = LocalFocusManager.current
    var menu by remember { mutableStateOf(false) }
    // Search as you type: once you stop typing for a moment (a new letter starts the wait again).
    LaunchedEffect(ui.query, Music.searchIn) {
        val q = ui.query.trim()
        val wait = liveSearchWait(q, Music.searchIn) ?: return@LaunchedEffect
        kotlinx.coroutines.delay(wait.toLong())
        if (q != ui.searched) { ui.pages.clear(); runSearch(ui, quiet = true) }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp).clip(R.pill)
        .background(C.fg.copy(alpha = 0.07f)), verticalAlignment = Alignment.CenterVertically) {
        Icon(Ico.SEARCH, C.muted, 18.dp, Modifier.padding(start = 14.dp))
        Box(Modifier.weight(1f).padding(start = 10.dp, top = 12.dp, bottom = 12.dp)) {
            if (ui.query.isEmpty()) Text("Songs, artists, albums or a link", style = T.body.copy(color = C.muted), maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            BasicTextField(ui.query, { ui.query = it; if (it.isNotBlank() && ui.tab != "search") goTo(ui, "search") }, singleLine = true,
                textStyle = T.body, cursorBrush = SolidColor(C.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); ui.pages.clear(); runSearch(ui) }),
                modifier = Modifier.fillMaxWidth())
        }
        if (ui.query.isNotEmpty()) IconButton(Ico.CLOSE, C.muted, 16.dp, 8.dp) { ui.query = "" }
        // Where to search: Tidal, YouTube Music or your own songs.
        Box {
            Text("${if (Music.searchIn == "ytm") "YouTube" else SEARCH_IN[Music.searchIn]}  ▾", style = T.tab.copy(color = C.accent),
                modifier = Modifier.clip(R.pill).clickable { menu = true }.padding(horizontal = 14.dp, vertical = 12.dp))
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
private fun SelectBar(ui: Ui, bar: Color) {
    val n = ui.selected.size
    val place = ui.selectPlace
    Row(Modifier.fillMaxWidth().background(bar).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("$n", style = T.head.copy(color = C.accent), modifier = Modifier.padding(horizontal = 8.dp))
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
            if (picked().any { org.omarchy.music.Tags.editable(it) != null }) Pill("Tags", quiet = true) { ui.editTagsFor = picked() }
            if (picked().any { Music.deletable(it) }) Pill("Delete", quiet = true) { ui.deleteFor = picked() }
            Pill("Cover", quiet = true) { ui.pickCovers(picked()); ui.stopSelecting() }
            Pill("Speed", quiet = true) { ui.speedFor = picked() }
            Pill("Volume", quiet = true) { ui.volumeFor = picked() }
            Pill("★", quiet = true) { Music.setFavorite(picked()); ui.stopSelecting() }
            Pill("Moods", quiet = true) { ui.moodsFor = picked() }
            if (Music.upgrading == null && picked().any { org.omarchy.music.upgradable(it) })
                Pill("Upgrade", quiet = true) { ui.upgradeFor = picked().filter { org.omarchy.music.upgradable(it) } }
            Pill("All", quiet = true) { ui.selected.clear(); ui.selected.addAll(ui.selectPool.map { it.id }.distinct()) }
        }
        IconButton(Ico.CLOSE, C.fg, 20.dp) { ui.stopSelecting() }
    }
}

// ───────────────────────────── What's playing ─────────────────────────────

private class Now(val t: Track?, val paused: Boolean, val pos: Double, val dur: Double, val remote: Boolean)

@Composable
private fun now(): Now {
    val remote = Remote.active
    return Now(if (remote) Remote.status.track else Music.current(), if (remote) Remote.status.paused else Music.paused,
        if (remote) Remote.status.position else Music.position, if (remote) Remote.status.duration else Music.duration, remote)
}

/** The bar at the bottom: the song, a thin line of how far in, play/pause and next. A tap opens the player. */
@Composable
private fun MiniPlayer(ui: Ui, bar: Color) {
    val n = now()
    val t = n.t
    Column(Modifier.fillMaxWidth().background(bar).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(C.fg.copy(alpha = 0.08f))) {
            Box(Modifier.fillMaxWidth(if (n.dur > 0) (n.pos / n.dur).toFloat().coerceIn(0f, 1f) else 0f).height(2.dp).background(C.accent))
        }
        Row(Modifier.fillMaxWidth().clickable(enabled = t != null) { ui.pane = "art"; ui.player = true }
            .pointerInput(Unit) {
                var dy = 0f
                detectVerticalDragGestures(onDragStart = { dy = 0f }, onDragEnd = { if (dy < -40f && t != null) { ui.pane = "art"; ui.player = true } }) { _, d -> dy += d }
            }
            .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ArtImage(t?.let { if (n.remote) Remote.artModel(it) else artModel(it, 300) }, 46.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(t?.title ?: "Nothing playing", style = T.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val status = if (n.remote) Remote.status.message else Music.status
                Text(if (t == null) "Search for a song, or pick one from your library" else if (status.isNotEmpty()) status else t.artist,
                    style = T.sub.copy(color = if (status.isNotEmpty() && t != null) C.accent else C.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (t != null) {
                IconButton(if (n.paused) Ico.PLAY else Ico.PAUSE, C.fg, 26.dp, 8.dp) { Music.togglePause() }
                IconButton(Ico.NEXT, C.fg, 22.dp, 8.dp) { Music.next() }
            }
        }
    }
}

/** The full-screen player, after Poweramp's: the cover big over a blur of itself, a waveform to seek on, the controls. */
@Composable
private fun Player(ui: Ui) {
    val n = now()
    val t = n.t
    val art = t?.let { if (n.remote) Remote.artModel(it) else artModel(it, 1200) }
    Box(Modifier.fillMaxSize().background(C.darker).pointerInput(Unit) { detectTapGestures { } }) {   // taps stay here
        // The cover again, blurred and dimmed, behind everything (Android 12 and up blur it; before that it's dimmed more).
        if (art != null && !C.isLight) AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().blur(70.dp).graphicsLayer(alpha = if (android.os.Build.VERSION.SDK_INT >= 31) 0.55f else 0.18f))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
            (if (C.isLight) C.bg else Color.Black).copy(alpha = 0.25f),
            (if (C.isLight) C.bg else Color.Black).copy(alpha = 0.55f),
            (if (C.isLight) C.bg else C.darker).copy(alpha = 0.95f)))))
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            PlayerTop(ui, n)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when (ui.pane) {
                    "lyrics" -> PaneBox { LyricsTab(ui) }
                    "visualizer" -> PaneBox { VisualizerTab() }
                    "queue" -> PaneBox { QueueTab(ui) }
                    else -> Cover(ui, art, t != null)
                }
            }
            SongInfo(ui, n)
            WaveSeek(n)
            Controls(n)
            Toolbar(ui, n)
        }
    }
}

@Composable
private fun PaneBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(horizontal = 10.dp).clip(R.big).background(C.bg.copy(alpha = 0.55f))) { content() }
}

@Composable
private fun PlayerTop(ui: Ui, n: Now) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)
        .pointerInput(Unit) {
            var dy = 0f
            detectVerticalDragGestures(onDragStart = { dy = 0f }, onDragEnd = { if (dy > 60f) ui.player = false }) { _, d -> dy += d }
        }, verticalAlignment = Alignment.CenterVertically) {
        IconButton(Ico.DOWN) { ui.player = false }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (n.remote) "PLAYING ON THE DESKTOP" else "NOW PLAYING", style = T.section.copy(color = if (n.remote) C.accent else C.muted))
            val from = when {
                n.remote -> ""
                Music.shownRadio() -> "Radio"
                Music.shownDj().on -> "DJ"
                n.t != null -> if (n.t.isFile || Music.lib.has(n.t.id)) "My music" else SOURCES[n.t.source] ?: ""
                else -> ""
            }
            if (from.isNotEmpty()) Text(from, style = T.sub.copy(color = C.fg), maxLines = 1)
        }
        Box {
            IconButton(Ico.MORE) { if (n.t != null) menu = true }
            if (n.t != null && !n.remote) TrackMenu(n.t, RowPlace(listOf(n.t), 0), ui, menu) { menu = false }
        }
    }
}

/** The cover, big: swipe it for the next or the last song, tap it for the lyrics. */
@Composable
private fun Cover(ui: Ui, art: Any?, playing: Boolean) {
    var dx by remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        val side = if (maxWidth < maxHeight) maxWidth else maxHeight
        Box(Modifier.size(side)
            .graphicsLayer(translationX = dx * 0.6f, rotationZ = dx / 90f, alpha = 1f - (kotlin.math.abs(dx) / 900f).coerceIn(0f, 0.5f))
            .shadow(24.dp, R.big, ambientColor = C.accent.copy(alpha = if (C.glow) 0.9f else 0.3f), spotColor = C.accent.copy(alpha = if (C.glow) 0.9f else 0.3f))
            .clip(R.big).background(C.light)
            .then(if (C.glow) Modifier.border(1.dp, C.accent.copy(alpha = 0.7f), R.big) else Modifier)
            .pointerInput(playing) {
                detectHorizontalDragGestures(onDragEnd = {
                    if (playing) { if (dx < -160f) Music.next() else if (dx > 160f) Music.previous() }
                    dx = 0f
                }, onDragCancel = { dx = 0f }) { _, d -> dx += d }
            }
            .pointerInput(Unit) { detectTapGestures(onTap = { ui.pane = "lyrics" }, onDoubleTap = { Music.togglePause() }) },
            contentAlignment = Alignment.Center) {
            Icon(Ico.NOTE, C.muted.copy(alpha = 0.5f), side * 0.3f)   // under the cover: shows when there's none
            if (art != null) AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun SongInfo(ui: Ui, n: Now) {
    val t = n.t
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(t?.title ?: "Nothing playing", style = T.big.copy(shadow = C.shadow), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        if (t != null) {
            ArtistLinks(t.artist, ui, t.source, style = T.body.copy(color = C.accent, textAlign = TextAlign.Center))
            if (t.album.isNotEmpty()) Text(t.album, style = T.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else Text("Search for a song to start", style = T.sub)
        // What it is: FLAC or streaming, its own volume and speed, and what the app is doing.
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!n.remote) SaveMark()
            if (t != null && !n.remote) {
                Music.speeds.get(t).speed.let { if (it != 1.0) Badge(org.omarchy.music.fmtSpeed(it), false) }
                Music.gains.get(t).let { if (it != 0.0) Badge("%+.1f dB".format(it), false) }
            }
            val status = if (n.remote) Remote.status.message else Music.status
            if (status.isNotEmpty()) Text(status, style = T.time.copy(color = C.accent), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The seek bar as a waveform (the shape is the song's own, made up from its name: the same each time). */
@Composable
private fun WaveSeek(n: Now) {
    val t = n.t
    val bars = remember(t?.id) {
        val r = java.util.Random((t?.id ?: "").hashCode().toLong())
        val raw = FloatArray(64) { 0.25f + r.nextFloat() * 0.75f }
        FloatArray(64) { i -> ((raw[(i - 1).coerceAtLeast(0)] + raw[i] * 2 + raw[(i + 1).coerceAtMost(63)]) / 4f).coerceIn(0.15f, 1f) }
    }
    var drag by remember { mutableStateOf<Float?>(null) }
    val frac = drag ?: if (n.dur > 0) (n.pos / n.dur).toFloat().coerceIn(0f, 1f) else 0f
    val accent = C.accent
    val rest = C.fg.copy(alpha = 0.22f)
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp)) {
        Canvas(Modifier.fillMaxWidth().height(46.dp)
            .pointerInput(n.dur) {
                detectTapGestures { o -> if (n.dur > 0) Music.seek((o.x / size.width).coerceIn(0f, 1f) * n.dur) }
            }
            .pointerInput(n.dur) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { drag?.let { if (n.dur > 0) Music.seek(it * n.dur) }; drag = null },
                    onDragCancel = { drag = null },
                ) { change, _ -> drag = (change.position.x / size.width).coerceIn(0f, 1f) }
            }) {
            val count = bars.size
            val step = size.width / count
            val w = step * 0.55f
            for (i in 0 until count) {
                val h = size.height * bars[i]
                val x = i * step + (step - w) / 2
                val played = (i + 0.5f) / count <= frac
                drawRoundRect(if (played) accent else rest, Offset(x, (size.height - h) / 2), Size(w, h), CornerRadius(w / 2))
            }
            // Where you are.
            val px = frac * size.width
            drawRoundRect(accent, Offset(px - 1.5f, 0f), Size(3f, size.height), CornerRadius(1.5f))
        }
        Row(Modifier.fillMaxWidth()) {
            Text(fmtTime(drag?.let { it * n.dur } ?: n.pos), style = T.time)
            Spacer(Modifier.weight(1f))
            Text(fmtTime(n.dur), style = T.time)
        }
    }
}

@Composable
private fun Controls(n: Now) {
    val rep = if (n.remote) Remote.status.repeat else Music.repeat
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        IconButton(Ico.SHUFFLE, C.fg, 24.dp) { Music.shuffle() }
        IconButton(Ico.PREV, C.fg, 32.dp) { Music.previous() }
        Box(Modifier.size(76.dp).shadow(if (C.glow) 18.dp else 8.dp, R.pill, ambientColor = C.accent, spotColor = C.accent)
            .clip(R.pill).background(C.accent).clickable { Music.togglePause() }, contentAlignment = Alignment.Center) {
            Icon(if (n.paused) Ico.PLAY else Ico.PAUSE, if (C.isLight) C.light else C.darker, 34.dp)
        }
        IconButton(Ico.NEXT, C.fg, 32.dp) { Music.next() }
        IconButton(if (rep == "one") Ico.REPEAT_ONE else Ico.REPEAT, if (rep != "off") C.accent else C.fg, 24.dp) { Music.cycleRepeat() }
    }
}

/** The row under the controls: favorite, like, and the cover's other faces. */
@Composable
private fun Toolbar(ui: Ui, n: Now) {
    val t = n.t
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically) {
        if (t != null && !n.remote) {
            Music.musicVersion
            val fav = Music.favorites.has(t)
            Tool(if (fav) Ico.STAR else Ico.STAR_LINE, "Favorite", fav) { Music.setFavorite(listOf(t)) }
        }
        val src = t?.let { Music.sources[it.source] }
        if (t != null && (n.remote || src?.signedIn == true)) {
            val liked = if (n.remote) Remote.status.liked else t.id in Music.liked
            Tool(if (liked) Ico.HEART else Ico.HEART_LINE, "Like", liked) { Music.toggleLike() }
        }
        Tool(Ico.EQ, "EQ", Music.eq.on) { ui.sheet = "eq" }
        Tool(Ico.LYRICS, "Lyrics", ui.pane == "lyrics") { ui.pane = if (ui.pane == "lyrics") "art" else "lyrics" }
        Tool(Ico.VIS, "Visualizer", ui.pane == "visualizer") { ui.pane = if (ui.pane == "visualizer") "art" else "visualizer" }
        Tool(Ico.QUEUE, "Up next", ui.pane == "queue") { ui.pane = if (ui.pane == "queue") "art" else "queue" }
    }
}

@Composable
private fun Tool(ico: Ico, label: String, on: Boolean, onClick: () -> Unit) {
    Column(Modifier.clip(R.card).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(ico, if (on) C.accent else C.fg.copy(alpha = 0.85f), 22.dp)
        Text(label, style = T.time.copy(fontSize = 10.sp, color = if (on) C.accent else C.muted), modifier = Modifier.padding(top = 3.dp))
    }
}

/** The FLAC mark: on disk, being saved, or streaming (tap to download). */
@Composable
private fun SaveMark() {
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
    Box(Modifier.clip(R.small).clickable(enabled = stream && !saving) {
        if (lossy != null) Music.toast("Streaming ${Music.dl.streamQuality(t.id)} from $lossy: Tidal has no FLAC of this song")
        else Music.saveCurrent()
    }) { Badge(label, saved != null || (stream && lossy == null)) }
}

/** The desktop's volume for a moment after a volume button changed it (the phone's own bar shows the phone's). */
@Composable
private fun DesktopVolume(modifier: Modifier) {
    val at = Remote.volumeShown
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(at) { if (at != 0L) { visible = true; kotlinx.coroutines.delay(1500); visible = false } }
    if (!visible || !Remote.hasVolume) return
    val v = if (Remote.status.muted) 0 else Remote.status.volume
    Row(modifier.clip(R.pill).background(C.light).border(1.dp, C.line, R.pill).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("Desktop volume", style = T.sub, modifier = Modifier.padding(end = 10.dp))
        Box(Modifier.width(120.dp).height(4.dp).clip(R.pill).background(C.line)) {
            Box(Modifier.fillMaxWidth(v / 100f).fillMaxHeight().background(C.accent))
        }
        Text("$v%", style = T.tab.copy(color = C.fg), modifier = Modifier.padding(start = 10.dp).width(40.dp))
    }
}
