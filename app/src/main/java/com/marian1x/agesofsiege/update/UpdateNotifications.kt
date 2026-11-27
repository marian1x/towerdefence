package com.marian1x.agesofsiege.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import com.marian1x.agesofsiege.BuildConfig
import com.marian1x.agesofsiege.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object UpdateNotifications {
    private const val CHANNEL = "updates"
    private const val ID_AVAILABLE = 1001
    private const val ID_UPDATED = 1002

    private fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Game updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Tells you when a new version of Ages of Siege is ready"
                }
            )
        }
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun post(context: Context, id: Int, title: String, text: String) {
        if (!canNotify(context)) return
        ensureChannel(context)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
            // Permission revoked in the meantime – nothing to do.
        }
    }

    fun showAvailable(context: Context, release: Release) {
        val firstLine = release.notes.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        post(context, ID_AVAILABLE, "${release.title} is ready", "Tap to open the game and update. $firstLine".trim())
    }

    fun showUpdated(context: Context, versionName: String) {
        NotificationManagerCompat.from(context).cancel(ID_AVAILABLE)
        post(context, ID_UPDATED, "Ages of Siege updated to $versionName", "Tap to continue your game.")
    }
}

/** Periodic background check so a new build shows up as a notification even when the game is closed. */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val latest = try {
            withContext(Dispatchers.IO) { AppUpdater.fetchLatest() }
        } catch (_: Throwable) {
            return Result.retry()
        }
        if (latest != null && latest.versionCode > BuildConfig.VERSION_CODE) {
            val prefs = applicationContext.getSharedPreferences("updates", Context.MODE_PRIVATE)
            if (prefs.getInt("notified", 0) < latest.versionCode) {
                UpdateNotifications.showAvailable(applicationContext, latest)
                prefs.edit().putInt("notified", latest.versionCode).apply()
            }
        }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(3, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("update-check", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
