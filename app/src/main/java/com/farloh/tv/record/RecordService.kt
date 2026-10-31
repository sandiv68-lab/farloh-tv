package com.farloh.tv.record

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.farloh.tv.R
import com.farloh.tv.data.Notify
import com.farloh.tv.data.RecFile
import com.farloh.tv.data.RecSchedule
import com.farloh.tv.data.Recordings
import com.farloh.tv.data.Store
import com.farloh.tv.ui.DownloadsActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Records in the background (with the "Recording…" notification Android requires), several at once if
 * needed. Keeps the device awake and the Wi-Fi on while recording.
 */
class RecordService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_SCHEDULE = "schedule"
        const val EXTRA_OCCURRENCE = "occurrence"
        const val EXTRA_FILE = "file"
        private const val CHANNEL = "recordings"
        private const val ID_RUNNING = 2001
        private const val ID_PROBLEM = 2002

        private class Active(val key: String, val job: Job, @Volatile var file: RecFile)

        private val active = ConcurrentHashMap<String, Active>()

        /** Ids of the recordings being made right now. */
        val runningIds: Set<String> get() = active.keys.toSet()

        /** The recording being made of this channel right now, if any. */
        fun activeFor(channelId: String): RecFile? = active.values.map { it.file }.firstOrNull { it.channelId == channelId && it.state == "recording" }

        fun isRecording(channelId: String) = active.values.any { it.file.channelId == channelId && it.file.state == "recording" }

        private fun channel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                if (nm.getNotificationChannel(CHANNEL) == null) {
                    nm.createNotificationChannel(NotificationChannel(CHANNEL, "Recordings", NotificationManager.IMPORTANCE_LOW))
                }
            }
        }

        private fun openRecordings(ctx: Context): PendingIntent = PendingIntent.getActivity(ctx, 0,
            Intent(ctx, DownloadsActivity::class.java).putExtra(DownloadsActivity.EXTRA_TAB, DownloadsActivity.TAB_RECORDINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        fun notifyProblem(ctx: Context, title: String, text: String) {
            if (!Notify.allowed(ctx)) return
            channel(ctx)
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_record)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openRecordings(ctx))
                .setAutoCancel(true)
                .build()
            runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID_PROBLEM, n) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        when (intent?.action) {
            ACTION_START -> {
                val id = intent.getStringExtra(EXTRA_SCHEDULE)
                val occ = intent.getLongExtra(EXTRA_OCCURRENCE, 0L)
                Recordings.schedules(this).firstOrNull { it.id == id }?.let { begin(it, occ) }
            }
            ACTION_STOP -> intent.getStringExtra(EXTRA_FILE)?.let { active[it]?.job?.cancel() }
        }
        if (active.isEmpty()) finishIfIdle()
        return START_NOT_STICKY
    }

    private fun begin(s: RecSchedule, occ: Long) {
        val key = "${s.id}|$occ"
        if (active.values.any { it.key == key }) return
        if (Recordings.files(this).any { it.scheduleId == s.id && it.occurrence == occ }) return
        val end = Recordings.window(this, s, occ).second
        if (end <= System.currentTimeMillis()) return
        val store = Store.get(this)
        val account = store.accountFor(s.playlistId)
        val folder = Recordings.folder(this)
        if (account == null || folder == null) {
            notifyProblem(this, "Recording failed: ${s.title}", if (account == null) "The playlist for ${s.channelName} isn't set up any more." else "No storage available.")
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(occ))
        val safe = (s.title.ifBlank { s.channelName }).replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').take(40).ifBlank { "recording" }
        val ts = File(folder, "${stamp}_$safe.ts").let { if (it.exists()) File(folder, "${stamp}_${safe}_${System.currentTimeMillis() % 10000}.ts") else it }
        val file = RecFile(
            scheduleId = s.id, occurrence = occ, channelId = s.channelId, channelName = s.channelName, logo = s.logo,
            playlistId = s.playlistId, title = s.title.ifBlank { s.channelName }, start = System.currentTimeMillis(), end = end,
            path = ts.path, state = "recording",
        )
        Recordings.saveFile(this, file)
        lateinit var entry: Active
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val notes = ArrayList<String>()
            val path = ts.path
            try {
                val r = Recorder.capture(account, s.channelId, ts, end)
                notes += r.notes
            } catch (e: CancellationException) {
                notes += "Stopped by you"
            } catch (e: Exception) {
                notes += "Problem: ${e.message}"
            } finally {
                withContext(NonCancellable) {
                    val ok = File(path).let { it.exists() && it.length() > 0 }
                    Recordings.saveFile(this@RecordService, entry.file.copy(path = path, state = if (ok) "done" else "failed",
                        end = if (ok) minOf(System.currentTimeMillis(), end) else end, notes = notes.joinToString(" • ")))
                    if (!ok) notifyProblem(this@RecordService, "Recording failed: ${s.title}", notes.joinToString(" • ").ifBlank { "Nothing was recorded." })
                    active.remove(entry.file.id)
                    withContext(Dispatchers.Main) {
                        updateNotification()
                        finishIfIdle()
                    }
                }
            }
        }
        entry = Active(key, job, file)
        active[file.id] = entry
        job.start()
        holdLocks()
        updateNotification()
        // The next recording's alarm.
        RecordScheduler.reschedule(this)
    }

    // ---------------- Notification & locks ----------------

    private fun notification(): android.app.Notification {
        channel(this)
        val list = active.values.map { it.file }
        val recording = list.filter { it.state == "recording" }
        val title = when {
            recording.size == 1 -> "● Recording: ${recording[0].title}"
            recording.size > 1 -> "● Recording ${recording.size} programmes"
            else -> "Recordings"
        }
        val fmt = android.text.format.DateFormat.getTimeFormat(this)
        val text = recording.joinToString("\n") { "${it.channelName} – until ${fmt.format(Date(it.end))}" }
            .ifBlank { "Starting…" }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_record)
            .setContentTitle(title)
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openRecordings(this))
            .build()
    }

    private fun goForeground() {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, ID_RUNNING, notification(), type)
    }

    private fun updateNotification() {
        if (active.isEmpty()) return
        runCatching { getSystemService(NotificationManager::class.java).notify(ID_RUNNING, notification()) }
    }

    @Suppress("DEPRECATION")
    private fun holdLocks() {
        if (wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "farloh:recording").apply { setReferenceCounted(false); acquire(12 * 3600_000L) }
        }
        if (wifiLock == null) {
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = runCatching {
                (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).createWifiLock(mode, "farloh:recording")
                    .apply { setReferenceCounted(false); acquire() }
            }.getOrNull()
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }; wakeLock = null
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }; wifiLock = null
    }

    private fun finishIfIdle() {
        if (active.isNotEmpty()) return
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Android 15 limits background work like this to 6 hours a day: stop cleanly, keeping what was saved. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        active.values.forEach { it.job.cancel() }
    }

    override fun onDestroy() {
        active.values.forEach { it.job.cancel() }
        scope.cancel()
        releaseLocks()
        super.onDestroy()
    }
}
