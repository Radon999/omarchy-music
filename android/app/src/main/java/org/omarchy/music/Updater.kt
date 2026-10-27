package org.omarchy.music

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File

/** New releases on GitHub. It looks at start, every 12 hours and when you open the accounts
 *  menu (Check looks now); Update downloads the release's APK and hands it to Android's
 *  installer, which asks you. Android shows that question only while the app is on screen,
 *  so one that came while you were away is kept, and asked when you come back (or Install). */
@SuppressLint("StaticFieldLeak")
object Updater {
    private const val REPO = "Radon999/omarchy-music"   // updates come from this one's releases
    private lateinit var ctx: Context
    var latest by mutableStateOf<String?>(null)   // the newer release ("1.2.0"), once there is one
        private set
    var state by mutableStateOf("")               // "", "working", "confirm" (Android's question is waiting), or what went wrong
    var checking by mutableStateOf(false)
        private set
    var checkError by mutableStateOf("")
        private set
    private var checkedAt = 0.0
    private var apk: String? = null
    private var confirm: Intent? = null           // Android's "install this update?", not shown yet
    private const val TAG = "omarchy-music"

    /** The version installed, as Android has it. Not BuildConfig.VERSION_NAME: Kotlin copies that
     *  into the code that reads it, and a build that doesn't recompile that code keeps the old one
     *  (1.1.1 went out calling itself 1.1.0, so it offered itself as an update forever). */
    val version: String by lazy { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "0" }

    fun init(c: Context) {
        if (::ctx.isInitialized) return
        ctx = c.applicationContext
        Music.scope.launch { while (true) { delay(10_000); check(); delay(12 * 3600_000L - 10_000) } }
    }

    private fun parts(v: String) = Regex("\\d+").findAll(v).take(3).map { it.value.toInt() }.toList() + listOf(0, 0, 0)
    private fun newer(a: String, b: String): Boolean {
        val x = parts(a); val y = parts(b)
        for (i in 0 until 3) if (x[i] != y[i]) return x[i] > y[i]
        return false
    }

    /** Look for a newer release (quiet: no message when there's none, as at start). */
    fun check(quiet: Boolean = true) {
        if (checking) return
        checking = true
        Music.bg({
            httpJson("https://api.github.com/repos/$REPO/releases/latest",
                mapOf("User-Agent" to "omarchy-music-android/$version", "Accept" to "application/vnd.github+json"))
        }) { rel, err ->
            checking = false
            checkedAt = now()
            checkError = err?.let { "couldn't check: ${it.message?.take(60)}" } ?: ""
            Music.accountsVersion++
            val tag = rel["tag_name"].str ?: return@bg
            if (!newer(tag, version)) { if (!quiet) Music.toast("VarMusic $version is the newest"); return@bg }
            val url = rel["assets"].list.firstOrNull { it["name"].str == "OmarchyMusic.apk" }?.get("browser_download_url").str
            if (url == null) {   // the release is still being put up: its APK comes a few minutes after
                if (!quiet) Music.toast("VarMusic ${tag.removePrefix("v")} is on its way: its Android app isn't up yet")
                Music.scope.launch { delay(5 * 60_000L); check() }
                return@bg
            }
            if (latest != tag.removePrefix("v")) Music.toast("VarMusic ${tag.removePrefix("v")} is out: update it in Settings (⚙)")
            latest = tag.removePrefix("v"); apk = url
            Music.accountsVersion++
        }
    }

    /** The Settings opened: look again if the last look is over ten minutes old. */
    fun checkSoon() { if (now() - checkedAt > 600) check() }

    /** The app is on screen again: ask Android's question that came while it wasn't. */
    fun resumed(activity: android.app.Activity) {
        val c = confirm ?: return
        confirm = null
        runCatching { activity.startActivity(c) }.onFailure { confirm = c; android.util.Log.w(TAG, "update: can't ask", it) }
    }

    /** Install, for an update whose question is waiting. */
    fun askAgain(activity: android.app.Activity) {
        if (confirm == null) { state = ""; install(); return }
        resumed(activity)
    }

    fun install() {
        val url = apk ?: return
        if (state == "working") return
        android.util.Log.i(TAG, "update: installing $url over $version")
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            // Android asks once whether this app may install apps; then pick Update again.
            Music.toast("Allow VarMusic to install apps, then pick Update again")
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        state = "working"
        Music.bg({
            val file = File(ctx.cacheDir, "update.apk")
            HTTP.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) throw HttpError(r.code, "", url)
                file.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
            }
            val installer = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                .apply { setAppPackageName(ctx.packageName) }
            val id = installer.createSession(params)
            installer.openSession(id).use { s ->
                s.openWrite("omarchy-music.apk", 0, file.length()).use { out -> file.inputStream().use { it.copyTo(out) }; s.fsync(out) }
                val done = PendingIntent.getBroadcast(ctx, id, Intent(ctx, Answer::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                s.commit(done.intentSender)
            }
            file.delete()
        }) { _, err ->
            if (err != null) { android.util.Log.w(TAG, "update failed", err); state = "Couldn't update: ${err.message?.take(100)}" }
        }
    }

    /** Android's installer answers here: it wants you to confirm, or it's done, or it failed. */
    class Answer : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            android.util.Log.i(TAG, "update: installer says $status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""}")
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val ask = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
                    // Kept until it's answered: Android drops it when the app isn't on screen.
                    confirm = ask
                    state = "confirm"
                    if (MainActivity.visible) runCatching { context.startActivity(ask); confirm = null }
                        .onFailure { android.util.Log.w(TAG, "update: can't ask now", it) }
                }
                PackageInstaller.STATUS_SUCCESS -> state = ""
                PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                    state = "This copy is signed differently (built by hand?): uninstall it once, then install the release"
                PackageInstaller.STATUS_FAILURE_ABORTED -> { state = ""; confirm = null }
                else -> state = "Couldn't update: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "the installer said no"}"
            }
        }
    }
}
