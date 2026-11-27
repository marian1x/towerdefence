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
import com.marian1x.agesofsiege.core.BaseGame
import com.marian1x.agesofsiege.core.Campaign
import com.marian1x.agesofsiege.core.CampaignSave
import com.marian1x.agesofsiege.core.CampaignSettings
import com.marian1x.agesofsiege.core.Command
import com.marian1x.agesofsiege.core.CommandResult
import com.marian1x.agesofsiege.core.Content
import com.marian1x.agesofsiege.core.Cost
import com.marian1x.agesofsiege.core.EnemyDef
import com.marian1x.agesofsiege.core.GameEvent
import com.marian1x.agesofsiege.core.Phase
import com.marian1x.agesofsiege.core.SaveData
import com.marian1x.agesofsiege.core.Structure
import com.marian1x.agesofsiege.core.StructureKind
import com.marian1x.agesofsiege.core.TowerSpecialization
import com.marian1x.agesofsiege.engine.Mesh
import com.marian1x.agesofsiege.engine.MeshBuilder
import com.marian1x.agesofsiege.engine.Model
import com.marian1x.agesofsiege.engine.ModelLibrary
import com.marian1x.agesofsiege.engine.Models
import com.marian1x.agesofsiege.engine.Palette
import com.marian1x.agesofsiege.engine.Renderer3D
import com.marian1x.agesofsiege.engine.TowerArt
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
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
    val specialization: TowerSpecialization?,
)

data class Toast(val id: Long, val text: String, val color: Long)

enum class Screen { MENU, GAME, WORLD }
enum class Overlay { NONE, PAUSE, SETTINGS, NEW_GAME, ARMY, CONFIRM_RESTART, MULTIPLAYER, SPECIALIZATION }

/** Player preferences (device-local, not part of the save). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var highQuality by mutableStateOf(sp.getBoolean("hq", true))
    var damageNumbers by mutableStateOf(sp.getBoolean("dmg", true))
    var cameraShake by mutableStateOf(sp.getBoolean("shake", true))
    var relayEndpoint: String
        get() = sp.getString("relayEndpoint", "").orEmpty()
        set(value) { sp.edit().putString("relayEndpoint", value).apply() }

    fun save() {
        sp.edit().putBoolean("hq", highQuality).putBoolean("dmg", damageNumbers).putBoolean("shake", cameraShake).apply()
    }
}

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

    var screen by mutableStateOf(Screen.MENU)
    var overlay by mutableStateOf(Overlay.NONE)
    var garrison by mutableStateOf<Map<String, Int>>(emptyMap())
    var armyCap by mutableIntStateOf(0)
    var away by mutableIntStateOf(0)
    var hasBarracks by mutableStateOf(false)
    var settings by mutableStateOf(CampaignSettings())
    var expeditionCount by mutableIntStateOf(0)
    val reports = mutableStateListOf<Pair<String, Boolean>>()
    /** Realm cell picked in the 3D realm view. */
    var realmSel by mutableStateOf<Pair<Int, Int>?>(null)
}

/** Where a camera looks from: orbit target, distance, pitch and yaw. One per view (base / realm). */
private class CamRig(var x: Float, var z: Float, var dist: Float, var yaw: Float)

class GameController(private val context: Context) : Choreographer.FrameCallback {
    val r3d = Renderer3D(context)
    private val models = ModelLibrary(r3d, context)
    val hud = HudState()
    /** Bumped every frame so the overlay canvas redraws. */
    val frame = mutableIntStateOf(0)
    val floatingTexts = ArrayList<FloatingText>()

    val prefs = Prefs(context)
    var campaign: Campaign = loadOrNew()
        private set
    val game: BaseGame get() = campaign.base
    val hasSave: Boolean get() = saveFile.exists()

    private val meshCache = HashMap<String, Mesh>()
    private val fx = Fx(r3d, mesh("cube") { Models.cube(this) })
    val realm = RealmView(r3d, models)
    private val villagers = Villagers(r3d, models)
    private var smokeTimer = 0f
    private val baseRig = CamRig(14f, 8.6f, 26f, 0f)
    private val realmRig = CamRig(0f, 0f, 44f, 0f)
    /** Distance the camera is easing toward (rising into the realm / dropping into the base). */
    private var zoomTo: Float? = null
    private var zoomOverscroll = 0f

    // Static world: glTF ground + obstacles, procedural water.
    private val worldModels = ArrayList<Model>()
    private var waterEntity = 0

    private class Piece(val root: Int, val top: Float, val model: Model? = null)

    private class StructureVisual(
        val base: Piece,
        val head: Piece?,
        val detail: Piece?,
        val level: Int,
        val specialization: TowerSpecialization?,
        val headY: Float,
    ) {
        val crew get() = head?.model?.name == "archer_crew"
        var animTime = Random.nextFloat() * 3f
        /** 1 -> 0 after being selected: a short squash-and-stretch so the player sees what was picked. */
        var pop = 0f
    }

    private class EnemyVisual(val model: Model, val def: EnemyDef) {
        var lastHp = Float.MAX_VALUE
        var pop = 0f
        var animTime = Random.nextFloat() * 2f
        var yaw = 0f
        var x = 0f
        var z = 0f
    }

    private class TroopVisual(val model: Model, val scale: Float) {
        var animTime = Random.nextFloat() * 2f
        var yaw = 0f
        var x = 0f
        var z = 0f
    }

    private class Corpse(val model: Model, val x: Float, val z: Float, val yaw: Float, val scale: Float) {
        var t = 0f
    }

    private val structureVisuals = HashMap<Int, StructureVisual>()
    private val enemyVisuals = HashMap<Int, EnemyVisual>()
    private val troopVisuals = HashMap<Int, TroopVisual>()
    private val troopDiedIds = HashSet<Int>()
    private val corpses = ArrayList<Corpse>()
    private val diedIds = HashSet<Int>()
    private val projectileVisuals = HashMap<Int, Piece>()
    private val towerMaterial = r3d.litInstance(Palette.WHITE)
    private var selectionModel: Model? = null
    private var selectionFor = -1

    // Overlays: selection range ring, build ghost, grid, volley marker.
    private val ringMi = r3d.litInstance(0xFFFFFF, r3d.ghostMaterial, 0.5f)
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
        models.warmUp("raider" to 16, "arrow" to 16, "archer_crew" to 8)
        meshPiece("entrance-banners") { TowerArt.gates(this, game.gates) }
        buildWorld()
        r3d.camTargetX = 14f
        r3d.camTargetZ = 8.6f
        r3d.setQuality(prefs.highQuality)
        refreshHud()
    }

    /** The simulation only runs while playing (not on menus or open dialogs). */
    private val simulating: Boolean
        get() = if (inMatch) {
            mp?.running == true
        } else {
            hud.screen != Screen.MENU && !hud.paused && (hud.overlay == Overlay.NONE || hud.overlay == Overlay.ARMY)
        }

    // ------------------------------------------------------------ multiplayer

    /** The current two-player session (lobby or match), or null. */
    var mp by mutableStateOf<MpSession?>(null)
        private set

    val inMatch: Boolean get() = campaign.mp != null

    fun hostMatch(
        name: String,
        mode: com.marian1x.agesofsiege.core.MatchMode,
        difficulty: com.marian1x.agesofsiege.core.Difficulty,
        pace: com.marian1x.agesofsiege.core.Pace,
        endpoint: com.marian1x.agesofsiege.net.RelayEndpoint? = null,
        hostKey: String = "",
    ) {
        mp?.close()
        if (endpoint != null) prefs.relayEndpoint = endpoint.toString()
        mp = MpSession.host(this, context, name.ifBlank { "Host" }, mode, difficulty, pace, endpoint, hostKey)
    }

    fun joinMatch(name: String, host: String, port: Int) {
        mp?.close()
        mp = MpSession.join(this, name.ifBlank { "Guest" }, host.trim(), port)
    }

    fun hostLobbyMatch(
        name: String,
        mode: com.marian1x.agesofsiege.core.MatchMode,
        invite: com.marian1x.agesofsiege.net.RelayInvite,
        ticket: String,
    ) {
        mp?.close()
        mp = MpSession.host(
            this, context, name, mode,
            com.marian1x.agesofsiege.core.Difficulty.NORMAL,
            com.marian1x.agesofsiege.core.Pace.STANDARD,
            endpoint = invite.endpoint, hostKey = ticket, lobbyInvite = invite,
        )
    }

    fun joinOnlineMatch(name: String, invite: com.marian1x.agesofsiege.net.RelayInvite) {
        mp?.close()
        mp = MpSession.joinOnline(this, name.ifBlank { "Guest" }, invite)
    }

    /** Guest: dial the host again after a dropped connection (the base is kept). */
    fun reconnectMatch() {
        mp?.reconnect()
    }

    /** Leaves the lobby without playing. */
    fun cancelMatch() {
        mp?.close()
        mp = null
    }

    /** Both phones enter the match: a fresh base for each, the shared realm from [setup]. */
    internal fun beginMatch(session: MpSession, setup: com.marian1x.agesofsiege.core.MatchSetup, slot: Int) {
        if (campaign.mp?.setup?.matchId == setup.matchId) {
            // Reconnected to the same match: keep this phone's base as it is.
            mp = session
            hud.overlay = Overlay.NONE
            toast("Reconnected!", 0xFF7CFC9A)
            return
        }
        if (campaign.mp == null) save() // keep the single-player game safe
        mp = session
        clearVisuals()
        campaign = Campaign(setup.seed, CampaignSettings(session.myName, setup.difficulty, setup.pace)).also { it.setupMultiplayer(setup, slot) }
        buildWorld()
        resetHudForNewGame()
        val other = setup.players.first { it.slot != slot }.name
        toast(if (setup.mode == com.marian1x.agesofsiege.core.MatchMode.VERSUS) "Match on! Defend your keep and raid $other's capital." else "Co-op with $other: defeat the rival lord together!", 0xFF7CFC9A)
    }

    /** Ends the match for this phone and goes back to the single-player game. */
    fun leaveMatch() {
        mp?.close()
        mp = null
        clearVisuals()
        campaign = loadOrNew()
        buildWorld()
        resetHudForNewGame()
        hud.screen = Screen.MENU
        refreshHud()
    }

    private fun resetHudForNewGame() {
        hud.selected = null
        hud.mode = InputMode.Normal
        hud.paused = false
        hud.reports.clear()
        hud.realmSel = null
        realmRig.x = 0f; realmRig.z = 0f; realmRig.dist = 44f; realmRig.yaw = 0f
        hud.toasts.clear()
        hud.overlay = Overlay.NONE
        hud.screen = Screen.GAME
        refreshHud()
    }

    internal fun mpToast(text: String, good: Boolean) = toast(text, if (good) 0xFF7CFC9A else 0xFFFFB070)

    internal fun mpReport(text: String, good: Boolean) {
        toast(text, if (good) 0xFF7CFC9A else 0xFFFFB070)
        hud.reports.add(0, text to good)
        if (hud.reports.size > 30) hud.reports.removeAt(hud.reports.size - 1)
    }

    /** Co-op: give resources to the ally. */
    fun sendSupplies(amount: Cost) {
        val err = mp?.sendSupplies(amount)
        if (err != null) toast(err, 0xFFFFB0A0) else toast("Supplies are on their way", 0xFF7CFC9A)
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
        mp?.close()
        models.destroy()
        r3d.destroy()
    }

    private val saveFile get() = File(context.filesDir, "base_save.json")

    private fun loadOrNew(): Campaign {
        val f = File(context.filesDir, "base_save.json")
        if (f.exists()) {
            try {
                val text = f.readText()
                return if (text.contains("\"base\"")) {
                    val data = CampaignSave.fromJson(text)
                    Campaign(data.seed, data.settings).also { it.loadFrom(data) }
                } else {
                    // Save from version 0.1 (base only): keep the base, start a fresh world around it.
                    val data = SaveData.fromJson(text)
                    Campaign(data.seed, CampaignSettings()).also { it.base.loadFrom(data) }
                }
            } catch (t: Throwable) {
                Log.w("AgesOfSiege", "Could not load save, starting fresh", t)
            }
        }
        return Campaign(System.currentTimeMillis() and 0xffffff, CampaignSettings())
    }

    fun save() {
        if (campaign.mp != null) return // matches are not saved over the single-player game
        try {
            saveFile.writeText(campaign.toSave().toJson())
        } catch (t: Throwable) {
            Log.w("AgesOfSiege", "Save failed", t)
        }
    }

    /** Starts a brand-new campaign (also used by Restart). */
    fun newGame(settings: CampaignSettings) {
        clearVisuals()
        campaign = Campaign(System.currentTimeMillis() and 0xffffff, settings)
        buildWorld()
        hud.selected = null
        hud.mode = InputMode.Normal
        hud.paused = false
        hud.reports.clear()
        hud.realmSel = null
        realmRig.x = 0f; realmRig.z = 0f; realmRig.dist = 44f; realmRig.yaw = 0f
        hud.toasts.clear()
        hud.overlay = Overlay.NONE
        hud.screen = Screen.GAME
        save()
        refreshHud()
    }

    fun continueGame() {
        hud.overlay = Overlay.NONE
        hud.screen = Screen.GAME
    }

    fun toMainMenu() {
        save()
        hud.overlay = Overlay.NONE
        hud.screen = Screen.MENU
    }

    fun updateCampaignSettings(s: CampaignSettings) {
        campaign.updateSettings(s)
        hud.settings = s
    }

    fun applyPrefs() {
        prefs.save()
        r3d.setQuality(prefs.highQuality)
    }

    // ------------------------------------------------------------ models

    private fun mesh(key: String, build: MeshBuilder.() -> Unit): Mesh =
        meshCache.getOrPut(key) { r3d.upload(MeshBuilder().apply(build)) }

    private fun baseModelName(s: Structure): String = when (s.defId) {
        "keep" -> "keep"
        "wall" -> "wall_block"
        else -> "${s.defId}_${s.level.coerceIn(1, 3)}"
    }

    private fun headModelName(s: Structure): String? = when (s.defId) {
        "archer" -> "archer_crew"
        "ballista" -> "ballista_head"
        "catapult" -> "catapult_head"
        else -> null
    }

    private fun modelPiece(name: String, batch: Int): Piece {
        val model = models.acquire(name, batch)
        return Piece(model.root, models.bounds(name)?.get(4) ?: 1f, model)
    }

    private fun meshPiece(key: String, build: MeshBuilder.() -> Unit): Piece {
        val geometry = mesh(key, build)
        val entity = r3d.createRenderable(geometry, towerMaterial)
        r3d.scene.addEntity(entity)
        return Piece(entity, geometry.box.center[1] + geometry.box.halfExtent[1])
    }

    private fun release(piece: Piece) {
        val model = piece.model
        if (model != null) models.release(model) else r3d.destroyEntity(piece.root)
    }

    private fun createStructureVisual(s: Structure): StructureVisual {
        val baseName = baseModelName(s)
        val tier = s.level.coerceIn(1, 3)
        val base = if (TowerArt.hasBody(s.defId)) {
            meshPiece(baseName) { TowerArt.body(this, s.defId, tier) }
        } else {
            modelPiece(baseName, if (s.kind == StructureKind.WALL) 24 else 6)
        }
        val head = if (s.defId == "repeater") {
            meshPiece("repeater-head") { TowerArt.repeaterHead(this) }
        } else {
            headModelName(s)?.let { modelPiece(it, 6) }
        }
        val headY = when (s.defId) {
            "repeater" -> TowerArt.REPEATER_MOUNT
            "frost" -> 1.25f
            "brazier" -> 0.83f
            else -> models.headY(baseName)
        }
        val detail = if (s.kind == StructureKind.TOWER && (s.level >= 4 || s.specialization != null)) {
            meshPiece("tower-detail-${s.level}-${s.specialization}") {
                TowerArt.upgradeDetail(this, s.level, s.specialization)
            }
        } else null
        r3d.setTransform(base.root, s.cx, 0f, s.cy)
        detail?.let { r3d.setTransform(it.root, s.cx, 0f, s.cy) }
        return StructureVisual(base, head, detail, s.level, s.specialization, headY)
    }

    private fun releaseStructureVisual(visual: StructureVisual) {
        release(visual.base)
        visual.head?.let(::release)
        visual.detail?.let(::release)
    }

    private fun enemyModelName(defId: String) = when (defId) {
        "outrider", "brute", "warlord" -> defId
        else -> "raider"
    }

    // ------------------------------------------------------------ static world

    private fun buildWorld() {
        for (m in worldModels) models.release(m)
        worldModels.clear()
        val g = game

        // Island of Kenney tiles (built by tools/blender/build_assets.py) – one draw call.
        val ground = models.acquire("ground", 1)
        r3d.setTransform(ground.root, 0f, 0f, 0f)
        worldModels += ground

        // Animated water all around the island.
        if (waterEntity == 0) {
            val water = MeshBuilder().apply {
                val cx = g.width / 2f
                val cz = g.height / 2f
                val n = 24
                val size = 160f
                val step = size / n
                for (i in 0 until n) for (j in 0 until n) {
                    val x0 = cx - size / 2 + i * step
                    val z0 = cz - size / 2 + j * step
                    quad(
                        com.marian1x.agesofsiege.engine.V3(x0, 0f, z0 + step),
                        com.marian1x.agesofsiege.engine.V3(x0 + step, 0f, z0 + step),
                        com.marian1x.agesofsiege.engine.V3(x0 + step, 0f, z0),
                        com.marian1x.agesofsiege.engine.V3(x0, 0f, z0),
                        Palette.WHITE,
                    )
                }
            }
            waterEntity = r3d.createRenderable(r3d.upload(water), r3d.waterMaterial.createInstance(), shadows = false)
            r3d.setTransform(waterEntity, 0f, WATER_LEVEL, 0f)
            r3d.scene.addEntity(waterEntity)
        }

        // Trees and rocks inside the base (seeded per save).
        for (o in g.obstacles) {
            val name = when {
                o.kind == 1 -> "boulders"
                o.variant % 2 == 0 -> "grove"
                else -> "pines"
            }
            val m = models.acquire(name, 8)
            r3d.setTransform(m.root, o.x + 0.5f, 0f, o.y + 0.5f, yaw = (o.variant % 628) / 100f)
            worldModels += m
        }

        // Faint build grid (shown in build mode).
        val grid = MeshBuilder().apply {
            for (x in 0..g.width) box(x - 0.012f, 0.004f, 0f, x + 0.012f, 0.01f, g.height.toFloat(), Palette.WHITE)
            for (y in 0..g.height) box(0f, 0.004f, y - 0.012f, g.width.toFloat(), 0.01f, y + 0.012f, Palette.WHITE)
        }
        if (gridEntity != 0) r3d.destroyEntity(gridEntity)
        gridEntity = r3d.createRenderable(r3d.upload(grid), r3d.litInstance(0xFFFFFF, r3d.ghostMaterial, 0.22f), shadows = false)
        r3d.setTransform(gridEntity, 0f, 0f, 0f)
        gridShown = false
    }

    /** Lazy grey puffs rising from house roofs. */
    private fun chimneySmoke(dt: Float) {
        smokeTimer -= dt
        if (smokeTimer > 0f) return
        smokeTimer = 0.45f
        for (s in game.structures.values) {
            if (s.defId != "house" || Random.nextFloat() > 0.5f) continue
            val top = models.bounds(baseModelName(s))?.get(4) ?: 1f
            fx.burst(s.cx + 0.12f, top - 0.05f, s.cy - 0.1f, 1, 0xC9CDD2, speed = 0.12f, up = 0.35f, size = 0.11f, life = 2.6f, gravity = -0.12f, grow = true)
        }
    }

    private fun clearVisuals() {
        villagers.clear()
        structureVisuals.values.forEach(::releaseStructureVisual)
        structureVisuals.clear()
        for (v in enemyVisuals.values) models.release(v.model)
        enemyVisuals.clear()
        for (v in troopVisuals.values) models.release(v.model)
        troopVisuals.clear()
        for (c in corpses) models.release(c.model)
        corpses.clear()
        projectileVisuals.values.forEach(::release)
        projectileVisuals.clear()
        selectionModel?.let { models.release(it) }
        selectionModel = null
        selectionFor = -1
        fx.clear()
        floatingTexts.clear()
    }

    // ------------------------------------------------------------ frame loop

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        Choreographer.getInstance().postFrameCallback(this)
        val dt = if (lastNanos == 0L) 0f else ((frameTimeNanos - lastNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastNanos = frameTimeNanos

        mp?.tick(dt)
        if (simulating) {
            acc += dt * (if (inMatch) 1 else hud.speed)
            var steps = 0
            while (acc >= BaseGame.DT && steps < 12) {
                campaign.step()
                handleEvents(game.drainEvents())
                acc -= BaseGame.DT
                steps++
            }
            if (steps == 12) acc = 0f
        }
        val alpha = (acc / BaseGame.DT).coerceIn(0f, 1f)
        val simDt = if (!simulating) 0f else dt * (if (inMatch) 1 else hud.speed)
        syncView()
        if (hud.screen == Screen.MENU) {
            // Slow cinematic orbit behind the main menu.
            r3d.camYawDeg = (r3d.camYawDeg + dt * 4f) % 360f
        } else {
            zoomTo?.let { goal ->
                r3d.camDistance += (goal - r3d.camDistance) * min(1f, dt * 4f)
                if (kotlin.math.abs(goal - r3d.camDistance) < 0.05f) zoomTo = null
                applyZoomPitch()
            }
            // Ease toward the player's chosen rotation.
            var d = gameYaw - r3d.camYawDeg
            while (d > 180f) d -= 360f
            while (d < -180f) d += 360f
            r3d.camYawDeg = (r3d.camYawDeg + d * min(1f, dt * 12f) + 360f) % 360f
            glide(dt)
        }

        if (r3d.showingRealm) realm.update(campaign, dt, hud.realmSel)
        villagers.update(game, simDt)
        chimneySmoke(simDt)
        syncStructures(simDt)
        syncEnemies(alpha, simDt)
        syncTroops(alpha, simDt)
        syncCorpses(simDt)
        syncProjectiles(alpha)
        syncOverlays(dt)
        fx.update(simDt)
        updateTexts(simDt)
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

    /** World height just above a structure's top (for HUD markers). */
    fun markerHeight(s: Structure): Float = structureTop(s) + 0.18f

    private fun applyShake(dt: Float) {
        if (shake <= 0f) return
        shake = max(0f, shake - dt * 2.5f)
    }

    private fun shakeOffset(): Float =
        if (shake > 0f && prefs.cameraShake) sin(game.time * 90f) * shake * 0.12f else 0f

    private fun troopModelName(defId: String) = when (defId) {
        "bowman" -> "archer_crew"
        "knight" -> "warlord"
        else -> "swordsman"
    }

    private fun syncTroops(alpha: Float, dt: Float) {
        val alive = HashSet<Int>()
        for (t in game.troops) {
            if (!t.alive) continue
            alive += t.id
            val v = troopVisuals.getOrPut(t.id) {
                TroopVisual(models.acquire(troopModelName(t.def.id), 8), TROOP_SCALE).also { it.yaw = t.heading }
            }
            v.x = t.prevX + (t.x - t.prevX) * alpha
            v.z = t.prevY + (t.y - t.prevY) * alpha
            var dy = t.heading - v.yaw
            while (dy > Math.PI) dy -= (2 * Math.PI).toFloat()
            while (dy < -Math.PI) dy += (2 * Math.PI).toFloat()
            v.yaw += dy * min(1f, dt * 10f)
            v.animTime += dt
            r3d.setTransform(v.model.root, v.x, 0f, v.z, yaw = v.yaw, scale = v.scale)
            when {
                t.sinceAttack < 0.5f && t.def.id == "bowman" -> v.model.animate("holding-both-shoot", t.sinceAttack, loop = false)
                t.sinceAttack < 0.5f -> v.model.animate("attack-melee-right", t.sinceAttack, loop = false)
                t.moving -> v.model.animate(if (t.def.id == "knight") "sprint" else "walk", v.animTime)
                else -> v.model.animate("idle", v.animTime)
            }
        }
        val it = troopVisuals.entries.iterator()
        while (it.hasNext()) {
            val (id, v) = it.next()
            if (id !in alive) {
                if (id in troopDiedIds) corpses += Corpse(v.model, v.x, v.z, v.yaw, v.scale) else models.release(v.model)
                troopDiedIds.remove(id)
                it.remove()
            }
        }
    }

    private fun syncStructures(dt: Float) {
        val g = game
        val it = structureVisuals.entries.iterator()
        while (it.hasNext()) {
            val (id, v) = it.next()
            val s = g.structures[id]
            if (s == null || s.level != v.level || s.specialization != v.specialization) {
                releaseStructureVisual(v)
                it.remove()
            }
        }
        for (s in g.structures.values) {
            val v = structureVisuals.getOrPut(s.id) { createStructureVisual(s) }
            val head = v.head ?: continue
            v.animTime += dt
            if (v.crew) {
                // Archer on the battlements: shoot animation right after firing, idle otherwise.
                r3d.setTransform(head.root, s.cx, v.headY, s.cy, yaw = s.aim, scale = 0.5f)
                if (s.sinceShot < 0.6f) head.model?.animate("holding-both-shoot", s.sinceShot, loop = false)
                else head.model?.animate("idle", v.animTime)
            } else {
                val recoil = if (s.sinceShot < 0.18f) (1f - s.sinceShot / 0.18f) else 0f
                val kickPitch = if (s.defId == "catapult") -recoil * 0.45f else 0f
                val back = recoil * 0.06f
                r3d.setTransform(head.root, s.cx - sin(s.aim) * back, v.headY, s.cy - cos(s.aim) * back, yaw = s.aim, pitch = kickPitch)
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
                EnemyVisual(models.acquire(enemyModelName(e.def.id), 12), e.def).also {
                    it.lastHp = e.hp
                    it.yaw = e.heading
                }
            }
            if (e.hp < v.lastHp - 0.01f) v.pop = 1f
            v.lastHp = e.hp
            if (v.pop > 0f) v.pop = max(0f, v.pop - dt * 7f)
            v.x = e.prevX + (e.x - e.prevX) * alpha
            v.z = e.prevY + (e.y - e.prevY) * alpha
            // Smoothly turn toward the heading.
            var dy = e.heading - v.yaw
            while (dy > Math.PI) dy -= (2 * Math.PI).toFloat()
            while (dy < -Math.PI) dy += (2 * Math.PI).toFloat()
            v.yaw += dy * min(1f, dt * 10f)
            val scale = enemyVisualScale(e.def) * (1f + v.pop * 0.12f)
            r3d.setTransform(v.model.root, v.x, 0f, v.z, yaw = v.yaw, scale = scale)
            val anim = if (e.def.id == "outrider") "sprint" else "walk"
            v.animTime += dt * (e.def.speed / 1.3f)
            v.model.animate(anim, v.animTime)
        }
        val it = enemyVisuals.entries.iterator()
        while (it.hasNext()) {
            val (id, v) = it.next()
            if (id !in alive) {
                if (id in diedIds) {
                    corpses += Corpse(v.model, v.x, v.z, v.yaw, enemyVisualScale(v.def))
                } else {
                    models.release(v.model)
                }
                diedIds.remove(id)
                it.remove()
            }
        }
    }

    /** Fallen raiders play their death animation, then sink into the ground. */
    private fun syncCorpses(dt: Float) {
        val it = corpses.iterator()
        while (it.hasNext()) {
            val c = it.next()
            c.t += dt
            val die = c.model.duration("die").coerceAtLeast(0.5f)
            val sink = ((c.t - die - 0.4f) / 0.6f).coerceIn(0f, 1f)
            r3d.setTransform(c.model.root, c.x, -sink * 0.6f * c.scale, c.z, yaw = c.yaw, scale = c.scale)
            c.model.animate("die", c.t, loop = false)
            if (sink >= 1f) {
                models.release(c.model)
                it.remove()
            }
        }
    }

    private fun projectileHeights(visual: String): Pair<Float, Float> = when (visual) {
        "archer" -> 0.0f to 0.3f
        "ballista" -> 0.0f to 0.3f
        else -> 0.0f to 0.1f
    }

    private fun syncProjectiles(alpha: Float) {
        val g = game
        val alive = HashSet<Int>()
        for (p in g.projectiles) {
            if (!p.alive) continue
            alive += p.id
            val m = projectileVisuals.getOrPut(p.id) {
                when (p.visual) {
                    "frost", "brazier" -> meshPiece("shot-${p.visual}") { TowerArt.shot(this, p.visual) }
                    "catapult" -> modelPiece("boulder", 16)
                    else -> modelPiece("arrow", 16)
                }
            }
            val x = p.prevX + (p.x - p.prevX) * alpha
            val z = p.prevY + (p.y - p.prevY) * alpha
            val (_, h1) = projectileHeights(p.visual)
            val owner = g.structures[p.ownerId]
            val h0 = owner?.let { structureVisuals[it.id]?.headY?.plus(0.25f) } ?: 0.5f
            val arc = when (p.visual) {
                "catapult" -> 2.4f
                "brazier" -> 0.65f
                "archer", "frost" -> 0.5f
                else -> 0.15f
            }
            val t = p.progress.coerceIn(0f, 1f)
            val y = h0 + (h1 - h0) * t + arc * 4f * t * (1f - t)
            val slope = ((h1 - h0) + arc * 4f * (1f - 2f * t)) / max(0.5f, p.totalDist)
            val yaw = atan2(p.tx - p.x, p.ty - p.y) + ARROW_YAW_OFFSET
            val scale = when (p.visual) {
                "ballista" -> 1.6f
                "repeater" -> 1.18f
                else -> 1f
            }
            r3d.setTransform(m.root, x, y, z, yaw = yaw, pitch = -atan2(slope, 1f), scale = scale)
        }
        val it = projectileVisuals.entries.iterator()
        while (it.hasNext()) {
            val (id, m) = it.next()
            if (id !in alive) {
                release(m)
                it.remove()
            }
        }
    }

    private fun syncOverlays(dt: Float) {
        // Range ring + selection brackets for the selected structure.
        val sel = hud.selected?.let { game.structures[it.id] }
        val showRing = sel != null && sel.kind == StructureKind.TOWER
        if (showRing && sel != null) {
            r3d.setTransform(ringEntity, sel.cx, 0f, sel.cy, scale = sel.towerRange)
        }
        setShown(ringEntity, showRing, ringShown).also { ringShown = showRing }
        if (sel?.id != selectionFor) {
            selectionModel?.let { models.release(it) }
            selectionModel = null
            selectionFor = sel?.id ?: -1
            if (sel != null) {
                selectionModel = models.acquire("selection", 1).also {
                    r3d.setTransform(it.root, sel.cx, 0.02f, sel.cy, scale = sel.w * 1.15f)
                }
                structureVisuals[sel.id]?.pop = 1f
            }
        }
        for ((id, v) in structureVisuals) {
            if (v.pop <= 0f) continue
            val s = game.structures[id] ?: continue
            v.pop = max(0f, v.pop - dt * 3.5f)
            val k = sin(v.pop * Math.PI.toFloat())
            val scale = 1f + 0.06f * k
            val heightScale = 1f + 0.12f * k
            r3d.setTransform(v.base.root, s.cx, 0f, s.cy, scale = scale, sy = heightScale)
            v.detail?.let { r3d.setTransform(it.root, s.cx, 0f, s.cy, scale = scale, sy = heightScale) }
        }

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
                val hy = structureVisuals[s.id]?.headY ?: 1f
                val color = when (ev.visual) {
                    "catapult" -> Palette.DIRT
                    "brazier" -> TowerArt.FIRE
                    "frost" -> TowerArt.ICE
                    else -> null
                }
                if (color != null) fx.burst(s.cx, hy + 0.3f, s.cy, 5, color, speed = 1f, up = 1f, size = 0.06f)
            }
            is GameEvent.Hit -> {
                if (ev.visual == "frost") {
                    fx.burst(ev.x, 0.15f, ev.y, 17, TowerArt.ICE, glow = true, speed = 2.1f, up = 1.9f, size = 0.07f, life = 0.55f, glowIntensity = 2.5f)
                } else if (ev.splash > 0f) {
                    val smoke = if (ev.visual == "brazier") 0x694155 else Palette.DIRT_DARK
                    fx.burst(ev.x, 0.15f, ev.y, 14, smoke, speed = 3f, up = 3.5f, size = 0.09f, life = 0.8f)
                    fx.burst(ev.x, 0.25f, ev.y, 10, 0xFFA640, glow = true, speed = 2.5f, up = 2f, size = 0.1f, life = 0.35f, glowIntensity = 3f)
                    if (ev.visual == "catapult") shake = max(shake, 0.25f)
                } else {
                    fx.burst(ev.x, 0.35f, ev.y, 4, 0xFFE2A0, glow = true, speed = 1.8f, up = 1.5f, size = 0.04f, life = 0.25f, glowIntensity = 2f)
                }
                if (ev.damage > 0 && ev.splash == 0f && prefs.damageNumbers) floatingTexts += FloatingText(ev.x, 0.8f, ev.y, ev.damage.toString(), 0xFFFFFFFF.toInt())
            }
            is GameEvent.EnemyDied -> {
                diedIds += ev.enemyId
                fx.burst(ev.x, 0.4f, ev.y, 5 + ev.bounty.coerceAtMost(12), Palette.GOLD, glow = true, speed = 1.2f, up = 4.5f, size = 0.06f, life = 0.7f, glowIntensity = 2.5f)
                floatingTexts += FloatingText(ev.x, 1.0f, ev.y, "+${ev.bounty}", 0xFFFFD34D.toInt())
                if (ev.defId == "warlord") {
                    shake = 0.6f
                    toast("Warlord slain!", 0xFFFFD34D)
                }
            }
            is GameEvent.RankUp -> {
                val s = game.structures[ev.structureId] ?: continue
                val hy = structureVisuals[s.id]?.headY ?: 1.5f
                fx.burst(s.cx, hy + 0.5f, s.cy, 18, 0xFFE066, glow = true, speed = 1.5f, up = 2.5f, size = 0.05f, life = 1f, gravity = 2f, glowIntensity = 3f)
                floatingTexts += FloatingText(s.cx, hy + 1.1f, s.cy, "RANK ${roman(ev.rank)}", 0xFFFFE066.toInt(), big = true)
            }
            is GameEvent.WaveStarted -> toast("Wave ${ev.wave} — the Marauders attack!", 0xFFFF7A59)
            is GameEvent.WaveCleared -> {
                toast("Wave ${ev.wave} repelled! +${ev.reward} gold", 0xFF7CFC9A)
                save()
            }
            is GameEvent.KeepHit -> {
                shake = max(shake, 0.35f)
                fx.burst(ev.x, 0.6f, ev.y, 8, Palette.STONE_LIGHT, speed = 2f, up = 2.5f, size = 0.07f)
                floatingTexts += FloatingText(ev.x, 1.3f, ev.y, "-${ev.damage}", 0xFFFF5A4A.toInt())
            }
            is GameEvent.RaidLost -> {
                toast("The Keep fell! Raiders looted 20% of your resources.", 0xFFFF5A4A)
                shake = 0.8f
            }
            is GameEvent.VolleyCast -> volleyMarkers += floatArrayOf(ev.x, ev.y) to Content.VOLLEY_DELAY
            is GameEvent.VolleyImpact -> {
                fx.burst(ev.x, 0.15f, ev.y, 26, Palette.WOOD, speed = 3.2f, up = 2.5f, size = 0.05f, life = 0.7f)
                fx.burst(ev.x, 0.2f, ev.y, 12, 0xFFE2A0, glow = true, speed = 3f, up = 1.5f, size = 0.04f, life = 0.3f, glowIntensity = 2f)
                shake = max(shake, 0.2f)
            }
            is GameEvent.Built -> {
                val s = game.structures[ev.structureId] ?: continue
                fx.burst(s.cx, 0.1f, s.cy, 14, Palette.DIRT, speed = 1.6f, up = 1.8f, size = 0.07f)
            }
            is GameEvent.Upgraded -> {
                val s = game.structures[ev.structureId] ?: continue
                fx.burst(s.cx, 1f, s.cy, 16, 0x9FE3FF, glow = true, speed = 1.5f, up = 3f, size = 0.05f, life = 0.8f, gravity = 3f, glowIntensity = 2.5f)
            }
            is GameEvent.Specialized -> {
                val s = game.structures[ev.structureId] ?: continue
                fx.burst(s.cx, 1f, s.cy, 20, Palette.GOLD, glow = true, speed = 1.5f, up = 3f, size = 0.05f, life = 0.65f)
                toast("${s.towerDef.name} gained ${ev.specialization.label}", 0xFFFFD34D)
                save()
            }
            is GameEvent.Removed -> Unit
            is GameEvent.TroopRecruited -> {
                val (rx, ry) = game.rallyPoint()
                fx.burst(rx, 0.3f, ry, 12, 0x9FE3FF, glow = true, speed = 1.2f, up = 2.5f, size = 0.05f, life = 0.7f, glowIntensity = 2f)
            }
            is GameEvent.TroopDied -> {
                troopDiedIds += ev.troopId
                toast("A ${Content.unit(ev.defId).name} has fallen", 0xFFFFB0A0)
            }
            is GameEvent.Report -> {
                toast(ev.text, if (ev.good) 0xFF7CFC9A else 0xFFFFB070)
                hud.reports.add(0, ev.text to ev.good)
                if (hud.reports.size > 30) hud.reports.removeAt(hud.reports.size - 1)
                save()
            }
            is GameEvent.AssaultStarted -> {
                toast("⚔ ${ev.from} attacks with ${ev.units} soldiers!", 0xFFFF7A59)
                shake = max(shake, 0.4f)
            }
            is GameEvent.AssaultEnded -> {
                mp?.onAssaultEnded(ev)
                toast(if (ev.reached == 0) "Assault repelled – all ${ev.total} attackers fell!" else "${ev.reached} attackers broke through!", if (ev.reached == 0) 0xFF7CFC9A else 0xFFFF7A59)
            }
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
        hud.garrison = g.garrison()
        hud.armyCap = g.armyCap()
        hud.away = g.awayTroops
        hud.hasBarracks = g.structures.values.any { it.defId == "barracks" }
        hud.settings = campaign.settings
        hud.expeditionCount = campaign.expeditions.size
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
                val effect = when {
                    d.chillSeconds > 0f -> " · Slow"
                    d.splash > 0f -> " · Splash"
                    d.damageType == com.marian1x.agesofsiege.core.DamageType.ANTI_ARMOR -> " · Anti-armor"
                    else -> ""
                }
                stats = "DMG ${"%.0f".format(s.towerDamage)} · RNG ${"%.1f".format(s.towerRange)}\n${"%.2f".format(s.towerFireRate)} shots/s$effect"
            }
            StructureKind.WALL -> { name = "Stone Wall"; maxLevel = 1; stats = "Blocks raider paths" }
            StructureKind.BUILDING -> {
                val d = Content.building(s.defId)
                name = d.name; maxLevel = d.upgradeCosts.size + 1
                val p = d.produces * Content.levelProductionMult(s.level)
                stats = if (s.defId == "barracks") "Army capacity +${s.level * Content.ARMY_CAP_PER_BARRACKS_LEVEL}" else listOfNotNull(
                    p.gold.takeIf { it > 0 }?.let { "+$it gold/min" },
                    p.wood.takeIf { it > 0 }?.let { "+$it wood/min" },
                    p.stone.takeIf { it > 0 }?.let { "+$it stone/min" },
                ).joinToString(" · ")
            }
        }
        val next = Content.rankXp.firstOrNull { it > s.xp }
        return SelectedInfo(
            s.id, name, s.kind, s.level, maxLevel, s.rank, s.xp, next, s.kills,
            game.upgradeCost(s), s.invested * Content.SELL_REFUND, stats, s.specialization,
        )
    }

    // ------------------------------------------------------------ input

    fun onTap(sx: Float, sy: Float) {
        if (r3d.showingRealm) {
            onRealmTap(sx, sy)
            return
        }
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

    private val density = context.resources.displayMetrics.density

    /** Tap in the realm: find where the ray meets the terrain and select that region. */
    private fun onRealmTap(sx: Float, sy: Float) {
        var h = 0f
        var p: FloatArray? = null
        repeat(4) {
            val q = r3d.screenToGround(sx, sy, h) ?: return@repeat
            p = q
            h = realm.heightAt(q[0], q[2])
        }
        val hit = p ?: return
        val cell = realm.cellAt(hit[0], hit[2])
        hud.realmSel = if (cell == hud.realmSel) null else cell
    }

    /**
     * Picks the structure under the finger.
     * 1. Exact: cast a ray from the camera and take the first building box it hits (box = footprint x model height),
     *    so tapping the roof of a tall tower selects that tower, not the tile behind it.
     * 2. Forgiving: if nothing was hit, take the building whose on-screen center is closest, within a finger's width.
     *    Walls are skipped here so a near-miss never grabs a wall segment instead of the tower beside it.
     */
    private fun pickStructure(sx: Float, sy: Float): Structure? {
        val ray = r3d.screenRay(sx, sy)
        var best: Structure? = null
        var bestT = Float.MAX_VALUE
        for (s in game.structures.values) {
            val t = rayHitStructure(ray, s) ?: continue
            if (t < bestT) {
                bestT = t
                best = s
            }
        }
        if (best != null) return best

        val maxPx = 28f * density
        var bestD = maxPx * maxPx
        val pos = FloatArray(2)
        for (s in game.structures.values) {
            if (s.kind == StructureKind.WALL) continue
            if (!r3d.worldToScreen(s.cx, structureTop(s) * 0.5f, s.cy, pos)) continue
            val dx = pos[0] - sx
            val dy = pos[1] - sy
            val d = dx * dx + dy * dy
            if (d < bestD) {
                bestD = d
                best = s
            }
        }
        return best
    }

    /** Height of a structure's clickable box: the model's top, or the crew / weapon on top of a tower. */
    private fun structureTop(s: Structure): Float {
        val visual = structureVisuals[s.id] ?: return 1f
        val headTop = visual.head?.let { visual.headY + it.top * if (visual.crew) 0.5f else 1f } ?: 0f
        return max(visual.base.top, headTop).coerceAtLeast(0.3f)
    }

    /** Ray vs. the structure's box (slab test). Returns the hit distance along the ray, or null. */
    private fun rayHitStructure(ray: FloatArray, s: Structure): Float? {
        val hx = s.w * 0.5f * 0.94f
        val hz = s.h * 0.5f * 0.94f
        val lo = floatArrayOf(s.cx - hx, 0f, s.cy - hz)
        val hi = floatArrayOf(s.cx + hx, structureTop(s), s.cy + hz)
        var tMin = 0f
        var tMax = Float.MAX_VALUE
        for (k in 0..2) {
            val o = ray[k]
            val d = ray[3 + k]
            if (kotlin.math.abs(d) < 1e-6f) {
                if (o < lo[k] || o > hi[k]) return null
            } else {
                var t0 = (lo[k] - o) / d
                var t1 = (hi[k] - o) / d
                if (t0 > t1) { val tmp = t0; t0 = t1; t1 = tmp }
                tMin = max(tMin, t0)
                tMax = min(tMax, t1)
                if (tMin > tMax) return null
            }
        }
        return tMin
    }

    // Camera feel: yaw chosen by the player, pan velocity for a short glide after the finger lifts.
    private var gameYaw by mutableFloatStateOf(0f)
    private var panVelX = 0f
    private var panVelZ = 0f
    private var lastPanNanos = 0L

    fun onPan(centroidX: Float, centroidY: Float, dx: Float, dy: Float) {
        val a = r3d.screenToGround(centroidX, centroidY) ?: return
        val b = r3d.screenToGround(centroidX + dx, centroidY + dy) ?: return
        val mx = -(b[0] - a[0])
        val mz = -(b[2] - a[2])
        moveCamera(mx, mz)
        val now = System.nanoTime()
        val dt = ((now - lastPanNanos) / 1e9f).coerceIn(1f / 120f, 0.1f)
        // Smoothed velocity so a quick flick keeps gliding.
        panVelX = panVelX * 0.5f + (mx / dt) * 0.5f
        panVelZ = panVelZ * 0.5f + (mz / dt) * 0.5f
        lastPanNanos = now
    }

    private fun moveCamera(mx: Float, mz: Float) {
        val (w, h) = if (r3d.showingRealm) RealmView.widthUnits to RealmView.heightUnits else game.width.toFloat() to game.height.toFloat()
        r3d.camTargetX = (r3d.camTargetX + mx).coerceIn(0f, w)
        r3d.camTargetZ = (r3d.camTargetZ + mz).coerceIn(0f, h)
    }

    // ------------------------------------------------------------ base <-> realm

    /** Shows the scene that matches the current screen; swaps camera rigs on change. */
    private fun syncView() {
        val wantRealm = hud.screen == Screen.WORLD
        if (wantRealm == r3d.showingRealm) return
        val from = if (r3d.showingRealm) realmRig else baseRig
        from.x = r3d.camTargetX; from.z = r3d.camTargetZ; from.dist = zoomTo ?: r3d.camDistance; from.yaw = gameYaw
        r3d.showRealm(wantRealm)
        panVelX = 0f; panVelZ = 0f
        zoomOverscroll = 0f
        val to = if (wantRealm) realmRig else baseRig
        if (wantRealm) {
            if (to.x == 0f && to.z == 0f || hud.realmSel == null) {
                // Rise above your own capital.
                to.x = RealmView.cellCenter(campaign.world.home.first) + 4f
                to.z = RealmView.cellCenter(campaign.world.home.second)
            }
            r3d.camDistance = 14f
            zoomTo = to.dist.coerceIn(30f, RealmView.MAX_DIST)
        } else {
            r3d.camDistance = MAX_ZOOM
            zoomTo = to.dist.coerceIn(MIN_ZOOM, MAX_ZOOM - 4f)
        }
        r3d.camTargetX = to.x; r3d.camTargetZ = to.z
        gameYaw = to.yaw
        r3d.camYawDeg = to.yaw
        applyZoomPitch()
    }

    /** Rise from the base to the 3D realm. */
    fun openRealm() {
        hud.overlay = Overlay.NONE
        hud.screen = Screen.WORLD
    }

    /** Drop back down into the base. */
    fun openBase() {
        hud.screen = Screen.GAME
    }

    /** Moves the realm camera to a cell (e.g. a report or a landmark). */
    fun focusRealmCell(x: Int, y: Int) {
        r3d.camTargetX = RealmView.cellCenter(x)
        r3d.camTargetZ = RealmView.cellCenter(y)
    }

    /** Continues the pan after release, slowing down quickly. */
    private fun glide(dt: Float) {
        if (System.nanoTime() - lastPanNanos < 60_000_000L) return
        val speed = sqrt(panVelX * panVelX + panVelZ * panVelZ)
        if (speed < 0.05f) {
            panVelX = 0f; panVelZ = 0f
            return
        }
        moveCamera(panVelX * dt, panVelZ * dt)
        val decay = kotlin.math.exp(-5f * dt)
        panVelX *= decay
        panVelZ *= decay
    }

    fun onZoom(factor: Float) {
        zoomTo = null
        val want = r3d.camDistance / factor
        if (r3d.showingRealm) {
            r3d.camDistance = want.coerceIn(RealmView.MIN_DIST, RealmView.MAX_DIST)
            // Pinching in further over your own capital drops you into the base.
            val hx = RealmView.cellCenter(campaign.world.home.first) - r3d.camTargetX
            val hz = RealmView.cellCenter(campaign.world.home.second) - r3d.camTargetZ
            val overHome = hx * hx + hz * hz < 36f
            zoomOverscroll = if (want < RealmView.MIN_DIST && overHome) zoomOverscroll + (RealmView.MIN_DIST - want) else 0f
            if (zoomOverscroll > 2.5f) openBase()
        } else {
            r3d.camDistance = want.coerceIn(MIN_ZOOM, MAX_ZOOM)
            // Pinching out past the furthest zoom rises into the realm.
            zoomOverscroll = if (want > MAX_ZOOM) zoomOverscroll + (want - MAX_ZOOM) else 0f
            if (zoomOverscroll > 5f) openRealm()
        }
        applyZoomPitch()
    }

    /** Look more top-down when zoomed out, low and cinematic when zoomed in. */
    private fun applyZoomPitch() {
        if (r3d.showingRealm) {
            val f = ((r3d.camDistance - RealmView.MIN_DIST) / (RealmView.MAX_DIST - RealmView.MIN_DIST)).coerceIn(0f, 1f)
            r3d.camPitchDeg = 36f + f * 26f
        } else {
            val f = ((r3d.camDistance - MIN_ZOOM) / (MAX_ZOOM - MIN_ZOOM)).coerceIn(0f, 1f)
            r3d.camPitchDeg = 30f + f * 32f
        }
    }

    /** Two-finger twist rotates the view around the point you're looking at. */
    fun onRotate(degrees: Float) {
        gameYaw = (gameYaw - degrees + 360f) % 360f
        panVelX = 0f; panVelZ = 0f
    }

    /** Back to the default north-up view. */
    fun resetCameraRotation() {
        gameYaw = 0f
        panVelX = 0f
        panVelZ = 0f
    }

    val cameraRotated: Boolean get() = gameYaw in 3f..357f

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

    fun specializeSelected(specialization: TowerSpecialization) {
        val sel = hud.selected ?: return
        val result = game.execute(Command.Specialize(sel.id, specialization))
        if (result is CommandResult.Rejected) {
            toast(result.reason, 0xFFFFB0A0)
        } else {
            hud.overlay = Overlay.NONE
            handleEvents(game.drainEvents())
        }
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

    fun recruit(unitId: String) {
        val r = game.execute(Command.Recruit(unitId))
        if (r is CommandResult.Rejected) toast(r.reason, 0xFFFFB0A0) else handleEvents(game.drainEvents())
        refreshHud()
    }

    /** Sends troops to a world-map point of interest (or to scout an empty cell when poiId < 0). */
    fun sendExpedition(counts: Map<String, Int>, poiId: Int, cellX: Int, cellY: Int): Boolean {
        val view = campaign.mp
        val session = mp
        if (view != null && session != null) {
            // Match: the host moves armies. Capitals are attacked (Versus) or reinforced (Co-op) as players.
            val world = campaign.world
            val poi = world.pois.firstOrNull { it.id == poiId }
            val other = view.other?.slot ?: -1
            val atOther = (poi?.kind == com.marian1x.agesofsiege.core.PoiKind.RIVAL_CAPITAL && view.mode == com.marian1x.agesofsiege.core.MatchMode.VERSUS) ||
                (world.allyHome == (cellX to cellY))
            val err = if (atOther) session.sendArmy(counts, -1, cellX, cellY, other) else session.sendArmy(counts, poiId, cellX, cellY, -1)
            if (err != null) {
                toast(err, 0xFFFFB0A0)
                return false
            }
            toast(if (atOther && view.mode == com.marian1x.agesofsiege.core.MatchMode.COOP) "Reinforcements march to your ally!" else "Your army marches out!", 0xFF7CFC9A)
            refreshHud()
            return true
        }
        val r = if (poiId >= 0) campaign.sendExpedition(counts, poiId) else campaign.sendScouts(counts, cellX, cellY)
        if (r is CommandResult.Rejected) {
            toast(r.reason, 0xFFFFB0A0)
            return false
        }
        toast("Your army marches out!", 0xFF7CFC9A)
        save()
        refreshHud()
        return true
    }

    companion object {
        /** Kenney mini characters are ~1 unit tall; raiders are drawn a bit over half a tile tall. */
        const val UNIT_VISUAL_SCALE = 0.8f
        const val TROOP_SCALE = 0.72f
        const val MIN_ZOOM = 6f
        const val MAX_ZOOM = 34f
        const val WATER_LEVEL = -0.17f
        /** The Kenney arrow model points along -Z; projectiles are oriented along +Z. */
        const val ARROW_YAW_OFFSET = 0f

        fun enemyVisualScale(def: EnemyDef) = def.scale * UNIT_VISUAL_SCALE

        fun roman(n: Int) = when (n) { 1 -> "I"; 2 -> "II"; 3 -> "III"; 4 -> "IV"; else -> "V" }
    }
}
