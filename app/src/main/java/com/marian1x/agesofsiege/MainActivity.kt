package com.marian1x.agesofsiege

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.marian1x.agesofsiege.game.GameController
import com.marian1x.agesofsiege.ui.GameScreen
import com.marian1x.agesofsiege.update.AppUpdater
import com.marian1x.agesofsiege.update.UpdateCheckWorker

class MainActivity : ComponentActivity() {
    private lateinit var controller: GameController

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional – only for update alerts */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        hideSystemBars()
        controller = GameController(this)
        // Make sure nothing is lost if the update replaces the app while it is running.
        AppUpdater.beforeInstall = { controller.save() }
        UpdateCheckWorker.schedule(this)
        askForNotificationsOnce()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                GameScreen(controller)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        controller.start()
        AppUpdater.onResume(this)
    }

    override fun onPause() {
        // Time is paused while the app is in the background (offline = paused); progress is saved.
        controller.stop()
        super.onPause()
    }

    override fun onDestroy() {
        if (isFinishing) AppUpdater.beforeInstall = null
        controller.destroy()
        super.onDestroy()
    }

    /** Update alerts need the notification permission on Android 13+; ask a single time. */
    private fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("updates", MODE_PRIVATE)
        if (prefs.getBoolean("asked_notifications", false)) return
        prefs.edit().putBoolean("asked_notifications", true).apply()
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }
}
