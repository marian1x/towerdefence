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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.marian1x.agesofsiege.core.Content
import com.marian1x.agesofsiege.core.Cost
import com.marian1x.agesofsiege.core.Phase
import com.marian1x.agesofsiege.core.StructureKind
import com.marian1x.agesofsiege.game.GameController
import com.marian1x.agesofsiege.game.InputMode
import com.marian1x.agesofsiege.game.SelectedInfo
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

private val PanelBg = Color(0xE01A1F2B)
private val PanelBorder = Color(0x40FFFFFF)
private val Gold = Color(0xFFFFC94D)
private val Danger = Color(0xFFFF5A4A)
private val TextDim = Color(0xB3FFFFFF)

@Composable
fun GameScreen(c: GameController) {
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { c.r3d.surfaceView }, modifier = Modifier.fillMaxSize())
        // Camera gestures + taps on the 3D world.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        if (pan != Offset.Zero) c.onPan(centroid.x, centroid.y, pan.x, pan.y)
                        if (zoom != 1f) c.onZoom(zoom)
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onTap = { c.onTap(it.x, it.y) }) }
        )
        WorldOverlay(c)
        Hud(c)
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
            if (!r.worldToScreen(e.x, 0.8f * e.def.scale * GameController.UNIT_VISUAL_SCALE + 0.2f, e.y, pos)) continue
            val w = barW * (0.8f + 0.25f * e.def.scale)
            val left = pos[0] - w / 2
            drawRoundRect(Color(0xCC000000), Offset(left - 1.5f, pos[1] - 1.5f), Size(w + 3f, barH + 3f), CornerRadius(3f))
            val f = (e.hp / e.maxHp).coerceIn(0f, 1f)
            val col = if (f > 0.5f) Color(0xFF6EE06A) else if (f > 0.25f) Color(0xFFFFC94D) else Danger
            drawRoundRect(col, Offset(left, pos[1]), Size(w * f, barH), CornerRadius(2f))
        }
        // Veterancy pips above towers.
        for (s in g.structures.values) {
            if (s.kind != StructureKind.TOWER || s.rank < 2) continue
            if (!r.worldToScreen(s.cx, 2.55f, s.cy, pos)) continue
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
        // Top-left resources.
        Row(Modifier.align(Alignment.TopStart), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ResourceChip(ResourceKind.GOLD, hud.gold)
            ResourceChip(ResourceKind.WOOD, hud.wood)
            ResourceChip(ResourceKind.STONE, hud.stone)
        }
        // Top-center wave banner.
        WaveBanner(c, Modifier.align(Alignment.TopCenter))
        // Top-right controls.
        Row(Modifier.align(Alignment.TopEnd), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RoundButton(if (hud.paused) "▶" else "❚❚", onClick = c::togglePause)
            RoundButton("${hud.speed}×", onClick = c::toggleSpeed, highlight = hud.speed > 1)
        }
        // Toasts.
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 84.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (t in hud.toasts) {
                LaunchedEffect(t.id) {
                    delay(2.6.seconds)
                    c.dismissToast(t)
                }
                Text(
                    t.text, color = Color(t.color), fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xC0101420)).padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
        }
        // Mode hint.
        val mode = hud.mode
        if (mode !is InputMode.Normal) {
            val text = when (mode) {
                is InputMode.Build -> "Tap tiles to build ${nameOf(mode.defId)} · tap the card again to stop"
                else -> "Tap the battlefield to fire an Arrow Volley"
            }
            Text(
                text, color = Color.White, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 104.dp)
                    .clip(RoundedCornerShape(10.dp)).background(Color(0xB0101420)).padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
        BuildBar(c, Modifier.align(Alignment.BottomStart))
        VolleyButton(c, Modifier.align(Alignment.BottomEnd))
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
private fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(PanelBg)
            .border(1.dp, PanelBorder, RoundedCornerShape(14.dp))
    ) { content() }
}

enum class ResourceKind { GOLD, WOOD, STONE }

@Composable
private fun ResourceChip(kind: ResourceKind, amount: Int) {
    Panel {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            ResourceIcon(kind, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("$amount", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
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
                Text("WAVE ${hud.wave}", color = Gold, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
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
                val f = hud.keepHp / Content.KEEP_MAX_HP.toFloat()
                Box(Modifier.width(170.dp).height(7.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x55000000))) {
                    Box(
                        Modifier.fillMaxHeight().fillMaxWidth(f).clip(RoundedCornerShape(4.dp))
                            .background(Brush.horizontalGradient(listOf(Color(0xFF4FC3F7), Color(0xFF7CFC9A))))
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text("${hud.keepHp}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
private fun RoundButton(label: String, onClick: () -> Unit, highlight: Boolean = false) {
    Box(
        Modifier.size(42.dp).clip(CircleShape)
            .background(if (highlight) Gold else PanelBg)
            .border(1.dp, PanelBorder, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (highlight) Color(0xFF1A1F2B) else Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

private val buildOptions = listOf("archer", "ballista", "catapult", "wall", "house", "lumber", "quarry")

@Composable
private fun BuildBar(c: GameController, modifier: Modifier) {
    val hud = c.hud
    Row(
        modifier.padding(end = 92.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (id in buildOptions) {
            val cost = if (Content.isTower(id)) Content.tower(id).cost else Content.building(id).cost
            val selected = (hud.mode as? InputMode.Build)?.defId == id
            val affordable = hud.gold >= cost.gold && hud.wood >= cost.wood && hud.stone >= cost.stone
            BuildCard(id, cost, selected, affordable) { c.selectBuild(id) }
        }
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
        BuildIcon(id, Modifier.size(30.dp))
        Text(
            nameOf(id).replace(" Tower", "").replace("Stone ", ""),
            color = if (affordable) Color.White else TextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        )
        CostRow(cost, affordable)
    }
}

@Composable
private fun CostRow(cost: Cost, affordable: Boolean, fontSize: Int = 10) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        val col = if (affordable) Color.White else Danger.copy(alpha = 0.9f)
        if (cost.gold > 0) { ResourceIcon(ResourceKind.GOLD, Modifier.size(9.dp)); Text("${cost.gold}", color = col, fontSize = fontSize.sp) }
        if (cost.wood > 0) { ResourceIcon(ResourceKind.WOOD, Modifier.size(9.dp)); Text("${cost.wood}", color = col, fontSize = fontSize.sp) }
        if (cost.stone > 0) { ResourceIcon(ResourceKind.STONE, Modifier.size(9.dp)); Text("${cost.stone}", color = col, fontSize = fontSize.sp) }
    }
}

/** Simple vector icons for the build cards. */
@Composable
private fun BuildIcon(id: String, modifier: Modifier) {
    Canvas(modifier) {
        val s = size.minDimension
        val stone = Color(0xFFB9BEC6)
        val wood = Color(0xFFB07A44)
        val roof = Color(0xFFD0603F)
        when (id) {
            "archer" -> {
                drawRect(wood, Offset(s * 0.3f, s * 0.35f), Size(s * 0.4f, s * 0.6f))
                drawPath(Path().apply { moveTo(s * 0.18f, s * 0.38f); lineTo(s * 0.5f, s * 0.02f); lineTo(s * 0.82f, s * 0.38f); close() }, roof)
            }
            "ballista" -> {
                drawRect(stone, Offset(s * 0.15f, s * 0.6f), Size(s * 0.7f, s * 0.35f))
                drawLine(wood, Offset(s * 0.5f, s * 0.15f), Offset(s * 0.5f, s * 0.6f), strokeWidth = s * 0.12f)
                drawArc(wood, 200f, 140f, false, Offset(s * 0.1f, s * 0.15f), Size(s * 0.8f, s * 0.5f), style = Stroke(s * 0.08f))
            }
            "catapult" -> {
                drawRect(wood, Offset(s * 0.1f, s * 0.6f), Size(s * 0.8f, s * 0.15f))
                drawLine(wood, Offset(s * 0.3f, s * 0.6f), Offset(s * 0.75f, s * 0.15f), strokeWidth = s * 0.09f)
                drawCircle(Color(0xFF7F8186), s * 0.13f, Offset(s * 0.78f, s * 0.15f))
                drawCircle(Color(0xFF6E4A2A), s * 0.1f, Offset(s * 0.25f, s * 0.82f))
                drawCircle(Color(0xFF6E4A2A), s * 0.1f, Offset(s * 0.75f, s * 0.82f))
            }
            "wall" -> {
                for (row in 0..2) for (col in 0..2) {
                    val off = if (row % 2 == 0) 0f else s * 0.15f
                    drawRect(stone, Offset(col * s * 0.32f - off + s * 0.04f, s * 0.2f + row * s * 0.26f), Size(s * 0.28f, s * 0.22f))
                }
            }
            "house" -> {
                drawRect(Color(0xFFECDFC2), Offset(s * 0.2f, s * 0.45f), Size(s * 0.6f, s * 0.5f))
                drawPath(Path().apply { moveTo(s * 0.1f, s * 0.48f); lineTo(s * 0.5f, s * 0.1f); lineTo(s * 0.9f, s * 0.48f); close() }, roof)
                drawRect(Color(0xFF6E4A2A), Offset(s * 0.42f, s * 0.65f), Size(s * 0.16f, s * 0.3f))
            }
            "lumber" -> {
                for (i in 0..2) {
                    val y = s * (0.25f + i * 0.22f)
                    drawRoundRect(Color(0xFF7A4E28), Offset(s * 0.08f, y), Size(s * 0.84f, s * 0.2f), CornerRadius(s * 0.1f))
                    drawCircle(Color(0xFFE2B57E), s * 0.09f, Offset(s * 0.82f, y + s * 0.1f))
                }
            }
            "quarry" -> {
                drawPath(Path().apply { moveTo(s * 0.05f, s * 0.95f); lineTo(s * 0.35f, s * 0.35f); lineTo(s * 0.6f, s * 0.55f); lineTo(s * 0.95f, s * 0.95f); close() }, Color(0xFF8D939B))
                drawLine(wood, Offset(s * 0.55f, s * 0.1f), Offset(s * 0.85f, s * 0.6f), strokeWidth = s * 0.08f)
                drawLine(Color(0xFFD7DBE0), Offset(s * 0.4f, s * 0.15f), Offset(s * 0.7f, s * 0.05f), strokeWidth = s * 0.1f)
            }
        }
    }
}

@Composable
private fun VolleyButton(c: GameController, modifier: Modifier) {
    val hud = c.hud
    val armed = hud.mode == InputMode.Volley
    val ready = hud.volleyReady >= 1f
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(76.dp).clip(CircleShape)
                .background(if (armed) Color(0xFF5A2A18) else PanelBg)
                .border(2.dp, if (ready) Gold else PanelBorder, CircleShape)
                .clickable { c.armVolley() },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(76.dp)) {
                val s = size.minDimension
                // Three falling arrows.
                for (i in -1..1) {
                    val x = s / 2 + i * s * 0.17f
                    drawLine(Color(0xFFE2C89A), Offset(x - s * 0.08f, s * 0.22f), Offset(x + s * 0.06f, s * 0.68f), strokeWidth = s * 0.04f)
                    drawCircle(Color(0xFFDCE3EA), s * 0.045f, Offset(x + s * 0.07f, s * 0.71f))
                }
                if (!ready) {
                    drawArc(Color(0xB0000000), -90f + 360f * hud.volleyReady, 360f * (1f - hud.volleyReady), true, Offset.Zero, size)
                }
            }
        }
        Text("VOLLEY", color = if (ready) Gold else TextDim, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun SelectionPanel(c: GameController, s: SelectedInfo) {
    Panel(Modifier.width(220.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.name, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
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
            }
            if (s.kind != StructureKind.KEEP) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val up = s.upgradeCost
                    if (up != null) {
                        val hud = c.hud
                        val ok = hud.gold >= up.gold && hud.wood >= up.wood && hud.stone >= up.stone
                        ActionButton("UPGRADE", up, ok, Color(0xFF2E7D4F), Modifier.weight(1f)) { c.upgradeSelected() }
                    }
                    ActionButton("SELL", s.refund, true, Color(0xFF7D2E2E), Modifier.weight(1f)) { c.sellSelected() }
                }
            }
        }
    }
}

@Composable
private fun ActionButton(label: String, cost: Cost, enabled: Boolean, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(10.dp))
            .background(if (enabled) color else color.copy(alpha = 0.4f))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        CostRow(cost, true)
    }
}

