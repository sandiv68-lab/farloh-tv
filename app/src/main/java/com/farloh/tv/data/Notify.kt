package com.farloh.tv.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.farloh.tv.R
import java.util.concurrent.TimeUnit

/**
 * Phone/TV notifications for a new Farloh TV version or a new message in the bell,
 * checked in the background every few hours (even when the app is closed).
 */
object Notify {
    private const val CHANNEL = "news"
    private const val PREFS = "farloh_notify"
    private const val ID_UPDATE = 1001
    private const val ID_MESSAGE = 1002

    fun schedule(ctx: Context) {
        if (!Feed.configured) return
        val req = PeriodicWorkRequestBuilder<FeedWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        runCatching {
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("feed-check", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }

    fun allowed(ctx: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Remembers what's already been seen so nothing is announced twice (call when the app shows it itself). */
    fun markShown(ctx: Context) {
        val e = prefs(ctx).edit()
        Feed.availableUpdate(ctx)?.let { e.putString("update", it.version) }
        e.putStringSet("messages", Feed.cached(ctx).map { it.key }.toSet())
        e.apply()
    }

    /** Posts notifications for anything new in the feed since last time. */
    fun announceNew(ctx: Context) {
        val p = prefs(ctx)
        val first = !p.contains("messages")
        val seen = Feed.seenKeys(ctx)
        val shown = p.getStringSet("messages", emptySet()) ?: emptySet()
        val update = Feed.availableUpdate(ctx)
        val newMessages = Feed.cached(ctx).filter { !it.isUpdate && it.key !in seen && it.key !in shown }
        if (!first && allowed(ctx)) {
            channel(ctx)
            if (update != null && p.getString("update", null) != update.version) {
                post(ctx, ID_UPDATE, "Farloh TV ${update.version} is available",
                    update.message.ifBlank { "Open Farloh TV to install the update." })
            }
            if (newMessages.isNotEmpty()) {
                val m = newMessages.first()
                val title = if (newMessages.size > 1) "${newMessages.size} new messages from Farloh TV" else m.title.ifBlank { "New message from Farloh TV" }
                post(ctx, ID_MESSAGE, title, if (newMessages.size > 1) m.title.ifBlank { m.message } else m.message)
            }
        }
        // Remember the current state (the first run only records it, so old messages aren't announced).
        markShown(ctx)
    }

    private fun channel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Updates and messages", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "New Farloh TV versions and messages from the bell"
                })
            }
        }
    }

    private fun post(ctx: Context, id: Int, title: String, text: String) {
        val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            ?: ctx.packageManager.getLeanbackLaunchIntentForPackage(ctx.packageName) ?: return
        val pi = PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
    }
}

class FeedWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = try {
        Feed.refresh(applicationContext, force = true)
        Notify.announceNew(applicationContext)
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }
}
