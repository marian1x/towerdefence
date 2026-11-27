package com.marian1x.agesofsiege.engine

import android.content.Context
import com.google.android.filament.EntityManager
import com.google.android.filament.Scene
import com.google.android.filament.gltfio.Animator
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.FilamentInstance
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/** One placed copy of a glTF model (built by tools/blender/build_assets.py). */
class Model internal constructor(
    val name: String,
    val instance: FilamentInstance,
    private val animIndex: Map<String, Int>,
) {
    val root: Int get() = instance.root
    val animator: Animator get() = instance.animator
    internal var inScene = false
    internal var scene: Scene? = null

    fun hasAnimation(anim: String) = anim in animIndex

    fun duration(anim: String): Float = animIndex[anim]?.let { animator.getAnimationDuration(it) } ?: 0f

    /** Poses the model at [time] seconds into [anim]. */
    fun animate(anim: String, time: Float, loop: Boolean = true) {
        val i = animIndex[anim] ?: return
        val d = animator.getAnimationDuration(i)
        val t = if (loop && d > 0f) time % d else min(time, d)
        animator.applyAnimation(i, t)
        animator.updateBoneMatrices()
    }
}

/**
 * Loads GLB models from assets/models and hands out pooled instances.
 * Instances of the same model share vertex buffers and textures (glTF instancing).
 */
class ModelLibrary(private val r: Renderer3D, private val context: Context) {
    private val materials = UbershaderProvider(r.engine)
    private val assetLoader = AssetLoader(r.engine, materials, EntityManager.get())
    private val resourceLoader = ResourceLoader(r.engine)
    private val sources = HashMap<String, ByteBuffer>()
    private val free = HashMap<String, ArrayDeque<Model>>()
    private val assets = ArrayList<FilamentAsset>()
    private val boundsByName = HashMap<String, FloatArray>()

    private val meta: JsonObject = context.assets.open("models/models.json").use {
        Json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject
    }

    /** Height where a tower's weapon / crew stands (from the asset pipeline). */
    fun headY(name: String): Float =
        meta[name]?.jsonObject?.get("headY")?.jsonPrimitive?.floatOrNull ?: 0f

    private fun source(name: String): ByteBuffer = sources.getOrPut(name) {
        val bytes = context.assets.open("models/$name.glb").use { it.readBytes() }
        ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).also { it.flip() }
    }

    private fun grow(name: String, count: Int) {
        val instances = arrayOfNulls<FilamentInstance>(count)
        val buffer = source(name)
        buffer.rewind()
        val asset = assetLoader.createInstancedAsset(buffer, instances)
            ?: error("Could not load model $name")
        resourceLoader.loadResources(asset)
        assets += asset
        if (name !in boundsByName) {
            val b = asset.boundingBox
            val c = b.center
            val h = b.halfExtent
            boundsByName[name] = floatArrayOf(c[0] - h[0], c[1] - h[1], c[2] - h[2], c[0] + h[0], c[1] + h[1], c[2] + h[2])
        }
        val rm = r.engine.renderableManager
        val queue = free.getOrPut(name) { ArrayDeque() }
        for (inst in instances) {
            inst ?: continue
            for (e in inst.entities) {
                if (rm.hasComponent(e)) {
                    val ri = rm.getInstance(e)
                    rm.setCastShadows(ri, true)
                    rm.setReceiveShadows(ri, true)
                }
            }
            val anims = HashMap<String, Int>()
            val animator = inst.animator
            for (i in 0 until animator.animationCount) anims[animator.getAnimationName(i)] = i
            queue.addLast(Model(name, inst, anims))
        }
    }

    /** Local-space bounds of a loaded model: minX, minY, minZ, maxX, maxY, maxZ (null until first loaded). */
    fun bounds(name: String): FloatArray? = boundsByName[name]

    /** Takes a model from the pool (creating a new batch if needed) and adds it to [scene]. */
    fun acquire(name: String, batch: Int = 8, scene: Scene = r.scene): Model {
        val queue = free.getOrPut(name) { ArrayDeque() }
        if (queue.isEmpty()) grow(name, batch)
        val m = queue.removeLast()
        scene.addEntities(m.instance.entities)
        m.scene = scene
        m.inScene = true
        return m
    }

    /** Returns a model to the pool and removes it from its scene. */
    fun release(m: Model) {
        if (m.inScene) m.scene?.removeEntities(m.instance.entities)
        m.inScene = false
        m.scene = null
        free.getOrPut(m.name) { ArrayDeque() }.addLast(m)
    }

    /** Pre-loads models so the first wave doesn't stutter. */
    fun warmUp(vararg names: Pair<String, Int>) {
        for ((name, count) in names) {
            if ((free[name]?.size ?: 0) < count) grow(name, count)
        }
    }

    fun destroy() {
        for (a in assets) assetLoader.destroyAsset(a)
        assets.clear()
        resourceLoader.destroy()
        assetLoader.destroy()
        materials.destroyMaterials()
        materials.destroy()
    }
}
