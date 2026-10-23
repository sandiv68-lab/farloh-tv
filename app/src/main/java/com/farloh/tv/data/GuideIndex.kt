package com.farloh.tv.data

import android.content.Context
import android.util.Xml
import com.farloh.tv.net.HttpError
import com.farloh.tv.net.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream
import kotlin.coroutines.coroutineContext

/**
 * Searchable copy of the playlist's full TV guide (XMLTV), for "TV Guide" search.
 * Downloaded only when the user asks. Only programmes from 2 hours ago to 36 hours ahead are kept,
 * as compact lines: start, end, channel id, title.
 */
object GuideIndex {

    class Programme(val start: Long, val end: Long, val channelId: String, val title: String) {
        fun isNow(now: Long = System.currentTimeMillis()) = now in start until end
    }

    /** [pid] null = the playlist in use. */
    private fun file(ctx: Context, pid: String? = null): File {
        val id = Store.get(ctx).playlist(pid)?.id ?: "none"
        return File(ctx.filesDir, "catalog/$id/guide_index.tsv")
    }

    fun lastUpdated(ctx: Context, pid: String? = null): Long = file(ctx, pid).let { if (it.exists()) it.lastModified() else 0L }

    fun exists(ctx: Context, pid: String? = null) = file(ctx, pid).let { it.exists() && it.length() > 0 }

    /** Downloads and indexes the TV guide. [progress] gets megabytes read so far. */
    suspend fun download(ctx: Context, pid: String? = null, progress: (Int) -> Unit): Int = withContext(Dispatchers.IO) {
        val account = Store.get(ctx).accountFor(pid) ?: throw IOException("Not signed in")
        // Only keep channels that are in this playlist (keeps the index small).
        val wanted = runCatching {
            Catalog.load(ctx, Section.LIVE, pid).items.mapNotNull { (it as? LiveChannel)?.epgId?.lowercase()?.takeIf { id -> id.isNotBlank() } }.toHashSet()
        }.getOrDefault(HashSet())

        val client = Net.client.newBuilder().readTimeout(120, java.util.concurrent.TimeUnit.SECONDS).build()
        val out = File(file(ctx, pid).path + ".part").apply { parentFile?.mkdirs() }
        var count = 0
        client.newCall(Request.Builder().url(account.xmltvUrl()).build()).execute().use { res ->
            if (!res.isSuccessful) throw HttpError(res.code)
            val body = res.body ?: throw IOException("Empty TV guide")
            val counting = CountingStream(body.byteStream()) { bytes -> progress((bytes / (1024 * 1024)).toInt()) }
            val buffered = BufferedInputStream(counting, 64 * 1024)
            buffered.mark(4)
            val gz = buffered.read() == 0x1f && buffered.read() == 0x8b
            buffered.reset()
            val input: InputStream = if (gz) GZIPInputStream(buffered, 64 * 1024) else buffered

            val now = System.currentTimeMillis()
            val from = now - 2 * 3600_000L
            val to = now + 36 * 3600_000L
            out.bufferedWriter().use { w ->
                val p = Xml.newPullParser()
                p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                p.setInput(input, null)
                var start = 0L; var stop = 0L; var channel = ""; var inProgramme = false; var title: String? = null
                var event = p.eventType
                var n = 0
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (++n % 5000 == 0) coroutineContext.ensureActive()
                    when (event) {
                        XmlPullParser.START_TAG -> when (p.name) {
                            "programme" -> {
                                inProgramme = true; title = null
                                start = parseTime(p.getAttributeValue(null, "start"))
                                stop = parseTime(p.getAttributeValue(null, "stop"))
                                channel = p.getAttributeValue(null, "channel").orEmpty()
                            }
                            "title" -> if (inProgramme && title == null) title = p.nextText()
                        }
                        XmlPullParser.END_TAG -> if (p.name == "programme") {
                            inProgramme = false
                            val t = title?.replace('\t', ' ')?.replace('\n', ' ')?.trim()
                            if (!t.isNullOrEmpty() && stop > from && start < to &&
                                (wanted.isEmpty() || channel.lowercase() in wanted)
                            ) {
                                w.write("$start\t$stop\t${channel.replace('\t', ' ')}\t$t\n")
                                count++
                            }
                        }
                    }
                    event = p.next()
                }
            }
        }
        val dest = file(ctx, pid)
        if (dest.exists()) dest.delete()
        if (!out.renameTo(dest)) throw IOException("Couldn't save the TV guide")
        count
    }

    /** Programmes whose title contains every word of [query]; on now first, then by start time. Ended ones are left out. */
    suspend fun search(ctx: Context, query: String, limit: Int, pid: String? = null): List<Programme> = withContext(Dispatchers.IO) {
        val f = file(ctx, pid)
        if (!f.exists()) return@withContext emptyList()
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return@withContext emptyList()
        val now = System.currentTimeMillis()
        val out = ArrayList<Programme>()
        f.bufferedReader().useLines { lines ->
            for (line in lines) {
                val parts = line.split('\t', limit = 4)
                if (parts.size < 4) continue
                val title = parts[3]
                val lower = title.lowercase()
                if (!words.all { lower.contains(it) }) continue
                val end = parts[1].toLongOrNull() ?: continue
                if (end <= now) continue
                out.add(Programme(parts[0].toLongOrNull() ?: 0L, end, parts[2], title))
                if (out.size >= limit * 3) break
            }
        }
        out.sortedWith(compareBy<Programme>({ !it.isNow(now) }, { it.start })).take(limit)
    }

    fun delete(ctx: Context, pid: String? = null) = file(ctx, pid).delete()

    /** XMLTV times look like "20261023180000 +0800" (offset sometimes missing → UTC). */
    private fun parseTime(s: String?): Long {
        if (s.isNullOrBlank()) return 0L
        val t = s.trim()
        return try {
            if (t.length >= 19 && (t[15] == '+' || t[15] == '-')) {
                SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).parse(t.substring(0, 20))?.time ?: 0L
            } else {
                SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .parse(t.take(14))?.time ?: 0L
            }
        } catch (e: Exception) { 0L }
    }

    private class CountingStream(inner: InputStream, val onMb: (Long) -> Unit) : FilterInputStream(inner) {
        private var total = 0L
        private var lastMb = -1L
        private fun add(n: Int) {
            if (n <= 0) return
            total += n
            val mb = total / (1024 * 1024)
            if (mb != lastMb) { lastMb = mb; onMb(total) }
        }
        override fun read(): Int = super.read().also { if (it >= 0) add(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { add(it) }
    }
}
