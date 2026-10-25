package org.omarchy.music

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager

/** Signing in to the services you browse and connecting them at start. YouTube Music and
 *  Apple Music sign in on their own web pages inside the app (the desktop reads Chromium's
 *  cookies instead); the cookies those pages leave are the login. */
@SuppressLint("StaticFieldLeak")
object Services {
    private lateinit var ctx: Context
    private var started = false

    private const val YTM = "https://music.youtube.com"
    private const val APPLE = "https://music.apple.com"

    private fun cookies(url: String): String? = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()

    /** Connect every service, each as soon as it answers; then the phone remote. */
    fun start(c: Context) {
        if (started) return
        started = true
        ctx = c.applicationContext
        connect("ytm")
        connect("apple")
        connect("spotify")
        Remote.init(ctx)
    }

    private fun connect(key: String) {
        Music.bg({
            when (key) {
                "ytm" -> YouTubeMusic(cookies(YTM)?.takeIf { "__Secure-3PAPISID=" in it })
                "apple" -> AppleMusic(ctx, cookies(APPLE)?.takeIf { "media-user-token=" in it })
                else -> Spotify(ctx)
            } as Source
        }) { src, err -> Music.sourceReady(key, src, err) }
    }

    fun ytmCookiesReady() = cookies(YTM)?.contains("__Secure-3PAPISID=") == true
    fun appleCookiesReady() = cookies(APPLE)?.contains("media-user-token=") == true

    fun ytmSignedIn() { CookieManager.getInstance().flush(); connect("ytm"); Music.toast("Signed in to YouTube Music") }
    fun appleSignedIn() { CookieManager.getInstance().flush(); connect("apple"); Music.toast("Signed in to Apple Music") }

    /** Forget a service's login: its cookies go, and it goes back to search only. */
    fun signOut(key: String) {
        val urls = if (key == "ytm") listOf(YTM, "https://www.youtube.com", "https://accounts.google.com", "https://www.google.com")
            else listOf(APPLE, "https://www.apple.com", "https://idmsa.apple.com")
        val cm = CookieManager.getInstance()
        for (url in urls) {
            val host = Uri.parse(url).host ?: continue
            val parent = "." + host.split(".").takeLast(2).joinToString(".")
            cookies(url)?.split(";")?.map { it.substringBefore("=").trim() }?.filter { it.isNotEmpty() }?.forEach { name ->
                for (domain in listOf(host, parent)) {
                    cm.setCookie(url, "$name=; Max-Age=0; Path=/; Domain=$domain")
                    cm.setCookie(url, "$name=; Max-Age=0; Path=/; Domain=$domain; Secure")
                }
                cm.setCookie(url, "$name=; Max-Age=0; Path=/")
            }
        }
        cm.flush()
        connect(key)
        Music.toast("Signed out of ${SOURCES[key]}")
    }

    // ---- Spotify ----
    private val spotify get() = Music.sources["spotify"] as? Spotify

    fun spotifyLoginUrl(): String = spotify?.loginUrl() ?: "about:blank"

    fun spotifySetClient(id: String): Boolean {
        val sp = spotify ?: return false.also { Music.toast("Spotify isn't ready yet") }
        if (!sp.setClient(id)) { Music.toast("That isn't a Spotify Client ID: 32 letters and digits"); return false }
        return true
    }

    fun spotifyFinish(redirected: String) {
        val sp = spotify ?: return
        Music.bg({ sp.finishLogin(redirected) }) { _, err ->
            if (err != null) { Music.toast("Spotify didn't connect: ${err.message?.take(80)}"); return@bg }
            Music.toast("Spotify connected" + if (sp.user.isNotEmpty()) " as ${sp.user}" else "")
            Music.sourceReady("spotify", sp, null)
        }
    }

    fun spotifyDisconnect() {
        val sp = spotify ?: return
        sp.disconnect()
        Music.sourceReady("spotify", sp, null)
    }

    /** A page in the phone's browser. */
    fun open(url: String) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
