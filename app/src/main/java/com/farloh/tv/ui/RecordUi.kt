package com.farloh.tv.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.farloh.tv.R
import com.farloh.tv.data.Programme
import com.farloh.tv.data.RecSchedule
import com.farloh.tv.data.Recordings
import com.farloh.tv.player.Playable
import com.farloh.tv.record.RecordScheduler
import com.farloh.tv.record.RecordService
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Setting up recordings: from the TV guide, "Record now" in the player, or by hand (channel, day, time). */
object RecordUi {

    /** What's being set up (an existing schedule keeps its [id]). */
    class Draft(
        val id: String?,
        val channelId: String,
        val channelName: String,
        val logo: String?,
        val playlistId: String?,
        var title: String,
        var start: Long,
        var end: Long,
        var days: Set<Int> = emptySet(),
        var quality: String,
    )

    private val DAY_NAMES = listOf(Calendar.MONDAY to "Mon", Calendar.TUESDAY to "Tue", Calendar.WEDNESDAY to "Wed",
        Calendar.THURSDAY to "Thu", Calendar.FRIDAY to "Fri", Calendar.SATURDAY to "Sat", Calendar.SUNDAY to "Sun")
    private val WEEKDAYS = setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY)
    private val WEEKEND = setOf(Calendar.SATURDAY, Calendar.SUNDAY)
    private val ALL_DAYS = WEEKDAYS + WEEKEND

    fun repeatLabel(days: Set<Int>) = when {
        days.isEmpty() -> "Once"
        days == ALL_DAYS -> "Every day"
        days == WEEKDAYS -> "Weekdays (Mon – Fri)"
        days == WEEKEND -> "Weekends"
        else -> "Every " + DAY_NAMES.filter { it.first in days }.joinToString(", ") { it.second }
    }

    fun whenLabel(activity: BaseActivity, start: Long, end: Long): String {
        val day = when {
            android.text.format.DateUtils.isToday(start) -> "Today"
            android.text.format.DateUtils.isToday(start - 86_400_000L) -> "Tomorrow"
            else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(start))
        }
        return "$day ${Fmt.time(activity, start)} – ${Fmt.time(activity, end)}"
    }

    // ---------------- Ways in ----------------

    /** OK on a programme in the TV guide → Record. */
    fun fromProgramme(activity: BaseActivity, channel: Playable, p: Programme) {
        if (p.end <= System.currentTimeMillis()) { activity.toast("That programme has finished"); return }
        edit(activity, Draft(null, channel.id, channel.title, channel.logo, channel.playlistId, p.title.ifBlank { channel.title },
            p.start, p.end, quality = activity.store.recQuality))
    }

    /** Player → Settings → Record: now (until the programme ends, or for a while), or set one up for later. */
    fun playerMenu(activity: BaseActivity, channel: Playable, now: Programme?) {
        val running = RecordService.activeFor(channel.id)
        val opts = ArrayList<Pair<String, () -> Unit>>()
        if (running != null) {
            opts += "■  Stop recording (${running.title})" to { RecordScheduler.stop(activity, running.id); activity.toast("Recording stopped") }
        } else {
            if (now != null && now.end > System.currentTimeMillis()) {
                opts += "●  Record now until \"${now.title}\" ends (${Fmt.time(activity, now.end)})" to {
                    startNow(activity, channel, now.title, now.end + activity.store.recPadAfterMin * 60_000L)
                }
            }
            for ((label, min) in listOf("30 minutes" to 30, "1 hour" to 60, "2 hours" to 120, "3 hours" to 180)) {
                opts += "●  Record now for $label" to {
                    startNow(activity, channel, now?.title ?: channel.title, System.currentTimeMillis() + min * 60_000L)
                }
            }
        }
        opts += "Set up a recording on this channel…" to {
            val start = roundUp(System.currentTimeMillis())
            edit(activity, Draft(null, channel.id, channel.title, channel.logo, channel.playlistId, channel.title,
                start, start + 3600_000L, quality = activity.store.recQuality))
        }
        AlertDialog.Builder(activity)
            .setTitle("Record – ${channel.title}")
            .setItems(opts.map { it.first }.toTypedArray()) { _, which -> opts[which].second() }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Downloads → Recordings → New recording: choose the channel, then the day and time. */
    fun manual(activity: BaseActivity, onSaved: () -> Unit = {}) {
        MvPicker(activity, { items, index, _ ->
            val ch = items.getOrNull(index) ?: return@MvPicker
            val start = roundUp(System.currentTimeMillis())
            edit(activity, Draft(null, ch.id, ch.title, ch.logo, ch.playlistId, ch.title, start, start + 3600_000L,
                quality = activity.store.recQuality), onSaved)
        }, liveOnly = true).start()
    }

    fun editSchedule(activity: BaseActivity, s: RecSchedule, onSaved: () -> Unit = {}) {
        edit(activity, Draft(s.id, s.channelId, s.channelName, s.logo, s.playlistId, s.title, s.startMs, s.endMs, s.days, s.quality), onSaved)
    }

    private fun startNow(activity: BaseActivity, channel: Playable, title: String, end: Long) {
        val now = System.currentTimeMillis()
        val s = RecSchedule(channelId = channel.id, channelName = channel.title, logo = channel.logo, playlistId = channel.playlistId,
            title = title, startMs = now, endMs = end, quality = activity.store.recQuality, now = true)
        beforeSaving(activity, s) {
            Recordings.saveSchedule(activity, s)
            RecordScheduler.reschedule(activity)
            activity.toast("● Recording until ${Fmt.time(activity, end)}")
        }
    }

    private fun roundUp(t: Long): Long {
        val c = Calendar.getInstance().apply { timeInMillis = t; set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val m = c.get(Calendar.MINUTE)
        c.add(Calendar.MINUTE, (5 - m % 5) % 5 + if (m % 5 == 0) 5 else 0)
        return c.timeInMillis
    }

    // ---------------- The editor ----------------

    fun edit(activity: BaseActivity, d: Draft, onSaved: () -> Unit = {}) {
        val again = { edit(activity, d, onSaved) }
        val st = activity.store
        val rows = ArrayList<Pair<String, () -> Unit>>()
        rows += "Programme:  ${d.title}" to {
            Dialogs.input(activity, "Name of the recording", d.title) { d.title = it.trim().ifBlank { d.channelName }; again() }
        }
        rows += "Day:  ${dayLabel(d.start)}" + (if (d.days.isNotEmpty()) "  (first time)" else "") to { chooseDay(activity, d, again) }
        rows += "Starts:  ${Fmt.time(activity, d.start)}" to {
            pickTime(activity, "Starts at", d.start) { h, m ->
                val dur = d.end - d.start
                d.start = Calendar.getInstance().apply {
                    timeInMillis = d.start; set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                d.end = d.start + dur
                again()
            }
        }
        rows += "Ends:  ${Fmt.time(activity, d.end)}" + (if (!sameDay(d.start, d.end)) " (next day)" else "") to {
            pickTime(activity, "Ends at", d.end) { h, m ->
                var e = Calendar.getInstance().apply {
                    timeInMillis = d.start; set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                if (e <= d.start) e += 86_400_000L
                d.end = e
                again()
            }
        }
        rows += "Repeat:  ${repeatLabel(d.days)}" to { chooseRepeat(activity, d, again) }
        rows += "Quality:  ${if (d.quality == "low") "Low (480p)" else "Default (as broadcast)"}" to {
            Dialogs.choose(activity, "Quality", listOf("Default (as broadcast)", "Low (480p) – smaller files, made after the recording ends"),
                listOf("default", "low"), d.quality) { d.quality = it; again() }
        }
        val pad = "Records ${st.recPadBeforeMin} min early and ${st.recPadAfterMin} min late (Settings → Recordings)."
        AlertDialog.Builder(activity)
            .setTitle("${if (d.id == null) "Record" else "Edit recording"} – ${d.channelName}")
            .setItems(rows.map { it.first }.toTypedArray()) { _, which -> rows[which].second() }
            .setPositiveButton("Save") { _, _ -> save(activity, d, onSaved) }
            .setNegativeButton("Cancel", null)
            .create().apply {
                setOnShowListener {
                    // The note about extra time, under the list.
                    listView?.addFooterView(TextView(activity).apply {
                        text = pad
                        textSize = 12f
                        setTextColor(ContextCompat.getColor(activity, R.color.text2))
                        val p = (20 * activity.resources.displayMetrics.density).toInt()
                        setPadding(p, p / 2, p, p / 2)
                    }, null, false)
                }
            }
            .show()
    }

    private fun sameDay(a: Long, b: Long): Boolean {
        val x = Calendar.getInstance().apply { timeInMillis = a }
        val y = Calendar.getInstance().apply { timeInMillis = b }
        return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR)
    }

    private fun dayLabel(t: Long) = when {
        android.text.format.DateUtils.isToday(t) -> "Today"
        android.text.format.DateUtils.isToday(t - 86_400_000L) -> "Tomorrow"
        else -> SimpleDateFormat("EEEE d MMM", Locale.getDefault()).format(Date(t))
    }

    private fun chooseDay(activity: BaseActivity, d: Draft, then: () -> Unit) {
        val base = Calendar.getInstance()
        val starts = (0 until 14).map { offset ->
            Calendar.getInstance().apply {
                timeInMillis = d.start
                set(Calendar.YEAR, base.get(Calendar.YEAR)); set(Calendar.DAY_OF_YEAR, base.get(Calendar.DAY_OF_YEAR))
                add(Calendar.DAY_OF_YEAR, offset)
            }.timeInMillis
        }
        AlertDialog.Builder(activity).setTitle("Day")
            .setItems(starts.map { dayLabel(it) }.toTypedArray()) { _, which ->
                val dur = d.end - d.start
                d.start = starts[which]; d.end = d.start + dur
                then()
            }
            .setNegativeButton("Back") { _, _ -> then() }
            .show()
    }

    private fun chooseRepeat(activity: BaseActivity, d: Draft, then: () -> Unit) {
        val choices = listOf("Once" to emptySet(), "Every day" to ALL_DAYS, "Weekdays (Mon – Fri)" to WEEKDAYS, "Weekends" to WEEKEND)
        AlertDialog.Builder(activity).setTitle("Repeat")
            .setItems((choices.map { it.first } + "Choose days…").toTypedArray()) { _, which ->
                if (which < choices.size) { d.days = choices[which].second; then(); return@setItems }
                val checked = DAY_NAMES.map { it.first in d.days }.toBooleanArray()
                AlertDialog.Builder(activity).setTitle("Record every…")
                    .setMultiChoiceItems(DAY_NAMES.map { dayName(it.first) }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
                    .setPositiveButton("OK") { _, _ ->
                        d.days = DAY_NAMES.filterIndexed { i, _ -> checked[i] }.map { it.first }.toSet()
                        then()
                    }
                    .setNegativeButton("Back") { _, _ -> then() }
                    .show()
            }
            .setNegativeButton("Back") { _, _ -> then() }
            .show()
    }

    private fun dayName(day: Int): String =
        SimpleDateFormat("EEEE", Locale.getDefault()).format(Calendar.getInstance().apply { set(Calendar.DAY_OF_WEEK, day) }.time)

    /** Hours and minutes with the remote's Up / Down (works on TVs and phones). */
    private fun pickTime(activity: BaseActivity, title: String, at: Long, onPick: (Int, Int) -> Unit) {
        val c = Calendar.getInstance().apply { timeInMillis = at }
        val hours = NumberPicker(activity).apply {
            minValue = 0; maxValue = 23; value = c.get(Calendar.HOUR_OF_DAY); setFormatter { "%02d".format(it) }
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
        }
        val minutes = NumberPicker(activity).apply {
            minValue = 0; maxValue = 59; value = c.get(Calendar.MINUTE); setFormatter { "%02d".format(it) }
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            addView(hours)
            addView(TextView(activity).apply { text = " : "; textSize = 22f; setTextColor(ContextCompat.getColor(activity, R.color.text)) })
            addView(minutes)
        }
        AlertDialog.Builder(activity).setTitle(title).setView(box)
            .setPositiveButton("OK") { _, _ -> onPick(hours.value, minutes.value) }
            .setNegativeButton("Cancel", null)
            .show()
        hours.requestFocus()
    }

    // ---------------- Saving ----------------

    private fun save(activity: BaseActivity, d: Draft, onSaved: () -> Unit) {
        if (d.days.isEmpty() && d.end <= System.currentTimeMillis()) { activity.toast("That time has already passed"); edit(activity, d, onSaved); return }
        val s = RecSchedule(
            id = d.id ?: java.util.UUID.randomUUID().toString(), channelId = d.channelId, channelName = d.channelName,
            logo = d.logo, playlistId = d.playlistId, title = d.title, startMs = d.start, endMs = d.end, days = d.days, quality = d.quality,
        )
        beforeSaving(activity, s) {
            Recordings.saveSchedule(activity, s)
            RecordScheduler.reschedule(activity)
            activity.toast("Recording set: ${whenLabel(activity, d.start, d.end)}" + if (d.days.isNotEmpty()) " • ${repeatLabel(d.days)}" else "")
            onSaved()
        }
    }

    /** Warnings before a recording is saved: connections, alarms allowed, phone battery savers. */
    private fun beforeSaving(activity: BaseActivity, s: RecSchedule, then: () -> Unit) {
        val steps = ArrayList<(() -> Unit) -> Unit>()
        val clashes = clashes(activity, s)
        if (clashes > 0) steps += { next ->
            AlertDialog.Builder(activity).setTitle("Recordings at the same time")
                .setMessage("This overlaps with ${if (clashes == 1) "another recording" else "$clashes other recordings"}. " +
                    "Each recording uses one connection to your IPTV account (and watching uses another), so " +
                    "please make sure your subscription allows that many at once.")
                .setPositiveButton("Record anyway") { _, _ -> next() }
                .setNegativeButton("Cancel", null).show()
        }
        if (!RecordScheduler.canExact(activity)) steps += { next ->
            AlertDialog.Builder(activity).setTitle("Start recordings on time")
                .setMessage("To start recordings exactly on time, allow \"Alarms & reminders\" for Farloh TV.")
                .setPositiveButton("Allow") { _, _ -> openAlarmSettings(activity); next() }
                .setNegativeButton("Not now") { _, _ -> next() }
                .setCancelable(false).show()
        }
        if (!activity.tvMode && !activity.store.recPhoneTipShown) steps += { next ->
            activity.store.recPhoneTipShown = true
            AlertDialog.Builder(activity).setTitle("Recording on a phone")
                .setMessage("Battery savers can stop recordings on phones. For the best results keep the phone charging and " +
                    "allow Farloh TV to run in the background (Settings → Apps → Farloh TV → Battery → Unrestricted).")
                .setPositiveButton("OK") { _, _ -> next() }
                .setCancelable(false).show()
        }
        fun run(i: Int) { if (i >= steps.size) then() else steps[i] { run(i + 1) } }
        run(0)
    }

    /** How many other recordings overlap with the next time [s] records. */
    private fun clashes(activity: BaseActivity, s: RecSchedule): Int {
        val occ = Recordings.nextOccurrence(activity, s) ?: return 0
        val (a, b) = Recordings.window(activity, s, occ)
        val others = Recordings.schedules(activity).filter { it.id != s.id }.count { o ->
            val oo = RecordScheduler.upcoming(activity, o) ?: return@count false
            val (c, e) = Recordings.window(activity, o, oo)
            c < b && a < e
        }
        val running = Recordings.files(activity).count { it.state == "recording" && it.end > a && it.scheduleId != s.id }
        return others + running
    }

    fun openAlarmSettings(activity: BaseActivity) {
        if (Build.VERSION.SDK_INT < 31) return
        try {
            activity.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${activity.packageName}")))
        } catch (e: Exception) {
            activity.toast("Open the device's Settings → Apps → Special app access → Alarms & reminders → Farloh TV", long = true)
        }
    }
}
