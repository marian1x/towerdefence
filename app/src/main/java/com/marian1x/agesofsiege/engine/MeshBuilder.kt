package com.marian1x.agesofsiege.engine

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * CPU-side builder for flat-shaded, vertex-colored low-poly meshes.
 * Every face gets its own vertices so lighting looks crisp and faceted.
 * Colors are given in sRGB (0xRRGGBB) and converted to linear when uploaded.
 */
class MeshBuilder {
    internal var positions = FloatArray(3 * 1024)
    internal var normals = FloatArray(3 * 1024)
    internal var colors = IntArray(1024)
    var vertexCount = 0
        private set

    private var m = Affine.identity()
    private val stack = ArrayList<Affine>()

    val minB = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
    val maxB = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)

    // ------------------------------------------------------------ transforms

    inline fun push(block: MeshBuilder.() -> Unit) {
        pushMatrix()
        block()
        popMatrix()
    }

    fun pushMatrix() { stack.add(m.copy()) }
    fun popMatrix() { m = stack.removeAt(stack.size - 1) }
    fun translate(x: Float, y: Float, z: Float) = apply { m = m * Affine.translation(x, y, z) }
    fun rotateY(rad: Float) = apply { m = m * Affine.rotationY(rad) }
    fun rotateX(rad: Float) = apply { m = m * Affine.rotationX(rad) }
    fun rotateZ(rad: Float) = apply { m = m * Affine.rotationZ(rad) }
    fun scale(x: Float, y: Float = x, z: Float = x) = apply { m = m * Affine.scaling(x, y, z) }

    // ------------------------------------------------------------ raw geometry

    private fun ensure(extra: Int) {
        val need = vertexCount + extra
        if (need <= colors.size) return
        val cap = max(need, colors.size * 2)
        positions = positions.copyOf(cap * 3)
        normals = normals.copyOf(cap * 3)
        colors = colors.copyOf(cap)
    }

    /** Adds a triangle in local space; the face normal comes from the winding (double-sided material fixes back faces). */
    fun tri(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float, color: Int) {
        ensure(3)
        val a = m.apply(ax, ay, az)
        val b = m.apply(bx, by, bz)
        val c = m.apply(cx, cy, cz)
        val ux = b[0] - a[0]; val uy = b[1] - a[1]; val uz = b[2] - a[2]
        val vx = c[0] - a[0]; val vy = c[1] - a[1]; val vz = c[2] - a[2]
        var nx = uy * vz - uz * vy
        var ny = uz * vx - ux * vz
        var nz = ux * vy - uy * vx
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        if (len < 1e-9f) return
        nx /= len; ny /= len; nz /= len
        for (p in arrayOf(a, b, c)) {
            val i = vertexCount
            positions[i * 3] = p[0]; positions[i * 3 + 1] = p[1]; positions[i * 3 + 2] = p[2]
            normals[i * 3] = nx; normals[i * 3 + 1] = ny; normals[i * 3 + 2] = nz
            colors[i] = color
            for (k in 0..2) {
                minB[k] = min(minB[k], p[k]); maxB[k] = max(maxB[k], p[k])
            }
            vertexCount++
        }
    }

    fun quad(p0: V3, p1: V3, p2: V3, p3: V3, color: Int) {
        tri(p0.x, p0.y, p0.z, p1.x, p1.y, p1.z, p2.x, p2.y, p2.z, color)
        tri(p0.x, p0.y, p0.z, p2.x, p2.y, p2.z, p3.x, p3.y, p3.z, color)
    }

    // ------------------------------------------------------------ primitives

    /** Axis-aligned box from (x0,y0,z0) to (x1,y1,z1). */
    fun box(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, color: Int, top: Int = color) {
        val p000 = V3(x0, y0, z0); val p100 = V3(x1, y0, z0); val p110 = V3(x1, y1, z0); val p010 = V3(x0, y1, z0)
        val p001 = V3(x0, y0, z1); val p101 = V3(x1, y0, z1); val p111 = V3(x1, y1, z1); val p011 = V3(x0, y1, z1)
        val side = shade(color, 0.92f)
        quad(p001, p101, p111, p011, color)  // +z (front)
        quad(p100, p000, p010, p110, side)   // -z
        quad(p101, p100, p110, p111, side)   // +x
        quad(p000, p001, p011, p010, color)  // -x
        quad(p011, p111, p110, p010, top)    // top
        quad(p000, p100, p101, p001, shade(color, 0.7f)) // bottom
    }

    /** Centered box: width/depth around the origin, from y0 to y1. */
    fun cbox(w: Float, d: Float, y0: Float, y1: Float, color: Int, top: Int = color) =
        box(-w / 2, y0, -d / 2, w / 2, y1, d / 2, color, top)

    /** Vertical prism / frustum / cone around the Y axis. */
    fun prism(sides: Int, r0: Float, r1: Float, y0: Float, y1: Float, color: Int, top: Int = color, angle0: Float = 0f, capBottom: Boolean = true) {
        for (i in 0 until sides) {
            val a0 = angle0 + (i.toFloat() / sides) * TAU
            val a1 = angle0 + ((i + 1).toFloat() / sides) * TAU
            val c0 = cos(a0); val s0 = sin(a0); val c1 = cos(a1); val s1 = sin(a1)
            val faceColor = if (i % 2 == 0) color else shade(color, 0.95f)
            if (r1 > 0f) {
                quad(V3(c0 * r0, y0, s0 * r0), V3(c1 * r0, y0, s1 * r0), V3(c1 * r1, y1, s1 * r1), V3(c0 * r1, y1, s0 * r1), faceColor)
                tri(0f, y1, 0f, c0 * r1, y1, s0 * r1, c1 * r1, y1, s1 * r1, top)
            } else {
                tri(c0 * r0, y0, s0 * r0, c1 * r0, y0, s1 * r0, 0f, y1, 0f, faceColor)
            }
            if (capBottom) tri(0f, y0, 0f, c1 * r0, y0, s1 * r0, c0 * r0, y0, s0 * r0, shade(color, 0.7f))
        }
    }

    fun cone(sides: Int, r: Float, y0: Float, y1: Float, color: Int, angle0: Float = 0f) =
        prism(sides, r, 0f, y0, y1, color, color, angle0)

    /** Gable roof along the Z axis. */
    fun gable(w: Float, d: Float, y0: Float, h: Float, color: Int) {
        val hw = w / 2; val hd = d / 2
        val ridge0 = V3(0f, y0 + h, -hd); val ridge1 = V3(0f, y0 + h, hd)
        quad(V3(-hw, y0, -hd), V3(-hw, y0, hd), ridge1, ridge0, color)
        quad(V3(hw, y0, hd), V3(hw, y0, -hd), ridge0, ridge1, shade(color, 0.88f))
        val gableColor = shade(color, 0.8f)
        tri(-hw, y0, hd, hw, y0, hd, 0f, y0 + h, hd, gableColor)
        tri(hw, y0, -hd, -hw, y0, -hd, 0f, y0 + h, -hd, gableColor)
    }

    /** Low-poly icosphere, optionally jittered (rocks, bushes, heads). */
    fun ico(radius: Float, color: Int, subdivisions: Int = 1, jitter: Float = 0f, seed: Int = 0, squashY: Float = 1f, cy: Float = 0f) {
        val (verts, faces) = icosphere(subdivisions)
        val rnd = Random(seed)
        val jittered = verts.map { v ->
            val j = 1f + if (jitter > 0f) (rnd.nextFloat() * 2f - 1f) * jitter else 0f
            V3(v.x * radius * j, v.y * radius * j * squashY + cy, v.z * radius * j)
        }
        for ((i, f) in faces.withIndex()) {
            val a = jittered[f[0]]; val b = jittered[f[1]]; val c = jittered[f[2]]
            val col = if (i % 3 == 0) shade(color, 0.94f) else color
            tri(a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z, col)
        }
    }

    /** Flat ring on the XZ plane (range indicators). */
    fun ring(rInner: Float, rOuter: Float, y: Float, segments: Int, color: Int) {
        for (i in 0 until segments) {
            val a0 = i.toFloat() / segments * TAU
            val a1 = (i + 1).toFloat() / segments * TAU
            quad(
                V3(cos(a0) * rInner, y, sin(a0) * rInner), V3(cos(a0) * rOuter, y, sin(a0) * rOuter),
                V3(cos(a1) * rOuter, y, sin(a1) * rOuter), V3(cos(a1) * rInner, y, sin(a1) * rInner), color,
            )
        }
    }

    fun disc(r: Float, y: Float, segments: Int, color: Int) {
        for (i in 0 until segments) {
            val a0 = i.toFloat() / segments * TAU
            val a1 = (i + 1).toFloat() / segments * TAU
            tri(0f, y, 0f, cos(a1) * r, y, sin(a1) * r, cos(a0) * r, y, sin(a0) * r, color)
        }
    }

    companion object {
        const val TAU = (Math.PI * 2).toFloat()

        fun shade(color: Int, f: Float): Int {
            val r = ((color shr 16 and 0xff) * f).toInt().coerceIn(0, 255)
            val g = ((color shr 8 and 0xff) * f).toInt().coerceIn(0, 255)
            val b = ((color and 0xff) * f).toInt().coerceIn(0, 255)
            return (r shl 16) or (g shl 8) or b
        }

        fun mix(a: Int, b: Int, t: Float): Int {
            fun ch(s: Int) = (((a shr s and 0xff) * (1 - t)) + ((b shr s and 0xff) * t)).toInt().coerceIn(0, 255)
            return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        fun srgbToLinear(c: Int): Float = (c / 255f).pow(2.2f)

        private val icoCache = HashMap<Int, Pair<List<V3>, List<IntArray>>>()

        fun icosphere(subdiv: Int): Pair<List<V3>, List<IntArray>> = icoCache.getOrPut(subdiv) {
            val t = ((1.0 + sqrt(5.0)) / 2.0).toFloat()
            val verts = mutableListOf(
                V3(-1f, t, 0f), V3(1f, t, 0f), V3(-1f, -t, 0f), V3(1f, -t, 0f),
                V3(0f, -1f, t), V3(0f, 1f, t), V3(0f, -1f, -t), V3(0f, 1f, -t),
                V3(t, 0f, -1f), V3(t, 0f, 1f), V3(-t, 0f, -1f), V3(-t, 0f, 1f),
            ).map { it.normalized() }.toMutableList()
            var faces = listOf(
                intArrayOf(0, 11, 5), intArrayOf(0, 5, 1), intArrayOf(0, 1, 7), intArrayOf(0, 7, 10), intArrayOf(0, 10, 11),
                intArrayOf(1, 5, 9), intArrayOf(5, 11, 4), intArrayOf(11, 10, 2), intArrayOf(10, 7, 6), intArrayOf(7, 1, 8),
                intArrayOf(3, 9, 4), intArrayOf(3, 4, 2), intArrayOf(3, 2, 6), intArrayOf(3, 6, 8), intArrayOf(3, 8, 9),
                intArrayOf(4, 9, 5), intArrayOf(2, 4, 11), intArrayOf(6, 2, 10), intArrayOf(8, 6, 7), intArrayOf(9, 8, 1),
            )
            repeat(subdiv) {
                val mid = HashMap<Long, Int>()
                fun midpoint(a: Int, b: Int): Int {
                    val key = if (a < b) (a.toLong() shl 32) or b.toLong() else (b.toLong() shl 32) or a.toLong()
                    return mid.getOrPut(key) {
                        val va = verts[a]; val vb = verts[b]
                        verts += V3((va.x + vb.x) / 2, (va.y + vb.y) / 2, (va.z + vb.z) / 2).normalized()
                        verts.size - 1
                    }
                }
                faces = faces.flatMap { f ->
                    val a = midpoint(f[0], f[1]); val b = midpoint(f[1], f[2]); val c = midpoint(f[2], f[0])
                    listOf(intArrayOf(f[0], a, c), intArrayOf(f[1], b, a), intArrayOf(f[2], c, b), intArrayOf(a, b, c))
                }
            }
            verts to faces
        }
    }
}

data class V3(val x: Float, val y: Float, val z: Float) {
    fun normalized(): V3 {
        val l = sqrt(x * x + y * y + z * z)
        return V3(x / l, y / l, z / l)
    }
}

/** Minimal 3x4 affine matrix (row-major) for building meshes. */
class Affine(val v: FloatArray) {
    fun copy() = Affine(v.copyOf())

    operator fun times(o: Affine): Affine {
        val a = v; val b = o.v
        val r = FloatArray(12)
        for (row in 0..2) {
            for (col in 0..3) {
                var s = a[row * 4] * b[col] + a[row * 4 + 1] * b[4 + col] + a[row * 4 + 2] * b[8 + col]
                if (col == 3) s += a[row * 4 + 3]
                r[row * 4 + col] = s
            }
        }
        return Affine(r)
    }

    fun apply(x: Float, y: Float, z: Float) = floatArrayOf(
        v[0] * x + v[1] * y + v[2] * z + v[3],
        v[4] * x + v[5] * y + v[6] * z + v[7],
        v[8] * x + v[9] * y + v[10] * z + v[11],
    )

    companion object {
        fun identity() = Affine(floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f))
        fun translation(x: Float, y: Float, z: Float) = Affine(floatArrayOf(1f, 0f, 0f, x, 0f, 1f, 0f, y, 0f, 0f, 1f, z))
        fun scaling(x: Float, y: Float, z: Float) = Affine(floatArrayOf(x, 0f, 0f, 0f, 0f, y, 0f, 0f, 0f, 0f, z, 0f))
        fun rotationY(a: Float): Affine {
            val c = cos(a); val s = sin(a)
            return Affine(floatArrayOf(c, 0f, s, 0f, 0f, 1f, 0f, 0f, -s, 0f, c, 0f))
        }
        fun rotationX(a: Float): Affine {
            val c = cos(a); val s = sin(a)
            return Affine(floatArrayOf(1f, 0f, 0f, 0f, 0f, c, -s, 0f, 0f, s, c, 0f))
        }
        fun rotationZ(a: Float): Affine {
            val c = cos(a); val s = sin(a)
            return Affine(floatArrayOf(c, -s, 0f, 0f, s, c, 0f, 0f, 0f, 0f, 1f, 0f))
        }
    }
}
