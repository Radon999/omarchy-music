package org.omarchy.music.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.graphicsLayer
import coil.compose.AsyncImage
import org.omarchy.music.Album
import org.omarchy.music.FolderItem
import org.omarchy.music.Music
import org.omarchy.music.Remote
import org.omarchy.music.Track
import org.omarchy.music.fmtTime
import org.omarchy.music.splitArtists

/** Where a song row sits, for its menu: a playlist of yours, the queue, the library… */
data class RowPlace(val group: List<Track>, val index: Int, val kind: String = "", val playlistId: String? = null,
                    val queueIndex: Int = -1, val radio: Boolean = false)

/** What the screens share: the tab, the open pages, what's selected, the open sheets. */
class Ui {
    var tab by mutableStateOf("player")                // what's in front: the player, or a list (from the ☰ menu)
    var player by mutableStateOf(false)                // (kept for the screens that close it: the player is a tab now)
    var drawer by mutableStateOf(false)                // the ☰ menu is out
    var showQueues by mutableStateOf(false)            // the queues switcher is open
    var lyricsSearch by mutableStateOf(false)          // the lyrics' search box is open
    var pane by mutableStateOf("art")                  // what the player shows: art, lyrics, visualizer, queue
    var musicView by mutableStateOf("all")
    var openMine by mutableStateOf<String?>(null)
    var openFolder by mutableStateOf<String?>(null)   // a folder of playlists, open in the Library or Music › Playlists
    var folderFor by mutableStateOf<FolderItem?>(null)   // a playlist being put in a folder
    var openList by mutableStateOf<Pair<Album, List<Track>?>?>(null)
    val pages = mutableStateListOf<Page>()            // artist and album pages, newest last
    var selecting by mutableStateOf(false)
    // Advanced multi-select: what you pick stays picked as you go from list to list, and the
    // Options can be used one after another on it (unless "close after an option" is on).
    val selected = androidx.compose.runtime.mutableStateMapOf<String, Track>()
    var selectPool by mutableStateOf(listOf<Track>())  // the list in front, to select from
    var closeAfter by mutableStateOf(true)
    var showOptions by mutableStateOf(false)
    var selectPlace by mutableStateOf<RowPlace?>(null)
    var query by mutableStateOf("")
    var found by mutableStateOf<org.omarchy.music.Found?>(null)
    var foundIn by mutableStateOf("")                   // where it was searched: tidal, ytm, local…
    var searchSeq = 0
    var searched = ""   // the last query searched for (searching as you type skips it)
    var resultsAreList by mutableStateOf(false)
    var searching by mutableStateOf(false)
    var sheet by mutableStateOf<String?>(null)          // "accounts", "eq", "tidal-login", "spotify-login"…
    var pickPlaylistFor by mutableStateOf<List<Track>?>(null)
    var editTagsFor by mutableStateOf<List<Track>?>(null)
    var deleteFor by mutableStateOf<List<Track>?>(null)
    var speedFor by mutableStateOf<List<Track>?>(null)
    var volumeFor by mutableStateOf<List<Track>?>(null)     // Volume…: a song's own boost
    var moodsFor by mutableStateOf<List<Track>?>(null)      // Moods…: set by hand
    var upgradeFor by mutableStateOf<List<Track>?>(null)    // Upgrade…: these songs (empty: all of yours)
    var exportFor by mutableStateOf<Pair<String, List<Track>>?>(null)   // Export…: a playlist as .m3u8
    var coverFor by mutableStateOf<List<List<Track>>?>(null)   // the albums still to pick a cover for
    var artistPictureFor by mutableStateOf<String?>(null)

    /** Cover… for tracks: one album at a time. */
    fun pickCovers(tracks: List<Track>) {
        coverFor = tracks.groupBy { org.omarchy.music.Covers.albumKey(it) }.values.toList().ifEmpty { null }
    }
    var askName by mutableStateOf<Triple<String, String, (String) -> Unit>?>(null)
    var confirm by mutableStateOf<Pair<String, () -> Unit>?>(null)
    var playlistsBySource = mutableStateListOf<Pair<String, List<Album>?>>()

    sealed class Page {
        data class Artist(val name: String, val prefer: String? = null) : Page()
        data class AlbumPage(val album: Album, val source: String, val tracks: List<Track>? = null) : Page()
        /** A list of songs with a name: a genre, a folder. */
        data class Songs(val title: String, val tracks: List<Track>, val kind: String = "") : Page()
    }

    fun startSelecting(pool: List<Track>, place: RowPlace?, first: Track?) {
        if (!selecting) selected.clear()
        selecting = true
        selectPool = pool
        selectPlace = place
        first?.let { selected[it.id] = it }
    }

    /** An option was used on the songs picked: the selection closes, unless you keep it for more. */
    fun afterOption() { if (closeAfter) stopSelecting() }

    fun selectAllHere() { selectPool.forEach { selected[it.id] = it } }
    fun deselectAllHere() { selectPool.forEach { selected.remove(it.id) } }
    fun invertHere() { selectPool.forEach { if (it.id in selected) selected.remove(it.id) else selected[it.id] = it } }
    /** The songs between the first and the last picked one in the list in front. */
    fun selectBetween() {
        val at = selectPool.indices.filter { selectPool[it].id in selected }
        if (at.size < 2) return
        for (i in at.first()..at.last()) selected[selectPool[i].id] = selectPool[i]
    }

    /** Start the DJ on f (see [org.omarchy.music.Dj.From]) and show it. */
    fun djFrom(f: org.omarchy.music.Dj.From) {
        Music.djStart(f)
        stopSelecting()
        tab = "music"; pages.clear(); openMine = null; musicView = "dj"; player = false
    }

    fun stopSelecting() {
        selecting = false
        selected.clear()
    }

    fun toggle(t: Track) { if (t.id in selected) selected.remove(t.id) else selected[t.id] = t }
    fun selectedTracks() = selected.values.toList()
}

@Composable
fun ArtImage(model: Any?, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(if (size >= 120.dp) R.card else R.small).background(C.light), contentAlignment = Alignment.Center) {
        Icon(Ico.NOTE, C.muted.copy(alpha = 0.6f), size * 0.42f)   // under the cover: shows when there's none
        if (model != null) AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(size))
    }
}

/** Artists' names; a tap opens the artist's page (a choice of them, for a few). */
@Composable
fun ArtistLinks(text: String, ui: Ui, prefer: String?, style: androidx.compose.ui.text.TextStyle = T.sub, suffix: String = "") {
    val names = splitArtists(text)
    var menu by remember { mutableStateOf(false) }
    Box {
        Text(text + suffix, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = if (names.isEmpty() || ui.selecting) Modifier else Modifier.clickable {
                if (names.size == 1) ui.pages += Ui.Page.Artist(names[0], prefer) else menu = true
            })
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            names.forEach { n ->
                DropdownMenuItem(text = { Text(n) }, onClick = { menu = false; ui.pages += Ui.Page.Artist(n, prefer) })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(t: Track, place: RowPlace, ui: Ui, extra: String = "", onPlay: () -> Unit) {
    val playing = Music.shownTrack()?.id == t.id && (place.kind != "queue" || place.queueIndex == Music.shownIndex())
    var menu by remember { mutableStateOf(false) }
    Music.savedVersion   // redraw when downloads change
    val saved = Music.lib.items[t.id]
    val checked = t.id in ui.selected
    if (ui.selecting) androidx.compose.runtime.SideEffect { if (ui.selectPool !== place.group) { ui.selectPool = place.group; ui.selectPlace = place } }
    Box {
        Row(
            Modifier.fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 1.dp)
                .clip(R.card)
                .background(if (playing || checked) C.accent.copy(alpha = 0.14f) else if (C.isLight) C.light else Color.Transparent)
                .combinedClickable(
                    onClick = { if (ui.selecting) ui.toggle(t) else onPlay() },
                    onLongClick = { if (ui.selecting) ui.toggle(t) else menu = true },
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (ui.selecting) Checkbox(checked = checked, onCheckedChange = { ui.toggle(t) },
                colors = CheckboxDefaults.colors(checkedColor = C.accent, checkmarkColor = C.darker, uncheckedColor = C.muted))
            Box(contentAlignment = Alignment.Center) {
                ArtImage(if (place.kind == "queue" && Remote.active) Remote.artModel(t) else artModel(t), 48.dp, Modifier.padding(start = 2.dp))
                if (playing) Box(Modifier.padding(start = 2.dp).size(48.dp).clip(R.small).background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center) { Icon(if (Music.paused) Ico.PAUSE else Ico.PLAY, C.accent, 20.dp) }
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(t.title, style = T.title.copy(color = if (playing) C.accent else C.fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val bits = buildList {
                    if (t.duration > 0) add(fmtTime(t.duration))
                    if (extra.isNotEmpty()) add(extra)
                }.joinToString(" · ")
                ArtistLinks(t.artist, ui, t.source, suffix = if (bits.isNotEmpty()) "  · $bits" else "")
            }
            if (saved != null) Badge(if (saved.format == "flac") "FLAC" else "✓")
            else if (Music.dl.busy(t.id) || Music.downloads.any { it.id == t.id }) Text("…", style = T.time)
            if (!ui.selecting) Box(Modifier.clip(R.pill).clickable { menu = true }.padding(6.dp)) { Icon(Ico.MORE, C.muted, 18.dp) }
        }
        TrackMenu(t, place, ui, menu) { menu = false }
    }
}

@Composable
fun TrackMenu(t: Track, place: RowPlace, ui: Ui, open: Boolean, dismiss: () -> Unit) {
    DropdownMenu(expanded = open, onDismissRequest = dismiss) {
        fun item(label: String, act: () -> Unit) = @Composable {
            DropdownMenuItem(text = { Text(label, style = T.body) }, onClick = { dismiss(); act() })
        }
        if (place.kind == "queue") {
            if (place.queueIndex != Music.shownIndex()) item("Play now") { Music.playIndex(place.queueIndex) }()
            if (place.queueIndex != Music.shownIndex()) item("Remove from the queue") { Music.unqueueMany(listOf(place.queueIndex)) }()
        } else {
            item("Play next") { Music.enqueue(listOf(t), "next") }()
            item("Add to the queue") { Music.enqueue(listOf(t)) }()
        }
        item("Add to playlist…") { ui.pickPlaylistFor = listOf(t) }()
        splitArtists(t.artist).take(3).forEach { n -> item("Go to $n") { ui.pages += Ui.Page.Artist(n, t.source) }() }
        if (place.playlistId != null) item("Remove from this playlist") { Music.removeFromPlaylist(place.playlistId, setOf(t.id)) }()
        if (!t.isFile && !Music.lib.has(t.id)) item("Download") { Music.download(listOf(t)) }()
        if (Music.lib.has(t.id)) item("Remove download") {
            ui.confirm = "Delete the downloaded FLAC of “${t.title}”?" to { Music.removeDownload(t.id) }
        }()
        else if (Music.phoneFile(t) != null) item("Delete…") { ui.deleteFor = listOf(t) }()
        if (org.omarchy.music.Tags.editable(t) != null) item("Edit tags…") { ui.editTagsFor = listOf(t) }()
        if (org.omarchy.music.upgradable(t) && Music.upgrading == null) item("Upgrade to FLAC…") { ui.upgradeFor = listOf(t) }()
        item(if (Music.favorites.has(t)) "Unfavorite" else "Favorite") { Music.setFavorite(listOf(t)) }()
        item("Moods…") { ui.moodsFor = listOf(t) }()
        Music.gains.get(t).let { g -> item(if (g == 0.0) "Volume…" else "Volume (${"%+.1f".format(g)} dB)…") { ui.volumeFor = listOf(t) }() }
        item("Cover…") { ui.pickCovers(listOf(t)) }()
        Music.speeds.get(t).speed.let { s -> item(if (s == 1.0) "Speed…" else "Speed (${org.omarchy.music.fmtSpeed(s)})…") { ui.speedFor = listOf(t) }() }
        item("Select…") { ui.startSelecting(place.group, place, t) }()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistRow(p: Album, art: Any? = null, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 1.dp).clip(R.card)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        ArtImage(art ?: p.thumb.takeIf { it.isNotEmpty() }, 52.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(p.title, style = T.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOf(p.sub, if (p.count.isNotEmpty()) "${p.count} songs" else "").filter { it.isNotEmpty() }.joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = T.sub, maxLines = 1)
        }
        Icon(Ico.BACK, C.muted, 16.dp, Modifier.graphicsLayer(rotationZ = 180f))
    }
}

/** A small mark: FLAC, ✓, a quality. */
@Composable
fun Badge(text: String, on: Boolean = true) {
    Text(text, style = T.time.copy(color = if (on) C.accent else C.muted, fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
        modifier = Modifier.border(1.dp, (if (on) C.accent else C.muted).copy(alpha = 0.55f), R.small).padding(horizontal = 5.dp, vertical = 1.dp))
}

@Composable
fun Section(text: String) {
    Text(text, style = T.section, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp))
}

@Composable
fun BackRow(text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Ico.BACK, C.accent, 18.dp)
        Text(text, style = T.title.copy(color = C.accent), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
fun ActionRow(text: String, onClick: () -> Unit) {
    Text(text, style = T.body.copy(color = C.accent, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp).clip(R.card).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 13.dp))
}

@Composable
fun Empty(title: String, sub: String = "") {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = T.title.copy(fontSize = T.title.fontSize * 1.1f), textAlign = TextAlign.Center)
        if (sub.isNotEmpty()) Text(sub, style = T.sub, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
    }
}

/** The desktop's chips: a quiet word, the accent with a frame when on. */
@Composable
fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    Text(text, style = T.tab.copy(color = if (on) C.accent else C.muted, shadow = if (on) C.shadow else null),
        modifier = Modifier
            .clip(R.pill)
            .background(if (on) C.accent.copy(alpha = 0.16f) else C.fg.copy(alpha = 0.05f))
            .border(1.dp, if (on) C.accent.copy(alpha = 0.5f) else Color.Transparent, R.pill)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp))
}

@Composable
fun Pill(text: String, quiet: Boolean = false, onClick: () -> Unit) {
    Text(text, style = T.tab.copy(color = if (quiet) C.fg else if (C.isLight) C.light else C.darker,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
        modifier = Modifier.clip(R.pill)
            .background(if (quiet) C.fg.copy(alpha = 0.07f) else C.accent)
            .border(1.dp, if (quiet) C.line else C.accent, R.pill)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp))
}

fun LazyListScope.tracks(list: List<Track>, ui: Ui, kind: String = "", playlistId: String? = null, radio: Boolean = false,
                         extra: (Track) -> String = { "" }) {
    items(list.size, key = { "${list[it].id}#$it" }) { i ->
        val t = list[i]
        TrackRow(t, RowPlace(list, i, kind, playlistId, radio = radio), ui, extra(t)) { Music.playFrom(list, i, radio) }
    }
}

