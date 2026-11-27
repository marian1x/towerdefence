package com.marian1x.agesofsiege.ui

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marian1x.agesofsiege.core.Campaign
import com.marian1x.agesofsiege.core.Content
import com.marian1x.agesofsiege.core.MatchMode
import com.marian1x.agesofsiege.core.Phase
import com.marian1x.agesofsiege.core.Poi
import com.marian1x.agesofsiege.core.PoiKind
import com.marian1x.agesofsiege.game.GameController

/**
 * The realm screen: the 3D realm (rendered by RealmView behind this UI) with camera gestures,
 * floating labels over landmarks, and the side panel for sending armies.
 */
@Composable
fun WorldMapScreen(c: GameController) {
    val camp = c.campaign
    Box(Modifier.fillMaxSize()) {
        // Camera gestures + taps on the 3D realm (full-screen so coordinates match the 3D view).
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures(panZoomLock = true) { centroid, pan, zoom, rotation ->
                        if (pan != Offset.Zero) c.onPan(centroid.x, centroid.y, pan.x, pan.y)
                        if (zoom != 1f) c.onZoom(zoom)
                        if (rotation != 0f) c.onRotate(rotation)
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onTap = { c.onTap(it.x, it.y) }) }
        )
        RealmLabels(c)
        Row(Modifier.fillMaxSize().safeDrawingPadding()) {
            Box(Modifier.weight(1f).fillMaxHeight().padding(10.dp)) {
                Column(Modifier.align(Alignment.TopStart)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MenuButton("← BASE") { c.openBase() }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "THE REALM", fontFamily = GameFont, fontSize = 22.sp, color = Gold,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(PanelBg).padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    CameraControls(c)
                }
                Text(
                    "Tap a region · drag to move · pinch in over your castle to return",
                    color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.BottomStart)
                        .clip(RoundedCornerShape(10.dp)).background(Color(0xB0101420)).padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            SidePanel(c, camp)
        }
    }
}

@Composable
private fun SidePanel(c: GameController, camp: Campaign) {
    val hud = c.hud
    Column(
        Modifier.width(300.dp).fillMaxHeight().background(Color(0xF01A1F2B)).padding(12.dp)
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
        val sel = hud.realmSel
        if (sel == null) {
            Text("Tap the land to choose a destination.", color = Color.White, fontSize = 14.sp)
        } else {
            SelectionCard(c, sel.first, sel.second) { hud.realmSel = null }
        }
        if (camp.mp != null) MatchStatus(c) else RivalStatus(camp)
        if (sel == null) Legend()
        if (hud.reports.isNotEmpty()) {
            SectionLabel("Reports")
            for ((text, good) in hud.reports.take(8)) {
                Text("• $text", color = if (good) Color(0xFF9CE8A8) else Color(0xFFFFC59A), fontSize = 12.sp, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}

/** Name / strength tags floating above landmarks and marching armies. */
@Composable
private fun RealmLabels(c: GameController) {
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val paint = remember {
        Paint().apply {
            isAntiAlias = true
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = 12f * density
        }
    }
    val bg = remember { Paint().apply { isAntiAlias = true } }
    val rect = remember { RectF() }
    val pos = remember { FloatArray(2) }
    Canvas(Modifier.fillMaxSize()) {
        c.frame.intValue // redraw every frame
        val camp = c.campaign
        val world = camp.world
        val r3d = c.r3d
        if (!r3d.showingRealm) return@Canvas
        val canvas = drawContext.canvas.nativeCanvas

        fun tag(x: Float, y: Float, z: Float, text: String, color: Int, fill: Int = 0xCC101420.toInt()) {
            if (!r3d.worldToScreen(x, y, z, pos)) return
            val w = paint.measureText(text) / 2 + 7f * density
            rect.set(pos[0] - w, pos[1] - 15f * density, pos[0] + w, pos[1] + 4f * density)
            bg.color = fill
            canvas.drawRoundRect(rect, 8f * density, 8f * density, bg)
            paint.color = color
            canvas.drawText(text, pos[0], pos[1] - 1f * density, paint)
        }

        val home = c.realm.labelAnchor(world.home.first, world.home.second, 2.9f)
        val view = camp.mp
        tag(home[0], home[1], home[2], view?.me?.name ?: c.hud.settings.lordName, 0xFF9FC8FF.toInt())
        world.allyHome?.let { (ax, ay) ->
            val a = c.realm.labelAnchor(ax, ay, 2.9f)
            tag(a[0], a[1], a[2], "🤝 ${view?.other?.name ?: "Ally"}", 0xFF7FB8FF.toInt())
        }
        for (p in world.pois) {
            val visible = world.isExplored(p.x, p.y) || (p.kind == PoiKind.RIVAL_CAPITAL && camp.rival.contact)
            if (!visible) continue
            if (p.cleared && p.kind != PoiKind.RUINS && p.kind != PoiKind.RIVAL_CAPITAL) continue
            val a = c.realm.labelAnchor(p.x, p.y, if (p.kind == PoiKind.RIVAL_CAPITAL) 3.0f else 1.4f)
            when (p.kind) {
                PoiKind.CACHE -> tag(a[0], a[1], a[2], "Supplies", 0xFFFFD34D.toInt())
                PoiKind.CAMP -> tag(a[0], a[1], a[2], "Camp ⚔${p.power.toInt()}", 0xFFFFB0A0.toInt())
                PoiKind.RUINS -> if (p.cleared) tag(a[0], a[1], a[2], "Ruins ✓", 0xFF9CE8A8.toInt())
                else tag(a[0], a[1], a[2], "Ruins ⚔${p.power.toInt()}", 0xFFE0D0FF.toInt())
                PoiKind.RIVAL_CAPITAL -> if (camp.rival.vassal) tag(a[0], a[1], a[2], "${camp.rival.name} (vassal)", 0xFF9CE8A8.toInt())
                else tag(a[0], a[1], a[2], "${camp.rival.name} ⚔${camp.rival.defense.toInt()}", 0xFFFF8A7A.toInt(), 0xCC3A1010.toInt())
            }
        }
        for (e in camp.expeditions) {
            val a = c.realm.armyAnchor(e)
            val power = e.troops.sumOf { Content.troopPower(it).toDouble() }.toInt()
            val mine = view == null || e.owner == view.mySlot
            if (!mine && view != null && !world.isExplored(e.path[e.progress.toInt().coerceIn(0, e.path.size - 1)] % com.marian1x.agesofsiege.core.World.W, e.path[e.progress.toInt().coerceIn(0, e.path.size - 1)] / com.marian1x.agesofsiege.core.World.W)) continue
            val owner = if (mine) "" else "${view?.players?.getOrNull(e.owner)?.name ?: "?"} "
            val color = when {
                !mine && view?.mode == MatchMode.VERSUS -> 0xFFFF8A7A.toInt()
                !mine -> 0xFF9FC8FF.toInt()
                e.returning -> 0xFF9CE8A8.toInt()
                else -> 0xFFFFD34D.toInt()
            }
            val state = if (e.besieging) "⚔ sieging " else if (e.returning) "↩ " else ""
            tag(a[0], a[1], a[2], "$owner$state${e.troops.size} ⚔$power", color)
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

/** Multiplayer: the other player's status (and the AI rival in co-op). */
@Composable
private fun MatchStatus(c: GameController) {
    val camp = c.campaign
    val view = camp.mp ?: return
    val other = view.other
    val connected = c.mp?.peerConnected == true
    SectionLabel(if (view.mode == MatchMode.VERSUS) "Opponent" else "Ally")
    if (other != null) {
        Text(
            other.name, fontFamily = GameFont, fontSize = 16.sp,
            color = if (view.mode == MatchMode.VERSUS) Color(0xFFFF8A7A) else Color(0xFF9FC8FF),
        )
        Text("Capital ⚔ ${other.defense.toInt()} · ${other.army} soldiers home · wave ${other.wave}", color = Color.White, fontSize = 12.sp)
        Text(if (connected) "● connected" else "○ not connected – game paused", color = if (connected) Color(0xFF9CE8A8) else Color(0xFFFFC59A), fontSize = 12.sp)
    }
    if (view.mode == MatchMode.COOP) {
        val r = camp.rival
        SectionLabel("Common enemy")
        Text(r.name, color = Color(0xFFFF8A7A), fontFamily = GameFont, fontSize = 16.sp)
        Text(if (r.vassal) "Defeated – you won together!" else "Capital defense ⚔ ${r.defense.toInt()} · victories ${r.defeats}/3", color = Color.White, fontSize = 12.sp)
    }
}

@Composable
private fun Legend() {
    SectionLabel("Legend")
    Text("🏰 Your capital  ·  🟥 Rival capital", color = TextDim, fontSize = 12.sp)
    Text("📦 Supply cache – free loot", color = TextDim, fontSize = 12.sp)
    Text("⛺ Marauder camp – fight for loot; each cleared camp weakens raids", color = TextDim, fontSize = 12.sp)
    Text("🏛 Ruins – guarded treasure", color = TextDim, fontSize = 12.sp)
    Text("☁ Clouds hide unexplored land – send scouts to lift them", color = TextDim, fontSize = 12.sp)
}

@Composable
private fun SelectionCard(c: GameController, x: Int, y: Int, onDone: () -> Unit) {
    val camp = c.campaign
    val world = camp.world
    val hud = c.hud
    val explored = world.isExplored(x, y)
    val terrain = world.terrainAt(x, y)
    val poi = world.poiAt(x, y)?.takeIf { explored || (it.kind == PoiKind.RIVAL_CAPITAL && camp.rival.contact) }
    val isHome = x == world.home.first && y == world.home.second
    val isAlly = world.allyHome == (x to y)
    val view = camp.mp
    val counts = remember(x, y) { mutableStateMapOf<String, Int>() }

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF232A39)).padding(10.dp)) {
        val title = when {
            isHome -> "Your capital"
            isAlly -> "${view?.other?.name ?: "Ally"}'s capital"
            poi != null -> if (poi.kind == PoiKind.RIVAL_CAPITAL) "${camp.rival.name}'s capital" else poi.kind.label
            !explored -> "Unexplored land"
            else -> terrain.name.lowercase().replaceFirstChar { it.uppercase() }
        }
        Text(title, fontFamily = GameFont, fontSize = 18.sp, color = Color.White)
        when {
            isHome -> {
                Text("Your keep and its defenders.", color = TextDim, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                MenuButton("ENTER BASE", Modifier.fillMaxWidth(), primary = true) { c.openBase() }
                return@Column
            }
            explored && !terrain.passable -> {
                Text("Impassable terrain.", color = TextDim, fontSize = 12.sp)
                return@Column
            }
            isAlly -> {
                Text("Your ally. Send soldiers to join their garrison, or share resources.", color = TextDim, fontSize = 12.sp)
                SectionLabel("Send supplies")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MenuButton("50 gold") { c.sendSupplies(com.marian1x.agesofsiege.core.Cost(gold = 50)) }
                    MenuButton("50 wood") { c.sendSupplies(com.marian1x.agesofsiege.core.Cost(wood = 50)) }
                    MenuButton("50 stone") { c.sendSupplies(com.marian1x.agesofsiege.core.Cost(stone = 50)) }
                }
            }
        }
        if (isAlly) Unit
        else if (poi != null) PoiDetails(camp, poi)
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
            val action = when {
                isAlly -> "REINFORCE"
                poi?.kind == PoiKind.RIVAL_CAPITAL && view?.mode == MatchMode.VERSUS -> "ATTACK"
                poi != null -> "MARCH"
                else -> "SCOUT"
            }
            MenuButton(action, Modifier.weight(1f), primary = true, enabled = counts.values.sum() > 0) {
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
            if (camp.mp?.mode == MatchMode.VERSUS)
                "Defense ⚔ ${camp.rival.defense.toInt()}. Your army storms ${camp.rival.name}'s base for real – their towers and soldiers fight back. Every attacker who reaches the keep steals 5% of their resources."
            else "Defense ⚔ ${camp.rival.defense.toInt()}. Win 3 assaults to make ${camp.rival.name} your vassal. Each victory plunders their treasury.",
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
