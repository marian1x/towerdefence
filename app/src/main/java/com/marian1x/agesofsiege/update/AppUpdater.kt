package com.marian1x.agesofsiege.update

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.marian1x.agesofsiege.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A build published by CI as a GitHub release tagged `build-<versionCode>`. */
data class Release(
    val versionCode: Int,
    val title: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release) : UpdateState
    data class Installing(val release: Release) : UpdateState
    data class Failed(val message: String, val release: Release?) : UpdateState
}

/**
 * Self-updater: checks the latest GitHub release of [BuildConfig.UPDATE_REPO], downloads its APK and
 * installs it with [PackageInstaller]. Updates keep the save because every build is signed with the same key.
 */
object AppUpdater {
    private const val TAG = "AgesOfSiege.Update"
    private const val CHECK_INTERVAL_MS = 30 * 60 * 1000L

    var state by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set

    /** Download progress 0..1 while [UpdateState.Downloading]. */
    var progress by mutableFloatStateOf(0f)
        private set

    /** Called right before the new APK is handed to the installer (the process may be replaced). */
    var beforeInstall: (() -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastCheck = 0L
    private var waitingForInstallPermission = false

    val currentVersion: String get() = "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"

    val availableRelease: Release?
        get() = when (val s = state) {
            is UpdateState.Available -> s.release
            is UpdateState.Failed -> s.release
            else -> null
        }

    // ------------------------------------------------------------ checking

    /** Checks GitHub for a newer build. Automatic checks are throttled and fail silently. */
    fun check(manual: Boolean = false) {
        val s = state
        if (s is UpdateState.Checking || s is UpdateState.Downloading || s is UpdateState.Installing) return
        if (!manual && System.currentTimeMillis() - lastCheck < CHECK_INTERVAL_MS) return
        lastCheck = System.currentTimeMillis()
        if (manual || s !is UpdateState.Available) state = UpdateState.Checking
        scope.launch {
            state = try {
                val latest = withContext(Dispatchers.IO) { fetchLatest() }
                if (latest != null && latest.versionCode > BuildConfig.VERSION_CODE) UpdateState.Available(latest)
                else UpdateState.UpToDate
            } catch (t: Throwable) {
                Log.w(TAG, "Update check failed", t)
                if (manual) UpdateState.Failed("Couldn't reach GitHub. Check your internet connection.", null)
                else UpdateState.Idle
            }
        }
    }

    /** Blocking – call off the main thread. Returns null when there is no usable release yet. */
    fun fetchLatest(): Release? {
        val conn = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "AgesOfSiege-Updater")
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        try {
            if (conn.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return null // nothing released yet
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("GitHub answered ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.getString("tag_name")
            val code = tag.substringAfterLast('-').toIntOrNull() ?: return null
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) {
                    return Release(
                        versionCode = code,
                        title = json.optString("name").ifBlank { tag },
                        notes = json.optString("body").trim(),
                        apkUrl = a.getString("browser_download_url"),
                        sizeBytes = a.optLong("size"),
                    )
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------ installing

    /** Downloads and installs [availableRelease]. Asks for the "install unknown apps" permission first if needed. */
    fun startUpdate(activity: Activity) {
        val release = availableRelease ?: return
        if (!activity.packageManager.canRequestPackageInstalls()) {
            waitingForInstallPermission = true
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            )
            return
        }
        val app = activity.applicationContext
        progress = 0f
        state = UpdateState.Downloading(release)
        scope.launch {
            try {
                val apk = withContext(Dispatchers.IO) { download(app, release) }
                state = UpdateState.Installing(release)
                beforeInstall?.invoke()
                withContext(Dispatchers.IO) { install(app, apk) }
            } catch (t: Throwable) {
                Log.w(TAG, "Update failed", t)
                state = UpdateState.Failed("Download failed: ${t.message ?: "unknown error"}", release)
            }
        }
    }

    /** Call from Activity.onResume: continues the update after the user granted the install permission. */
    fun onResume(activity: Activity) {
        if (waitingForInstallPermission) {
            waitingForInstallPermission = false
            if (activity.packageManager.canRequestPackageInstalls()) {
                startUpdate(activity)
                return
            }
        }
        check()
    }

    internal fun onInstallFinished(status: Int, message: String?) {
        val release = (state as? UpdateState.Installing)?.release ?: availableRelease
        state = when (status) {
            PackageInstaller.STATUS_SUCCESS -> UpdateState.UpToDate
            PackageInstaller.STATUS_FAILURE_ABORTED -> release?.let { UpdateState.Available(it) } ?: UpdateState.Idle
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                UpdateState.Failed(
                    "This copy was signed with a different key. Uninstall it once and install the APK from GitHub; " +
                        "after that, updates work in-app.", release,
                )
            PackageInstaller.STATUS_FAILURE_STORAGE -> UpdateState.Failed("Not enough storage for the update.", release)
            else -> UpdateState.Failed("Install failed: ${message ?: "error $status"}", release)
        }
    }

    private fun download(context: Context, release: Release): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "AgesOfSiege-${release.versionCode}.apk")
        val conn = URL(release.apkUrl).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "AgesOfSiege-Updater")
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("server answered ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) progress = (done.toFloat() / total).coerceIn(0f, 1f)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        return out
    }

    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Lets Android update us without an extra prompt where allowed (the app updating itself).
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
    }
}
