package com.marian1x.agesofsiege.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BaseGameTest {

    private fun runSeconds(g: BaseGame, seconds: Float) {
        repeat((seconds / BaseGame.DT).toInt()) { g.step() }
    }

    @Test
    fun allGatesReachTheKeep() {
        val g = BaseGame(42)
        for ((x, y) in g.gates) assertNotEquals(FlowField.INF, g.flow.dist[g.idx(x, y)])
    }

    @Test
    fun cannotCompletelyBlockThePath() {
        val g = BaseGame(7)
        g.res.stone = 10_000f
        // Try to wall off a full column in front of the Keep; at least one placement must be refused.
        var rejected = 0
        for (y in 0 until g.height) {
            val r = g.execute(Command.Place("wall", 18, y))
            if (r is CommandResult.Rejected) rejected++
        }
        assertTrue(rejected > 0)
        for ((x, y) in g.gates) assertNotEquals(FlowField.INF, g.flow.dist[g.idx(x, y)])
    }

    @Test
    fun undefendedKeepLosesRaidEventually() {
        val g = BaseGame(1)
        g.execute(Command.StartWave)
        var lost = false
        repeat(30 * 120) {
            g.step()
            if (g.drainEvents().any { it is GameEvent.RaidLost || it is GameEvent.KeepHit }) lost = true
        }
        assertTrue(lost)
    }

    @Test
    fun archersDefendFirstWaveAndGainXp() {
        val g = BaseGame(3)
        g.res.gold = 1000f
        g.res.wood = 1000f
        val spots = listOf(4 to 5, 4 to 10, 9 to 5, 9 to 10, 15 to 6, 15 to 9)
        for ((x, y) in spots) {
            val r = g.execute(Command.Place("archer", x, y))
            if (r is CommandResult.Rejected) g.execute(Command.Place("archer", x + 1, y))
        }
        g.execute(Command.StartWave)
        var cleared = false
        repeat(30 * 120) {
            g.step()
            if (g.drainEvents().any { it is GameEvent.WaveCleared }) cleared = true
        }
        assertTrue("wave 1 should be cleared", cleared)
        assertTrue(g.wave >= 2)
        assertTrue(g.structures.values.any { it.xp > 0 && it.kills > 0 })
    }

    @Test
    fun saveRoundTrip() {
        val g = BaseGame(99)
        g.execute(Command.Place("archer", 5, 5))
        g.execute(Command.Place("house", 6, 2))
        runSeconds(g, 5f)
        val json = g.toSave().toJson()
        val loaded = BaseGame(99)
        loaded.loadFrom(SaveData.fromJson(json))
        assertEquals(g.structures.size, loaded.structures.size)
        assertNotNull(loaded.structureAt(5, 5))
        assertEquals(g.res.gold, loaded.res.gold, 0.001f)
    }

    @Test
    fun simulationIsDeterministic() {
        fun play(): String {
            val g = BaseGame(5)
            g.execute(Command.Place("archer", 8, 6))
            g.execute(Command.Place("catapult", 12, 9))
            g.execute(Command.StartWave)
            runSeconds(g, 60f)
            return "${g.res.gold}|${g.enemies.size}|${g.structures.values.sumOf { it.xp }}|${g.keepHp}"
        }
        assertEquals(play(), play())
    }
}
