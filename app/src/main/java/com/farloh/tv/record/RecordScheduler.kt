package com.farloh.tv.record

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.farloh.tv.data.RecSchedule
import com.farloh.tv.data.Recordings

/**
 * Wakes the app up when the next recording is due (one alarm for the earliest one) and starts the ones that
 * should be recording now. Called whenever recordings change, when the app starts, after a restart of the
 * device, and by the alarm itself.
 */
object RecordScheduler {
    const val ACTION_ALARM = "com.farloh.tv.RECORD_ALARM"

    /** Android 12+: may we set alarms to the exact minute? (Settings → Recordings explains how to allow it.) */
    fun canExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()

    /** The next programme start of [s] that hasn't been recorded yet (null = none). */
    fun upcoming(ctx: Context, s: RecSchedule, now: Long = System.currentTimeMillis()): Long? {
        val done = Recordings.files(ctx).filter { it.scheduleId == s.id }.map { it.occurrence }.toSet()
        var occ = Recordings.nextOccurrence(ctx, s, now) ?: return null
        repeat(10) {
            if (occ !in done) return occ
            val end = Recordings.window(ctx, s, occ).second
            occ = Recordings.nextOccurrence(ctx, s, end + 1_000) ?: return null
        }
        return null
    }

    /** [fromBoot]: just after the device started, Android doesn't allow starting a recording straight away. */
    fun reschedule(ctx: Context, fromBoot: Boolean = false) {
        Recordings.dropExpired(ctx)
        val now = System.currentTimeMillis()
        var next = Long.MAX_VALUE
        for (s in Recordings.schedules(ctx)) {
            val occ = upcoming(ctx, s, now) ?: continue
            val (start, end) = Recordings.window(ctx, s, occ)
            if (start <= now + 20_000 && end > now) {
                if (fromBoot) next = minOf(next, now + 20_000) else start(ctx, s.id, occ)
            } else if (start > now) {
                next = minOf(next, start)
            }
        }
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, RecordAlarmReceiver::class.java).setAction(ACTION_ALARM),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.cancel(pi)
        if (next == Long.MAX_VALUE) return
        try {
            when {
                canExact(ctx) && Build.VERSION.SDK_INT >= 23 -> am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
                canExact(ctx) -> am.setExact(AlarmManager.RTC_WAKEUP, next, pi)
                Build.VERSION.SDK_INT >= 23 -> am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
                else -> am.set(AlarmManager.RTC_WAKEUP, next, pi)
            }
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi) else am.set(AlarmManager.RTC_WAKEUP, next, pi)
        }
    }

    fun start(ctx: Context, scheduleId: String, occurrence: Long) {
        val i = Intent(ctx, RecordService::class.java).setAction(RecordService.ACTION_START)
            .putExtra(RecordService.EXTRA_SCHEDULE, scheduleId).putExtra(RecordService.EXTRA_OCCURRENCE, occurrence)
        try {
            ContextCompat.startForegroundService(ctx, i)
        } catch (e: Exception) {
            // Android didn't allow it from the background (e.g. alarms not allowed to be exact).
            RecordService.notifyProblem(ctx, "A recording couldn't start", "Open Farloh TV to start it. To avoid this, allow " +
                "\"Alarms & reminders\" for Farloh TV (Settings → Recordings).")
        }
    }

    fun stop(ctx: Context, fileId: String) {
        val i = Intent(ctx, RecordService::class.java).setAction(RecordService.ACTION_STOP).putExtra(RecordService.EXTRA_FILE, fileId)
        runCatching { ctx.startService(i) }
    }
}

/** The alarm: start what's due and set the next alarm. */
class RecordAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = RecordScheduler.reschedule(context)
}

/** After a restart of the device or an app update, alarms are gone: set them again. */
class RecordBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, "android.intent.action.QUICKBOOT_POWERON" ->
                RecordScheduler.reschedule(context, fromBoot = true)
        }
    }
}
