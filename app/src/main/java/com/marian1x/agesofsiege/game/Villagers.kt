package com.marian1x.agesofsiege.game

import com.marian1x.agesofsiege.core.BaseGame
import com.marian1x.agesofsiege.core.Phase
import com.marian1x.agesofsiege.core.Structure
import com.marian1x.agesofsiege.core.StructureKind
import com.marian1x.agesofsiege.engine.Model
import com.marian1x.agesofsiege.engine.ModelLibrary
import com.marian1x.agesofsiege.engine.Renderer3D
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Purely visual town life: villagers walk between the keep and the houses / lumber mills / quarries,
 * pause to "work", and hurry back inside the keep when a wave attacks. Not part of the simulation.
 */
class Villagers(private val r3d: Renderer3D, private val models: ModelLibrary) {
    private class V(val model: Model, val scale: Float) {
        var x = 0f
        var z = 0f
        var tx = 0f
        var tz = 0f
        var wait = 0f
        var yaw = 0f
        var t = Random.nextFloat() * 2f
        var home = true
        var hidden = false
    }

    private val list = ArrayList<V>()
    private val rnd = Random(5)

    private fun workplaces(g: BaseGame): List<Structure> =
        g.structures.values.filter { it.kind == StructureKind.BUILDING && it.defId != "barracks" }

    private fun door(keep: Structure) = floatArrayOf(keep.cx - keep.w / 2f - 0.2f, keep.cy + 0.3f)

    fun update(g: BaseGame, dt: Float) {
        val keep = g.structures.values.firstOrNull { it.kind == StructureKind.KEEP } ?: return
        val work = workplaces(g)
        val wanted = (2 + work.size).coerceAtMost(9)
        while (list.size < wanted) {
            val name = if (list.size % 3 == 1) "villager_f" else "villager"
            val v = V(models.acquire(name, 4), 0.55f)
            val d = door(keep)
            v.x = d[0]; v.z = d[1]; v.tx = v.x; v.tz = v.z
            v.wait = rnd.nextFloat() * 4f
            list += v
        }
        while (list.size > wanted) models.release(list.removeAt(list.size - 1).model)

        val danger = g.phase == Phase.ACTIVE
        val d = door(keep)
        for (v in list) {
            v.t += dt
            if (danger) {
                // Everyone runs for the keep during an attack.
                v.tx = d[0]; v.tz = d[1]; v.wait = 0f
            } else if (v.wait > 0f) {
                v.wait -= dt
                if (v.wait <= 0f) {
                    if (v.home && work.isNotEmpty()) {
                        val s = work[rnd.nextInt(work.size)]
                        v.tx = s.cx + (rnd.nextFloat() - 0.5f) * 0.6f + 0.6f
                        v.tz = s.cy + (rnd.nextFloat() - 0.5f) * 0.6f + 0.6f
                        v.home = false
                    } else {
                        v.tx = d[0] + (rnd.nextFloat() - 0.5f) * 0.4f
                        v.tz = d[1] + (rnd.nextFloat() - 0.5f) * 0.4f
                        v.home = true
                    }
                }
            }
            val dx = v.tx - v.x
            val dz = v.tz - v.z
            val dist = sqrt(dx * dx + dz * dz)
            val speed = if (danger) 1.8f else 0.8f
            val moving = dist > 0.05f
            if (moving) {
                val step = min(dist, speed * dt)
                v.x += dx / dist * step
                v.z += dz / dist * step
                var dy = atan2(dx, dz) - v.yaw
                while (dy > Math.PI) dy -= (2 * Math.PI).toFloat()
                while (dy < -Math.PI) dy += (2 * Math.PI).toFloat()
                v.yaw += dy * min(1f, dt * 8f)
            } else if (v.wait <= 0f && !danger) {
                v.wait = 2f + rnd.nextFloat() * 5f
            }
            // Inside the keep (at the door during danger) villagers disappear.
            val atDoor = sqrt((v.x - d[0]) * (v.x - d[0]) + (v.z - d[1]) * (v.z - d[1])) < 0.25f
            val hide = atDoor && (danger || v.wait > 0f)
            if (hide != v.hidden) {
                if (hide) r3d.scene.removeEntities(v.model.instance.entities) else r3d.scene.addEntities(v.model.instance.entities)
                v.hidden = hide
            }
            if (!v.hidden) {
                r3d.setTransform(v.model.root, v.x, 0f, v.z, yaw = v.yaw, scale = v.scale)
                v.model.animate(if (moving) (if (danger) "sprint" else "walk") else "idle", v.t)
            }
        }
    }

    fun clear() {
        for (v in list) {
            if (v.hidden) r3d.scene.addEntities(v.model.instance.entities)
            models.release(v.model)
        }
        list.clear()
    }
}
