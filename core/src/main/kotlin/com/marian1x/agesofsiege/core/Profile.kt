package com.marian1x.agesofsiege.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.max

/*
 * The player's campaign progress, kept apart from the realm save: stars, records, Glory perks,
 * achievements, daily quests and the Daily Challenge streak.
 */

@Serializable
data class LevelRecord(
    var stars: Int = 0,
    var heroic: Boolean = false,
    var iron: Boolean = false,
    var bestScore: Int = 0,
    var endlessBest: Int = 0,
    var plays: Int = 0,
    var wins: Int = 0,
)

@Serializable
data class QuestState(val id: String, val target: Int, var progress: Int = 0, var claimed: Boolean = false) {
    val done get() = progress >= target
}

/** A permanent campaign upgrade bought with Glory (earned from stars, achievements and quests). */
class PerkDef(val id: String, val name: String, val description: String, val maxLevel: Int, val cost: Int)

object Perks {
    val all = listOf(
        PerkDef("war_chest", "War Chest", "+8% starting gold per level", 5, 2),
        PerkDef("stonework", "Stonework", "+4 Keep health per level", 3, 3),
        PerkDef("fletchers", "Fletchers' Guild", "Archers, Ballistas and Repeaters +6% damage per level", 3, 3),
        PerkDef("siege", "Siege Engineers", "Catapults and Braziers +6% damage per level", 3, 3),
        PerkDef("frostcraft", "Frostcraft", "Frost Spire chill lasts 0.35 s longer per level", 2, 2),
        PerkDef("tactics", "Battle Tactics", "Abilities recharge 10% faster per level", 3, 3),
        PerkDef("plunder", "Plunder", "+8% gold from kills per level", 3, 3),
        PerkDef("masons", "Master Masons", "Tower upgrades 6% cheaper per level", 3, 3),
    )
    fun get(id: String) = all.first { it.id == id }
}

class AchievementDef(val id: String, val name: String, val description: String, val glory: Int, val check: (PlayerProfile) -> Boolean)

class QuestDef(val id: String, val text: (Int) -> String, val targets: List<Int>)

/** What one finished battle reports to the profile. */
data class BattleResult(
    val levelId: String,
    val mode: LevelMode,
    val won: Boolean,
    val stars: Int,
    val score: Int,
    val wave: Int,
    val kills: Int,
    val shatters: Int,
    val bossKills: Int,
    val abilitiesUsed: Int,
    val towersBuilt: Int,
    val day: Long,
)

/** What changed after recording a battle (shown on the results screen). */
data class RecordOutcome(
    val newStars: Int,
    val gloryGained: Int,
    val achievements: List<AchievementDef>,
    val quests: List<QuestState>,
    val newBest: Boolean,
    val firstWin: Boolean,
)

@Serializable
class PlayerProfile(
    val version: Int = 1,
    var name: String = "",
    val levels: MutableMap<String, LevelRecord> = mutableMapOf(),
    val perks: MutableMap<String, Int> = mutableMapOf(),
    val achievements: MutableSet<String> = mutableSetOf(),
    var bonusGlory: Int = 0,
    var totalKills: Int = 0,
    var totalShatters: Int = 0,
    var bossKills: Int = 0,
    var battlesWon: Int = 0,
    var heroicWins: Int = 0,
    var ironWins: Int = 0,
    var questDay: Long = -1,
    val quests: MutableList<QuestState> = mutableListOf(),
    /** Best Daily Challenge score per day. */
    val daily: MutableMap<Long, Int> = mutableMapOf(),
    var dailyStreak: Int = 0,
    var lastDailyDay: Long = -1,
    /** Kills per tower type over all battles (unlocks gilded tower trims). */
    val towerKills: MutableMap<String, Int> = mutableMapOf(),
) {
    fun record(id: String) = levels.getOrPut(id) { LevelRecord() }
    fun stars(id: String) = levels[id]?.stars ?: 0
    val totalStars get() = levels.values.sumOf { it.stars }

    val gloryEarned: Int
        get() = levels.values.sumOf { it.stars + (if (it.heroic) 2 else 0) + (if (it.iron) 3 else 0) } + bonusGlory
    val glorySpent: Int get() = perks.entries.sumOf { (id, lvl) -> Perks.all.firstOrNull { it.id == id }?.cost?.times(lvl) ?: 0 }
    val glory: Int get() = gloryEarned - glorySpent

    fun perk(id: String) = perks[id] ?: 0

    fun buyPerk(id: String): String? {
        val def = Perks.all.firstOrNull { it.id == id } ?: return "Unknown perk"
        val lvl = perk(id)
        if (lvl >= def.maxLevel) return "Already at max level"
        if (glory < def.cost) return "Not enough Glory"
        perks[id] = lvl + 1
        return null
    }

    /** Gives all Glory back so it can be spent differently. */
    fun resetPerks() = perks.clear()

    // ------------------------------------------------------------ unlocks

    fun isUnlocked(level: LevelDef): Boolean {
        val i = Levels.all.indexOf(level)
        if (i <= 0) return true
        if (totalStars < Levels.chapterStarGate(level.chapter)) return false
        return stars(Levels.all[i - 1].id) >= 1
    }

    fun chapterOpen(chapter: Int) = totalStars >= Levels.chapterStarGate(chapter)

    fun modeUnlocked(level: LevelDef, mode: LevelMode): Boolean = when (mode) {
        LevelMode.NORMAL -> isUnlocked(level)
        LevelMode.HEROIC, LevelMode.IRON -> stars(level.id) >= 3
        LevelMode.ENDLESS -> stars(level.id) >= 1
        LevelMode.DAILY, LevelMode.RACE -> true
    }

    /** The first level that still needs winning (or the last one). */
    fun currentLevel(): LevelDef = Levels.all.firstOrNull { isUnlocked(it) && stars(it.id) == 0 } ?: Levels.all.last { isUnlocked(it) }

    /** Gilded trims for veteran tower types. */
    fun towerTier(defId: String): Int {
        val k = towerKills[defId] ?: 0
        return when {
            k >= 1500 -> 2
            k >= 400 -> 1
            else -> 0
        }
    }

    // ------------------------------------------------------------ quests

    /** Rolls today's three quests (same for everyone on that day). */
    fun refreshQuests(day: Long) {
        if (questDay == day) return
        questDay = day
        quests.clear()
        val rng = Rng(day * 40503L + 7)
        val pool = QuestBook.all.toMutableList()
        repeat(3) {
            val q = pool.removeAt(rng.nextInt(pool.size))
            quests += QuestState(q.id, q.targets[rng.nextInt(q.targets.size)])
        }
    }

    fun questText(q: QuestState) = QuestBook.all.firstOrNull { it.id == q.id }?.text?.invoke(q.target) ?: q.id

    /** Collects a finished quest's reward (1 Glory, +1 for finishing all three). */
    fun claimQuest(q: QuestState): Int {
        if (!q.done || q.claimed) return 0
        q.claimed = true
        var g = 1
        if (quests.all { it.claimed }) g += 1
        bonusGlory += g
        return g
    }

    // ------------------------------------------------------------ results

    fun recordBattle(r: BattleResult, towerKillsThisBattle: Map<String, Int> = emptyMap()): RecordOutcome {
        refreshQuests(r.day)
        val gloryBefore = gloryEarned
        val rec = record(r.levelId)
        rec.plays++
        val firstWin = r.won && rec.wins == 0 && r.mode == LevelMode.NORMAL
        var newStars = 0
        var newBest = false
        when (r.mode) {
            LevelMode.NORMAL -> if (r.won) {
                newStars = max(0, r.stars - rec.stars)
                rec.stars = max(rec.stars, r.stars)
            }
            LevelMode.HEROIC -> if (r.won) { rec.heroic = true; heroicWins++ }
            LevelMode.IRON -> if (r.won) { rec.iron = true; ironWins++ }
            LevelMode.ENDLESS -> if (r.wave - 1 > rec.endlessBest) { rec.endlessBest = r.wave - 1; newBest = true }
            LevelMode.DAILY -> {
                val prev = daily[r.day]
                if (prev == null || r.score > prev) {
                    daily[r.day] = r.score
                    newBest = true
                }
                if (lastDailyDay != r.day) {
                    dailyStreak = if (lastDailyDay == r.day - 1) dailyStreak + 1 else 1
                    lastDailyDay = r.day
                }
            }
            LevelMode.RACE -> Unit
        }
        if (r.won) {
            rec.wins++
            battlesWon++
        }
        if (r.mode == LevelMode.NORMAL && r.score > rec.bestScore) {
            if (rec.bestScore > 0) newBest = true
            rec.bestScore = r.score
        }
        totalKills += r.kills
        totalShatters += r.shatters
        bossKills += r.bossKills
        for ((id, n) in towerKillsThisBattle) towerKills[id] = (towerKills[id] ?: 0) + n

        // Quest progress.
        val finished = ArrayList<QuestState>()
        for (q in quests) {
            if (q.done) continue
            val add = when (q.id) {
                "kills" -> r.kills
                "wins" -> if (r.won) 1 else 0
                "stars" -> if (r.won && r.mode == LevelMode.NORMAL) r.stars else 0
                "shatters" -> r.shatters
                "abilities" -> r.abilitiesUsed
                "boss" -> r.bossKills
                "daily" -> if (r.mode == LevelMode.DAILY) 1 else 0
                "towers" -> r.towersBuilt
                "perfect" -> if (r.won && r.stars == 3) 1 else 0
                else -> 0
            }
            q.progress = minOf(q.target, q.progress + add)
            if (q.done) finished += q
        }

        val unlocked = Achievements.all.filter { it.id !in achievements && it.check(this) }
        for (a in unlocked) {
            achievements += a.id
            bonusGlory += a.glory
        }
        return RecordOutcome(newStars, gloryEarned - gloryBefore, unlocked, finished, newBest, firstWin)
    }

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun fromJson(text: String): PlayerProfile = json.decodeFromString(serializer(), text)
    }
}

object QuestBook {
    val all = listOf(
        QuestDef("kills", { "Defeat $it raiders" }, listOf(120, 200, 300)),
        QuestDef("wins", { "Win $it battles" }, listOf(2, 3)),
        QuestDef("stars", { "Earn $it stars" }, listOf(4, 6)),
        QuestDef("shatters", { "Shatter $it frozen raiders" }, listOf(5, 10)),
        QuestDef("abilities", { "Use abilities $it times" }, listOf(8, 15)),
        QuestDef("boss", { "Defeat a Warlord" }, listOf(1)),
        QuestDef("daily", { "Play the Daily Challenge" }, listOf(1)),
        QuestDef("towers", { "Build $it towers" }, listOf(15, 25)),
        QuestDef("perfect", { "Win $it battles with 3 stars" }, listOf(1, 2)),
    )
}

object Achievements {
    private fun beat(id: String): (PlayerProfile) -> Boolean = { (it.levels[id]?.stars ?: 0) >= 1 }

    val all = listOf(
        AchievementDef("first_win", "First Victory", "Win your first battle", 1) { it.battlesWon >= 1 },
        AchievementDef("chapter1", "Greenvale Freed", "Finish chapter 1", 2, beat("1-6")),
        AchievementDef("chapter2", "Pass Cleared", "Finish chapter 2", 2, beat("2-6")),
        AchievementDef("chapter3", "Realm Saved", "Defeat the Marauder King", 3, beat("3-6")),
        AchievementDef("flawless5", "Flawless", "Get 3 stars on 5 levels", 2) { p -> p.levels.values.count { it.stars >= 3 } >= 5 },
        AchievementDef("flawless_all", "Master Commander", "Get 3 stars on every level", 5) { p -> Levels.all.all { p.stars(it.id) >= 3 } },
        AchievementDef("heroic", "Heroic Deed", "Win a Heroic battle", 1) { it.heroicWins >= 1 },
        AchievementDef("iron", "Iron Will", "Win an Iron battle", 2) { it.ironWins >= 1 },
        AchievementDef("kills1000", "A Thousand Fallen", "Defeat 1,000 raiders", 2) { it.totalKills >= 1000 },
        AchievementDef("kills5000", "Marauders' Bane", "Defeat 5,000 raiders", 3) { it.totalKills >= 5000 },
        AchievementDef("shatter50", "Ice and Fire", "Shatter 50 frozen raiders", 2) { it.totalShatters >= 50 },
        AchievementDef("boss3", "Kingslayer", "Defeat 3 Warlords", 2) { it.bossKills >= 3 },
        AchievementDef("endless20", "Unbreakable", "Survive 20 waves in Endless", 2) { p -> p.levels.values.any { it.endlessBest >= 20 } },
        AchievementDef("daily3", "Daily Devotion", "Play the Daily Challenge 3 days in a row", 2) { it.dailyStreak >= 3 },
    )
}
