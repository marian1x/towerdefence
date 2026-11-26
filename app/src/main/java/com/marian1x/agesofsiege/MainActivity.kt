package com.marian1x.agesofsiege

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.marian1x.agesofsiege.game.GameController
import com.marian1x.agesofsiege.ui.GameScreen

class MainActivity : ComponentActivity() {
    private lateinit var controller: GameController

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        hideSystemBars()
        controller = GameController(this)
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
    }

    override fun onPause() {
        // Time is paused while the app is in the background (offline = paused); progress is saved.
        controller.stop()
        super.onPause()
    }

    override fun onDestroy() {
        controller.destroy()
        super.onDestroy()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }
}
