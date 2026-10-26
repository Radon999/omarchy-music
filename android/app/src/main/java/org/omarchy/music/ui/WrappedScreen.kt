package org.omarchy.music.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// The slides' colours, a slide each in turn (the desktop's s0 to s7), and the second colour
// each one glows with in its top corner.
private val MAGENTA = Color(0xFFBB9AF7); private val YELLOW = Color(0xFFE0AF68); private val BLUE = Color(0xFF2AC3DE)
private val CYAN = Color(0xFF7DCFFF); private val ORANGE = Color(0xFFFF9E64)
private val SLIDE = listOf(C.accent, MAGENTA, C.green, YELLOW, C.red, BLUE, CYAN, ORANGE)
private val GLOW = listOf(MAGENTA, CYAN, YELLOW, C.red, MAGENTA, C.green, BLUE, YELLOW)
private val KINDS = listOf("week" to "Week", "month" to "Month", "year" to "Year", "all" to "All time")

private object W {
    val kicker = T.body.copy(color = C.fg.copy(alpha = 0.72f), fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp, fontSize = 11.sp)
    val big = T.body.copy(fontSize = 27.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 32.sp)
    val huge = T.body.copy(fontSize = 48.sp, fontWeight = FontWeight.Black, lineHeight = 54.sp)
    val sub = T.body.copy(color = C.fg.copy(alpha = 0.85f), fontSize = 14.sp)
    val small = T.body.copy(color = C.muted, fontSize = 11.5.sp)
    val rank = T.body.copy(color = C.accent, fontWeight = FontWeight.Black, fontSize = 16.sp)
    val name = T.body.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp)
    val mid = T.body.copy(fontWeight = FontWeight.ExtraBold, fontSize = 19.sp)
}

private fun n(i: Int) = "%,d".format(i)

private class Slide(val color: Int, val content: @Composable ColumnScope.() -> Unit)

/** The summary slide's Share…: opens the top's menu, and isn't in the picture. */
private class Sharing(val open: () -> Unit, val busy: Boolean)
private val LocalSharing = compositionLocalOf<Sharing?> { null }

/** Apps a slide's picture can go to straight away, when they're installed. */
private val SHARE_APPS = listOf("com.instagram.android" to "Instagram", "com.facebook.katana" to "Facebook")

/** Your week, month or year in music (or all of it) from your Last.fm history, a slide at a
 *  time: tap the right of a slide for the next, the left to go back; Week · Month · Year ·
 *  All time at the top, and which one of them. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WrappedScreen(onClose: () -> Unit) {
    var period by remember { mutableStateOf<Wrapped.Period>(Wrapped.Period.Year(Wrapped.year())) }
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
    val label = period.name.uppercase()
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
        data!!.period == Wrapped.Period.All -> allTime(data!!)
        else -> periodSlides(data!!)
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val pager = rememberPagerState { slides.size }
        val scope = rememberCoroutineScope()
        LaunchedEffect(slides.size, data) { pager.scrollToPage(0) }
        val ctx = LocalContext.current
        val layers = remember { HashMap<Int, GraphicsLayer>() }   // each page's drawing, for its picture
        var busy by remember { mutableStateOf(false) }
        var menu by remember { mutableStateOf(false) }
        val apps = remember { SHARE_APPS.filter { (pkg, _) -> runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isSuccess } }

        /** A picture of the slide showing: saved to Pictures, or sent to an app (pkg) or to the one you pick (null). */
        fun share(save: Boolean, pkg: String? = null) {
            val d = data ?: return
            val i = pager.currentPage
            val layer = layers[i] ?: return
            scope.launch {
                busy = true                            // the Share… button hides,
                withFrameNanos {}; withFrameNanos {}   // and the page is drawn again without it
                val bmp = runCatching { layer.toImageBitmap().asAndroidBitmap() }.getOrNull()
                busy = false
                val last = i == slides.size - 1   // the summary: named as it always was
                val name = "Wrapped ${label.lowercase().replaceFirstChar { it.uppercase() }} · ${d.user}${if (last) "" else " · ${i + 1}"}"
                when {
                    bmp == null -> Music.toast("Couldn't make the picture")
                    save -> Music.toast(if (savePicture(bmp, name)) "Saved to Pictures" else "Couldn't save it")
                    else -> if (!sharePicture(ctx, bmp, name, pkg)) Music.toast("Couldn't share it")
                }
            }
        }
        Box(Modifier.fillMaxSize().background(C.darker)) {
            HorizontalPager(pager, Modifier.fillMaxSize(), key = { "$label-${slides.size}-$it" }) { i ->
                val s = slides[i]
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
                    val tint = SLIDE[s.color % 8]
                    val glow = GLOW[s.color % 8]
                    val layer = rememberGraphicsLayer()
                    DisposableEffect(i, layer) { layers[i] = layer; onDispose { layers.remove(i, layer) } }
                    Box(Modifier.fillMaxSize()
                        .drawWithContent { layer.record { this@drawWithContent.drawContent() }; drawLayer(layer) }
                        .background(C.darker)
                        .background(Brush.linearGradient(0f to tint.copy(alpha = 0.5f), 0.62f to C.darker.copy(alpha = 0f),
                            start = Offset(w * 0.4f, 0f), end = Offset(w * 0.6f + h * 0.27f, h)))
                        .background(Brush.radialGradient(listOf(glow.copy(alpha = 0.30f), Color.Transparent),
                            center = Offset(w * 0.92f, h * 0.08f), radius = maxOf(w, h) * 0.38f))
                        .background(Brush.radialGradient(listOf(tint.copy(alpha = 0.22f), Color.Transparent),
                            center = Offset(0f, h), radius = maxOf(w, h) * 0.45f))
                        .pointerInput(i, slides.size) {
                            detectTapGestures { o ->
                                val to = (if (o.x < size.width * 0.3f) i - 1 else i + 1).coerceIn(0, slides.size - 1)
                                scope.launch { pager.animateScrollToPage(to) }
                            }
                        }) {
                        CompositionLocalProvider(LocalSharing provides Sharing({ menu = true }, busy)) {
                            Column(Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, top = 112.dp, bottom = 40.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically), content = s.content)
                        }
                    }
                }
            }
            // The top: a segment a slide and close; under them Week · Month · Year · All time, and which one.
            Column(Modifier.statusBarsPadding().padding(start = 16.dp, end = 12.dp, top = 14.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (slides.size > 1) repeat(slides.size) { k ->
                            Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp))
                                .background(if (k <= pager.currentPage) C.fg else C.fg.copy(alpha = 0.25f)))
                        }
                    }
                    if (data?.empty == false) Box {
                        Text("Share", style = T.tab.copy(fontSize = 12.sp), modifier = Modifier.clip(CircleShape).background(C.darker.copy(alpha = 0.4f))
                            .pointerInput(Unit) { detectTapGestures { menu = true } }.padding(horizontal = 12.dp, vertical = 8.dp))
                        DropdownMenu(menu, { menu = false }, containerColor = C.light) {
                            DropdownMenuItem({ Text("Save to Pictures", style = T.body) }, { menu = false; share(save = true) })
                            apps.forEach { (pkg, name) ->
                                DropdownMenuItem({ Text("Post to $name", style = T.body) }, { menu = false; share(save = false, pkg = pkg) })
                            }
                            DropdownMenuItem({ Text("Share…", style = T.body) }, { menu = false; share(save = false) })
                        }
                    }
                    Text("✕", style = T.title, modifier = Modifier.clip(CircleShape).background(C.darker.copy(alpha = 0.4f))
                        .pointerInput(Unit) { detectTapGestures { onClose() } }.padding(horizontal = 11.dp, vertical = 7.dp))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.clip(RoundedCornerShape(4.dp)).background(C.darker.copy(alpha = 0.45f))) {
                        KINDS.forEach { (kind, name) ->
                            val on = period.kind == kind
                            Text(name, style = T.tab.copy(color = if (on) C.darker else C.fg.copy(alpha = 0.75f), fontSize = 12.sp),
                                modifier = Modifier.background(if (on) C.accent else Color.Transparent)
                                    .pointerInput(kind, period) { detectTapGestures { if (!on) period = Wrapped.Period.current(kind) } }
                                    .padding(horizontal = 11.dp, vertical = 6.dp))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    val choices = Wrapped.Period.choices(period.kind, data?.years ?: listOf(LocalDate.now().year))
                    if (choices.isNotEmpty()) {
                        var open by remember { mutableStateOf(false) }
                        Box {
                            Pill("${choices.firstOrNull { it.first == period }?.second ?: period.name} ▾", quiet = true) { open = true }
                            DropdownMenu(open, { open = false }, containerColor = C.light) {
                                choices.forEach { (p, name) ->
                                    DropdownMenuItem({ Text(name, style = T.body) }, { open = false; if (p != period) period = p })
                                }
                            }
                        }
                    }
                }
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
private fun Ranked(items: List<Triple<String, String, Int>>, art: List<String>? = null, start: Int = 1, meter: Boolean = false) {
    val top = (items.maxOfOrNull { it.third } ?: 0).coerceAtLeast(1)
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEachIndexed { i, (name, sub, plays) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${i + start}", style = W.rank, modifier = Modifier.widthIn(min = 26.dp))
                art?.let { Pic(it.getOrElse(i) { "" }, 44.dp) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(name, style = W.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (sub.isNotEmpty()) Text(sub, style = W.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (meter) Meter(plays.toFloat() / top)
                }
                Text(n(plays), style = W.small)
            }
        }
    }
}

/** A thin bar, filled as far as fraction (0..1). */
@Composable
private fun Meter(fraction: Float) {
    Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50)).background(C.fg.copy(alpha = 0.12f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).fillMaxHeight().clip(RoundedCornerShape(50)).background(C.accent))
    }
}

/** A short accent line under a heading. */
@Composable
private fun Rule() = Box(Modifier.width(36.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(C.accent))

/** Numbers in tiles, two to a row: (value, what it is). */
@Composable
private fun Stats(tiles: List<Pair<String, String>>) {
    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (value, what) ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(C.fg.copy(alpha = 0.07f))
                        .border(1.dp, C.fg.copy(alpha = 0.10f), RoundedCornerShape(6.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(value, style = W.mid, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(what, style = W.small)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** "▲ 12% more than the month before" (or less), when there's something to compare with. */
@Composable
private fun Change(d: Wrapped.Data) {
    val before = d.prevCount ?: return
    if (before <= 0 || d.period == Wrapped.Period.All) return
    val pct = Math.round((d.scrobbles - before) * 100.0 / before).toInt()
    val than = if (d.partial) "this far into the ${d.period.kind} before" else "the ${d.period.kind} before"
    val text = if (pct > 0) "▲ $pct% more than $than" else if (pct < 0) "▼ ${-pct}% less than $than" else "Just like $than"
    val color = if (pct >= 0) C.green else C.red
    Text(text, style = W.small.copy(color = color, fontWeight = FontWeight.Bold),
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.18f)).padding(horizontal = 12.dp, vertical = 4.dp))
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

private val WEEK_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")

private fun day(t: Long, pattern: String) =
    DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH).format(Instant.ofEpochSecond(t).atZone(ZoneId.systemDefault()))

/** A year's, a month's or a week's Wrapped. */
private fun periodSlides(d: Wrapped.Data): List<Slide> {
    val p = d.period
    val out = ArrayList<Slide>()
    out += Slide(0) {
        Text("YOUR", style = W.kicker)
        Text(p.title, style = W.huge.copy(color = C.accent))
        Text("WRAPPED", style = W.big)
        Rule()
        Text("${d.user} · ${n(d.scrobbles)} scrobbles", style = W.sub)
        Change(d)
        Text("Tap, or swipe, to start", style = W.small)
    }
    out += Slide(1) {
        Text("YOU LISTENED FOR", style = W.kicker)
        Text(n(d.minutes), style = W.huge)
        Text("minutes", style = W.big)
        val hours = d.minutes / 60.0
        Text(if (hours >= 48) "That's ${"%.1f".format(d.minutes / 1440.0)} days of music." else "That's ${"%.1f".format(hours)} hours of music.",
            style = W.sub)
        Stats(listOf(n(d.songCount) to "different songs", n(d.artistCount) to "artists",
            "${d.days} of ${d.spanDays}" to "days with music", n(Math.round(d.minutes.toDouble() / d.spanDays.coerceAtLeast(1)).toInt()) to "minutes a day"))
    }
    if (d.genres.isNotEmpty()) out += Slide(2) {
        Text("YOUR TOP GENRE", style = W.kicker)
        Text(d.genres[0], style = W.big.copy(color = C.accent))
        Rule()
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            d.genres.drop(1).forEachIndexed { i, g ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${i + 2}", style = W.rank, modifier = Modifier.widthIn(min = 26.dp)); Text(g, style = W.name, maxLines = 1)
                }
            }
        }
    }
    val (name, plays) = d.artists[0]
    val share = if (d.scrobbles > 0) plays.toFloat() / d.scrobbles else 0f
    out += Slide(3) {
        Text("YOUR TOP ARTIST", style = W.kicker)
        Pic(d.artistImage, 210.dp, round = true)
        Text(name, style = W.big)
        Text("${n(plays)} plays · ${Math.round(share * 100)}% of everything you played", style = W.sub)
        Meter(share)
        d.topArtistSong?.let { (title, k) -> Text("Most of all, “$title”: ${n(k)} plays", style = W.small) }
    }
    out += Slide(4) {
        Text("YOUR TOP ARTISTS", style = W.kicker)
        Text("#1 of ${n(d.artistCount)}", style = W.mid.copy(color = C.accent))
        Ranked(d.artists.take(5).map { Triple(it.first, "", it.second) }, meter = true)
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
        Ranked(d.songs.take(5).map { Triple(it.title, it.artist, it.plays) }, d.songs.take(5).map { it.art }, meter = true)
    }
    d.albums.firstOrNull()?.let { a ->
        out += Slide(7) {
            Text("YOUR TOP ALBUM", style = W.kicker)
            Pic(a.art, 200.dp)
            Text(a.name, style = W.big)
            Text("${a.artist} · ${n(a.plays)} plays", style = W.sub)
            d.albums.drop(1).take(3).forEachIndexed { i, x ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${i + 2}", style = W.rank, modifier = Modifier.widthIn(min = 26.dp))
                    Pic(x.art, 36.dp)
                    Text("${x.name} · ${x.artist}", style = W.small)
                }
            }
        }
    }
    val hours = d.hours
    val peak = (0 until 24).maxBy { hours[it] }
    val night = listOf(22, 23, 0, 1, 2, 3).sumOf { hours[it] }
    val morning = (5 until 10).sumOf { hours[it] }
    val kind = if (night > 0.25 * d.scrobbles) "Night owl" else if (morning > 0.3 * d.scrobbles) "Early bird" else "Daytime listener"
    val weekday = (0 until 7).maxBy { d.weekdays[it] }
    out += Slide(0) {
        Text("WHEN YOU LISTEN", style = W.kicker)
        Text(kind, style = W.big.copy(color = C.accent))
        Text("You listened most around ${if (peak % 12 == 0) 12 else peak % 12} ${if (peak < 12) "AM" else "PM"}.", style = W.sub)
        Bars(hours, (0 until 24).map { when (it) { 0 -> "12a"; 6 -> "6a"; 12 -> "12p"; 18 -> "6p"; else -> "" } }, top = peak)
        Text("And most of all on ${Wrapped.WEEKDAYS[weekday]}.", style = W.sub)
        Bars(d.weekdays, WEEK_LETTERS, height = 50.dp, top = weekday)
    }
    if (p is Wrapped.Period.Year) {
        val best = (0 until 12).maxBy { d.months[it] }
        out += Slide(1) {
            Text("MONTH BY MONTH", style = W.kicker)
            Text("${Wrapped.MONTH_NAMES[best]} was your month", style = W.big)
            Text("${n(d.months[best])} scrobbles in ${Wrapped.MONTHS[best]}.", style = W.sub)
            Bars(d.months, Wrapped.MONTHS.map { it.take(1) }, height = 150.dp, top = best)
        }
    } else if (d.daily.isNotEmpty()) {
        val best = d.daily.indices.maxBy { d.daily[it] }
        val bestDay = p.start().plusDays(best.toLong())
        out += Slide(1) {
            Text("DAY BY DAY", style = W.kicker)
            Text(if (p is Wrapped.Period.Week) "${DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH).format(bestDay)} was your day"
                 else "${DateTimeFormatter.ofPattern("MMMM d", Locale.ENGLISH).format(bestDay)} was your day", style = W.big)
            Text("${n(d.daily[best])} scrobbles that day.", style = W.sub)
            Bars(d.daily, if (p is Wrapped.Period.Week) WEEK_LETTERS else d.daily.indices.map { if (it % 7 == 0) "${it + 1}" else "" },
                height = 150.dp, top = best)
        }
    }
    out += Slide(2) {
        Text("MOMENTS", style = W.kicker)
        val busiest = d.busiest?.let { DateTimeFormatter.ofPattern("MMMM d", Locale.ENGLISH).format(it) } ?: ""
        val moments = ArrayList<Pair<String, String>>()
        if (p !is Wrapped.Period.Week) moments += "${d.streak} days" to "your longest run of music, day after day"
        moments += busiest to "your biggest day: ${n(d.busiestN)} scrobbles"
        moments += (d.first?.title ?: "") to "your first song of ${p.of}, by ${d.first?.artist}"
        moments += "${d.days} days" to "of ${d.spanDays} had music in them"
        moments.forEach { (big, small) ->
            Text(big, style = W.big.copy(fontSize = 22.sp, lineHeight = 26.sp)); Text(small, style = W.sub)
        }
    }
    out += summary(d, p.name.uppercase())
    return out
}

private fun allTime(d: Wrapped.Data): List<Slide> {
    val out = ArrayList<Slide>()
    out += Slide(0) {
        Text("YOUR", style = W.kicker)
        Text("ALL TIME", style = W.huge.copy(color = C.accent))
        Text("since ${d.since}", style = W.big)
        Rule()
        Text("${d.user} · ${n(d.scrobbles)} scrobbles", style = W.sub)
        Text("Tap, or swipe, to start", style = W.small)
    }
    out += Slide(1) {
        Text("YOU'VE LISTENED FOR ABOUT", style = W.kicker)
        Text(n(d.minutes), style = W.huge)
        Text("minutes", style = W.big)
        Text("That's around ${n(Math.round(d.minutes / 1440.0).toInt())} days of music.", style = W.sub)
        val years = (LocalDate.now().year - d.since + 1).coerceAtLeast(1)
        Stats(listOf(n(d.scrobbles) to "scrobbles", "$years" to "years of history",
            n(d.scrobbles / years) to "scrobbles a year", n(Math.round(d.minutes / (years * 365.0)).toInt()) to "minutes a day"))
    }
    if (d.genres.isNotEmpty()) out += Slide(2) {
        Text("YOUR GENRES", style = W.kicker)
        Text(d.genres[0], style = W.big.copy(color = C.accent))
        Rule()
        d.genres.drop(1).forEach { Text(it, style = W.sub) }
    }
    d.artists.firstOrNull()?.let { (name, plays) ->
        out += Slide(3) {
            Text("YOUR ARTIST OF ALL TIME", style = W.kicker)
            Pic(d.artistImage, 200.dp, round = true)
            Text(name, style = W.big)
            Text("${n(plays)} plays", style = W.sub)
            Ranked(d.artists.drop(1).take(4).map { Triple(it.first, "", it.second) }, start = 2, meter = true)
        }
    }
    out += Slide(5) {
        Text("YOUR SONGS OF ALL TIME", style = W.kicker)
        Ranked(d.songs.take(6).map { Triple(it.title, it.artist, it.plays) }, d.songs.take(6).map { it.art }, meter = true)
    }
    if (d.albums.isNotEmpty()) out += Slide(7) {
        Text("YOUR ALBUMS OF ALL TIME", style = W.kicker)
        Ranked(d.albums.take(5).map { Triple(it.name, it.artist, it.plays) }, d.albums.take(5).map { it.art }, meter = true)
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
    Column(Modifier.fillMaxWidth()
        .background(C.bg).background(Brush.linearGradient(listOf(C.accent.copy(alpha = 0.20f), Color.Transparent)))
        .border(1.dp, C.line, RoundedCornerShape(4.dp)).padding(horizontal = 18.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Pic(d.artistImage, 56.dp, round = true)
            Column(Modifier.weight(1f)) {
                Text("$title WRAPPED", style = W.kicker)
                Text(d.user, style = W.mid, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Rule()
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf("TOP ARTISTS" to d.artists.take(5).map { it.first }, "TOP SONGS" to d.songs.take(5).map { it.title }).forEach { (head, items) ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(head, style = W.small)
                    items.forEachIndexed { i, it -> Text("${i + 1}  $it", style = W.name.copy(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf("MINUTES" to n(d.minutes), "SCROBBLES" to n(d.scrobbles), "TOP GENRE" to (d.genres.firstOrNull() ?: "—")).forEach { (head, value) ->
                Column(Modifier.weight(1f)) {
                    Text(head, style = W.small)
                    Text(value, style = W.mid.copy(color = C.accent), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    val sharing = LocalSharing.current
    Box(Modifier.alpha(if (sharing?.busy == true) 0f else 1f)) {   // (kept in place, so nothing moves)
        Pill("Share…") { sharing?.open?.invoke() }
    }
}

/** A bitmap Bitmap.compress can write (a GraphicsLayer's may be in the GPU's memory). */
private fun Bitmap.plain(): Bitmap = if (config == Bitmap.Config.HARDWARE) copy(Bitmap.Config.ARGB_8888, false) else this

private fun savePicture(bmp: Bitmap, name: String): Boolean = runCatching {
    val cr = Music.ctx.contentResolver
    val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
    }) ?: return false
    cr.openOutputStream(uri)?.use { bmp.plain().compress(Bitmap.CompressFormat.PNG, 100, it) } ?: return false
    true
}.getOrDefault(false)

/** Send the picture to pkg (Instagram, Facebook), or to the app you pick in Android's share sheet. */
private fun sharePicture(ctx: Context, bmp: Bitmap, name: String, pkg: String?): Boolean = runCatching {
    val dir = File(ctx.cacheDir, "shared").apply { mkdirs(); listFiles()?.forEach { it.delete() } }   // only the latest one is kept
    val file = File(dir, name.replace(Regex("[\\\\/:*?\"<>|]"), "") + ".png")
    file.outputStream().use { bmp.plain().compress(Bitmap.CompressFormat.PNG, 100, it) }
    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = android.content.ClipData.newRawUri(name, uri)
    ctx.startActivity(if (pkg != null) send.setPackage(pkg) else Intent.createChooser(send, "Share your Wrapped"))
    true
}.getOrDefault(false)

