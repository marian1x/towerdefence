package com.marian1x.agesofsiege.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

@Serializable
data class CampaignSettings(
    val lordName: String = "Lord of the Keep",
    val difficulty: Difficulty = Difficulty.NORMAL,
    val pace: Pace = Pace.STANDARD,
)

@Serializable
data class Expedition(
    val id: Int,
    val targetPoi: Int,
    val troops: MutableList<TroopData>,
    var path: List<Int>,
    /** Position along the path in cells (fractional). */
    var progress: Float = 0f,
    var returning: Boolean = false,
    var loot: Cost = Cost(),
    /** Multiplayer: slot of the player who sent it (0 in single-player). */
    val owner: Int = 0,
    /** Multiplayer: slot of the player whose capital is attacked / reinforced, or -1. */
    val targetPlayer: Int = -1,
    /** Multiplayer: waiting at an enemy capital while the assault is fought out in their base. */
    var besieging: Boolean = false,
)

@Serializable
data class Rival(
    val name: String,
    var defense: Float = 160f,
    var army: Float = 40f,
    var gold: Float = 200f,
    /** How far (in world cells) the rival has explored from its capital. */
    var explored: Float = 3f,
    var contact: Boolean = false,
    var raidTimer: Float = 0f,
    var defeats: Int = 0,
    var vassal: Boolean = false,
)

@Serializable
data class PoiState(val id: Int, val cleared: Boolean, val power: Float, val loot: Cost)

@Serializable
data class CampaignSave(
    val version: Int = 2,
    val seed: Long,
    val settings: CampaignSettings,
    val base: SaveData,
    val explored: String,
    val pois: List<PoiState>,
    val expeditions: List<Expedition>,
    val rival: Rival,
    val worldTime: Float,
    val nextId: Int,
) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun fromJson(text: String): CampaignSave = json.decodeFromString(serializer(), text)
    }
}

/**
 * A whole single-player game: the base (tower defense), the world map with expeditions, and the rival AI lord.
 * Everything advances in fixed steps from [step]; time is paused while the app is closed.
 */
class Campaign(val seed: Long, settings: CampaignSettings) {
    var settings = settings
        private set
    val base = BaseGame(seed)
    val world = World(seed xor 0x5DEECE66DL)
    val expeditions = ArrayList<Expedition>()
    var rival = Rival(name = RIVAL_NAMES[((seed ushr 3) % RIVAL_NAMES.size).toInt()])
        private set
    var worldTime = 0f
        private set
    private var nextId = 1
    private val rng = Rng(seed * 31 + 7)

    companion object {
        val RIVAL_NAMES = listOf("Baron Grimwald", "Lady Morwen", "Count Varek", "Duke Halvard", "Queen Sabeth")
        /** World cells per second for marching troops at Standard pace. */
        const val MARCH_SPEED = 0.45f
        /** Seconds of play (Standard pace, Normal difficulty) before the rival's scouts reach your lands. */
        const val CONTACT_SECONDS = 30f * 60f
        const val RIVAL_RAID_INTERVAL = 6f * 60f
        const val TRIBUTE_PER_SEC = 0.35f
    }

    init {
        applyModifiers()
    }

    val distanceToRival: Float
        get() {
            val dx = (world.rivalHome.first - world.home.first).toFloat()
            val dy = (world.rivalHome.second - world.home.second).toFloat()
            return sqrt(dx * dx + dy * dy)
        }

    fun updateSettings(s: CampaignSettings) {
        settings = s
        applyModifiers()
    }

    private fun applyModifiers() {
        val cleared = world.pois.count { it.kind == PoiKind.CAMP && it.cleared }
        base.hpModifier = settings.difficulty.raidHp * max(0.7f, 1f - 0.03f * cleared)
        base.productionMult = settings.pace.mult
        val mySlot = mp?.mySlot ?: 0
        base.awayTroops = expeditions.sumOf { if (it.owner == mySlot) it.troops.size else 0 }
    }

    // -------------------------------------------------------------- simulation

    fun step() {
        base.step()
        if (mp != null) return // the shared realm is simulated by the match host
        val dt = BaseGame.DT * settings.pace.mult
        worldTime += dt
        stepExpeditions(dt)
        stepRival(dt)
    }

    // -------------------------------------------------------------- multiplayer

    /** This phone's view of a two-player match (null in single-player). */
    class MpView(val setup: MatchSetup, val mySlot: Int) {
        var players: List<MpPlayer> = setup.players
        val mode get() = setup.mode
        val me get() = players[mySlot]
        val other get() = players.firstOrNull { it.slot != mySlot }
    }

    var mp: MpView? = null
        private set

    /** Turns this (fresh) campaign into this phone's side of a match. */
    fun setupMultiplayer(setup: MatchSetup, mySlot: Int) {
        val view = MpView(setup, mySlot)
        mp = view
        MatchRules.prepareWorld(world, setup.mode)
        val me = setup.players[mySlot]
        val other = setup.players.first { it.slot != mySlot }
        world.home = me.home
        if (setup.mode == MatchMode.VERSUS) {
            // The "rival capital" landmark becomes the opponent's capital.
            world.rivalHome = other.home
            world.pois.firstOrNull { it.kind == PoiKind.RIVAL_CAPITAL }?.let { it.x = other.homeX; it.y = other.homeY }
            rival = Rival(name = other.name, defense = other.defense, contact = true)
        } else {
            world.allyHome = other.home
        }
        for (i in world.explored.indices) world.explored[i] = false
        world.reveal(me.homeX, me.homeY, 5)
        if (setup.mode == MatchMode.COOP) world.reveal(other.homeX, other.homeY, 3)
        expeditions.clear()
        applyModifiers()
    }

    /** Applies the host's realm snapshot. */
    fun applySnapshot(s: NetMsg.Snapshot) {
        val view = mp ?: return
        view.players = s.players
        for (ps in s.pois) world.pois.firstOrNull { it.id == ps.id }?.let {
            it.cleared = ps.cleared
            it.power = ps.power
            it.loot = ps.loot
        }
        expeditions.clear()
        expeditions += s.expeditions
        worldTime = s.worldTime
        for (e in s.expeditions) {
            if (e.owner != view.mySlot && view.mode == MatchMode.VERSUS) continue
            val cell = e.path[e.progress.toInt().coerceIn(0, e.path.size - 1)]
            world.reveal(cell % World.W, cell / World.W, 2)
        }
        if (view.mode == MatchMode.VERSUS) {
            val o = view.other
            if (o != null) rival = rival.copy(name = o.name, defense = o.defense, contact = true)
            world.rivalPoi().power = rival.defense
        } else {
            s.rival?.let { rival = it }
            world.pois.firstOrNull { it.kind == PoiKind.RIVAL_CAPITAL }?.power = rival.defense
        }
        applyModifiers()
    }

    /** Validates a march order and takes the soldiers out of the base (multiplayer: the host moves them). */
    fun takeArmy(counts: Map<String, Int>): Pair<List<TroopData>?, String?> {
        if (counts.values.none { it > 0 }) return null to "Choose at least one soldier"
        val garrison = base.garrison()
        if (counts.any { (id, n) -> n !in 0..(garrison[id] ?: 0) }) return null to "Invalid soldier count"
        return base.takeTroops(counts) to null
    }

    private fun report(text: String, good: Boolean) {
        base.events += GameEvent.Report(text, good)
    }

    private fun stepExpeditions(dt: Float) {
        val it = expeditions.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val last = (e.path.size - 1).toFloat()
            e.progress = min(last, e.progress + MARCH_SPEED * dt)
            val cell = e.path[e.progress.toInt().coerceIn(0, e.path.size - 1)]
            world.reveal(cell % World.W, cell / World.W, 2)
            if (e.progress < last) continue
            if (!e.returning) {
                arrive(e)
                if (e.troops.isEmpty()) {
                    it.remove()
                    applyModifiers()
                    continue
                }
                e.returning = true
                e.path = e.path.reversed()
                e.progress = 0f
            } else {
                base.returnTroops(e.troops)
                base.res.add(e.loot)
                val lootText = lootText(e.loot)
                report("Your army is home${if (lootText.isNotEmpty()) " with $lootText" else ""}.", true)
                it.remove()
                applyModifiers()
            }
        }
    }

    private fun arrive(e: Expedition) {
        val poi = world.pois.firstOrNull { it.id == e.targetPoi } ?: return
        if (poi.cleared && poi.kind != PoiKind.RIVAL_CAPITAL) {
            report("${poi.kind.label} was already empty. Marching home.", false)
            return
        }
        when (poi.kind) {
            PoiKind.CACHE -> {
                e.loot = e.loot + poi.loot
                poi.cleared = true
                report("Supplies found: ${lootText(poi.loot)}!", true)
            }
            PoiKind.CAMP, PoiKind.RUINS -> {
                val (won, lost) = battle(e.troops, poi.power)
                if (won) {
                    e.loot = e.loot + poi.loot
                    poi.cleared = true
                    report("${poi.kind.label} conquered! Lost $lost soldiers. Loot: ${lootText(poi.loot)}.", true)
                } else {
                    report("Defeated at the ${poi.kind.label.lowercase()}. Lost $lost soldiers.", false)
                }
            }
            PoiKind.RIVAL_CAPITAL -> {
                val (won, lost) = battle(e.troops, rival.defense)
                if (won) {
                    val plunder = rival.gold * 0.4f
                    rival.gold -= plunder
                    rival.defense *= 0.6f
                    rival.army *= 0.5f
                    rival.defeats++
                    e.loot = e.loot + Cost(plunder.toInt(), 40, 40)
                    if (rival.defeats >= 3 && !rival.vassal) {
                        rival.vassal = true
                        report("${rival.name} bends the knee and becomes your vassal! Tribute will flow to you.", true)
                    } else {
                        report("You stormed ${rival.name}'s capital (victory ${rival.defeats}/3)! Lost $lost soldiers.", true)
                    }
                } else {
                    report("The assault on ${rival.name}'s capital failed. Lost $lost soldiers.", false)
                }
            }
        }
        applyModifiers()
    }

    /** Auto-resolved battle. Mutates [troops] (wounds and deaths). Returns (won, soldiers lost). */
    fun battle(troops: MutableList<TroopData>, defense: Float): Pair<Boolean, Int> {
        val attack = troops.sumOf { Content.troopPower(it).toDouble() }.toFloat()
        val ratio = attack / max(1f, defense) * rng.range(0.85f, 1.15f)
        val won = ratio >= 1f
        val lossFraction = if (won) min(0.8f, 0.4f / ratio) else (1f - 0.3f * ratio).coerceIn(0.5f, 1f)
        var damagePool = troops.sumOf { it.hp.toDouble() }.toFloat() * lossFraction
        val before = troops.size
        // Spread damage over the army, weakest soldiers fall first.
        for (t in troops.sortedBy { it.hp }) {
            if (damagePool <= 0f) break
            val hit = min(t.hp, damagePool)
            t.hp -= hit
            damagePool -= hit
        }
        troops.removeAll { it.hp <= 0.5f }
        if (troops.isNotEmpty()) {
            val xpEach = (defense * (if (won) 0.6f else 0.25f) / troops.size).toInt()
            for (t in troops) t.xp += xpEach
        }
        return won to (before - troops.size)
    }

    private fun stepRival(dt: Float) {
        val growth = settings.difficulty.rivalGrowth
        if (rival.vassal) {
            base.res.gold += TRIBUTE_PER_SEC * dt
            return
        }
        rival.defense += 0.07f * growth * dt
        rival.army += 0.05f * growth * dt
        rival.gold += 0.4f * growth * dt
        world.rivalPoi().power = rival.defense
        if (!rival.contact) {
            rival.explored = min(distanceToRival, 3f + (distanceToRival - 3f) * worldTime * growth / CONTACT_SECONDS)
            if (rival.explored >= distanceToRival - 4f) {
                rival.contact = true
                rival.raidTimer = RIVAL_RAID_INTERVAL
                world.reveal(world.rivalHome.first, world.rivalHome.second, 2)
                report("${rival.name}'s scouts have found your lands! Expect attacks.", false)
            }
        } else {
            rival.raidTimer -= dt
            if (rival.raidTimer <= 0f) {
                rival.raidTimer = RIVAL_RAID_INTERVAL
                val units = (rival.army / 22f).toInt().coerceIn(2, 14)
                rival.army *= 0.55f
                base.pendingRivalUnits += units
                report("${rival.name} is sending $units soldiers with the next Marauder wave!", false)
            }
        }
    }

    // -------------------------------------------------------------- commands

    fun sendExpedition(counts: Map<String, Int>, poiId: Int): CommandResult {
        val poi = world.pois.firstOrNull { it.id == poiId } ?: return CommandResult.Rejected("Unknown target")
        if (!world.isExplored(poi.x, poi.y)) return CommandResult.Rejected("Explore the area first")
        val total = counts.values.sum()
        if (total <= 0) return CommandResult.Rejected("Choose at least one soldier")
        val garrison = base.garrison()
        if (counts.any { (id, n) -> n > (garrison[id] ?: 0) }) return CommandResult.Rejected("Not enough soldiers at home")
        val path = world.path(world.home.first, world.home.second, poi.x, poi.y)
            ?: return CommandResult.Rejected("No route to that place")
        val troops = base.takeTroops(counts).toMutableList()
        expeditions += Expedition(nextId++, poi.id, troops, path)
        applyModifiers()
        return CommandResult.Ok
    }

    /** Explore an empty spot of the map (scouting). */
    fun sendScouts(counts: Map<String, Int>, x: Int, y: Int): CommandResult {
        if (!world.inBounds(x, y) || !world.terrain[world.idx(x, y)].passable) return CommandResult.Rejected("Can't march there")
        if (counts.values.sum() <= 0) return CommandResult.Rejected("Choose at least one soldier")
        val path = world.path(world.home.first, world.home.second, x, y) ?: return CommandResult.Rejected("No route")
        val troops = base.takeTroops(counts).toMutableList()
        expeditions += Expedition(nextId++, -1, troops, path)
        applyModifiers()
        return CommandResult.Ok
    }

    fun etaSeconds(poiX: Int, poiY: Int): Int? {
        val p = world.path(world.home.first, world.home.second, poiX, poiY) ?: return null
        return ((p.size - 1) / (MARCH_SPEED * settings.pace.mult)).toInt()
    }

    private fun lootText(c: Cost): String = listOfNotNull(
        c.gold.takeIf { it > 0 }?.let { "$it gold" },
        c.wood.takeIf { it > 0 }?.let { "$it wood" },
        c.stone.takeIf { it > 0 }?.let { "$it stone" },
    ).joinToString(", ")

    // -------------------------------------------------------------- save / load

    fun toSave() = CampaignSave(
        seed = seed,
        settings = settings,
        base = base.toSave(),
        explored = world.explored.joinToString("") { if (it) "1" else "0" },
        pois = world.pois.map { PoiState(it.id, it.cleared, it.power, it.loot) },
        expeditions = expeditions.toList(),
        rival = rival,
        worldTime = worldTime,
        nextId = nextId,
    )

    fun loadFrom(s: CampaignSave) {
        base.loadFrom(s.base)
        for (i in world.explored.indices) world.explored[i] = s.explored.getOrNull(i) == '1'
        for (ps in s.pois) world.pois.firstOrNull { it.id == ps.id }?.let {
            it.cleared = ps.cleared
            it.power = ps.power
            it.loot = ps.loot
        }
        expeditions.clear()
        expeditions += s.expeditions
        rival = s.rival
        worldTime = s.worldTime
        nextId = s.nextId
        applyModifiers()
    }
}
