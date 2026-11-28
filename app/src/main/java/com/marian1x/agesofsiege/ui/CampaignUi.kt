package com.marian1x.agesofsiege.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marian1x.agesofsiege.core.Ability
import com.marian1x.agesofsiege.core.Achievements
import com.marian1x.agesofsiege.core.Content
import com.marian1x.agesofsiege.core.DailyChallenge
import com.marian1x.agesofsiege.core.LevelDef
import com.marian1x.agesofsiege.core.LevelMode
import com.marian1x.agesofsiege.core.Levels
import com.marian1x.agesofsiege.core.Perks
import com.marian1x.agesofsiege.game.BattleSummary
import com.marian1x.agesofsiege.game.GameController
import com.marian1x.agesofsiege.game.InputMode
import com.marian1x.agesofsiege.game.Overlay
import com.marian1x.agesofsiege.game.Screen
import com.marian1x.agesofsiege.game.UiSound
import com.marian1x.agesofsiege.net.Leaderboards
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val StarOn = Color(0xFFFFD34D)
private val StarOff = Color(0x55FFFFFF)
private val GloryColor = Color(0xFFB98CFF)

@Composable
internal fun Stars(n: Int, size: Int = 16, max: Int = 3) {
    Row {
        for (i in 0 until max) Text("★", color = if (i < n) StarOn else StarOff, fontSize = size.sp)
    }
}

@Composable
private fun Chip(text: String, color: Color = Color(0xFF2B3243), textColor: Color = Color.White, onClick: (() -> Unit)? = null) {
    Text(
        text, color = textColor, fontFamily = GameFont, fontSize = 14.sp,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(color)
            .then(if (onClick != null) Modifier.clickable { UiSound.click(); onClick() } else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

// ------------------------------------------------------------------ campaign map

/** The realm seen from above: one banner per level along the road to the Marauder stronghold. */
@Composable
fun CampaignMapScreen(c: GameController) {
    val hud = c.hud
    hud.profileVersion
    val profile = c.profile
    val density = LocalDensity.current
    val flagW = with(density) { 70.dp.toPx() }
    val flagH = with(density) { 78.dp.toPx() }
    val pulse by rememberInfiniteTransition(label = "flag").animateFloat(
        1f, 1.12f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse",
    )
    val current = profile.currentLevel()
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTransformGestures(panZoomLock = true) { centroid, pan, zoom, rotation ->
                    if (pan != Offset.Zero) c.onPan(centroid.x, centroid.y, pan.x, pan.y)
                    if (zoom != 1f) c.onZoom(zoom)
                    if (rotation != 0f) c.onRotate(rotation)
                }
            },
        )
        // The road between the banners.
        val tmp = remember { FloatArray(2) }
        Canvas(Modifier.fillMaxSize()) {
            c.frame.intValue
            var prev: Offset? = null
            for (i in Levels.all.indices) {
                val a = c.levelAnchor(i)
                if (!c.r3d.worldToScreen(a[0], a[1] - 1.1f, a[2], tmp)) { prev = null; continue }
                val p = Offset(tmp[0], tmp[1])
                prev?.let {
                    val done = profile.stars(Levels.all[i - 1].id) > 0
                    drawLine(
                        if (done) Color(0xCCFFD34D) else Color(0x88FFFFFF), it, p, strokeWidth = 5f, cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 12f)),
                    )
                }
                prev = p
            }
        }
        for ((i, level) in Levels.all.withIndex()) {
            val unlocked = profile.isUnlocked(level)
            val stars = profile.stars(level.id)
            val isCurrent = level === current && unlocked
            Column(
                Modifier
                    .offset {
                        c.frame.intValue
                        val a = c.levelAnchor(i)
                        if (c.r3d.worldToScreen(a[0], a[1], a[2], tmp)) IntOffset((tmp[0] - flagW / 2).roundToInt(), (tmp[1] - flagH).roundToInt())
                        else IntOffset(-10_000, -10_000)
                    }
                    .width(70.dp)
                    .scale(if (isCurrent) pulse else 1f)
                    .clickable {
                        if (unlocked) c.openBriefing(level) else {
                            UiSound.audio?.play("error", 0.5f)
                            val gate = Levels.chapterStarGate(level.chapter)
                            c.toastLocked(if (profile.totalStars < gate) "Chapter ${level.chapter} needs $gate ★" else "Win the previous battle first")
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val bg = when {
                    !unlocked -> Color(0xCC2A2E38)
                    stars == 3 -> Color(0xF0C98A1E)
                    stars > 0 -> Color(0xF04A7D3A)
                    else -> Color(0xF08E3B2E)
                }
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(10.dp, 10.dp, 22.dp, 22.dp)).background(bg)
                        .border(2.dp, if (isCurrent) Gold else Color(0x66FFFFFF), RoundedCornerShape(10.dp, 10.dp, 22.dp, 22.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (unlocked) level.id else "🔒", color = Color.White, fontFamily = GameFont, fontSize = 15.sp)
                }
                if (unlocked) Stars(stars, 13)
                if (isCurrent) {
                    Text(
                        level.name, color = Color.White, fontSize = 11.sp, maxLines = 1, softWrap = false, textAlign = TextAlign.Center,
                        modifier = Modifier.wrapContentWidth(unbounded = true).clip(RoundedCornerShape(6.dp))
                            .background(Color(0xCC101420)).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
        }
        // Top bar.
        Row(
            Modifier.fillMaxWidth().safeDrawingPadding().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RoundButton("◀", onClick = { c.toMainMenu() }, actionLabel = "Back to the menu")
            Panel {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("CAMPAIGN", fontFamily = GameFont, fontSize = 20.sp, color = Gold)
                    Spacer(Modifier.width(10.dp))
                    Text("★ ${profile.totalStars}/${Levels.all.size * 3}", color = StarOn, fontFamily = GameFont, fontSize = 17.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            Chip("✦ ${profile.glory} Glory", Color(0xF0352850), GloryColor) { hud.overlay = Overlay.PERKS }
            val questsReady = profile.quests.count { it.done && !it.claimed }
            Chip(if (questsReady > 0) "QUESTS •$questsReady" else "QUESTS", onClick = { hud.overlay = Overlay.QUESTS })
            Chip("DAILY", onClick = { hud.overlay = Overlay.DAILY })
        }
        // Chapters.
        Column(Modifier.align(Alignment.BottomStart).safeDrawingPadding().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((k, ch) in Levels.chapters.withIndex()) {
                val n = k + 1
                val open = profile.chapterOpen(n)
                val got = Levels.chapter(n).sumOf { profile.stars(it.id) }
                Panel {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("$n · ${ch.first}", color = if (open) Color.White else TextDim, fontFamily = GameFont, fontSize = 14.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (open) "★ $got/18" else "🔒 ${Levels.chapterStarGate(n)} ★",
                            color = if (open) StarOn else TextDim, fontSize = 12.sp,
                        )
                    }
                }
            }
        }
        Panel(Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(10.dp)) {
            Column(Modifier.padding(10.dp).widthIn(max = 260.dp)) {
                Text("Next: ${current.id} ${current.name}", color = Gold, fontFamily = GameFont, fontSize = 16.sp)
                Text(current.intro, color = TextDim, fontSize = 11.sp, maxLines = 2)
                Spacer(Modifier.height(6.dp))
                MenuButton("PLAY", Modifier.fillMaxWidth(), primary = true) { c.openBriefing(current) }
            }
        }
    }
}

// ------------------------------------------------------------------ briefing

@Composable
fun BriefingDialog(c: GameController, level: LevelDef) {
    val profile = c.profile
    c.hud.profileVersion
    val record = profile.levels[level.id]
    var mode by remember(level.id) { mutableStateOf(LevelMode.NORMAL) }
    GameDialog("${level.id} · ${level.name}", width = 560, onClose = { c.hud.overlay = Overlay.NONE }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Stars(profile.stars(level.id), 22)
            Spacer(Modifier.width(10.dp))
            Text(Levels.chapters[level.chapter - 1].first, color = TextDim, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text(if (level.roads) "Road battle" else "Open field", color = TextDim, fontSize = 12.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(level.intro, color = Color.White, fontSize = 14.sp)
        if (level.unlocks.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text("New: ${level.unlocks}", color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        SectionLabel("Raiders")
        for (id in level.enemyIds) {
            val e = Content.enemy(id)
            Row(Modifier.padding(vertical = 1.dp)) {
                Text(e.name, color = if (e.boss) Color(0xFFFF8A7A) else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(140.dp))
                Text(e.description, color = TextDim, fontSize = 12.sp)
            }
        }
        SectionLabel("Battle")
        Text(
            "${level.waveCount} waves · ${level.startGold} gold · Keep ${level.keepHp} HP · ★★★ no leaks · ★★ up to ${level.twoStarLeaks} leaks",
            color = Color.White, fontSize = 12.sp,
        )
        Text("Tip: ${level.tip}", color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        SectionLabel("Mode")
        val modes = listOf(LevelMode.NORMAL, LevelMode.HEROIC, LevelMode.IRON, LevelMode.ENDLESS)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (m in modes) {
                val open = profile.modeUnlocked(level, m)
                val done = when (m) {
                    LevelMode.HEROIC -> record?.heroic == true
                    LevelMode.IRON -> record?.iron == true
                    else -> false
                }
                Chip(
                    (if (!open) "🔒 " else if (done) "✔ " else "") + m.label,
                    if (mode == m) Gold else Color(0xFF2B3243), if (mode == m) Color(0xFF1A1F2B) else if (open) Color.White else TextDim,
                ) { mode = m }
            }
        }
        val modeText = when (mode) {
            LevelMode.NORMAL -> "Win to unlock the next battle. Stars depend on how many raiders reach your Keep."
            LevelMode.HEROIC -> "Raiders have 30% more health and move faster. Win for +2 Glory. Needs ★★★."
            LevelMode.IRON -> "The Keep has a single hit point: one leak and it falls. Win for +3 Glory. Needs ★★★."
            LevelMode.ENDLESS -> "The waves never stop. How long can you hold? Best: wave ${record?.endlessBest ?: 0}. Needs a win."
            else -> ""
        }
        Text(modeText, color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        val perks = profile.perks.values.sum()
        if (perks > 0) Text("War Council perks active ($perks)", color = GloryColor, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        if ((record?.bestScore ?: 0) > 0) Text("Best score: ${record?.bestScore}", color = TextDim, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MenuButton("RANKING", Modifier.weight(1f)) {
                val board = if (mode == LevelMode.ENDLESS) "endless-${level.id}" else "level-${level.id}-${mode.name.lowercase()}"
                c.openLeaderboard(board, "${level.id} ${level.name} · ${mode.label}")
            }
            MenuButton("TO BATTLE", Modifier.weight(2f), primary = true, enabled = profile.modeUnlocked(level, mode)) { c.startLevel(level, mode) }
        }
    }
}

// ------------------------------------------------------------------ results

@Composable
fun ResultsDialog(c: GameController, s: BattleSummary) {
    val shown = remember(s) { mutableIntStateOf(0) }
    val scales = remember(s) { List(3) { Animatable(0f) } }
    val score = remember(s) { Animatable(0f) }
    LaunchedEffect(s) {
        delay(450)
        for (i in 0 until s.stars) {
            UiSound.star(i)
            c.audio.haptic(25, 90 + 50 * i)
            scales[i].animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
            shown.intValue = i + 1
            delay(160)
        }
        score.animateTo(s.score.toFloat(), tween(900, easing = FastOutSlowInEasing))
    }
    val title = when {
        s.mode == LevelMode.ENDLESS -> "Held for ${s.wave - 1} waves"
        s.race != null -> if (s.race.youWon) "RACE WON!" else if (s.won) "VICTORY" else "DEFEAT"
        s.won -> "VICTORY!"
        else -> "THE KEEP HAS FALLEN"
    }
    GameDialog(title, width = 480) {
        Text("${s.levelId} · ${s.levelName} · ${s.mode.label}", color = TextDim, fontSize = 13.sp)
        if (s.mode != LevelMode.ENDLESS && s.won) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
                for (i in 0 until 3) {
                    Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                        Text("★", color = StarOff, fontSize = 56.sp)
                        Text("★", color = StarOn, fontSize = 56.sp, modifier = Modifier.scale(scales[i].value))
                    }
                }
            }
        }
        val leakText = when {
            !s.won && s.mode != LevelMode.ENDLESS -> "Wave ${s.wave}${if (s.totalWaves > 0) " of ${s.totalWaves}" else ""}. ${s.leaks} raiders broke through."
            s.mode == LevelMode.ENDLESS -> "${s.kills} raiders defeated."
            s.stars == 3 -> "Flawless – not a single raider reached the Keep!"
            s.stars == 2 -> "${s.leaks} raider${if (s.leaks == 1) "" else "s"} got through. Stop them all for ★★★!"
            else -> "${s.leaks} raiders got through. Allow at most ${s.twoStarLeaks} for ★★."
        }
        Text(leakText, color = Color.White, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Score ", color = TextDim, fontSize = 14.sp)
            Text("${score.value.toInt()}", color = Gold, fontFamily = GameFont, fontSize = 24.sp)
            if (s.record?.newBest == true) {
                Spacer(Modifier.width(8.dp))
                Chip("NEW BEST", Color(0xFF2E7D4F))
            }
        }
        val r = s.record
        if (r != null) {
            if (r.gloryGained > 0) Text("+${r.gloryGained} Glory for the War Council", color = GloryColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            for (a in r.achievements) Text("🏆 ${a.name} – ${a.description}", color = Gold, fontSize = 13.sp)
            for (q in r.quests) Text("✔ Quest done: ${c.profile.questText(q)} (claim it in Quests)", color = Color(0xFF7CFC9A), fontSize = 12.sp)
            if (r.firstWin && s.hasNext) Text("New battle unlocked!", color = Color(0xFF7CFC9A), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        s.race?.let { Text(it.text, color = if (it.youWon) Color(0xFF7CFC9A) else Color(0xFFFFB070), fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp)) }
        if (s.board != null && !Leaderboards.available(LocalContext.current)) {
            Text("Add a friends lobby to compare scores with friends.", color = TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.race == null) {
                MenuButton("RETRY", Modifier.weight(1f), primary = !s.won || (s.stars < 3 && !s.hasNext)) { c.retryBattle() }
            }
            if (s.hasNext) MenuButton("NEXT ▶", Modifier.weight(1f), primary = true) { c.nextLevel() }
            MenuButton(if (s.race != null || s.mode == LevelMode.DAILY) "DONE" else "MAP", Modifier.weight(1f)) { c.leaveBattle() }
        }
        if (s.board != null) {
            Spacer(Modifier.height(8.dp))
            MenuButton("FRIENDS RANKING", Modifier.fillMaxWidth()) { c.openLeaderboard(s.board, "${s.levelName} · ${s.mode.label}") }
        }
    }
}

// ------------------------------------------------------------------ war council (perks)

@Composable
fun PerksDialog(c: GameController) {
    val profile = c.profile
    c.hud.profileVersion
    GameDialog("War Council", width = 560, onClose = { c.hud.overlay = Overlay.NONE }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("✦ ${profile.glory} Glory", color = GloryColor, fontFamily = GameFont, fontSize = 22.sp)
            Spacer(Modifier.width(10.dp))
            Text("Earned from stars, Heroic/Iron wins, achievements and quests.", color = TextDim, fontSize = 12.sp)
        }
        Text("Perks apply to campaign battles (not to the Daily Challenge or races).", color = TextDim, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        for (p in Perks.all) {
            val lvl = profile.perk(p.id)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF232A39)).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, color = Color.White, fontFamily = GameFont, fontSize = 15.sp)
                        Spacer(Modifier.width(8.dp))
                        for (i in 0 until p.maxLevel) {
                            Box(Modifier.padding(end = 3.dp).size(9.dp).clip(CircleShape).background(if (i < lvl) GloryColor else Color(0x40FFFFFF)))
                        }
                    }
                    Text(p.description, color = TextDim, fontSize = 12.sp)
                }
                if (lvl < p.maxLevel) {
                    MenuButton("+ ${p.cost}", enabled = profile.glory >= p.cost) { c.buyPerk(p.id) }
                } else Text("MAX", color = GloryColor, fontFamily = GameFont, fontSize = 15.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MenuButton("REFUND ALL", Modifier.weight(1f), enabled = profile.glorySpent > 0) { c.resetPerks() }
            MenuButton("DONE", Modifier.weight(1f), primary = true) { c.hud.overlay = Overlay.NONE }
        }
    }
}

// ------------------------------------------------------------------ quests + achievements

@Composable
fun QuestsDialog(c: GameController) {
    val profile = c.profile
    c.hud.profileVersion
    LaunchedEffect(Unit) { profile.refreshQuests(c.today()) }
    GameDialog("Quests & Deeds", width = 560, onClose = { c.hud.overlay = Overlay.NONE }) {
        SectionLabel("Today's quests (new ones every day)")
        for (q in profile.quests) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF232A39)).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(profile.questText(q), color = Color.White, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x55000000))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth((q.progress.toFloat() / q.target).coerceIn(0f, 1f)).background(Gold))
                    }
                    Text("${q.progress}/${q.target}", color = TextDim, fontSize = 11.sp)
                }
                Spacer(Modifier.width(8.dp))
                when {
                    q.claimed -> Text("✔", color = Color(0xFF7CFC9A), fontSize = 20.sp)
                    q.done -> MenuButton("+1 ✦", primary = true) { c.claimQuest(q) }
                    else -> Text("+1 ✦", color = TextDim, fontSize = 13.sp)
                }
            }
        }
        Text("Finish all three for a bonus Glory.", color = TextDim, fontSize = 11.sp)
        SectionLabel("Deeds (${profile.achievements.size}/${Achievements.all.size})")
        for (a in Achievements.all) {
            val got = a.id in profile.achievements
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (got) "🏆" else "○", fontSize = 15.sp, color = if (got) Gold else TextDim, modifier = Modifier.width(26.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.name, color = if (got) Gold else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(a.description, color = TextDim, fontSize = 11.sp)
                }
                Text("+${a.glory} ✦", color = if (got) GloryColor else TextDim, fontSize = 12.sp)
            }
        }
        SectionLabel("Record")
        Text(
            "${profile.battlesWon} battles won · ${profile.totalKills} raiders defeated · ${profile.totalShatters} shatters · ${profile.bossKills} Warlords slain",
            color = Color.White, fontSize = 12.sp,
        )
    }
}

// ------------------------------------------------------------------ daily challenge

@Composable
fun DailyDialog(c: GameController) {
    val profile = c.profile
    c.hud.profileVersion
    val day = c.today()
    val setup = remember(day) { DailyChallenge.forDay(day) }
    val best = profile.daily[day]
    var left by remember { mutableIntStateOf(0) }
    LaunchedEffect(day) {
        while (true) {
            left = (86_400 - (System.currentTimeMillis() / 1000 % 86_400)).toInt()
            delay(1000)
        }
    }
    GameDialog("Daily Challenge", width = 520, onClose = { c.hud.overlay = Overlay.NONE }) {
        Text("Everyone gets the same battle today. No perks – pure skill.", color = TextDim, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Text("${setup.level.id} · ${setup.level.name}", color = Color.White, fontFamily = GameFont, fontSize = 20.sp)
        Text(setup.level.intro, color = TextDim, fontSize = 12.sp)
        SectionLabel("Today's rules")
        for (m in setup.modifiers) {
            Row(Modifier.padding(vertical = 2.dp)) {
                Text(m.label, color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(130.dp))
                Text(m.description, color = Color.White, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (best != null) "Your best today: $best" else "Not played yet today", color = if (best != null) Gold else TextDim, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            if (profile.dailyStreak > 0) Text("🔥 ${profile.dailyStreak}-day streak", color = Color(0xFFFF9B5A), fontSize = 13.sp)
        }
        Text("New challenge in ${left / 3600}h ${(left % 3600) / 60}m", color = TextDim, fontSize = 11.sp)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MenuButton("RANKING", Modifier.weight(1f)) { c.openLeaderboard(DailyChallenge.boardId(day), "Daily Challenge") }
            MenuButton(if (best != null) "TRY AGAIN" else "PLAY", Modifier.weight(2f), primary = true) { c.startDaily() }
        }
    }
}

// ------------------------------------------------------------------ leaderboard

@Composable
fun LeaderboardDialog(c: GameController) {
    val board = c.hud.leaderboardId ?: return
    val context = LocalContext.current
    var entries by remember(board) { mutableStateOf<List<Leaderboards.Entry>?>(null) }
    var error by remember(board) { mutableStateOf<String?>(null) }
    LaunchedEffect(board) {
        Leaderboards.top(context, board) { r ->
            r.onSuccess { entries = it }.onFailure { error = it.message }
        }
    }
    val back = { c.hud.overlay = if (c.battle?.finished == true) Overlay.RESULTS else if (c.hud.screen == Screen.CAMPAIGN && c.hud.briefing != null) Overlay.BRIEFING else Overlay.NONE }
    GameDialog("Friends Ranking", width = 460, onClose = back) {
        Text(c.hud.leaderboardTitle, color = TextDim, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        val list = entries
        when {
            error != null -> Text(error ?: "", color = Color(0xFFFFB070), fontSize = 13.sp)
            list == null -> Text("Loading…", color = TextDim, fontSize = 13.sp)
            list.isEmpty() -> Text("No scores yet. Be the first!", color = Color.White, fontSize = 14.sp)
            else -> for ((i, e) in list.withIndex()) {
                val me = e.name == c.playerName()
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (me) Color(0x553A3218) else Color(0xFF232A39)).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val medal = when (i) { 0 -> "🥇"; 1 -> "🥈"; 2 -> "🥉"; else -> "${i + 1}." }
                    Text(medal, color = Color.White, fontSize = 15.sp, modifier = Modifier.width(34.dp))
                    Text(e.name, color = if (me) Gold else Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    if (e.stars > 0) Stars(e.stars, 12)
                    Spacer(Modifier.width(8.dp))
                    Text("${e.score}", color = Gold, fontFamily = GameFont, fontSize = 16.sp)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        MenuButton("BACK", Modifier.fillMaxWidth()) { back() }
    }
}

// ------------------------------------------------------------------ battle HUD pieces

/** Live star tracker: how many stars you would get right now. */
@Composable
internal fun StarTracker(c: GameController) {
    val hud = c.hud
    if (hud.battleMode != LevelMode.NORMAL && hud.battleMode != LevelMode.HEROIC && hud.battleMode != LevelMode.DAILY) return
    val stars = when {
        hud.leaks <= 0 -> 3
        hud.leaks <= hud.twoStarLeaks -> 2
        else -> 1
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Stars(stars, 15)
        Spacer(Modifier.width(6.dp))
        Text(if (hud.leaks == 0) "no leaks" else "${hud.leaks} leaked", color = if (hud.leaks == 0) Color(0xFF7CFC9A) else Color(0xFFFFB070), fontSize = 11.sp)
    }
}

/** Big health bar for a Warlord on the field. */
@Composable
internal fun BossBar(c: GameController, modifier: Modifier = Modifier) {
    c.frame.intValue
    val boss = c.game.enemies.firstOrNull { it.alive && it.def.boss } ?: return
    val f = (boss.hp / boss.maxHp).coerceIn(0f, 1f)
    Column(modifier.width(300.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            boss.def.name.uppercase() + if (boss.enraged) " · ENRAGED" else "",
            color = if (boss.enraged) Color(0xFFFF5A4A) else Color(0xFFFFB0A0), fontFamily = GameFont, fontSize = 14.sp,
        )
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xAA000000))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(Brush.horizontalGradient(listOf(Color(0xFF8E1E1E), Color(0xFFFF5A4A)))))
        }
    }
}

/** Race: the opponent's battle at a glance, and the button to send raiders. */
@Composable
internal fun RacePanel(c: GameController, modifier: Modifier = Modifier) {
    val r = c.hud.race ?: return
    c.hud.raceVersion
    Panel(modifier) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text("🏁 vs ${r.opponent}", color = Color.White, fontFamily = GameFont, fontSize = 14.sp)
            val status = when (r.outcome) {
                null -> "Wave ${r.wave} · Keep ${r.keepHp}/${r.keepMax} · ${r.score} pts"
                else -> if (r.outcome == com.marian1x.agesofsiege.core.Outcome.WON) "Finished! ${r.score} pts" else "Their Keep fell (wave ${r.wave})"
            }
            Text(status, color = TextDim, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "SEND RAIDERS ×${r.tokens}",
                color = if (r.tokens > 0) Color(0xFF1A1F2B) else TextDim, fontFamily = GameFont, fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (r.tokens > 0) Color(0xFFFF8A5A) else Color(0x33FFFFFF))
                    .clickable { c.sendRaceSquad() }.padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Text("Sent ${r.sent} · received ${r.received}", color = TextDim, fontSize = 10.sp)
        }
    }
}

/** Ability buttons (Volley, Militia, Tar Pit) with cooldown sweeps. */
@Composable
internal fun AbilityBar(c: GameController, modifier: Modifier = Modifier) {
    val hud = c.hud
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
        for (a in hud.abilities) {
            val ready = (hud.abilityReady[a] ?: 1f).coerceIn(0f, 1f)
            val armed = (hud.mode as? InputMode.Cast)?.ability == a
            val big = a == Ability.VOLLEY
            val s = if (big) 72.dp else 58.dp
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(s).clip(CircleShape)
                        .background(if (armed) Color(0xFF5A2A18) else PanelBg)
                        .border(2.dp, if (ready >= 1f) Gold else PanelBorder, CircleShape)
                        .clickable { c.armAbility(a) },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(s)) {
                        val d = size.minDimension
                        when (a) {
                            Ability.VOLLEY -> for (i in -1..1) {
                                val x = d / 2 + i * d * 0.17f
                                drawLine(Color(0xFFE2C89A), Offset(x - d * 0.08f, d * 0.22f), Offset(x + d * 0.06f, d * 0.68f), strokeWidth = d * 0.04f)
                                drawCircle(Color(0xFFDCE3EA), d * 0.045f, Offset(x + d * 0.07f, d * 0.71f))
                            }
                            Ability.REINFORCE -> {
                                drawLine(Color(0xFFB07A44), Offset(d * 0.3f, d * 0.78f), Offset(d * 0.7f, d * 0.22f), strokeWidth = d * 0.05f)
                                drawCircle(Color(0xFFC0C6CE), d * 0.07f, Offset(d * 0.7f, d * 0.22f))
                                drawCircle(Color(0xFF9FE3FF), d * 0.16f, Offset(d * 0.42f, d * 0.52f))
                            }
                            Ability.TAR -> {
                                drawOval(Color(0xFF2A1C12), Offset(d * 0.18f, d * 0.45f), androidx.compose.ui.geometry.Size(d * 0.64f, d * 0.3f))
                                drawCircle(Color(0xFF5A4030), d * 0.06f, Offset(d * 0.4f, d * 0.5f))
                                drawCircle(Color(0xFF5A4030), d * 0.04f, Offset(d * 0.6f, d * 0.56f))
                            }
                        }
                        if (ready < 1f) drawArc(Color(0xB0000000), -90f + 360f * ready, 360f * (1f - ready), true, Offset.Zero, size)
                    }
                }
                Text(a.label.uppercase(), color = if (ready >= 1f) Gold else TextDim, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}
