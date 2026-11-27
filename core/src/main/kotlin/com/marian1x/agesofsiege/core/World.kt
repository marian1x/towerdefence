package com.marian1x.agesofsiege.core

import kotlinx.serialization.Serializable
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

enum class Terrain(val cost: Int, val passable: Boolean) {
    GRASS(10, true), FOREST(16, true), HILLS(18, true), MOUNTAIN(0, false), WATER(0, false)
}

enum class PoiKind(val label: String) {
    CACHE("Supply cache"), CAMP("Marauder camp"), RUINS("Ancient ruins"), RIVAL_CAPITAL("Rival capital")
}

@Serializable
data class Poi(
    val id: Int,
    val kind: PoiKind,
    var x: Int,
    var y: Int,
    var power: Float,
    var loot: Cost,
    var cleared: Boolean = false,
)

/**
 * The strategic world map: procedural terrain (same seed = same world), fog of war and points of interest.
 * Cells are much bigger than base tiles – one cell is a whole region.
 */
class World(val seed: Long) {
    companion object {
        const val W = 40
        const val H = 26
    }

    val terrain = Array(W * H) { Terrain.GRASS }
    val explored = BooleanArray(W * H)
    val pois = ArrayList<Poi>()
    /** Your capital. In multiplayer each phone points this at its own player's capital. */
    var home = 3 to H / 2
    /** The other capital (AI rival, or the opponent in Versus). */
    var rivalHome = W - 4 to H / 2
    /** Co-op: the ally's capital, or null. */
    var allyHome: Pair<Int, Int>? = null

    private val rng = Rng(seed)

    init {
        generateTerrain()
        ensureConnected()
        placePois()
        reveal(home.first, home.second, 5)
    }

    fun idx(x: Int, y: Int) = y * W + x
    fun inBounds(x: Int, y: Int) = x in 0 until W && y in 0 until H
    fun terrainAt(x: Int, y: Int) = terrain[idx(x, y)]
    fun isExplored(x: Int, y: Int) = inBounds(x, y) && explored[idx(x, y)]
    fun poiAt(x: Int, y: Int) = pois.firstOrNull { it.x == x && it.y == y }
    fun rivalPoi() = pois.first { it.kind == PoiKind.RIVAL_CAPITAL }

    fun distanceFromHome(x: Int, y: Int): Float {
        val dx = (x - home.first).toFloat()
        val dy = (y - home.second).toFloat()
        return sqrt(dx * dx + dy * dy)
    }

    fun reveal(cx: Int, cy: Int, r: Int) {
        for (y in cy - r..cy + r) for (x in cx - r..cx + r) {
            if (!inBounds(x, y)) continue
            val dx = x - cx
            val dy = y - cy
            if (dx * dx + dy * dy <= r * r + r) explored[idx(x, y)] = true
        }
    }

    // -------------------------------------------------------------- generation

    private fun lattice(step: Int): (Int, Int) -> Float {
        val gw = W / step + 2
        val gh = H / step + 2
        val v = FloatArray(gw * gh) { rng.nextFloat() }
        return { x, y ->
            val fx = x.toFloat() / step
            val fy = y.toFloat() / step
            val x0 = floor(fx).toInt()
            val y0 = floor(fy).toInt()
            val tx = fx - x0
            val ty = fy - y0
            fun at(i: Int, j: Int) = v[(j.coerceIn(0, gh - 1)) * gw + i.coerceIn(0, gw - 1)]
            val a = at(x0, y0) * (1 - tx) + at(x0 + 1, y0) * tx
            val b = at(x0, y0 + 1) * (1 - tx) + at(x0 + 1, y0 + 1) * tx
            a * (1 - ty) + b * ty
        }
    }

    private fun generateTerrain() {
        val e1 = lattice(6)
        val e2 = lattice(3)
        val m1 = lattice(5)
        for (y in 0 until H) for (x in 0 until W) {
            val e = e1(x, y) * 0.7f + e2(x, y) * 0.3f
            val m = m1(x, y)
            terrain[idx(x, y)] = when {
                e > 0.74f -> Terrain.MOUNTAIN
                e < 0.2f -> Terrain.WATER
                e > 0.62f -> Terrain.HILLS
                m > 0.58f -> Terrain.FOREST
                else -> Terrain.GRASS
            }
        }
        for ((hx, hy) in listOf(home, rivalHome)) {
            for (y in hy - 2..hy + 2) for (x in hx - 2..hx + 2) if (inBounds(x, y)) terrain[idx(x, y)] = Terrain.GRASS
        }
    }

    /** Guarantees the two capitals are connected by carving a road if needed. */
    private fun ensureConnected() {
        if (path(home.first, home.second, rivalHome.first, rivalHome.second) != null) return
        var x = home.first
        var y = home.second
        while (x != rivalHome.first || y != rivalHome.second) {
            if (x != rivalHome.first) x += if (rivalHome.first > x) 1 else -1
            if (y != rivalHome.second && rng.nextFloat() < 0.4f) y += if (rivalHome.second > y) 1 else -1
            if (!terrain[idx(x, y)].passable) terrain[idx(x, y)] = Terrain.GRASS
        }
    }

    private fun placePois() {
        var id = 1
        pois += Poi(id++, PoiKind.RIVAL_CAPITAL, rivalHome.first, rivalHome.second, power = 160f, loot = Cost())
        val wanted = listOf(PoiKind.CACHE to 14, PoiKind.CAMP to 10, PoiKind.RUINS to 5)
        for ((kind, count) in wanted) {
            var placed = 0
            var tries = 0
            while (placed < count && tries < 2000) {
                tries++
                val x = 1 + rng.nextInt(W - 2)
                val y = 1 + rng.nextInt(H - 2)
                if (!terrain[idx(x, y)].passable) continue
                if (abs(x - home.first) + abs(y - home.second) < 3) continue
                if (abs(x - rivalHome.first) + abs(y - rivalHome.second) < 4) continue
                if (pois.any { abs(it.x - x) + abs(it.y - y) < 3 }) continue
                if (path(home.first, home.second, x, y) == null) continue
                val d = distanceFromHome(x, y)
                val jitter = rng.range(0.8f, 1.2f)
                val poi = when (kind) {
                    PoiKind.CACHE -> Poi(id++, kind, x, y, 0f, Cost((25 + d * 4).toInt(), (20 + d * 3).toInt(), (10 + d * 2).toInt()) * jitter)
                    PoiKind.CAMP -> Poi(id++, kind, x, y, (35f + d * 9f) * jitter, Cost((40 + d * 8).toInt(), (15 + d * 3).toInt(), (10 + d * 3).toInt()))
                    PoiKind.RUINS -> Poi(id++, kind, x, y, (25f + d * 6f) * jitter, Cost((90 + d * 10).toInt(), 0, (30 + d * 4).toInt()))
                    PoiKind.RIVAL_CAPITAL -> error("unreachable")
                }
                pois += poi
                placed++
            }
        }
    }

    // -------------------------------------------------------------- pathfinding

    /** A* over passable cells (8 directions). Returns cell indices from start to goal, or null. */
    fun path(sx: Int, sy: Int, gx: Int, gy: Int): List<Int>? {
        if (!inBounds(gx, gy) || !terrain[idx(gx, gy)].passable) return null
        val cost = IntArray(W * H) { Int.MAX_VALUE }
        val from = IntArray(W * H) { -1 }
        val start = idx(sx, sy)
        val goal = idx(gx, gy)
        cost[start] = 0
        val open = PriorityQueue<Long>()
        open.add(start.toLong())
        while (open.isNotEmpty()) {
            val cur = (open.poll() and 0xffffffffL).toInt()
            if (cur == goal) break
            val cx = cur % W
            val cy = cur / W
            for (k in 0 until 8) {
                val nx = cx + FlowField.DX[k]
                val ny = cy + FlowField.DY[k]
                if (!inBounds(nx, ny)) continue
                val n = idx(nx, ny)
                val t = terrain[n]
                if (!t.passable) continue
                val step = if (k < 4) t.cost else t.cost * 14 / 10
                val nc = cost[cur] + step
                if (nc < cost[n]) {
                    cost[n] = nc
                    from[n] = cur
                    val h = max(abs(nx - gx), abs(ny - gy)) * 10
                    open.add(((nc + h).toLong() shl 32) or n.toLong())
                }
            }
        }
        if (cost[goal] == Int.MAX_VALUE) return null
        val out = ArrayList<Int>()
        var c = goal
        while (c != -1) {
            out += c
            c = from[c]
        }
        out.reverse()
        return out
    }
}
