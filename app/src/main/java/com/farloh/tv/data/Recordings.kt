package com.farloh.tv.data

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.UUID

/**
 * A recording that's set up: one channel, a start and end time, and optionally repeated every day or on
 * chosen days of the week. [days] uses Calendar.SUNDAY…SATURDAY; empty = once.
 */
data class RecSchedule(
    val id: String = UUID.randomUUID().toString(),
    val channelId: String,
    val channelName: String,
    val logo: String?,
    val playlistId: String?,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val days: Set<Int> = emptySet(),
    /** "default" = as broadcast, "low" = 480p. */
    val quality: String = "default",
    /** Recorded straight away from the player ("Record now"): exactly from start to end, no extra time. */
    val now: Boolean = false,
) {
    val repeats get() = days.isNotEmpty()
    val durationMs get() = (endMs - startMs).coerceAtLeast(60_000L)
}

/** A recording that has been made (or is being made). */
data class RecFile(
    val id: String = UUID.randomUUID().toString(),
    val scheduleId: String,
    /** The programme start this recording is for (to avoid recording the same one twice). */
    val occurrence: Long,
    val channelId: String,
    val channelName: String,
    val logo: String?,
    val playlistId: String?,
    val title: String,
    val start: Long,
    val end: Long,
    val path: String,
    /** "recording", "converting", "done", "failed" or "skipped" (a repeat the user skipped; not shown). */
    val state: String,
    val notes: String = "",
    val quality: String = "default",
)

object Recordings {

    private fun schedulesFile(ctx: Context) = File(ctx.filesDir, "rec_schedules.json")
    private fun filesFile(ctx: Context) = File(ctx.filesDir, "rec_files.json")

    // ---------------- Schedules ----------------

    @Synchronized
    fun schedules(ctx: Context): List<RecSchedule> = read(schedulesFile(ctx)) { o ->
        RecSchedule(
            id = o.getString("id"), channelId = o.getString("channelId"), channelName = o.optString("channelName"),
            logo = o.optString("logo").ifBlank { null }, playlistId = o.optString("playlistId").ifBlank { null },
            title = o.optString("title"), startMs = o.getLong("startMs"), endMs = o.getLong("endMs"),
            days = o.optJSONArray("days")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() } ?: emptySet(),
            quality = o.optString("quality", "default"), now = o.optBoolean("now"),
        )
    }

    @Synchronized
    fun saveSchedule(ctx: Context, s: RecSchedule) {
        val list = schedules(ctx).filter { it.id != s.id } + s
        writeSchedules(ctx, list)
    }

    @Synchronized
    fun removeSchedule(ctx: Context, id: String) = writeSchedules(ctx, schedules(ctx).filter { it.id != id })

    private fun writeSchedules(ctx: Context, list: List<RecSchedule>) {
        val a = JSONArray()
        for (s in list) a.put(JSONObject().put("id", s.id).put("channelId", s.channelId).put("channelName", s.channelName)
            .put("logo", s.logo ?: "").put("playlistId", s.playlistId ?: "").put("title", s.title)
            .put("startMs", s.startMs).put("endMs", s.endMs).put("days", JSONArray(s.days.sorted()))
            .put("quality", s.quality).put("now", s.now))
        schedulesFile(ctx).writeText(a.toString())
    }

    /**
     * The next time [s] is on whose end (plus the extra time) hasn't passed yet: the programme start,
     * without the extra time. null = it won't be on again.
     */
    fun nextOccurrence(ctx: Context, s: RecSchedule, now: Long = System.currentTimeMillis()): Long? {
        val after = if (s.now) 0L else Store.get(ctx).recPadAfterMin * 60_000L
        if (!s.repeats) return s.startMs.takeIf { s.endMs + after > now }
        val c = Calendar.getInstance().apply { timeInMillis = s.startMs }
        val hour = c.get(Calendar.HOUR_OF_DAY); val minute = c.get(Calendar.MINUTE)
        // From yesterday (a programme may be on right now, having started before midnight) for 8 days.
        val day = Calendar.getInstance().apply {
            timeInMillis = maxOf(now, s.startMs); add(Calendar.DAY_OF_YEAR, -1)
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        repeat(9) {
            val t = day.timeInMillis
            if (day.get(Calendar.DAY_OF_WEEK) in s.days && t >= s.startMs - 60_000 && t + s.durationMs + after > now) return t
            day.add(Calendar.DAY_OF_YEAR, 1)
        }
        return null
    }

    /** When recording should actually start / stop for the programme starting at [occ] (with the extra time). */
    fun window(ctx: Context, s: RecSchedule, occ: Long): Pair<Long, Long> {
        if (s.now) return occ to occ + s.durationMs // "Record now": exactly the time chosen
        val st = Store.get(ctx)
        return (occ - st.recPadBeforeMin * 60_000L) to (occ + s.durationMs + st.recPadAfterMin * 60_000L)
    }

    /** Schedules whose time has gone for good are removed. */
    @Synchronized
    fun dropExpired(ctx: Context) {
        val list = schedules(ctx)
        val keep = list.filter { nextOccurrence(ctx, it) != null }
        if (keep.size != list.size) writeSchedules(ctx, keep)
        // "Skip the next one" markers that are long past.
        val files = files(ctx)
        val day = System.currentTimeMillis() - 86_400_000L
        if (files.any { it.state == "skipped" && it.end < day }) writeFiles(ctx, files.filterNot { it.state == "skipped" && it.end < day })
    }

    // ---------------- Recorded files ----------------

    @Synchronized
    fun files(ctx: Context): List<RecFile> = read(filesFile(ctx)) { o ->
        RecFile(
            id = o.getString("id"), scheduleId = o.optString("scheduleId"), occurrence = o.optLong("occurrence"),
            channelId = o.optString("channelId"), channelName = o.optString("channelName"),
            logo = o.optString("logo").ifBlank { null }, playlistId = o.optString("playlistId").ifBlank { null },
            title = o.optString("title"), start = o.optLong("start"), end = o.optLong("end"), path = o.optString("path"),
            state = o.optString("state", "done"), notes = o.optString("notes"), quality = o.optString("quality", "default"),
        )
    }

    @Synchronized
    fun saveFile(ctx: Context, f: RecFile) = writeFiles(ctx, files(ctx).filter { it.id != f.id } + f)

    @Synchronized
    fun deleteFile(ctx: Context, f: RecFile) {
        File(f.path).delete()
        writeFiles(ctx, files(ctx).filter { it.id != f.id })
    }

    private fun writeFiles(ctx: Context, list: List<RecFile>) {
        val a = JSONArray()
        for (r in list) a.put(JSONObject().put("id", r.id).put("scheduleId", r.scheduleId).put("occurrence", r.occurrence)
            .put("channelId", r.channelId).put("channelName", r.channelName).put("logo", r.logo ?: "")
            .put("playlistId", r.playlistId ?: "").put("title", r.title).put("start", r.start).put("end", r.end)
            .put("path", r.path).put("state", r.state).put("notes", r.notes).put("quality", r.quality))
        filesFile(ctx).writeText(a.toString())
    }

    /**
     * After the app was closed or the device restarted during a recording, it isn't running any more:
     * mark it so (the part saved so far is kept and can be played).
     */
    @Synchronized
    fun markInterrupted(ctx: Context, running: Set<String>) {
        for (f in files(ctx)) {
            if ((f.state == "recording" || f.state == "converting") && f.id !in running) {
                val ok = File(f.path).let { it.exists() && it.length() > 0 }
                saveFile(ctx, f.copy(state = if (ok) "done" else "failed",
                    notes = listOf(f.notes, "Stopped early (the app or device was closed)").filter { it.isNotBlank() }.joinToString(" • ")))
            }
        }
    }

    // ---------------- Where they're saved ----------------

    /** Box storage (default) and any USB drive / SD card the app can write to. */
    fun locations(ctx: Context): List<Pair<String, File>> {
        val dirs = ctx.getExternalFilesDirs(Environment.DIRECTORY_MOVIES).filterNotNull()
        return dirs.mapIndexed { i, d ->
            val label = if (i == 0) "Device storage" else {
                val desc = if (Build.VERSION.SDK_INT >= 24) runCatching {
                    (ctx.getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager)
                        .getStorageVolume(d)?.getDescription(ctx)
                }.getOrNull() else null
                desc ?: "USB drive / SD card"
            }
            label to d.resolve("farloh/recordings")
        }
    }

    fun folder(ctx: Context): File? {
        val want = Store.get(ctx).recFolder
        val dir = locations(ctx).map { it.second }.firstOrNull { want.isNotBlank() && it.path == want }
            ?: locations(ctx).firstOrNull()?.second
        return dir?.apply { mkdirs() }
    }

    fun freeSpace(dir: File): Long = try { StatFs(dir.path).availableBytes } catch (e: Exception) { Long.MAX_VALUE }

    private fun <T> read(f: File, parse: (JSONObject) -> T): List<T> {
        if (!f.exists()) return emptyList()
        return try {
            val a = JSONArray(f.readText())
            (0 until a.length()).mapNotNull { i -> runCatching { parse(a.getJSONObject(i)) }.getOrNull() }
        } catch (e: Exception) { emptyList() }
    }
}
