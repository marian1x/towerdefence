package com.marian1x.agesofsiege.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/*
 * Two-player matches (same Wi-Fi first, internet relay later).
 *
 * Every phone simulates its OWN base (BaseGame) locally – tower defense needs zero latency.
 * The HOST additionally runs [MatchRealm]: the shared realm with points of interest, every army on the
 * map, battles, the AI rival (co-op) and attacks between players. Clients send [NetMsg]s to the host,
 * the host answers with snapshots and results. The host's own player goes through the same messages.
 */

enum class MatchMode(val label: String) { VERSUS("Versus"), COOP("Co-op"), RACE("Race") }

@Serializable
data class MpPlayer(
    val slot: Int,
    val name: String,
    val homeX: Int,
    val homeY: Int,
    /** Rough strength of the capital (towers + garrison), reported by the player. */
    var defense: Float = 0f,
    /** Soldiers at home, reported by the player. */
    var army: Int = 0,
    var wave: Int = 1,
    var connected: Boolean = true,
) {
    val home get() = homeX to homeY
}

@Serializable
data class MatchSetup(
    val matchId: Long,
    val seed: Long,
    val mode: MatchMode,
    val difficulty: Difficulty,
    val pace: Pace,
    val players: List<MpPlayer>,
    /** Race: the campaign level both players fight on. */
    val level: String? = null,
)

/** Wire protocol: one JSON object per line. */
@Serializable
sealed class NetMsg {
    @Serializable @SerialName("hello")
    data class Hello(
        val name: String,
        val protocol: Int = MatchRules.PROTOCOL,
        val playerToken: String = "",
        val resumeMatchId: Long? = null,
    ) : NetMsg()

    @Serializable @SerialName("welcome")
    data class Welcome(val setup: MatchSetup, val yourSlot: Int, val started: Boolean) : NetMsg()

    @Serializable @SerialName("reject")
    data class Reject(val reason: String) : NetMsg()

    @Serializable @SerialName("start")
    data class Start(val setup: MatchSetup) : NetMsg()

    @Serializable @SerialName("ping")
    data object Ping : NetMsg()

    /** Heartbeat + strength report (every second during a match). */
    @Serializable @SerialName("status")
    data class Status(val slot: Int, val defense: Float, val army: Int, val wave: Int) : NetMsg()

    /** The shared realm as the host sees it (a few times per second). */
    @Serializable @SerialName("snap")
    data class Snapshot(
        val pois: List<PoiState>,
        val expeditions: List<Expedition>,
        val players: List<MpPlayer>,
        val rival: Rival?,
        val worldTime: Float,
    ) : NetMsg()

    /** Send soldiers somewhere: a point of interest, an empty cell (scouting) or a player's capital. */
    @Serializable @SerialName("send")
    data class SendArmy(
        val slot: Int,
        val troops: List<TroopData>,
        val poiId: Int = -1,
        val cellX: Int = -1,
        val cellY: Int = -1,
        val targetPlayer: Int = -1,
    ) : NetMsg()

    /** Soldiers (and loot) arriving in a player's base: an army coming home or allied reinforcements. */
    @Serializable @SerialName("home")
    data class ArmyHome(val troops: List<TroopData>, val loot: Cost) : NetMsg()

    @Serializable @SerialName("report")
    data class Report(val text: String, val good: Boolean) : NetMsg()

    /** An enemy army storms this player's base right now. */
    @Serializable @SerialName("assault")
    data class Assault(val assaultId: Int, val units: Int, val hp: Float, val from: String, val enemyId: String = "rival") : NetMsg()

    /** The defender tells the host how its base fought the assault off (and what was stolen). */
    @Serializable @SerialName("assaultResult")
    data class AssaultResult(val assaultId: Int, val total: Int, val killed: Int, val reached: Int, val stolen: Cost) : NetMsg()

    /** Co-op: gift resources to the ally. */
    @Serializable @SerialName("supplies")
    data class Supplies(val from: Int, val to: Int, val amount: Cost) : NetMsg()

    /** Race: how a player's battle is going (every second, and once when it ends). */
    @Serializable @SerialName("race")
    data class RaceStatus(
        val slot: Int, val wave: Int, val keepHp: Int, val keepMax: Int, val leaks: Int, val score: Int,
        val done: Boolean = false, val won: Boolean = false,
    ) : NetMsg()

    /** Race: a raider squad sent into the other player's battlefield. */
    @Serializable @SerialName("raceSend")
    data class RaceSend(val id: Int, val units: Int, val hp: Float, val enemyId: String) : NetMsg()

    /** A game action or result that must survive a temporary connection loss. */
    @Serializable @SerialName("delivery")
    data class Delivery(val sequence: Long, val message: NetMsg) : NetMsg()

    @Serializable @SerialName("ack")
    data class Ack(val sequence: Long) : NetMsg()

    @Serializable @SerialName("bye")
    data class Bye(val reason: String) : NetMsg()
}

object NetCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "t"
    }

    fun encode(msg: NetMsg): String = json.encodeToString(NetMsg.serializer(), msg)
    fun decode(line: String): NetMsg = json.decodeFromString(NetMsg.serializer(), line)
}

object MatchRules {
    const val PROTOCOL = 3
    const val PORT = 47821

    /** Same derivation as [Campaign] so every phone builds the identical realm. */
    fun worldSeed(seed: Long) = seed xor 0x5DEECE66DL

    /** Capitals for each player: Versus = opposite ends of the realm, Co-op = neighbours in the west. */
    fun homes(world: World, mode: MatchMode): List<Pair<Int, Int>> = when (mode) {
        MatchMode.VERSUS, MatchMode.RACE -> listOf(world.home, world.rivalHome)
        MatchMode.COOP -> listOf(world.home, coopHome(world))
    }

    /**
     * Deterministic second capital for co-op: the nearest open spot 6-9 cells from the first, cleared to grass.
     * Called on every phone with the same world, so all of them agree.
     */
    fun coopHome(world: World): Pair<Int, Int> {
        val (hx, hy) = world.home
        for (r in 6..9) for (dy in -r..r) for (dx in 0..r) {
            if (max(abs(dx), abs(dy)) != r) continue
            val x = hx + dx
            val y = hy + dy
            if (x < 2 || y < 2 || x > World.W - 3 || y > World.H - 3) continue
            if (world.pois.any { abs(it.x - x) <= 1 && abs(it.y - y) <= 1 }) continue
            if (world.terrainAt(x, y) == Terrain.GRASS && world.path(hx, hy, x, y) != null) return x to y
        }
        // The generated capital starts with a clear 5×5 area and no adjacent landmarks.
        return hx + 2 to hy
    }

    /** Applies the match layout to a freshly generated world (identical on host and clients). */
    fun prepareWorld(world: World, mode: MatchMode) {
        if (mode == MatchMode.COOP) {
            val (cx, cy) = coopHome(world)
            for (y in cy - 1..cy + 1) for (x in cx - 1..cx + 1) if (world.inBounds(x, y)) world.terrain[world.idx(x, y)] = Terrain.GRASS
        }
    }

    /** Converts an attacking army into enemy soldiers for the defender's base. */
    fun assaultFor(troops: List<TroopData>): Pair<Int, Float> {
        val power = troops.sumOf { Content.troopPower(it).toDouble() }.toFloat()
        val units = ceil(power / 9f).toInt().coerceIn(1, 40)
        val hp = (power / units) * 13f
        return units to hp
    }

    /** What the defender loses when attackers reach the keep. */
    fun plunder(res: MutableRes, reached: Int): Cost {
        if (reached <= 0) return Cost()
        val f = min(0.35f, 0.05f * reached)
        return Cost((res.gold * f).toInt(), (res.wood * f).toInt(), (res.stone * f).toInt())
    }

    /** Strength of a capital as reported to the other player. */
    fun defenseOf(base: BaseGame): Float {
        var d = 0f
        for (s in base.structures.values) if (s.kind == StructureKind.TOWER) {
            d += 12f * s.level * Content.rankDamageMult(s.rank)
        }
        d += base.troops.sumOf { Content.troopPower(it.toData()).toDouble() }.toFloat()
        return d
    }
}

/**
 * The host's authoritative realm. Feed it player messages with [handle], advance it with [step],
 * and deliver everything in [outbox] (slot -1 = everyone).
 */
class MatchRealm(val setup: MatchSetup) {
    val world = World(MatchRules.worldSeed(setup.seed))
    val players = setup.players.map { it.copy() }.toMutableList()
    val expeditions = ArrayList<Expedition>()
    var rival: Rival? = if (setup.mode == MatchMode.COOP) Rival(name = Campaign.RIVAL_NAMES[((setup.seed ushr 3) % Campaign.RIVAL_NAMES.size).toInt()]) else null
        private set
    var worldTime = 0f
        private set
    val outbox = ArrayList<Pair<Int, NetMsg>>()

    private var nextId = 1
    private var nextAssault = 1
    private val assaultToExpedition = HashMap<Int, Int>()
    private val rng = Rng(setup.seed * 17 + 3)
    private var rivalTargetToggle = 0

    init {
        MatchRules.prepareWorld(world, setup.mode)
        if (setup.mode == MatchMode.VERSUS) {
            // The world's "rival capital" landmark is player 2's capital; it is attacked like a player.
            world.pois.removeAll { it.kind == PoiKind.RIVAL_CAPITAL }
        }
    }

    private fun send(slot: Int, msg: NetMsg) {
        outbox += slot to msg
    }

    private fun report(slot: Int, text: String, good: Boolean) = send(slot, NetMsg.Report(text, good))

    private fun name(slot: Int) = players.getOrNull(slot)?.name ?: "Player ${slot + 1}"

    private fun Cost.isNonnegative() = gold >= 0 && wood >= 0 && stone >= 0

    private fun lootText(c: Cost): String = listOfNotNull(
        c.gold.takeIf { it > 0 }?.let { "$it gold" },
        c.wood.takeIf { it > 0 }?.let { "$it wood" },
        c.stone.takeIf { it > 0 }?.let { "$it stone" },
    ).joinToString(", ")

    // -------------------------------------------------------------- incoming

    fun handle(slot: Int, msg: NetMsg) {
        when (msg) {
            is NetMsg.Status -> players.getOrNull(slot)?.let {
                it.defense = msg.defense
                it.army = msg.army
                it.wave = msg.wave
            }
            is NetMsg.SendArmy -> dispatch(slot, msg)
            is NetMsg.AssaultResult -> assaultResult(slot, msg)
            is NetMsg.Supplies -> if (setup.mode == MatchMode.COOP && msg.to in players.indices && msg.to != slot && msg.amount.isNonnegative()) {
                send(msg.to, NetMsg.ArmyHome(emptyList(), msg.amount))
                report(msg.to, "${name(slot)} sent you supplies: ${lootText(msg.amount)}.", true)
                report(slot, "Supplies delivered to ${name(msg.to)}.", true)
            }
            else -> Unit
        }
    }

    fun setConnected(slot: Int, connected: Boolean) {
        players.getOrNull(slot)?.connected = connected
    }

    private fun refund(slot: Int, troops: List<TroopData>, reason: String) {
        send(slot, NetMsg.ArmyHome(troops, Cost()))
        report(slot, reason, false)
    }

    private fun dispatch(slot: Int, m: NetMsg.SendArmy) {
        val owner = players.getOrNull(slot) ?: return
        if (m.troops.isEmpty() || m.troops.any { !Content.isUnit(it.defId) || it.hp <= 0f || !it.hp.isFinite() }) return
        val (hx, hy) = owner.home
        val target: Pair<Int, Int> = when {
            m.targetPlayer >= 0 -> players.getOrNull(m.targetPlayer)?.home ?: return refund(slot, m.troops, "Unknown capital")
            m.poiId >= 0 -> world.pois.firstOrNull { it.id == m.poiId }?.let { it.x to it.y } ?: return refund(slot, m.troops, "Unknown target")
            else -> m.cellX to m.cellY
        }
        if (m.targetPlayer == slot) return refund(slot, m.troops, "That's your own capital")
        val path = world.path(hx, hy, target.first, target.second) ?: return refund(slot, m.troops, "No route there – your soldiers came back")
        expeditions += Expedition(
            id = nextId++, targetPoi = m.poiId, troops = m.troops.toMutableList(), path = path,
            owner = slot, targetPlayer = m.targetPlayer,
        )
    }

    // -------------------------------------------------------------- simulation

    fun step(dt: Float) {
        val sdt = dt * setup.pace.mult
        worldTime += sdt
        val it = expeditions.iterator()
        val finished = ArrayList<Expedition>()
        while (it.hasNext()) {
            val e = it.next()
            if (e.besieging) continue
            val last = (e.path.size - 1).toFloat()
            e.progress = min(last, e.progress + Campaign.MARCH_SPEED * sdt)
            if (e.progress < last) continue
            if (!e.returning) {
                arrive(e)
                if (e.besieging) continue
                if (e.troops.isEmpty()) {
                    finished += e
                    continue
                }
                e.returning = true
                e.path = e.path.reversed()
                e.progress = 0f
            } else {
                send(e.owner, NetMsg.ArmyHome(e.troops.toList(), e.loot))
                val lt = lootText(e.loot)
                report(e.owner, "Your army is home${if (lt.isNotEmpty()) " with $lt" else ""}.", true)
                finished += e
            }
        }
        expeditions.removeAll(finished.toSet())
        stepRival(sdt)
    }

    private fun arrive(e: Expedition) {
        if (e.targetPlayer >= 0) {
            arriveAtCapital(e)
            return
        }
        val poi = world.pois.firstOrNull { it.id == e.targetPoi } ?: return
        if (poi.cleared && poi.kind != PoiKind.RIVAL_CAPITAL) {
            report(e.owner, "${poi.kind.label} was already empty. Marching home.", false)
            return
        }
        when (poi.kind) {
            PoiKind.CACHE -> {
                e.loot = e.loot + poi.loot
                poi.cleared = true
                report(e.owner, "Supplies found: ${lootText(poi.loot)}!", true)
                for (p in players) if (p.slot != e.owner) report(p.slot, "${name(e.owner)} looted a supply cache.", false)
            }
            PoiKind.CAMP, PoiKind.RUINS -> {
                val (won, lost) = battle(e.troops, poi.power)
                if (won) {
                    e.loot = e.loot + poi.loot
                    poi.cleared = true
                    report(e.owner, "${poi.kind.label} conquered! Lost $lost soldiers. Loot: ${lootText(poi.loot)}.", true)
                } else {
                    report(e.owner, "Defeated at the ${poi.kind.label.lowercase()}. Lost $lost soldiers.", false)
                }
            }
            PoiKind.RIVAL_CAPITAL -> {
                val r = rival ?: return
                val (won, lost) = battle(e.troops, r.defense)
                if (won) {
                    val plunder = r.gold * 0.4f
                    r.gold -= plunder
                    r.defense *= 0.6f
                    r.army *= 0.5f
                    r.defeats++
                    e.loot = e.loot + Cost(plunder.toInt(), 40, 40)
                    if (r.defeats >= 3 && !r.vassal) {
                        r.vassal = true
                        for (p in players) report(p.slot, "${r.name} bends the knee! Your alliance has won the realm.", true)
                    } else {
                        for (p in players) report(p.slot, "${name(e.owner)} stormed ${r.name}'s capital (victory ${r.defeats}/3)!", true)
                    }
                } else {
                    report(e.owner, "The assault on ${r.name}'s capital failed. Lost $lost soldiers.", false)
                }
            }
        }
    }

    private fun arriveAtCapital(e: Expedition) {
        val defender = players.getOrNull(e.targetPlayer) ?: return
        if (setup.mode == MatchMode.COOP) {
            // Reinforcements join the ally's garrison for good.
            send(defender.slot, NetMsg.ArmyHome(e.troops.toList(), Cost()))
            report(defender.slot, "${name(e.owner)} sent you ${e.troops.size} soldiers as reinforcements!", true)
            report(e.owner, "Your ${e.troops.size} soldiers joined ${defender.name}'s garrison.", true)
            e.troops.clear()
            return
        }
        if (!defender.connected) {
            // Nobody home to fight it out live: auto-resolve against the last reported defense.
            val (won, lost) = battle(e.troops, max(30f, defender.defense))
            if (won) {
                val loot = Cost(60, 30, 30)
                e.loot = e.loot + loot
                report(e.owner, "${defender.name}'s capital was caught off guard! Loot: ${lootText(loot)}. Lost $lost soldiers.", true)
            } else {
                report(e.owner, "${defender.name}'s defenders held. Lost $lost soldiers.", false)
            }
            return
        }
        val (units, hp) = MatchRules.assaultFor(e.troops)
        val id = nextAssault++
        assaultToExpedition[id] = e.id
        e.besieging = true
        send(defender.slot, NetMsg.Assault(id, units, hp, name(e.owner)))
        report(defender.slot, "${name(e.owner)}'s army is storming your base with $units soldiers!", false)
        report(e.owner, "Your army reached ${defender.name}'s capital – the assault begins!", true)
    }

    private fun assaultResult(defenderSlot: Int, r: NetMsg.AssaultResult) {
        if (r.total <= 0 || r.killed !in 0..r.total || r.reached != r.total - r.killed || !r.stolen.isNonnegative()) return
        val expId = assaultToExpedition[r.assaultId]
        val e = expeditions.firstOrNull { it.id == expId }
        if (e == null) {
            // A rival (AI) assault in co-op – nothing to send home.
            if (r.reached > 0) report(defenderSlot, "Raiders broke through and stole ${lootText(r.stolen)}.", false)
            return
        }
        if (e.targetPlayer != defenderSlot || r.total != MatchRules.assaultFor(e.troops).first) return
        assaultToExpedition.remove(r.assaultId)
        e.besieging = false
        val frac = r.reached.toFloat() / r.total
        val keep = ceil(e.troops.size * frac).toInt().coerceIn(0, e.troops.size)
        val survivors = e.troops.sortedByDescending { it.hp }.take(keep)
        e.troops.clear()
        e.troops += survivors
        for (t in e.troops) t.xp += 25
        e.loot = e.loot + r.stolen
        val defender = name(defenderSlot)
        if (r.reached > 0) {
            report(e.owner, "Your soldiers broke into $defender's keep and plundered ${lootText(r.stolen)}! ${e.troops.size} survivors march home.", true)
            report(defenderSlot, "${name(e.owner)}'s soldiers broke through and stole ${lootText(r.stolen)}.", false)
        } else {
            report(e.owner, "$defender's towers destroyed your whole army.", false)
            report(defenderSlot, "You crushed ${name(e.owner)}'s assault!", true)
        }
        if (e.troops.isEmpty()) {
            expeditions.remove(e)
            return
        }
        e.returning = true
        e.path = e.path.reversed()
        e.progress = 0f
    }

    /** Auto-resolved battle (same rules as single-player). Mutates [troops]. */
    private fun battle(troops: MutableList<TroopData>, defense: Float): Pair<Boolean, Int> {
        val attack = troops.sumOf { Content.troopPower(it).toDouble() }.toFloat()
        val ratio = attack / max(1f, defense) * rng.range(0.85f, 1.15f)
        val won = ratio >= 1f
        val lossFraction = if (won) min(0.8f, 0.4f / ratio) else (1f - 0.3f * ratio).coerceIn(0.5f, 1f)
        var damagePool = troops.sumOf { it.hp.toDouble() }.toFloat() * lossFraction
        val before = troops.size
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

    /** Co-op only: the AI lord grows and raids the two players in turn. */
    private fun stepRival(dt: Float) {
        val r = rival ?: return
        if (r.vassal) return
        val growth = setup.difficulty.rivalGrowth * (1f + 0.25f * (players.size - 1))
        r.defense += 0.07f * growth * dt
        r.army += 0.05f * growth * dt
        r.gold += 0.4f * growth * dt
        world.pois.firstOrNull { it.kind == PoiKind.RIVAL_CAPITAL }?.power = r.defense
        if (!r.contact) {
            if (worldTime * growth >= Campaign.CONTACT_SECONDS * 0.6f) {
                r.contact = true
                r.raidTimer = Campaign.RIVAL_RAID_INTERVAL
                for (p in players) report(p.slot, "${r.name}'s scouts have found your lands! Expect attacks.", false)
            }
            return
        }
        r.raidTimer -= dt
        if (r.raidTimer > 0f) return
        r.raidTimer = Campaign.RIVAL_RAID_INTERVAL
        val connected = players.filter { it.connected }
        if (connected.isEmpty()) return
        val target = connected[rivalTargetToggle++ % connected.size]
        val units = (r.army / 22f).toInt().coerceIn(2, 14)
        r.army *= 0.55f
        val id = nextAssault++
        send(target.slot, NetMsg.Assault(id, units, Content.enemy("rival").hp * setup.difficulty.raidHp, r.name))
        for (p in players) report(p.slot, "${r.name} attacks ${if (p.slot == target.slot) "you" else target.name} with $units soldiers!", false)
    }

    fun snapshot(): NetMsg.Snapshot = NetMsg.Snapshot(
        pois = world.pois.map { PoiState(it.id, it.cleared, it.power, it.loot) },
        expeditions = expeditions.map { it.copy(troops = it.troops.map { t -> t.copy() }.toMutableList()) },
        players = players.map { it.copy() },
        rival = rival?.copy(),
        worldTime = worldTime,
    )
}
