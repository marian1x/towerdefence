package com.marian1x.agesofsiege.core

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * Campaign levels: hand-designed battlefields with a fixed number of waves.
 * A level is won by surviving every wave; stars depend on how many raiders reached the Keep.
 * Only gold counts in levels (kills, wave rewards, calling waves early).
 */

/** Ground of one battlefield tile. */
enum class Tile(val buildable: Boolean) {
    GRASS(true), ROAD(false), ROCK(false), TREE(false), WATER(false),
}

/** Look of a level: lighting, sky, weather and colour grading (used by the renderer). */
enum class LevelTheme { MEADOW, AUTUMN, FROST, NIGHT, MARSH, EMBER }

@Serializable
enum class LevelMode(val label: String) {
    NORMAL("Normal"), HEROIC("Heroic"), IRON("Iron"), ENDLESS("Endless"), DAILY("Daily"), RACE("Race"),
}

/** Optional rule changes (Daily Challenge). */
@Serializable
enum class BattleModifier(val label: String, val description: String) {
    SWIFT("Swift foes", "Raiders move 20% faster"),
    ARMORED("Iron horde", "Raiders have 25% more health"),
    NO_WALLS("No masons", "Walls can't be built"),
    LEAN("Lean purse", "Start with 30% less gold"),
    GOLD_RUSH("Gold rush", "Kills pay 50% more gold"),
    FRAGILE("Old stones", "The Keep has half its health"),
    NO_ABILITIES("No commanders", "Abilities are disabled"),
    STORM("Arrow storm", "Abilities recharge twice as fast"),
}

/** One group of raiders inside a wave. */
data class WaveGroup(val enemyId: String, val count: Int, val gate: Int, val gap: Float)

/** A raider type joining a level's waves from [fromWave], with a share of the wave's strength. */
data class RosterEntry(val enemyId: String, val fromWave: Int, val weight: Float)

/** A generated battlefield: tiles, entry gates and the Keep. */
class LevelMap(val tiles: Array<Tile>, val gates: List<Pair<Int, Int>>, val keepX: Int, val keepY: Int) {
    fun tile(x: Int, y: Int) = tiles[y * BaseGame.WIDTH + x]
}

/**
 * How a battlefield is laid out. Road levels carve [routes] (gate -> ... -> next to the Keep) and raiders stay on them.
 * Open-field levels only use the first point of each route as a gate; raiders walk anywhere and walls reshape the path.
 */
class MapSpec(
    val keepX: Int = 21,
    val keepY: Int = 7,
    val routes: List<List<Pair<Int, Int>>>,
    val water: List<IntArray> = emptyList(),
    val rocks: List<IntArray> = emptyList(),
    val trees: Int = 14,
    val boulders: Int = 4,
)

class LevelDef(
    val id: String,
    val chapter: Int,
    val number: Int,
    val name: String,
    val intro: String,
    val roads: Boolean,
    val spec: MapSpec,
    val waveCount: Int,
    val roster: List<RosterEntry>,
    val boss: String? = null,
    val startGold: Int,
    val keepHp: Int = 20,
    val towers: List<String>,
    val abilities: List<Ability>,
    /** Leaks allowed for two stars (zero leaks = three stars). */
    val twoStarLeaks: Int,
    val hpScale: Float,
    /** Strength budget of wave 1 and how much it grows per wave (raider = 1). */
    val baseThreat: Float,
    val threatGrowth: Float,
    val theme: LevelTheme,
    val tip: String,
    /** What this level introduces (shown on the briefing). */
    val unlocks: String = "",
) {
    val map: LevelMap by lazy { LevelMaps.build(this) }
    val waves: List<List<WaveGroup>> by lazy { (1..waveCount).map { LevelWaves.wave(this, it) } }
    val enemyIds: List<String> get() = (roster.map { it.enemyId } + listOfNotNull(boss)).distinct()
    val seed: Long get() = id.hashCode().toLong() * 7919L + 17L

    /** HP multiplier for wave [w] (1-based), before mode/modifiers. */
    fun hpFor(w: Int): Float {
        val n = (w - 1).toFloat()
        return hpScale * (1f + 0.075f * n + 0.004f * n * n)
    }

    fun waveReward(w: Int) = 22 + 5 * w

    fun starsFor(leaks: Int): Int = when {
        leaks <= 0 -> 3
        leaks <= twoStarLeaks -> 2
        else -> 1
    }
}

/** Everything that defines one battle in a level (also used by the Daily Challenge and Race mode). */
data class BattleSetup(
    val level: LevelDef,
    val mode: LevelMode = LevelMode.NORMAL,
    val modifiers: Set<BattleModifier> = emptySet(),
    /** Glory perk levels (empty for Daily/Race so every player is equal). */
    val perks: Map<String, Int> = emptyMap(),
) {
    fun has(m: BattleModifier) = m in modifiers
    fun perk(id: String) = perks[id] ?: 0
}

// ---------------------------------------------------------------------------------- map building

internal object LevelMaps {
    private const val W = BaseGame.WIDTH
    private const val H = BaseGame.HEIGHT

    fun build(level: LevelDef): LevelMap {
        val spec = level.spec
        val tiles = Array(W * H) { Tile.GRASS }
        val rng = Rng(level.seed)
        fun set(x: Int, y: Int, t: Tile) { if (x in 0 until W && y in 0 until H) tiles[y * W + x] = t }
        for (r in spec.water) for (y in r[1]..r[3]) for (x in r[0]..r[2]) set(x, y, Tile.WATER)
        for (r in spec.rocks) for (y in r[1]..r[3]) for (x in r[0]..r[2]) set(x, y, Tile.ROCK)
        val gates = spec.routes.map { it.first() }
        val roadTiles = HashSet<Int>()
        for (route in spec.routes) {
            // Open fields only get a short dirt track at each entrance.
            val points = if (level.roads) route else route.take(1) + listOf(stub(route.first()))
            for (i in 0 until points.size - 1) {
                var (x, y) = points[i]
                val (tx, ty) = points[i + 1]
                roadTiles += y * W + x
                while (x != tx || y != ty) {
                    if (x != tx) {
                        x += if (tx > x) 1 else -1
                    } else {
                        y += if (ty > y) 1 else -1
                    }
                    roadTiles += y * W + x
                }
            }
        }
        for (i in roadTiles) tiles[i] = Tile.ROAD
        val keep = spec.keepX to spec.keepY
        for (y in keep.second..keep.second + 1) for (x in keep.first..keep.first + 1) set(x, y, Tile.GRASS)

        fun nearKeep(x: Int, y: Int) = x >= keep.first - 2 && x <= keep.first + 3 && y >= keep.second - 2 && y <= keep.second + 3
        fun nearGate(x: Int, y: Int) = gates.any { abs(it.first - x) + abs(it.second - y) < 3 }
        fun nearRoad(x: Int, y: Int): Boolean {
            for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx; val ny = y + dy
                if (nx in 0 until W && ny in 0 until H && tiles[ny * W + nx] == Tile.ROAD) return true
            }
            return false
        }
        // Scatter trees and boulders. On open fields every gate must keep a path to the Keep.
        for ((kind, count) in listOf(Tile.TREE to spec.trees, Tile.ROCK to spec.boulders)) {
            var placed = 0
            var tries = 0
            while (placed < count && tries < 600) {
                tries++
                val x = 1 + rng.nextInt(W - 2)
                val y = 1 + rng.nextInt(H - 2)
                val i = y * W + x
                if (tiles[i] != Tile.GRASS || nearKeep(x, y) || nearGate(x, y)) continue
                // Leave room to build next to roads.
                if (level.roads && nearRoad(x, y) && rng.nextFloat() < 0.85f) continue
                tiles[i] = kind
                if (!level.roads && !connected(tiles, gates, keep, roads = false)) {
                    tiles[i] = Tile.GRASS
                    continue
                }
                placed++
            }
        }
        return LevelMap(tiles, gates, keep.first, keep.second)
    }

    private fun stub(g: Pair<Int, Int>): Pair<Int, Int> {
        val (x, y) = g
        return when {
            x == 0 -> 2 to y
            x == W - 1 -> W - 3 to y
            y == 0 -> x to 2
            else -> x to H - 3
        }
    }

    fun walkable(t: Tile, roads: Boolean) = when (t) {
        Tile.ROAD -> true
        Tile.GRASS -> !roads
        else -> false
    }

    /** True when every gate can reach the Keep. */
    fun connected(tiles: Array<Tile>, gates: List<Pair<Int, Int>>, keep: Pair<Int, Int>, roads: Boolean): Boolean {
        val blocked = BooleanArray(W * H) { !walkable(tiles[it], roads) }
        val goal = BooleanArray(W * H)
        for (y in keep.second..keep.second + 1) for (x in keep.first..keep.first + 1) {
            goal[y * W + x] = true
            blocked[y * W + x] = true
        }
        val f = FlowField(W, H)
        f.compute(blocked, goal)
        return gates.all { (x, y) -> f.dist[y * W + x] != FlowField.INF }
    }
}

// ---------------------------------------------------------------------------------- wave generation

object LevelWaves {
    /** How much of a wave's strength one raider of this type uses. */
    fun threat(id: String) = when (id) {
        "raider" -> 1f
        "outrider" -> 1.5f
        "brute" -> 3f
        "shieldbearer" -> 3.4f
        "shaman" -> 3f
        "harpy" -> 2f
        "ram" -> 9f
        "warlord" -> 30f
        else -> 3f
    }

    private fun gap(id: String) = when (id) {
        "outrider" -> 0.55f
        "harpy" -> 0.7f
        "brute", "shieldbearer" -> 1.25f
        "shaman" -> 1.6f
        "ram" -> 3.2f
        "warlord" -> 3f
        else -> 0.75f
    }

    /** Global knob for how fast waves grow inside a level. */
    private const val GROWTH = 0.8f

    fun budget(level: LevelDef, w: Int) = level.baseThreat + level.threatGrowth * GROWTH * (w - 1)

    /** Deterministic composition of wave [w]: strength is shared between the raider types unlocked so far. */
    fun wave(level: LevelDef, w: Int): List<WaveGroup> {
        val gates = level.spec.routes.size
        val active = level.roster.filter { it.fromWave <= w }
        val budget = budget(level, w)
        val total = active.sumOf { it.weight.toDouble() }.toFloat().coerceAtLeast(0.01f)
        val groups = ArrayList<WaveGroup>()
        var g = w // rotate the first gate every wave
        var used = 0f
        for (e in active.sortedBy { threat(it.enemyId) }) {
            val share = budget * e.weight / total
            var count = (share / threat(e.enemyId)).toInt()
            if (e.fromWave == w) count = max(count, if (threat(e.enemyId) >= 8f) 1 else 3) // a clear introduction
            if (count <= 0) continue
            used += count * threat(e.enemyId)
            // Big groups split over two gates so every entrance sees action.
            if (count >= 6 && gates > 1) {
                groups += WaveGroup(e.enemyId, count - count / 2, g % gates, gap(e.enemyId))
                groups += WaveGroup(e.enemyId, count / 2, (g + 1) % gates, gap(e.enemyId))
                g += 2
            } else {
                groups += WaveGroup(e.enemyId, count, g % gates, gap(e.enemyId))
                g++
            }
        }
        val rest = ((budget - used) / threat("raider")).toInt()
        if (rest > 0 && level.roster.any { it.enemyId == "raider" }) groups.add(0, WaveGroup("raider", rest, g++ % gates, gap("raider")))
        if (level.boss != null && w == level.waveCount) groups += WaveGroup(level.boss, 1, (w + 1) % gates, gap(level.boss))
        if (level.boss != null && level.waveCount >= 12 && w == level.waveCount / 2) {
            groups += WaveGroup("brute", 3, (w + 1) % gates, gap("brute")) // a mid-level spike
        }
        return groups
    }

    /** Endless waves after the script: every type the level knows, growing forever. */
    fun endless(level: LevelDef, w: Int): List<WaveGroup> {
        val gates = level.spec.routes.size
        val budget = level.baseThreat + level.threatGrowth * (w - 1) * 1.15f
        val ids = level.enemyIds.filter { it != "warlord" }
        val out = ArrayList<WaveGroup>()
        var used = 0f
        for ((k, id) in ids.withIndex()) {
            val count = (budget / ids.size / threat(id)).toInt()
            if (count <= 0) continue
            used += count * threat(id)
            out += WaveGroup(id, count, (w + k) % gates, gap(id))
        }
        if (budget - used > 1f) out.add(0, WaveGroup("raider", (budget - used).toInt(), w % gates, gap("raider")))
        if (w % 5 == 0) out += WaveGroup("warlord", w / 10 + 1, w % gates, gap("warlord"))
        return out
    }

    fun spawns(groups: List<WaveGroup>): List<SpawnEntry> {
        val out = ArrayList<SpawnEntry>()
        var t = 0f
        for (gr in groups) {
            repeat(gr.count) {
                out += SpawnEntry(t, gr.enemyId, gr.gate)
                t += gr.gap
            }
            t += 1.4f
        }
        return out
    }
}

// ---------------------------------------------------------------------------------- the campaign

object Levels {
    private fun r(vararg pts: Int): List<Pair<Int, Int>> = pts.toList().chunked(2).map { it[0] to it[1] }
    private fun rect(x0: Int, y0: Int, x1: Int, y1: Int) = intArrayOf(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))
    private fun roster(vararg e: Pair<String, Pair<Int, Float>>) = e.map { RosterEntry(it.first, it.second.first, it.second.second) }

    private val basic = listOf("archer", "ballista")
    private val withCatapult = basic + "catapult" + "wall"

    val chapters = listOf(
        "The Greenvale" to "Marauders raid the quiet valleys of your realm. Hold the border.",
        "Frostpeak Pass" to "The raiders flee into the mountains, with sky raiders and shamans at their side.",
        "The Ashen Marches" to "Burning marshes guard the Marauder King's stronghold. End this war.",
    )

    val all: List<LevelDef> = listOf(
        // ------------------------------------------------------------------ Chapter 1: The Greenvale
        LevelDef(
            "1-1", 1, 1, "Border Watch",
            "A small band of raiders follows the old road toward your watchtower.",
            roads = true, spec = MapSpec(routes = listOf(r(0, 4, 6, 4, 6, 11, 14, 11, 14, 7, 20, 7))),
            waveCount = 6, roster = roster("raider" to (1 to 1f), "outrider" to (4 to 0.35f)),
            startGold = 230, towers = basic, abilities = listOf(Ability.VOLLEY), twoStarLeaks = 4,
            hpScale = 0.85f, baseThreat = 6f, threatGrowth = 2.2f, theme = LevelTheme.MEADOW,
            tip = "Build Archer Towers where the road bends – they cover it twice.",
            unlocks = "Archer Tower · Ballista · Arrow Volley",
        ),
        LevelDef(
            "1-2", 1, 2, "Old Mill Road",
            "Two roads meet at the mill. Axe Brutes in heavy armor march with the raiders.",
            roads = true, spec = MapSpec(routes = listOf(r(0, 3, 8, 3, 8, 8, 20, 8), r(10, 15, 10, 12, 16, 12, 16, 8))),
            waveCount = 8, roster = roster("raider" to (1 to 1f), "brute" to (3 to 0.45f), "outrider" to (5 to 0.3f)),
            startGold = 260, towers = basic, abilities = listOf(Ability.VOLLEY), twoStarLeaks = 5,
            hpScale = 0.9f, baseThreat = 7f, threatGrowth = 2.6f, theme = LevelTheme.MEADOW,
            tip = "Arrows bounce off armor. Ballista bolts deal 170% damage to Brutes.",
            unlocks = "Axe Brutes",
        ),
        LevelDef(
            "1-3", 1, 3, "Hilltop Fields",
            "Open farmland, no roads. Build walls and force the raiders to take the long way.",
            roads = false, spec = MapSpec(
                routes = listOf(r(0, 3), r(0, 12)),
                rocks = listOf(rect(10, 1, 10, 5), rect(10, 10, 10, 14)), trees = 16,
            ),
            waveCount = 8, roster = roster("raider" to (1 to 1f), "outrider" to (2 to 0.3f), "brute" to (4 to 0.4f)),
            startGold = 300, towers = withCatapult, abilities = listOf(Ability.VOLLEY), twoStarLeaks = 5,
            hpScale = 0.95f, baseThreat = 8f, threatGrowth = 2.8f, theme = LevelTheme.AUTUMN,
            tip = "Walls cost almost nothing. A long maze in front of a Catapult is deadly.",
            unlocks = "Stone Walls · Catapult",
        ),
        LevelDef(
            "1-4", 1, 4, "Riverford",
            "The raiders cross the river at two fords. Fast Outriders try to slip past.",
            roads = true, spec = MapSpec(
                routes = listOf(r(0, 2, 4, 2, 4, 6, 17, 6, 17, 8, 20, 8), r(0, 13, 8, 13, 8, 10, 14, 10, 14, 6)),
                water = listOf(rect(11, 0, 12, 15)), trees = 12,
            ),
            waveCount = 10, roster = roster("raider" to (1 to 1f), "outrider" to (2 to 0.6f), "brute" to (5 to 0.4f)),
            startGold = 300, towers = withCatapult + "frost", abilities = listOf(Ability.VOLLEY), twoStarLeaks = 5,
            hpScale = 1f, baseThreat = 9f, threatGrowth = 3f, theme = LevelTheme.MEADOW,
            tip = "The Frost Spire slows raiders by 40% – put it where two roads meet.",
            unlocks = "Frost Spire",
        ),
        LevelDef(
            "1-5", 1, 5, "Shield Wall",
            "Shieldbearers lead the charge. Their huge shields stop boulders and flames.",
            roads = true, spec = MapSpec(routes = listOf(r(6, 0, 6, 5, 12, 5, 12, 7, 20, 7), r(6, 15, 6, 10, 12, 10, 12, 8, 20, 8))),
            waveCount = 10, roster = roster("raider" to (1 to 1f), "shieldbearer" to (3 to 0.5f), "outrider" to (4 to 0.3f), "brute" to (6 to 0.35f)),
            startGold = 320, towers = withCatapult + "frost" + "repeater", abilities = listOf(Ability.VOLLEY), twoStarLeaks = 5,
            hpScale = 1.05f, baseThreat = 10f, threatGrowth = 3.2f, theme = LevelTheme.AUTUMN,
            tip = "Splash damage barely scratches Shieldbearers. Repeaters shred them.",
            unlocks = "Shieldbearers · Repeater",
        ),
        LevelDef(
            "1-6", 1, 6, "The Warlord's Raid",
            "The Marauder Warlord himself rides to battle. He smashes walls when enraged.",
            roads = false, spec = MapSpec(
                routes = listOf(r(0, 3), r(0, 12), r(12, 0)),
                rocks = listOf(rect(7, 6, 8, 9), rect(15, 3, 16, 4), rect(15, 11, 16, 12)), trees = 14,
            ),
            waveCount = 12, roster = roster("raider" to (1 to 1f), "outrider" to (2 to 0.35f), "brute" to (3 to 0.4f), "shieldbearer" to (6 to 0.35f)),
            boss = "warlord",
            startGold = 430, towers = withCatapult + "frost" + "repeater" + "banner", abilities = listOf(Ability.VOLLEY), twoStarLeaks = 6,
            hpScale = 1.05f, baseThreat = 9f, threatGrowth = 3f, theme = LevelTheme.AUTUMN,
            tip = "A War Banner makes nearby towers hit harder and shoot faster.",
            unlocks = "War Banner · Boss: the Warlord",
        ),
        // ------------------------------------------------------------------ Chapter 2: Frostpeak Pass
        LevelDef(
            "2-1", 2, 1, "Snowbound Gate",
            "Sky Raiders glide over the snow straight at the Keep. Only arrows and bolts can reach them.",
            roads = true, spec = MapSpec(routes = listOf(r(0, 7, 5, 7, 5, 2, 11, 2, 11, 13, 17, 13, 17, 8, 20, 8)), trees = 16),
            waveCount = 10, roster = roster("raider" to (1 to 1f), "harpy" to (2 to 0.45f), "brute" to (4 to 0.35f)),
            startGold = 340, towers = withCatapult + "frost" + "repeater" + "banner", abilities = listOf(Ability.VOLLEY, Ability.TAR),
            twoStarLeaks = 5, hpScale = 1.1f, baseThreat = 11f, threatGrowth = 3.4f, theme = LevelTheme.FROST,
            tip = "Sky Raiders ignore the road. Keep a few archers close to the Keep.",
            unlocks = "Sky Raiders · Tar Pit",
        ),
        LevelDef(
            "2-2", 2, 2, "Frozen Lake",
            "A frozen lake splits the valley. Light the braziers – fire shatters frozen raiders.",
            roads = false, spec = MapSpec(
                routes = listOf(r(0, 2), r(0, 13)),
                water = listOf(rect(9, 5, 15, 10)), trees = 14,
            ),
            waveCount = 10, roster = roster("raider" to (1 to 1f), "outrider" to (2 to 0.45f), "brute" to (3 to 0.4f), "harpy" to (5 to 0.3f)),
            startGold = 450, towers = withCatapult + "frost" + "brazier" + "repeater" + "banner", abilities = listOf(Ability.VOLLEY, Ability.TAR),
            twoStarLeaks = 5, hpScale = 1.1f, baseThreat = 9f, threatGrowth = 3.2f, theme = LevelTheme.FROST,
            tip = "Combo: a Flame Brazier hitting a chilled raider SHATTERS it for 160% damage.",
            unlocks = "Flame Brazier · Shatter combo",
        ),
        LevelDef(
            "2-3", 2, 3, "Shaman's Circle",
            "War Shamans chant at the standing stones, healing every raider around them.",
            roads = true, spec = MapSpec(routes = listOf(r(0, 3, 9, 3, 9, 7, 20, 7), r(0, 12, 9, 12, 9, 8, 20, 8)), boulders = 6),
            waveCount = 12, roster = roster("raider" to (1 to 1f), "shaman" to (2 to 0.3f), "brute" to (3 to 0.4f), "outrider" to (5 to 0.3f)),
            startGold = 360, towers = withCatapult + "frost" + "brazier" + "repeater" + "banner", abilities = listOf(Ability.VOLLEY, Ability.TAR),
            twoStarLeaks = 6, hpScale = 1.15f, baseThreat = 12f, threatGrowth = 3.6f, theme = LevelTheme.FROST,
            tip = "Set a tower to target STRONGEST or FASTEST from its panel to snipe Shamans.",
            unlocks = "War Shamans · Targeting priority",
        ),
        LevelDef(
            "2-4", 2, 4, "Night Siege",
            "Under a moonless sky the raiders come from three sides at once.",
            roads = true, spec = MapSpec(routes = listOf(
                r(0, 2, 7, 2, 7, 7, 20, 7), r(0, 13, 14, 13, 14, 8, 20, 8), r(13, 0, 13, 4, 18, 4, 18, 7),
            )),
            waveCount = 12, roster = roster("raider" to (1 to 1f), "outrider" to (2 to 0.4f), "harpy" to (3 to 0.3f), "shaman" to (5 to 0.25f), "brute" to (4 to 0.4f)),
            startGold = 440, towers = withCatapult + "frost" + "brazier" + "repeater" + "banner", abilities = listOf(Ability.VOLLEY, Ability.TAR),
            twoStarLeaks = 6, hpScale = 1.15f, baseThreat = 11f, threatGrowth = 3.4f, theme = LevelTheme.NIGHT,
            tip = "Three gates, one Keep. Defend where the roads join.",
        ),
        LevelDef(
            "2-5", 2, 5, "Avalanche Ridge",
            "Battering Rams roll down the pass. Smash one and its crew jumps out.",
            roads = false, spec = MapSpec(
                routes = listOf(r(0, 2), r(0, 13)),
                rocks = listOf(rect(3, 5, 7, 5), rect(10, 5, 18, 5), rect(7, 10, 14, 10), rect(17, 10, 20, 10)), trees = 10,
            ),
            waveCount = 12, roster = roster("raider" to (1 to 1f), "brute" to (2 to 0.4f), "ram" to (3 to 0.35f), "harpy" to (6 to 0.25f), "shieldbearer" to (8 to 0.3f)),
            startGold = 470, towers = withCatapult + "frost" + "brazier" + "repeater" + "banner",
            abilities = listOf(Ability.VOLLEY, Ability.TAR, Ability.REINFORCE),
            twoStarLeaks = 6, hpScale = 1.15f, baseThreat = 10f, threatGrowth = 3.5f, theme = LevelTheme.FROST,
            tip = "Call the Militia in front of a Ram – they hold it while your towers work.",
            unlocks = "Battering Rams · Militia",
        ),
        LevelDef(
            "2-6", 2, 6, "The Ice Warlord",
            "The Warlord returns, wrapped in furs and fury, with an army of the mountains.",
            roads = false, spec = MapSpec(
                routes = listOf(r(0, 3), r(0, 12), r(12, 15)),
                rocks = listOf(rect(6, 7, 7, 8), rect(13, 3, 14, 5), rect(17, 11, 18, 12)), water = listOf(rect(9, 10, 10, 11)), trees = 12,
            ),
            waveCount = 14, roster = roster("raider" to (1 to 1f), "outrider" to (2 to 0.3f), "brute" to (2 to 0.4f), "harpy" to (4 to 0.3f), "shaman" to (5 to 0.25f), "ram" to (8 to 0.25f)),
            boss = "warlord",
            startGold = 490, towers = withCatapult + "frost" + "brazier" + "repeater" + "banner",
            abilities = listOf(Ability.VOLLEY, Ability.TAR, Ability.REINFORCE),
            twoStarLeaks = 7, hpScale = 1.2f, baseThreat = 10f, threatGrowth = 3.6f, theme = LevelTheme.FROST,
            tip = "Keep walls away from where the Warlord walks – or let him waste time smashing them.",
        ),
        // ------------------------------------------------------------------ Chapter 3: The Ashen Marches
        LevelDef(
            "3-1", 3, 1, "Bog Road",
            "A causeway winds through the stinking marsh. Fog hides what is coming.",
            roads = true, spec = MapSpec(
                routes = listOf(r(0, 8, 4, 8, 4, 3, 10, 3, 10, 12, 16, 12, 16, 6, 21, 6)),
                water = listOf(rect(1, 11, 3, 13), rect(13, 1, 15, 2), rect(19, 12, 21, 14), rect(6, 6, 7, 8)), trees = 12,
            ),
            waveCount = 12, roster = roster("raider" to (1 to 1f), "shieldbearer" to (2 to 0.35f), "shaman" to (3 to 0.25f), "outrider" to (4 to 0.3f), "ram" to (7 to 0.25f)),
            startGold = 420, towers = withCatapult + "frost" + "brazier" + "repeater" + "banner",
            abilities = listOf(Ability.VOLLEY, Ability.TAR, Ability.REINFORCE),
            twoStarLeaks = 6, hpScale = 1.3f, baseThreat = 15f, threatGrowth = 4.2f, theme = LevelTheme.MARSH,
            tip = "One long road: stack your defense on the final stretch.",
        ),
        LevelDef(
            "3-2", 3, 2, "Burning Farms",
            "The Marauders torch the farms as they come. Fight among the burning fields.",
            roads = false, spec = MapSpec(
                routes = listOf(r(0, 4), r(0, 11), r(16, 0)),
                rocks = listOf(rect(8, 2, 8, 6), rect(8, 9, 8, 13), rect(13, 7, 15, 8)), trees = 20,
            ),
            waveCount = 12, roster = roster("raider" to (1 to 1f), "outrider" to (2 to 0.45f), "brute" to (3 to 0.4f), "harpy" to (4 to 0.3f), "shieldbearer" to (6 to 0.3f)),
            startGold = 510, towers = withCatapult + "frost" + "brazier" + "repeater" + "banner",
            abilities = listOf(Ability.VOLLEY, Ability.TAR, Ability.REINFORCE),
            twoStarLeaks = 6, hpScale = 1.3f, baseThreat = 11f, threatGrowth = 3.8f, theme = LevelTheme.EMBER,
            tip = "Funnel the gates together with walls so every tower fights every wave.",
        ),
        LevelDef(
            "3-3", 3, 3, "Sky Raid",
            "Your Keep stands in the middle of the plain. Raiders come from both sides – and from the sky.",
            roads = true, spec = MapSpec(
                keepX = 12, keepY = 7,
                routes = listOf(r(0, 7, 4, 7, 4, 3, 8, 3, 8, 7, 11, 7), r(25, 8, 21, 8, 21, 12, 17, 12, 17, 8, 14, 8)),
                trees = 14,
            ),
            waveCount = 12, roster = roster("raider" to (1 to 0.8f), "harpy" to (1 to 0.7f), "outrider" to (3 to 0.3f), "brute" to (4 to 0.35f), "shaman" to (6 to 0.2f)),
            startGold = 490, towers = withCatapult + "frost" + "brazier" + "repeater" + "banner",
            abilities = listOf(Ability.VOLLEY, Ability.TAR, Ability.REINFORCE),
            twoStarLeaks = 6, hpScale = 1.3f, baseThreat = 12f, threatGrowth = 3.8f, theme = LevelTheme.EMBER,
            tip = "Catapults can't hit flyers – this is a job for archers and repeaters.",
        ),
        LevelDef(
            "3-4", 3, 4, "Four Gates",
            "Surrounded. The marsh opens on every side of the Keep.",
            roads = false, spec = MapSpec(
                keepX = 12, keepY = 7,
                routes = listOf(r(0, 3), r(25, 12), r(6, 0), r(19, 15)),
                water = listOf(rect(3, 9, 5, 11), rect(19, 3, 21, 5)), rocks = listOf(rect(9, 4, 10, 4), rect(15, 11, 16, 11)), trees = 12,
            ),
            waveCount = 14, roster = roster("raider" to (1 to 1f), "outrider" to (2 to 0.35f), "brute" to (2 to 0.35f), "shaman" to (4 to 0.25f), "harpy" to (5 to 0.3f), "ram" to (8 to 0.25f)),
            startGold = 560, towers = withCatapult + "frost" + "brazier" + "repeater" + "banner",
            abilities = listOf(Ability.VOLLEY, Ability.TAR, Ability.REINFORCE),
            twoStarLeaks = 7, hpScale = 1.3f, baseThreat = 9f, threatGrowth = 3.8f, theme = LevelTheme.MARSH,
            tip = "Build a ring of walls around the Keep with a single opening.",
        ),
        LevelDef(
            "3-5", 3, 5, "Siege of Ashford",
            "Rams and shield walls on three roads. The last town before the stronghold.",
            roads = true, spec = MapSpec(routes = listOf(
                r(0, 1, 12, 1, 12, 6, 21, 6), r(0, 14, 12, 14, 12, 9, 21, 9), r(0, 8, 20, 8),
            ), trees = 10),
            waveCount = 14, roster = roster("raider" to (1 to 1f), "shieldbearer" to (2 to 0.4f), "brute" to (3 to 0.35f), "ram" to (4 to 0.35f), "shaman" to (6 to 0.25f), "harpy" to (8 to 0.25f)),
            startGold = 500, towers = withCatapult + "frost" + "brazier" + "repeater" + "banner",
            abilities = listOf(Ability.VOLLEY, Ability.TAR, Ability.REINFORCE),
            twoStarLeaks = 7, hpScale = 1.45f, baseThreat = 18f, threatGrowth = 4.8f, theme = LevelTheme.EMBER,
            tip = "Rams take forever to die – slow them with Frost and tar, then pile on.",
        ),
        LevelDef(
            "3-6", 3, 6, "The Marauder King",
            "The Marauder King leads every warband he has left. Win this and the realm is free.",
            roads = false, spec = MapSpec(
                routes = listOf(r(0, 2), r(0, 13), r(10, 0), r(10, 15)),
                rocks = listOf(rect(5, 6, 6, 9), rect(14, 2, 15, 4), rect(14, 11, 15, 13)), water = listOf(rect(17, 7, 18, 8)), trees = 12,
            ),
            waveCount = 15, roster = roster("raider" to (1 to 1f), "outrider" to (2 to 0.3f), "brute" to (2 to 0.35f), "shieldbearer" to (3 to 0.3f), "harpy" to (4 to 0.3f), "shaman" to (5 to 0.25f), "ram" to (7 to 0.25f)),
            boss = "warlord",
            startGold = 620, keepHp = 25, towers = withCatapult + "frost" + "brazier" + "repeater" + "banner",
            abilities = listOf(Ability.VOLLEY, Ability.TAR, Ability.REINFORCE),
            twoStarLeaks = 8, hpScale = 1.4f, baseThreat = 12f, threatGrowth = 4.2f, theme = LevelTheme.EMBER,
            tip = "Everything you've learned, all at once. Good luck, Commander.",
        ),
    )

    private val byId = all.associateBy { it.id }
    fun get(id: String): LevelDef? = byId[id]
    fun next(id: String): LevelDef? = all.getOrNull(all.indexOfFirst { it.id == id } + 1)
    fun chapter(n: Int) = all.filter { it.chapter == n }

    /** Stars needed to open each chapter (chapter 1 is always open). */
    fun chapterStarGate(chapter: Int) = when (chapter) { 1 -> 0; 2 -> 12; else -> 27 }
}

// ---------------------------------------------------------------------------------- daily challenge

/** The same level and rules for everyone on a given (UTC) day. */
object DailyChallenge {
    fun forDay(epochDay: Long): BattleSetup {
        val rng = Rng(epochDay * 2654435761L + 99)
        val level = Levels.all[rng.nextInt(Levels.all.size)]
        val pool = BattleModifier.entries.filter { m ->
            !(m == BattleModifier.NO_WALLS && level.roads) // walls are already off on road levels
        }.toMutableList()
        val mods = LinkedHashSet<BattleModifier>()
        while (mods.size < 2) mods += pool.removeAt(rng.nextInt(pool.size))
        if (BattleModifier.NO_ABILITIES in mods) mods -= BattleModifier.STORM
        return BattleSetup(level, LevelMode.DAILY, mods)
    }

    fun boardId(epochDay: Long) = "daily-$epochDay"
}
