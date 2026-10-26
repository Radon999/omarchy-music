package org.omarchy.music.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.omarchy.music.Music

/** Whether the app may read the music on the phone, and a way to ask. Once Android stops
 *  showing its prompt (asked twice and refused), asking opens the app's settings instead;
 *  coming back from there is noticed. */
class MusicAccess(val granted: Boolean, val ask: () -> Unit)

private val AUDIO = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

private fun Context.activity(): Activity? = this as? Activity ?: (this as? ContextWrapper)?.baseContext?.activity()

@Composable
fun rememberMusicAccess(): MusicAccess {
    val ctx = LocalContext.current
    fun has() = ContextCompat.checkSelfPermission(ctx, AUDIO) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(has()) }
    var refused by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        refused = !ok
        if (ok) Music.rescan()
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val watch = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME && !granted && has()) { granted = true; Music.rescan() }
        }
        owner.lifecycle.addObserver(watch)
        onDispose { owner.lifecycle.removeObserver(watch) }
    }
    return MusicAccess(granted) {
        val act = ctx.activity()
        if (refused && act != null && !act.shouldShowRequestPermissionRationale(AUDIO)) {
            Music.toast("Turn on Music and audio under Permissions")
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
        } else launcher.launch(AUDIO)
    }
}

private class Folder(val path: String, val songs: Int, val hasSub: Boolean)

/** The folders right inside at, each with every song under it. */
private fun children(counts: Map<String, Int>, at: String): List<Folder> {
    val out = sortedMapOf<String, IntArray>(String.CASE_INSENSITIVE_ORDER)   // path → songs, has subfolders
    for ((p, n) in counts) {
        if (!p.startsWith(at) || p == at) continue
        val child = at + p.substring(at.length).substringBefore('/') + "/"
        val e = out.getOrPut(child) { IntArray(2) }
        e[0] += n
        if (p != child) e[1] = 1
    }
    return out.map { (k, v) -> Folder(k, v[0], v[1] == 1) }
}

private fun parent(path: String) = path.trimEnd('/').substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }

private fun songs(n: Int) = "$n song${if (n != 1) "s" else ""}"

/** Pick the folders whose songs show in the Music tab: walk into a folder with ›, tick the
 *  ones you want. Ticking a folder takes everything inside it. */
@Composable
fun FolderBrowser(onClose: () -> Unit) {
    val access = rememberMusicAccess()
    var counts by remember { mutableStateOf<Map<String, Int>?>(null) }
    var at by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf(Music.folders.toSet()) }
    LaunchedEffect(access.granted) {
        if (access.granted) Music.bg({ Music.local.folderCounts() }) { res, _ -> counts = res ?: emptyMap() }
    }
    val kids = remember(counts, at) { counts?.let { children(it, at) } ?: emptyList() }
    AlertDialog(onDismissRequest = onClose, containerColor = C.light,
        title = { Text("Choose folders", style = T.title) },
        text = {
            Column {
                when {
                    !access.granted -> {
                        Text("VarMusic needs permission to see the music on this phone.", style = T.sub,
                            modifier = Modifier.padding(bottom = 10.dp))
                        Pill("Allow access") { access.ask() }
                    }
                    counts == null -> Text("Reading your music…", style = T.sub)
                    else -> {
                        Text(if (at.isEmpty()) "This phone" else at, style = T.sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (at.isNotEmpty()) BackRow("Up") { at = parent(at) }
                        LazyColumn(Modifier.heightIn(max = 440.dp)) {
                            if (kids.isEmpty()) item { Text("No folders with music in here.", style = T.sub, modifier = Modifier.padding(vertical = 10.dp)) }
                            items(kids.size, key = { kids[it].path }) { i ->
                                val f = kids[i]
                                val outer = chosen.firstOrNull { f.path != it && f.path.startsWith(it) }
                                val state = when {
                                    outer != null || f.path in chosen -> ToggleableState.On
                                    chosen.any { it.startsWith(f.path) } -> ToggleableState.Indeterminate
                                    else -> ToggleableState.Off
                                }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    TriStateCheckbox(state, enabled = outer == null, onClick = {
                                        chosen = if (state == ToggleableState.On) chosen - f.path
                                                 else chosen.filterNot { it.startsWith(f.path) }.toSet() + f.path
                                    }, colors = CheckboxDefaults.colors(checkedColor = C.accent, uncheckedColor = C.muted,
                                        checkmarkColor = C.darker, disabledCheckedColor = C.accent.copy(alpha = 0.45f)))
                                    Column(Modifier.weight(1f).clickable(enabled = f.hasSub) { at = f.path }.padding(vertical = 8.dp)) {
                                        Text(f.path.removePrefix(at).trimEnd('/'), style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(songs(f.songs) + if (outer != null) " · in $outer" else "", style = T.sub, maxLines = 1,
                                            overflow = TextOverflow.Ellipsis)
                                    }
                                    if (f.hasSub) Text("›", style = T.title.copy(color = C.muted),
                                        modifier = Modifier.clickable { at = f.path }.padding(horizontal = 12.dp, vertical = 8.dp))
                                }
                            }
                        }
                        val total = counts!!.entries.sumOf { (p, n) -> if (chosen.any { p.startsWith(it) }) n else 0 }
                        Text("${songs(total)} in ${chosen.size} folder${if (chosen.size != 1) "s" else ""}", style = T.sub.copy(color = C.accent),
                            modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton({ Music.setFolders(chosen.toList()); onClose() }, enabled = access.granted && counts != null) {
                Text("Done", color = C.accent)
            }
        },
        dismissButton = { TextButton(onClose) { Text("Cancel", color = C.muted) } })
}
