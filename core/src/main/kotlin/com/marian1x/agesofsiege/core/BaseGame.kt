package com.marian1x.agesofsiege.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class StructureKind { KEEP, TOWER, WALL, BUILDING }

class Structure(
    val id: Int,
    val kind: StructureKind,
    val defId: String,
    val x: Int,
    val y: Int,
    val w: Int = 1,
    val h: Int = 1,
) {
    var level = 1
    var xp = 0
    var kills = 0
    var cooldown = 0f
    /** Aim direction (radians, world XZ plane) – used by the renderer. */
    var aim = 0f
    /** Seconds since last shot – lets the renderer animate recoil. */
    var sinceShot = 99f
    var invested = Cost()
    var specialization: TowerSpecialization? = null
        internal set
    val rank get() = Content.rankFor(xp)
    val cx get() = x + w / 2f
    val cy get() = y + h / 2f
    val towerDef get() = Content.tower(defId)
    val towerDamage get() = towerDef.damage * Content.levelDamageMult(level) * Content.rankDamageMult(rank) *
        (specialization?.damageMultiplier ?: 1f)
    val towerRange get() = towerDef.range + Content.levelRangeBonus(level) + Content.rankRangeBonus(rank) +
        (specialization?.rangeBonus ?: 0f)
    val towerFireRate get() = towerDef.fireRate * Content.levelRateMult(level) *
        (specialization?.fireRateMultiplier ?: 1f)
}

class Enemy(
    val id: Int,
    val def: EnemyDef,
    var x: Float,
    var y: Float,
    val maxHp: Float,
    /** Lateral offset so raiders don't walk in a single file. */
    val lane: Float,
) {
    var hp = maxHp
    var prevX = x
    var prevY = y
    var heading = 0f
    var alive = true
    var lastTile = -1
    var slowTimer = 0f
    var attackCd = 0f
    /** True while fighting a troop (stands still). */
    var engaged = false
    /** Non-zero for soldiers of another player's assault (multiplayer). */
    var assaultId = 0
}

/** A recruited soldier defending the base. */
class Troop(
    val id: Int,
    val def: UnitDef,
    var x: Float,
    var y: Float,
    var hp: Float,
    var xp: Int,
    var kills: Int,
) {
    var prevX = x
    var prevY = y
    var heading = 0f
    var cooldown = 0f
    var sinceAttack = 99f
    var alive = true
    /** True when moving (for walk animation). */
    var moving = false
    val rank get() = Content.rankFor(xp)
    fun toData() = TroopData(def.id, hp, xp, kills)
}

class Projectile(
    val id: Int,
    val ownerId: Int,
    val visual: String,
    val type: DamageType,
    val damage: Float,
    val splash: Float,
    val homing: Boolean,
    val targetId: Int,
    var x: Float,
    var y: Float,
    var tx: Float,
    var ty: Float,
    val speed: Float,
    val chillSeconds: Float = 0f,
) {
    var prevX = x
    var prevY = y
    val startX = x
    val startY = y
    var totalDist = max(0.01f, dist(x, y, tx, ty))
    var alive = true
    /** 0..1 flight progress (for arcs). */
    var progress = 0f
}

class PendingVolley(val x: Float, val y: Float, var timer: Float, val damage: Float)

enum class Phase { PREP, ACTIVE }

sealed class GameEvent {
    data class Shot(val structureId: Int, val visual: String) : GameEvent()
    data class Hit(
        val x: Float, val y: Float, val damage: Int, val type: DamageType, val splash: Float, val visual: String = "",
    ) : GameEvent()
    data class EnemyDied(val enemyId: Int, val defId: String, val x: Float, val y: Float, val bounty: Int) : GameEvent()
    data class RankUp(val structureId: Int, val rank: Int) : GameEvent()
    data class WaveStarted(val wave: Int) : GameEvent()
    data class WaveCleared(val wave: Int, val reward: Int) : GameEvent()
    data class KeepHit(val damage: Int, val x: Float, val y: Float) : GameEvent()
    data class RaidLost(val wave: Int) : GameEvent()
    data class VolleyCast(val x: Float, val y: Float) : GameEvent()
    data class VolleyImpact(val x: Float, val y: Float) : GameEvent()
    data class Built(val structureId: Int) : GameEvent()
    data class Removed(val structureId: Int) : GameEvent()
    data class Upgraded(val structureId: Int, val level: Int) : GameEvent()
    data class Specialized(val structureId: Int, val specialization: TowerSpecialization) : GameEvent()
    data class TroopRecruited(val troopId: Int) : GameEvent()
    data class TroopDied(val troopId: Int, val defId: String, val x: Float, val y: Float) : GameEvent()
    /** Narrative message for the player (expeditions, rival news...). */
    data class Report(val text: String, val good: Boolean) : GameEvent()
    /** Another player's assault arrived at the gates. */
    data class AssaultStarted(val assaultId: Int, val units: Int, val from: String) : GameEvent()
    /** All soldiers of an assault are dead or reached the keep. */
    data class AssaultEnded(val assaultId: Int, val total: Int, val killed: Int, val reached: Int) : GameEvent()
}

/** Every player action is a command. A server can later validate the same commands. */
sealed class Command {
    data class Place(val defId: String, val x: Int, val y: Int) : Command()
    data class Upgrade(val structureId: Int) : Command()
    data class Specialize(val structureId: Int, val specialization: TowerSpecialization) : Command()
    data class Sell(val structureId: Int) : Command()
    object StartWave : Command()
    data class CastVolley(val x: Float, val y: Float) : Command()
    data class Recruit(val unitId: String) : Command()
}

sealed class CommandResult {
    object Ok : CommandResult()
    data class Rejected(val reason: String) : CommandResult()
}

class Obstacle(val x: Int, val y: Int, val kind: Int, val variant: Int)

/**
 * The base defense simulation. Runs at a fixed tick ([DT]) and is fully deterministic for a given seed
 * and command sequence.
 */
class BaseGame(val seed: Long = 1234L) {
    companion object {
        const val WIDTH = 26
        const val HEIGHT = 16
        const val DT = 1f / 30f
        const val FIRST_WAVE_DELAY = 40f
        const val WAVE_DELAY = 25f
    }

    val width = WIDTH
    val height = HEIGHT
    private val rng = Rng(seed)

    val res = MutableRes(Content.startResources)
    val structures = LinkedHashMap<Int, Structure>()
    val enemies = ArrayList<Enemy>()
    val projectiles = ArrayList<Projectile>()
    val obstacles = ArrayList<Obstacle>()
    val volleys = ArrayList<PendingVolley>()
    val events = ArrayList<GameEvent>()
    val troops = ArrayList<Troop>()

    /** Raider HP multiplier (difficulty, cleared camps) – set by the campaign. */
    var hpModifier = 1f
    /** Resource production multiplier (game pace) – set by the campaign. */
    var productionMult = 1f
    /** Rival soldiers that will join the next wave. */
    var pendingRivalUnits = 0
    /** Troops currently away on expeditions (count towards the army cap). */
    var awayTroops = 0

    // Multiplayer assaults: enemy soldiers sent by another player, spawned outside the wave cycle.
    private class AssaultSpawn(var delay: Float, val gate: Int, val assaultId: Int, val enemyId: String, val hp: Float)
    private class AssaultTrack(val total: Int, var killed: Int = 0, var reached: Int = 0)
    private val assaultQueue = ArrayList<AssaultSpawn>()
    private val assaults = HashMap<Int, AssaultTrack>()

    /** Number of assaults still in progress (spawning or alive). */
    val activeAssaults: Int get() = assaults.size

    /** Entry gates on the map edge (tile coordinates). */
    val gates: List<Pair<Int, Int>> = listOf(0 to 3, 0 to 12, 12 to 0)

    var wave = 1
        private set
    var phase = Phase.PREP
        private set
    var countdown = FIRST_WAVE_DELAY
        private set
    var keepHp = Content.KEEP_MAX_HP
        private set
    var volleyCooldown = 0f
        private set
    var time = 0f
        private set
    var bestWave = 0
        private set

    private val blocked = BooleanArray(WIDTH * HEIGHT)
    private val goal = BooleanArray(WIDTH * HEIGHT)
    private val occupant = IntArray(WIDTH * HEIGHT) { -1 }
    val flow = FlowField(WIDTH, HEIGHT)
    private val scratch = IntArray(WIDTH * HEIGHT)
    private var spawnQueue = ArrayDeque<SpawnEntry>()
    private var waveTime = 0f
    private var nextId = 1

    val keep: Structure

    init {
        keep = Structure(nextId++, StructureKind.KEEP, "keep", 21, 7, 2, 2)
        addStructure(keep)
        generateObstacles()
        recomputeFlow()
    }

    // ---------------------------------------------------------------- map helpers

    fun idx(x: Int, y: Int) = y * WIDTH + x
    fun inBounds(x: Int, y: Int) = x in 0 until WIDTH && y in 0 until HEIGHT
    fun isBlocked(x: Int, y: Int) = blocked[idx(x, y)]
    fun structureAt(x: Int, y: Int): Structure? = if (!inBounds(x, y)) null else structures[occupant[idx(x, y)]]
    fun isGateTile(x: Int, y: Int) = gates.any { it.first == x && it.second == y }
    fun activeGates() = Content.gatesFor(wave)

    private fun generateObstacles() {
        // Natural clutter (trees, rocks) – avoid the Keep surroundings, gates and edges.
        val count = 20
        var tries = 0
        while (obstacles.size < count && tries < 500) {
            tries++
            val x = 2 + rng.nextInt(WIDTH - 4)
            val y = 1 + rng.nextInt(HEIGHT - 2)
            if (abs(x - 21.5f) < 4f && abs(y - 7.5f) < 4f) continue
            if (gates.any { abs(it.first - x) + abs(it.second - y) < 3 }) continue
            val i = idx(x, y)
            if (blocked[i]) continue
            blocked[i] = true
            if (!pathsOk()) {
                blocked[i] = false
                continue
            }
            obstacles += Obstacle(x, y, if (rng.nextFloat() < 0.65f) 0 else 1, rng.nextInt(1000))
        }
    }

    private fun addStructure(s: Structure) {
        structures[s.id] = s
        for (yy in s.y until s.y + s.h) for (xx in s.x until s.x + s.w) {
            val i = idx(xx, yy)
            blocked[i] = true
            occupant[i] = s.id
            if (s.kind == StructureKind.KEEP) goal[i] = true
        }
    }

    private fun removeStructure(s: Structure) {
        structures.remove(s.id)
        for (yy in s.y until s.y + s.h) for (xx in s.x until s.x + s.w) {
            val i = idx(xx, yy)
            blocked[i] = false
            occupant[i] = -1
        }
    }

    private fun recomputeFlow() = flow.compute(blocked, goal)

    private fun pathsOk(): Boolean {
        flow.compute(blocked, goal, scratch)
        for ((gx, gy) in gates) if (scratch[idx(gx, gy)] == FlowField.INF) return false
        for (e in enemies) {
            val t = enemyTile(e)
            if (t >= 0 && !blocked[t] && scratch[t] == FlowField.INF) return false
        }
        return true
    }

    private fun enemyTile(e: Enemy): Int {
        val tx = floor(e.x).toInt()
        val ty = floor(e.y).toInt()
        return if (inBounds(tx, ty)) idx(tx, ty) else -1
    }

    // ---------------------------------------------------------------- commands

    fun canPlace(defId: String, x: Int, y: Int): String? {
        if (!inBounds(x, y)) return "Outside the base"
        if (blocked[idx(x, y)]) return "Tile is occupied"
        if (isGateTile(x, y)) return "Can't build on a gate"
        if (enemies.any { it.alive && enemyTile(it) == idx(x, y) }) return "Enemies in the way"
        val cost = costOf(defId)
        if (!res.canAfford(cost)) return "Not enough resources"
        blocked[idx(x, y)] = true
        val ok = pathsOk()
        blocked[idx(x, y)] = false
        if (!ok) return "That would block every path"
        return null
    }

    fun costOf(defId: String): Cost = when {
        Content.isTower(defId) -> Content.tower(defId).cost
        Content.isBuilding(defId) -> Content.building(defId).cost
        else -> error("Unknown def $defId")
    }

    fun upgradeCost(s: Structure): Cost? = when (s.kind) {
        StructureKind.TOWER -> s.towerDef.upgradeCosts.getOrNull(s.level - 1)
        StructureKind.BUILDING -> Content.building(s.defId).upgradeCosts.getOrNull(s.level - 1)
        else -> null
    }

    fun execute(cmd: Command): CommandResult = when (cmd) {
        is Command.Place -> {
            val err = canPlace(cmd.defId, cmd.x, cmd.y)
            if (err != null) CommandResult.Rejected(err) else {
                val kind = when {
                    Content.isBuilding(cmd.defId) -> StructureKind.BUILDING
                    Content.tower(cmd.defId).isWall -> StructureKind.WALL
                    else -> StructureKind.TOWER
                }
                val cost = costOf(cmd.defId)
                res.pay(cost)
                val s = Structure(nextId++, kind, cmd.defId, cmd.x, cmd.y)
                s.invested = cost
                addStructure(s)
                recomputeFlow()
                events += GameEvent.Built(s.id)
                CommandResult.Ok
            }
        }
        is Command.Upgrade -> {
            val s = structures[cmd.structureId]
            val cost = s?.let { upgradeCost(it) }
            when {
                s == null || cost == null -> CommandResult.Rejected("Max level")
                !res.canAfford(cost) -> CommandResult.Rejected("Not enough resources")
                else -> {
                    res.pay(cost)
                    s.level++
                    s.invested = s.invested + cost
                    events += GameEvent.Upgraded(s.id, s.level)
                    CommandResult.Ok
                }
            }
        }
        is Command.Specialize -> {
            val s = structures[cmd.structureId]
            val cost = Content.specializationCost
            when {
                s == null || s.kind != StructureKind.TOWER -> CommandResult.Rejected("Select a tower first")
                s.level < Content.SPECIALIZATION_LEVEL -> CommandResult.Rejected("Upgrade this tower to level ${Content.SPECIALIZATION_LEVEL} first")
                s.specialization != null -> CommandResult.Rejected("This tower already has a specialization")
                !res.canAfford(cost) -> CommandResult.Rejected("Not enough resources")
                else -> {
                    res.pay(cost)
                    s.invested += cost
                    s.specialization = cmd.specialization
                    events += GameEvent.Specialized(s.id, cmd.specialization)
                    CommandResult.Ok
                }
            }
        }
        is Command.Sell -> {
            val s = structures[cmd.structureId]
            if (s == null || s.kind == StructureKind.KEEP) CommandResult.Rejected("Can't sell that") else {
                res.add(s.invested * Content.SELL_REFUND)
                removeStructure(s)
                recomputeFlow()
                events += GameEvent.Removed(s.id)
                CommandResult.Ok
            }
        }
        Command.StartWave -> {
            if (phase != Phase.PREP) CommandResult.Rejected("Wave already running") else {
                // Calling a wave early pays a bonus for the time skipped.
                res.gold += countdown.toInt()
                startWave()
                CommandResult.Ok
            }
        }
        is Command.Recruit -> {
            val def = Content.unit(cmd.unitId)
            when {
                structures.values.none { it.defId == "barracks" } -> CommandResult.Rejected("Build a Barracks first")
                troops.size + awayTroops >= armyCap() -> CommandResult.Rejected("Army is full – upgrade or build Barracks")
                !res.canAfford(def.cost) -> CommandResult.Rejected("Not enough resources")
                else -> {
                    res.pay(def.cost)
                    val t = Troop(nextId++, def, keep.x - 0.3f, keep.cy, def.hp, 0, 0)
                    troops += t
                    events += GameEvent.TroopRecruited(t.id)
                    CommandResult.Ok
                }
            }
        }
        is Command.CastVolley -> {
            if (volleyCooldown > 0f) CommandResult.Rejected("Volley recharging") else {
                volleyCooldown = Content.VOLLEY_COOLDOWN
                volleys += PendingVolley(cmd.x, cmd.y, Content.VOLLEY_DELAY, 40f + 6f * wave)
                events += GameEvent.VolleyCast(cmd.x, cmd.y)
                CommandResult.Ok
            }
        }
    }

    // ---------------------------------------------------------------- simulation

    fun step() {
        time += DT
        produce()
        if (volleyCooldown > 0f) volleyCooldown = max(0f, volleyCooldown - DT)
        when (phase) {
            Phase.PREP -> {
                countdown -= DT
                if (countdown <= 0f) startWave()
            }
            Phase.ACTIVE -> {
                waveTime += DT
                while (spawnQueue.isNotEmpty() && spawnQueue.first().time <= waveTime) spawn(spawnQueue.removeFirst())
            }
        }
        stepAssaults()
        moveEnemies()
        troopsAct()
        towersAct()
        moveProjectiles()
        updateVolleys()
        enemies.removeAll { !it.alive }
        troops.removeAll { !it.alive }
        projectiles.removeAll { !it.alive }
        if (phase == Phase.ACTIVE && spawnQueue.isEmpty() && enemies.none { it.assaultId == 0 }) waveCleared()
    }

    private fun produce() {
        val perSec = 1f / 60f * DT * productionMult
        res.addScaled(Content.keepProduction, perSec)
        for (s in structures.values) if (s.kind == StructureKind.BUILDING) {
            res.addScaled(Content.building(s.defId).produces, perSec * Content.levelProductionMult(s.level))
        }
    }

    private fun startWave() {
        phase = Phase.ACTIVE
        waveTime = 0f
        val spawns = ArrayList(Content.waveSpawns(wave))
        if (pendingRivalUnits > 0) {
            var t = (spawns.lastOrNull()?.time ?: 0f) + 2f
            repeat(pendingRivalUnits) {
                spawns += SpawnEntry(t, "rival", it % activeGates())
                t += 1f
            }
            events += GameEvent.Report("The rival lord's soldiers join this attack!", false)
            pendingRivalUnits = 0
        }
        spawnQueue = ArrayDeque(spawns)
        events += GameEvent.WaveStarted(wave)
    }

    /**
     * Another player's army attacks this base right now: [units] soldiers of [enemyId] with [hp] each,
     * marching in through the open gates a second apart. Ends with [GameEvent.AssaultEnded].
     */
    fun launchAssault(assaultId: Int, units: Int, hp: Float, from: String, enemyId: String = "rival") {
        if (units <= 0 || assaultId in assaults) return
        assaults[assaultId] = AssaultTrack(units)
        repeat(units) { assaultQueue += AssaultSpawn(1.5f + it * 0.9f, it % gates.size, assaultId, enemyId, hp) }
        events += GameEvent.AssaultStarted(assaultId, units, from)
    }

    private fun stepAssaults() {
        if (assaultQueue.isEmpty()) return
        val it = assaultQueue.iterator()
        while (it.hasNext()) {
            val a = it.next()
            a.delay -= DT
            if (a.delay > 0f) continue
            it.remove()
            val def = Content.enemy(a.enemyId)
            val (gx, gy) = gates[a.gate]
            val sx = if (gx == 0) -0.6f else gx + 0.5f
            val sy = if (gy == 0) -0.6f else gy + 0.5f
            val enemy = Enemy(nextId++, def, sx, sy, a.hp, rng.range(-0.28f, 0.28f))
            enemy.assaultId = a.assaultId
            enemies += enemy
        }
    }

    private fun assaultOutcome(assaultId: Int, killed: Boolean) {
        val t = assaults[assaultId] ?: return
        if (killed) t.killed++ else t.reached++
        if (t.killed + t.reached >= t.total) {
            assaults.remove(assaultId)
            events += GameEvent.AssaultEnded(assaultId, t.total, t.killed, t.reached)
        }
    }

    private fun waveCleared() {
        val reward = Content.waveReward(wave)
        res.gold += reward
        events += GameEvent.WaveCleared(wave, reward)
        bestWave = max(bestWave, wave)
        wave++
        phase = Phase.PREP
        countdown = WAVE_DELAY
    }

    private fun raidLost() {
        // Attacking soldiers still standing when the keep falls count as having broken through.
        for (e in enemies) if (e.alive && e.assaultId != 0) assaultOutcome(e.assaultId, killed = false)
        for (a in assaultQueue) assaultOutcome(a.assaultId, killed = false)
        assaultQueue.clear()
        events += GameEvent.RaidLost(wave)
        res.gold *= 0.8f
        res.wood *= 0.8f
        res.stone *= 0.8f
        enemies.clear()
        projectiles.clear()
        spawnQueue.clear()
        keepHp = Content.KEEP_MAX_HP
        phase = Phase.PREP
        countdown = WAVE_DELAY
    }

    private fun spawn(e: SpawnEntry) {
        val def = Content.enemy(e.enemyId)
        val (gx, gy) = gates[e.gate % gates.size]
        // Start just outside the map edge so raiders "march in".
        val sx = if (gx == 0) -0.6f else gx + 0.5f
        val sy = if (gy == 0) -0.6f else gy + 0.5f
        val enemy = Enemy(nextId++, def, sx, sy + 0f, def.hp * Content.waveHpScale(wave) * hpModifier, rng.range(-0.28f, 0.28f))
        enemy.x += rng.range(-0.2f, 0.2f) * (if (gx == 0) 0f else 1f)
        enemy.y += rng.range(-0.2f, 0.2f) * (if (gy == 0) 0f else 1f)
        enemies += enemy
    }

    private fun moveEnemies() {
        val d = flow.dist
        for (e in enemies) {
            if (!e.alive) continue
            e.prevX = e.x
            e.prevY = e.y
            val speedMultiplier = if (e.slowTimer > 0f) Content.CHILLED_SPEED_MULTIPLIER else 1f
            e.slowTimer = (e.slowTimer - DT).coerceAtLeast(0f)
            // Blocked by a soldier: stop and fight.
            val foe = troops.firstOrNull { it.alive && dist(it.x, it.y, e.x, e.y) < 0.7f }
            if (foe != null) {
                e.engaged = true
                e.heading = atan2(foe.x - e.x, foe.y - e.y)
                if (e.attackCd > 0f) e.attackCd -= DT else {
                    e.attackCd = 1f
                    foe.hp -= e.def.damage
                    if (foe.hp <= 0f) {
                        foe.alive = false
                        events += GameEvent.TroopDied(foe.id, foe.def.id, foe.x, foe.y)
                    }
                }
                continue
            }
            e.engaged = false
            var tx = floor(e.x).toInt()
            var ty = floor(e.y).toInt()
            // Off-map (marching in): clamp to the entry tile.
            tx = tx.coerceIn(0, WIDTH - 1)
            ty = ty.coerceIn(0, HEIGHT - 1)
            var tile = idx(tx, ty)
            if (blocked[tile] && !goal[tile] && e.lastTile >= 0) {
                tile = e.lastTile
                tx = tile % WIDTH
                ty = tile / WIDTH
            } else {
                e.lastTile = tile
            }
            // Pick the best neighbor (lowest distance to the Keep).
            var best = -1
            var bestD = d[tile]
            for (k in 0 until 8) {
                val nx = tx + FlowField.DX[k]
                val ny = ty + FlowField.DY[k]
                if (!inBounds(nx, ny)) continue
                val ni = idx(nx, ny)
                if (k >= 4) {
                    val a = idx(tx + FlowField.DX[k], ty)
                    val b = idx(tx, ty + FlowField.DY[k])
                    if ((blocked[a] && !goal[a]) || (blocked[b] && !goal[b])) continue
                }
                val nd = if (goal[ni]) 0 else d[ni]
                if (nd < bestD) {
                    bestD = nd
                    best = ni
                }
            }
            val speed = e.def.speed * speedMultiplier * DT
            var targetX: Float
            var targetY: Float
            if (best < 0) {
                // Already on the best tile (or stuck): walk to tile center.
                targetX = tx + 0.5f
                targetY = ty + 0.5f
            } else if (goal[best]) {
                // Next to the Keep: walk up to its wall and strike.
                targetX = e.x.coerceIn(keep.x.toFloat() - 0.1f, (keep.x + keep.w).toFloat() + 0.1f)
                targetY = e.y.coerceIn(keep.y.toFloat() - 0.1f, (keep.y + keep.h).toFloat() + 0.1f)
                if (dist(e.x, e.y, targetX, targetY) < 0.25f) {
                    e.alive = false
                    if (e.assaultId != 0) assaultOutcome(e.assaultId, killed = false)
                    keepHp -= e.def.keepDamage
                    events += GameEvent.KeepHit(e.def.keepDamage, e.x, e.y)
                    if (keepHp <= 0) {
                        raidLost()
                        return
                    }
                    continue
                }
            } else {
                val bx = best % WIDTH
                val by = best / WIDTH
                val dirX = (bx - tx).toFloat()
                val dirY = (by - ty).toFloat()
                val len = sqrt(dirX * dirX + dirY * dirY)
                targetX = bx + 0.5f - dirY / len * e.lane
                targetY = by + 0.5f + dirX / len * e.lane
            }
            val dx = targetX - e.x
            val dy = targetY - e.y
            val len = sqrt(dx * dx + dy * dy)
            if (len > 1e-4f) {
                val m = min(speed, len)
                e.x += dx / len * m
                e.y += dy / len * m
                e.heading = atan2(dx, dy)
            }
        }
    }

    /** Where idle troops gather (in front of the Keep). */
    fun rallyPoint(): Pair<Float, Float> = (keep.x - 1.3f) to keep.cy

    fun armyCap(): Int = Content.ARMY_BASE_CAP +
        structures.values.filter { it.defId == "barracks" }.sumOf { it.level } * Content.ARMY_CAP_PER_BARRACKS_LEVEL

    fun garrison(): Map<String, Int> = troops.groupingBy { it.def.id }.eachCount()

    /** Removes troops from the base to send them on an expedition (healthiest first). */
    fun takeTroops(counts: Map<String, Int>): List<TroopData> {
        val out = ArrayList<TroopData>()
        for ((id, n) in counts) {
            val pick = troops.filter { it.def.id == id }.sortedByDescending { it.hp }.take(n)
            for (t in pick) {
                troops.remove(t)
                out += t.toData()
            }
        }
        return out
    }

    /** Troops coming home from an expedition. */
    fun returnTroops(list: List<TroopData>) {
        for (d in list) {
            if (!Content.isUnit(d.defId) || d.hp <= 0f) continue
            troops += Troop(nextId++, Content.unit(d.defId), keep.x - 0.3f, keep.cy, d.hp, d.xp, d.kills)
        }
    }

    private fun troopsAct() {
        val (rx, ry) = rallyPoint()
        for ((i, t) in troops.withIndex()) {
            if (!t.alive) continue
            t.prevX = t.x
            t.prevY = t.y
            t.moving = false
            t.sinceAttack += DT
            if (t.cooldown > 0f) t.cooldown -= DT
            val homeX = rx - (i / 4) * 0.75f
            val homeY = ry + ((i % 4) - 1.5f) * 0.75f
            var target: Enemy? = null
            var best = Float.MAX_VALUE
            for (e in enemies) {
                if (!e.alive) continue
                if (dist(homeX, homeY, e.x, e.y) > Content.TROOP_AGGRO_RANGE + t.def.range) continue
                val d = dist(t.x, t.y, e.x, e.y)
                if (d < best) {
                    best = d
                    target = e
                }
            }
            if (target != null) {
                t.heading = atan2(target.x - t.x, target.y - t.y)
                if (best > t.def.range) {
                    moveToward(t, target.x, target.y)
                } else if (t.cooldown <= 0f) {
                    t.cooldown = 1f / t.def.attackRate
                    t.sinceAttack = 0f
                    val dmg = t.def.damage * Content.rankDamageMult(t.rank)
                    if (t.def.range > 1.5f) {
                        projectiles += Projectile(
                            nextId++, t.id, "bowman", t.def.damageType, dmg, 0f, true, target.id,
                            t.x, t.y, target.x, target.y, 11f,
                        )
                    } else {
                        val dealt = damage(target, dmg, t.def.damageType, null, t)
                        events += GameEvent.Hit(target.x, target.y, dealt, t.def.damageType, 0f)
                    }
                }
            } else {
                if (dist(t.x, t.y, homeX, homeY) > 0.05f) moveToward(t, homeX, homeY)
                t.hp = min(t.def.hp, t.hp + 6f * DT)
            }
        }
    }

    private fun moveToward(t: Troop, x: Float, y: Float) {
        val dx = x - t.x
        val dy = y - t.y
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1e-4f) return
        val m = min(t.def.speed * DT, len)
        t.x += dx / len * m
        t.y += dy / len * m
        t.heading = atan2(dx, dy)
        t.moving = true
    }

    private fun towersAct() {
        val d = flow.dist
        for (s in structures.values) {
            if (s.kind != StructureKind.TOWER) continue
            val def = s.towerDef
            s.sinceShot += DT
            if (s.cooldown > 0f) s.cooldown -= DT
            val range = s.towerRange
            // Target the raider closest to the Keep.
            var target: Enemy? = null
            var targetScore = Int.MAX_VALUE
            for (e in enemies) {
                if (!e.alive) continue
                if (dist(s.cx, s.cy, e.x, e.y) > range) continue
                val t = enemyTile(e)
                val score = if (t < 0) FlowField.INF - 1 else d[t]
                if (score < targetScore) {
                    targetScore = score
                    target = e
                }
            }
            if (target == null) continue
            s.aim = atan2(target.x - s.cx, target.y - s.cy)
            if (s.cooldown > 0f) continue
            s.cooldown = 1f / s.towerFireRate
            s.sinceShot = 0f
            // Artillery leads its target a little.
            val lead = if (def.homing) 0f else dist(s.cx, s.cy, target.x, target.y) / def.projectileSpeed
            val tx = target.x + (target.x - target.prevX) / DT * lead * 0.8f
            val ty = target.y + (target.y - target.prevY) / DT * lead * 0.8f
            projectiles += Projectile(
                nextId++, s.id, def.id, def.damageType, s.towerDamage, def.splash, def.homing, target.id,
                s.cx, s.cy, tx, ty, def.projectileSpeed, def.chillSeconds,
            )
            events += GameEvent.Shot(s.id, def.id)
        }
    }

    private fun moveProjectiles() {
        for (p in projectiles) {
            if (!p.alive) continue
            p.prevX = p.x
            p.prevY = p.y
            if (p.homing) {
                val t = enemies.firstOrNull { it.id == p.targetId && it.alive }
                if (t != null) {
                    p.tx = t.x
                    p.ty = t.y
                }
            }
            val dx = p.tx - p.x
            val dy = p.ty - p.y
            val len = sqrt(dx * dx + dy * dy)
            val stepLen = p.speed * DT
            if (len <= stepLen) {
                p.x = p.tx
                p.y = p.ty
                p.alive = false
                impact(p)
            } else {
                p.x += dx / len * stepLen
                p.y += dy / len * stepLen
            }
            val traveled = dist(p.startX, p.startY, p.x, p.y)
            p.progress = min(1f, traveled / p.totalDist)
        }
    }

    private fun impact(p: Projectile) {
        val owner = structures[p.ownerId]
        val troopOwner = if (owner == null) troops.firstOrNull { it.id == p.ownerId } else null
        if (p.splash > 0f) {
            for (e in enemies) if (e.alive && dist(e.x, e.y, p.x, p.y) <= p.splash) {
                e.slowTimer = max(e.slowTimer, p.chillSeconds)
                damage(e, p.damage, p.type, owner)
            }
            events += GameEvent.Hit(p.x, p.y, p.damage.toInt(), p.type, p.splash, p.visual)
        } else {
            val t = enemies.firstOrNull { it.id == p.targetId && it.alive } ?: return
            t.slowTimer = max(t.slowTimer, p.chillSeconds)
            val dealt = damage(t, p.damage, p.type, owner, troopOwner)
            events += GameEvent.Hit(t.x, t.y, dealt, p.type, 0f, p.visual)
        }
    }

    private fun updateVolleys() {
        val it = volleys.iterator()
        while (it.hasNext()) {
            val v = it.next()
            v.timer -= DT
            if (v.timer > 0f) continue
            for (e in enemies) if (e.alive && dist(e.x, e.y, v.x, v.y) <= Content.VOLLEY_RADIUS) damage(e, v.damage, DamageType.PIERCE, null)
            events += GameEvent.VolleyImpact(v.x, v.y)
            it.remove()
        }
    }

    private fun damage(e: Enemy, raw: Float, type: DamageType, owner: Structure?, troop: Troop? = null): Int {
        val dealt = raw * Content.damageMultiplier(type, e.def.armor)
        e.hp -= dealt
        if (e.hp <= 0f && e.alive) {
            e.alive = false
            if (e.assaultId != 0) assaultOutcome(e.assaultId, killed = true)
            res.gold += e.def.bounty
            events += GameEvent.EnemyDied(e.id, e.def.id, e.x, e.y, e.def.bounty)
            if (owner != null) {
                val before = owner.rank
                owner.xp += e.def.bounty * 3
                owner.kills++
                if (owner.rank > before) events += GameEvent.RankUp(owner.id, owner.rank)
            }
            if (troop != null) {
                troop.xp += e.def.bounty * 3
                troop.kills++
            }
        }
        return dealt.toInt()
    }

    /** Enemies the player is about to face, by type. */
    fun nextWavePreview(): List<Pair<String, Int>> =
        Content.waveComposition(wave) + (if (pendingRivalUnits > 0) listOf("rival" to pendingRivalUnits) else emptyList())

    fun drainEvents(): List<GameEvent> {
        val copy = ArrayList(events)
        events.clear()
        return copy
    }

    // ---------------------------------------------------------------- save / load

    fun toSave(): SaveData = SaveData(
        seed = seed,
        gold = res.gold, wood = res.wood, stone = res.stone,
        wave = wave, bestWave = bestWave,
        structures = structures.values.filter { it.kind != StructureKind.KEEP }.map {
            SavedStructure(it.defId, it.x, it.y, it.level, it.xp, it.kills, it.invested, it.specialization)
        },
        troops = troops.map { it.toData() },
    )

    /** Rebuilds the base from a save. Waves restart from the PREP phase (time is paused while offline). */
    fun loadFrom(save: SaveData) {
        require(save.seed == seed) { "Seed mismatch" }
        res.gold = save.gold
        res.wood = save.wood
        res.stone = save.stone
        wave = save.wave
        bestWave = save.bestWave
        for (ss in save.structures) {
            val i = idx(ss.x, ss.y)
            if (!inBounds(ss.x, ss.y) || blocked[i] || (!Content.isBuilding(ss.defId) && !Content.isTower(ss.defId))) continue
            val kind = when {
                Content.isBuilding(ss.defId) -> StructureKind.BUILDING
                Content.tower(ss.defId).isWall -> StructureKind.WALL
                else -> StructureKind.TOWER
            }
            val s = Structure(nextId++, kind, ss.defId, ss.x, ss.y)
            s.level = ss.level
            s.xp = ss.xp
            s.kills = ss.kills
            s.invested = ss.invested
            s.specialization = ss.specialization
            addStructure(s)
        }
        returnTroops(save.troops)
        recomputeFlow()
        phase = Phase.PREP
        countdown = WAVE_DELAY
    }
}

class MutableRes(start: Cost) {
    var gold = start.gold.toFloat()
    var wood = start.wood.toFloat()
    var stone = start.stone.toFloat()

    fun canAfford(c: Cost) = gold >= c.gold && wood >= c.wood && stone >= c.stone
    fun pay(c: Cost) {
        gold -= c.gold; wood -= c.wood; stone -= c.stone
    }
    fun add(c: Cost) {
        gold += c.gold; wood += c.wood; stone += c.stone
    }
    fun addScaled(c: Cost, f: Float) {
        gold += c.gold * f; wood += c.wood * f; stone += c.stone * f
    }
}

internal fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = ax - bx
    val dy = ay - by
    return sqrt(dx * dx + dy * dy)
}
