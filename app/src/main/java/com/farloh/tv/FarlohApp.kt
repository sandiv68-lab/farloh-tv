package com.farloh.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.farloh.tv.net.Net
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class FarlohApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // Installs the bundled root certificates on old Android versions (5.x / 6.x)
        // that are missing the current Let's Encrypt / Google / DigiCert roots.
        Net.init(this)
        com.farloh.tv.data.Notify.schedule(this)
        com.farloh.tv.data.Xtream.epgTtlMs = com.farloh.tv.data.Store.get(this).epgMinutes * 60_000L
        // An update was installed (or abandoned): the downloaded APK isn't needed any more.
        cacheDir.resolve("updates").deleteRecursively()
        // Version 1.0 kept lists directly in files/catalog; they now live in one folder per playlist.
        filesDir.resolve("catalog").listFiles()?.filter { it.isFile }?.forEach { it.delete() }
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient { Net.client.newBuilder().readTimeout(20, TimeUnit.SECONDS).build() }
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.15).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("images"))
                    .maxSizeBytes(com.farloh.tv.data.Store.get(this).imageCacheMb.coerceAtLeast(20) * 1024L * 1024L)
                    .build()
            }
            .respectCacheHeaders(false)
            .crossfade(true)
            .build()
}
