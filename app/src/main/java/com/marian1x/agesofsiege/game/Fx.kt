package com.marian1x.agesofsiege.game

import com.google.android.filament.MaterialInstance
import com.marian1x.agesofsiege.engine.Mesh
import com.marian1x.agesofsiege.engine.Renderer3D
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Pooled low-poly particle system: sparks, dust, debris and coins. */
class Fx(private val r: Renderer3D, private val cube: Mesh) {
    private class P(val entity: Int, val mi: MaterialInstance, val glow: Boolean) {
        var x = 0f; var y = 0f; var z = 0f
        var vx = 0f; var vy = 0f; var vz = 0f
        var life = 0f; var maxLife = 1f
        var size = 0.1f
        var gravity = 9f
        var spin = 0f
        var active = false
    }

    private val litPool = ArrayList<P>()
    private val glowPool = ArrayList<P>()
    private val rnd = Random(11)

    init {
        repeat(140) { litPool += make(false) }
        repeat(110) { glowPool += make(true) }
    }

    private fun make(glow: Boolean): P {
        val mi = r.litInstance(0xFFFFFF, if (glow) r.glowMaterial else r.litMaterial)
        val e = r.createRenderable(cube, mi, shadows = !glow)
        return P(e, mi, glow)
    }

    private fun take(glow: Boolean): P? {
        val pool = if (glow) glowPool else litPool
        val p = pool.firstOrNull { !it.active } ?: return null
        p.active = true
        r.scene.addEntity(p.entity)
        return p
    }

    fun burst(
        x: Float, y: Float, z: Float, count: Int, color: Int, glow: Boolean = false,
        speed: Float = 2.5f, up: Float = 2.5f, size: Float = 0.09f, life: Float = 0.6f, gravity: Float = 9f,
        glowIntensity: Float = 1f,
    ) {
        repeat(count) {
            val p = take(glow) ?: return
            val a = rnd.nextFloat() * 6.2832f
            val s = speed * (0.4f + rnd.nextFloat() * 0.6f)
            p.x = x; p.y = y; p.z = z
            p.vx = cos(a) * s; p.vz = sin(a) * s
            p.vy = up * (0.5f + rnd.nextFloat() * 0.7f)
            p.maxLife = life * (0.7f + rnd.nextFloat() * 0.6f)
            p.life = p.maxLife
            p.size = size * (0.7f + rnd.nextFloat() * 0.6f)
            p.gravity = gravity
            p.spin = rnd.nextFloat() * 6f
            r.setTint(p.mi, varyColor(color))
            p.mi.setParameter("flash", glowIntensity - 1f)
        }
    }

    private fun varyColor(c: Int): Int {
        val f = 0.85f + rnd.nextFloat() * 0.3f
        val rr = ((c shr 16 and 0xff) * f).toInt().coerceIn(0, 255)
        val gg = ((c shr 8 and 0xff) * f).toInt().coerceIn(0, 255)
        val bb = ((c and 0xff) * f).toInt().coerceIn(0, 255)
        return (rr shl 16) or (gg shl 8) or bb
    }

    fun update(dt: Float) {
        for (pool in arrayOf(litPool, glowPool)) for (p in pool) {
            if (!p.active) continue
            p.life -= dt
            if (p.life <= 0f || p.y < -0.5f) {
                p.active = false
                r.scene.remove(p.entity)
                continue
            }
            p.vy -= p.gravity * dt
            p.x += p.vx * dt; p.y += p.vy * dt; p.z += p.vz * dt
            if (p.y < 0.02f && !p.glow) {
                p.y = 0.02f; p.vy = -p.vy * 0.3f; p.vx *= 0.6f; p.vz *= 0.6f
            }
            val t = p.life / p.maxLife
            p.spin += dt * 5f
            r.setTransform(p.entity, p.x, p.y, p.z, yaw = p.spin, scale = p.size * (0.3f + 0.7f * t), pitch = p.spin * 0.7f)
        }
    }

    fun clear() {
        for (pool in arrayOf(litPool, glowPool)) for (p in pool) if (p.active) {
            p.active = false
            r.scene.remove(p.entity)
        }
    }
}

/** Floating combat text drawn by the Compose overlay. */
class FloatingText(val x: Float, var y: Float, val z: Float, val text: String, val color: Int, val big: Boolean = false) {
    var life = if (big) 1.6f else 0.9f
    val maxLife = life
}
