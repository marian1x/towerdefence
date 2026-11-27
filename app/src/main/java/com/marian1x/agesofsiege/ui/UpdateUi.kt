package com.marian1x.agesofsiege.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marian1x.agesofsiege.update.AppUpdater
import com.marian1x.agesofsiege.update.UpdateState

private val UpdateGreen = Color(0xFF6EE06A)

/** True when the HUD should advertise an update. */
internal val updateWaiting: Boolean
    get() = AppUpdater.state.let {
        it is UpdateState.Available || it is UpdateState.Downloading || it is UpdateState.Installing
    }

/** Card shown on the main menu and in Settings while an update is available / in progress. */
@Composable
internal fun UpdateCard(modifier: Modifier = Modifier) {
    val activity = LocalActivity.current
    val state = AppUpdater.state
    val release = when (state) {
        is UpdateState.Available -> state.release
        is UpdateState.Downloading -> state.release
        is UpdateState.Installing -> state.release
        is UpdateState.Failed -> state.release
        else -> null
    } ?: return
    Column(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xE0142318))
            .border(1.dp, UpdateGreen.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text("NEW VERSION · ${release.title}", fontFamily = GameFont, fontSize = 16.sp, color = UpdateGreen)
        if (release.notes.isNotBlank()) {
            Text(release.notes.take(220), color = TextDim, fontSize = 12.sp, maxLines = 4, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.height(8.dp))
        when (state) {
            is UpdateState.Downloading -> ProgressBar("Downloading… ${(AppUpdater.progress * 100).toInt()}%", AppUpdater.progress)
            is UpdateState.Installing -> Text("Installing… the game restarts when it's done.", color = Color.White, fontSize = 13.sp)
            is UpdateState.Failed -> {
                Text(state.message, color = Color(0xFFFFB070), fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                MenuButton("TRY AGAIN", Modifier.fillMaxWidth(), primary = true) { activity?.let(AppUpdater::startUpdate) }
            }
            else -> {
                Text("Your progress is saved and kept.", color = TextDim, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                MenuButton("UPDATE NOW", Modifier.fillMaxWidth(), primary = true) { activity?.let(AppUpdater::startUpdate) }
            }
        }
    }
}

@Composable
private fun ProgressBar(label: String, fraction: Float) {
    Text(label, color = Color.White, fontSize = 13.sp)
    Spacer(Modifier.height(4.dp))
    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x40FFFFFF))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(8.dp).background(UpdateGreen))
    }
}

/** "Updates" block for the Settings dialog. */
@Composable
internal fun UpdateSettingsSection() {
    SectionLabel("Updates")
    Text("Version ${AppUpdater.currentVersion}", color = Color.White, fontSize = 14.sp)
    Spacer(Modifier.height(6.dp))
    when (val s = AppUpdater.state) {
        is UpdateState.Available, is UpdateState.Downloading, is UpdateState.Installing -> UpdateCard()
        is UpdateState.Failed -> if (s.release != null) UpdateCard() else {
            Text(s.message, color = Color(0xFFFFB070), fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            MenuButton("CHECK FOR UPDATES", Modifier.fillMaxWidth()) { AppUpdater.check(manual = true) }
        }
        UpdateState.Checking -> Text("Checking GitHub…", color = TextDim, fontSize = 13.sp)
        UpdateState.UpToDate -> {
            Text("You have the latest version.", color = UpdateGreen, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            MenuButton("CHECK AGAIN", Modifier.fillMaxWidth()) { AppUpdater.check(manual = true) }
        }
        UpdateState.Idle -> MenuButton("CHECK FOR UPDATES", Modifier.fillMaxWidth()) { AppUpdater.check(manual = true) }
    }
    Text(
        "New versions are published automatically whenever the game is updated on GitHub. " +
            "You'll also get a notification when one is ready.",
        color = TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp),
    )
}
