package org.omarchy.music.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Size
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.ImageRequest
import coil.request.Options
import coil.request.SuccessResult
import org.omarchy.music.Music
import org.omarchy.music.Track
import org.omarchy.music.sizedThumb
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

/** The cover inside a song on the phone (or Android's thumbnail of it). */
data class FileArt(val uri: String)

class FileArtFetcher(private val art: FileArt, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val ctx = options.context
        val bmp = embedded(ctx, art.uri) ?: runCatching {
            ctx.contentResolver.loadThumbnail(Uri.parse(art.uri), Size(512, 512), null)
        }.getOrNull() ?: return null
        return DrawableResult(android.graphics.drawable.BitmapDrawable(ctx.resources, bmp), false, DataSource.DISK)
    }

    class Factory : Fetcher.Factory<FileArt> {
        override fun create(data: FileArt, options: Options, imageLoader: ImageLoader) = FileArtFetcher(data, options)
    }

    companion object {
        fun embedded(ctx: Context, uri: String): Bitmap? = runCatching {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(ctx, Uri.parse(uri))
                r.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }
        }.getOrNull()
    }
}

/** What to draw for a song's art: the saved cover, the service's picture, or the file's own. */
fun artModel(t: Track, px: Int = 226): Any? {
    Music.lib.items[t.id]?.art?.takeIf { it.isNotEmpty() && File(it).exists() }?.let { return File(it) }
    if (t.thumb.isNotEmpty()) return sizedThumb(t.thumb, px)
    return t.file?.let { FileArt(it) }
}

object Tint {
    /** The colour a cover mostly is: its most common hue, vivid pixels counting more than grey ones. */
    fun artColor(bmp: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(bmp.copy(Bitmap.Config.ARGB_8888, false), 32, 32, true)
        val buckets = HashMap<Int, DoubleArray>()
        val hsv = FloatArray(3)
        for (y in 0 until 32) for (x in 0 until 32) {
            val p = small.getPixel(x, y)
            val r = android.graphics.Color.red(p); val g = android.graphics.Color.green(p); val b = android.graphics.Color.blue(p)
            android.graphics.Color.RGBToHSV(r, g, b, hsv)
            val h = hsv[0] / 360f; val s = hsv[1]; val v = hsv[2]
            if (v < 0.1) continue   // black borders and shadows say nothing about the cover
            val w = 0.2 + s * v
            val key = (((h * 12).toInt() % 12) * 100) + (min(2, (s * 3).toInt()) * 10) + min(2, (v * 3).toInt())
            val acc = buckets.getOrPut(key) { DoubleArray(4) }
            acc[0] += r * w; acc[1] += g * w; acc[2] += b * w; acc[3] += w
        }
        val best = buckets.values.maxByOrNull { it[3] } ?: return android.graphics.Color.rgb(24, 24, 24)
        return android.graphics.Color.rgb((best[0] / best[3]).roundToInt(), (best[1] / best[3]).roundToInt(), (best[2] / best[3]).roundToInt())
    }

    /** The window and the player bar in the cover's colour, dark enough for the text. */
    fun shades(rgb: Int): Pair<Color, Color> {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(rgb, hsl)
        hsl[1] = min(hsl[1], 0.6f)
        fun shade(l: Float) = Color(ColorUtils.HSLToColor(floatArrayOf(hsl[0], hsl[1], l)))
        return shade(0.15f) to shade(0.09f)
    }

    suspend fun of(ctx: Context, loader: ImageLoader, model: Any?): Pair<Color, Color>? {
        model ?: return null
        val res = loader.execute(ImageRequest.Builder(ctx).data(model).allowHardware(false).size(64).build())
        val bmp = ((res as? SuccessResult)?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return null
        return shades(artColor(bmp))
    }
}
