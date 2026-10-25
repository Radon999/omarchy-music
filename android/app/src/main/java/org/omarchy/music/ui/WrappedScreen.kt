package org.omarchy.music.ui

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import org.omarchy.music.Music
import org.omarchy.music.Wrapped
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// The slides' colours, a slide each in turn (the desktop's s0 to s7).
private val SLIDE = listOf(C.accent, Color(0xFFBB9AF7), C.green, Color(0xFFE0AF68), C.red, Color(0xFF2AC3DE),
    Color(0xFF7DCFFF), Color(0xFFFF9E64))

private object W {
    val kicker = T.body.copy(color = C.fg.copy(alpha = 0.72f), fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp, fontSize = 11.sp)
    val big = T.body.copy(fontSize = 27.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 32.sp)
    val huge = T.body.copy(fontSize = 48.sp, fontWeight = FontWeight.Black, lineHeight = 54.sp)
    val sub = T.body.copy(color = C.fg.copy(alpha = 0.85f), fontSize = 14.sp)
    val small = T.body.copy(color = C.muted, fontSize = 11.5.sp)
    val rank = T.body.copy(color = C.accent, fontWeight = FontWeight.Black, fontSize = 16.sp)
    val name = T.body.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp)
}

private fun n(i: Int) = "%,d".format(i)

private class Slide(val color: Int, val save: String? = null, val content: @Composable ColumnScope.() -> Unit)

/** Your year in music from your Last.fm history, a slide at a time: tap the right of a
 *  slide for the next, the left to go back; pick another year (or all time) at the top. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WrappedScreen(onClose: () -> Unit) {
    var period by remember { mutableStateOf<Int?>(Wrapped.year()) }
    var data by remember { mutableStateOf<Wrapped.Data?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf("") }
    var seq by remember { mutableIntStateOf(0) }
    LaunchedEffect(period, seq) {
        data = null; error = null; progress = ""
        val mine = seq
        Music.bg({ Wrapped.load(Music.ctx, period) { p -> Music.scope.launch { if (mine == seq) progress = p } } }) { d, err ->
            if (mine != seq) return@bg
            data = d; error = err?.message?.take(200) ?: if (d == null) "nothing came back" else null
        }
    }
    val label = period?.toString() ?: "ALL TIME"
    val slides: List<Slide> = when {
        error != null -> listOf(Slide(4) {
            Text("COULDN'T OPEN YOUR WRAPPED", style = W.kicker)
            Text(error!!, style = W.sub)
            Spacer(Modifier.height(8.dp))
            Pill("Try again") { seq++ }
        })
        data == null -> listOf(Slide(0) {
            CircularProgressIndicator(color = C.fg, modifier = Modifier.size(44.dp))
            Spacer(Modifier.height(8.dp))
            Text(label, style = W.kicker)
            Text("Your Wrapped is on its way", style = W.big)
            Text(progress, style = W.sub)
        })
        data!!.empty -> listOf(Slide(0) {
            Text(label, style = W.kicker)
            Text("No scrobbles here yet", style = W.big)
            Text("Play some music and come back: everything you play is scrobbled to Last.fm.", style = W.sub)
        })
        data!!.period == null -> allTime(data!!)
        else -> yearSlides(data!!)
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val pager = rememberPagerState { slides.size }
        val scope = rememberCoroutineScope()
        LaunchedEffect(slides.size, data) { pager.scrollToPage(0) }
        Box(Modifier.fillMaxSize().background(C.darker)) {
            HorizontalPager(pager, Modifier.fillMaxSize(), key = { "$label-${slides.size}-$it" }) { i ->
                val s = slides[i]
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
                    val tint = SLIDE[s.color % 8]
                    Box(Modifier.fillMaxSize()
                        .background(Brush.linearGradient(0f to tint.copy(alpha = 0.5f), 0.62f to C.darker.copy(alpha = 0f),
                            start = Offset(w * 0.4f, 0f), end = Offset(w * 0.6f + h * 0.27f, h)))
                        .pointerInput(i, slides.size) {
                            detectTapGestures { o ->
                                val to = (if (o.x < size.width * 0.3f) i - 1 else i + 1).coerceIn(0, slides.size - 1)
                                scope.launch { pager.animateScrollToPage(to) }
                            }
                        }) {
                        Column(Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, top = 72.dp, bottom = 40.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically), content = s.content)
                    }
                }
            }
            // The top: a segment a slide, the year picker, close.
            Row(Modifier.statusBarsPadding().padding(start = 16.dp, end = 12.dp, top = 14.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (slides.size > 1) repeat(slides.size) { k ->
                        Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp))
                            .background(if (k <= pager.currentPage) C.fg else C.fg.copy(alpha = 0.25f)))
                    }
                }
                data?.let { d ->
                    var open by remember { mutableStateOf(false) }
                    Box {
                        Pill(if (d.period == null) "All time ▾" else "${d.period} ▾", quiet = true) { open = true }
                        DropdownMenu(open, { open = false }, containerColor = C.light) {
                            (d.years.map { it as Int? } + listOf(null)).forEach { y ->
                                DropdownMenuItem({ Text(y?.toString() ?: "All time", style = T.body) },
                                    { open = false; if (y != period) period = y })
                            }
                        }
                    }
                }
                Text("✕", style = T.title, modifier = Modifier.clip(CircleShape).background(C.darker.copy(alpha = 0.4f))
                    .pointerInput(Unit) { detectTapGestures { onClose() } }.padding(horizontal = 11.dp, vertical = 7.dp))
            }
        }
    }
}

@Composable
private fun Pic(url: String, size: Dp, round: Boolean = false) {
    Box(Modifier.size(size).clip(if (round) CircleShape else RoundedCornerShape(4.dp)).background(C.light)) {
        if (url.isNotEmpty()) AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun Ranked(items: List<Triple<String, String, Int>>, art: List<String>? = null, start: Int = 1) {
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEachIndexed { i, (name, sub, plays) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${i + start}", style = W.rank, modifier = Modifier.widthIn(min = 26.dp))
                art?.let { Pic(it.getOrElse(i) { "" }, 44.dp) }
                Column(Modifier.weight(1f)) {
                    Text(name, style = W.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (sub.isNotEmpty()) Text(sub, style = W.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(n(plays), style = W.small)
            }
        }
    }
}

/** A little bar chart; top is the bar to light up. A label sits under its bar and runs on
 *  under the unlabelled ones after it, so 24 hours fit. */
@Composable
private fun Bars(values: List<Int>, labels: List<String>, height: Dp = 110.dp, top: Int) {
    val peak = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
            values.forEachIndexed { i, v ->
                Box(Modifier.weight(1f).height(maxOf(3.dp, height * (v.toFloat() / peak))).clip(RoundedCornerShape(2.dp))
                    .background(if (i == top) C.accent else C.fg.copy(alpha = 0.22f)))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            var i = 0
            while (i < labels.size) {
                val span = ((i + 1 until labels.size).firstOrNull { labels[it].isNotEmpty() } ?: labels.size) - i
                Text(labels[i], style = W.small, maxLines = 1, modifier = Modifier.weight(span.toFloat()))
                i += span
            }
        }
    }
}

private fun day(t: Long, pattern: String) =
    DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH).format(Instant.ofEpochSecond(t).atZone(ZoneId.systemDefault()))

private fun yearSlides(d: Wrapped.Data): List<Slide> {
    val y = d.period!!
    val out = ArrayList<Slide>()
    out += Slide(0) {
        Text("YOUR", style = W.kicker)
        Text("$y", style = W.huge.copy(color = C.accent))
        Text("WRAPPED", style = W.big)
        Text("${d.user} · ${n(d.scrobbles)} scrobbles", style = W.sub)
        Text("Tap, or swipe, to start", style = W.small)
    }
    out += Slide(1) {
        Text("YOU LISTENED FOR", style = W.kicker)
        Text(n(d.minutes), style = W.huge)
        Text("minutes", style = W.big)
        Text("That's ${"%.1f".format(d.minutes / 1440.0)} days of music: ${n(d.songCount)} different songs by ${n(d.artistCount)} artists.", style = W.sub)
    }
    if (d.genres.isNotEmpty()) out += Slide(2) {
        Text("YOUR TOP GENRE", style = W.kicker)
        Text(d.genres[0], style = W.big.copy(color = C.accent))
        Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            d.genres.drop(1).forEachIndexed { i, g ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${i + 2}", style = W.rank, modifier = Modifier.widthIn(min = 26.dp)); Text(g, style = W.name, maxLines = 1)
                }
            }
        }
    }
    val (name, plays) = d.artists[0]
    out += Slide(3) {
        Text("YOUR TOP ARTIST", style = W.kicker)
        Pic(d.artistImage, 210.dp, round = true)
        Text(name, style = W.big)
        Text("${n(plays)} plays · #1 of ${n(d.artistCount)} artists", style = W.sub)
        d.topArtistSong?.let { (title, k) -> Text("Most of all, “$title”: ${n(k)} plays", style = W.small) }
    }
    out += Slide(4) {
        Text("YOUR TOP ARTISTS", style = W.kicker)
        Ranked(d.artists.take(5).map { Triple(it.first, "", it.second) })
    }
    d.songs.firstOrNull()?.let { s ->
        out += Slide(5) {
            Text("YOUR TOP SONG", style = W.kicker)
            Pic(s.art, 220.dp)
            Text(s.title, style = W.big)
            Text(s.artist, style = W.sub)
            Text("${n(s.plays)} plays" + (s.first?.let { " · first played on ${day(it, "MMMM d")}" } ?: ""), style = W.small)
        }
    }
    out += Slide(6) {
        Text("YOUR TOP SONGS", style = W.kicker)
        Ranked(d.songs.take(5).map { Triple(it.title, it.artist, it.plays) }, d.songs.take(5).map { it.art })
    }
    d.albums.firstOrNull()?.let { a ->
        out += Slide(7) {
            Text("YOUR TOP ALBUM", style = W.kicker)
            Pic(a.art, 200.dp)
            Text(a.name, style = W.big)
            Text("${a.artist} · ${n(a.plays)} plays", style = W.sub)
            d.albums.drop(1).take(3).forEachIndexed { i, x ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${i + 2}", style = W.rank, modifier = Modifier.widthIn(min = 26.dp)); Text("${x.name} · ${x.artist}", style = W.small)
                }
            }
        }
    }
    val hours = d.hours
    val peak = (0 until 24).maxBy { hours[it] }
    val night = listOf(22, 23, 0, 1, 2, 3).sumOf { hours[it] }
    val morning = (5 until 10).sumOf { hours[it] }
    val kind = if (night > 0.25 * d.scrobbles) "Night owl" else if (morning > 0.3 * d.scrobbles) "Early bird" else "Daytime listener"
    out += Slide(0) {
        Text("WHEN YOU LISTEN", style = W.kicker)
        Text(kind, style = W.big.copy(color = C.accent))
        Text("You listened most around ${if (peak % 12 == 0) 12 else peak % 12} ${if (peak < 12) "AM" else "PM"}.", style = W.sub)
        Bars(hours, (0 until 24).map { when (it) { 0 -> "12a"; 6 -> "6a"; 12 -> "12p"; 18 -> "6p"; else -> "" } }, top = peak)
        Text("And most of all on ${Wrapped.WEEKDAYS[(0 until 7).maxBy { d.weekdays[it] }]}.", style = W.sub)
    }
    val best = (0 until 12).maxBy { d.months[it] }
    out += Slide(1) {
        Text("MONTH BY MONTH", style = W.kicker)
        Text("${Wrapped.MONTH_NAMES[best]} was your month", style = W.big)
        Text("${n(d.months[best])} scrobbles in ${Wrapped.MONTHS[best]}.", style = W.sub)
        Bars(d.months, Wrapped.MONTHS.map { it.take(1) }, height = 150.dp, top = best)
    }
    out += Slide(2) {
        Text("MOMENTS", style = W.kicker)
        val busiest = d.busiest?.let { DateTimeFormatter.ofPattern("MMMM d", Locale.ENGLISH).format(it) } ?: ""
        listOf("${d.streak} days" to "your longest run of music, day after day",
            busiest to "your biggest day: ${n(d.busiestN)} scrobbles",
            (d.first?.title ?: "") to "your first song of $y, by ${d.first?.artist}",
            "${d.days} days" to "of $y had music in them").forEach { (big, small) ->
            Text(big, style = W.big.copy(fontSize = 22.sp, lineHeight = 26.sp)); Text(small, style = W.sub)
        }
    }
    out += summary(d, "$y")
    return out
}

private fun allTime(d: Wrapped.Data): List<Slide> {
    val out = ArrayList<Slide>()
    out += Slide(0) {
        Text("YOUR", style = W.kicker)
        Text("ALL TIME", style = W.huge.copy(color = C.accent))
        Text("since ${d.since}", style = W.big)
        Text("${d.user} · ${n(d.scrobbles)} scrobbles", style = W.sub)
        Text("Tap, or swipe, to start", style = W.small)
    }
    out += Slide(1) {
        Text("YOU'VE LISTENED FOR ABOUT", style = W.kicker)
        Text(n(d.minutes), style = W.huge)
        Text("minutes", style = W.big)
        Text("That's around ${n(Math.round(d.minutes / 1440.0).toInt())} days of music.", style = W.sub)
    }
    if (d.genres.isNotEmpty()) out += Slide(2) {
        Text("YOUR GENRES", style = W.kicker)
        Text(d.genres[0], style = W.big.copy(color = C.accent))
        d.genres.drop(1).forEach { Text(it, style = W.sub) }
    }
    d.artists.firstOrNull()?.let { (name, plays) ->
        out += Slide(3) {
            Text("YOUR ARTIST OF ALL TIME", style = W.kicker)
            Pic(d.artistImage, 200.dp, round = true)
            Text(name, style = W.big)
            Text("${n(plays)} plays", style = W.sub)
            Ranked(d.artists.drop(1).take(4).map { Triple(it.first, "", it.second) }, start = 2)
        }
    }
    out += Slide(5) {
        Text("YOUR SONGS OF ALL TIME", style = W.kicker)
        Ranked(d.songs.take(6).map { Triple(it.title, it.artist, it.plays) }, d.songs.take(6).map { it.art })
    }
    if (d.albums.isNotEmpty()) out += Slide(7) {
        Text("YOUR ALBUMS OF ALL TIME", style = W.kicker)
        Ranked(d.albums.take(5).map { Triple(it.name, it.artist, it.plays) }, d.albums.take(5).map { it.art })
    }
    val counts = d.yearsCount
    if (counts.isNotEmpty()) {
        val best = counts.indices.maxBy { counts[it].second }
        out += Slide(1) {
            Text("YEAR BY YEAR", style = W.kicker)
            Text("${counts[best].first} was your year", style = W.big)
            Text("${n(counts[best].second)} scrobbles.", style = W.sub)
            Bars(counts.map { it.second }, counts.mapIndexed { i, (y, _) -> if (counts.size <= 14 || i % 2 == 0) "’${y.toString().drop(2)}" else "" },
                height = 150.dp, top = best)
        }
    }
    out += summary(d, "ALL TIME")
    return out
}

/** The last slide: the lot on one card, to save as a picture. */
private fun summary(d: Wrapped.Data, title: String) = Slide(3) {
    val layer = rememberGraphicsLayer()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth()
        .drawWithContent { layer.record { this@drawWithContent.drawContent() }; drawLayer(layer) }
        .background(C.bg).border(1.dp, C.line, RoundedCornerShape(4.dp)).padding(horizontal = 18.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("$title WRAPPED · ${d.user}", style = W.kicker)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf("TOP ARTISTS" to d.artists.take(5).map { it.first }, "TOP SONGS" to d.songs.take(5).map { it.title }).forEach { (head, items) ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(head, style = W.small)
                    items.forEachIndexed { i, it -> Text("${i + 1}  $it", style = W.name.copy(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf("MINUTES" to n(d.minutes), "TOP GENRE" to (d.genres.firstOrNull() ?: "—")).forEach { (head, value) ->
                Column(Modifier.weight(1f)) {
                    Text(head, style = W.small)
                    Text(value, style = W.big.copy(color = C.accent, fontSize = 22.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Pill("Save as a picture") {
        scope.launch {
            val bmp = runCatching { layer.toImageBitmap().asAndroidBitmap() }.getOrNull()
            Music.toast(if (bmp != null && savePicture(bmp, "Wrapped ${title.lowercase().replaceFirstChar { it.uppercase() }} · ${d.user}"))
                "Saved to Pictures" else "Couldn't save it")
        }
    }
}

private fun savePicture(bmp: Bitmap, name: String): Boolean = runCatching {
    val cr = Music.ctx.contentResolver
    val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
    }) ?: return false
    cr.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: return false
    true
}.getOrDefault(false)

