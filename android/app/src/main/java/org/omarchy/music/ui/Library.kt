package org.omarchy.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import org.omarchy.music.Dj
import org.omarchy.music.Music
import org.omarchy.music.Track

// Albums, Genres and Folders (from the ☰ menu), a named list of songs, and your queues.

/** Albums: your songs by album, as covers in a grid. */
@Composable
fun AlbumsTab(ui: Ui) {
    val albums = remember(Music.musicVersion, Music.savedVersion) { runCatching { Music.albums() }.getOrDefault(emptyList()) }
    if (albums.isEmpty()) { Empty("No albums yet", "Your songs, by album, show up here."); return }
    LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        items(albums.size) { i ->
            val (al, songs) = albums[i]
            Column(Modifier.padding(6.dp).clip(R.card).clickable { ui.pages += Ui.Page.AlbumPage(al, "local", songs) }.padding(4.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).shadow(6.dp, R.card).clip(R.card).background(C.light),
                    contentAlignment = Alignment.Center) {
                    Icon(Ico.DISC, C.muted.copy(alpha = 0.5f), 40.dp)
                    AsyncImage(model = artModel(songs.first(), 400), contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize())
                }
                Text(al.title, style = T.title.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp))
                Text("${al.sub} · ${songs.size}", style = T.sub.copy(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A row in Genres and Folders: an icon, a name, how many songs. */
@Composable
private fun GroupRow(ico: Ico, name: String, sub: String, count: Int, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).clip(R.card).background(C.light.copy(alpha = 0.6f))
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(R.card).background(C.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(ico, C.accent, 22.dp)
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(name, style = T.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, style = T.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("%,d".format(count), style = T.sub)
    }
}

@Composable
fun GenresTab(ui: Ui) {
    val genres = remember(Music.musicVersion, Music.savedVersion) { runCatching { Music.genres() }.getOrDefault(emptyList()) }
    if (genres.isEmpty()) { Empty("No genres yet", "Your songs, by the genre their files give, show up here."); return }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Section("${genres.size} GENRES") }
        items(genres.size) { i ->
            val (g, songs) = genres[i]
            GroupRow(Ico.GENRE, g, songs.take(3).joinToString { it.artist.split(",")[0] }, songs.size) {
                ui.pages += Ui.Page.Songs(g, songs, "genre")
            }
        }
    }
}

@Composable
fun FoldersTab(ui: Ui) {
    val folders = remember(Music.musicVersion, Music.savedVersion) { runCatching { Music.folderSongs() }.getOrDefault(emptyList()) }
    if (folders.isEmpty()) { Empty("No folders yet", "The folders your songs are in show up here."); return }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Section("${folders.size} FOLDERS") }
        items(folders.size) { i ->
            val (f, songs) = folders[i]
            val name = f.trimEnd('/').substringAfterLast('/').ifEmpty { f }
            GroupRow(Ico.FOLDER, name, "/storage/emulated/0/" + f.trimEnd('/'), songs.size) {
                ui.pages += Ui.Page.Songs(name, songs, "folder")
            }
        }
    }
}

/** A genre's or a folder's songs. */
@Composable
fun SongsPage(ui: Ui, page: Ui.Page.Songs) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { ListActions(page.tracks, onDj = { ui.djFrom(Dj.From("songs", name = page.title, songs = page.tracks)) }) }
        tracks(page.tracks, ui, page.kind)
    }
}

/** Above Up next: which queue this is, and the way to your others. */
@Composable
fun QueueHeader(ui: Ui) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).clip(R.card).background(C.light.copy(alpha = 0.7f))
        .clickable { ui.showQueues = true }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Ico.QUEUE, C.accent, 20.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(Music.queueName.ifEmpty { "Queue" }, style = T.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (Music.queues.isEmpty()) "Your only queue" else "${Music.queues.size + 1} queues · tap to switch",
                style = T.sub)
        }
        Icon(Ico.DOWN, C.muted, 16.dp)
    }
}

/** Your queues: go back to one where you left it, rename them, close them. */
@Composable
fun QueuesDialog(ui: Ui) {
    AlertDialog(onDismissRequest = { ui.showQueues = false }, containerColor = C.light,
        title = { Text("Queues", style = T.head) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                    Text("A separate queue for each folder, album, artist and playlist you play from", style = T.sub,
                        modifier = Modifier.weight(1f))
                    Switch(Music.multiQueue, { Music.setMultiQueue(it) }, colors = SwitchDefaults.colors(
                        checkedThumbColor = C.light, checkedTrackColor = C.accent))
                }
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    item { QueueLine(ui, Music.queueId, Music.queueName.ifEmpty { "Queue" }, Music.queue, Music.index, true) }
                    items(Music.queues.size) { i ->
                        val q = Music.queues[i]
                        QueueLine(ui, q.id, q.name, q.tracks, q.index, false)
                    }
                }
            }
        },
        confirmButton = { TextButton({ ui.showQueues = false }) { Text("Done", color = C.accent) } },
        dismissButton = {
            if (Music.queues.isNotEmpty()) TextButton({ Music.removeOtherQueues() }) { Text("Remove all other queues", color = C.red) }
        })
}

@Composable
private fun QueueLine(ui: Ui, id: String, name: String, tracks: List<Track>, index: Int, current: Boolean) {
    Row(Modifier.fillMaxWidth().clip(R.small).clickable(enabled = !current) { Music.switchQueue(id); ui.showQueues = false }
        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(current, { if (!current) { Music.switchQueue(id); ui.showQueues = false } },
            colors = RadioButtonDefaults.colors(selectedColor = C.accent, unselectedColor = C.muted))
        Column(Modifier.weight(1f)) {
            Text(name, style = T.title.copy(color = if (current) C.accent else C.fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val t = tracks.getOrNull(index)
            Text("${index + 1}/${tracks.size}" + (t?.let { " · ${it.title}" } ?: ""), style = T.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(Ico.EDIT, C.muted, 18.dp, 6.dp, desc = "Rename") {
            ui.askName = Triple("Rename the queue", name) { n -> Music.renameQueue(id, n) }
        }
        if (!current) IconButton(Ico.CLOSE, C.muted, 16.dp, 6.dp, desc = "Remove") { Music.deleteQueue(id) }
    }
}

/** The bar while you pick songs: how many, Options, Advanced select, Cancel. */
@Composable
fun SelectBar(ui: Ui) {
    var adv by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp).shadow(10.dp, R.big).clip(R.big).background(C.light)) {
        Text("${ui.selected.size} song${if (ui.selected.size != 1) "s" else ""} selected", style = T.sub,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            BarButton(Ico.MORE, "Options") { if (ui.selected.isNotEmpty()) ui.showOptions = true }
            Box {
                BarButton(Ico.SELECT, "Advanced select") { adv = true }
                androidx.compose.material3.DropdownMenu(adv, { adv = false }, containerColor = C.light) {
                    listOf("Select all from this list" to { ui.selectAllHere() },
                        "Deselect all from this list" to { ui.deselectAllHere() },
                        "Select songs in between the first and last selected" to { ui.selectBetween() },
                        "Invert selection" to { ui.invertHere() },
                        "Deselect everything" to { ui.selected.clear() }).forEach { (l, act) ->
                        androidx.compose.material3.DropdownMenuItem({ Text(l, style = T.body) }, { adv = false; act() })
                    }
                }
            }
            BarButton(Ico.CLOSE, "Cancel") { ui.stopSelecting() }
        }
    }
}

@Composable
private fun BarButton(ico: Ico, label: String, onClick: () -> Unit) {
    Column(Modifier.clip(R.card).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(ico, C.accent, 22.dp)
        Text(label, style = T.time.copy(fontSize = 11.sp, color = C.fg), modifier = Modifier.padding(top = 2.dp))
    }
}

/** Options for the songs picked: any of them, one after another if "close after an option" is off. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun OptionsSheet(ui: Ui) {
    val picked = ui.selectedTracks()
    val place = ui.selectPlace
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = { ui.showOptions = false }, containerColor = C.light) {
        LazyColumn(Modifier.padding(bottom = 24.dp)) {
            item { Text("Options for ${picked.size} selected song${if (picked.size != 1) "s" else ""}", style = T.head,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
            val opts = buildList<Triple<Ico, String, () -> Unit>> {
                add(Triple(Ico.PLAY, "Play") { Music.playList(picked) })
                add(Triple(Ico.SHUFFLE, "Shuffle play") { Music.playList(picked, shuffled = true) })
                add(Triple(Ico.NEXT, "Play after the current song") { Music.enqueue(picked, "next") })
                add(Triple(Ico.QUEUE, "Add to the queue") { Music.enqueue(picked) })
                add(Triple(Ico.PLAYLIST, "Add to a playlist…") { ui.pickPlaylistFor = picked })
                add(Triple(Ico.STAR, "Add to favorites") { Music.setFavorite(picked, true) })
                add(Triple(Ico.STAR_LINE, "Remove from favorites") { Music.setFavorite(picked, false) })
                add(Triple(Ico.DOWNLOAD, "Download") { Music.download(picked) })
                if (picked.any { org.omarchy.music.Tags.editable(it) != null }) add(Triple(Ico.EDIT, "Edit tags…") { ui.editTagsFor = picked })
                add(Triple(Ico.ART, "Cover…") { ui.pickCovers(picked) })
                add(Triple(Ico.MOOD, "Moods…") { ui.moodsFor = picked })
                add(Triple(Ico.EQ, "Volume…") { ui.volumeFor = picked })
                add(Triple(Ico.CLOCK, "Speed…") { ui.speedFor = picked })
                if (Music.upgrading == null && picked.any { org.omarchy.music.upgradable(it) })
                    add(Triple(Ico.SPARKLE, "Upgrade to FLAC…") { ui.upgradeFor = picked.filter { org.omarchy.music.upgradable(it) } })
                if (place?.playlistId != null) add(Triple(Ico.CLOSE, "Remove from this playlist") {
                    Music.removeFromPlaylist(place.playlistId, picked.map { it.id }.toSet()) })
                if (place?.kind == "queue") add(Triple(Ico.CLOSE, "Remove from the queue") {
                    val ids = picked.map { it.id }.toSet()
                    Music.unqueueMany(Music.queue.indices.filter { Music.queue[it].id in ids }) })
                val files = picked.mapNotNull { it.file ?: Music.lib.items[it.id]?.file }.filter { it.startsWith("content://") }
                if (files.isNotEmpty()) add(Triple(Ico.SHARE, "Share") {
                    val uris = ArrayList(files.map { android.net.Uri.parse(it) })
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).setType("audio/*")
                        .putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
                        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    ctx.startActivity(android.content.Intent.createChooser(send, "Share ${files.size} songs"))
                })
                if (picked.any { Music.deletable(it) }) add(Triple(Ico.TRASH, "Delete permanently…") { ui.deleteFor = picked })
            }
            items(opts.size) { i ->
                val (ico, label, act) = opts[i]
                Row(Modifier.fillMaxWidth().clickable {
                    ui.showOptions = false
                    act()
                    // The ones that open a dialog close the selection when they're done.
                    if (label.endsWith("…").not()) ui.afterOption()
                }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(ico, if (label.startsWith("Delete")) C.red else C.accent, 20.dp)
                    Text(label, style = T.body.copy(color = if (label.startsWith("Delete")) C.red else C.fg), modifier = Modifier.padding(start = 16.dp))
                }
            }
            item {
                Row(Modifier.fillMaxWidth().clickable { ui.closeAfter = !ui.closeAfter }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(ui.closeAfter, { ui.closeAfter = it },
                        colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = C.accent, checkmarkColor = C.light))
                    Text("Close the selection after an option (off: use several options on the same songs)", style = T.sub)
                }
            }
        }
    }
}
