package com.marian1x.agesofsiege.core

import kotlinx.serialization.Serializable

/**
 * All game content (towers, enemies, buildings, waves) lives here as plain data,
 * so balancing means editing numbers in this file only.
 */

@Serializable
data class Cost(val gold: Int = 0, val wood: Int = 0, val stone: Int = 0) {
    operator fun plus(o: Cost) = Cost(gold + o.gold, wood + o.wood, stone + o.stone)
    operator fun times(f: Float) = Cost((gold * f).toInt(), (wood * f).toInt(), (stone * f).toInt())
}

enum class DamageType { PIERCE, ANTI_ARMOR, EXPLOSIVE }
enum class Armor { LIGHT, HEAVY }

@Serializable
enum class TowerSpecialization(
    val label: String,
    val benefit: String,
    val damageMultiplier: Float = 1f,
    val rangeBonus: Float = 0f,
    val fireRateMultiplier: Float = 1f,
) {
    POWER("Power", "+25% damage per hit", damageMultiplier = 1.25f),
    REACH("Reach", "+0.9 tiles of range", rangeBonus = 0.9f),
    RAPID("Rapid Fire", "+25% attack speed", fireRateMultiplier = 1.25f),
}

data class TowerDef(
    val id: String,
    val name: String,
    val description: String,
    val cost: Cost,
    val damage: Float = 0f,
    val range: Float = 0f,
    /** Shots per second. */
    val fireRate: Float = 0f,
    val damageType: DamageType = DamageType.PIERCE,
    val splash: Float = 0f,
    val projectileSpeed: Float = 10f,
    /** Homing projectiles follow their target; others fly to a fixed point (artillery). */
    val homing: Boolean = true,
    val upgradeCosts: List<Cost> = emptyList(),
    val isWall: Boolean = false,
    val chillSeconds: Float = 0f,
)

data class EnemyDef(
    val id: String,
    val name: String,
    val hp: Float,
    /** Tiles per second. */
    val speed: Float,
    val armor: Armor,
    val bounty: Int,
    val keepDamage: Int,
    val scale: Float,
    /** Melee damage per hit against troops. */
    val damage: Float = 6f,
)

/** Trainable troops (recruited at the Barracks). */
data class UnitDef(
    val id: String,
    val name: String,
    val description: String,
    val hp: Float,
    val damage: Float,
    val range: Float,
    /** Attacks per second. */
    val attackRate: Float,
    /** Tiles per second. */
    val speed: Float,
    val cost: Cost,
    val damageType: DamageType,
    /** Strength used by auto-resolved battles on the world map. */
    val power: Float,
)

enum class Difficulty(val label: String, val rivalGrowth: Float, val raidHp: Float) {
    EASY("Easy", 0.7f, 0.85f), NORMAL("Normal", 1f, 1f), HARD("Hard", 1.35f, 1.15f)
}

enum class Pace(val label: String, val mult: Float) {
    RELAXED("Relaxed", 0.6f), STANDARD("Standard", 1f), FAST("Fast", 1.8f)
}

data class BuildingDef(
    val id: String,
    val name: String,
    val description: String,
    val cost: Cost,
    /** Production per minute at level 1. */
    val produces: Cost,
    val upgradeCosts: List<Cost>,
)

data class SpawnEntry(val time: Float, val enemyId: String, val gate: Int)

object Content {
    val towers: List<TowerDef> = listOf(
        TowerDef(
            id = "archer", name = "Archer Tower",
            description = "Fast arrows. Great against light raiders.",
            cost = Cost(gold = 50, wood = 25),
            damage = 8f, range = 3.3f, fireRate = 1.3f, damageType = DamageType.PIERCE,
            projectileSpeed = 12f,
            upgradeCosts = listOf(Cost(60, 30, 0), Cost(110, 40, 25), Cost(175, 65, 45), Cost(260, 90, 70)),
        ),
        TowerDef(
            id = "ballista", name = "Ballista",
            description = "Heavy bolts that punch through armor.",
            cost = Cost(gold = 90, wood = 40, stone = 10),
            damage = 40f, range = 4.3f, fireRate = 0.5f, damageType = DamageType.ANTI_ARMOR,
            projectileSpeed = 15f,
            upgradeCosts = listOf(Cost(90, 40, 20), Cost(160, 50, 50), Cost(235, 80, 80), Cost(330, 110, 120)),
        ),
        TowerDef(
            id = "catapult", name = "Catapult",
            description = "Slow boulders that crush whole groups.",
            cost = Cost(gold = 120, wood = 30, stone = 40),
            damage = 22f, range = 5.2f, fireRate = 0.33f, damageType = DamageType.EXPLOSIVE,
            splash = 1.35f, projectileSpeed = 6.5f, homing = false,
            upgradeCosts = listOf(Cost(120, 30, 50), Cost(200, 40, 90), Cost(275, 60, 130), Cost(365, 90, 180)),
        ),
        TowerDef(
            id = "frost", name = "Frost Spire",
            description = "Chills nearby raiders, slowing their movement by 40%.",
            cost = Cost(gold = 75, wood = 30, stone = 20),
            damage = 7f, range = 3.5f, fireRate = 0.8f, damageType = DamageType.EXPLOSIVE,
            splash = 0.8f, projectileSpeed = 9f, chillSeconds = 1.9f,
            upgradeCosts = listOf(Cost(75, 35, 25), Cost(135, 50, 45), Cost(205, 75, 70), Cost(300, 100, 105)),
        ),
        TowerDef(
            id = "brazier", name = "Flame Brazier",
            description = "Short-range fire splashes across groups of raiders.",
            cost = Cost(gold = 105, wood = 25, stone = 30),
            damage = 15f, range = 2.65f, fireRate = 0.9f, damageType = DamageType.EXPLOSIVE,
            splash = 1.05f, projectileSpeed = 10f,
            upgradeCosts = listOf(Cost(100, 35, 35), Cost(165, 50, 60), Cost(235, 65, 95), Cost(330, 90, 135)),
        ),
        TowerDef(
            id = "repeater", name = "Repeater",
            description = "A rapid volley of steel bolts against armored enemies.",
            cost = Cost(gold = 115, wood = 55, stone = 15),
            damage = 13f, range = 3.15f, fireRate = 1.55f, damageType = DamageType.ANTI_ARMOR,
            projectileSpeed = 16f,
            upgradeCosts = listOf(Cost(105, 50, 20), Cost(170, 70, 40), Cost(245, 95, 65), Cost(340, 125, 95)),
        ),
        TowerDef(
            id = "wall", name = "Stone Wall",
            description = "Blocks the path. Force raiders to take the long way.",
            cost = Cost(stone = 6), isWall = true,
        ),
    )

    val enemies: List<EnemyDef> = listOf(
        EnemyDef("raider", "Raider", hp = 36f, speed = 1.45f, armor = Armor.LIGHT, bounty = 3, keepDamage = 1, scale = 1f, damage = 4f),
        EnemyDef("outrider", "Outrider", hp = 50f, speed = 2.5f, armor = Armor.LIGHT, bounty = 5, keepDamage = 2, scale = 1.1f, damage = 5f),
        EnemyDef("brute", "Axe Brute", hp = 130f, speed = 0.95f, armor = Armor.HEAVY, bounty = 7, keepDamage = 3, scale = 1.35f, damage = 14f),
        EnemyDef("warlord", "Marauder Warlord", hp = 800f, speed = 0.75f, armor = Armor.HEAVY, bounty = 60, keepDamage = 10, scale = 1.9f, damage = 30f),
        EnemyDef("rival", "Rival Soldier", hp = 120f, speed = 1.2f, armor = Armor.HEAVY, bounty = 10, keepDamage = 3, scale = 1.1f, damage = 11f),
    )

    val units: List<UnitDef> = listOf(
        UnitDef(
            "swordsman", "Swordsman", "Sturdy melee infantry that holds raiders back.",
            hp = 130f, damage = 10f, range = 0.65f, attackRate = 1f, speed = 1.6f,
            cost = Cost(gold = 35, wood = 10), damageType = DamageType.PIERCE, power = 10f,
        ),
        UnitDef(
            "bowman", "Bowman", "Shoots from range; fragile up close.",
            hp = 70f, damage = 8f, range = 3f, attackRate = 0.9f, speed = 1.5f,
            cost = Cost(gold = 40, wood = 20), damageType = DamageType.PIERCE, power = 9f,
        ),
        UnitDef(
            "knight", "Knight", "Heavy cavalry. Crushes armored foes.",
            hp = 260f, damage = 20f, range = 0.75f, attackRate = 0.8f, speed = 2.1f,
            cost = Cost(gold = 100, stone = 30), damageType = DamageType.ANTI_ARMOR, power = 26f,
        ),
    )

    const val ARMY_BASE_CAP = 4
    const val ARMY_CAP_PER_BARRACKS_LEVEL = 4
    const val TROOP_AGGRO_RANGE = 3.6f
    private val unitMap = units.associateBy { it.id }
    fun unit(id: String) = unitMap.getValue(id)
    fun isUnit(id: String) = id in unitMap

    /** World-map strength of one troop, including veterancy and wounds. */
    fun troopPower(t: TroopData): Float {
        val d = unit(t.defId)
        return d.power * rankDamageMult(rankFor(t.xp)) * (0.35f + 0.65f * (t.hp / d.hp).coerceIn(0f, 1f))
    }

    val buildings: List<BuildingDef> = listOf(
        BuildingDef(
            "house", "House", "Villagers pay taxes in gold.",
            cost = Cost(gold = 40, wood = 30), produces = Cost(gold = 14),
            upgradeCosts = listOf(Cost(60, 40, 10), Cost(100, 60, 30)),
        ),
        BuildingDef(
            "lumber", "Lumber Mill", "Produces wood.",
            cost = Cost(gold = 50, wood = 10), produces = Cost(wood = 18),
            upgradeCosts = listOf(Cost(70, 20, 10), Cost(120, 30, 30)),
        ),
        BuildingDef(
            "quarry", "Quarry", "Produces stone.",
            cost = Cost(gold = 60, wood = 30), produces = Cost(stone = 12),
            upgradeCosts = listOf(Cost(80, 40, 0), Cost(130, 60, 20)),
        ),
        BuildingDef(
            "barracks", "Barracks", "Recruit troops. Each level adds room for 4 more soldiers.",
            cost = Cost(gold = 80, wood = 60, stone = 20), produces = Cost(),
            upgradeCosts = listOf(Cost(120, 60, 40), Cost(200, 80, 80)),
        ),
    )

    /** The Keep itself produces a little of everything. */
    val keepProduction = Cost(gold = 8, wood = 4, stone = 2)

    const val KEEP_MAX_HP = 30
    const val VOLLEY_COOLDOWN = 20f
    const val VOLLEY_RADIUS = 1.8f
    const val VOLLEY_DELAY = 0.7f
    const val SELL_REFUND = 0.6f
    const val CHILLED_SPEED_MULTIPLIER = 0.6f
    const val SPECIALIZATION_LEVEL = 3
    val specializationCost = Cost(gold = 75, wood = 25, stone = 20)
    val startResources = Cost(gold = 170, wood = 90, stone = 40)

    private val towerMap = towers.associateBy { it.id }
    private val enemyMap = enemies.associateBy { it.id }
    private val buildingMap = buildings.associateBy { it.id }
    fun tower(id: String) = towerMap.getValue(id)
    fun enemy(id: String) = enemyMap.getValue(id)
    fun building(id: String) = buildingMap.getValue(id)
    fun isTower(id: String) = id in towerMap
    fun isBuilding(id: String) = id in buildingMap

    fun damageMultiplier(type: DamageType, armor: Armor): Float = when (type) {
        DamageType.PIERCE -> if (armor == Armor.LIGHT) 1f else 0.55f
        DamageType.ANTI_ARMOR -> if (armor == Armor.LIGHT) 0.75f else 1.7f
        DamageType.EXPLOSIVE -> if (armor == Armor.LIGHT) 1f else 0.85f
    }

    /** Veterancy: XP needed for ranks I..V. */
    val rankXp = intArrayOf(0, 40, 120, 280, 560)
    fun rankFor(xp: Int): Int {
        var r = 1
        for (i in rankXp.indices) if (xp >= rankXp[i]) r = i + 1
        return r
    }
    fun rankDamageMult(rank: Int) = 1f + 0.1f * (rank - 1)
    fun rankRangeBonus(rank: Int) = 0.12f * (rank - 1)
    fun levelDamageMult(level: Int) = 1f + 0.45f * (level - 1)
    fun levelRangeBonus(level: Int) = 0.3f * (level - 1)
    fun levelRateMult(level: Int) = 1f + 0.12f * (level - 1)
    fun levelProductionMult(level: Int) = 1f + 0.6f * (level - 1)

    fun waveHpScale(wave: Int): Float {
        val n = (wave - 1).toFloat()
        return 1f + 0.14f * n + 0.012f * n * n
    }

    fun waveReward(wave: Int) = 25 + 6 * wave

    /** Number of entry gates open for a wave. */
    fun gatesFor(wave: Int) = if (wave >= 6) 3 else 2

    /** Deterministic wave composition. */
    fun waveComposition(wave: Int): List<Pair<String, Int>> {
        val list = mutableListOf<Pair<String, Int>>()
        list += "raider" to (5 + 2 * wave)
        if (wave >= 4) list += "outrider" to (wave - 2)
        if (wave >= 3) list += "brute" to ((wave - 1) / 2)
        if (wave % 5 == 0) list += "warlord" to (wave / 5)
        return list
    }

    fun waveSpawns(wave: Int): List<SpawnEntry> {
        val gates = gatesFor(wave)
        val out = mutableListOf<SpawnEntry>()
        var t = 0f
        var g = 0
        for ((id, count) in waveComposition(wave)) {
            val gap = when (id) { "outrider" -> 0.6f; "brute" -> 1.3f; "warlord" -> 3f; else -> 0.8f }
            repeat(count) {
                out += SpawnEntry(t, id, g % gates)
                g++
                t += gap
            }
            t += 1.5f
        }
        return out
    }
}
