package com.farloh.tv.record

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.farloh.tv.data.Account
import com.farloh.tv.data.Recordings
import com.farloh.tv.net.Net
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Saves a live channel to a file until a given time. Uses the channel's .ts stream (appending after every
 * reconnect) and falls back to its HLS (.m3u8) stream when the server doesn't offer .ts.
 */
object Recorder {

    /** Stop before the storage is completely full. */
    private const val MIN_FREE = 300L * 1024 * 1024

    private val client: OkHttpClient by lazy {
        Net.client.newBuilder().readTimeout(20, TimeUnit.SECONDS).callTimeout(0, TimeUnit.MILLISECONDS).build()
    }

    class HttpError(val code: Int) : IOException("server answered HTTP $code")

    class Result(val bytes: Long, val notes: List<String>)

    suspend fun capture(account: Account, channelId: String, out: File, endAt: Long): Result = withContext(Dispatchers.IO) {
        out.parentFile?.mkdirs()
        val notes = ArrayList<String>()
        var hls = false
        var tsFailures = 0
        var drops = 0
        var lastError: String? = null
        while (System.currentTimeMillis() < endAt) {
            ensureActive()
            if (Recordings.freeSpace(out.parentFile ?: out) < MIN_FREE) { notes += "Stopped early: storage nearly full"; break }
            val before = out.length()
            try {
                if (hls) hls(account.liveUrl(channelId, "m3u8"), out, endAt)
                else ts(account.liveUrl(channelId, "ts"), out, endAt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message
                // The server doesn't give this channel as .ts: use its HLS stream instead.
                if (!hls && out.length() == 0L && ++tsFailures >= 2) hls = true
            }
            if (System.currentTimeMillis() >= endAt) break
            if (out.length() > before) drops++ // it was recording and the stream stopped: reconnect
            delay(if (out.length() > before) 1_000 else 4_000)
        }
        if (drops > 0) notes += "Stream dropped $drops× (reconnected)"
        if (out.length() == 0L) notes += "Nothing could be recorded" + (lastError?.let { " ($it)" } ?: "")
        Result(out.length(), notes)
    }

    private suspend fun ts(url: String, out: File, endAt: Long) {
        val call = client.newCall(Request.Builder().url(url).header("User-Agent", Net.USER_AGENT).build())
        val handle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { r ->
                if (!r.isSuccessful) throw HttpError(r.code)
                val input = r.body?.byteStream() ?: throw IOException("empty stream")
                FileOutputStream(out, true).use { os ->
                    val buf = ByteArray(64 * 1024)
                    var sinceCheck = 0L
                    while (System.currentTimeMillis() < endAt) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) return
                        os.write(buf, 0, n)
                        sinceCheck += n
                        if (sinceCheck > 64L * 1024 * 1024) {
                            sinceCheck = 0
                            if (Recordings.freeSpace(out.parentFile ?: out) < MIN_FREE) return
                        }
                    }
                }
            }
        } finally {
            handle.dispose()
        }
    }

    /** Live HLS: keeps reading the playlist and appends each new segment. */
    private suspend fun hls(url: String, out: File, endAt: Long) {
        var playlist = url.toHttpUrl()
        var lastSeq = -1L
        var seen = HashSet<String>()
        while (System.currentTimeMillis() < endAt) {
            currentCoroutineContext().ensureActive()
            val (finalUrl, text) = getText(playlist)
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) {
                // Master playlist: the best quality.
                var best: String? = null; var bestBw = -1L
                for (i in lines.indices) if (lines[i].startsWith("#EXT-X-STREAM-INF")) {
                    val bw = Regex("BANDWIDTH=(\\d+)").find(lines[i])?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val uri = lines.getOrNull(i + 1)?.takeIf { !it.startsWith("#") } ?: continue
                    if (bw > bestBw) { bestBw = bw; best = uri }
                }
                playlist = finalUrl.resolve(best ?: throw IOException("no stream in playlist")) ?: throw IOException("bad playlist")
                continue
            }
            if (lines.any { it.startsWith("#EXT-X-KEY") && !it.contains("METHOD=NONE") }) throw IOException("encrypted stream can't be recorded")
            if (lines.any { it.startsWith("#EXT-X-MAP") }) throw IOException("this stream format can't be recorded")
            val target = lines.firstOrNull { it.startsWith("#EXT-X-TARGETDURATION:") }?.substringAfter(':')?.toDoubleOrNull() ?: 6.0
            var seq = lines.firstOrNull { it.startsWith("#EXT-X-MEDIA-SEQUENCE:") }?.substringAfter(':')?.trim()?.toLongOrNull() ?: 0L
            val segments = ArrayList<Pair<Long, String>>()
            for (l in lines) if (!l.startsWith("#")) { segments += seq to l; seq++ }
            for ((s, uri) in segments) {
                if (System.currentTimeMillis() >= endAt) return
                if (lastSeq >= 0 && s <= lastSeq) continue
                if (uri in seen) continue
                val segUrl = finalUrl.resolve(uri) ?: continue
                val bytes = getBytes(segUrl)
                FileOutputStream(out, true).use { it.write(bytes) }
                lastSeq = s
                seen += uri
                if (seen.size > 200) seen = HashSet(seen.toList().takeLast(100))
            }
            if (lines.any { it.startsWith("#EXT-X-ENDLIST") }) return
            delay((target * 500).toLong().coerceIn(1_000, 6_000))
        }
    }

    private fun getText(url: HttpUrl): Pair<HttpUrl, String> =
        client.newCall(Request.Builder().url(url).header("User-Agent", Net.USER_AGENT).build()).execute().use { r ->
            if (!r.isSuccessful) throw HttpError(r.code)
            r.request.url to (r.body?.string() ?: "")
        }

    private fun getBytes(url: HttpUrl): ByteArray =
        client.newCall(Request.Builder().url(url).header("User-Agent", Net.USER_AGENT).build()).execute().use { r ->
            if (!r.isSuccessful) throw HttpError(r.code)
            r.body?.bytes() ?: ByteArray(0)
        }

    /** Low quality: a 480p copy (H.264 + AAC in .mp4) made after the recording ends. */
    @OptIn(UnstableApi::class)
    suspend fun to480p(ctx: Context, input: File, output: File) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val transformer = Transformer.Builder(ctx)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()
            val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input)))
                .setEffects(Effects(listOf(), listOf(Presentation.createForHeight(480))))
                .build()
            transformer.start(item, output.absolutePath)
            cont.invokeOnCancellation { runCatching { transformer.cancel() } }
        }
    }
}
