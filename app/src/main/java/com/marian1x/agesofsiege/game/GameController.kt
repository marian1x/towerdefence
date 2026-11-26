package com.marian1x.agesofsiege.game

import android.content.Context
import android.util.Log
import android.view.Choreographer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.filament.MaterialInstance
import com.marian1x.agesofsiege.core.BaseGame
import com.marian1x.agesofsiege.core.Command
import com.marian1x.agesofsiege.core.CommandResult
import com.marian1x.agesofsiege.core.Content
import com.marian1x.agesofsiege.core.Cost
import com.marian1x.agesofsiege.core.GameEvent
import com.marian1x.agesofsiege.core.Phase
import com.marian1x.agesofsiege.core.SaveData
import com.marian1x.agesofsiege.core.Structure
import com.marian1x.agesofsiege.core.StructureKind
import com.marian1x.agesofsiege.engine.Mesh
import com.marian1x.agesofsiege.engine.MeshBuilder
import com.marian1x.agesofsiege.engine.Models
import com.marian1x.agesofsiege.engine.Palette
import com.marian1x.agesofsiege.engine.Renderer3D
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

sealed class InputMode {
    object Normal : InputMode()
    data class Build(val defId: String) : InputMode()
    object Volley : InputMode()
}

data class SelectedInfo(
    val id: Int,
    val name: String,
    val kind: StructureKind,
    val level: Int,
    val maxLevel: Int,
    val rank: Int,
    val xp: Int,
    val nextRankXp: Int?,
    val kills: Int,
    val upgradeCost: Cost?,
    val refund: Cost,
    val stats: String,
)

data class Toast(val id: Long, val text: String, val color: Long)

/** Observable state for the Compose HUD. */
class HudState {
    var gold by mutableIntStateOf(0)
    var wood by mutableIntStateOf(0)
    var stone by mutableIntStateOf(0)
    var wave by mutableIntStateOf(1)
    var bestWave by mutableIntStateOf(0)
    var phase by mutableStateOf(Phase.PREP)
    var countdown by mutableIntStateOf(0)
    var enemiesLeft by mutableIntStateOf(0)
    var keepHp by mutableIntStateOf(Content.KEEP_MAX_HP)
    var volleyReady by mutableFloatStateOf(1f)
    var speed by mutableIntStateOf(1)
    var paused by mutableStateOf(false)
    var mode by mutableStateOf<InputMode>(InputMode.Normal)
    var selected by mutableStateOf<SelectedInfo?>(null)
    var preview by mutableStateOf<List<Pair<String, Int>>>(emptyList())
    val toasts = mutableStateListOf<Toast>()
}

class GameController(private val context: Context) : Choreographer.FrameCallback {
    val r3d = Renderer3D(context)
    val hud = HudState()
    /** Bumped every frame so the overlay canvas redraws. */
    val frame = mutableIntStateOf(0)
    val floatingTexts = ArrayList<FloatingText>()

    var game: BaseGame = loadOrNew()
        private set

    private val meshCache = HashMap<String, Mesh>()
    private val worldEntities = ArrayList<Int>()
    private val fx = Fx(r3d, mesh("cube") { Models.cube(this) })

    private class StructureVisual(val base: Int, val head: Int?, val level: Int)
    private class EnemyVisual(val entity: Int, val mi: MaterialInstance, val defId: String) {
        var lastHp = Float.MAX_VALUE
        var flash = 0f
        var walk = Random.nextFloat() * 6f
        var yaw = 0f
    }

    private val structureVisuals = HashMap<Int, StructureVisual>()
    private val enemyVisuals = HashMap<Int, EnemyVisual>()
    private val enemyPool = HashMap<String, ArrayDeque<EnemyVisual>>()
    private val projectileVisuals = HashMap<Int, Pair<Int, String>>()
    private val projectilePool = HashMap<String, ArrayDeque<Int>>()

    // Overlays: selection range ring, build ghost, grid, volley marker.
    private val ringMi = r3d.litInstance(0xFFFFFF, r3d.ghostMaterial, 0.55f)
    private val ringEntity = r3d.createRenderable(mesh("ring") { Models.ringMesh(this) }, ringMi, shadows = false)
    private val ghostMi = r3d.litInstance(0x55FF77, r3d.ghostMaterial, 0.45f)
    private val ghostEntity = r3d.createRenderable(mesh("tile") { Models.tileMarker(this) }, ghostMi, shadows = false)
    private val volleyMi = r3d.litInstance(0xFF5533, r3d.ghostMaterial, 0.3f)
    private val volleyEntity = r3d.createRenderable(mesh("disc") { Models.discMesh(this) }, volleyMi, shadows = false)
    private var gridEntity = 0
    private var ringShown = false
    private var ghostShown = false
    private var gridShown = false
    private var volleyMarkers = ArrayList<Pair<FloatArray, Float>>()
    private var ghostTimer = 0f

    private var running = false
    private var lastNanos = 0L
    private var acc = 0f
    private var hudTimer = 0f
    private var toastId = 0L
    private var shake = 0f

    init {
        buildWorld()
        r3d.camTargetX = 14f
        r3d.camTargetZ = 8.6f
        refreshHud()
    }

    // ------------------------------------------------------------ lifecycle

    fun start() {
        if (running) return
        running = true
        lastNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        save()
    }

    fun destroy() {
        stop()
        r3d.destroy()
    }

    private val saveFile get() = File(context.filesDir, "base_save.json")

    private fun loadOrNew(): BaseGame {
        val f = File(context.filesDir, "base_save.json")
        if (f.exists()) {
            try {
                val data = SaveData.fromJson(f.readText())
                return BaseGame(data.seed).also { it.loadFrom(data) }
            } catch (t: Throwable) {
                Log.w("AgesOfSiege", "Could not load save, starting fresh", t)
            }
        }
        return BaseGame(System.currentTimeMillis() and 0xffffff)
    }

    fun save() {
        try {
            saveFile.writeText(game.toSave().toJson())
        } catch (t: Throwable) {
            Log.w("AgesOfSiege", "Save failed", t)
        }
    }

    fun newGame() {
        clearVisuals()
        game = BaseGame(System.currentTimeMillis() and 0xffffff)
        buildWorld()
        hud.selected = null
        hud.mode = InputMode.Normal
        save()
        refreshHud()
    }

    // ------------------------------------------------------------ meshes

    private fun mesh(key: String, build: MeshBuilder.() -> Unit): Mesh =
        meshCache.getOrPut(key) { r3d.upload(MeshBuilder().apply(build)) }

    private fun structureMeshes(s: Structure): Pair<Mesh, Mesh?> {
        val l = s.level
        return when (s.defId) {
            "keep" -> mesh("keep") { Models.keep(this) } to null
            "archer" -> mesh("archer$l") { Models.archerTowerBase(this, l) } to mesh("archerHead$l") { Models.archerTowerHead(this, l) }
            "ballista" -> mesh("ballista$l") { Models.ballistaBase(this, l) } to mesh("ballistaHead$l") { Models.ballistaHead(this, l) }
            "catapult" -> mesh("catapult$l") { Models.catapultBase(this, l) } to mesh("catapultHead$l") { Models.catapultHead(this, l) }
            "wall" -> mesh("wall") { Models.wall(this) } to null
            "house" -> mesh("house$l") { Models.house(this, l) } to null
            "lumber" -> mesh("lumber$l") { Models.lumberMill(this, l) } to null
            "quarry" -> mesh("quarry$l") { Models.quarry(this, l) } to null
            else -> mesh("cube") { Models.cube(this) } to null
        }
    }

    private fun enemyMesh(defId: String): Mesh = when (defId) {
        "wolf" -> mesh("wolf") { Models.wolfRider(this) }
        "brute" -> mesh("brute") { Models.brute(this) }
        "warlord" -> mesh("warlord") { Models.warlord(this) }
        else -> mesh("raider") { Models.raider(this) }
    }

    private fun projectileMesh(visual: String): Mesh = when (visual) {
        "ballista" -> mesh("bolt") { Models.bolt(this) }
        "catapult" -> mesh("boulder") { Models.boulder(this) }
        else -> mesh("arrow") { Models.arrow(this) }
    }

    // ------------------------------------------------------------ static world

    private fun buildWorld() {
        for (e in worldEntities) r3d.destroyEntity(e)
        worldEntities.clear()
        val g = game
        val rnd = Random(g.seed)
        val border = 3
        val ground = MeshBuilder().apply {
            // Island slab.
            box(-border - 0.2f, -1.6f, -border - 0.2f, g.width + border + 0.2f, -0.6f, g.height + border + 0.2f, Palette.CLIFF_DARK)
            box(-border.toFloat(), -0.6f, -border.toFloat(), (g.width + border).toFloat(), -0.02f, (g.height + border).toFloat(), Palette.CLIFF, Palette.MEADOW)
            // Playable tiles.
            for (y in 0 until g.height) for (x in 0 until g.width) {
                val base = if ((x + y) % 2 == 0) Palette.GRASS_A else Palette.GRASS_B
                val c = MeshBuilder.mix(base, Palette.GRASS_C, rnd.nextFloat() * 0.35f)
                box(x.toFloat(), -0.04f, y.toFloat(), x + 1f, 0f, y + 1f, c)
            }
            // Dirt roads leading from each gate off the island.
            for ((gx, gy) in g.gates) {
                if (gx == 0) {
                    box(-border.toFloat(), -0.035f, gy + 0.1f, 1f, 0.005f, gy + 0.9f, Palette.DIRT)
                } else {
                    box(gx + 0.1f, -0.035f, -border.toFloat(), gx + 0.9f, 0.005f, 1f, Palette.DIRT)
                }
            }
            // Keep courtyard.
            val k = g.keep
            box(k.x - 0.5f, -0.03f, k.y - 0.5f, k.x + k.w + 0.5f, 0.004f, k.y + k.h + 0.5f, Palette.DIRT_DARK)
        }
        addWorld(r3d.upload(ground), 0f, 0f, 0f, 0f)

        // Decorative forest ring around the play area.
        for (i in 0 until 140) {
            val side = rnd.nextInt(4)
            val t = rnd.nextFloat()
            val d = 0.4f + rnd.nextFloat() * (border - 0.6f)
            val (x, z) = when (side) {
                0 -> (-d) to (t * g.height)
                1 -> (g.width + d) to (t * g.height)
                2 -> (t * (g.width + 2 * border) - border) to (-d)
                else -> (t * (g.width + 2 * border) - border) to (g.height + d)
            }
            if (g.gates.any { (gx, gy) -> (gx == 0 && side == 0 && abs(z - gy - 0.5f) < 1.1f) || (gy == 0 && side == 2 && abs(x - gx - 0.5f) < 1.1f) }) continue
            val v = rnd.nextInt(1000)
            val m = when (rnd.nextInt(10)) {
                in 0..4 -> mesh("pine${v % 5}") { Models.pine(this, v % 5) }
                in 5..7 -> mesh("tree${v % 4}") { Models.roundTree(this, v % 4) }
                8 -> mesh("bush${v % 3}") { Models.bush(this, v % 3) }
                else -> mesh("rock${v % 4}") { Models.rock(this, v % 4) }
            }
            addWorld(m, x, 0f, z, rnd.nextFloat() * 6.28f)
        }
        // Obstacles inside the base.
        for (o in g.obstacles) {
            val m = if (o.kind == 0) {
                if (o.variant % 2 == 0) mesh("pine${o.variant % 5}") { Models.pine(this, o.variant % 5) }
                else mesh("tree${o.variant % 4}") { Models.roundTree(this, o.variant % 4) }
            } else mesh("rock${o.variant % 4}") { Models.rock(this, o.variant % 4) }
            addWorld(m, o.x + 0.5f, 0f, o.y + 0.5f, (o.variant % 628) / 100f)
        }

        // Faint build grid (shown in build mode).
        val grid = MeshBuilder().apply {
            for (x in 0..g.width) box(x - 0.015f, 0.006f, 0f, x + 0.015f, 0.012f, g.height.toFloat(), Palette.WHITE)
            for (y in 0..g.height) box(0f, 0.006f, y - 0.015f, g.width.toFloat(), 0.012f, y + 0.015f, Palette.WHITE)
        }
        if (gridEntity != 0) r3d.destroyEntity(gridEntity)
        gridEntity = r3d.createRenderable(r3d.upload(grid), r3d.litInstance(0xFFFFFF, r3d.ghostMaterial, 0.18f), shadows = false)
        r3d.setTransform(gridEntity, 0f, 0f, 0f)
        gridShown = false
    }

    private fun addWorld(m: Mesh, x: Float, y: Float, z: Float, yaw: Float) {
        val e = r3d.createRenderable(m, r3d.defaultLit)
        r3d.setTransform(e, x, y, z, yaw)
        r3d.scene.addEntity(e)
        worldEntities += e
    }

    private fun clearVisuals() {
        for (v in structureVisuals.values) {
            r3d.destroyEntity(v.base); v.head?.let { r3d.destroyEntity(it) }
        }
        structureVisuals.clear()
        for ((_, v) in enemyVisuals) r3d.scene.remove(v.entity)
        for (v in enemyVisuals.values) enemyPool.getOrPut(v.defId) { ArrayDeque() }.addLast(v)
        enemyVisuals.clear()
        for ((_, ev) in projectileVisuals) {
            r3d.scene.remove(ev.first)
            projectilePool.getOrPut(ev.second) { ArrayDeque() }.addLast(ev.first)
        }
        projectileVisuals.clear()
        fx.clear()
        floatingTexts.clear()
    }

    // ------------------------------------------------------------ frame loop

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        Choreographer.getInstance().postFrameCallback(this)
        val dt = if (lastNanos == 0L) 0f else ((frameTimeNanos - lastNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastNanos = frameTimeNanos

        if (!hud.paused) {
            acc += dt * hud.speed
            var steps = 0
            while (acc >= BaseGame.DT && steps < 12) {
                game.step()
                handleEvents(game.drainEvents())
                acc -= BaseGame.DT
                steps++
            }
            if (steps == 12) acc = 0f
        }
        val alpha = (acc / BaseGame.DT).coerceIn(0f, 1f)
        val fxDt = if (hud.paused) 0f else dt * hud.speed

        syncStructures()
        syncEnemies(alpha, fxDt)
        syncProjectiles(alpha)
        syncOverlays(dt)
        fx.update(fxDt)
        updateTexts(fxDt)
        applyShake(dt)
        r3d.shake = shakeOffset()

        hudTimer += dt
        if (hudTimer > 0.1f) {
            hudTimer = 0f
            refreshHud()
        }
        r3d.render(frameTimeNanos)
        frame.intValue++
    }

    private fun applyShake(dt: Float) {
        if (shake <= 0f) return
        shake = max(0f, shake - dt * 2.5f)
    }

    /** Camera shake offset, read by the renderer each frame. */
    fun shakeOffset(): Float = if (shake > 0f) sin(game.time * 90f) * shake * 0.12f else 0f

    private fun syncStructures() {
        val g = game
        val it = structureVisuals.entries.iterator()
        while (it.hasNext()) {
            val (id, v) = it.next()
            val s = g.structures[id]
            if (s == null || s.level != v.level) {
                r3d.destroyEntity(v.base); v.head?.let { h -> r3d.destroyEntity(h) }
                it.remove()
            }
        }
        for (s in g.structures.values) {
            var v = structureVisuals[s.id]
            if (v == null) {
                val (baseMesh, headMesh) = structureMeshes(s)
                val base = r3d.createRenderable(baseMesh, r3d.defaultLit)
                r3d.setTransform(base, s.cx, 0f, s.cy, if (s.kind == StructureKind.BUILDING) ((s.x * 7 + s.y * 3) % 4) * 1.5708f else 0f)
                r3d.scene.addEntity(base)
                val head = headMesh?.let { m -> r3d.createRenderable(m, r3d.defaultLit).also { r3d.scene.addEntity(it) } }
                v = StructureVisual(base, head, s.level)
                structureVisuals[s.id] = v
            }
            v.head?.let { h ->
                val recoil = if (s.sinceShot < 0.18f) (1f - s.sinceShot / 0.18f) else 0f
                val kickPitch = if (s.defId == "catapult") -recoil * 0.5f else 0f
                val back = recoil * 0.06f
                r3d.setTransform(
                    h, s.cx - sin(s.aim) * back, 0f, s.cy - kotlin.math.cos(s.aim) * back,
                    yaw = s.aim, pitch = kickPitch,
                )
            }
        }
    }

    private fun syncEnemies(alpha: Float, dt: Float) {
        val g = game
        val alive = HashSet<Int>()
        for (e in g.enemies) {
            if (!e.alive) continue
            alive += e.id
            val v = enemyVisuals.getOrPut(e.id) {
                val pooled = enemyPool[e.def.id]?.removeLastOrNull()
                val vis = pooled ?: run {
                    val mi = r3d.litInstance(0xFFFFFF)
                    EnemyVisual(r3d.createRenderable(enemyMesh(e.def.id), mi), mi, e.def.id)
                }
                vis.lastHp = e.hp
                vis.flash = 0f
                vis.yaw = e.heading
                r3d.scene.addEntity(vis.entity)
                vis
            }
            if (e.hp < v.lastHp - 0.01f) v.flash = 1f
            v.lastHp = e.hp
            if (v.flash > 0f) {
                v.flash = max(0f, v.flash - dt * 6f)
                v.mi.setParameter("flash", v.flash * 0.8f)
            }
            val x = e.prevX + (e.x - e.prevX) * alpha
            val z = e.prevY + (e.y - e.prevY) * alpha
            v.walk += dt * e.def.speed * 9f
            // Smoothly turn toward the heading.
            var dy = e.heading - v.yaw
            while (dy > Math.PI) dy -= (2 * Math.PI).toFloat()
            while (dy < -Math.PI) dy += (2 * Math.PI).toFloat()
            v.yaw += dy * min(1f, dt * 10f)
            val scale = e.def.scale * UNIT_VISUAL_SCALE
            val bob = abs(sin(v.walk)) * 0.05f * scale
            val sway = sin(v.walk) * 0.08f
            r3d.setTransform(v.entity, x, bob, z, yaw = v.yaw, scale = scale, pitch = sway * 0.3f)
        }
        val it = enemyVisuals.entries.iterator()
        while (it.hasNext()) {
            val (id, v) = it.next()
            if (id !in alive) {
                r3d.scene.remove(v.entity)
                v.mi.setParameter("flash", 0f)
                enemyPool.getOrPut(v.defId) { ArrayDeque() }.addLast(v)
                it.remove()
            }
        }
    }

    private fun projectileHeights(visual: String): Pair<Float, Float> = when (visual) {
        "archer" -> 1.55f to 0.45f
        "ballista" -> 0.62f to 0.45f
        else -> 0.95f to 0.15f
    }

    private fun syncProjectiles(alpha: Float) {
        val g = game
        val alive = HashSet<Int>()
        for (p in g.projectiles) {
            if (!p.alive) continue
            alive += p.id
            val e = projectileVisuals.getOrPut(p.id) {
                val ent = projectilePool[p.visual]?.removeLastOrNull()
                    ?: r3d.createRenderable(projectileMesh(p.visual), r3d.defaultLit)
                r3d.scene.addEntity(ent)
                ent to p.visual
            }.first
            val x = p.prevX + (p.x - p.prevX) * alpha
            val z = p.prevY + (p.y - p.prevY) * alpha
            val (h0, h1) = projectileHeights(p.visual)
            val arc = when (p.visual) { "catapult" -> 2.4f; "archer" -> 0.5f; else -> 0.15f }
            val t = p.progress.coerceIn(0f, 1f)
            val y = h0 + (h1 - h0) * t + arc * 4f * t * (1f - t)
            val slope = ((h1 - h0) + arc * 4f * (1f - 2f * t)) / max(0.5f, p.totalDist)
            val yaw = atan2(p.tx - p.x, p.ty - p.y)
            r3d.setTransform(e, x, y, z, yaw = yaw, pitch = -atan2(slope, 1f), scale = 1f)
        }
        val it = projectileVisuals.entries.iterator()
        while (it.hasNext()) {
            val (id, ev) = it.next()
            if (id !in alive) {
                r3d.scene.remove(ev.first)
                projectilePool.getOrPut(ev.second) { ArrayDeque() }.addLast(ev.first)
                it.remove()
            }
        }
    }

    private fun syncOverlays(dt: Float) {
        // Range ring for the selected tower.
        val sel = hud.selected?.let { game.structures[it.id] }
        val showRing = sel != null && sel.kind == StructureKind.TOWER
        if (showRing && sel != null) {
            val def = sel.towerDef
            val range = def.range + Content.levelRangeBonus(sel.level) + Content.rankRangeBonus(sel.rank)
            r3d.setTransform(ringEntity, sel.cx, 0f, sel.cy, scale = range)
        }
        setShown(ringEntity, showRing, ringShown).also { ringShown = showRing }

        val building = hud.mode is InputMode.Build
        setShown(gridEntity, building, gridShown).also { gridShown = building }
        if (ghostTimer > 0f) ghostTimer -= dt
        val ghost = ghostTimer > 0f
        setShown(ghostEntity, ghost, ghostShown).also { ghostShown = ghost }

        // Volley impact markers.
        val vIt = volleyMarkers.iterator()
        var anyVolley = false
        while (vIt.hasNext()) {
            val m = vIt.next()
            if (m.second <= 0f) { vIt.remove(); continue }
            anyVolley = true
        }
        if (anyVolley) {
            val m = volleyMarkers.last()
            r3d.setTransform(volleyEntity, m.first[0], 0f, m.first[1], scale = Content.VOLLEY_RADIUS)
            volleyMarkers[volleyMarkers.size - 1] = m.first to (m.second - dt * hud.speed)
            r3d.scene.addEntity(volleyEntity)
        } else r3d.scene.remove(volleyEntity)
    }

    private fun setShown(e: Int, show: Boolean, was: Boolean) {
        if (show && !was) r3d.scene.addEntity(e)
        if (!show && was) r3d.scene.remove(e)
    }

    private fun updateTexts(dt: Float) {
        val it = floatingTexts.iterator()
        while (it.hasNext()) {
            val t = it.next()
            t.life -= dt
            t.y += dt * (if (t.big) 0.5f else 0.9f)
            if (t.life <= 0f) it.remove()
        }
    }

    // ------------------------------------------------------------ events -> juice

    private fun handleEvents(events: List<GameEvent>) {
        for (ev in events) when (ev) {
            is GameEvent.Shot -> {
                val s = game.structures[ev.structureId] ?: continue
                if (ev.visual == "catapult") fx.burst(s.cx, 0.9f, s.cy, 4, Palette.DIRT, speed = 1f, up = 1f, size = 0.07f)
            }
            is GameEvent.Hit -> {
                if (ev.splash > 0f) {
                    fx.burst(ev.x, 0.2f, ev.y, 14, Palette.DIRT_DARK, speed = 3f, up = 3.5f, size = 0.1f, life = 0.8f)
                    fx.burst(ev.x, 0.3f, ev.y, 10, 0xFFA640, glow = true, speed = 2.5f, up = 2f, size = 0.12f, life = 0.35f, glowIntensity = 3f)
                    shake = max(shake, 0.25f)
                } else {
                    fx.burst(ev.x, 0.35f, ev.y, 4, 0xFFE2A0, glow = true, speed = 1.8f, up = 1.5f, size = 0.05f, life = 0.25f, glowIntensity = 2f)
                }
                if (ev.damage > 0 && ev.splash == 0f) floatingTexts += FloatingText(ev.x, 0.9f, ev.y, ev.damage.toString(), 0xFFFFFFFF.toInt())
            }
            is GameEvent.EnemyDied -> {
                val col = when (ev.defId) { "wolf" -> Palette.WOLF; "brute" -> 0x5C3B2A; "warlord" -> 0x3A3346; else -> Palette.CLOTH_RED }
                fx.burst(ev.x, 0.3f, ev.y, 10, col, speed = 2.2f, up = 3f, size = 0.08f, life = 0.9f)
                fx.burst(ev.x, 0.4f, ev.y, 5 + ev.bounty.coerceAtMost(12), Palette.GOLD, glow = true, speed = 1.2f, up = 4.5f, size = 0.07f, life = 0.7f, glowIntensity = 2.5f)
                floatingTexts += FloatingText(ev.x, 1.1f, ev.y, "+${ev.bounty}", 0xFFFFD34D.toInt())
                if (ev.defId == "warlord") {
                    shake = 0.6f
                    toast("Warlord slain!", 0xFFFFD34D)
                }
            }
            is GameEvent.RankUp -> {
                val s = game.structures[ev.structureId] ?: continue
                fx.burst(s.cx, 1.6f, s.cy, 18, 0xFFE066, glow = true, speed = 1.5f, up = 2.5f, size = 0.06f, life = 1f, gravity = 2f, glowIntensity = 3f)
                floatingTexts += FloatingText(s.cx, 2.2f, s.cy, "RANK ${roman(ev.rank)}", 0xFFFFE066.toInt(), big = true)
            }
            is GameEvent.WaveStarted -> toast("Wave ${ev.wave} — the Marauders attack!", 0xFFFF7A59)
            is GameEvent.WaveCleared -> {
                toast("Wave ${ev.wave} repelled! +${ev.reward} gold", 0xFF7CFC9A)
                save()
            }
            is GameEvent.KeepHit -> {
                shake = max(shake, 0.35f)
                fx.burst(ev.x, 0.6f, ev.y, 8, Palette.STONE, speed = 2f, up = 2.5f, size = 0.08f)
                floatingTexts += FloatingText(ev.x, 1.4f, ev.y, "-${ev.damage}", 0xFFFF5A4A.toInt())
            }
            is GameEvent.RaidLost -> {
                toast("The Keep fell! Raiders looted 20% of your resources.", 0xFFFF5A4A)
                shake = 0.8f
            }
            is GameEvent.VolleyCast -> volleyMarkers += floatArrayOf(ev.x, ev.y) to Content.VOLLEY_DELAY
            is GameEvent.VolleyImpact -> {
                fx.burst(ev.x, 0.15f, ev.y, 26, Palette.WOOD, speed = 3.2f, up = 2.5f, size = 0.06f, life = 0.7f)
                fx.burst(ev.x, 0.2f, ev.y, 12, 0xFFE2A0, glow = true, speed = 3f, up = 1.5f, size = 0.05f, life = 0.3f, glowIntensity = 2f)
                shake = max(shake, 0.2f)
            }
            is GameEvent.Built -> {
                val s = game.structures[ev.structureId] ?: continue
                fx.burst(s.cx, 0.1f, s.cy, 12, Palette.DIRT, speed = 1.6f, up = 1.8f, size = 0.07f)
            }
            is GameEvent.Upgraded -> {
                val s = game.structures[ev.structureId] ?: continue
                fx.burst(s.cx, 1f, s.cy, 16, 0x9FE3FF, glow = true, speed = 1.5f, up = 3f, size = 0.06f, life = 0.8f, gravity = 3f, glowIntensity = 2.5f)
            }
            is GameEvent.Removed -> Unit
        }
    }

    private fun toast(text: String, color: Long) {
        val t = Toast(toastId++, text, color)
        hud.toasts.add(t)
        if (hud.toasts.size > 3) hud.toasts.removeAt(0)
    }

    fun dismissToast(t: Toast) {
        hud.toasts.remove(t)
    }

    // ------------------------------------------------------------ HUD

    fun refreshHud() {
        val g = game
        hud.gold = g.res.gold.toInt()
        hud.wood = g.res.wood.toInt()
        hud.stone = g.res.stone.toInt()
        hud.wave = g.wave
        hud.bestWave = g.bestWave
        hud.phase = g.phase
        hud.countdown = g.countdown.toInt().coerceAtLeast(0)
        hud.enemiesLeft = g.enemies.size
        hud.keepHp = g.keepHp.coerceAtLeast(0)
        hud.volleyReady = 1f - g.volleyCooldown / Content.VOLLEY_COOLDOWN
        hud.preview = g.nextWavePreview()
        hud.selected = hud.selected?.let { sel -> g.structures[sel.id]?.let { info(it) } }
    }

    private fun info(s: Structure): SelectedInfo {
        val name: String
        val maxLevel: Int
        val stats: String
        when (s.kind) {
            StructureKind.KEEP -> { name = "The Keep"; maxLevel = 1; stats = "HP ${game.keepHp}/${Content.KEEP_MAX_HP} · produces gold, wood, stone" }
            StructureKind.TOWER -> {
                val d = s.towerDef
                name = d.name; maxLevel = d.upgradeCosts.size + 1
                val dmg = d.damage * Content.levelDamageMult(s.level) * Content.rankDamageMult(s.rank)
                val range = d.range + Content.levelRangeBonus(s.level) + Content.rankRangeBonus(s.rank)
                stats = "DMG ${"%.0f".format(dmg)} · RNG ${"%.1f".format(range)} · ${d.damageType.name.lowercase().replace('_', '-')}"
            }
            StructureKind.WALL -> { name = "Stone Wall"; maxLevel = 1; stats = "Blocks raider paths" }
            StructureKind.BUILDING -> {
                val d = Content.building(s.defId)
                name = d.name; maxLevel = d.upgradeCosts.size + 1
                val p = d.produces * Content.levelProductionMult(s.level)
                stats = listOfNotNull(
                    p.gold.takeIf { it > 0 }?.let { "+$it gold/min" },
                    p.wood.takeIf { it > 0 }?.let { "+$it wood/min" },
                    p.stone.takeIf { it > 0 }?.let { "+$it stone/min" },
                ).joinToString(" · ")
            }
        }
        val next = Content.rankXp.firstOrNull { it > s.xp }
        return SelectedInfo(
            s.id, name, s.kind, s.level, maxLevel, s.rank, s.xp, next, s.kills,
            game.upgradeCost(s), s.invested * Content.SELL_REFUND, stats,
        )
    }

    // ------------------------------------------------------------ input

    fun onTap(sx: Float, sy: Float) {
        val p = r3d.screenToGround(sx, sy) ?: return
        val tx = floor(p[0]).toInt()
        val ty = floor(p[2]).toInt()
        when (val mode = hud.mode) {
            is InputMode.Build -> {
                if (!game.inBounds(tx, ty)) return
                val result = game.execute(Command.Place(mode.defId, tx, ty))
                r3d.setTransform(ghostEntity, tx + 0.5f, 0f, ty + 0.5f)
                ghostTimer = 0.35f
                if (result is CommandResult.Rejected) {
                    r3d.setTint(ghostMi, 0xFF4444, 0.5f)
                    toast(result.reason, 0xFFFFB0A0)
                } else {
                    r3d.setTint(ghostMi, 0x55FF77, 0.45f)
                    handleEvents(game.drainEvents())
                    // Keep building; stop automatically when out of resources.
                    if (!game.res.canAfford(game.costOf(mode.defId))) hud.mode = InputMode.Normal
                }
            }
            InputMode.Volley -> {
                val r = game.execute(Command.CastVolley(p[0], p[2]))
                if (r is CommandResult.Rejected) toast(r.reason, 0xFFFFB0A0) else handleEvents(game.drainEvents())
                hud.mode = InputMode.Normal
            }
            InputMode.Normal -> {
                hud.selected = pickStructure(sx, sy)?.let { info(it) }
            }
        }
        refreshHud()
    }

    /** Picks the structure nearest to the tap, testing both ground level and tower-top height. */
    private fun pickStructure(sx: Float, sy: Float): Structure? {
        var best: Structure? = null
        var bestD = Float.MAX_VALUE
        for (h in floatArrayOf(0f, 0.6f, 1.2f)) {
            val p = r3d.screenToGround(sx, sy, h) ?: continue
            for (s in game.structures.values) {
                val dx = p[0] - s.cx
                val dz = p[2] - s.cy
                val d = kotlin.math.sqrt(dx * dx + dz * dz) - (s.w - 1) * 0.5f
                if (d < 0.75f && d < bestD) {
                    bestD = d
                    best = s
                }
            }
        }
        return best
    }

    fun onPan(centroidX: Float, centroidY: Float, dx: Float, dy: Float) {
        val a = r3d.screenToGround(centroidX, centroidY) ?: return
        val b = r3d.screenToGround(centroidX + dx, centroidY + dy) ?: return
        r3d.camTargetX = (r3d.camTargetX - (b[0] - a[0])).coerceIn(0f, game.width.toFloat())
        r3d.camTargetZ = (r3d.camTargetZ - (b[2] - a[2])).coerceIn(0f, game.height.toFloat())
    }

    fun onZoom(factor: Float) {
        r3d.camDistance = (r3d.camDistance / factor).coerceIn(9f, 34f)
        // Look more top-down when zoomed out, more cinematic when zoomed in.
        r3d.camPitchDeg = 44f + (r3d.camDistance - 9f) / 25f * 22f
    }

    fun selectBuild(defId: String?) {
        hud.selected = null
        hud.mode = if (defId == null || (hud.mode as? InputMode.Build)?.defId == defId) InputMode.Normal else InputMode.Build(defId)
    }

    fun armVolley() {
        if (game.volleyCooldown > 0f) {
            toast("Volley recharging", 0xFFFFB0A0)
            return
        }
        hud.selected = null
        hud.mode = if (hud.mode == InputMode.Volley) InputMode.Normal else InputMode.Volley
    }

    fun callWave() {
        val r = game.execute(Command.StartWave)
        if (r is CommandResult.Ok) handleEvents(game.drainEvents())
        refreshHud()
    }

    fun upgradeSelected() {
        val sel = hud.selected ?: return
        val r = game.execute(Command.Upgrade(sel.id))
        if (r is CommandResult.Rejected) toast(r.reason, 0xFFFFB0A0) else handleEvents(game.drainEvents())
        refreshHud()
    }

    fun sellSelected() {
        val sel = hud.selected ?: return
        val r = game.execute(Command.Sell(sel.id))
        if (r is CommandResult.Rejected) toast(r.reason, 0xFFFFB0A0)
        hud.selected = null
        refreshHud()
    }

    fun deselect() {
        hud.selected = null
    }

    fun toggleSpeed() {
        hud.speed = when (hud.speed) { 1 -> 2; 2 -> 3; else -> 1 }
    }

    fun togglePause() {
        hud.paused = !hud.paused
    }

    companion object {
        /** Units are drawn larger than their footprint so they read well on a phone screen. */
        const val UNIT_VISUAL_SCALE = 1.45f

        fun roman(n: Int) = when (n) { 1 -> "I"; 2 -> "II"; 3 -> "III"; 4 -> "IV"; else -> "V" }
    }
}
