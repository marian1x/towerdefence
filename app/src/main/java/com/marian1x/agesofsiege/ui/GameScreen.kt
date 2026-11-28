package com.marian1x.agesofsiege.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.marian1x.agesofsiege.R
import androidx.activity.compose.BackHandler
import com.marian1x.agesofsiege.game.Overlay
import com.marian1x.agesofsiege.game.Screen
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.marian1x.agesofsiege.core.Content
import com.marian1x.agesofsiege.core.Cost
import com.marian1x.agesofsiege.core.Phase
import com.marian1x.agesofsiege.core.StructureKind
import com.marian1x.agesofsiege.core.TowerSpecialization
import com.marian1x.agesofsiege.game.GameController
import com.marian1x.agesofsiege.game.InputMode
import com.marian1x.agesofsiege.game.SelectedInfo
import com.marian1x.agesofsiege.game.UiSound
import com.marian1x.agesofsiege.core.TargetPriority
import androidx.compose.ui.draw.scale
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

internal val PanelBg = Color(0xE01A1F2B)
internal val PanelBorder = Color(0x40FFFFFF)
internal val Gold = Color(0xFFFFC94D)
internal val Danger = Color(0xFFFF5A4A)
internal val TextDim = Color(0xB3FFFFFF)

/** Lilita One (SIL Open Font License) – chunky display font for titles and numbers. */
internal val GameFont = FontFamily(Font(R.font.lilita_one))

@Composable
fun GameScreen(c: GameController) {
    val hud = c.hud
    BackHandler(enabled = hud.screen != Screen.MENU || hud.overlay != Overlay.NONE) {
        when {
            hud.overlay == Overlay.RESULTS -> c.leaveBattle()
            hud.overlay == Overlay.LEADERBOARD -> hud.overlay = if (c.battle?.finished == true) Overlay.RESULTS else Overlay.NONE
            hud.overlay == Overlay.SETTINGS || hud.overlay == Overlay.NEW_GAME || hud.overlay == Overlay.CONFIRM_RESTART ->
                hud.overlay = if (hud.screen == Screen.MENU || hud.screen == Screen.CAMPAIGN) Overlay.NONE else Overlay.PAUSE
            hud.overlay == Overlay.MULTIPLAYER -> {
                c.cancelMatch()
                hud.overlay = Overlay.NONE
            }
            hud.overlay != Overlay.NONE -> hud.overlay = Overlay.NONE
            hud.screen == Screen.WORLD -> c.openBase()
            hud.screen == Screen.CAMPAIGN -> c.toMainMenu()
            else -> hud.overlay = Overlay.PAUSE
        }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { c.r3d.surfaceView }, modifier = Modifier.fillMaxSize())
        when (hud.screen) {
            Screen.GAME -> {
                // Camera gestures + taps on the 3D world.
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            // panZoomLock: a pinch never turns into an accidental rotation.
                            detectTransformGestures(panZoomLock = true) { centroid, pan, zoom, rotation ->
                                if (pan != Offset.Zero) c.onPan(centroid.x, centroid.y, pan.x, pan.y)
                                if (zoom != 1f) c.onZoom(zoom)
                                if (rotation != 0f) c.onRotate(rotation)
                            }
                        }
                        .pointerInput(Unit) { detectTapGestures(onTap = { c.onTap(it.x, it.y) }) }
                )
                WorldOverlay(c)
                Hud(c)
            }
            Screen.WORLD -> WorldMapScreen(c)
            Screen.MENU -> MainMenu(c)
            Screen.CAMPAIGN -> CampaignMapScreen(c)
        }
        when (hud.overlay) {
            Overlay.PAUSE -> PauseMenu(c)
            Overlay.SETTINGS -> SettingsPanel(c)
            Overlay.NEW_GAME -> NewGamePanel(c)
            Overlay.CONFIRM_RESTART -> ConfirmRestart(c)
            Overlay.ARMY -> ArmyPanel(c)
            Overlay.MULTIPLAYER -> MultiplayerPanel(c)
            Overlay.SPECIALIZATION -> hud.selected?.let { SpecializationDialog(c, it) }
            Overlay.BRIEFING -> hud.briefing?.let { BriefingDialog(c, it) }
            Overlay.RESULTS -> hud.summary?.let { ResultsDialog(c, it) }
            Overlay.PERKS -> PerksDialog(c)
            Overlay.QUESTS -> QuestsDialog(c)
            Overlay.DAILY -> DailyDialog(c)
            Overlay.LEADERBOARD -> LeaderboardDialog(c)
            Overlay.NONE -> Unit
        }
        if (hud.screen != Screen.MENU) {
            WaitingForFriend(c)
            Toasts(c)
        }
    }
}

/** Short messages (rejections, reports) – shown on every in-game screen. */
@Composable
private fun Toasts(c: GameController) {
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(top = 94.dp), contentAlignment = Alignment.TopCenter) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (t in c.hud.toasts) {
                LaunchedEffect(t.id) {
                    delay(3.seconds)
                    c.dismissToast(t)
                }
                Text(
                    t.text, color = Color(t.color), fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xE0101420)).padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ overlay (health bars, numbers)

@Composable
private fun WorldOverlay(c: GameController) {
    val textPaint = remember {
        Paint().apply {
            isAntiAlias = true
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            setShadowLayer(4f, 0f, 2f, android.graphics.Color.argb(200, 0, 0, 0))
        }
    }
    val pos = remember { FloatArray(2) }
    Canvas(Modifier.fillMaxSize()) {
        c.frame.intValue // redraw every frame
        val r = c.r3d
        val g = c.game
        val barW = 30.dp.toPx()
        val barH = 4.5.dp.toPx()
        for (e in g.enemies) {
            if (!e.alive || e.hp >= e.maxHp) continue
            if (!r.worldToScreen(e.x, GameController.enemyVisualScale(e.def) * 1.05f + 0.12f, e.y, pos)) continue
            val w = barW * (0.8f + 0.25f * e.def.scale)
            val left = pos[0] - w / 2
            drawRoundRect(Color(0xCC000000), Offset(left - 1.5f, pos[1] - 1.5f), Size(w + 3f, barH + 3f), CornerRadius(3f))
            val f = (e.hp / e.maxHp).coerceIn(0f, 1f)
            val col = if (f > 0.5f) Color(0xFF6EE06A) else if (f > 0.25f) Color(0xFFFFC94D) else Danger
            drawRoundRect(col, Offset(left, pos[1]), Size(w * f, barH), CornerRadius(2f))
            if (e.slowTimer > 0f) {
                val marker = Offset(left - barH, pos[1] + barH * 0.5f)
                drawCircle(Color(0xCC092031), barH * 0.72f, marker)
                drawCircle(Color(0xFFA0EAFF), barH * 0.44f, marker)
            }
        }
        // Veterancy pips above towers.
        for (s in g.structures.values) {
            if (s.kind != StructureKind.TOWER || s.rank < 2) continue
            if (!r.worldToScreen(s.cx, c.markerHeight(s), s.cy, pos)) continue
            val n = s.rank - 1
            val gap = 7.dp.toPx()
            for (i in 0 until n) {
                val x = pos[0] + (i - (n - 1) / 2f) * gap
                drawChevron(Offset(x, pos[1]), 3.2.dp.toPx())
            }
        }
        for (t in c.floatingTexts) {
            if (!r.worldToScreen(t.x, t.y, t.z, pos)) continue
            val a = (t.life / t.maxLife).coerceIn(0f, 1f)
            textPaint.color = t.color
            textPaint.alpha = (255 * minOf(1f, a * 2f)).toInt()
            textPaint.textSize = (if (t.big) 20.sp else 13.sp).toPx()
            drawContext.canvas.nativeCanvas.drawText(t.text, pos[0], pos[1], textPaint)
        }
        // Coins fly from fallen raiders to the gold counter (top-left).
        val target = Offset(36.dp.toPx(), 28.dp.toPx())
        val coinR = 5.dp.toPx()
        for (f in c.coinFlies) {
            val t = f.t.coerceIn(0f, 1f)
            val e = t * t
            val start = Offset(f.x, f.y)
            val ctrl = Offset(f.x + (target.x - f.x) * 0.2f, minOf(f.y, target.y) - 80f)
            val p = start * ((1 - e) * (1 - e)) + ctrl * (2 * e * (1 - e)) + target * (e * e)
            drawCircle(Color(0xFFB8860B), coinR, p)
            drawCircle(Gold, coinR * 0.75f, p)
        }
    }
}

private fun DrawScope.drawChevron(center: Offset, s: Float) {
    val p = Path().apply {
        moveTo(center.x - s, center.y - s * 0.4f)
        lineTo(center.x, center.y + s * 0.6f)
        lineTo(center.x + s, center.y - s * 0.4f)
    }
    drawPath(p, Color(0xFF000000), style = Stroke(width = s * 1.2f, cap = StrokeCap.Round))
    drawPath(p, Gold, style = Stroke(width = s * 0.6f, cap = StrokeCap.Round))
}

// ------------------------------------------------------------------ HUD

@Composable
private fun Hud(c: GameController) {
    val hud = c.hud
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(10.dp)) {
        if (hud.intro) {
            Text(
                "${hud.battleName.uppercase()}\nTap to skip", color = Color.White, fontFamily = GameFont, fontSize = 26.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(14.dp)).background(Color(0x88101420)).padding(16.dp),
            )
            return@Box
        }
        // Top-left resources.
        Column(Modifier.align(Alignment.TopStart)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ResourceChip(ResourceKind.GOLD, hud.gold, pulse = hud.goldPulse)
                if (!hud.goldOnly) {
                    ResourceChip(ResourceKind.WOOD, hud.wood)
                    ResourceChip(ResourceKind.STONE, hud.stone)
                }
            }
            MatchBadge(c, Modifier.padding(top = 6.dp))
            RacePanel(c, Modifier.padding(top = 6.dp))
        }
        // Top-center wave banner.
        Column(Modifier.align(Alignment.TopCenter), horizontalAlignment = Alignment.CenterHorizontally) {
            WaveBanner(c, Modifier)
            BossBar(c, Modifier.padding(top = 6.dp))
        }
        // Top-right controls.
        Column(Modifier.align(Alignment.TopEnd), horizontalAlignment = Alignment.End) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!c.inMatch) {
                    RoundButton(if (hud.paused) "▶" else "❚❚", onClick = c::togglePause)
                    RoundButton("${hud.speed}×", onClick = c::toggleSpeed, highlight = hud.speed > 1)
                }
                RoundButton("☰", onClick = { hud.overlay = Overlay.PAUSE })
            }
            Spacer(Modifier.height(6.dp))
            CameraControls(c)
            Spacer(Modifier.height(8.dp))
            if (!hud.inBattle) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NavButton("ARMY ${hud.garrison.values.sum() + hud.away}/${hud.armyCap}") { hud.overlay = Overlay.ARMY }
                NavButton(if (hud.expeditionCount > 0) "REALM •${hud.expeditionCount}" else "REALM") { c.openRealm() }
            }
            if (updateWaiting) {
                Spacer(Modifier.height(6.dp))
                NavButton("⬆ UPDATE") { hud.overlay = Overlay.SETTINGS }
            }
        }
        Column(Modifier.align(Alignment.BottomStart), horizontalAlignment = Alignment.CenterHorizontally) {
            val mode = hud.mode
            if (mode !is InputMode.Normal) {
                val text = when (mode) {
                    is InputMode.Build -> "Tap a tile to build ${nameOf(mode.defId)}"
                    is InputMode.Cast -> "Tap the battlefield: ${mode.ability.description}"
                    InputMode.Normal -> ""
                }
                Text(
                    text, color = Color.White, fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 6.dp)
                        .clip(RoundedCornerShape(10.dp)).background(Color(0xD0101420)).padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
            BuildBar(c, Modifier)
        }
        AbilityBar(c, Modifier.align(Alignment.BottomEnd))
        AnimatedVisibility(
            visible = hud.selected != null,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
        ) {
            hud.selected?.let { SelectionPanel(c, it) }
        }
    }
}

private fun nameOf(defId: String) =
    if (Content.isTower(defId)) Content.tower(defId).name else Content.building(defId).name

@Composable
internal fun NavButton(label: String, onClick: () -> Unit) {
    Text(
        label, fontFamily = GameFont, fontSize = 15.sp, color = Color.White,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(PanelBg)
            .border(1.dp, Gold.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .clickable { UiSound.click(); onClick() }.padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
internal fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(PanelBg)
            .border(1.dp, PanelBorder, RoundedCornerShape(14.dp))
    ) { content() }
}

enum class ResourceKind { GOLD, WOOD, STONE }

@Composable
private fun ResourceChip(kind: ResourceKind, amount: Int, pulse: Int = 0) {
    // The gold coin bounces when flying coins arrive.
    val bounce = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(pulse) {
        if (pulse == 0) return@LaunchedEffect
        bounce.snapTo(1.35f)
        bounce.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.4f, stiffness = 600f))
    }
    Panel {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            ResourceIcon(kind, Modifier.size(18.dp).scale(bounce.value))
            Spacer(Modifier.width(6.dp))
            Text("$amount", color = Color.White, fontFamily = GameFont, fontSize = 18.sp)
        }
    }
}

@Composable
fun ResourceIcon(kind: ResourceKind, modifier: Modifier) {
    Canvas(modifier) {
        val s = size.minDimension
        when (kind) {
            ResourceKind.GOLD -> {
                drawCircle(Color(0xFFB8860B), s / 2)
                drawCircle(Gold, s / 2 * 0.82f)
                drawCircle(Color(0xFFFFE9A8), s / 2 * 0.45f, center = Offset(s * 0.42f, s * 0.42f))
            }
            ResourceKind.WOOD -> {
                drawRoundRect(Color(0xFF7A4E28), Offset(0f, s * 0.22f), Size(s, s * 0.56f), CornerRadius(s * 0.28f))
                drawCircle(Color(0xFFE2B57E), s * 0.24f, center = Offset(s * 0.78f, s * 0.5f))
                drawCircle(Color(0xFFB07A44), s * 0.12f, center = Offset(s * 0.78f, s * 0.5f))
            }
            ResourceKind.STONE -> {
                val p = Path().apply {
                    moveTo(s * 0.1f, s * 0.8f); lineTo(s * 0.25f, s * 0.3f); lineTo(s * 0.6f, s * 0.15f)
                    lineTo(s * 0.92f, s * 0.45f); lineTo(s * 0.85f, s * 0.85f); close()
                }
                drawPath(p, Color(0xFF8D939B))
                drawLine(Color(0xFFD7DBE0), Offset(s * 0.3f, s * 0.35f), Offset(s * 0.6f, s * 0.22f), strokeWidth = s * 0.08f)
            }
        }
    }
}

@Composable
private fun WaveBanner(c: GameController, modifier: Modifier) {
    val hud = c.hud
    Panel(modifier.widthIn(min = 250.dp)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (hud.totalWaves > 0) "WAVE ${hud.wave}/${hud.totalWaves}" else "WAVE ${hud.wave}", color = Gold, fontFamily = GameFont, fontSize = 20.sp)
                Spacer(Modifier.width(10.dp))
                if (hud.phase == Phase.PREP) {
                    Text("in ${hud.countdown}s", color = TextDim, fontSize = 14.sp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "CALL NOW +${hud.countdown}",
                        color = Color(0xFF1A1F2B), fontWeight = FontWeight.Bold, fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Gold)
                            .clickable { c.callWave() }.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                } else {
                    Text("${hud.enemiesLeft} raiders on the field", color = Color(0xFFFF9B7A), fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(5.dp))
            // Keep HP.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("KEEP", color = TextDim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                val f = (hud.keepHp / hud.keepMax.toFloat()).coerceIn(0f, 1f)
                Box(Modifier.width(170.dp).height(7.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x55000000))) {
                    Box(
                        Modifier.fillMaxHeight().fillMaxWidth(f).clip(RoundedCornerShape(4.dp))
                            .background(Brush.horizontalGradient(listOf(Color(0xFF4FC3F7), Color(0xFF7CFC9A))))
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text("${hud.keepHp}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                if (hud.inBattle) {
                    Spacer(Modifier.width(10.dp))
                    StarTracker(c)
                }
            }
            if (hud.phase == Phase.PREP && hud.preview.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Incoming: " + hud.preview.joinToString("  ") { (id, n) -> "${Content.enemy(id).name} ×$n" },
                    color = TextDim, fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
internal fun RoundButton(
    label: String,
    onClick: () -> Unit,
    highlight: Boolean = false,
    actionLabel: String? = null,
) {
    Box(
        Modifier.size(42.dp).clip(CircleShape)
            .background(if (highlight) Gold else PanelBg)
            .border(1.dp, PanelBorder, CircleShape)
            .clickable(onClickLabel = actionLabel, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (highlight) Color(0xFF1A1F2B) else Color.White, fontFamily = GameFont, fontSize = 16.sp)
    }
}

@Composable
internal fun CameraControls(c: GameController) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        RoundButton("↶", onClick = { c.onRotate(45f) }, actionLabel = "Rotate view left")
        RoundButton("N", onClick = c::resetCameraRotation, highlight = !c.cameraRotated, actionLabel = "Reset view to north")
        RoundButton("↷", onClick = { c.onRotate(-45f) }, actionLabel = "Rotate view right")
    }
}

private val defenseCards = Content.towers.map { it.id }
private val townCards = listOf("barracks", "house", "lumber", "quarry")

@Composable
private fun BuildBar(c: GameController, modifier: Modifier) {
    val hud = c.hud
    var town by remember { mutableStateOf(false) }
    if (hud.inBattle) town = false
    Row(
        modifier.padding(end = (70 * hud.abilities.size + 22).dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!hud.inBattle) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BuildCategory("Defense", !town) {
                if (town && hud.mode is InputMode.Build) c.selectBuild(null)
                town = false
            }
            BuildCategory("Town", town) {
                if (!town && hud.mode is InputMode.Build) c.selectBuild(null)
                town = true
            }
        }
        key(town) {
            Row(
                Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (id in if (town) townCards else hud.towers.ifEmpty { defenseCards }) {
                    val raw = if (town) Content.building(id).cost else Content.tower(id).cost
                    val cost = if (hud.goldOnly) raw.toGold() else raw
                    val selected = (hud.mode as? InputMode.Build)?.defId == id
                    val affordable = hud.gold >= cost.gold && hud.wood >= cost.wood && hud.stone >= cost.stone
                    BuildCard(id, cost, selected, affordable) { c.selectBuild(id) }
                }
            }
        }
    }
}

@Composable
private fun BuildCategory(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.width(68.dp).clip(shape).background(if (selected) Gold else PanelBg)
            .border(1.dp, PanelBorder, shape).clickable(onClick = onClick).padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) Color(0xFF211B13) else Color.White, fontFamily = GameFont, fontSize = 13.sp)
    }
}

@Composable
private fun BuildCard(id: String, cost: Cost, selected: Boolean, affordable: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.width(96.dp).clip(shape)
            .background(if (selected) Color(0xF03A3218) else PanelBg)
            .border(if (selected) 2.dp else 1.dp, if (selected) Gold else PanelBorder, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BuildIcon(id, Modifier.size(48.dp))
        Text(
            nameOf(id).replace(" Tower", "").replace("Stone ", ""),
            color = if (affordable) Color.White else TextDim, fontSize = 13.sp, fontFamily = GameFont, maxLines = 1,
        )
        val role = when (id) {
            "archer" -> "Light infantry"
            "ballista", "repeater" -> "Anti-armor"
            "catapult", "brazier" -> "Area damage"
            "frost" -> "Slows raiders"
            "banner" -> "Boosts towers"
            "wall" -> "Blocks paths"
            "barracks" -> "Train troops"
            "house" -> "Gold income"
            "lumber" -> "Wood income"
            else -> "Stone income"
        }
        Text(role, color = TextDim, fontSize = 9.sp, maxLines = 1)
        CostRow(cost, affordable)
    }
}

@Composable
internal fun CostRow(cost: Cost, affordable: Boolean, fontSize: Int = 10) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        val col = if (affordable) Color.White else Danger.copy(alpha = 0.9f)
        if (cost.gold > 0) { ResourceIcon(ResourceKind.GOLD, Modifier.size(9.dp)); Text("${cost.gold}", color = col, fontSize = fontSize.sp) }
        if (cost.wood > 0) { ResourceIcon(ResourceKind.WOOD, Modifier.size(9.dp)); Text("${cost.wood}", color = col, fontSize = fontSize.sp) }
        if (cost.stone > 0) { ResourceIcon(ResourceKind.STONE, Modifier.size(9.dp)); Text("${cost.stone}", color = col, fontSize = fontSize.sp) }
    }
}

/** Build-card icons rendered from the real 3D models by tools/blender/render_previews.py. */
@Composable
private fun BuildIcon(id: String, modifier: Modifier) {
    if (id == "frost" || id == "brazier" || id == "repeater" || id == "banner") {
        NewTowerIcon(id, modifier)
        return
    }
    val res = when (id) {
        "archer" -> R.drawable.ic_build_archer
        "ballista" -> R.drawable.ic_build_ballista
        "catapult" -> R.drawable.ic_build_catapult
        "wall" -> R.drawable.ic_build_wall
        "house" -> R.drawable.ic_build_house
        "barracks" -> R.drawable.ic_build_barracks
        "lumber" -> R.drawable.ic_build_lumber
        else -> R.drawable.ic_build_quarry
    }
    Image(painterResource(res), contentDescription = nameOf(id), modifier = modifier)
}

@Composable
private fun SelectionPanel(c: GameController, s: SelectedInfo) {
    Panel(Modifier.width(220.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.name, color = Color.White, fontFamily = GameFont, fontSize = 19.sp, modifier = Modifier.weight(1f))
                Text("✕", color = TextDim, fontSize = 16.sp, modifier = Modifier.clickable { c.deselect() }.padding(4.dp))
            }
            if (s.maxLevel > 1) Text("Level ${s.level}/${s.maxLevel}", color = TextDim, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Text(s.stats, color = Color.White, fontSize = 12.sp)
            if (s.kind == StructureKind.TOWER) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Rank ${GameController.roman(s.rank)}", color = Gold, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("${s.kills} kills", color = TextDim, fontSize = 12.sp)
                }
                val next = s.nextRankXp
                val prev = Content.rankXp[(s.rank - 1).coerceIn(0, Content.rankXp.size - 1)]
                val f = if (next == null) 1f else (s.xp - prev).toFloat() / (next - prev)
                Spacer(Modifier.height(3.dp))
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x55000000))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(f.coerceIn(0f, 1f)).background(Gold))
                }
                Text(if (next == null) "Max veterancy" else "${s.xp}/$next XP", color = TextDim, fontSize = 10.sp)
                val specialization = s.specialization
                if (specialization != null) {
                    Text("${specialization.label} · ${specialization.benefit}", color = Gold, fontSize = 11.sp)
                } else if (s.level >= Content.SPECIALIZATION_LEVEL && !s.support) {
                    Spacer(Modifier.height(5.dp))
                    NavButton("SPECIALIZE") { c.hud.overlay = Overlay.SPECIALIZATION }
                }
                if (!s.support) {
                    Spacer(Modifier.height(6.dp))
                    Text("TARGET", color = TextDim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        for (p in TargetPriority.entries) {
                            val on = p == s.priority
                            Text(
                                p.label, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                color = if (on) Color(0xFF1A1F2B) else Color.White,
                                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (on) Gold else Color(0xFF2B3243))
                                    .clickable { c.setPriority(p) }.padding(horizontal = 5.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }
            if (s.kind != StructureKind.KEEP) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val up = s.upgradeCost
                    if (up != null) {
                        val hud = c.hud
                        val ok = hud.gold >= up.gold && hud.wood >= up.wood && hud.stone >= up.stone
                        ActionButton("LEVEL ${s.level + 1}", up, ok, Color(0xFF2E7D4F), Modifier.weight(1f)) { c.upgradeSelected() }
                    }
                    ActionButton("SELL", s.refund, true, Color(0xFF7D2E2E), Modifier.weight(1f)) { c.sellSelected() }
                }
            }
        }
    }
}

@Composable
private fun SpecializationDialog(c: GameController, s: SelectedInfo) {
    val cost = s.specCost
    val hud = c.hud
    val affordable = hud.gold >= cost.gold && hud.wood >= cost.wood && hud.stone >= cost.stone
    GameDialog("Specialize ${s.name}", onClose = { hud.overlay = Overlay.NONE }) {
        Text("Choose one permanent upgrade.", color = TextDim, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        CostRow(cost, affordable, fontSize = 13)
        for (choice in TowerSpecialization.entries) {
            Spacer(Modifier.height(9.dp))
            MenuButton("${choice.label} · ${choice.benefit}", Modifier.fillMaxWidth(), enabled = affordable) {
                c.specializeSelected(choice)
            }
        }
    }
}

@Composable
private fun ActionButton(label: String, cost: Cost, enabled: Boolean, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(10.dp))
            .background(if (enabled) color else color.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        CostRow(cost, enabled)
    }
}

