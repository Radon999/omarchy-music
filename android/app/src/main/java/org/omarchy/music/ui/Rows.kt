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
    var tab by mutableStateOf("music")
    var musicView by mutableStateOf("all")
    var openMine by mutableStateOf<String?>(null)
    var openFolder by mutableStateOf<String?>(null)   // a folder of playlists, open in the Library or Music › Playlists
    var folderFor by mutableStateOf<FolderItem?>(null)   // a playlist being put in a folder
    var openList by mutableStateOf<Pair<Album, List<Track>?>?>(null)
    val pages = mutableStateListOf<Page>()            // artist and album pages, newest last
    var selecting by mutableStateOf(false)
    val selected = mutableStateListOf<String>()
    var selectPool by mutableStateOf(listOf<Track>())  // the list being selected from
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
    }

    fun startSelecting(pool: List<Track>, place: RowPlace?, first: Track?) {
        selecting = true
        selectPool = pool
        selectPlace = place
        selected.clear()
        first?.let { selected += it.id }
    }

    /** Start the DJ on f (see [org.omarchy.music.Dj.From]) and show it. */
    fun djFrom(f: org.omarchy.music.Dj.From) {
        Music.djStart(f)
        stopSelecting()
        tab = "music"; pages.clear(); openMine = null; musicView = "dj"
    }

    fun stopSelecting() {
        selecting = false
        selected.clear()
    }

    fun toggle(t: Track) { if (t.id in selected) selected -= t.id else selected += t.id }
    fun selectedTracks() = selectPool.filter { it.id in selected }.distinctBy { it.id }
}

@Composable
fun ArtImage(model: Any?, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(androidx.compose.foundation.shape.RoundedCornerShape(0.dp)).background(C.light)) {
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
    Box {
        Row(
            Modifier.fillMaxWidth()
                .background(if (playing || checked) C.accent.copy(alpha = 0.10f) else Color.Transparent)
                .border(width = 0.dp, color = Color.Transparent)
                .combinedClickable(
                    onClick = { if (ui.selecting) ui.toggle(t) else onPlay() },
                    onLongClick = { if (ui.selecting) ui.toggle(t) else menu = true },
                )
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (playing) Box(Modifier.width(2.dp).size(2.dp, 44.dp).background(C.accent))
            if (ui.selecting) Checkbox(checked = checked, onCheckedChange = { ui.toggle(t) },
                colors = CheckboxDefaults.colors(checkedColor = C.accent, checkmarkColor = C.darker, uncheckedColor = C.muted))
            ArtImage(if (place.kind == "queue" && Remote.active) Remote.artModel(t) else artModel(t), 44.dp, Modifier.padding(start = 6.dp))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(t.title, style = T.title.copy(color = if (playing) C.accent else C.fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val bits = buildList {
                    if (t.duration > 0) add(fmtTime(t.duration))
                    if (extra.isNotEmpty()) add(extra)
                }.joinToString(" · ")
                ArtistLinks(t.artist, ui, t.source, suffix = if (bits.isNotEmpty()) "  · $bits" else "")
            }
            if (saved != null) Text(if (saved.format == "flac") "FLAC" else "✓", style = T.time.copy(color = C.accent))
            else if (Music.dl.busy(t.id) || Music.downloads.any { it.id == t.id }) Text("…", style = T.time)
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
        item("Cover…") { ui.pickCovers(listOf(t)) }()
        Music.speeds.get(t).speed.let { s -> item(if (s == 1.0) "Speed…" else "Speed (${org.omarchy.music.fmtSpeed(s)})…") { ui.speedFor = listOf(t) }() }
        item("Select…") { ui.startSelecting(place.group, place, t) }()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistRow(p: Album, art: Any? = null, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        ArtImage(art ?: p.thumb.takeIf { it.isNotEmpty() }, 44.dp)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(p.title, style = T.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOf(p.sub, if (p.count.isNotEmpty()) "${p.count} songs" else "").filter { it.isNotEmpty() }.joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = T.sub, maxLines = 1)
        }
        Text("›", style = T.title.copy(color = C.muted))
    }
}

@Composable
fun Section(text: String) {
    Text(text, style = T.section, modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp))
}

@Composable
fun BackRow(text: String, onClick: () -> Unit) {
    Text("‹  $text", style = T.title.copy(color = C.accent), maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp))
}

@Composable
fun ActionRow(text: String, onClick: () -> Unit) {
    Text(text, style = T.body.copy(color = C.accent), modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp))
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
    Text(text, style = T.tab.copy(color = if (on) C.accent else C.muted),
        modifier = Modifier
            .background(if (on) C.accent.copy(alpha = 0.12f) else Color.Transparent)
            .border(1.dp, if (on) C.accent.copy(alpha = 0.45f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp))
}

@Composable
fun Pill(text: String, quiet: Boolean = false, onClick: () -> Unit) {
    Text(text, style = T.tab.copy(color = if (quiet) C.muted else C.accent, fontSize = T.tab.fontSize * 0.92f),
        modifier = Modifier.border(1.dp, if (quiet) C.line else C.accent.copy(alpha = 0.5f))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp))
}

fun LazyListScope.tracks(list: List<Track>, ui: Ui, kind: String = "", playlistId: String? = null, radio: Boolean = false,
                         extra: (Track) -> String = { "" }) {
    items(list.size, key = { "${list[it].id}#$it" }) { i ->
        val t = list[i]
        TrackRow(t, RowPlace(list, i, kind, playlistId, radio = radio), ui, extra(t)) { Music.playFrom(list, i, radio) }
    }
}

