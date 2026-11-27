package com.marian1x.agesofsiege.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiplayerTest {

    private fun setup(mode: MatchMode): MatchSetup {
        val world = World(MatchRules.worldSeed(42L))
        val homes = MatchRules.homes(world, mode)
        return MatchSetup(
            matchId = 1L, seed = 42L, mode = mode, difficulty = Difficulty.NORMAL, pace = Pace.FAST,
            players = listOf(
                MpPlayer(0, "Host", homes[0].first, homes[0].second),
                MpPlayer(1, "Guest", homes[1].first, homes[1].second),
            ),
        )
    }

    private fun troops(n: Int) = List(n) { TroopData("knight", Content.unit("knight").hp, xp = 0) }

    @Test
    fun codecRoundTrip() {
        val s = setup(MatchMode.VERSUS)
        val msgs = listOf(
            NetMsg.Hello("Bob", playerToken = "guest-token", resumeMatchId = s.matchId),
            NetMsg.Ping,
            NetMsg.Delivery(7, NetMsg.Supplies(1, 0, Cost(50))),
            NetMsg.Ack(7),
            NetMsg.Welcome(s, 1, started = false),
            NetMsg.SendArmy(1, troops(2), targetPlayer = 0),
            NetMsg.AssaultResult(3, 5, 4, 1, Cost(10, 5, 2)),
            MatchRealm(s).snapshot(),
        )
        for (m in msgs) assertEquals(m.javaClass, NetCodec.decode(NetCodec.encode(m)).javaClass)
        val delivery = NetMsg.Delivery(12, NetMsg.AssaultResult(3, 5, 4, 1, Cost(10, 5, 2)))
        assertEquals(delivery, NetCodec.decode(NetCodec.encode(delivery)))
        val back = NetCodec.decode(NetCodec.encode(NetMsg.SendArmy(1, troops(2), targetPlayer = 0))) as NetMsg.SendArmy
        assertEquals(2, back.troops.size)
        assertEquals(0, back.targetPlayer)
    }

    @Test
    fun bothPhonesBuildTheSameRealm() {
        val s = setup(MatchMode.COOP)
        val host = MatchRealm(s)
        val a = Campaign(s.seed, CampaignSettings()).also { it.setupMultiplayer(s, 0) }
        val b = Campaign(s.seed, CampaignSettings()).also { it.setupMultiplayer(s, 1) }
        assertTrue(host.world.terrain.contentEquals(a.world.terrain))
        assertTrue(a.world.terrain.contentEquals(b.world.terrain))
        assertEquals(s.players[1].home, b.world.home)
        assertEquals(s.players[0].home, b.world.allyHome)
        assertNotNull(host.world.path(s.players[0].homeX, s.players[0].homeY, s.players[1].homeX, s.players[1].homeY))
    }

    @Test
    fun versusAssaultIsFoughtInTheDefendersBase() {
        val s = setup(MatchMode.VERSUS)
        val host = MatchRealm(s)
        val defender = Campaign(s.seed, CampaignSettings()).also { it.setupMultiplayer(s, 0) }

        host.handle(1, NetMsg.SendArmy(1, troops(4), targetPlayer = 0))
        assertEquals(1, host.expeditions.size)

        // March until the army reaches the capital and the assault is announced to the defender.
        var assault: NetMsg.Assault? = null
        repeat(20000) {
            if (assault != null) return@repeat
            host.step(BaseGame.DT)
            assault = host.outbox.map { it.second }.filterIsInstance<NetMsg.Assault>().firstOrNull()
        }
        val a = assertNotNullAndGet(assault)
        assertTrue(host.expeditions.single().besieging)
        assertTrue(host.outbox.any { it.first == 0 && it.second is NetMsg.Assault })
        host.outbox.clear()

        // The defender's base (no towers) fights it out.
        defender.base.launchAssault(a.assaultId, a.units, a.hp, a.from)
        var ended: GameEvent.AssaultEnded? = null
        repeat(30 * 300) {
            if (ended != null) return@repeat
            defender.step()
            ended = defender.base.drainEvents().filterIsInstance<GameEvent.AssaultEnded>().firstOrNull()
        }
        val end = assertNotNullAndGet(ended)
        assertEquals(a.units, end.total)
        assertTrue("undefended keep: attackers get through", end.reached > 0)

        val stolen = MatchRules.plunder(defender.base.res, end.reached)
        val result = NetMsg.AssaultResult(a.assaultId, end.total, end.killed, end.reached, stolen)
        host.handle(1, result)
        assertTrue(host.expeditions.single().besieging)
        host.handle(0, result)
        val e = host.expeditions.single()
        assertTrue(e.returning)
        assertEquals(stolen, e.loot)

        // Survivors come home with the plunder.
        var home: NetMsg.ArmyHome? = null
        repeat(20000) {
            if (home != null) return@repeat
            host.step(BaseGame.DT)
            home = host.outbox.filter { it.first == 1 }.map { it.second }.filterIsInstance<NetMsg.ArmyHome>().firstOrNull()
        }
        assertEquals(stolen, assertNotNullAndGet(home).loot)
        assertTrue(host.expeditions.isEmpty())
    }

    @Test
    fun coopReinforcementsJoinTheAlly() {
        val s = setup(MatchMode.COOP)
        val host = MatchRealm(s)
        host.handle(0, NetMsg.SendArmy(0, troops(3), targetPlayer = 1))
        var arrived: NetMsg.ArmyHome? = null
        repeat(20000) {
            if (arrived != null) return@repeat
            host.step(BaseGame.DT)
            arrived = host.outbox.filter { it.first == 1 }.map { it.second }.filterIsInstance<NetMsg.ArmyHome>().firstOrNull()
        }
        assertEquals(3, assertNotNullAndGet(arrived).troops.size)
        assertTrue(host.expeditions.isEmpty())
    }

    private fun <T> assertNotNullAndGet(v: T?): T {
        assertNotNull(v)
        return v!!
    }
}
