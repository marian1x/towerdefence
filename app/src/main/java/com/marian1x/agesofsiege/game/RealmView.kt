package com.marian1x.agesofsiege.game

import com.marian1x.agesofsiege.core.Campaign
import com.marian1x.agesofsiege.core.Expedition
import com.marian1x.agesofsiege.core.PoiKind
import com.marian1x.agesofsiege.core.Terrain
import com.marian1x.agesofsiege.core.World
import com.marian1x.agesofsiege.engine.Mesh
import com.marian1x.agesofsiege.engine.MeshBuilder
import com.marian1x.agesofsiege.engine.Model
import com.marian1x.agesofsiege.engine.ModelLibrary
import com.marian1x.agesofsiege.engine.Models
import com.marian1x.agesofsiege.engine.Palette
import com.marian1x.agesofsiege.engine.Renderer3D
import com.marian1x.agesofsiege.engine.V3
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The zoomed-out 3D realm: low-poly terrain generated from [World], forests, fog-of-war clouds,
 * landmarks for points of interest, both capitals and marching armies. Lives in [Renderer3D.realmScene].
 */
class RealmView(private val r3d: Renderer3D, private val models: ModelLibrary) {
    companion object {
        /** World units per realm cell. */
        const val CELL = 2f
        /** Cells of open sea drawn around the continent. */
        private const val MARGIN = 4
        /** Terrain vertices per cell edge. */
        private const val RES = 3
        const val WATER_Y = -0.2f
        const val MIN_DIST = 9f
        const val MAX_DIST = 80f

        fun cellCenter(i: Int) = i * CELL + CELL / 2
        val widthUnits get() = World.W * CELL
        val heightUnits get() = World.H * CELL

        private fun hash(a: Int, b: Int, salt: Int = 0): Float {
            var h = a * 374761393 + b * 668265263 + salt * 1274126177
            h = (h xor (h ushr 13)) * 1274126177
            h = h xor (h ushr 16)
            return (h and 0xffff) / 65535f // 0..1
        }
    }

    private var builtSeed: Long? = null
    private val gx = (World.W + 2 * MARGIN) * RES + 1
    private val gz = (World.H + 2 * MARGIN) * RES + 1
    private val heights = FloatArray(gx * gz)

    private val owned = ArrayList<Pair<Int, Mesh>>() // static entities + meshes, freed on rebuild
    private var fogEntity = 0
    private var fogMesh: Mesh? = null
    private var fogExplored = -1
    private var fogTimer = 0f
    private val fogMi = r3d.litInstance(0xFFFFFF)

    private var pathEntity = 0
    private var pathMesh: Mesh? = null
    private var pathKey = ""
    private val pathMi = r3d.litInstance(0xFFFFFF, r3d.glowMaterial)

    private val poiModels = HashMap<Int, Model>()
    private var homeModel: Model? = null
    private var allyModel: Model? = null

    private class Army(val models: List<Model>) {
        var t = Random.nextFloat() * 2f
    }
    private val armies = HashMap<Int, Army>()

    private val selMi = r3d.litInstance(0xFFFFFF, r3d.ghostMaterial, 0.75f)
    private val selEntity = r3d.createRenderable(r3d.upload(MeshBuilder().apply { Models.ringMesh(this) }), selMi, shadows = false)
    private var selShown = false
    private var time = 0f

    // ------------------------------------------------------------------ terrain sampling

    private fun cellBaseHeight(x: Int, y: Int, world: World): Float {
        if (!world.inBounds(x, y)) {
            val d = max(max(-x, x - (World.W - 1)), max(-y, y - (World.H - 1))).toFloat()
            return -0.55f - 0.25f * d
        }
        return when (world.terrainAt(x, y)) {
            Terrain.WATER -> -0.6f
            Terrain.GRASS -> 0.05f
            Terrain.FOREST -> 0.14f
            Terrain.HILLS -> 0.62f
            Terrain.MOUNTAIN -> 2.1f
        }
    }

    private fun cellRoughness(x: Int, y: Int, world: World): Float {
        if (!world.inBounds(x, y)) return 0.05f
        return when (world.terrainAt(x, y)) {
            Terrain.WATER -> 0.08f
            Terrain.GRASS -> 0.07f
            Terrain.FOREST -> 0.1f
            Terrain.HILLS -> 0.22f
            Terrain.MOUNTAIN -> 0.55f
        }
    }

    /** Bilinear blend of a per-cell value at world position (cell centers are the sample points). */
    private inline fun blend(wx: Float, wz: Float, f: (Int, Int) -> Float): Float {
        val fx = wx / CELL - 0.5f
        val fy = wz / CELL - 0.5f
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val tx = fx - x0
        val ty = fy - y0
        val a = f(x0, y0) * (1 - tx) + f(x0 + 1, y0) * tx
        val b = f(x0, y0 + 1) * (1 - tx) + f(x0 + 1, y0 + 1) * tx
        return a * (1 - ty) + b * ty
    }

    /** Terrain height at a world position (bilinear over the vertex grid). */
    fun heightAt(wx: Float, wz: Float): Float {
        val fi = (wx / CELL + MARGIN) * RES
        val fj = (wz / CELL + MARGIN) * RES
        val i0 = floor(fi).toInt().coerceIn(0, gx - 2)
        val j0 = floor(fj).toInt().coerceIn(0, gz - 2)
        val tx = (fi - i0).coerceIn(0f, 1f)
        val tz = (fj - j0).coerceIn(0f, 1f)
        val a = heights[j0 * gx + i0] * (1 - tx) + heights[j0 * gx + i0 + 1] * tx
        val b = heights[(j0 + 1) * gx + i0] * (1 - tx) + heights[(j0 + 1) * gx + i0 + 1] * tx
        return max(WATER_Y, a * (1 - tz) + b * tz)
    }

    /** World position -> realm cell, or null outside the map. */
    fun cellAt(wx: Float, wz: Float): Pair<Int, Int>? {
        val x = floor(wx / CELL).toInt()
        val y = floor(wz / CELL).toInt()
        return if (x in 0 until World.W && y in 0 until World.H) x to y else null
    }

    // ------------------------------------------------------------------ building

    /** (Re)builds the static realm when the campaign (world seed) changes. */
    fun ensureBuilt(camp: Campaign) {
        if (builtSeed == camp.world.seed) return
        clear()
        builtSeed = camp.world.seed
        val world = camp.world
        buildHeights(world)
        addStatic(buildTerrain(world), r3d.litInstance(0xFFFFFF), shadows = false)
        addStatic(buildScenery(world), r3d.litInstance(0xFFFFFF), shadows = true)
        addStatic(buildWater(), r3d.waterMaterial.createInstance(), shadows = false, y = WATER_Y)
        fogExplored = -1
    }

    private fun addStatic(b: MeshBuilder, mi: com.google.android.filament.MaterialInstance, shadows: Boolean, y: Float = 0f) {
        val mesh = r3d.upload(b)
        val e = r3d.createRenderable(mesh, mi, shadows)
        r3d.setTransform(e, 0f, y, 0f)
        r3d.realmScene.addEntity(e)
        owned += e to mesh
    }

    private fun clear() {
        for ((e, m) in owned) {
            r3d.destroyEntity(e)
            r3d.destroyMesh(m)
        }
        owned.clear()
        if (fogEntity != 0) { r3d.destroyEntity(fogEntity); fogEntity = 0 }
        fogMesh?.let { r3d.destroyMesh(it) }; fogMesh = null
        if (pathEntity != 0) { r3d.destroyEntity(pathEntity); pathEntity = 0 }
        pathMesh?.let { r3d.destroyMesh(it) }; pathMesh = null
        pathKey = ""
        for (m in poiModels.values) models.release(m)
        poiModels.clear()
        homeModel?.let { models.release(it) }; homeModel = null
        allyModel?.let { models.release(it) }; allyModel = null
        for (a in armies.values) a.models.forEach { models.release(it) }
        armies.clear()
    }

    private fun buildHeights(world: World) {
        for (j in 0 until gz) for (i in 0 until gx) {
            val wx = (i.toFloat() / RES - MARGIN) * CELL
            val wz = (j.toFloat() / RES - MARGIN) * CELL
            val base = blend(wx, wz) { x, y -> cellBaseHeight(x, y, world) }
            val rough = blend(wx, wz) { x, y -> cellRoughness(x, y, world) }
            val n = hash(i, j, 3) * 2f - 1f
            heights[j * gx + i] = base + n * rough
        }
    }

    private fun terrainColor(world: World, wx: Float, wz: Float, h: Float, ny: Float, i: Int, j: Int): Int {
        val cx = floor(wx / CELL).toInt()
        val cy = floor(wz / CELL).toInt()
        val t = if (world.inBounds(cx, cy)) world.terrainAt(cx, cy) else Terrain.WATER
        val base = when {
            h < -0.3f -> 0xB8A878
            h < 0.0f -> 0xE2D196
            ny < 0.72f && t != Terrain.WATER -> if (h > 1.5f) 0xD9DDE3 else 0x8C877F
            t == Terrain.MOUNTAIN -> if (h > 1.55f) 0xF0F3F6 else 0x9A958C
            t == Terrain.HILLS -> 0xA7A866
            t == Terrain.FOREST -> 0x5B8C43
            t == Terrain.WATER -> 0xE2D196
            else -> 0x86B95A
        }
        return MeshBuilder.shade(base, 0.93f + hash(i, j, 9) * 0.12f)
    }

    private fun buildTerrain(world: World): MeshBuilder {
        val b = MeshBuilder()
        fun pos(i: Int, j: Int) = V3((i.toFloat() / RES - MARGIN) * CELL, heights[j * gx + i], (j.toFloat() / RES - MARGIN) * CELL)
        for (j in 0 until gz - 1) for (i in 0 until gx - 1) {
            val p00 = pos(i, j); val p10 = pos(i + 1, j); val p01 = pos(i, j + 1); val p11 = pos(i + 1, j + 1)
            val flip = (i + j) % 2 == 0
            val tris = if (flip) arrayOf(arrayOf(p00, p01, p11), arrayOf(p00, p11, p10)) else arrayOf(arrayOf(p00, p01, p10), arrayOf(p10, p01, p11))
            for ((k, t) in tris.withIndex()) {
                val (a, bb, c) = t
                val ux = bb.x - a.x; val uy = bb.y - a.y; val uz = bb.z - a.z
                val vx = c.x - a.x; val vy = c.y - a.y; val vz = c.z - a.z
                val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
                val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
                val h = (a.y + bb.y + c.y) / 3f
                val color = terrainColor(world, (a.x + bb.x + c.x) / 3f, (a.z + bb.z + c.z) / 3f, h, kotlin.math.abs(ny / len), i * 2 + k, j)
                b.tri(a.x, a.y, a.z, bb.x, bb.y, bb.z, c.x, c.y, c.z, color)
            }
        }
        return b
    }

    private fun buildWater(): MeshBuilder = MeshBuilder().apply {
        val x0 = -MARGIN * CELL - 30f
        val z0 = -MARGIN * CELL - 30f
        val w = (World.W + 2 * MARGIN) * CELL + 60f
        val d = (World.H + 2 * MARGIN) * CELL + 60f
        val n = 32
        for (i in 0 until n) for (k in 0 until n) {
            val ax = x0 + w * i / n; val bx = x0 + w * (i + 1) / n
            val az = z0 + d * k / n; val bz = z0 + d * (k + 1) / n
            quad(V3(ax, 0f, bz), V3(bx, 0f, bz), V3(bx, 0f, az), V3(ax, 0f, az), Palette.WHITE)
        }
    }

    /** Forests, lone trees, bushes and mountain rocks – one merged mesh. */
    private fun buildScenery(world: World): MeshBuilder {
        val b = MeshBuilder()
        val rnd = Random(world.seed)
        val blocked = HashSet<Int>()
        for (p in world.pois) blocked += world.idx(p.x, p.y)
        for (h in listOfNotNull(world.home, world.allyHome, world.rivalHome)) for (dy in -1..1) for (dx in -1..1) {
            val hx = h.first + dx; val hy = h.second + dy
            if (world.inBounds(hx, hy)) blocked += world.idx(hx, hy)
        }
        for (y in 0 until World.H) for (x in 0 until World.W) {
            if (world.idx(x, y) in blocked) continue
            val t = world.terrainAt(x, y)
            val trees = when (t) {
                Terrain.FOREST -> 5 + rnd.nextInt(3)
                Terrain.GRASS -> if (rnd.nextFloat() < 0.12f) 1 + rnd.nextInt(2) else 0
                Terrain.HILLS -> if (rnd.nextFloat() < 0.3f) 1 else 0
                else -> 0
            }
            repeat(trees) {
                val px = x * CELL + 0.25f + rnd.nextFloat() * (CELL - 0.5f)
                val pz = y * CELL + 0.25f + rnd.nextFloat() * (CELL - 0.5f)
                tree(b, px, heightAt(px, pz) - 0.03f, pz, 0.75f + rnd.nextFloat() * 0.45f, rnd)
            }
            if (t == Terrain.HILLS && rnd.nextFloat() < 0.5f) {
                val px = x * CELL + 0.4f + rnd.nextFloat() * (CELL - 0.8f)
                val pz = y * CELL + 0.4f + rnd.nextFloat() * (CELL - 0.8f)
                b.push { translate(px, heightAt(px, pz), pz); ico(0.16f, 0x5E8A3E, 0, 0.25f, rnd.nextInt(), 0.7f, 0.05f) }
            }
            if (t == Terrain.MOUNTAIN || (t == Terrain.HILLS && rnd.nextFloat() < 0.25f)) {
                repeat(1 + rnd.nextInt(2)) {
                    val px = x * CELL + 0.3f + rnd.nextFloat() * (CELL - 0.6f)
                    val pz = y * CELL + 0.3f + rnd.nextFloat() * (CELL - 0.6f)
                    val r = 0.14f + rnd.nextFloat() * 0.18f
                    b.push { translate(px, heightAt(px, pz), pz); ico(r, 0x8F8A84, 0, 0.3f, rnd.nextInt(), 0.75f, r * 0.3f) }
                }
            }
        }
        return b
    }

    private fun tree(b: MeshBuilder, x: Float, y: Float, z: Float, s: Float, rnd: Random) {
        b.push {
            translate(x, y, z)
            rotateY(rnd.nextFloat() * 6.28f)
            cone(4, 0.07f * s, 0f, 0.32f * s, 0x6B4A2E)
            if (rnd.nextFloat() < 0.7f) {
                val g = if (rnd.nextBoolean()) 0x3F6E36 else 0x4A7D3D
                prism(5, 0.34f * s, 0f, 0.18f * s, 0.78f * s, g, g, capBottom = false)
                prism(5, 0.25f * s, 0f, 0.5f * s, 1.05f * s, MeshBuilder.shade(g, 1.08f), g, capBottom = false)
            } else {
                ico(0.3f * s, if (rnd.nextBoolean()) 0x5E9A3F else 0x6FA645, 0, 0.18f, rnd.nextInt(), 0.95f, 0.55f * s)
            }
        }
    }

    // ------------------------------------------------------------------ per frame

    fun update(camp: Campaign, dt: Float, selected: Pair<Int, Int>?) {
        ensureBuilt(camp)
        time += dt
        val world = camp.world
        updateFog(world, dt)
        updateLandmarks(camp)
        updateArmies(camp, dt)
        updatePaths(camp)
        val sel = selected
        if (sel != null) {
            val cx = cellCenter(sel.first)
            val cz = cellCenter(sel.second)
            val pulse = 1f + 0.06f * sin(time * 5f)
            r3d.setTransform(selEntity, cx, heightAt(cx, cz) + 0.08f, cz, scale = CELL * 0.62f * pulse)
        }
        val show = sel != null
        if (show && !selShown) r3d.realmScene.addEntity(selEntity)
        if (!show && selShown) r3d.realmScene.remove(selEntity)
        selShown = show
    }

    /** Clouds over unexplored land; rebuilt (throttled) when exploration changes. */
    private fun updateFog(world: World, dt: Float) {
        fogTimer -= dt
        val explored = world.explored.count { it }
        if (explored == fogExplored || (fogExplored >= 0 && fogTimer > 0f)) return
        fogExplored = explored
        fogTimer = 1.2f
        // A rolling "sea of clouds": one sheet whose height follows how unexplored the land below is.
        // Over explored land it sinks under the terrain, so its edge forms a soft cloud bank.
        val res = 2
        val nx = World.W * res + 1
        val nz = World.H * res + 1
        val u = FloatArray(nx * nz)
        val hy = FloatArray(nx * nz)
        for (j in 0 until nz) for (i in 0 until nx) {
            val wx = i.toFloat() / res * CELL
            val wz = j.toFloat() / res * CELL
            val v = blend(wx, wz) { x, y ->
                val cx = x.coerceIn(0, World.W - 1)
                val cy = y.coerceIn(0, World.H - 1)
                if (world.isExplored(cx, cy)) 0f else 1f
            }
            u[j * nx + i] = v
            val bump = (hash(i, j, 51) - 0.5f) * 0.5f + sin(i * 0.9f + j * 0.4f) * 0.18f
            hy[j * nx + i] = if (v < 0.3f) -3f else 1.1f + (v - 0.3f) / 0.7f * 1.6f + bump * v
        }
        val b = MeshBuilder()
        fun p(i: Int, j: Int) = V3(i.toFloat() / res * CELL, hy[j * nx + i], j.toFloat() / res * CELL)
        for (j in 0 until nz - 1) for (i in 0 until nx - 1) {
            val k00 = j * nx + i; val k10 = k00 + 1; val k01 = k00 + nx; val k11 = k01 + 1
            if (u[k00] < 0.3f && u[k10] < 0.3f && u[k01] < 0.3f && u[k11] < 0.3f) continue
            val shade = 0.94f + hash(i, j, 61) * 0.06f
            val col = MeshBuilder.shade(0xF7F9FC, shade)
            val a = p(i, j); val bb = p(i + 1, j); val c = p(i, j + 1); val d = p(i + 1, j + 1)
            b.tri(a.x, a.y, a.z, c.x, c.y, c.z, d.x, d.y, d.z, col)
            b.tri(a.x, a.y, a.z, d.x, d.y, d.z, bb.x, bb.y, bb.z, MeshBuilder.shade(col, 0.985f))
        }
        if (fogEntity != 0) r3d.destroyEntity(fogEntity)
        fogMesh?.let { r3d.destroyMesh(it) }
        fogEntity = 0
        fogMesh = null
        if (b.vertexCount == 0) return
        val mesh = r3d.upload(b)
        fogMesh = mesh
        fogEntity = r3d.createRenderable(mesh, fogMi, shadows = false)
        r3d.setTransform(fogEntity, 0f, 0f, 0f)
        r3d.realmScene.addEntity(fogEntity)
    }

    private fun landmarkModel(kind: PoiKind) = when (kind) {
        PoiKind.CACHE -> "realm_cache" to 2.4f
        PoiKind.CAMP -> "realm_camp" to 2.6f
        PoiKind.RUINS -> "realm_ruins" to 2.2f
        PoiKind.RIVAL_CAPITAL -> "realm_rival_castle" to 1.05f
    }

    private fun updateLandmarks(camp: Campaign) {
        val world = camp.world
        if (homeModel == null) {
            homeModel = models.acquire("keep", 1, r3d.realmScene).also {
                val cx = cellCenter(world.home.first)
                val cz = cellCenter(world.home.second)
                r3d.setTransform(it.root, cx, heightAt(cx, cz), cz, scale = 1.05f)
            }
        }
        val ally = world.allyHome
        if (ally != null && allyModel == null) {
            allyModel = models.acquire("keep", 1, r3d.realmScene).also {
                val cx = cellCenter(ally.first)
                val cz = cellCenter(ally.second)
                r3d.setTransform(it.root, cx, heightAt(cx, cz), cz, yaw = 0.4f, scale = 0.95f)
            }
        }
        for (p in world.pois) {
            val visible = (world.isExplored(p.x, p.y) || (p.kind == PoiKind.RIVAL_CAPITAL && camp.rival.contact)) &&
                !(p.cleared && (p.kind == PoiKind.CACHE || p.kind == PoiKind.CAMP))
            val m = poiModels[p.id]
            if (visible && m == null) {
                val (name, scale) = landmarkModel(p.kind)
                poiModels[p.id] = models.acquire(name, 4, r3d.realmScene).also {
                    val cx = cellCenter(p.x)
                    val cz = cellCenter(p.y)
                    val yaw = if (p.kind == PoiKind.RIVAL_CAPITAL) Math.PI.toFloat() * 0.5f else hash(p.id, 7) * 6.28f
                    r3d.setTransform(it.root, cx, heightAt(cx, cz), cz, yaw = yaw, scale = scale)
                }
            } else if (!visible && m != null) {
                models.release(m)
                poiModels.remove(p.id)
            }
        }
    }

    /** Point along an expedition's path in world units. */
    private fun pathPoint(e: Expedition, progress: Float): FloatArray {
        val i0 = progress.toInt().coerceIn(0, e.path.size - 1)
        val i1 = (i0 + 1).coerceAtMost(e.path.size - 1)
        val f = (progress - i0).coerceIn(0f, 1f)
        val a = e.path[i0]
        val b = e.path[i1]
        val x = cellCenter(a % World.W) * (1 - f) + cellCenter(b % World.W) * f
        val z = cellCenter(a / World.W) * (1 - f) + cellCenter(b / World.W) * f
        return floatArrayOf(x, z)
    }

    private fun soldierModel(defId: String) = when (defId) {
        "bowman" -> "archer_crew"
        "knight" -> "warlord"
        else -> "swordsman"
    }

    private fun updateArmies(camp: Campaign, dt: Float) {
        val alive = HashSet<Int>()
        for (e in camp.expeditions) {
            alive += e.id
            val army = armies.getOrPut(e.id) {
                val enemy = camp.mp?.let { it.mode == com.marian1x.agesofsiege.core.MatchMode.VERSUS && e.owner != it.mySlot } == true
                val defs = e.troops.take(3).map { if (enemy) "rival" else soldierModel(it.defId) }.ifEmpty { listOf(if (enemy) "rival" else "swordsman") }
                Army(defs.map { models.acquire(it, 4, r3d.realmScene) })
            }
            army.t += dt
            val here = pathPoint(e, e.progress)
            val ahead = pathPoint(e, min(e.path.size - 1f, e.progress + 0.3f))
            var dx = ahead[0] - here[0]
            var dz = ahead[1] - here[1]
            val len = sqrt(dx * dx + dz * dz)
            val moving = len > 1e-3f
            if (moving) { dx /= len; dz /= len } else { dx = 0f; dz = 1f }
            val yaw = atan2(dx, dz)
            // Enemy armies stay hidden under your fog of war.
            val view = camp.mp
            val hereCell = e.path[e.progress.toInt().coerceIn(0, e.path.size - 1)]
            val hidden = view != null && e.owner != view.mySlot && view.mode == com.marian1x.agesofsiege.core.MatchMode.VERSUS &&
                !camp.world.isExplored(hereCell % World.W, hereCell / World.W)
            army.models.forEachIndexed { k, m ->
                if (hidden) {
                    r3d.setTransform(m.root, here[0], -20f, here[1])
                    return@forEachIndexed
                }
                // Small column: leader in front, two followers behind and to the sides.
                val back = if (k == 0) 0f else 0.45f
                val side = when (k) { 1 -> -0.3f; 2 -> 0.3f; else -> 0f }
                val x = here[0] - dx * back + dz * side
                val z = here[1] - dz * back - dx * side
                r3d.setTransform(m.root, x, heightAt(x, z), z, yaw = yaw, scale = 0.85f)
                m.animate(if (moving) "walk" else "idle", army.t + k * 0.21f)
            }
        }
        val it = armies.entries.iterator()
        while (it.hasNext()) {
            val (id, a) = it.next()
            if (id !in alive) {
                a.models.forEach { models.release(it) }
                it.remove()
            }
        }
    }

    /** Dotted route from each army to its destination. */
    private fun updatePaths(camp: Campaign) {
        val key = camp.expeditions.joinToString("|") { "${it.id}:${it.returning}:${(it.progress * 2).toInt()}" }
        if (key == pathKey) return
        pathKey = key
        if (pathEntity != 0) r3d.destroyEntity(pathEntity)
        pathMesh?.let { r3d.destroyMesh(it) }
        pathEntity = 0
        pathMesh = null
        if (camp.expeditions.isEmpty()) return
        val b = MeshBuilder()
        val view = camp.mp
        for (e in camp.expeditions) {
            val mine = view == null || e.owner == view.mySlot
            val color = when {
                !mine && view?.mode == com.marian1x.agesofsiege.core.MatchMode.VERSUS -> continue
                !mine -> 0x7FB8FF
                e.returning -> 0x7CFC9A
                else -> 0xFFC94D
            }
            var p = e.progress
            val end = e.path.size - 1f
            while (p < end) {
                val pt = pathPoint(e, p)
                b.push { translate(pt[0], heightAt(pt[0], pt[1]) + 0.06f, pt[1]); disc(0.11f, 0f, 8, color) }
                p += 0.28f
            }
            val last = pathPoint(e, end)
            b.push { translate(last[0], heightAt(last[0], last[1]) + 0.07f, last[1]); ring(0.55f, 0.68f, 0f, 24, color) }
        }
        if (b.vertexCount == 0) return
        val mesh = r3d.upload(b)
        pathMesh = mesh
        pathEntity = r3d.createRenderable(mesh, pathMi, shadows = false)
        r3d.setTransform(pathEntity, 0f, 0f, 0f)
        r3d.realmScene.addEntity(pathEntity)
    }

    /** World position (x, y, z) of a cell's landmark, for screen labels. */
    fun labelAnchor(x: Int, y: Int, lift: Float): FloatArray {
        val cx = cellCenter(x)
        val cz = cellCenter(y)
        return floatArrayOf(cx, heightAt(cx, cz) + lift, cz)
    }

    /** Current world position of an army (for labels). */
    fun armyAnchor(e: Expedition): FloatArray {
        val p = pathPoint(e, e.progress)
        return floatArrayOf(p[0], heightAt(p[0], p[1]) + 1.2f, p[1])
    }
}
