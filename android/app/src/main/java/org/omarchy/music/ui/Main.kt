package org.omarchy.music.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.imageLoader
import org.omarchy.music.LastFm
import org.omarchy.music.Music
import org.omarchy.music.Remote
import org.omarchy.music.SEARCH_IN
import org.omarchy.music.SOURCES
import org.omarchy.music.Track
import org.omarchy.music.fmtTime
import org.omarchy.music.liveSearchWait

// The layout, after Stellio: the player is the main screen. ☰ (top left) slides out the menu of
// everything in your library (My music, Albums, Artists, Genres, Playlists, Folders, your queues…);
// the gear (top right) opens Settings, full screen. A list keeps a floating mini player at the
// bottom: a tap on it goes back to the player. Long-press or ⋮ on a song for its menu, or pick
// several (from as many lists as you like) for the Options.

@Composable
fun App() {
    val ui = remember { Ui() }
    val snack = remember { SnackbarHostState() }
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { for (t in Music.toasts) { snack.currentSnackbarData?.dismiss(); snack.showSnackbar(t) } }

    // Dark icons on the system bars over a light look.
    val view = LocalView.current
    SideEffect {
        (view.context as? android.app.Activity)?.window?.let { w ->
            androidx.core.view.WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = C.isLight
                isAppearanceLightNavigationBars = C.isLight
            }
        }
    }

    // The window in the colour of what's playing's cover (dark plain looks only).
    val cur = if (Remote.active) Remote.status.track else Music.current()
    var tint by remember { mutableStateOf<Pair<Color, Color>?>(null) }
    LaunchedEffect(cur?.id, Music.artColors, Music.savedVersion, Music.look) {
        tint = if (Music.artColors && cur != null && !C.isLight && !C.glow && C.gradient == null)
            Tint.of(ctx, ctx.imageLoader, if (Remote.active) Remote.artModel(cur) else artModel(cur)) else null
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

    // The ☰ menu: out only when you tap ☰ (a swipe on the player is for the cover).
    val drawer = rememberDrawerState(DrawerValue.Closed)
    LaunchedEffect(ui.drawer) { if (ui.drawer) drawer.open() else drawer.close() }
    LaunchedEffect(drawer.currentValue) { ui.drawer = drawer.currentValue == DrawerValue.Open }

    // A new queue takes the name of the list its songs came from.
    val page = ui.pages.lastOrNull()
    val title = titleOf(ui, page)
    LaunchedEffect(title, ui.query) { Music.listName = if (ui.tab == "search" && page == null) "Search: ${ui.query.trim()}" else title }

    BackHandler(enabled = ui.drawer || ui.selecting || ui.pane != "art" || page != null || ui.openMine != null ||
        ui.openList != null || ui.openFolder != null || ui.tab != "player") {
        when {
            ui.drawer -> ui.drawer = false
            ui.selecting -> ui.stopSelecting()
            ui.tab == "player" && ui.pane != "art" -> ui.pane = "art"
            ui.pages.isNotEmpty() -> ui.pages.removeAt(ui.pages.lastIndex)
            ui.openMine != null -> ui.openMine = null
            ui.openList != null -> ui.openList = null
            ui.openFolder != null -> ui.openFolder = null
            else -> goTo(ui, "player")
        }
    }

    ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = ui.drawer, scrimColor = Color.Black.copy(alpha = 0.35f),
        drawerContent = { DrawerSheet(ui) }) {
        Box(Modifier.fillMaxSize().background(bg).then(C.gradient?.let { Modifier.background(Brush.linearGradient(it)) } ?: Modifier)) {
            if (C.stars) Stars()
            if (ui.tab == "player" && page == null) {
                Player(ui)
                if (ui.selecting) Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) { SelectBar(ui) }
            } else Column(Modifier.fillMaxSize().statusBarsPadding()) {
                ScreenHeader(ui, page, title)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (page != null) PageScreen(ui, page)
                    else when (ui.tab) {
                        "search" -> SearchTab(ui)
                        "library" -> if (Music.browseLocal) MusicTab(ui) else LibraryTab(ui)   // My music has no service library
                        "music" -> MusicTab(ui)
                        "artists" -> ArtistsTab(ui)
                        "albums" -> AlbumsTab(ui)
                        "genres" -> GenresTab(ui)
                        "folders" -> FoldersTab(ui)
                        "queue" -> QueueTab(ui)
                        "lyrics" -> LyricsTab(ui)
                        "visualizer" -> VisualizerTab()
                    }
                }
                Box(Modifier.navigationBarsPadding()) { if (ui.selecting) SelectBar(ui) else MiniPlayer(ui) }
            }
            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 90.dp))
            DesktopVolume(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp))
            Sheets(ui)
            if (ui.showOptions) OptionsSheet(ui)
            if (ui.showQueues) QueuesDialog(ui)
        }
    }
}

/** A few stars over the galaxy looks: drawn once, light. */
@Composable
private fun Stars() {
    val stars = remember {
        val r = java.util.Random(7)
        List(110) { Triple(r.nextFloat(), r.nextFloat(), r.nextFloat()) }
    }
    val col = Color.White
    Canvas(Modifier.fillMaxSize()) {
        for ((x, y, k) in stars) drawCircle(col.copy(alpha = 0.25f + k * 0.6f), radius = (0.6f + k * 1.4f) * density,
            center = Offset(x * size.width, y * size.height))
    }
}

private fun goTo(ui: Ui, tab: String, view: String? = null) {
    ui.pages.clear(); ui.openMine = null; ui.openList = null; ui.openFolder = null   // what you've picked stays picked
    if (view != null) ui.musicView = view
    ui.tab = tab
    ui.drawer = false
}

/** A round icon button. */
@Composable
fun IconButton(ico: Ico, color: Color = C.fg, size: Dp = 24.dp, pad: Dp = 10.dp, desc: String = DESC[ico] ?: "", onClick: () -> Unit) {
    Box(Modifier.clip(R.pill).clickable(onClick = onClick).semantics { contentDescription = desc }.padding(pad),
        contentAlignment = Alignment.Center) { Icon(ico, color, size) }
}

/** What an icon button says to TalkBack. */
private val DESC = mapOf(Ico.MENU to "Menu", Ico.SEARCH to "Search", Ico.BACK to "Back", Ico.DOWN to "Close",
    Ico.MORE to "More", Ico.PLAY to "Play", Ico.PAUSE to "Pause", Ico.NEXT to "Next", Ico.PREV to "Previous",
    Ico.SHUFFLE to "Shuffle", Ico.REPEAT to "Repeat", Ico.REPEAT_ONE to "Repeat one", Ico.CLOSE to "Close",
    Ico.SETTINGS to "Settings", Ico.EQ to "Equalizer")

// ───────────────────────────── The ☰ menu ─────────────────────────────

private class Entry(val label: String, val ico: Ico, val tab: String, val view: String? = null)

private fun entries(): List<Entry> = buildList {
    add(Entry("Now playing", Ico.PLAY, "player"))
    add(Entry("Search", Ico.SEARCH, "search"))
    add(Entry("My music", Ico.NOTE, "music", "all"))
    add(Entry("Albums", Ico.DISC, "albums"))
    add(Entry("Artists", Ico.PERSON, "artists"))
    add(Entry("Genres", Ico.GENRE, "genres"))
    add(Entry("Playlists", Ico.PLAYLIST, "music", "playlists"))
    add(Entry("Folders", Ico.FOLDER, "folders"))
    if (!Music.browseLocal) add(Entry("${SOURCES[Music.source] ?: "Service"} library", Ico.CLOUD, "library"))
    add(Entry("Up next & queues", Ico.QUEUE, "queue"))
    add(Entry("Recently played", Ico.CLOCK, "music", "recent"))
    add(Entry("Most played", Ico.CHART, "music", "top"))
    add(Entry("Favorites", Ico.STAR, "music", "favorites"))
    add(Entry("Not favorited", Ico.STAR_LINE, "music", "unfavorited"))
    add(Entry("Moods", Ico.MOOD, "music", "moods"))
    add(Entry("Skips", Ico.SKIP, "music", "skips"))
    add(Entry("Duplicates", Ico.COPY, "music", "dupes"))
    add(Entry("DJ", Ico.DISC, "music", "dj"))
}

@Composable
private fun DrawerSheet(ui: Ui) {
    ModalDrawerSheet(drawerContainerColor = C.light, drawerShape = androidx.compose.foundation.shape.RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
        modifier = Modifier.width(300.dp)) {
        Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp)) {
            // Where you browse: your own music on the phone, or a service.
            Column(Modifier.fillMaxWidth().clip(R.card).background(C.bg).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (Music.browseLocal) Ico.NOTE else Ico.CLOUD, C.accent, 22.dp)
                    Text(if (Music.browseLocal) "Phone" else SOURCES[Music.source] ?: "", style = T.title.copy(fontSize = 17.sp),
                        modifier = Modifier.padding(start = 12.dp))
                }
                SourceChips(ui, Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(8.dp))
            val here = ui.pages.isEmpty()
            entries().forEach { e ->
                val on = here && ui.tab == e.tab && (e.view == null || e.view == ui.musicView)
                DrawerItem(e.label, e.ico, on) { goTo(ui, e.tab, e.view); if (e.tab == "player") ui.pane = "art" }
            }
            if (LastFm.connected) DrawerItem("Your ${org.omarchy.music.Wrapped.year()} Wrapped", Ico.SPARKLE, false) {
                ui.drawer = false; ui.sheet = "wrapped"
            }
            androidx.compose.material3.HorizontalDivider(color = C.line, modifier = Modifier.padding(vertical = 8.dp))
            DrawerItem("Settings", Ico.SETTINGS, false, quiet = true) { ui.drawer = false; ui.sheet = "accounts" }
            DrawerItem("Equalizer", Ico.EQ, false, quiet = true) { ui.drawer = false; ui.sheet = "eq" }
        }
    }
}

@Composable
private fun DrawerItem(label: String, ico: Ico, on: Boolean, quiet: Boolean = false, onClick: () -> Unit) {
    val onCol = if (C.isLight) Color.White else C.darker
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)
        .then(if (on) Modifier.shadow(8.dp, R.card, ambientColor = C.accent, spotColor = C.accent) else Modifier)
        .clip(R.card).background(if (on) C.accent else Color.Transparent).clickable(onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(ico, if (on) onCol else if (quiet) C.muted else C.accent, 22.dp)
        Text(label, style = T.title.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (on) onCol else if (quiet) C.muted else C.fg),
            modifier = Modifier.padding(start = 18.dp))
    }
}

/** Where you browse: a service, or your own music on the phone. */
@Composable
private fun SourceChips(ui: Ui, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("Phone", Music.browseLocal) {   // your own songs: no service library, the search looks in them
            Music.browse("local")
            if (ui.tab == "library") goTo(ui, "music", "all")
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

// ───────────────────────────── A list's screen ─────────────────────────────

/** ☰ (or ‹ inside something), the screen's name, search and the gear. */
@Composable
private fun ScreenHeader(ui: Ui, page: Ui.Page?, title: String) {
    val inside = page != null || ui.openMine != null || ui.openList != null || ui.openFolder != null
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (inside) IconButton(Ico.BACK) {
                when {
                    ui.pages.isNotEmpty() -> ui.pages.removeAt(ui.pages.lastIndex)
                    ui.openMine != null -> ui.openMine = null
                    ui.openList != null -> ui.openList = null
                    else -> ui.openFolder = null
                }
            } else IconButton(Ico.MENU) { ui.drawer = true }
            Text(title, style = T.head.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 4.dp))
            if (ui.tab != "search" || page != null) IconButton(Ico.SEARCH) { goTo(ui, "search") }
            IconButton(Ico.SETTINGS) { ui.sheet = "accounts" }
        }
        if (ui.tab == "search" && page == null) SearchField(ui)
        if (ui.tab == "library" && page == null && !Music.browseLocal) SourceChips(ui, Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
    }
}

private fun titleOf(ui: Ui, page: Ui.Page?): String = when (page) {
    is Ui.Page.Artist -> page.name
    is Ui.Page.AlbumPage -> page.album.title
    is Ui.Page.Songs -> page.title
    null -> when (ui.tab) {
        "player" -> "Now playing"
        "search" -> "Search"
        "library" -> if (Music.browseLocal) "My music" else "${SOURCES[Music.source] ?: "Service"} library"
        "artists" -> "Artists"
        "albums" -> "Albums"
        "genres" -> "Genres"
        "folders" -> "Folders"
        "queue" -> "Up next"
        "lyrics" -> "Lyrics"
        "visualizer" -> "Visualizer"
        "music" -> entries().firstOrNull { it.tab == "music" && it.view == ui.musicView }?.label ?: "My music"
        else -> "Library"
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
        .background(if (C.isLight) C.light else C.fg.copy(alpha = 0.07f)), verticalAlignment = Alignment.CenterVertically) {
        Icon(Ico.SEARCH, C.muted, 18.dp, Modifier.padding(start = 16.dp))
        Box(Modifier.weight(1f).padding(start = 10.dp, top = 13.dp, bottom = 13.dp)) {
            if (ui.query.isEmpty()) Text("Songs, artists, albums or a link", style = T.body.copy(color = C.muted), maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            BasicTextField(ui.query, { ui.query = it }, singleLine = true,
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

// ───────────────────────────── What's playing ─────────────────────────────

private class Now(val t: Track?, val paused: Boolean, val pos: Double, val dur: Double, val remote: Boolean)

@Composable
private fun now(): Now {
    val remote = Remote.active
    return Now(if (remote) Remote.status.track else Music.current(), if (remote) Remote.status.paused else Music.paused,
        if (remote) Remote.status.position else Music.position, if (remote) Remote.status.duration else Music.duration, remote)
}

/** The floating card at the bottom of a list: the song, previous, play/pause, next. A tap goes back to the player. */
@Composable
private fun MiniPlayer(ui: Ui) {
    val n = now()
    val t = n.t ?: return
    val open = { goTo(ui, "player"); ui.pane = "art" }
    Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
        Column(Modifier.fillMaxWidth().shadow(14.dp, R.big, ambientColor = C.accent.copy(alpha = 0.3f), spotColor = C.accent.copy(alpha = 0.3f))
            .clip(R.big).background(C.light).clickable(onClick = open)
            .pointerInput(Unit) {
                var dy = 0f
                detectVerticalDragGestures(onDragStart = { dy = 0f }, onDragEnd = { if (dy < -40f) open() }) { _, d -> dy += d }
            }) {
            Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ArtImage(if (n.remote) Remote.artModel(t) else artModel(t, 300), 50.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(t.title, style = T.title.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val status = if (n.remote) Remote.status.message else Music.status
                    Text(status.ifEmpty { t.artist }, style = T.sub.copy(color = if (status.isNotEmpty()) C.accent else C.muted),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(Ico.PREV, C.fg, 20.dp, 8.dp) { Music.previous() }
                Box(Modifier.padding(horizontal = 2.dp).size(46.dp).shadow(8.dp, R.pill, ambientColor = C.accent, spotColor = C.accent)
                    .clip(R.pill).background(C.accent).clickable { Music.togglePause() }, contentAlignment = Alignment.Center) {
                    Icon(if (n.paused) Ico.PLAY else Ico.PAUSE, Color.White, 20.dp)
                }
                IconButton(Ico.NEXT, C.fg, 20.dp, 8.dp) { Music.next() }
            }
            Box(Modifier.fillMaxWidth().height(2.dp).background(C.fg.copy(alpha = 0.06f))) {
                Box(Modifier.fillMaxWidth(if (n.dur > 0) (n.pos / n.dur).toFloat().coerceIn(0f, 1f) else 0f).height(2.dp).background(C.accent))
            }
        }
    }
}

/** The player: the main screen. The cover big, a waveform to seek on, the controls; the cover's place
 *  also shows the lyrics, the visualizer or the queue. */
@Composable
private fun Player(ui: Ui) {
    val n = now()
    val t = n.t
    val art = t?.let { if (n.remote) Remote.artModel(it) else artModel(it, 1200) }
    Box(Modifier.fillMaxSize()) {
        // A dark plain look: the cover again, blurred and dimmed, behind everything.
        if (art != null && !C.isLight && C.gradient == null) {
            AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(70.dp).graphicsLayer(alpha = if (android.os.Build.VERSION.SDK_INT >= 31) 0.5f else 0.16f))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.2f),
                Color.Black.copy(alpha = 0.5f), C.darker.copy(alpha = 0.95f)))))
        }
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
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun PaneBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(horizontal = 12.dp).shadow(10.dp, R.big).clip(R.big)
        .background(if (C.isLight) C.light else C.bg.copy(alpha = 0.6f))) { content() }
}

@Composable
private fun PlayerTop(ui: Ui, n: Now) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(Ico.MENU) { ui.drawer = true }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            val q = Music.shownQueue(); val i = Music.shownIndex()
            if (n.remote) Text("PLAYING ON THE DESKTOP", style = T.section.copy(color = C.accent))
            else if (q.isNotEmpty() && i >= 0) Text("${i + 1} of ${q.size}", style = T.sub)
            val name = when {
                n.remote -> ""
                Music.shownRadio() -> "Radio"
                Music.shownDj().on -> "DJ"
                else -> Music.queueName
            }
            if (name.isNotEmpty()) Text(name, style = T.time, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clip(R.small).clickable { ui.showQueues = true }.padding(horizontal = 6.dp))
        }
        Box {
            IconButton(Ico.MORE) { if (n.t != null) menu = true }
            if (n.t != null && !n.remote) TrackMenu(n.t, RowPlace(listOf(n.t), 0), ui, menu) { menu = false }
        }
        IconButton(Ico.SETTINGS) { ui.sheet = "accounts" }
    }
}

/** The cover, big: swipe it for the next or the last song, tap it for the lyrics, double-tap to pause. */
@Composable
private fun Cover(ui: Ui, art: Any?, playing: Boolean) {
    var dx by remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        val side = if (maxWidth < maxHeight) maxWidth else maxHeight
        Box(Modifier.size(side)
            .graphicsLayer(translationX = dx * 0.6f, rotationZ = dx / 90f, alpha = 1f - (kotlin.math.abs(dx) / 900f).coerceIn(0f, 0.5f))
            .shadow(28.dp, R.big, ambientColor = C.accent.copy(alpha = if (C.glow) 0.9f else 0.35f), spotColor = C.accent.copy(alpha = if (C.glow) 0.9f else 0.35f))
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
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(t?.title ?: "Nothing playing", style = T.big.copy(shadow = C.shadow, fontWeight = FontWeight.SemiBold),
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        if (t != null) {
            ArtistLinks(t.artist, ui, t.source, style = T.body.copy(color = C.accent, textAlign = TextAlign.Center, fontSize = 15.sp))
            if (t.album.isNotEmpty()) Text(t.album, style = T.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else Text("Open ☰ and pick a song to start", style = T.sub)
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
    val rest = C.fg.copy(alpha = 0.2f)
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
        Canvas(Modifier.fillMaxWidth().height(44.dp)
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
            val w = step * 0.5f
            for (i in 0 until count) {
                val h = size.height * bars[i]
                val x = i * step + (step - w) / 2
                val played = (i + 0.5f) / count <= frac
                drawRoundRect(if (played) accent else rest, Offset(x, (size.height - h) / 2), Size(w, h), CornerRadius(w / 2))
            }
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
    Row(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        IconButton(Ico.PREV, C.fg, 30.dp) { Music.previous() }
        Box(Modifier.size(74.dp).shadow(if (C.glow) 18.dp else 14.dp, R.pill, ambientColor = C.accent, spotColor = C.accent)
            .clip(R.pill).background(C.accent).clickable { Music.togglePause() }, contentAlignment = Alignment.Center) {
            Icon(if (n.paused) Ico.PLAY else Ico.PAUSE, if (C.glow) C.darker else Color.White, 32.dp)
        }
        IconButton(Ico.NEXT, C.fg, 30.dp) { Music.next() }
    }
}

/** The row under the controls: repeat, favorite, like, lyrics, visualizer, the queue, shuffle; the quality and the EQ. */
@Composable
private fun Toolbar(ui: Ui, n: Now) {
    val t = n.t
    val rep = if (n.remote) Remote.status.repeat else Music.repeat
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp), horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically) {
        Tool(if (rep == "one") Ico.REPEAT_ONE else Ico.REPEAT, rep != "off", "Repeat") { Music.cycleRepeat() }
        if (t != null && !n.remote) {
            Music.musicVersion
            val fav = Music.favorites.has(t)
            Tool(if (fav) Ico.STAR else Ico.STAR_LINE, fav, "Favorite") { Music.setFavorite(listOf(t)) }
        }
        val src = t?.let { Music.sources[it.source] }
        if (t != null && (n.remote || src?.signedIn == true)) {
            val liked = if (n.remote) Remote.status.liked else t.id in Music.liked
            Tool(if (liked) Ico.HEART else Ico.HEART_LINE, liked, "Like") { Music.toggleLike() }
        }
        Tool(Ico.LYRICS, ui.pane == "lyrics", "Lyrics") { ui.pane = if (ui.pane == "lyrics") "art" else "lyrics" }
        Tool(Ico.VIS, ui.pane == "visualizer", "Visualizer") { ui.pane = if (ui.pane == "visualizer") "art" else "visualizer" }
        Tool(Ico.QUEUE, ui.pane == "queue", "Up next") { ui.pane = if (ui.pane == "queue") "art" else "queue" }
        Tool(Ico.SHUFFLE, false, "Shuffle") { Music.shuffle() }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!n.remote) SaveMark()
        if (t != null && !n.remote) {
            Music.speeds.get(t).speed.let { if (it != 1.0) Text("  " + org.omarchy.music.fmtSpeed(it), style = T.time) }
            Music.gains.get(t).let { if (it != 0.0) Text("  %+.1f dB".format(it), style = T.time) }
        }
        val status = if (n.remote) Remote.status.message else Music.status
        Text(status, style = T.time.copy(color = C.accent), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        Text(if (Music.eq.on) "EQ · ${Music.outputNow.label}" else "EQ off", style = T.time.copy(color = if (Music.eq.on) C.accent else C.muted),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clip(R.small).clickable { ui.sheet = "eq" }.padding(horizontal = 6.dp, vertical = 4.dp))
    }
}

@Composable
private fun Tool(ico: Ico, on: Boolean, desc: String, onClick: () -> Unit) {
    IconButton(ico, if (on) C.accent else C.fg.copy(alpha = 0.75f), 22.dp, 10.dp, desc, onClick)
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
