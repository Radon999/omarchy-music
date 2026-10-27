package org.omarchy.music.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.omarchy.music.Missed
import org.omarchy.music.Music
import org.omarchy.music.Track
import org.omarchy.music.fmtTime

/** What an imported playlist's songs weren't found (Music.importReport): look for each on
 *  another service, and add the right one to the playlist where it was, or download it too. */
@Composable
fun ImportReportDialog(r: Music.ImportReport) {
    val sources = remember { Music.reportSources() }
    var pick by remember { mutableIntStateOf(0) }
    val left = remember { mutableStateListOf<Missed>().apply { addAll(r.missed) } }
    val placed = remember { ArrayList<Missed>() }
    val added = remember { mutableStateMapOf<Int, Track>() }                          // its line -> what went in
    val hits = remember { mutableStateMapOf<Int, Pair<Track?, List<Track>>>() }       // its line -> what a search found
    val notes = remember { mutableStateMapOf<Int, String>() }
    var status by remember { mutableStateOf(if (sources.isEmpty()) "Connect a service in Settings to look for these." else "") }
    var busy by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val canDownload = Music.tidal.connected
    val close = { Music.importReport = null }

    fun source() = sources.getOrNull(pick)?.let { (k, n) -> Music.sources[k]?.let { it to n } }

    fun add(m: Missed, t: Track, download: Boolean) {
        if (!Music.placeMissed(r.id, m, t, placed)) { status = "That one's in the playlist already"; return }
        left.remove(m); added[m.n] = t; hits.remove(m.n); notes.remove(m.n)
        if (download) Music.download(listOf(t))
    }

    fun find(m: Missed) {
        val (src, n) = source() ?: return
        notes[m.n] = "Looking on $n…"
        Music.bg({ Music.matchSong(src, m.artist, m.title, m.seconds) }) { res, err ->
            notes.remove(m.n)
            if (res != null) hits[m.n] = res
            if (err != null) notes[m.n] = "$n didn't answer: ${err.message?.take(80)}"
            else if (res?.second.isNullOrEmpty()) notes[m.n] = "Nothing on $n"
        }
    }

    fun all() {
        val (src, n) = source() ?: return
        val todo = left.toList()
        busy = true
        status = "Looking for ${todo.size} song${if (todo.size != 1) "s" else ""} on $n…"
        Music.bg({ todo.map { m -> m to runCatching { Music.matchSong(src, m.artist, m.title, m.seconds) } } }) { res, _ ->
            busy = false
            var got = 0
            for ((m, found) in res.orEmpty()) {
                if (m !in left) continue   // picked by hand meanwhile
                val f = found.getOrNull()
                if (f?.first != null && m.artist.isNotEmpty()) { add(m, f.first!!, false); got++ }   // (a title alone, "track07", is anyone's)
                else if (f != null) { hits[m.n] = f; if (f.second.isEmpty()) notes[m.n] = "Nothing on $n" }
                else notes[m.n] = "$n didn't answer: ${found.exceptionOrNull()?.message?.take(80)}"
            }
            status = "Found $got of ${todo.size} on $n" + if (got < todo.size) ": the rest show what came closest." else "."
        }
    }

    AlertDialog(onDismissRequest = close, containerColor = C.light,
        title = {
            Text(if (left.isEmpty()) "Every song in “${r.name}” is in it now" else "${left.size} of ${r.missed.size} songs in “${r.name}” weren't found",
                style = T.title)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Look for them on another service, then pick the right one: it goes into the playlist where it was.", style = T.sub)
                if (sources.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    sources.forEachIndexed { i, (_, n) -> Pill(n, quiet = i != pick) { pick = i } }
                }
                if (sources.isNotEmpty() && left.isNotEmpty() && !busy) Pill("Try them all") { all() }
                if (status.isNotEmpty()) Text(status, style = T.sub)
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(r.missed.size) { i ->
                        val m = r.missed[i]
                        val done = added[m.n]
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(if (done != null) "Added: ${done.title}" else m.title, style = T.body.copy(fontWeight = FontWeight.Bold),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(done?.artist ?: listOf(m.artist, m.why).filter { it.isNotEmpty() }.joinToString(" · "), style = T.sub,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                if (done == null && sources.isNotEmpty()) Pill("Find", quiet = true) { find(m) }
                            }
                            notes[m.n]?.let { Text(it, style = T.sub) }
                            hits[m.n]?.let { (best, all) ->
                                (listOfNotNull(best) + all.filter { it != best }).take(6).forEach { h ->
                                    Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("${h.title} · ${h.artist}" + if (h.duration > 0) " · ${fmtTime(h.duration)}" else "",
                                            style = T.sub.copy(color = if (h == best) C.accent else C.fg), maxLines = 2,
                                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                        Pill("Add") { add(m, h, false) }
                                        if (canDownload) Pill("↓") { add(m, h, true) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(close) { Text("Done", color = C.accent) } },
        dismissButton = {
            TextButton({
                clipboard.setText(AnnotatedString(left.joinToString("\n") { it.name }))
                status = "Copied the list of what's still missing"
            }) { Text("Copy the list", color = C.muted) }
        })
}
