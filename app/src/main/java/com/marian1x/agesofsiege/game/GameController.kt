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
import com.marian1x.agesofsiege.core.Ability
import com.marian1x.agesofsiege.core.BaseGame
import com.marian1x.agesofsiege.core.BattleResult
import com.marian1x.agesofsiege.core.BattleSetup
import com.marian1x.agesofsiege.core.DailyChallenge
import com.marian1x.agesofsiege.core.LevelDef
import com.marian1x.agesofsiege.core.LevelMode
import com.marian1x.agesofsiege.core.LevelTheme
import com.marian1x.agesofsiege.core.Levels
import com.marian1x.agesofsiege.core.Outcome
import com.marian1x.agesofsiege.core.PlayerProfile
import com.marian1x.agesofsiege.core.TargetPriority
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
import com.marian1x.agesofsiege.engine.BattleArt
import com.marian1x.agesofsiege.engine.Mesh
import com.marian1x.agesofsiege.engine.MeshBuilder
import com.marian1x.agesofsiege.engine.Model
import com.marian1x.agesofsiege.engine.ModelLibrary
import com.marian1x.agesofsiege.engine.Models
import com.marian1x.agesofsiege.engine.Palette
import com.marian1x.agesofsiege.engine.Renderer3D
import com.marian1x.agesofsiege.engine.TowerArt
import com.marian1x.agesofsiege.net.Leaderboards
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
    /** Waiting for a tap on the battlefield to cast an ability. */
    data class Cast(val ability: Ability) : InputMode()
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
    val priority: TargetPriority = TargetPriority.FIRST,
    val support: Boolean = false,
    val specCost: Cost = Content.specializationCost,
)

data class Toast(val id: Long, val text: String, val color: Long)

enum class Screen { MENU, GAME, WORLD, CAMPAIGN }
enum class Overlay {
    NONE, PAUSE, SETTINGS, NEW_GAME, ARMY, CONFIRM_RESTART, MULTIPLAYER, SPECIALIZATION,
    BRIEFING, RESULTS, PERKS, QUESTS, DAILY, LEADERBOARD,
}

/** A coin flying from a fallen raider to the gold counter (screen pixels). */
class CoinFly(val x: Float, val y: Float) {
    var t = 0f
}

/** Player preferences (device-local, not part of the save). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var highQuality by mutableStateOf(sp.getBoolean("hq", true))
    var damageNumbers by mutableStateOf(sp.getBoolean("dmg", true))
    var cameraShake by mutableStateOf(sp.getBoolean("shake", true))
    var sfxVolume by mutableFloatStateOf(sp.getFloat("sfx", 0.8f))
    var musicVolume by mutableFloatStateOf(sp.getFloat("music", 0.45f))
    var haptics by mutableStateOf(sp.getBoolean("haptics", true))
    var relayEndpoint: String
        get() = sp.getString("relayEndpoint", "").orEmpty()
        set(value) { sp.edit().putString("relayEndpoint", value).apply() }

    fun save() {
        sp.edit().putBoolean("hq", highQuality).putBoolean("dmg", damageNumbers).putBoolean("shake", cameraShake)
            .putFloat("sfx", sfxVolume).putFloat("music", musicVolume).putBoolean("haptics", haptics).apply()
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

    // Campaign battles.
    var inBattle by mutableStateOf(false)
    var battleMode by mutableStateOf(LevelMode.NORMAL)
    var battleName by mutableStateOf("")
    var totalWaves by mutableIntStateOf(0)
    var leaks by mutableIntStateOf(0)
    var twoStarLeaks by mutableIntStateOf(0)
    var keepMax by mutableIntStateOf(Content.KEEP_MAX_HP)
    var towers by mutableStateOf<List<String>>(emptyList())
    var abilities by mutableStateOf<List<Ability>>(listOf(Ability.VOLLEY))
    var abilityReady by mutableStateOf<Map<Ability, Float>>(emptyMap())
    var goldOnly by mutableStateOf(false)
    var goldPulse by mutableIntStateOf(0)
    var intro by mutableStateOf(false)
    /** Level picked on the campaign map (briefing dialog). */
    var briefing by mutableStateOf<LevelDef?>(null)
    var summary by mutableStateOf<BattleSummary?>(null)
    /** Bumped whenever the profile changes so profile screens recompose. */
    var profileVersion by mutableIntStateOf(0)
    var leaderboardId by mutableStateOf<String?>(null)
    var leaderboardTitle by mutableStateOf("")
    var race by mutableStateOf<RaceInfo?>(null)
    var raceVersion by mutableIntStateOf(0)
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
    val audio = Audio(context, prefs).also { UiSound.audio = it }
    var campaign: Campaign = loadOrNew()
        private set
    /** Campaign progress (stars, Glory, quests...). */
    val profile: PlayerProfile = loadProfile()
    /** The level battle being fought, or null for the realm base. */
    var battle: LevelBattle? = null
        private set
    val game: BaseGame get() = battle?.game ?: campaign.base
    val hasSave: Boolean get() = saveFile.exists()
    /** Coins flying to the gold counter (drawn by the HUD overlay). */
    val coinFlies = ArrayList<CoinFly>()
    /** A fixed realm used as the illustrated campaign map. */
    val mapCampaign: Campaign by lazy { Campaign(CAMPAIGN_MAP_SEED, CampaignSettings()).also { prepareCampaignMap(it) } }

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
        /** 1 -> 0 right after being built: the tower rises out of the ground. */
        var rise = 0f
    }

    /** Structures built this frame (their visual rises once created). */
    private val risingIds = HashSet<Int>()

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
    /** Wings, rams, shields and staffs carried by the new raider types. */
    private val enemyExtras = HashMap<Int, Piece>()
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

    // Battlefield extras (roads, ponds, gate flags) rebuilt per level; tar pits; weather.
    private val levelPieces = ArrayList<Pair<Int, Mesh?>>()
    private val tarEntities = ArrayList<Int>()
    private val tarMi = r3d.litInstance(0x2A1C12, r3d.ghostMaterial, 0.72f)
    private val tarMesh by lazy { mesh("disc") { Models.discMesh(this) } }
    private var weatherTimer = 0f
    private var keepSmokeTimer = 0f
    private var introFrom = floatArrayOf(0f, 0f)

    init {
        models.warmUp("raider" to 16, "arrow" to 16, "archer_crew" to 8)
        buildWorld()
        r3d.camTargetX = 14f
        r3d.camTargetZ = 8.6f
        r3d.setQuality(prefs.highQuality)
        refreshHud()
    }

    /** The simulation only runs while playing (not on menus or open dialogs). */
    private val simulating: Boolean
        get() {
            val b = battle
            if (b != null && (b.intro > 0f || b.finished || hud.screen != Screen.GAME)) return false
            if (inMatch) return mp?.running == true
            return (hud.screen == Screen.GAME || hud.screen == Screen.WORLD) && !hud.paused &&
                (hud.overlay == Overlay.NONE || hud.overlay == Overlay.ARMY)
        }

    // ------------------------------------------------------------ campaign battles

    private val profileFile get() = File(context.filesDir, "profile.json")

    private fun loadProfile(): PlayerProfile = try {
        val f = File(context.filesDir, "profile.json")
        if (f.exists()) PlayerProfile.fromJson(f.readText()) else PlayerProfile()
    } catch (t: Throwable) {
        Log.w("AgesOfSiege", "Could not load profile", t)
        PlayerProfile()
    }

    fun saveProfile() {
        try {
            profileFile.writeText(profile.toJson())
        } catch (t: Throwable) {
            Log.w("AgesOfSiege", "Profile save failed", t)
        }
        hud.profileVersion++
    }

    /** Today's number for the Daily Challenge and quests (UTC days). */
    fun today(): Long = System.currentTimeMillis() / 86_400_000L

    /** The campaign map: your capital in the west, the Marauder stronghold in the east. */
    private fun prepareCampaignMap(c: Campaign) {
        c.world.explored.fill(false)
        c.rival.contact = true
    }

    /** Unlocked levels lift the clouds around them. */
    private fun revealCampaignMap() {
        val w = mapCampaign.world
        w.reveal(w.home.first, w.home.second, 3)
        for ((i, l) in Levels.all.withIndex()) {
            if (!profile.isUnlocked(l)) continue
            val (x, y) = levelCell(i)
            w.reveal(x, y, if (profile.stars(l.id) > 0) 3 else 2)
        }
    }

    private val levelCells: List<Pair<Int, Int>> by lazy {
        val w = mapCampaign.world
        val ww = com.marian1x.agesofsiege.core.World.W
        val wh = com.marian1x.agesofsiege.core.World.H
        val path = w.path(w.home.first, w.home.second, w.rivalHome.first, w.rivalHome.second).orEmpty()
        val n = Levels.all.size
        if (path.size < 3) return@lazy List(n) { (2 + it * 2).coerceAtMost(ww - 2) to wh / 2 }
        List(n) { i ->
            val k = ((i + 1).toFloat() / (n + 1) * (path.size - 1)).toInt().coerceIn(1, path.size - 1)
            val c = path[k]
            // Zig-zag a little off the road so flags don't overlap.
            val x = c % ww
            val y = c / ww + (if (i % 2 == 0) -1 else 1)
            x to y.coerceIn(1, wh - 2)
        }
    }

    /** Realm cell of the campaign map flag for level number [index]. */
    fun levelCell(index: Int): Pair<Int, Int> = levelCells[index]

    /** World position above a level's flag (for screen labels). */
    fun levelAnchor(index: Int): FloatArray {
        val (x, y) = levelCell(index)
        return realm.labelAnchor(x, y, 1.2f)
    }

    fun openCampaign() {
        hud.overlay = Overlay.NONE
        hud.briefing = null
        if (battle != null) endBattle()
        profile.refreshQuests(today())
        revealCampaignMap()
        val i = Levels.all.indexOf(profile.currentLevel())
        val (x, y) = levelCell(i)
        realmRig.x = RealmView.cellCenter(x)
        realmRig.z = RealmView.cellCenter(y)
        realmRig.dist = 30f
        realmRig.yaw = 0f
        hud.screen = Screen.CAMPAIGN
        hud.profileVersion++
    }

    fun openBriefing(level: LevelDef) {
        audio.play("page", 0.7f)
        hud.briefing = level
        hud.overlay = Overlay.BRIEFING
    }

    fun perksFor(mode: LevelMode): Map<String, Int> =
        if (mode == LevelMode.DAILY || mode == LevelMode.RACE) emptyMap() else HashMap(profile.perks)

    fun startLevel(level: LevelDef, mode: LevelMode) {
        if (!profile.modeUnlocked(level, mode)) {
            toast("Locked", 0xFFFFB0A0)
            return
        }
        startBattle(BattleSetup(level, mode, perks = perksFor(mode)))
    }

    fun startDaily() = startBattle(DailyChallenge.forDay(today()))

    private fun startBattle(setup: BattleSetup, seed: Long = System.nanoTime() and 0xffffff, race: RaceInfo? = null) {
        if (battle == null && campaign.mp == null) save() // keep the realm safe
        clearVisuals()
        battle = LevelBattle(setup, BaseGame(seed, setup), race)
        hud.race = race
        buildWorld()
        hud.selected = null
        hud.mode = InputMode.Normal
        hud.paused = false
        hud.speed = 1
        hud.toasts.clear()
        hud.summary = null
        hud.overlay = Overlay.NONE
        hud.screen = Screen.GAME
        // Opening flight: from the first gate to the Keep.
        val (gx, gy) = game.gates.first()
        introFrom = floatArrayOf(gx + 0.5f, gy + 0.5f)
        r3d.camTargetX = introFrom[0]
        r3d.camTargetZ = introFrom[1]
        r3d.camDistance = 15f
        zoomTo = null
        gameYaw = 0f
        applyZoomPitch()
        audio.play("horn", 0.5f)
        toast("${setup.level.name} — ${setup.mode.label}", 0xFFFFD34D)
        refreshHud()
    }

    fun skipIntro() {
        battle?.let { if (it.intro > 0f) it.intro = 0.01f }
    }

    fun retryBattle() {
        val b = battle ?: return
        if (b.race != null) return
        startBattle(b.setup.copy(perks = perksFor(b.mode)))
    }

    fun nextLevel() {
        val b = battle ?: return
        val next = Levels.next(b.level.id)
        if (next == null || !profile.isUnlocked(next)) return openCampaign()
        startLevel(next, LevelMode.NORMAL)
    }

    /** Leaves the battle (to the campaign map, or the menu after a Daily/Race). */
    fun leaveBattle() {
        val b = battle ?: return
        endBattle()
        when {
            b.race != null -> {
                mp?.close()
                mp = null
                hud.screen = Screen.MENU
            }
            b.mode == LevelMode.DAILY -> {
                hud.screen = Screen.MENU
                hud.overlay = Overlay.DAILY
            }
            else -> openCampaign()
        }
    }

    private fun endBattle() {
        clearVisuals()
        battle = null
        hud.race = null
        hud.summary = null
        buildWorld()
        r3d.camTargetX = 14f
        r3d.camTargetZ = 8.6f
        refreshHud()
    }

    /** The battle is over: record it, update quests and boards, and show the results. */
    private fun finishBattle(won: Boolean) {
        val b = battle ?: return
        if (b.finished) return
        b.finished = true
        val g = b.game
        val score = g.score()
        val record = if (b.mode == LevelMode.RACE) null else profile.recordBattle(
            BattleResult(
                b.level.id, b.mode, won, g.stars, score, g.wave, g.stats.kills, g.stats.shatters, g.stats.bossKills,
                g.stats.abilitiesUsed, g.stats.towersBuilt, today(),
            ),
            HashMap(g.stats.killsByTower),
        )
        saveProfile()
        val board = when (b.mode) {
            LevelMode.DAILY -> DailyChallenge.boardId(today())
            LevelMode.NORMAL, LevelMode.HEROIC, LevelMode.IRON -> if (won) "level-${b.level.id}-${b.mode.name.lowercase()}" else null
            LevelMode.ENDLESS -> "endless-${b.level.id}"
            LevelMode.RACE -> null
        }
        if (board != null) Leaderboards.submit(context, board, playerName(), score, g.stars, g.wave)
        hud.summary = BattleSummary(
            b.level.id, b.level.name, b.mode, won, g.stars, g.leaks, b.level.twoStarLeaks, score, g.wave,
            if (g.totalWaves == Int.MAX_VALUE) 0 else g.totalWaves, g.stats.kills, record,
            hasNext = won && b.mode == LevelMode.NORMAL && Levels.next(b.level.id)?.let { profile.isUnlocked(it) } == true,
            board = board,
            race = b.race?.let { raceResult(it) },
        )
        if (b.race != null) mp?.raceStatus(g, force = true)
        if (won) {
            audio.play("victory", 0.9f, minGapMs = 0)
            audio.hapticPattern(longArrayOf(0, 40, 80, 40, 80, 90), intArrayOf(0, 120, 0, 160, 0, 220))
        } else {
            audio.play("defeat", 0.9f, minGapMs = 0)
            audio.haptic(220, 200)
        }
        record?.achievements?.forEach { toast("🏆 ${it.name} (+${it.glory} Glory)", 0xFFFFD34D) }
        if (record?.achievements?.isNotEmpty() == true) audio.play("achievement", 0.8f, minGapMs = 0)
        hud.mode = InputMode.Normal
        hud.overlay = Overlay.RESULTS
    }

    fun playerName(): String = profile.name.ifBlank { hud.settings.lordName.takeIf { it != "Lord of the Keep" } ?: "Commander" }

    fun setPlayerName(name: String) {
        profile.name = name.trim().take(18)
        saveProfile()
    }

    fun buyPerk(id: String) {
        val err = profile.buyPerk(id)
        if (err != null) {
            toast(err, 0xFFFFB0A0)
            audio.play("error")
        } else {
            audio.play("upgrade")
            saveProfile()
        }
    }

    fun resetPerks() {
        profile.resetPerks()
        audio.play("sell")
        saveProfile()
    }

    fun claimQuest(q: com.marian1x.agesofsiege.core.QuestState) {
        val g = profile.claimQuest(q)
        if (g > 0) {
            audio.play("coins", 0.9f)
            audio.play("star", 0.7f)
            toast("+$g Glory", 0xFFFFD34D)
            saveProfile()
        }
    }

    fun openLeaderboard(board: String, title: String) {
        hud.leaderboardId = board
        hud.leaderboardTitle = title
        hud.overlay = Overlay.LEADERBOARD
    }

    // ------------------------------------------------------------ race (same level, two phones)

    private fun raceResult(r: RaceInfo): RaceResult {
        val g = game
        val me = g.outcome
        val them = r.outcome
        val youWon = when {
            me == Outcome.WON && them != Outcome.WON -> true
            me != Outcome.WON && them == Outcome.WON -> false
            g.wave != r.wave -> g.wave > r.wave
            else -> g.score() >= r.score
        }
        val text = when {
            them == null && me == Outcome.LOST -> "Your Keep fell first. ${r.opponent} is still fighting (wave ${r.wave})."
            them == null -> "${r.opponent} is still fighting (wave ${r.wave})…"
            youWon -> "You beat ${r.opponent}! Score ${g.score()} vs ${r.score}"
            else -> "${r.opponent} wins this race. Score ${g.score()} vs ${r.score}"
        }
        return RaceResult(youWon && !(them == null && me == Outcome.LOST), r.opponent, text)
    }

    /** Both phones start the same level with the same seed. */
    internal fun beginRace(session: MpSession, setup: com.marian1x.agesofsiege.core.MatchSetup, slot: Int) {
        if (battle?.race != null && raceMatchId == setup.matchId) {
            // Reconnected to the same race: keep this battle.
            mp = session
            hud.overlay = if (battle?.finished == true) Overlay.RESULTS else Overlay.NONE
            toast("Reconnected!", 0xFF7CFC9A)
            return
        }
        raceMatchId = setup.matchId
        mp = session
        val level = Levels.get(setup.level ?: "") ?: Levels.all.first()
        val other = setup.players.first { it.slot != slot }.name
        startBattle(BattleSetup(level, LevelMode.RACE), seed = setup.seed, race = RaceInfo(other))
        toast("Race on ${level.name}! Kills earn raider squads to send to $other.", 0xFF7CFC9A)
    }

    /** Race: the opponent's latest numbers. */
    internal fun onRaceStatus(msg: com.marian1x.agesofsiege.core.NetMsg.RaceStatus) {
        val r = battle?.race ?: return
        r.wave = msg.wave
        r.keepHp = msg.keepHp
        r.keepMax = msg.keepMax
        r.leaks = msg.leaks
        r.score = msg.score
        if (msg.done) r.outcome = if (msg.won) Outcome.WON else Outcome.LOST
        hud.raceVersion++
        val summary = hud.summary
        if (summary?.race != null && battle?.finished == true) hud.summary = summary.copy(race = raceResult(r))
    }

    /** Race: the opponent sent raiders into this battlefield. */
    internal fun onRaceAttack(msg: com.marian1x.agesofsiege.core.NetMsg.RaceSend) {
        val b = battle ?: return
        val r = b.race ?: return
        if (b.finished) return
        r.received += msg.units
        b.game.launchAssault(msg.id, msg.units, msg.hp, r.opponent, msg.enemyId)
        handleEvents(b.game.drainEvents())
    }

    /** Race: spend a token to send a raider squad to the opponent. */
    fun sendRaceSquad() {
        val b = battle ?: return
        val r = b.race ?: return
        if (r.tokens <= 0) {
            toast("Defeat more raiders to earn a squad", 0xFFFFB0A0)
            return
        }
        val session = mp
        val err = if (session == null) "Not connected" else session.sendRaceSquad(b.game)
        if (err != null) {
            toast(err, 0xFFFFB0A0)
            return
        }
        r.tokens--
        r.sent++
        audio.play("horn", 0.45f, rate = 1.3f)
        toast("Raiders march on ${r.opponent}!", 0xFF7CFC9A)
        hud.raceVersion++
    }
    var mp by mutableStateOf<MpSession?>(null)
        private set

    /** A live two-player game: a realm match, or a Race whose opponent is still connected. */
    val inMatch: Boolean get() = campaign.mp != null || (battle?.race != null && mp != null && mp?.phase != MpSession.Phase.ENDED)
    private var raceMatchId = 0L

    fun hostMatch(
        name: String,
        mode: com.marian1x.agesofsiege.core.MatchMode,
        difficulty: com.marian1x.agesofsiege.core.Difficulty,
        pace: com.marian1x.agesofsiege.core.Pace,
        endpoint: com.marian1x.agesofsiege.net.RelayEndpoint? = null,
        hostKey: String = "",
        raceLevel: String? = null,
    ) {
        mp?.close()
        if (endpoint != null) prefs.relayEndpoint = endpoint.toString()
        mp = MpSession.host(this, context, name.ifBlank { "Host" }, mode, difficulty, pace, endpoint, hostKey, raceLevel = raceLevel)
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
        if (battle?.race != null) {
            leaveBattle()
            return
        }
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
        audio.resume()
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        audio.pause()
        save()
        // A battle in progress pauses itself so the player comes back to the pause menu.
        if (battle?.finished == false && !inMatch && hud.overlay == Overlay.NONE) hud.overlay = Overlay.PAUSE
    }

    fun destroy() {
        stop()
        mp?.close()
        audio.release()
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
        audio.applyVolumes()
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
        val trim = if (s.kind == StructureKind.TOWER && battle != null) profile.towerTier(s.defId) else 0
        val detail = if (s.kind == StructureKind.TOWER && (s.level >= 4 || s.specialization != null || trim > 0)) {
            meshPiece("tower-detail-${s.level}-${s.specialization}-$trim") {
                TowerArt.upgradeDetail(this, s.level, s.specialization)
                if (trim > 0) BattleArt.gildedTrim(this, trim)
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
        "shieldbearer" -> "rival"
        "shaman" -> "villager_f"
        "harpy" -> "outrider"
        "ram" -> "brute"
        else -> "raider"
    }

    private fun enemyExtra(defId: String): Piece? = when (defId) {
        "harpy" -> meshPiece("enemy-wings") { BattleArt.wings(this) }
        "ram" -> meshPiece("enemy-ram") { BattleArt.ram(this) }
        "shieldbearer" -> meshPiece("enemy-shield") { BattleArt.shield(this) }
        "shaman" -> meshPiece("enemy-staff") { BattleArt.staff(this) }
        else -> null
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

        // Per-battlefield pieces: gate flags, roads, ponds.
        for ((e, m) in levelPieces) {
            r3d.destroyEntity(e)
            m?.let { r3d.destroyMesh(it) }
        }
        levelPieces.clear()
        fun addPiece(b: MeshBuilder, mi: com.google.android.filament.MaterialInstance, shadows: Boolean, y: Float = 0f) {
            if (b.vertexCount == 0) return
            val m = r3d.upload(b)
            val e = r3d.createRenderable(m, mi, shadows)
            r3d.setTransform(e, 0f, y, 0f)
            r3d.scene.addEntity(e)
            levelPieces += e to m
        }
        addPiece(MeshBuilder().apply { TowerArt.gates(this, g.gates, allOpen = g.level != null, width = g.width, height = g.height) }, towerMaterial, true)
        if (g.level != null) {
            addPiece(MeshBuilder().apply { BattleArt.shores(this, g.tiles, g.width, g.height) }, r3d.defaultLit, false)
            addPiece(MeshBuilder().apply { BattleArt.roads(this, g.tiles, g.width, g.height) }, r3d.defaultLit, false)
            addPiece(MeshBuilder().apply { BattleArt.water(this, g.tiles, g.width, g.height) }, r3d.waterMaterial.createInstance(), false, y = 0.02f)
        }
        val theme = g.level?.theme ?: LevelTheme.MEADOW
        r3d.applyLook(Looks.of(theme))
        audio.music.setTheme(theme, isMenu = g.level == null)

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
        for (e in tarEntities) r3d.scene.remove(e)
        coinFlies.clear()
        enemyExtras.values.forEach(::release)
        enemyExtras.clear()
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
        val b = battle
        var timeScale = (if (inMatch) 1 else hud.speed).toFloat()
        if (b != null) {
            if (b.intro > 0f && hud.screen == Screen.GAME) introFlight(b, dt)
            if (b.hitStop > 0f) {
                b.hitStop -= dt
                timeScale = 0f
            } else if (b.slowMo > 0f) {
                b.slowMo -= dt
                timeScale *= 0.3f
            }
        }
        if (simulating) {
            acc += dt * timeScale
            var steps = 0
            while (acc >= BaseGame.DT && steps < 12) {
                if (b != null) b.game.step() else campaign.step()
                handleEvents(game.drainEvents())
                acc -= BaseGame.DT
                steps++
                if (battle !== b) break
            }
            if (steps == 12) acc = 0f
        }
        val alpha = (acc / BaseGame.DT).coerceIn(0f, 1f)
        val simDt = if (!simulating) 0f else dt * timeScale
        audio.music.intensity = if (hud.screen == Screen.GAME && game.phase == Phase.ACTIVE && game.enemies.isNotEmpty()) 1f else 0f
        syncView()
        if (hud.screen == Screen.GAME) {
            weather(dt)
            keepDamage(simDt)
            syncTar()
        }
        if (hud.screen == Screen.MENU) {
            // Slow cinematic orbit behind the main menu.
            r3d.camYawDeg = (r3d.camYawDeg + dt * 4f) % 360f
        } else if (b == null || b.intro <= 0f || hud.screen != Screen.GAME) {
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

        if (r3d.showingRealm) {
            if (hud.screen == Screen.CAMPAIGN) realm.update(mapCampaign, dt, null) else realm.update(campaign, dt, hud.realmSel)
        }
        villagers.update(game, if (b != null) 0f else simDt)
        chimneySmoke(simDt)
        syncStructures(simDt, dt)
        syncEnemies(alpha, simDt)
        syncTroops(alpha, simDt)
        syncCorpses(simDt)
        syncProjectiles(alpha)
        syncOverlays(dt)
        val fxDt = when {
            simulating -> simDt
            b != null && b.intro > 0f -> dt
            else -> 0f
        }
        fx.update(fxDt)
        updateTexts(simDt)
        updateCoins(dt)
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

    /** Opening shot of a battle: glide from the first gate to the Keep, then the countdown starts. */
    private fun introFlight(b: LevelBattle, dt: Float) {
        val total = if (b.race == null) 3.4f else 2f
        b.intro -= dt
        val t = (1f - b.intro / total).coerceIn(0f, 1f)
        val e = t * t * (3f - 2f * t)
        val k = game.keep
        r3d.camTargetX = introFrom[0] + (k.cx - 2.5f - introFrom[0]) * e
        r3d.camTargetZ = introFrom[1] + (k.cy - introFrom[1]) * e
        r3d.camDistance = 14f + 10f * e
        r3d.camYawDeg = 18f * (1f - e)
        applyZoomPitch()
        if (b.intro <= 0f) {
            b.intro = 0f
            gameYaw = 0f
            audio.play("bong", 0.5f)
        }
    }

    /** Snow, ash, embers and fireflies drifting over the battlefield. */
    private fun weather(dt: Float) {
        val theme = game.level?.theme ?: return
        weatherTimer -= dt
        if (weatherTimer > 0f) return
        weatherTimer = when (theme) {
            LevelTheme.FROST -> 0.07f
            LevelTheme.EMBER -> 0.12f
            LevelTheme.NIGHT, LevelTheme.MARSH -> 0.3f
            LevelTheme.AUTUMN -> 0.45f
            LevelTheme.MEADOW -> 0.9f
        }
        val glow = theme == LevelTheme.EMBER || theme == LevelTheme.NIGHT || theme == LevelTheme.MARSH
        if (fx.freeCount(glow) < 60) return
        val x = r3d.camTargetX + Random.nextFloat() * 22f - 11f
        val z = r3d.camTargetZ + Random.nextFloat() * 14f - 7f
        when (theme) {
            LevelTheme.FROST -> fx.drift(x, 6f, z, 0xF4F8FF, false, 0.25f, -0.9f, 0.1f, 0.045f, 6f)
            LevelTheme.EMBER -> fx.drift(x, 0.2f, z, if (Random.nextBoolean()) 0xFF7A2A else 0xFFB347, true, 0.1f, 0.6f, -0.05f, 0.035f, 3.5f, 3f)
            LevelTheme.NIGHT -> fx.drift(x, 0.4f + Random.nextFloat(), z, 0xE8FF7A, true, Random.nextFloat() * 0.3f - 0.15f, 0.06f, Random.nextFloat() * 0.3f - 0.15f, 0.03f, 3f, 4f)
            LevelTheme.MARSH -> fx.drift(x, 0.3f + Random.nextFloat() * 0.6f, z, 0xB8FF9A, true, 0.08f, 0.05f, 0.05f, 0.028f, 3f, 2.6f)
            LevelTheme.AUTUMN -> fx.drift(x, 4f, z, if (Random.nextBoolean()) 0xD9822B else 0xB5452A, false, 0.6f, -0.7f, 0.2f, 0.05f, 6f)
            LevelTheme.MEADOW -> fx.drift(x, 0.5f + Random.nextFloat(), z, 0xFFFFFF, true, 0.25f, 0.05f, 0.1f, 0.022f, 2.5f, 1.6f)
        }
    }

    /** A wounded Keep smokes, a badly wounded one burns. */
    private fun keepDamage(dt: Float) {
        if (dt <= 0f) return
        val g = game
        val f = g.keepHp / g.keepMaxHp.toFloat()
        if (f > 0.66f) return
        keepSmokeTimer -= dt
        if (keepSmokeTimer > 0f) return
        keepSmokeTimer = if (f < 0.33f) 0.12f else 0.35f
        val k = g.keep
        val x = k.cx + Random.nextFloat() * 1.2f - 0.6f
        val z = k.cy + Random.nextFloat() * 1.2f - 0.6f
        fx.burst(x, 1.6f, z, 1, 0x4A4A4E, speed = 0.15f, up = 0.6f, size = 0.16f, life = 2.4f, gravity = -0.2f, grow = true)
        if (f < 0.33f) fx.burst(x, 1.2f, z, 2, TowerArt.FIRE, glow = true, speed = 0.4f, up = 1.4f, size = 0.07f, life = 0.5f, gravity = -1f, glowIntensity = 3f)
    }

    /** Dark sticky discs where Tar Pits were cast. */
    private fun syncTar() {
        val pits = game.tarPits
        while (tarEntities.size < pits.size) {
            tarEntities += r3d.createRenderable(tarMesh, tarMi, shadows = false)
        }
        for ((i, e) in tarEntities.withIndex()) {
            val p = pits.getOrNull(i)
            if (p == null) {
                r3d.scene.remove(e)
                continue
            }
            val fade = (p.timer / 0.5f).coerceIn(0f, 1f)
            r3d.setTransform(e, p.x, 0.035f, p.y, scale = Ability.TAR.radius * (0.6f + 0.4f * fade))
            r3d.scene.addEntity(e)
            if (Random.nextFloat() < 0.08f) fx.burst(p.x + Random.nextFloat() - 0.5f, 0.08f, p.y + Random.nextFloat() - 0.5f, 1, 0x3A2A1A, speed = 0.1f, up = 0.6f, size = 0.05f, life = 0.6f)
        }
    }

    private fun updateCoins(dt: Float) {
        if (coinFlies.isEmpty()) return
        val it = coinFlies.iterator()
        while (it.hasNext()) {
            val c = it.next()
            c.t += dt * 1.6f
            if (c.t >= 1f) {
                it.remove()
                hud.goldPulse++
            }
        }
    }

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

    private fun syncStructures(dt: Float, realDt: Float) {
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
            val v = structureVisuals.getOrPut(s.id) {
                createStructureVisual(s).also { if (risingIds.remove(s.id)) it.rise = 1f }
            }
            var lift = 1f
            if (v.rise > 0f) {
                v.rise = max(0f, v.rise - realDt * 3.2f)
                val k = 1f - v.rise
                // Ease-out-back: overshoots a little, then settles.
                val c = 1.9f
                lift = (1f + (c + 1f) * (k - 1f) * (k - 1f) * (k - 1f) + c * (k - 1f) * (k - 1f)).coerceAtLeast(0.04f)
                r3d.setTransform(v.base.root, s.cx, 0f, s.cy, sy = lift)
                v.detail?.let { r3d.setTransform(it.root, s.cx, 0f, s.cy, sy = lift) }
            }
            val head = v.head ?: continue
            v.animTime += dt
            if (v.crew) {
                // Archer on the battlements: shoot animation right after firing, idle otherwise.
                r3d.setTransform(head.root, s.cx, v.headY * lift, s.cy, yaw = s.aim, scale = 0.5f)
                if (s.sinceShot < 0.6f) head.model?.animate("holding-both-shoot", s.sinceShot, loop = false)
                else head.model?.animate("idle", v.animTime)
            } else {
                val recoil = if (s.sinceShot < 0.18f) (1f - s.sinceShot / 0.18f) else 0f
                val kickPitch = if (s.defId == "catapult") -recoil * 0.45f else 0f
                val back = recoil * 0.06f
                r3d.setTransform(head.root, s.cx - sin(s.aim) * back, v.headY * lift, s.cy - cos(s.aim) * back, yaw = s.aim, pitch = kickPitch)
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
            val scale = enemyVisualScale(e.def) * (1f + v.pop * 0.12f) * (if (e.enraged) 1.12f else 1f)
            v.animTime += dt * (e.def.speed / 1.3f)
            val lift = if (e.def.flying) 1.05f + 0.12f * sin(v.animTime * 4f) else 0f
            r3d.setTransform(v.model.root, v.x, lift, v.z, yaw = v.yaw, scale = scale)
            val anim = when {
                e.def.flying -> "idle"
                e.def.id == "outrider" -> "sprint"
                e.enraged -> "sprint"
                else -> "walk"
            }
            v.model.animate(anim, v.animTime)
            if (!enemyExtras.containsKey(e.id)) enemyExtra(e.def.id)?.let { enemyExtras[e.id] = it }
            enemyExtras[e.id]?.let { piece ->
                val fwd = if (e.def.id == "ram") 0.62f else 0f
                val px = v.x + sin(v.yaw) * fwd
                val pz = v.z + cos(v.yaw) * fwd
                when (e.def.id) {
                    "harpy" -> r3d.setTransform(piece.root, v.x, lift, v.z, yaw = v.yaw, scale = scale, sy = scale * (1f + 0.4f * sin(v.animTime * 13f)))
                    "ram" -> r3d.setTransform(piece.root, px, 0f, pz, yaw = v.yaw, scale = 0.95f)
                    else -> r3d.setTransform(piece.root, px, lift, pz, yaw = v.yaw, scale = scale)
                }
            }
            if (e.enraged && simulating && Random.nextFloat() < 0.15f) {
                fx.burst(v.x, 1.4f, v.z, 1, 0xFF3A2A, glow = true, speed = 0.5f, up = 1.2f, size = 0.05f, life = 0.5f, gravity = -0.5f, glowIntensity = 3f)
            }
        }
        val extras = enemyExtras.entries.iterator()
        while (extras.hasNext()) {
            val (id, piece) = extras.next()
            if (id !in alive) {
                release(piece)
                extras.remove()
            }
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
            // Faint trails behind every shot.
            if (simulating && Random.nextFloat() < 0.4f && fx.freeCount(true) > 40) {
                when (p.visual) {
                    "frost" -> fx.mote(x, y, z, TowerArt.ICE, size = 0.045f, life = 0.3f, glowIntensity = 2.4f)
                    "brazier" -> fx.mote(x, y, z, TowerArt.FIRE, size = 0.05f, life = 0.3f, glowIntensity = 3f, rise = 0.4f)
                    "catapult" -> fx.mote(x, y, z, 0xB8A080, size = 0.05f, life = 0.35f, glowIntensity = 1.1f)
                    "ballista", "repeater" -> fx.mote(x, y, z, 0xDDE6FF, size = 0.025f, life = 0.18f)
                    else -> fx.mote(x, y, z, 0xFFF0C8, size = 0.02f, life = 0.15f, glowIntensity = 1.5f)
                }
            }
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

    private val screenPos = FloatArray(2)

    /** Sound, vibration and extra effects for every event (on top of the visuals below). */
    private fun juice(ev: GameEvent) {
        val a = audio
        when (ev) {
            is GameEvent.Shot -> when (ev.visual) {
                "archer" -> a.play("bow", 0.28f, minGapMs = 70)
                "ballista" -> a.play("twang", 0.45f, minGapMs = 90)
                "repeater" -> a.play("bow", 0.22f, rate = 1.45f, minGapMs = 55)
                "catapult" -> a.play("launch", 0.5f, minGapMs = 120)
                "frost" -> a.play("chime", 0.18f, rate = 1.3f, minGapMs = 120)
                "brazier" -> a.play("fire", 0.32f, minGapMs = 110)
            }
            is GameEvent.Hit -> when {
                ev.visual == "catapult" -> a.play("boom", 0.5f, minGapMs = 90)
                ev.visual == "brazier" -> a.play("fire", 0.22f, rate = 1.3f, minGapMs = 90)
                ev.visual == "frost" -> a.play("frost_hit", 0.3f, minGapMs = 80)
                ev.type == com.marian1x.agesofsiege.core.DamageType.ANTI_ARMOR -> a.play("bolt_hit", 0.3f, minGapMs = 60)
                else -> a.play("arrow_hit", 0.22f, minGapMs = 50)
            }
            is GameEvent.EnemyDied -> {
                a.play("die", 0.25f, minGapMs = 70)
                a.play("coins", 0.18f, minGapMs = 110)
                if (prefs.damageNumbers && coinFlies.size < 24 && r3d.worldToScreen(ev.x, 0.8f, ev.y, screenPos)) {
                    coinFlies += CoinFly(screenPos[0], screenPos[1])
                }
                val b = battle
                if (b != null) {
                    val g = b.game
                    val boss = Content.enemy(ev.defId).boss
                    if (boss) {
                        b.slowMo = 1.1f
                        b.hitStop = 0.08f
                        a.play("roar", 0.7f, rate = 0.8f, minGapMs = 0)
                        a.haptic(120, 230)
                    } else if (g.wave >= g.totalWaves && g.enemies.count { it.alive } <= 1) {
                        b.slowMo = 0.8f // the very last raider
                    }
                    b.race?.let { r ->
                        r.killsBanked++
                        if (r.killsBanked >= RACE_KILLS_PER_SQUAD) {
                            r.killsBanked = 0
                            r.tokens++
                            hud.raceVersion++
                        }
                    }
                }
            }
            is GameEvent.KeepHit -> {
                a.play("keep_hit", 0.6f, minGapMs = 80)
                a.haptic(35, 140)
                val g = game
                if (g.keepHp in 1..(g.keepMaxHp / 4)) a.play("bell", 0.5f, minGapMs = 3000)
            }
            is GameEvent.WaveStarted -> a.play("horn", 0.55f, minGapMs = 0)
            is GameEvent.WaveCleared -> a.play("wave_clear", 0.55f, minGapMs = 0)
            is GameEvent.Built -> {
                risingIds += ev.structureId
                val s = game.structures[ev.structureId]
                if (s?.kind == StructureKind.WALL) a.play("wall", 0.5f) else a.play("build", 0.6f)
                a.haptic(12, 60)
            }
            is GameEvent.Upgraded -> {
                a.play("upgrade", 0.6f)
                game.structures[ev.structureId]?.let { fx.ring(it.cx, 0.15f, it.cy, 22, 0x9FE3FF) }
            }
            is GameEvent.Specialized -> a.play("confirm", 0.7f)
            is GameEvent.Removed -> a.play("sell", 0.6f)
            is GameEvent.RankUp -> a.play("rank_up", 0.5f, minGapMs = 400)
            is GameEvent.VolleyCast -> a.play("whoosh", 0.6f)
            is GameEvent.VolleyImpact -> a.play("arrow_hit", 0.6f, minGapMs = 0)
            is GameEvent.TroopRecruited -> a.play("blade", 0.5f)
            is GameEvent.TroopDied -> a.play("die", 0.35f)
            is GameEvent.AssaultStarted -> a.play("horn", 0.6f, rate = 0.85f, minGapMs = 0)
            is GameEvent.LevelWon -> {
                if (ev.stars == 3) toast("Flawless! Not a single raider got through.", 0xFFFFD34D)
                finishBattle(true)
            }
            is GameEvent.LevelLost -> {
                shake = 0.9f
                finishBattle(false)
            }
            is GameEvent.Shatter -> {
                a.play("shatter", 0.6f, minGapMs = 60)
                fx.burst(ev.x, 0.5f, ev.y, 16, TowerArt.ICE, glow = true, speed = 3.2f, up = 2.6f, size = 0.06f, life = 0.6f, glowIntensity = 3f)
                floatingTexts += FloatingText(ev.x, 1.3f, ev.y, "SHATTER! ${ev.damage}", 0xFF9FEFFF.toInt(), big = true)
                battle?.hitStop = 0.04f
            }
            is GameEvent.Healed -> {
                a.play("heal", 0.22f, minGapMs = 500)
                fx.ring(ev.x, 0.2f, ev.y, 14, 0x7CFF9A, speed = 1.6f, life = 0.5f)
            }
            is GameEvent.BossEnraged -> {
                a.play("roar", 0.9f, minGapMs = 0)
                a.haptic(160, 220)
                shake = max(shake, 0.6f)
                fx.ring(ev.x, 0.2f, ev.y, 30, 0xFF3A2A, speed = 3.4f, life = 0.7f, glowIntensity = 3.5f)
                toast("The Warlord is enraged!", 0xFFFF5A4A)
            }
            is GameEvent.BossSummon -> {
                a.play("horn", 0.35f, rate = 1.5f, minGapMs = 0)
                fx.ring(ev.x, 0.2f, ev.y, 16, 0xFF9A3A, speed = 2f)
            }
            is GameEvent.WallSmashed -> {
                a.play("wall_smash", 0.8f, minGapMs = 0)
                shake = max(shake, 0.45f)
                fx.burst(ev.x, 0.4f, ev.y, 18, Palette.STONE_LIGHT, speed = 3f, up = 3f, size = 0.09f, life = 0.9f)
            }
            is GameEvent.Split -> {
                a.play("chop", 0.7f, minGapMs = 0)
                a.play("creak", 0.5f, minGapMs = 0)
                fx.burst(ev.x, 0.4f, ev.y, 20, Palette.WOOD, speed = 2.8f, up = 3f, size = 0.08f, life = 0.9f)
                shake = max(shake, 0.3f)
            }
            is GameEvent.TarCast -> {
                a.play("tar", 0.7f, minGapMs = 0)
                fx.burst(ev.x, 0.2f, ev.y, 18, 0x2A1C12, speed = 2f, up = 2f, size = 0.08f, life = 0.7f)
            }
            is GameEvent.MilitiaArrived -> {
                a.play("militia", 0.7f, minGapMs = 0)
                a.play("blade", 0.5f, minGapMs = 0)
                fx.ring(ev.x, 0.15f, ev.y, 20, 0x9FE3FF)
            }
            is GameEvent.MilitiaGone -> fx.burst(ev.x, 0.4f, ev.y, 6, 0x9FE3FF, glow = true, speed = 0.8f, up = 1.5f, size = 0.04f, life = 0.5f, glowIntensity = 2f)
            else -> Unit
        }
    }

    private fun handleEvents(events: List<GameEvent>) {
        for (ev in events) {
        juice(ev)
        when (ev) {
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
            else -> Unit
        }
        }
    }

    private fun toast(text: String, color: Long) {
        val t = Toast(toastId++, text, color)
        hud.toasts.add(t)
        if (hud.toasts.size > 3) hud.toasts.removeAt(0)
    }

    fun toastLocked(text: String) = toast(text, 0xFFFFB0A0)

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
        hud.volleyReady = 1f - g.volleyCooldown / g.cooldownMax(Ability.VOLLEY)
        hud.preview = g.nextWavePreview()
        val b = battle
        hud.inBattle = b != null
        hud.goldOnly = g.goldOnly
        hud.keepMax = g.keepMaxHp
        hud.leaks = g.leaks
        hud.intro = b != null && b.intro > 0f
        if (b != null) {
            hud.battleMode = b.mode
            hud.battleName = b.level.name
            hud.totalWaves = if (g.totalWaves == Int.MAX_VALUE) 0 else g.totalWaves
            hud.twoStarLeaks = b.level.twoStarLeaks
        }
        val towers = g.availableTowers()
        if (towers != hud.towers) hud.towers = towers
        val abilities = Ability.entries.filter { g.abilityAllowed(it) && (b != null || it == Ability.VOLLEY || g.structures.values.any { s -> s.defId == "barracks" }) }
        if (abilities != hud.abilities) hud.abilities = abilities
        hud.abilityReady = abilities.associateWith { 1f - g.cooldown(it) / g.cooldownMax(it) }
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
            StructureKind.KEEP -> {
                name = "The Keep"; maxLevel = 1
                stats = "HP ${game.keepHp}/${game.keepMaxHp}" + if (game.goldOnly) " · ${game.leaks} raiders got in" else " · produces gold, wood, stone"
            }
            StructureKind.TOWER -> {
                val d = s.towerDef
                name = d.name; maxLevel = d.upgradeCosts.size + 1
                val effect = when {
                    d.isSupport -> ""
                    !d.hitsAir -> " · Ground only"
                    d.chillSeconds > 0f -> " · Slow"
                    d.splash > 0f -> " · Splash"
                    d.damageType == com.marian1x.agesofsiege.core.DamageType.ANTI_ARMOR -> " · Anti-armor"
                    else -> ""
                }
                stats = if (d.isSupport) {
                    val f = 1f + 0.5f * (s.level - 1)
                    "+${(d.auraDamage * f * 100).toInt()}% damage · +${(d.auraRate * f * 100).toInt()}% speed\nfor towers within ${"%.1f".format(s.towerRange)} tiles"
                } else {
                    val aura = if (s.auraDamage > 0f) " · Banner +${(s.auraDamage * 100).toInt()}%" else ""
                    "DMG ${"%.0f".format(s.towerDamage)} · RNG ${"%.1f".format(s.towerRange)}\n${"%.2f".format(s.towerFireRate)} shots/s$effect$aura"
                }
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
            priority = s.priority,
            support = s.kind == StructureKind.TOWER && s.towerDef.isSupport,
            specCost = game.specializationCost(),
        )
    }

    // ------------------------------------------------------------ input

    fun onTap(sx: Float, sy: Float) {
        if (r3d.showingRealm) {
            if (hud.screen == Screen.WORLD) onRealmTap(sx, sy)
            return
        }
        val b = battle
        if (b != null && b.intro > 0f) {
            skipIntro()
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
                    audio.play("error", 0.6f)
                } else {
                    r3d.setTint(ghostMi, 0x55FF77, 0.45f)
                    handleEvents(game.drainEvents())
                    // Keep building; stop automatically when out of resources.
                    if (!game.res.canAfford(game.costOf(mode.defId))) hud.mode = InputMode.Normal
                }
            }
            is InputMode.Cast -> {
                val r = game.execute(Command.CastAbility(mode.ability, p[0], p[2]))
                if (r is CommandResult.Rejected) {
                    toast(r.reason, 0xFFFFB0A0)
                    audio.play("error", 0.6f)
                } else handleEvents(game.drainEvents())
                hud.mode = InputMode.Normal
            }
            InputMode.Normal -> {
                val picked = pickStructure(sx, sy)
                if (picked != null) audio.play("select", 0.5f)
                hud.selected = picked?.let { info(it) }
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
        val wantRealm = hud.screen == Screen.WORLD || hud.screen == Screen.CAMPAIGN
        if (wantRealm == r3d.showingRealm) return
        val from = if (r3d.showingRealm) realmRig else baseRig
        from.x = r3d.camTargetX; from.z = r3d.camTargetZ; from.dist = zoomTo ?: r3d.camDistance; from.yaw = gameYaw
        r3d.showRealm(wantRealm)
        panVelX = 0f; panVelZ = 0f
        zoomOverscroll = 0f
        val to = if (wantRealm) realmRig else baseRig
        if (wantRealm) {
            if (hud.screen == Screen.WORLD && (to.x == 0f && to.z == 0f || hud.realmSel == null)) {
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
            val overHome = hx * hx + hz * hz < 36f && hud.screen == Screen.WORLD
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

    fun armVolley() = armAbility(Ability.VOLLEY)

    fun armAbility(a: Ability) {
        if (game.cooldown(a) > 0f) {
            toast("${a.label} recharging", 0xFFFFB0A0)
            audio.play("error", 0.5f)
            return
        }
        hud.selected = null
        val armed = (hud.mode as? InputMode.Cast)?.ability == a
        hud.mode = if (armed) InputMode.Normal else InputMode.Cast(a)
        audio.play("toggle", 0.5f)
    }

    fun setPriority(p: TargetPriority) {
        val sel = hud.selected ?: return
        game.execute(Command.SetPriority(sel.id, p))
        audio.play("toggle", 0.5f)
        refreshHud()
    }

    fun callWave() {
        val r = game.execute(Command.StartWave)
        if (r is CommandResult.Ok) handleEvents(game.drainEvents())
        refreshHud()
    }

    fun upgradeSelected() {
        val sel = hud.selected ?: return
        val r = game.execute(Command.Upgrade(sel.id))
        if (r is CommandResult.Rejected) {
            toast(r.reason, 0xFFFFB0A0)
            audio.play("error", 0.6f)
        } else handleEvents(game.drainEvents())
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
        /** Fixed realm seed of the illustrated campaign map. */
        const val CAMPAIGN_MAP_SEED = 0x5E1A6E5L
        /** Race: kills needed to earn one raider squad to send. */
        const val RACE_KILLS_PER_SQUAD = 6
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
