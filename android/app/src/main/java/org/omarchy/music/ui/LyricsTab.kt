package org.omarchy.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.omarchy.music.Lyrics
import org.omarchy.music.Music

/** The Lyrics tab: Genius's text, or the lines timed to the music with the one being sung
 *  lit up and kept in view (tap a line to jump there). Follows the desktop while it's the remote. */
@Composable
fun LyricsTab(ui: Ui) {
    val t = Music.shownTrack()
    var got by remember { mutableStateOf<Lyrics.Got?>(null) }
    var state by remember { mutableStateOf("") }   // "", "loading", "none", or an error
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(t?.id, refresh) {
        got = null
        if (t == null) return@LaunchedEffect
        state = "loading"
        val again = refresh > 0
        Music.bg({ Lyrics.get(Music.ctx, t, again) }) { g, err ->
            if (Music.shownTrack()?.id != t.id) return@bg
            got = g
            state = err?.message?.take(140) ?: if (g == null) "none" else ""
        }
    }
    when {
        t == null -> { Empty("Nothing playing", "The lyrics of the song that's playing show up here."); return }
        state == "loading" -> { Empty("Finding the lyrics…", "${t.title} · ${t.artist}"); return }
        state == "none" -> { Empty("No lyrics found", "Genius, Tidal and LRCLIB don't have this song."); return }
        state.isNotEmpty() || got == null -> {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Empty("Couldn't get the lyrics", state)
                Pill("Try again") { refresh++ }
            }
            return
        }
    }
    val g = got!!
    if (g.instrumental && g.plain == null && g.synced == null) { Empty("Instrumental", "No words in this one."); return }
    val lines = remember(g) { Lyrics.parseLrc(g.synced) }
    val synced = lines.isNotEmpty(); val plain = g.plain != null
    // Genius's annotations: the lines they're about can be tapped (the words as written only).
    var notes by remember(g) { mutableStateOf<List<Lyrics.Noted>>(emptyList()) }
    LaunchedEffect(g) { if (plain) Music.bg({ Lyrics.notes(Music.ctx, g) }) { n, _ -> if (n != null && got == g) notes = n } }
    var mode = Music.lyricsMode
    if (mode == "synced" && !synced) mode = "genius"
    if (mode == "genius" && !plain) mode = "synced"
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (mode == "synced") "TIMED · ${(g.synced_from ?: "").uppercase()}" else "LYRICS · ${(g.plain_from ?: "").uppercase()}" +
                if (notes.isNotEmpty()) " · ${notes.size} NOTE${if (notes.size != 1) "S" else ""}: TAP A MARKED LINE" else "",
                style = T.section, maxLines = 1, modifier = Modifier.weight(1f))
            if (synced && plain) {
                Chip("Genius", mode == "genius") { Music.setLyricsMode("genius") }
                Chip("Synced", mode == "synced") { Music.setLyricsMode("synced") }
            }
        }
        if (mode == "synced") Synced(lines) else Plain(g.plain!!, notes, g.url)
    }
}

@Composable
private fun Plain(text: String, notes: List<Lyrics.Noted>, page: String?) {
    var open by remember(notes) { mutableStateOf<Int?>(null) }
    val noted = remember(text, notes) { Lyrics.placeNotes(text, notes) }
    val marked = TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline, background = C.accent.copy(alpha = 0.10f)))
    val styled = remember(text, noted) {
        buildAnnotatedString {
            text.lines().forEachIndexed { i, line ->
                if (i > 0) append('\n')
                val k = noted[i]
                if (Regex("\\s*\\[[^]]+]\\s*").matches(line))   // [Verse 1], [Chorus]: Genius's section names
                    withStyle(SpanStyle(color = C.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)) { append(line.uppercase()) }
                else if (k != null && line.isNotBlank()) withLink(LinkAnnotation.Clickable("note$k", marked) { open = k }) { append(line) }
                else append(line)
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        SelectionContainer {
            Text(styled, style = T.body.copy(fontSize = 15.sp, lineHeight = 23.sp),
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp))
        }
        open?.let { k -> notes.getOrNull(k)?.let { NoteCard(it, page, Modifier.align(Alignment.BottomCenter)) { open = null } } }
    }
}

/** One of Genius's annotations, over the bottom of the lyrics: the best of the line's notes. */
@Composable
private fun NoteCard(n: Lyrics.Noted, page: String?, modifier: Modifier, close: () -> Unit) {
    val uri = LocalUriHandler.current
    val best = n.notes.maxWith(compareBy<Lyrics.Note>({ it.verified }, { it.state == "accepted" }, { it.votes }))
    val shape = RoundedCornerShape(6.dp)
    Column(modifier.padding(10.dp).fillMaxWidth().heightIn(max = 340.dp).background(C.light, shape)
        .border(1.dp, C.accent.copy(alpha = 0.45f), shape).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text(n.fragment.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" / "),
                style = T.body.copy(color = C.accent, fontWeight = FontWeight.Bold, fontSize = 12.sp), maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("✕", style = T.title, modifier = Modifier.clickable(onClick = close).padding(start = 10.dp, end = 2.dp))
        }
        Text(best.text, style = T.body, modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val about = listOf(if (best.votes != 0) "%+,d votes".format(best.votes) else "",
                if (best.verified) "verified by the artist" else if (best.state == "accepted") "" else "not reviewed yet",
                if (n.notes.size > 1) "${n.notes.size - 1} more on Genius" else "").filter { it.isNotEmpty() }.joinToString(" · ")
            Text(about, style = T.sub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("On Genius", style = T.sub.copy(color = C.accent),
                modifier = Modifier.clickable { runCatching { uri.openUri(n.url.ifEmpty { page ?: "https://genius.com/" }) } }.padding(start = 10.dp))
        }
    }
}

@Composable
private fun Synced(lines: List<Pair<Double, String>>) {
    var pos by remember { mutableDoubleStateOf(Music.livePosition()) }
    LaunchedEffect(Unit) { while (true) { pos = Music.livePosition(); delay(120) } }
    val times = remember(lines) { lines.map { it.first } }
    // The line being sung: a quarter second early, as you read ahead.
    val cur = times.indexOfLast { it <= pos + 0.25 }
    val list = rememberLazyListState()
    val dragged by list.interactionSource.collectIsDraggedAsState()
    var touched by remember { mutableLongStateOf(0L) }
    LaunchedEffect(dragged) { if (dragged) touched = System.currentTimeMillis() }
    LaunchedEffect(cur) {
        if (cur < 0 || System.currentTimeMillis() - touched < 4000) return@LaunchedEffect   // you're reading ahead: leave it
        val info = list.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == cur }
        if (item != null) {
            val centre = (info.viewportEndOffset - info.viewportStartOffset) / 3
            list.animateScrollBy((item.offset - centre).toFloat())
        } else list.scrollToItem(maxOf(0, cur - 3))
    }
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        items(lines.size) { i ->
            val (at, words) = lines[i]
            Text(words.ifEmpty { "♪" },
                style = T.body.copy(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = if (i == cur) FontWeight.ExtraBold else FontWeight.Bold,
                    color = when { i == cur -> C.accent; i < cur -> C.muted.copy(alpha = 0.7f); else -> C.fg.copy(alpha = 0.85f) }),
                modifier = Modifier.fillMaxWidth().clickable { Music.seek(at) }.padding(vertical = 7.dp))
        }
    }
}
