package com.marian1x.agesofsiege.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.content.IntentCompat
import com.marian1x.agesofsiege.BuildConfig

/** Receives the PackageInstaller session result. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Android wants the user to confirm the update: show the system dialog.
            val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(confirm)
            } catch (t: Throwable) {
                Log.w("AgesOfSiege.Update", "Could not show the install prompt", t)
                AppUpdater.onInstallFinished(PackageInstaller.STATUS_FAILURE, "could not show the install prompt")
            }
            return
        }
        AppUpdater.onInstallFinished(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}

/** Fired after this app was replaced by a newer version: invite the player back in. */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        UpdateNotifications.showUpdated(context, BuildConfig.VERSION_NAME)
    }
}
