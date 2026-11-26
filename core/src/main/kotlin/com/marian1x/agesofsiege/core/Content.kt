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
)

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
            upgradeCosts = listOf(Cost(60, 30, 0), Cost(110, 40, 25)),
        ),
        TowerDef(
            id = "ballista", name = "Ballista",
            description = "Heavy bolts that punch through armor.",
            cost = Cost(gold = 90, wood = 40, stone = 10),
            damage = 40f, range = 4.3f, fireRate = 0.5f, damageType = DamageType.ANTI_ARMOR,
            projectileSpeed = 15f,
            upgradeCosts = listOf(Cost(90, 40, 20), Cost(160, 50, 50)),
        ),
        TowerDef(
            id = "catapult", name = "Catapult",
            description = "Slow boulders that crush whole groups.",
            cost = Cost(gold = 120, wood = 30, stone = 40),
            damage = 22f, range = 5.2f, fireRate = 0.33f, damageType = DamageType.EXPLOSIVE,
            splash = 1.35f, projectileSpeed = 6.5f, homing = false,
            upgradeCosts = listOf(Cost(120, 30, 50), Cost(200, 40, 90)),
        ),
        TowerDef(
            id = "wall", name = "Stone Wall",
            description = "Blocks the path. Force raiders to take the long way.",
            cost = Cost(stone = 6), isWall = true,
        ),
    )

    val enemies: List<EnemyDef> = listOf(
        EnemyDef("raider", "Raider", hp = 36f, speed = 1.45f, armor = Armor.LIGHT, bounty = 3, keepDamage = 1, scale = 1f),
        EnemyDef("wolf", "Wolf Rider", hp = 50f, speed = 2.5f, armor = Armor.LIGHT, bounty = 5, keepDamage = 2, scale = 1.1f),
        EnemyDef("brute", "Axe Brute", hp = 130f, speed = 0.95f, armor = Armor.HEAVY, bounty = 7, keepDamage = 3, scale = 1.35f),
        EnemyDef("warlord", "Marauder Warlord", hp = 800f, speed = 0.75f, armor = Armor.HEAVY, bounty = 60, keepDamage = 10, scale = 1.9f),
    )

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
    )

    /** The Keep itself produces a little of everything. */
    val keepProduction = Cost(gold = 8, wood = 4, stone = 2)

    const val KEEP_MAX_HP = 30
    const val VOLLEY_COOLDOWN = 20f
    const val VOLLEY_RADIUS = 1.8f
    const val VOLLEY_DELAY = 0.7f
    const val SELL_REFUND = 0.6f
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
        if (wave >= 4) list += "wolf" to (wave - 2)
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
            val gap = when (id) { "wolf" -> 0.6f; "brute" -> 1.3f; "warlord" -> 3f; else -> 0.8f }
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
