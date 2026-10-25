package org.omarchy.music

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import org.omarchy.music.ui.FileArtFetcher

/** The process: the music and the services start with it, whether the window or the
 *  playback service came first; covers load through Coil, the phone's own songs' too. */
class MusicApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Music.init(this)
        LastFm.init(this)
        Services.start(this)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(FileArtFetcher.Factory()) }
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.2).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("art")).maxSizeBytes(200L * 1024 * 1024).build() }
        .crossfade(true)
        .build()
}
