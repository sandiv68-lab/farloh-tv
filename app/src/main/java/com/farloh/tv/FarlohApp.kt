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
        // Version 1.0 kept lists directly in files/catalog; they now live in one folder per playlist.
        filesDir.resolve("catalog").listFiles()?.filter { it.isFile }?.forEach { it.delete() }
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient {
                val builder = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                Net.legacyTls?.let { builder.sslSocketFactory(it.socketFactory, it.trustManager) }
                builder.build()
            }
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.15).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("images"))
                    .maxSizeBytes(150L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .crossfade(true)
            .build()
}
