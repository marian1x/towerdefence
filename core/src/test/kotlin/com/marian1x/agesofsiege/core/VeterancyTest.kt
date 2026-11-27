package com.marian1x.agesofsiege.core

import org.junit.Assert.assertTrue
import org.junit.Test

class VeterancyTest {
    private val save = """{"version":1,"seed":3876627,"gold":96.3,"wood":7.3,"stone":2.9,"wave":5,"bestWave":4,"structures":[
        {"defId":"archer","x":9,"y":5,"level":1,"xp":18,"kills":2,"invested":{"gold":50,"wood":25,"stone":0}},
        {"defId":"archer","x":9,"y":9,"level":2,"xp":81,"kills":9,"invested":{"gold":110,"wood":55,"stone":0}},
        {"defId":"wall","x":20,"y":6,"level":1,"xp":0,"kills":0},{"defId":"wall","x":20,"y":7,"level":1,"xp":0,"kills":0},
        {"defId":"wall","x":20,"y":8,"level":1,"xp":0,"kills":0},{"defId":"wall","x":20,"y":9,"level":1,"xp":0,"kills":0},
        {"defId":"wall","x":21,"y":9,"level":1,"xp":0,"kills":0},
        {"defId":"archer","x":14,"y":5,"level":1,"xp":18,"kills":2,"invested":{"gold":50,"wood":25,"stone":0}},
        {"defId":"wall","x":21,"y":5,"level":1,"xp":0,"kills":0}]}"""

    @Test
    fun xpGrowsOnlyWithKills() {
        val g = BaseGame(3876627)
        g.loadFrom(SaveData.fromJson(save))
        g.execute(Command.StartWave)
        repeat(30 * 10) { g.step() }
        for (s in g.structures.values) {
            if (s.kind != StructureKind.TOWER) continue
            println("tower ${s.x},${s.y} xp=${s.xp} kills=${s.kills} rank=${s.rank}")
            assertTrue("xp must equal kills-based gain", s.xp <= 18 + 81 + s.kills * 3 * 60)
            assertTrue("rank exploded: ${s.rank} with ${s.kills} kills", s.rank <= 3)
        }
    }
}
