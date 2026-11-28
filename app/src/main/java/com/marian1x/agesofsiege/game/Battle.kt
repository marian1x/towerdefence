package com.marian1x.agesofsiege.game

import com.marian1x.agesofsiege.core.BaseGame
import com.marian1x.agesofsiege.core.BattleSetup
import com.marian1x.agesofsiege.core.LevelMode
import com.marian1x.agesofsiege.core.LevelTheme
import com.marian1x.agesofsiege.core.Outcome
import com.marian1x.agesofsiege.core.RecordOutcome
import com.marian1x.agesofsiege.engine.Renderer3D

/** A campaign battle in progress: a level, the Daily Challenge, Endless or a Race against a friend. */
class LevelBattle(val setup: BattleSetup, val game: BaseGame, val race: RaceInfo? = null) {
    /** Seconds left of the opening camera flight (the countdown waits). */
    var intro = if (race == null) 3.4f else 2f
    /** Real seconds of slow motion left (last kill, boss death). */
    var slowMo = 0f
    /** Real seconds the simulation freezes for a heavy hit. */
    var hitStop = 0f
    var finished = false
    val level get() = setup.level
    val mode get() = setup.mode
}

/** Shown on the results screen. */
data class BattleSummary(
    val levelId: String,
    val levelName: String,
    val mode: LevelMode,
    val won: Boolean,
    val stars: Int,
    val leaks: Int,
    val twoStarLeaks: Int,
    val score: Int,
    val wave: Int,
    val totalWaves: Int,
    val kills: Int,
    val record: RecordOutcome?,
    val hasNext: Boolean,
    /** Online board this score was sent to (null = offline). */
    val board: String?,
    val race: RaceResult? = null,
)

data class RaceResult(val youWon: Boolean, val opponent: String, val text: String)

/** The other player in a Race: what they report every second. */
class RaceInfo(val opponent: String) {
    var wave = 1
    var keepHp = 0
    var keepMax = 1
    var leaks = 0
    var score = 0
    var outcome: Outcome? = null
    /** Raider squads you can send (earned by kills). */
    var tokens = 0
    var killsBanked = 0
    var sent = 0
    var received = 0
}

object Looks {
    private fun f(vararg v: Float) = v

    val MEADOW = Renderer3D.Look.DEFAULT
    val AUTUMN = Renderer3D.Look(f(1f, 0.82f, 0.62f), 115_000f, f(0.6f, -0.8f, -0.4f), 16_000f, 1.15f, 1.08f, 0.18f, 0.02f, null)
    val FROST = Renderer3D.Look(f(0.86f, 0.93f, 1f), 105_000f, f(0.35f, -0.9f, -0.6f), 22_000f, 0.62f, 1.04f, -0.35f, 0.0f,
        f(0.82f, 0.88f, 0.96f), fogDensity = 0.035f, exposure = 15.6f)
    val NIGHT = Renderer3D.Look(f(0.55f, 0.66f, 1f), 26_000f, f(0.3f, -1f, 0.5f), 5_500f, 0.8f, 1.12f, -0.3f, 0.05f,
        f(0.06f, 0.08f, 0.16f), fogDensity = 0.03f, bloom = 0.12f, exposure = 14.6f)
    val MARSH = Renderer3D.Look(f(0.95f, 0.98f, 0.8f), 80_000f, f(0.5f, -0.85f, -0.45f), 14_000f, 0.85f, 1.05f, 0.05f, -0.12f,
        f(0.55f, 0.6f, 0.5f), fogDensity = 0.06f, exposure = 15.4f)
    val EMBER = Renderer3D.Look(f(1f, 0.6f, 0.38f), 95_000f, f(0.8f, -0.45f, -0.35f), 11_000f, 1.2f, 1.14f, 0.35f, 0.03f,
        f(0.5f, 0.3f, 0.22f), fogDensity = 0.025f, bloom = 0.1f, exposure = 15.4f)

    fun of(theme: LevelTheme) = when (theme) {
        LevelTheme.MEADOW -> MEADOW
        LevelTheme.AUTUMN -> AUTUMN
        LevelTheme.FROST -> FROST
        LevelTheme.NIGHT -> NIGHT
        LevelTheme.MARSH -> MARSH
        LevelTheme.EMBER -> EMBER
    }
}
