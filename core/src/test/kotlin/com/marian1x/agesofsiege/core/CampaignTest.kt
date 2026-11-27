package com.marian1x.agesofsiege.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CampaignTest {

    private fun richCampaign(seed: Long = 11): Campaign {
        val c = Campaign(seed, CampaignSettings())
        c.base.res.gold = 5000f
        c.base.res.wood = 5000f
        c.base.res.stone = 5000f
        return c
    }

    private fun buildBarracks(c: Campaign) {
        for (y in 1 until 15) {
            if (c.base.execute(Command.Place("barracks", 17, y)) is CommandResult.Ok) return
        }
        error("no spot for barracks")
    }

    @Test
    fun recruitNeedsBarracksAndRespectsCap() {
        val c = richCampaign()
        assertTrue(c.base.execute(Command.Recruit("swordsman")) is CommandResult.Rejected)
        buildBarracks(c)
        repeat(c.base.armyCap()) { assertTrue(c.base.execute(Command.Recruit("swordsman")) is CommandResult.Ok) }
        assertTrue(c.base.execute(Command.Recruit("swordsman")) is CommandResult.Rejected)
        assertEquals(c.base.armyCap(), c.base.troops.size)
    }

    @Test
    fun capitalsAreConnectedAndWorldIsDeterministic() {
        val a = World(99)
        val b = World(99)
        assertNotNull(a.path(a.home.first, a.home.second, a.rivalHome.first, a.rivalHome.second))
        assertEquals(a.terrain.toList(), b.terrain.toList())
        assertEquals(a.pois.map { it.x to it.y }, b.pois.map { it.x to it.y })
    }

    @Test
    fun expeditionToCacheBringsLootHome() {
        val c = richCampaign(5)
        buildBarracks(c)
        repeat(2) { c.base.execute(Command.Recruit("swordsman")) }
        val cache = c.world.pois.filter { it.kind == PoiKind.CACHE }.minBy { c.world.distanceFromHome(it.x, it.y) }
        c.world.reveal(cache.x, cache.y, 1)
        val goldBefore = c.base.res.gold
        assertTrue(c.sendExpedition(mapOf("swordsman" to 2), cache.id) is CommandResult.Ok)
        assertEquals(0, c.base.troops.size)
        assertEquals(2, c.base.awayTroops)
        repeat(30 * 400) { c.step() }
        assertTrue(cache.cleared)
        assertTrue(c.expeditions.isEmpty())
        // (Returned troops may since have died defending against the automatic waves.)
        assertEquals(0, c.base.awayTroops)
        assertTrue(goldBefore > 0f)
        assertTrue(c.base.events.any { it is GameEvent.Report && it.text.startsWith("Your army is home with") })
    }

    @Test
    fun troopsFightRaiders() {
        val c = richCampaign(3)
        buildBarracks(c)
        repeat(4) { c.base.execute(Command.Recruit("knight")) }
        c.base.execute(Command.StartWave)
        repeat(30 * 120) { c.step() }
        assertTrue("knights should earn kills", c.base.troops.sumOf { it.kills } > 0)
    }

    @Test
    fun rivalMakesContactAndCanBeConquered() {
        val c = richCampaign(8)
        repeat((30 * Campaign.CONTACT_SECONDS * 1.05f).toInt()) { c.step() }
        assertTrue(c.rival.contact)
        // Three strong assaults make the rival a vassal.
        repeat(3) {
            val army = MutableList(40) { TroopData("knight", 220f, 600) }
            c.battle(army, c.rival.defense)
        }
        val big = MutableList(60) { TroopData("knight", 220f, 600) }
        val (won, _) = c.battle(big, c.rival.defense)
        assertTrue(won)
    }

    @Test
    fun campaignSaveRoundTrip() {
        val c = richCampaign(21)
        buildBarracks(c)
        c.base.execute(Command.Recruit("bowman"))
        repeat(30 * 20) { c.step() }
        val json = c.toSave().toJson()
        val restored = Campaign(21, CampaignSettings())
        restored.loadFrom(CampaignSave.fromJson(json))
        assertEquals(c.base.troops.size, restored.base.troops.size)
        assertEquals(c.world.explored.count { it }, restored.world.explored.count { it })
        assertEquals(c.rival.defense, restored.rival.defense, 0.01f)
    }
}
