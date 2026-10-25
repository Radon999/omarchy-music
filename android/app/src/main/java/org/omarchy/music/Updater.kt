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

/** New releases on GitHub. It looks at start and every 12 hours; Update (in the accounts
 *  menu) downloads the release's APK and hands it to Android's installer, which asks you. */
@SuppressLint("StaticFieldLeak")
object Updater {
    private const val REPO = "Varixxl/omarchy-music"
    private lateinit var ctx: Context
    var latest by mutableStateOf<String?>(null)   // the newer release ("1.2.0"), once there is one
        private set
    var state by mutableStateOf("")               // "", "working", or what went wrong
    private var apk: String? = null

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

    fun check() {
        Music.bg({
            httpJson("https://api.github.com/repos/$REPO/releases/latest",
                mapOf("User-Agent" to "omarchy-music-android/${BuildConfig.VERSION_NAME}", "Accept" to "application/vnd.github+json"))
        }) { rel, _ ->
            val tag = rel["tag_name"].str ?: return@bg
            val url = rel["assets"].list.firstOrNull { it["name"].str == "OmarchyMusic.apk" }?.get("browser_download_url").str
            if (url == null || !newer(tag, BuildConfig.VERSION_NAME)) return@bg
            if (latest != tag.removePrefix("v")) Music.toast("Omarchy Music ${tag.removePrefix("v")} is out: update it in the accounts menu (☰)")
            latest = tag.removePrefix("v"); apk = url
            Music.accountsVersion++
        }
    }

    fun install() {
        val url = apk ?: return
        if (state == "working") return
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            // Android asks once whether this app may install apps; then pick Update again.
            Music.toast("Allow Omarchy Music to install apps, then pick Update again")
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
        }) { _, err -> if (err != null) state = "Couldn't update: ${err.message?.take(100)}" }
    }

    /** Android's installer answers here: it wants you to confirm, or it's done, or it failed. */
    class Answer : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                PackageInstaller.STATUS_SUCCESS -> state = ""
                PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                    state = "This copy is signed differently (built by hand?): uninstall it once, then install the release"
                PackageInstaller.STATUS_FAILURE_ABORTED -> state = ""
                else -> state = "Couldn't update: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "the installer said no"}"
            }
        }
    }
}
