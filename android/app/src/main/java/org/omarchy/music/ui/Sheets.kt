package org.omarchy.music.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.omarchy.music.DESKTOP_UA
import org.omarchy.music.Eq
import org.omarchy.music.LastFm
import org.omarchy.music.Music
import org.omarchy.music.Remote
import org.omarchy.music.Updater
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import org.omarchy.music.SOURCES
import org.omarchy.music.Services
import org.omarchy.music.Tidal

@Composable
fun Sheets(ui: Ui) {
    when (ui.sheet) {
        "accounts" -> AccountsSheet(ui)
        "eq" -> EqSheet(ui)
        "tidal-login" -> WebLogin(Music.tidal.loginUrl(), { it.startsWith(Tidal.REDIRECT) }) { url ->
            ui.sheet = "accounts"; if (url != null) Music.tidalLoggedIn(url)
        }
        "ytm-login" -> WebLogin("https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&passive=true&continue=" +
            "https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F",
            { it.startsWith("https://music.youtube.com") && Services.ytmCookiesReady() }) { url ->
            ui.sheet = "accounts"; if (url != null) Services.ytmSignedIn()
        }
        "apple-login" -> WebLogin("https://music.apple.com/login", { Services.appleCookiesReady() }, desktop = true) { url ->
            ui.sheet = "accounts"; if (url != null) Services.appleSignedIn()
        }
        "spotify-login" -> WebLogin(Services.spotifyLoginUrl(), { it.startsWith(org.omarchy.music.Spotify.REDIRECT) }) { url ->
            ui.sheet = "accounts"; if (url != null) Services.spotifyFinish(url)
        }
        "lastfm-login" -> WebLogin(LastFm.authUrl(), { LastFm.authDone(it) }) { url ->
            ui.sheet = "accounts"; LastFm.finishLogin(quiet = url == null)
        }
        "wrapped" -> WrappedScreen { ui.sheet = null }
    }
    ui.pickPlaylistFor?.let { tracks -> PlaylistPicker(ui, tracks) }
    ui.folderFor?.let { item -> FolderPicker(ui, item) }
    ui.editTagsFor?.let { tracks -> TagsDialog(ui, tracks) }
    DeleteDialog(ui)
    ui.coverFor?.let { albums -> CoverDialog(ui, albums) }
    ui.artistPictureFor?.let { name -> ArtistPictureDialog(ui, name) }
    ui.speedFor?.let { tracks -> if (tracks.isNotEmpty()) SpeedDialog(ui, tracks) else ui.speedFor = null }
    ui.askName?.let { (title, start, done) -> NameDialog(title, start, { ui.askName = null }) { ui.askName = null; done(it) } }
    ui.confirm?.let { (text, act) ->
        AlertDialog(onDismissRequest = { ui.confirm = null }, containerColor = C.light,
            text = { Text(text, style = T.body) },
            confirmButton = { TextButton({ ui.confirm = null; act() }) { Text("Yes", color = C.red) } },
            dismissButton = { TextButton({ ui.confirm = null }) { Text("Cancel", color = C.muted) } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountsSheet(ui: Ui) {
    Music.accountsVersion
    ModalBottomSheet(onDismissRequest = { ui.sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = C.light) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 24.dp)) {
            LaunchedEffect(Unit) { Updater.checkSoon() }
            val activity = LocalContext.current.let { c0 ->   // the sheet's context wraps the activity's
                var c = c0
                while (c !is android.app.Activity && c is android.content.ContextWrapper) c = c.baseContext
                c as? android.app.Activity
            }
            val v = Updater.latest
            if (v != null) Line("Update", when (Updater.state) {
                "" -> "Omarchy Music $v is out (this is ${Updater.version})"
                "working" -> "Downloading $v…"
                "confirm" -> "Downloaded $v: install it"
                else -> Updater.state
            }, false) {
                when (Updater.state) {
                    "working" -> Unit
                    "confirm" -> Pill("Install") { activity?.let { Updater.askAgain(it) } }
                    else -> Pill("Update") { Updater.install() }
                }
            } else Line("Version", if (Updater.checking) "${Updater.version} · checking…"
                else Updater.checkError.ifEmpty { "${Updater.version} · up to date" }.let { if (it.startsWith("couldn't")) "${Updater.version} · $it" else it }, false) {
                if (!Updater.checking) Pill("Check", quiet = true) { Updater.check(quiet = false) }
            }
            Divider()
            // ---- play on ----
            Line("Play on", if (Remote.active) "The desktop (${Remote.name})" + if (!Remote.reachable) " · not reachable" else ""
                else "This phone", Remote.active) {
                if (Remote.paired) Switch(Remote.on, { Remote.setOn(it) }, colors = swColors())
            }
            if (!Remote.paired) PairDesktop()
            else Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Paired with ${Remote.host}", style = T.sub, modifier = Modifier.weight(1f))
                Pill("Forget", quiet = true) { Remote.forget() }
            }
            Divider()
            // ---- services ----
            for (key in listOf("ytm", "apple")) {
                val src = Music.sources[key]
                if (src?.signedIn == true) Line(SOURCES[key]!!, "Signed in", true) {
                    SyncSwitch(key); Spacer(Modifier.width(6.dp)); Pill("Sign out", quiet = true) { Services.signOut(key) }
                } else Line(SOURCES[key]!!, "Sign in to browse your library and playlists", false) {
                    Pill("Sign in") { ui.sheet = "$key-login" }
                }
            }
            if (Music.tidal.connected) {
                Line("Tidal", "Connected · songs play in FLAC (lossy only where Tidal has no FLAC)", true) {
                    SyncSwitch("tidal"); Spacer(Modifier.width(6.dp)); Pill("Disconnect", quiet = true) { Music.tidalDisconnect() }
                }
                Line("Keep", "Save every song you play, in FLAC. Off: songs stream from Tidal and download only when you pick Download",
                    Music.keepPlayed) { Switch(Music.keepPlayed, { Music.setKeepPlayed(it) }, colors = swColors()) }
            } else Line("Tidal", "Connect to play everything in FLAC (it needs a Tidal subscription)", false) {
                Pill("Connect") { ui.sheet = "tidal-login" }
            }
            SpotifyLine(ui)
            Divider()
            // ---- your music ----
            val n = Music.local.files.size
            Line("Folders", if (Music.local.scanning) "Reading your music…" else "$n song${if (n != 1) "s" else ""} in your folders, in the Music tab", n > 0) {
                FolderPicker()
            }
            Music.folders.forEach { f ->
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(f, style = T.sub, modifier = Modifier.weight(1f))
                    Pill("Remove", quiet = true) { Music.removeFolder(f) }
                }
            }
            Line("Colours", "The window takes the colour of the album art", Music.artColors) {
                Switch(Music.artColors, { Music.setArtColors(it) }, colors = swColors())
            }
            Divider()
            LastFmLine(ui)
            SpotifyHistoryLine(ui)
            Divider()
            SettingsLine()
        }
    }
}

@Composable
private fun PairDesktop() {
    var addr by remember { mutableStateOf(Remote.found.firstOrNull()?.second ?: "") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Text("To control the desktop app from here, switch on Phone remote in its accounts menu, then enter the code it shows.",
        style = T.sub, modifier = Modifier.padding(bottom = 6.dp))
    Remote.found.forEach { (name, a) ->
        Text("◉ $name  ($a)", style = T.body.copy(color = if (a == addr) C.accent else C.fg),
            modifier = Modifier.fillMaxWidth().clickable { addr = a }.padding(vertical = 6.dp))
    }
    Field(addr, "Desktop address, like 192.168.1.20") { addr = it }
    Field(code, "Pairing code") { code = it }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.End) {
        Pill(if (busy) "Pairing…" else "Pair") {
            if (addr.isBlank() || code.isBlank() || busy) return@Pill
            busy = true
            Remote.pair(addr, code) { err -> busy = false; Music.toast(if (err == null) "Paired: playing on the desktop" else "Couldn't pair: ${err.message?.take(80)}") }
        }
    }
}

@Composable
private fun SpotifyLine(ui: Ui) {
    val sp = Music.sources["spotify"] as? org.omarchy.music.Spotify
    if (sp?.signedIn == true) {
        Line("Spotify", if (sp.user.isNotEmpty()) "Connected as ${sp.user}" else "Connected", true) {
            SyncSwitch("spotify"); Spacer(Modifier.width(6.dp)); Pill("Disconnect", quiet = true) { Services.spotifyDisconnect() }
        }
        return
    }
    var cid by remember { mutableStateOf(sp?.clientId ?: "") }
    Line("Spotify", "Spotify wants a developer app of yours (its owner needs Premium). Make one with the Web API ticked and " +
        "this redirect URI, then paste its Client ID:", false) {}
    Text(org.omarchy.music.Spotify.REDIRECT, style = T.sub.copy(color = C.fg), modifier = Modifier.padding(bottom = 4.dp))
    Field(cid, "Client ID") { cid = it }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        Pill("Make an app", quiet = true) { Services.open("https://developer.spotify.com/dashboard/create") }
        Pill("Connect") { if (Services.spotifySetClient(cid)) ui.sheet = "spotify-login" }
    }
}

@Composable
private fun LastFmLine(ui: Ui) {
    if (LastFm.connected) {
        Line("Last.fm", "Scrobbling as ${LastFm.user}", true) { Pill("Disconnect", quiet = true) { LastFm.disconnect() } }
        ActionRow("✦  Your ${org.omarchy.music.Wrapped.year()} Wrapped  ›") { ui.sheet = "wrapped" }
        return
    }
    var key by remember { mutableStateOf(LastFm.key) }
    var secret by remember { mutableStateOf(LastFm.secret) }
    Line("Last.fm", "Scrobble what you play, and see your Wrapped. Last.fm wants an API account of yours: make one (free), " +
        "then paste its key and secret.", false) {}
    Field(key, "API key") { key = it }
    Field(secret, "Shared secret") { secret = it }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        Pill("Make an API account", quiet = true) { Services.open("https://www.last.fm/api/account/create") }
        Pill("Connect") { LastFm.begin(key, secret) { ok -> if (ok) ui.sheet = "lastfm-login" } }
    }
}

/** Your Spotify plays for Wrapped: the zip from Spotify's privacy page, and your last plays
 *  through the connection, picked up when the app starts. */
@Composable
private fun SpotifyHistoryLine(ui: Ui) {
    val h = org.omarchy.music.SpotifyHistory
    val sp = Music.sources["spotify"] as? org.omarchy.music.Spotify
    var asking by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) Music.bg({ h.importFile(Music.ctx, uri) }) { n, err ->
            Music.toast(if (err != null) "Couldn't import it: ${err.message?.take(140)}"
                else "Added ${"%,d".format(n ?: 0)} Spotify plays to your Wrapped" + if (n == 0) " (you had them all)" else "")
        }
    }
    val state = h.busy ?: if (h.count > 0) "${"%,d".format(h.count)} Spotify plays in your Wrapped, each counted once with your scrobbles"
        else "Add your Spotify listening to your Wrapped: import the zip from Spotify's privacy page" +
            if (sp?.signedIn == true) ". Your last plays come in by themselves" else ", or connect Spotify for your last plays"
    Line("History", state, h.count > 0) {}
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        Pill("Get the zip", quiet = true) { Services.open("https://www.spotify.com/account/privacy/") }
        if (sp?.signedIn == true) Pill("Sync", quiet = true) {
            Music.bg({ h.sync() }) { n, err ->
                Music.toast(if (err != null) "Couldn't read your Spotify plays: ${err.message?.take(100)}"
                    else "${"%,d".format(n ?: 0)} new Spotify play${if (n == 1) "" else "s"}")
            }
        }
        if (h.count > 0) Pill("Clear", quiet = true) { asking = true }
        Pill("Import zip") { if (h.busy == null) pick.launch(arrayOf("application/zip", "application/json", "application/octet-stream")) }
    }
    if (h.count > 0 && !LastFm.connected) ActionRow("✦  Your ${org.omarchy.music.Wrapped.year()} Wrapped  ›") { ui.sheet = "wrapped" }
    if (asking) AlertDialog(onDismissRequest = { asking = false }, containerColor = C.light,
        title = { Text("Clear your Spotify history?", style = T.title) },
        text = { Text("Wrapped goes back to your Last.fm scrobbles. Plays from the zip come back if you import it again; " +
            "the last plays picked up from Spotify don't.", style = T.sub) },
        confirmButton = { TextButton({ asking = false; Music.bg({ h.clear() }) }) { Text("Clear", color = C.accent) } },
        dismissButton = { TextButton({ asking = false }) { Text("Cancel", color = C.muted) } })
}

/** The keys in API_KEYS: each one you fill in is kept with the rest of the app's state. */
@Composable
private fun SettingsLine() {
    var open by remember { mutableStateOf(false) }
    val keys = remember { androidx.compose.runtime.mutableStateMapOf<String, String>().apply { putAll(Music.apiKeys) } }
    val have = org.omarchy.music.API_KEYS.filterKeys { Music.apiKeys[it] != null }.values.map { it.first }
    Line("Settings", if (have.isNotEmpty()) "Using your " + have.joinToString(", ")
        else "Keys of yours for Google's pictures, Chartmetric's moods and an AutoEq server: none is needed", have.isNotEmpty()) {
        Pill(if (open) "Close" else "Edit", quiet = true) { open = !open }
    }
    if (open) {
        org.omarchy.music.API_KEYS.forEach { (k, v) ->
            Text(v.second, style = T.sub, modifier = Modifier.padding(top = 4.dp))
            Field(keys[k] ?: "", v.first, password = k == "google_key" || k == "chartmetric") { keys[k] = it }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.End) {
            Pill("Save") { Music.saveApiKeys(keys.toMap()); open = false; Music.toast("Settings saved") }
        }
    }
}

@Composable
private fun FolderPicker() {
    var open by remember { mutableStateOf(false) }
    Pill("Choose") { open = true }
    if (open) FolderBrowser { open = false }
}

@Composable
private fun SyncSwitch(key: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Sync ", style = T.sub)
        Switch(Music.syncSources[key] == true, { Music.setSyncSource(key, it) }, colors = swColors())
    }
}

@Composable
private fun swColors() = SwitchDefaults.colors(checkedThumbColor = C.darker, checkedTrackColor = C.accent,
    uncheckedThumbColor = C.muted, uncheckedTrackColor = C.dark, uncheckedBorderColor = C.line)

@Composable
private fun Line(name: String, state: String, ok: Boolean, actions: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = T.title, modifier = Modifier.width(92.dp))
        Text(state, style = T.sub.copy(color = if (ok) C.accent else C.muted), modifier = Modifier.weight(1f).padding(end = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

@Composable
private fun Divider() = HorizontalDivider(color = C.line, modifier = Modifier.padding(vertical = 6.dp))

@Composable
fun Field(value: String, hint: String, password: Boolean = false, onChange: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 3.dp).border(1.dp, C.line).background(C.fg.copy(alpha = 0.04f))
        .padding(horizontal = 10.dp, vertical = 10.dp)) {
        if (value.isEmpty()) Text(hint, style = T.body.copy(color = C.muted))
        BasicTextField(value, onChange, singleLine = true, textStyle = T.body, cursorBrush = SolidColor(C.accent),
            visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation()
                else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier.fillMaxWidth())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EqSheet(ui: Ui) {
    val eq = Music.eq
    ModalBottomSheet(onDismissRequest = { ui.sheet = null }, containerColor = C.light) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Equalizer", style = T.title.copy(fontSize = 16.sp), modifier = Modifier.weight(1f))
                Switch(eq.on, { Music.setEqOn(it) }, colors = swColors())
            }
            Text("Headphones", style = T.section, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
            ChipRow(Eq.headphones(eq).map { (k, v) -> k to v.first }, eq.headphones) { Music.setEqHeadphones(it) }
            AutoEqFinder()
            Text("Sound", style = T.section, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
            ChipRow(Eq.PRESETS.map { (k, v) -> k to v.first }, eq.preset) { Music.setEqPreset(it) }
            Row(Modifier.fillMaxWidth().height(190.dp).padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Eq.BANDS.forEachIndexed { i, hz ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(Eq.fmtDb(eq.bands[i]), style = T.time.copy(fontSize = 9.sp))
                        Box(Modifier.weight(1f).width(36.dp), contentAlignment = Alignment.Center) {
                            VerticalSlider(eq.bands[i].toFloat()) { Music.setEqBand(i, it.toDouble()) }
                        }
                        Text(if (hz >= 1000) "${hz / 1000}k" else "$hz", style = T.time.copy(fontSize = 9.sp))
                    }
                }
            }
            listOf("bass" to "Bass", "treble" to "Treble").forEach { (k, l) ->
                val g = if (k == "bass") eq.bass else eq.treble
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(l, style = T.sub, modifier = Modifier.width(60.dp))
                    Slider(g.toFloat(), { Music.setEqTone(k, it.toDouble()) }, valueRange = -12f..12f,
                        colors = SliderDefaults.colors(thumbColor = C.accent, activeTrackColor = C.accent, inactiveTrackColor = C.fg.copy(alpha = 0.1f)),
                        modifier = Modifier.weight(1f))
                    Text(Eq.fmtDb(g), style = T.time, modifier = Modifier.width(40.dp).clickable { Music.setEqTone(k, 0.0) })
                }
            }
            Text(if (eq.on) "Pre-gain ${Eq.fmtDb(Eq.preamp(Eq.filters(eq)))} dB keeps the boosts from clipping." else "Off: the music plays untouched.",
                style = T.sub, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun VerticalSlider(value: Float, onChange: (Float) -> Unit) {
    Slider(value, onChange, valueRange = -12f..12f,
        colors = SliderDefaults.colors(thumbColor = C.accent, activeTrackColor = C.accent, inactiveTrackColor = C.fg.copy(alpha = 0.1f)),
        modifier = Modifier.vertical().width(160.dp))
}

/** Stands a horizontal control on its end: laid out turned a quarter, its low end at the bottom. */
private fun Modifier.vertical() = layout { measurable, c ->
    val p = measurable.measure(Constraints(minWidth = 0, maxWidth = c.maxHeight, minHeight = 0, maxHeight = c.maxWidth))
    layout(p.height, p.width) {
        p.placeWithLayer(-(p.width - p.height) / 2, (p.width - p.height) / 2) { rotationZ = -90f }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(items: List<Pair<String, String>>, on: String, pick: (String) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { (k, l) -> Chip(l, k == on) { pick(k) } }
    }
}

@Composable
private fun PlaylistPicker(ui: Ui, tracks: List<org.omarchy.music.Track>) {
    AlertDialog(onDismissRequest = { ui.pickPlaylistFor = null }, containerColor = C.light,
        title = { Text(if (tracks.size == 1) "Add “${tracks[0].title}” to…" else "Add ${tracks.size} songs to…", style = T.title,
            maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            LazyColumn {
                item {
                    Text("＋  New playlist", style = T.body.copy(color = C.accent), modifier = Modifier.fillMaxWidth().clickable {
                        ui.pickPlaylistFor = null
                        ui.askName = Triple("New playlist", "") { n -> Music.addToPlaylist(Music.newPlaylist(n), tracks) }
                    }.padding(vertical = 10.dp))
                }
                val lists = Music.lists.all()
                items(lists.size) { i ->
                    val pl = lists[i]
                    Text("${pl.name}  ·  ${pl.tracks.size}", style = T.body, modifier = Modifier.fillMaxWidth().clickable {
                        ui.pickPlaylistFor = null; Music.addToPlaylist(pl.id, tracks)
                    }.padding(vertical = 10.dp))
                }
            }
        },
        confirmButton = { TextButton({ ui.pickPlaylistFor = null }) { Text("Cancel", color = C.muted) } })
}

/** Long-press on a playlist: which folder to put it in (or a new one). */
@Composable
private fun FolderPicker(ui: Ui, item: org.omarchy.music.FolderItem) {
    AlertDialog(onDismissRequest = { ui.folderFor = null }, containerColor = C.light,
        title = { Text("Put “${item.title}” in a folder", style = T.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            LazyColumn {
                item {
                    Text("＋  New folder", style = T.body.copy(color = C.accent), modifier = Modifier.fillMaxWidth().clickable {
                        ui.folderFor = null
                        ui.askName = Triple("New folder", "") { n -> Music.newFolder(n, item) }
                    }.padding(vertical = 10.dp))
                }
                val folders = Music.playlistFolders.all()
                items(folders.size) { i ->
                    val f = folders[i]
                    Text("${f.name}  ·  ${f.playlists.size}", style = T.body, modifier = Modifier.fillMaxWidth().clickable {
                        ui.folderFor = null; Music.addToFolder(f.id, item)
                    }.padding(vertical = 10.dp))
                }
            }
        },
        confirmButton = { TextButton({ ui.folderFor = null }) { Text("Cancel", color = C.muted) } })
}

/** Auto EQ: find any headphones in AutoEq's results as you type, or Detect the ones connected;
 *  their correction is added to Headphones and used (and comes back whenever they connect). */
@Composable
private fun AutoEqFinder() {
    var q by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<org.omarchy.music.AutoEq.Entry>>(emptyList()) }
    var note by remember { mutableStateOf("") }
    val api = Music.apiKeys["autoeq_api"].orEmpty()
    LaunchedEffect(q) {
        if (q.trim().length < 2) { found = emptyList(); note = ""; return@LaunchedEffect }
        kotlinx.coroutines.delay(300)
        Music.bg({ if (api.isNotEmpty()) org.omarchy.music.AutoEq.apiSearch(api, q) else org.omarchy.music.AutoEq.search(org.omarchy.music.AutoEq.index(Music.ctx), q) }) { r, err ->
            found = r ?: emptyList()
            note = if (err != null) "AutoEq couldn't be reached: ${err.message?.take(80)}" else if (found.isEmpty()) "Nothing in AutoEq by that name" else ""
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
        Box(Modifier.weight(1f)) { Field(q, "Auto EQ: find your headphones") { q = it } }
        Spacer(Modifier.width(6.dp))
        Pill("Detect", quiet = true) {
            val dev = org.omarchy.music.AutoEq.output(Music.ctx)
            if (dev.isEmpty()) { note = "No headphones connected"; return@Pill }
            note = "Looking…"
            Music.bg({ org.omarchy.music.AutoEq.matchDevice(listOf(dev), org.omarchy.music.AutoEq.index(Music.ctx)) }) { m, err ->
                found = listOfNotNull(m)
                note = when {
                    err != null -> "AutoEq couldn't be reached: ${err.message?.take(80)}"
                    m != null -> "$dev is connected: AutoEq has"
                    else -> "$dev is connected: AutoEq has nothing by that name. Look yours up."
                }
            }
        }
    }
    if (note.isNotEmpty()) Text(note, style = T.sub, modifier = Modifier.padding(top = 4.dp))
    found.take(8).forEach { e ->
        Text("${e.name} · ${e.source}", style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().clickable {
                note = "Getting ${e.name}…"
                Music.bg({ org.omarchy.music.AutoEq.profile(e, api) }) { f, err ->
                    if (f != null) { found = emptyList(); note = ""; q = ""; Music.addAutoEq(e, f) }
                    else note = "Couldn't get it: ${err?.message?.take(80)}"
                }
            }.padding(vertical = 8.dp))
    }
}

/** Cover…: covers of the first album left to pick from (Google's, Genius's, Wikipedia's), or a
 *  picture of yours; then the next album. Unreleased songs often have none: skip, or pick a file. */
@Composable
private fun CoverDialog(ui: Ui, albums: List<List<org.omarchy.music.Track>>) {
    val songs = albums[0]
    val t = songs[0]
    var choices by remember(songs) { mutableStateOf<List<org.omarchy.music.Pictures.Choice>?>(null) }
    var busy by remember(songs) { mutableStateOf(false) }
    val next = { ui.coverFor = albums.drop(1).ifEmpty { null } }
    val use = { src: String -> busy = true; Music.setCover(songs, src) { next() } }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) use(uri.toString()) }
    LaunchedEffect(songs) {
        Music.bg({ org.omarchy.music.Pictures.coverChoices(t.artist.split(",")[0].trim(), t.album.ifEmpty { t.title }) }) { c, _ -> choices = c ?: emptyList() }
    }
    AlertDialog(onDismissRequest = { ui.coverFor = null }, containerColor = C.light,
        title = { Text("Cover of “${t.album.ifEmpty { t.title }}”" + if (albums.size > 1) " · ${albums.size} left" else "", style = T.title,
            maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            val c = choices
            Column {
                Text(when {
                    busy -> "Putting it on the songs…"
                    c == null -> "Looking on Google, Genius and Wikipedia…"
                    c.isEmpty() -> "Nothing found: unreleased songs often have none. Pick a picture of yours, or skip it."
                    else -> "${t.artist}${if (t.album.isNotEmpty()) " · goes on every song of the album" else ""}"
                }, style = T.sub, modifier = Modifier.padding(bottom = 8.dp))
                if (!c.isNullOrEmpty() && !busy) LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items((c.size + 2) / 3) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                            for (ch in c.drop(row * 3).take(3)) Column(Modifier.clickable { use(ch.url) }) {
                                ArtImage(ch.thumb, 84.dp)
                                Text(ch.from, style = T.time)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ pick.launch(arrayOf("image/*")) }, enabled = !busy) { Text("Pick a picture…", color = C.accent) } },
        dismissButton = { TextButton(next, enabled = !busy) { Text(if (albums.size > 1) "Skip" else "Cancel", color = C.muted) } })
}

/** Change picture…: pictures of the artist to pick from (Google's, Genius's, Wikipedia's), or one of yours. */
@Composable
private fun ArtistPictureDialog(ui: Ui, name: String) {
    var choices by remember(name) { mutableStateOf<List<org.omarchy.music.Pictures.Choice>?>(null) }
    val close = { ui.artistPictureFor = null }
    val use = { src: String -> close(); Music.setArtistPicture(name, src) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) use(uri.toString()) }
    LaunchedEffect(name) { Music.bg({ org.omarchy.music.Pictures.artistChoices(name) }) { c, _ -> choices = c ?: emptyList() } }
    AlertDialog(onDismissRequest = close, containerColor = C.light,
        title = { Text("A picture of $name", style = T.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            val c = choices
            Column {
                Text(when {
                    c == null -> "Looking on Google, Genius and Wikipedia…"
                    c.isEmpty() -> "Nothing found. Pick a picture of yours."
                    else -> "Kept for their tile and their page"
                }, style = T.sub, modifier = Modifier.padding(bottom = 8.dp))
                if (!c.isNullOrEmpty()) LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items((c.size + 2) / 3) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                            for (ch in c.drop(row * 3).take(3)) Column(Modifier.clickable { use(ch.url) }) {
                                ArtImage(ch.thumb, 84.dp)
                                Text(ch.from, style = T.time)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ pick.launch(arrayOf("image/*")) }) { Text("Pick a picture…", color = C.accent) } },
        dismissButton = { TextButton(close) { Text("Cancel", color = C.muted) } })
}

/** Speed…: how fast the songs play, from half to twice their speed. */
@Composable
private fun SpeedDialog(ui: Ui, tracks: List<org.omarchy.music.Track>) {
    val start = remember(tracks) { Music.speeds.get(tracks[0]) }
    var speed by remember(tracks) { mutableStateOf(start.speed.toFloat()) }
    var pitch by remember(tracks) { mutableStateOf(start.pitch) }
    val close = { ui.speedFor = null }
    val done = { s: Double -> close(); Music.setSpeed(tracks, s, pitch); ui.stopSelecting() }
    AlertDialog(onDismissRequest = close, containerColor = C.light,
        title = { Text(if (tracks.size == 1) "Speed of “${tracks[0].title}”" else "Speed of ${tracks.size} songs", style = T.title,
            maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                Text(org.omarchy.music.fmtSpeed(Math.round(speed * 20) / 20.0), style = T.title.copy(color = C.accent))
                Slider(speed, { speed = Math.round(it * 20) / 20f }, valueRange = 0.5f..2f, steps = 29,
                    colors = SliderDefaults.colors(thumbColor = C.accent, activeTrackColor = C.accent, inactiveTrackColor = C.fg.copy(alpha = 0.1f)))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Pitch moves with it (a sped-up edit)", style = T.sub, modifier = Modifier.weight(1f))
                    Switch(pitch, { pitch = it }, colors = swColors())
                }
            }
        },
        confirmButton = { TextButton({ done(Math.round(speed * 20) / 20.0) }) { Text("Save", color = C.accent) } },
        dismissButton = {
            Row {
                TextButton({ done(1.0) }) { Text("Normal", color = C.fg) }
                TextButton(close) { Text("Cancel", color = C.muted) }
            }
        })
}

/** Delete: are you sure? Downloads go at once; the phone's own songs (other apps' files) only once
 *  Android asks too and you say yes there. */
@Composable
private fun DeleteDialog(ui: Ui) {
    var phone by remember { mutableStateOf<List<org.omarchy.music.Track>>(emptyList()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) Music.phoneFilesDeleted(phone)
        phone = emptyList()
    }
    val tracks = ui.deleteFor ?: return
    val ctx = LocalContext.current
    val downloads = tracks.filter { Music.lib.has(it.id) }
    val files = tracks.filter { Music.phoneFile(it) != null }
    val n = downloads.size + files.size
    val close = { ui.deleteFor = null }
    AlertDialog(onDismissRequest = close, containerColor = C.light,
        title = { Text(if (n == 1) "Delete “${(downloads + files)[0].title}”?" else "Delete $n songs?", style = T.title,
            maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Text(listOfNotNull(
                if (downloads.isNotEmpty()) "${if (n == 1) "Its" else "${downloads.size}"} downloaded FLAC${if (downloads.size > 1) "s" else ""} will be deleted for good (you can download them again)." else null,
                if (files.isNotEmpty()) "${if (n == 1) "Its file" else "${files.size} files"} on the phone will be deleted: Android asks you once more." else null,
                (tracks.size - n).takeIf { it > 0 }?.let { left ->
                    if (left == 1) "1 of the songs you picked has no file here: it stays." else "$left of the songs you picked have no file here: they stay."
                },
            ).joinToString("\n\n"), style = T.body)
        },
        confirmButton = {
            TextButton({
                close(); ui.stopSelecting()
                Music.deleteDownloads(downloads)
                val playing = Music.current()?.id
                val todo = files.filter { it.id != playing }
                if (todo.size < files.size) Music.toast("The playing song is left as it is: skip it first")
                if (todo.isNotEmpty() && android.os.Build.VERSION.SDK_INT >= 30) {
                    phone = todo
                    val req = android.provider.MediaStore.createDeleteRequest(ctx.contentResolver, todo.map { android.net.Uri.parse(Music.phoneFile(it)) })
                    ask.launch(androidx.activity.result.IntentSenderRequest.Builder(req.intentSender).build())
                }
            }, enabled = n > 0) { Text("Delete", color = C.red) }
        },
        dismissButton = { TextButton(close) { Text("Cancel", color = C.muted) } })
}

/** Edit tags…: the downloaded FLACs' tags. With several songs, a field they disagree on is left
 *  as it is in each unless you type in it. */
@Composable
private fun TagsDialog(ui: Ui, tracks: List<org.omarchy.music.Track>) {
    val songs = remember(tracks) { tracks.filter { org.omarchy.music.Tags.editable(it) != null } }
    var have by remember { mutableStateOf<Map<String, String?>?>(null) }
    val typed = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    LaunchedEffect(songs) {
        Music.bg({ org.omarchy.music.Tags.read(Music.ctx, songs.mapNotNull { org.omarchy.music.Tags.editable(it) }) }) { h, _ -> have = h ?: emptyMap() }
    }
    val close = { ui.editTagsFor = null }
    AlertDialog(onDismissRequest = close, containerColor = C.light,
        title = { Text(if (songs.size == 1) "Edit tags" else "Edit tags · ${songs.size} songs", style = T.title) },
        text = {
            val h = have
            if (h == null) Text("Reading the tags…", style = T.sub)
            else LazyColumn {
                if (songs.size < tracks.size) item {
                    Text("Only songs downloaded here can be edited: ${tracks.size - songs.size} of them are left out.", style = T.sub,
                        modifier = Modifier.padding(bottom = 6.dp))
                }
                items(org.omarchy.music.Tags.FIELDS.size) { i ->
                    val (k, label) = org.omarchy.music.Tags.FIELDS[i]
                    Text(label, style = T.sub, modifier = Modifier.padding(top = 4.dp))
                    Field(typed[k] ?: h[k] ?: "", if (h.containsKey(k) && h[k] == null) "Several (left as they are)" else "") { typed[k] = it }
                }
            }
        },
        confirmButton = {
            TextButton({ close(); Music.editTags(songs, typed.toMap()); ui.stopSelecting() }, enabled = have != null) {
                Text("Save", color = C.accent)
            }
        },
        dismissButton = { TextButton(close) { Text("Cancel", color = C.muted) } })
}

@Composable
private fun NameDialog(title: String, start: String, dismiss: () -> Unit, done: (String) -> Unit) {
    var name by remember { mutableStateOf(start) }
    AlertDialog(onDismissRequest = dismiss, containerColor = C.light, title = { Text(title, style = T.title) },
        text = { Field(name, "Name") { name = it } },
        confirmButton = { TextButton({ if (name.isNotBlank()) done(name.trim()) }) { Text("OK", color = C.accent) } },
        dismissButton = { TextButton(dismiss) { Text("Cancel", color = C.muted) } })
}

/** A sign-in page in the app; done(url) once isDone says the sign-in finished, done(null) if closed. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebLogin(url: String, isDone: (String) -> Boolean, desktop: Boolean = false, done: (String?) -> Unit) {
    var finished by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = { done(null) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(C.bg)) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Sign in", style = T.title, modifier = Modifier.weight(1f).padding(start = 8.dp))
                Text("✕", style = T.title, modifier = Modifier.clickable { done(null) }.padding(10.dp))
            }
            AndroidView(factory = { ctx ->
                CookieManager.getInstance().setAcceptCookie(true)
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // Google's sign-in takes the WebView as it is; a borrowed browser name gives it away.
                    if (desktop) settings.userAgentString = DESKTOP_UA
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        fun check(u: String): Boolean {
                            if (!finished && isDone(u)) { finished = true; CookieManager.getInstance().flush(); done(u); return true }
                            return false
                        }
                        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest) = check(req.url.toString())
                        override fun onPageStarted(view: WebView, u: String, favicon: Bitmap?) { check(u) }
                        override fun onPageFinished(view: WebView, u: String) { check(u) }
                    }
                    loadUrl(url)
                }
            }, modifier = Modifier.fillMaxSize())
        }
    }
}
