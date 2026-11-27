package com.marian1x.agesofsiege.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marian1x.agesofsiege.core.Campaign
import com.marian1x.agesofsiege.core.Content
import com.marian1x.agesofsiege.core.Phase
import com.marian1x.agesofsiege.core.Poi
import com.marian1x.agesofsiege.core.PoiKind
import com.marian1x.agesofsiege.core.Terrain
import com.marian1x.agesofsiege.core.World
import com.marian1x.agesofsiege.game.GameController
import com.marian1x.agesofsiege.game.Screen
import kotlin.math.floor
import kotlin.math.min

private val Parchment = Color(0xFFE8D7B0)
private val Fog = Color(0xFFCDBB92)
private val Ink = Color(0xFF4A3B28)

private fun terrainColor(t: Terrain) = when (t) {
    Terrain.GRASS -> Color(0xFFA8C878)
    Terrain.FOREST -> Color(0xFF6E9E58)
    Terrain.HILLS -> Color(0xFFC2AE7E)
    Terrain.MOUNTAIN -> Color(0xFF9C978F)
    Terrain.WATER -> Color(0xFF79AECB)
}

@Composable
fun WorldMapScreen(c: GameController) {
    val hud = c.hud
    val camp = c.campaign
    var selected by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    Row(Modifier.fillMaxSize().background(Color(0xFF2A2118)).safeDrawingPadding()) {
        // ------------------------------------------------ map
        Box(Modifier.weight(1f).fillMaxHeight().padding(10.dp)) {
            Canvas(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures { p ->
                        val cell = min(size.width / World.W.toFloat(), size.height / World.H.toFloat())
                        val ox = (size.width - cell * World.W) / 2
                        val oy = (size.height - cell * World.H) / 2
                        val x = floor((p.x - ox) / cell).toInt()
                        val y = floor((p.y - oy) / cell).toInt()
                        if (x in 0 until World.W && y in 0 until World.H) selected = x to y
                    }
                }
            ) {
                c.frame.intValue // animate marching armies
                drawWorld(camp, selected)
            }
            Row(Modifier.align(Alignment.TopStart).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                MenuButton("← BASE") { hud.screen = Screen.GAME }
                Spacer(Modifier.width(10.dp))
                Text(
                    "THE REALM", fontFamily = GameFont, fontSize = 24.sp, color = Ink,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xCCE8D7B0)).padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
        // ------------------------------------------------ side panel
        Column(
            Modifier.width(320.dp).fillMaxHeight().background(Color(0xF01A1F2B)).padding(12.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(hud.settings.lordName, fontFamily = GameFont, fontSize = 20.sp, color = Gold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                ResourceIcon(ResourceKind.GOLD, Modifier.size(14.dp)); Text("${hud.gold}", color = Color.White, fontSize = 13.sp)
                ResourceIcon(ResourceKind.WOOD, Modifier.size(14.dp)); Text("${hud.wood}", color = Color.White, fontSize = 13.sp)
                ResourceIcon(ResourceKind.STONE, Modifier.size(14.dp)); Text("${hud.stone}", color = Color.White, fontSize = 13.sp)
            }
            Text(
                "Army: ${hud.garrison.values.sum()} at home · ${hud.away} away · cap ${hud.armyCap}",
                color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
            )
            if (hud.phase == Phase.ACTIVE) {
                Text("⚠ Wave ${hud.wave} is attacking your base!", color = Danger, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            } else {
                Text("Next wave in ${hud.countdown}s", color = TextDim, fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
            val sel = selected
            if (sel == null) {
                Text("Tap the map to choose a destination.", color = Color.White, fontSize = 14.sp)
            } else {
                SelectionCard(c, sel.first, sel.second) { selected = null }
            }
            RivalStatus(camp)
            if (sel == null) Legend()
            if (hud.reports.isNotEmpty()) {
                SectionLabel("Reports")
                for ((text, good) in hud.reports.take(8)) {
                    Text("• $text", color = if (good) Color(0xFF9CE8A8) else Color(0xFFFFC59A), fontSize = 12.sp, modifier = Modifier.padding(bottom = 3.dp))
                }
            }
        }
    }
}

@Composable
private fun RivalStatus(camp: Campaign) {
    val r = camp.rival
    val known = r.contact || camp.world.isExplored(camp.world.rivalHome.first, camp.world.rivalHome.second)
    SectionLabel("Rival")
    when {
        !known -> Text("A rival lord rules somewhere to the east. Explore to find them.", color = TextDim, fontSize = 12.sp)
        r.vassal -> Text("${r.name} is your vassal and pays tribute.", color = Color(0xFF9CE8A8), fontSize = 13.sp)
        else -> {
            Text(r.name, color = Color(0xFFFF8A7A), fontFamily = GameFont, fontSize = 16.sp)
            Text("Capital defense ⚔ ${r.defense.toInt()} · victories ${r.defeats}/3", color = Color.White, fontSize = 12.sp)
            if (r.contact) Text("Next rival attack in ~${(r.raidTimer / camp.settings.pace.mult).toInt() / 60 + 1} min", color = TextDim, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Legend() {
    SectionLabel("Legend")
    Text("🏰 Your capital  ·  🟥 Rival capital", color = TextDim, fontSize = 12.sp)
    Text("📦 Supply cache – free loot", color = TextDim, fontSize = 12.sp)
    Text("⛺ Marauder camp – fight for loot; each cleared camp weakens raids", color = TextDim, fontSize = 12.sp)
    Text("🏛 Ruins – guarded treasure", color = TextDim, fontSize = 12.sp)
}

@Composable
private fun SelectionCard(c: GameController, x: Int, y: Int, onDone: () -> Unit) {
    val camp = c.campaign
    val world = camp.world
    val hud = c.hud
    val explored = world.isExplored(x, y)
    val terrain = world.terrainAt(x, y)
    val poi = world.poiAt(x, y)?.takeIf { explored }
    val isHome = x == world.home.first && y == world.home.second
    val counts = remember(x, y) { mutableStateMapOf<String, Int>() }

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF232A39)).padding(10.dp)) {
        val title = when {
            isHome -> "Your capital"
            !explored -> "Unexplored land"
            poi != null -> if (poi.kind == PoiKind.RIVAL_CAPITAL) "${camp.rival.name}'s capital" else poi.kind.label
            else -> terrain.name.lowercase().replaceFirstChar { it.uppercase() }
        }
        Text(title, fontFamily = GameFont, fontSize = 18.sp, color = Color.White)
        when {
            isHome -> {
                Text("Your keep and its defenders.", color = TextDim, fontSize = 12.sp)
                return@Column
            }
            explored && !terrain.passable -> {
                Text("Impassable terrain.", color = TextDim, fontSize = 12.sp)
                return@Column
            }
        }
        if (poi != null) PoiDetails(camp, poi)
        else Text(if (explored) "Send scouts to reveal the land around it." else "Unknown. Send soldiers to scout.", color = TextDim, fontSize = 12.sp)
        if (poi?.cleared == true && poi.kind != PoiKind.RIVAL_CAPITAL) return@Column
        if (poi?.kind == PoiKind.RIVAL_CAPITAL && camp.rival.vassal) return@Column

        camp.etaSeconds(x, y)?.let { Text("March time: ~${it}s each way", color = TextDim, fontSize = 12.sp) }
        SectionLabel("Choose soldiers")
        if (hud.garrison.values.sum() == 0) {
            Text("No soldiers at home. Recruit at the Barracks (ARMY).", color = Color(0xFFFFC59A), fontSize = 12.sp)
            return@Column
        }
        for (u in Content.units) {
            val have = hud.garrison[u.id] ?: 0
            if (have == 0) continue
            val n = counts[u.id] ?: 0
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Image(painterResource(unitIcon(u.id)), u.name, Modifier.size(34.dp))
                Text("${u.name} ($have)", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                StepButton("−") { counts[u.id] = (n - 1).coerceAtLeast(0) }
                Text("$n", color = Gold, fontFamily = GameFont, fontSize = 16.sp, modifier = Modifier.width(28.dp).padding(horizontal = 6.dp))
                StepButton("+") { counts[u.id] = (n + 1).coerceAtMost(have) }
            }
        }
        val power = counts.entries.sumOf { (id, n) -> (Content.unit(id).power * n).toDouble() }.toInt()
        val defense = poi?.let { if (it.kind == PoiKind.RIVAL_CAPITAL) camp.rival.defense else it.power }?.toInt() ?: 0
        Text(
            "Squad power ⚔ $power" + if (defense > 0) "  vs  defense ⚔ $defense" else "",
            color = if (defense == 0 || power >= defense) Color(0xFF9CE8A8) else Color(0xFFFFC59A), fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MenuButton("ALL") { for (u in Content.units) counts[u.id] = hud.garrison[u.id] ?: 0 }
            MenuButton(if (poi != null) "MARCH" else "SCOUT", Modifier.weight(1f), primary = true, enabled = counts.values.sum() > 0) {
                if (c.sendExpedition(counts.filterValues { it > 0 }, poi?.id ?: -1, x, y)) onDone()
            }
        }
    }
}

@Composable
private fun PoiDetails(camp: Campaign, poi: Poi) {
    val lootText = listOfNotNull(
        poi.loot.gold.takeIf { it > 0 }?.let { "$it gold" },
        poi.loot.wood.takeIf { it > 0 }?.let { "$it wood" },
        poi.loot.stone.takeIf { it > 0 }?.let { "$it stone" },
    ).joinToString(", ")
    when (poi.kind) {
        PoiKind.CACHE -> Text(if (poi.cleared) "Already looted." else "Unguarded supplies: $lootText", color = TextDim, fontSize = 12.sp)
        PoiKind.CAMP, PoiKind.RUINS ->
            if (poi.cleared) Text("Cleared.", color = Color(0xFF9CE8A8), fontSize = 12.sp)
            else Text("Guarded (defense ⚔ ${poi.power.toInt()}). Loot: $lootText", color = TextDim, fontSize = 12.sp)
        PoiKind.RIVAL_CAPITAL -> Text(
            "Defense ⚔ ${camp.rival.defense.toInt()}. Win 3 assaults to make ${camp.rival.name} your vassal. Each victory plunders their treasury.",
            color = TextDim, fontSize = 12.sp,
        )
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(30.dp).clip(CircleShape).background(Color(0xFF39425A)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontSize = 16.sp) }
}

// ------------------------------------------------------------------ map drawing

private fun DrawScope.drawWorld(camp: Campaign, selected: Pair<Int, Int>?) {
    val world = camp.world
    val cell = min(size.width / World.W, size.height / World.H)
    val ox = (size.width - cell * World.W) / 2
    val oy = (size.height - cell * World.H) / 2
    drawRect(Parchment, Offset(ox - 6, oy - 6), Size(cell * World.W + 12, cell * World.H + 12))

    for (y in 0 until World.H) for (x in 0 until World.W) {
        val px = ox + x * cell
        val py = oy + y * cell
        if (!world.explored[world.idx(x, y)]) {
            val n = ((x * 73856093) xor (y * 19349663)) and 7
            drawRect(Fog, Offset(px, py), Size(cell + 0.5f, cell + 0.5f))
            if (n == 0) drawCircle(Color(0x22000000), cell * 0.12f, Offset(px + cell * 0.5f, py + cell * 0.5f))
            continue
        }
        val t = world.terrainAt(x, y)
        drawRect(terrainColor(t), Offset(px, py), Size(cell + 0.5f, cell + 0.5f))
        val cx = px + cell / 2
        val cy = py + cell / 2
        when (t) {
            Terrain.FOREST -> {
                tri(cx - cell * 0.18f, cy + cell * 0.05f, cell * 0.22f, Color(0xFF3F6E35))
                tri(cx + cell * 0.16f, cy + cell * 0.2f, cell * 0.2f, Color(0xFF4A7C3E))
            }
            Terrain.HILLS -> drawArc(Color(0xFF8C7A52), 200f, 140f, false, Offset(px + cell * 0.15f, cy - cell * 0.1f), Size(cell * 0.7f, cell * 0.5f), style = Stroke(cell * 0.08f))
            Terrain.MOUNTAIN -> {
                tri(cx, cy + cell * 0.25f, cell * 0.4f, Color(0xFF6E6A64))
                tri(cx, cy - cell * 0.02f, cell * 0.14f, Color(0xFFF2F2F2))
            }
            Terrain.WATER -> drawLine(Color(0xFFA9D0E4), Offset(px + cell * 0.2f, cy), Offset(px + cell * 0.8f, cy), strokeWidth = cell * 0.06f)
            Terrain.GRASS -> Unit
        }
    }
    // Rival territory (once known).
    val rival = camp.rival
    if (rival.contact && !rival.vassal) {
        drawCircle(
            Color(0x33D0453A), rival.explored * cell,
            Offset(ox + (world.rivalHome.first + 0.5f) * cell, oy + (world.rivalHome.second + 0.5f) * cell),
        )
    }
    // Points of interest.
    for (p in world.pois) {
        if (!world.explored[world.idx(p.x, p.y)] && !(p.kind == PoiKind.RIVAL_CAPITAL && rival.contact)) continue
        val c = Offset(ox + (p.x + 0.5f) * cell, oy + (p.y + 0.5f) * cell)
        val alpha = if (p.cleared && p.kind != PoiKind.RIVAL_CAPITAL) 0.4f else 1f
        when (p.kind) {
            PoiKind.CACHE -> {
                drawRect(Color(0xFF8A5A2B).copy(alpha = alpha), Offset(c.x - cell * 0.28f, c.y - cell * 0.18f), Size(cell * 0.56f, cell * 0.38f))
                drawRect(Gold.copy(alpha = alpha), Offset(c.x - cell * 0.28f, c.y - cell * 0.22f), Size(cell * 0.56f, cell * 0.12f))
            }
            PoiKind.CAMP -> tri(c.x, c.y + cell * 0.3f, cell * 0.36f, Color(0xFFB8432F).copy(alpha = alpha))
            PoiKind.RUINS -> for (i in -1..1) drawRect(
                Color(0xFF8E8A82).copy(alpha = alpha), Offset(c.x + i * cell * 0.2f - cell * 0.06f, c.y - cell * 0.25f), Size(cell * 0.12f, cell * 0.5f),
            )
            PoiKind.RIVAL_CAPITAL -> castle(c, cell, if (rival.vassal) Color(0xFF7A9E6A) else Color(0xFFC0392B))
        }
        if (p.cleared && p.kind != PoiKind.RIVAL_CAPITAL) {
            drawLine(Color(0xFF2E7D32), Offset(c.x - cell * 0.2f, c.y), Offset(c.x - cell * 0.05f, c.y + cell * 0.15f), strokeWidth = cell * 0.08f)
            drawLine(Color(0xFF2E7D32), Offset(c.x - cell * 0.05f, c.y + cell * 0.15f), Offset(c.x + cell * 0.25f, c.y - cell * 0.2f), strokeWidth = cell * 0.08f)
        }
    }
    castle(Offset(ox + (world.home.first + 0.5f) * cell, oy + (world.home.second + 0.5f) * cell), cell, Color(0xFF3F6FB5))

    // Marching armies.
    for (e in camp.expeditions) {
        val target = e.path.last()
        val dash = PathEffect.dashPathEffect(floatArrayOf(cell * 0.25f, cell * 0.2f))
        val path = Path()
        e.path.forEachIndexed { i, idx ->
            val p = Offset(ox + (idx % World.W + 0.5f) * cell, oy + (idx / World.W + 0.5f) * cell)
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        drawPath(path, Ink.copy(alpha = 0.6f), style = Stroke(cell * 0.07f, pathEffect = dash))
        val i0 = e.progress.toInt().coerceIn(0, e.path.size - 1)
        val i1 = (i0 + 1).coerceAtMost(e.path.size - 1)
        val f = e.progress - i0
        val a = e.path[i0]
        val b = e.path[i1]
        val pos = Offset(
            ox + ((a % World.W) * (1 - f) + (b % World.W) * f + 0.5f) * cell,
            oy + ((a / World.W) * (1 - f) + (b / World.W) * f + 0.5f) * cell,
        )
        drawCircle(Color(0xFF1A1F2B), cell * 0.42f, pos)
        drawCircle(if (e.returning) Color(0xFF7CFC9A) else Gold, cell * 0.32f, pos)
        drawLine(Color.White, Offset(pos.x, pos.y - cell * 0.6f), Offset(pos.x, pos.y), strokeWidth = cell * 0.06f)
        drawRect(Color(0xFF3F6FB5), Offset(pos.x, pos.y - cell * 0.6f), Size(cell * 0.35f, cell * 0.22f))
        val tx = ox + (target % World.W + 0.5f) * cell
        val ty = oy + (target / World.W + 0.5f) * cell
        drawCircle(Ink, cell * 0.5f, Offset(tx, ty), style = Stroke(cell * 0.05f))
    }
    // Selection.
    selected?.let { (x, y) ->
        drawRect(Color.White, Offset(ox + x * cell, oy + y * cell), Size(cell, cell), style = Stroke(cell * 0.1f))
    }
    drawRect(Ink, Offset(ox - 6, oy - 6), Size(cell * World.W + 12, cell * World.H + 12), style = Stroke(4f))
}

private fun DrawScope.tri(cx: Float, baseY: Float, h: Float, color: Color) {
    val p = Path().apply {
        moveTo(cx, baseY - h)
        lineTo(cx + h * 0.6f, baseY)
        lineTo(cx - h * 0.6f, baseY)
        close()
    }
    drawPath(p, color)
}

private fun DrawScope.castle(c: Offset, cell: Float, color: Color) {
    val w = cell * 0.9f
    val h = cell * 0.6f
    drawRect(color, Offset(c.x - w / 2, c.y - h / 2), Size(w, h))
    for (i in 0..2) drawRect(color, Offset(c.x - w / 2 + i * w * 0.4f, c.y - h / 2 - cell * 0.18f), Size(w * 0.2f, cell * 0.2f))
    drawRect(Color(0xFF2A2118), Offset(c.x - cell * 0.1f, c.y), Size(cell * 0.2f, h / 2))
    drawRect(Color(0xFF2A2118), Offset(c.x - w / 2, c.y - h / 2), Size(w, h), style = Stroke(cell * 0.05f))
}
