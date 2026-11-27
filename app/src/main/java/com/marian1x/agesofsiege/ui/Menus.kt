package com.marian1x.agesofsiege.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marian1x.agesofsiege.BuildConfig
import com.marian1x.agesofsiege.core.CampaignSettings
import com.marian1x.agesofsiege.core.Difficulty
import com.marian1x.agesofsiege.core.Pace
import com.marian1x.agesofsiege.game.GameController
import com.marian1x.agesofsiege.game.Overlay
import com.marian1x.agesofsiege.game.Screen
import com.marian1x.agesofsiege.update.AppUpdater

// ------------------------------------------------------------------ shared building blocks

@Composable
internal fun MenuButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bg = when {
        !enabled -> Color(0x40FFFFFF)
        primary -> Gold
        danger -> Color(0xFF8E3030)
        else -> Color(0xFF2B3243)
    }
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, if (primary) Color(0xFFFFE6A0) else PanelBorder, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontFamily = GameFont, fontSize = 18.sp, color = if (primary) Color(0xFF1A1F2B) else Color.White)
    }
}

@Composable
internal fun ChipRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Text(
                label, fontFamily = GameFont, fontSize = 15.sp,
                color = if (on) Color(0xFF1A1F2B) else Color.White,
                modifier = Modifier.clip(RoundedCornerShape(10.dp))
                    .background(if (on) Gold else Color(0xFF2B3243))
                    .clickable { onSelect(i) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
internal fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = value, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Gold, checkedThumbColor = Color(0xFF1A1F2B)),
        )
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(text.uppercase(), color = TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
}

/** Dimmed scrim + centered panel. */
@Composable
internal fun GameDialog(title: String, width: Int = 430, onClose: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0x99000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose?.invoke() },
        contentAlignment = Alignment.Center,
    ) {
        Panel(
            Modifier.widthIn(max = width.dp).fillMaxWidth(0.9f)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Column(Modifier.padding(18.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontFamily = GameFont, fontSize = 24.sp, color = Gold, modifier = Modifier.weight(1f))
                    if (onClose != null) {
                        Text("✕", color = TextDim, fontSize = 20.sp, modifier = Modifier.clickable { onClose() }.padding(6.dp))
                    }
                }
                Spacer(Modifier.height(6.dp))
                content()
            }
        }
    }
}

// ------------------------------------------------------------------ screens

@Composable
fun MainMenu(c: GameController) {
    Box(
        Modifier.fillMaxSize().background(
            Brush.horizontalGradient(listOf(Color(0xE60E121B), Color(0xB30E121B), Color(0x000E121B)))
        )
    ) {
        Column(
            Modifier.fillMaxHeight().safeDrawingPadding().padding(start = 48.dp, top = 24.dp, bottom = 24.dp).width(340.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "AGES OF SIEGE", fontFamily = GameFont, fontSize = 46.sp, color = Gold,
                style = TextStyle(shadow = Shadow(Color(0xAA000000), Offset(0f, 6f), 12f)),
            )
            Text("Defend your keep · Explore the realm · Conquer your rival", color = TextDim, fontSize = 14.sp)
            Spacer(Modifier.height(28.dp))
            if (c.hasSave) {
                MenuButton("CONTINUE", Modifier.fillMaxWidth(), primary = true) { c.continueGame() }
                Spacer(Modifier.height(10.dp))
            }
            MenuButton("NEW GAME", Modifier.fillMaxWidth(), primary = !c.hasSave) { c.hud.overlay = Overlay.NEW_GAME }
            Spacer(Modifier.height(10.dp))
            MenuButton("SETTINGS", Modifier.fillMaxWidth()) { c.hud.overlay = Overlay.SETTINGS }
            if (updateWaiting || AppUpdater.availableRelease != null) {
                Spacer(Modifier.height(14.dp))
                UpdateCard()
            }
            Spacer(Modifier.height(24.dp))
            Text("Prototype · Medieval Age · v${BuildConfig.VERSION_NAME}", color = Color(0x80FFFFFF), fontSize = 12.sp)
            Text("Art: Kenney (CC0) · Sky: Poly Haven (CC0)", color = Color(0x80FFFFFF), fontSize = 12.sp)
        }
    }
}

@Composable
fun NewGamePanel(c: GameController) {
    val current = c.hud.settings
    var name by remember { mutableStateOf(current.lordName) }
    var difficulty by remember { mutableStateOf(current.difficulty) }
    var pace by remember { mutableStateOf(current.pace) }
    val back = { c.hud.overlay = if (c.hud.screen == Screen.MENU) Overlay.NONE else Overlay.PAUSE }
    GameDialog("New realm", onClose = back) {
        SectionLabel("Your name")
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(24) }, singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedBorderColor = Gold, unfocusedBorderColor = PanelBorder, cursorColor = Gold,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        SectionLabel("Difficulty")
        ChipRow(Difficulty.entries.map { it.label }, difficulty.ordinal) { difficulty = Difficulty.entries[it] }
        SectionLabel("Game pace")
        ChipRow(Pace.entries.map { it.label }, pace.ordinal) { pace = Pace.entries[it] }
        Text(
            "Pace speeds up production, marches and how fast your rival grows. Time is paused while the game is closed.",
            color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
        )
        if (c.hasSave) {
            Text("Starting a new realm replaces your current game.", color = Color(0xFFFFB070), fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MenuButton("CANCEL", Modifier.weight(1f)) { back() }
            MenuButton("START", Modifier.weight(1f), primary = true) {
                c.newGame(CampaignSettings(name.ifBlank { "Lord of the Keep" }, difficulty, pace))
            }
        }
    }
}

@Composable
fun PauseMenu(c: GameController) {
    GameDialog("Paused", width = 340, onClose = { c.hud.overlay = Overlay.NONE }) {
        Text("${c.hud.settings.lordName} · ${c.hud.settings.difficulty.label} · ${c.hud.settings.pace.label} pace", color = TextDim, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        MenuButton("RESUME", Modifier.fillMaxWidth(), primary = true) { c.hud.overlay = Overlay.NONE }
        Spacer(Modifier.height(8.dp))
        MenuButton("SETTINGS", Modifier.fillMaxWidth()) { c.hud.overlay = Overlay.SETTINGS }
        Spacer(Modifier.height(8.dp))
        MenuButton("RESTART", Modifier.fillMaxWidth(), danger = true) { c.hud.overlay = Overlay.CONFIRM_RESTART }
        Spacer(Modifier.height(8.dp))
        MenuButton("MAIN MENU", Modifier.fillMaxWidth()) { c.toMainMenu() }
    }
}

@Composable
fun ConfirmRestart(c: GameController) {
    GameDialog("Restart?", width = 380, onClose = { c.hud.overlay = Overlay.PAUSE }) {
        Text("Your base, army and world progress will be lost. You can pick new settings next.", color = Color.White, fontSize = 15.sp)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MenuButton("BACK", Modifier.weight(1f)) { c.hud.overlay = Overlay.PAUSE }
            MenuButton("RESTART", Modifier.weight(1f), danger = true) { c.hud.overlay = Overlay.NEW_GAME }
        }
    }
}

@Composable
fun SettingsPanel(c: GameController) {
    val prefs = c.prefs
    val s = c.hud.settings
    val back = { c.hud.overlay = if (c.hud.screen == Screen.MENU) Overlay.NONE else Overlay.PAUSE }
    GameDialog("Settings", onClose = back) {
        SectionLabel("Graphics")
        ChipRow(listOf("High quality", "Battery saver"), if (prefs.highQuality) 0 else 1) {
            prefs.highQuality = it == 0
            c.applyPrefs()
        }
        Text("Battery saver turns off ambient occlusion, bloom and anti-aliasing.", color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        SectionLabel("Gameplay")
        ToggleRow("Show damage numbers", prefs.damageNumbers) { prefs.damageNumbers = it; c.applyPrefs() }
        ToggleRow("Camera shake", prefs.cameraShake) { prefs.cameraShake = it; c.applyPrefs() }
        if (c.hud.screen != Screen.MENU) {
            SectionLabel("This realm")
            Text("Game pace", color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            ChipRow(Pace.entries.map { it.label }, s.pace.ordinal) { c.updateCampaignSettings(s.copy(pace = Pace.entries[it])) }
            Spacer(Modifier.height(8.dp))
            Text("Difficulty", color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            ChipRow(Difficulty.entries.map { it.label }, s.difficulty.ordinal) { c.updateCampaignSettings(s.copy(difficulty = Difficulty.entries[it])) }
        }
        UpdateSettingsSection()
        Spacer(Modifier.height(16.dp))
        MenuButton("DONE", Modifier.fillMaxWidth(), primary = true) { back() }
    }
}
