package com.marian1x.agesofsiege.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelsTest {

    private fun battle(id: String, mode: LevelMode = LevelMode.NORMAL, mods: Set<BattleModifier> = emptySet()) =
        BaseGame(7, BattleSetup(Levels.get(id)!!, mode, mods))

    private fun runSeconds(g: BaseGame, seconds: Float) {
        repeat((seconds / BaseGame.DT).toInt()) { g.step() }
    }

    @Test
    fun everyLevelIsPlayable() {
        assertEquals(18, Levels.all.size)
        assertEquals(Levels.all.size, Levels.all.map { it.id }.toSet().size)
        for (l in Levels.all) {
            val g = BaseGame(1, BattleSetup(l))
            assertTrue("${l.id} needs gates", g.gates.isNotEmpty())
            for ((x, y) in g.gates) {
                assertTrue("${l.id} gate $x,$y must be on the edge", x == 0 || y == 0 || x == BaseGame.WIDTH - 1 || y == BaseGame.HEIGHT - 1)
                assertNotEquals("${l.id} gate $x,$y can't reach the Keep", FlowField.INF, g.flow.dist[g.idx(x, y)])
            }
            assertEquals(l.waveCount, l.waves.size)
            for ((i, w) in l.waves.withIndex()) assertTrue("${l.id} wave ${i + 1} is empty", w.sumOf { it.count } > 0)
            if (l.boss != null) assertTrue(l.waves.last().any { it.enemyId == l.boss })
            for (t in l.towers) assertTrue(Content.isTower(t))
            // There must be room to build next to the road.
            val buildable = (0 until BaseGame.WIDTH * BaseGame.HEIGHT).count { g.tiles[it] == Tile.GRASS }
            assertTrue("${l.id} has too little room", buildable > 120)
        }
    }

    @Test
    fun levelsUseGoldOnly() {
        val g = battle("1-1")
        assertEquals(Cost(gold = 75), g.costOf("archer"))
        assertEquals(0f, g.res.wood)
        val gold = g.res.gold
        runSeconds(g, 5f)
        assertEquals("no passive income in levels", gold, g.res.gold)
        assertNotNull(g.canPlace("house", 3, 3))
    }

    @Test
    fun roadLevelsKeepTowersOffTheRoad() {
        val g = battle("1-1")
        val road = (0 until BaseGame.WIDTH * BaseGame.HEIGHT).first { g.tiles[it] == Tile.ROAD && !g.isGateTile(it % BaseGame.WIDTH, it / BaseGame.WIDTH) }
        assertEquals("Can't build on the road", g.canPlace("archer", road % BaseGame.WIDTH, road / BaseGame.WIDTH))
        assertFalse("walls are useless on road levels", "wall" in g.availableTowers())
    }

    @Test
    fun undefendedLevelIsLost() {
        val g = battle("1-1")
        var lost: GameEvent.LevelLost? = null
        repeat(30 * 600) {
            if (g.outcome != null) return@repeat
            if (g.phase == Phase.PREP) g.execute(Command.StartWave)
            g.step()
            g.drainEvents().filterIsInstance<GameEvent.LevelLost>().firstOrNull()?.let { lost = it }
        }
        assertEquals(Outcome.LOST, g.outcome)
        assertNotNull(lost)
        assertTrue(g.leaks > 0)
    }

    @Test
    fun starsFollowLeaks() {
        val l = Levels.get("1-1")!!
        assertEquals(3, l.starsFor(0))
        assertEquals(2, l.starsFor(1))
        assertEquals(2, l.starsFor(l.twoStarLeaks))
        assertEquals(1, l.starsFor(l.twoStarLeaks + 1))
    }

    @Test
    fun ironModeHasOneHitPoint() {
        assertEquals(1, battle("1-1", LevelMode.IRON).keepMaxHp)
        assertEquals(10, battle("1-1", mods = setOf(BattleModifier.FRAGILE)).keepMaxHp)
    }

    @Test
    fun flyersIgnoreTheMaze() {
        val g = battle("2-1")
        val e = Enemy(999, Content.enemy("harpy"), 0f, 7.5f, 50f, 0f)
        g.enemies += e
        runSeconds(g, 15f)
        assertFalse("the harpy should have reached the Keep", e.alive)
        assertEquals(1, g.leaks)
    }

    @Test
    fun ramsReleaseTheirCrew() {
        val g = battle("2-5")
        g.res.gold = 10_000f
        val ram = Enemy(999, Content.enemy("ram"), 3.5f, 3.5f, 1f, 0f)
        g.enemies += ram
        g.execute(Command.CastAbility(Ability.VOLLEY, 3.5f, 3.5f))
        var split = false
        repeat(60) {
            g.step()
            if (g.drainEvents().any { it is GameEvent.Split }) split = true
        }
        assertTrue(split)
        assertEquals(3, g.enemies.count { it.def.id == "raider" })
    }

    @Test
    fun shamansHeal() {
        val g = battle("2-3")
        val hurt = Enemy(1000, Content.enemy("brute"), 2.5f, 3.5f, 200f, 0f).also { it.hp = 50f }
        val shaman = Enemy(1001, Content.enemy("shaman"), 2.5f, 3.6f, 70f, 0f)
        g.enemies += hurt
        g.enemies += shaman
        g.step()
        assertTrue(hurt.hp > 50f)
    }

    /** Two free grass tiles side by side in the sandbox. */
    private fun freePair(g: BaseGame): Pair<Int, Int> {
        for (y in 3 until 12) for (x in 4 until 16) {
            if (g.canPlace("archer", x, y) == null && g.canPlace("archer", x + 1, y) == null) return x to y
        }
        error("no room")
    }

    private fun rich(seed: Long) = BaseGame(seed).also { it.res.gold = 5000f; it.res.wood = 5000f; it.res.stone = 5000f }

    @Test
    fun bannersBoostNeighbours() {
        val g = rich(3)
        val (x, y) = freePair(g)
        assertTrue(g.execute(Command.Place("archer", x, y)) is CommandResult.Ok)
        val archer = g.structureAt(x, y)!!
        val base = archer.towerDamage
        assertTrue(g.execute(Command.Place("banner", x + 1, y)) is CommandResult.Ok)
        assertTrue(archer.towerDamage > base * 1.1f)
        g.execute(Command.Sell(g.structureAt(x + 1, y)!!.id))
        assertEquals(base, archer.towerDamage, 0.001f)
    }

    @Test
    fun fireShattersChilledRaiders() {
        val g = rich(3)
        val (x, y) = freePair(g)
        g.execute(Command.Place("brazier", x, y))
        val e = Enemy(999, Content.enemy("brute"), x + 0.5f, y + 1.5f, 10_000f, 0f)
        e.slowTimer = 5f
        g.enemies += e
        var shatter = false
        repeat(90) {
            g.step()
            if (g.drainEvents().any { it is GameEvent.Shatter }) shatter = true
        }
        assertTrue(shatter)
        assertTrue(g.stats.shatters >= 1)
    }

    @Test
    fun targetingPriorityPicksTheStrongest() {
        val g = rich(3)
        val (x, y) = freePair(g)
        g.execute(Command.Place("ballista", x, y))
        val s = g.structureAt(x, y)!!
        g.execute(Command.SetPriority(s.id, TargetPriority.STRONG))
        val weak = Enemy(1000, Content.enemy("raider"), x + 1.5f, y + 1.5f, 30f, 0f)
        val strong = Enemy(1001, Content.enemy("brute"), x - 0.5f, y + 2.5f, 3000f, 0f)
        g.enemies += weak
        g.enemies += strong
        g.step()
        val shot = g.projectiles.firstOrNull { it.ownerId == s.id }
        assertNotNull(shot)
        assertEquals(strong.id, shot!!.targetId)
    }

    @Test
    fun militiaAndTarWork() {
        val g = battle("2-5")
        assertTrue(g.execute(Command.CastAbility(Ability.REINFORCE, 8f, 8f)) is CommandResult.Ok)
        assertEquals(Content.MILITIA_COUNT, g.troops.size)
        assertEquals(0, g.soldierCount())
        assertTrue(g.execute(Command.CastAbility(Ability.TAR, 8f, 8f)) is CommandResult.Ok)
        assertTrue(g.execute(Command.CastAbility(Ability.TAR, 8f, 8f)) is CommandResult.Rejected)
        runSeconds(g, Content.MILITIA_SECONDS + 1f)
        assertTrue(g.troops.isEmpty())
        assertTrue(g.tarPits.isEmpty())
        // Not unlocked in the first level.
        assertTrue(battle("1-1").execute(Command.CastAbility(Ability.TAR, 8f, 8f)) is CommandResult.Rejected)
    }

    @Test
    fun endlessGoesPastTheLastWave() {
        val l = Levels.get("1-1")!!
        val g = BaseGame(5, BattleSetup(l, LevelMode.ENDLESS))
        assertEquals(Int.MAX_VALUE, g.totalWaves)
        assertTrue(g.waveGroups(l.waveCount + 3)!!.sumOf { it.count } > l.waves.last().sumOf { it.count })
    }

    @Test
    fun dailyChallengeIsTheSameForEveryone() {
        val a = DailyChallenge.forDay(20_000)
        val b = DailyChallenge.forDay(20_000)
        assertEquals(a.level.id, b.level.id)
        assertEquals(a.modifiers, b.modifiers)
        assertTrue(a.perks.isEmpty())
    }

    @Test
    fun profileUnlocksLevelsInOrder() {
        val p = PlayerProfile()
        val l1 = Levels.all[0]
        val l2 = Levels.all[1]
        assertTrue(p.isUnlocked(l1))
        assertFalse(p.isUnlocked(l2))
        val out = p.recordBattle(BattleResult(l1.id, LevelMode.NORMAL, true, 2, 1000, 6, 40, 0, 0, 2, 5, 100))
        assertEquals(2, out.newStars)
        assertTrue(out.firstWin)
        assertTrue(p.isUnlocked(l2))
        assertFalse(p.modeUnlocked(l1, LevelMode.HEROIC))
        p.recordBattle(BattleResult(l1.id, LevelMode.NORMAL, true, 3, 1200, 6, 40, 0, 0, 2, 5, 100))
        assertEquals(3, p.stars(l1.id))
        assertTrue(p.modeUnlocked(l1, LevelMode.HEROIC))
        // Chapter 2 needs stars.
        assertFalse(p.isUnlocked(Levels.all.first { it.chapter == 2 }))
        // Glory: 3 stars + First Victory achievement.
        assertTrue(p.glory >= 4)
        assertNull(p.buyPerk("war_chest"))
        val round = PlayerProfile.fromJson(p.toJson())
        assertEquals(p.glory, round.glory)
        assertEquals(1, round.perk("war_chest"))
    }

    @Test
    fun perksChangeTheBattle() {
        val l = Levels.get("1-1")!!
        val plain = BaseGame(1, BattleSetup(l))
        val rich = BaseGame(1, BattleSetup(l, perks = mapOf("war_chest" to 5, "stonework" to 2)))
        assertTrue(rich.res.gold > plain.res.gold * 1.35f)
        assertEquals(plain.keepMaxHp + 8, rich.keepMaxHp)
    }

    /** A plain greedy player: builds where towers cover the most path, upgrades, casts Volley. */
    private fun bot(l: LevelDef, mode: LevelMode = LevelMode.NORMAL): BaseGame {
        val g = BaseGame(11, BattleSetup(l, mode))
        val towers = g.availableTowers().filter { it != "wall" }
        val plan = listOf("archer", "ballista", "archer", "frost", "catapult", "repeater", "brazier", "banner", "ballista")
            .filter { it in towers }
        var next = 0
        var timer = 0f
        while (g.outcome == null && g.time < 3600f) {
            g.step()
            g.drainEvents()
            timer += BaseGame.DT
            if (timer < 0.5f) continue
            timer = 0f
            val g2 = g
            if (g2.cooldown(Ability.VOLLEY) <= 0f && g2.abilityAllowed(Ability.VOLLEY)) {
                g2.enemies.minByOrNull { (it.x - g2.keep.cx) * (it.x - g2.keep.cx) + (it.y - g2.keep.cy) * (it.y - g2.keep.cy) }
                    ?.let { g2.execute(Command.CastAbility(Ability.VOLLEY, it.x, it.y)) }
            }
            val towersBuilt = g.structures.values.count { it.kind == StructureKind.TOWER }
            val upgradable = g.structures.values.filter { it.kind == StructureKind.TOWER && g.upgradeCost(it) != null }
                .minByOrNull { g.upgradeCost(it)!!.gold }
            if (towersBuilt >= 5 + g.wave / 2 && upgradable != null) {
                if (g.res.canAfford(g.upgradeCost(upgradable)!!)) g.execute(Command.Upgrade(upgradable.id))
                continue
            }
            val id = plan[next % plan.size]
            if (!g.res.canAfford(g.costOf(id))) continue
            val def = Content.tower(id)
            // The route raiders actually walk: follow the flow field down from every gate.
            val route = HashSet<Int>()
            for ((gx, gy) in g.gates) {
                var cx = gx
                var cy = gy
                repeat(200) {
                    route += g.idx(cx, cy)
                    var bx = cx
                    var by = cy
                    var bd = g.flow.dist[g.idx(cx, cy)]
                    for (k in 0 until 4) {
                        val nx = cx + FlowField.DX[k]
                        val ny = cy + FlowField.DY[k]
                        if (!g.inBounds(nx, ny)) continue
                        val nd = g.flow.dist[g.idx(nx, ny)]
                        if (nd < bd) { bd = nd; bx = nx; by = ny }
                    }
                    if (bx == cx && by == cy) return@repeat
                    cx = bx
                    cy = by
                }
            }
            var best: Pair<Int, Int>? = null
            var bestScore = -1f
            for (y in 0 until BaseGame.HEIGHT) for (x in 0 until BaseGame.WIDTH) {
                if (g.tiles[g.idx(x, y)] != Tile.GRASS || g.structureAt(x, y) != null || g.idx(x, y) in route) continue
                var score = 0f
                val r = def.range.toInt() + 1
                for (yy in y - r..y + r) for (xx in x - r..x + r) {
                    if (!g.inBounds(xx, yy) || g.idx(xx, yy) !in route) continue
                    if (dist(x + 0.5f, y + 0.5f, xx + 0.5f, yy + 0.5f) > def.range) continue
                    score += 1f + 100f / (g.flow.dist[g.idx(xx, yy)] + 100f)
                }
                if (score > bestScore && g.canPlace(id, x, y) == null) {
                    bestScore = score
                    best = x to y
                }
            }
            val spot = best ?: continue
            if (g.execute(Command.Place(id, spot.first, spot.second)) is CommandResult.Ok) next++
        }
        return g
    }

    /**
     * Balance guard. The bot never mazes and never uses Tar, Militia or targeting, so it is a weak player:
     * it must still beat chapter 1 up to the boss, and the report shows where the difficulty curve sits.
     */
    @Test
    fun aSimpleBotCanBeatTheFirstLevels() {
        val report = StringBuilder()
        val results = HashMap<String, BaseGame>()
        for (l in Levels.all) {
            val g = bot(l)
            results[l.id] = g
            report.append("${l.id} ${l.name}: ${g.outcome} wave ${g.wave}/${l.waveCount} leaks ${g.leaks} stars ${g.stars} gold ${g.res.gold.toInt()}\n")
        }
        println(report)
        for (id in listOf("1-1", "1-2", "1-3", "1-4", "1-5")) assertEquals("$id\n$report", Outcome.WON, results[id]!!.outcome)
        // A bot without mazing should not breeze through the finale.
        assertTrue(report.toString(), results["3-6"]!!.stars < 3)
    }
}
