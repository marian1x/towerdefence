package com.marian1x.agesofsiege.engine

import android.content.Context
import android.view.Surface
import android.view.SurfaceView
import com.google.android.filament.Box
import com.google.android.filament.Camera
import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.IndexBuffer
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.MathUtils
import com.google.android.filament.RenderableManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.Skybox
import com.google.android.filament.SwapChain
import com.google.android.filament.ToneMapper
import com.google.android.filament.VertexBuffer
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.UiHelper
import com.google.android.filament.filamat.MaterialBuilder
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.utils.KTX1Loader
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** GPU mesh uploaded to Filament. */
class Mesh(val vb: VertexBuffer, val ib: IndexBuffer, val indexCount: Int, val box: Box)

/**
 * Thin wrapper over Filament: engine, view, lights, post-processing, materials and mesh upload.
 * All calls happen on the main thread.
 */
class Renderer3D(context: Context) {
    val engine: Engine
    val renderer: Renderer
    val scene: Scene
    val view: View
    val camera: Camera
    private val cameraEntity: Int
    val surfaceView = SurfaceView(context)
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private val displayHelper = DisplayHelper(context)
    private var swapChain: SwapChain? = null

    lateinit var litMaterial: Material
    lateinit var glowMaterial: Material
    lateinit var ghostMaterial: Material
    lateinit var waterMaterial: Material
    /** Shared instance for static scenery (white tint, no flash). */
    val defaultLit: MaterialInstance

    private val sunEntity: Int
    private val entities = HashSet<Int>()
    private val meshes = ArrayList<Mesh>()

    var width = 1
        private set
    var height = 1
        private set

    // Camera rig (orbit around a target on the ground).
    var camTargetX = 13f
    var camTargetZ = 8f
    var camDistance = 26f
    var camPitchDeg = 56f
    var camYawDeg = 0f
    /** Camera shake offset in world units (applied to eye and target). */
    var shake = 0f
    val fovDeg = 32.0

    init {
        Filament.init()
        Gltfio.init()
        Utils.init()
        MaterialBuilder.init()
        engine = Engine.Builder().backend(Engine.Backend.OPENGL).build()
        renderer = engine.createRenderer()
        scene = engine.createScene()
        view = engine.createView()
        cameraEntity = EntityManager.get().create()
        camera = engine.createCamera(cameraEntity)
        view.scene = scene
        view.camera = camera

        buildMaterials()
        buildWaterMaterial()
        MaterialBuilder.shutdown()
        defaultLit = litInstance(0xFFFFFF)

        setupPostProcessing()

        // Image-based lighting + sky generated with Filament's cmgen from a CC0 Poly Haven HDRI.
        try {
            scene.indirectLight = KTX1Loader.createIndirectLight(engine, readAsset(context, "envs/sky_ibl.ktx"))
                .indirectLight?.apply { intensity = 18_000f }
            scene.skybox = KTX1Loader.createSkybox(engine, readAsset(context, "envs/sky_skybox.ktx")).skybox
        } catch (t: Throwable) {
            scene.skybox = Skybox.Builder().color(0.36f, 0.58f, 0.86f, 1f).build(engine)
            scene.indirectLight = IndirectLight.Builder()
                .irradiance(1, floatArrayOf(0.85f, 0.9f, 1.0f))
                .intensity(32_000f)
                .build(engine)
        }

        // Warm sun with soft shadows.
        sunEntity = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.SUN)
            .color(1.0f, 0.92f, 0.80f)
            .intensity(120_000f)
            .direction(0.45f, -1f, -0.55f)
            .sunAngularRadius(1.6f)
            .castShadows(true)
            .shadowOptions(LightManager.ShadowOptions().apply {
                mapSize = 2048
                shadowCascades = 1
                stable = false
                constantBias = 0.002f
                normalBias = 0.6f
                shadowFar = 70f
            })
            .build(engine, sunEntity)
        scene.addEntity(sunEntity)

        camera.setExposure(16f, 1f / 125f, 100f)

        uiHelper.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: Surface) {
                swapChain?.let { engine.destroySwapChain(it) }
                swapChain = engine.createSwapChain(surface)
                displayHelper.attach(renderer, surfaceView.display)
            }

            override fun onDetachedFromSurface() {
                displayHelper.detach()
                swapChain?.let {
                    engine.destroySwapChain(it)
                    engine.flushAndWait()
                    swapChain = null
                }
            }

            override fun onResized(w: Int, h: Int) {
                width = w
                height = h
                view.viewport = Viewport(0, 0, w, h)
                updateCamera()
            }
        }
        uiHelper.attachTo(surfaceView)
    }

    private fun buildMaterials() {
        litMaterial = compile(
            MaterialBuilder()
                .name("vertexColorLit")
                .require(MaterialBuilder.VertexAttribute.COLOR)
                .uniformParameter(MaterialBuilder.UniformType.FLOAT4, "tint")
                .uniformParameter(MaterialBuilder.UniformType.FLOAT, "flash")
                .shading(MaterialBuilder.Shading.LIT)
                .doubleSided(true)
                .material(
                    """
                    void material(inout MaterialInputs material) {
                        prepareMaterial(material);
                        vec3 c = getColor().rgb * materialParams.tint.rgb;
                        material.baseColor = vec4(mix(c, vec3(1.0, 0.92, 0.85), materialParams.flash), 1.0);
                        material.roughness = 0.82;
                        material.metallic = 0.0;
                        material.reflectance = 0.3;
                    }
                    """.trimIndent()
                )
        )
        glowMaterial = compile(
            MaterialBuilder()
                .name("glow")
                .require(MaterialBuilder.VertexAttribute.COLOR)
                .uniformParameter(MaterialBuilder.UniformType.FLOAT4, "tint")
                .uniformParameter(MaterialBuilder.UniformType.FLOAT, "flash")
                .shading(MaterialBuilder.Shading.UNLIT)
                .doubleSided(true)
                .material(
                    """
                    void material(inout MaterialInputs material) {
                        prepareMaterial(material);
                        material.baseColor = vec4(getColor().rgb * materialParams.tint.rgb * (1.0 + materialParams.flash), 1.0);
                    }
                    """.trimIndent()
                )
        )
        ghostMaterial = compile(
            MaterialBuilder()
                .name("ghost")
                .require(MaterialBuilder.VertexAttribute.COLOR)
                .uniformParameter(MaterialBuilder.UniformType.FLOAT4, "tint")
                .uniformParameter(MaterialBuilder.UniformType.FLOAT, "flash")
                .shading(MaterialBuilder.Shading.UNLIT)
                .blending(MaterialBuilder.BlendingMode.TRANSPARENT)
                .doubleSided(true)
                .depthWrite(false)
                .material(
                    """
                    void material(inout MaterialInputs material) {
                        prepareMaterial(material);
                        vec4 c = vec4(getColor().rgb * materialParams.tint.rgb, materialParams.tint.a);
                        material.baseColor = vec4(c.rgb * c.a, c.a);
                    }
                    """.trimIndent()
                )
        )
    }

    private fun buildWaterMaterial() {
        waterMaterial = compile(
            MaterialBuilder()
                .name("water")
                .variable(MaterialBuilder.Variable.CUSTOM0, "pos")
                .shading(MaterialBuilder.Shading.LIT)
                .materialVertex(
                    """
                    void materialVertex(inout MaterialVertexInputs material) {
                        material.pos = getPosition();
                    }
                    """.trimIndent()
                )
                .material(
                    """
                    void material(inout MaterialInputs material) {
                        vec3 p = variable_pos.xyz;
                        float t = getUserTime().x;
                        float a = sin(p.x * 1.7 + t * 1.3) * 0.5 + sin((p.x + p.z) * 0.9 + t * 0.7) * 0.5;
                        float b = cos(p.z * 1.5 - t * 1.1) * 0.5 + cos((p.x - p.z) * 1.1 - t * 0.9) * 0.5;
                        material.normal = normalize(vec3(a * 0.09, b * 0.09, 1.0));
                        prepareMaterial(material);
                        material.baseColor = vec4(0.09, 0.33, 0.50, 1.0);
                        material.roughness = 0.16;
                        material.metallic = 0.0;
                        material.reflectance = 0.7;
                    }
                    """.trimIndent()
                )
        )
    }

    private fun compile(builder: MaterialBuilder): Material {
        val pkg = builder
            .platform(MaterialBuilder.Platform.MOBILE)
            .targetApi(MaterialBuilder.TargetApi.OPENGL)
            .build()
        check(pkg.isValid) { "Material compilation failed" }
        val buffer = pkg.buffer
        return Material.Builder().payload(buffer, buffer.remaining()).build(engine)
    }

    private fun setupPostProcessing() {
        view.ambientOcclusionOptions = view.ambientOcclusionOptions.apply {
            enabled = true
            radius = 0.5f
            intensity = 1.1f
            power = 1.2f
        }
        view.bloomOptions = view.bloomOptions.apply {
            enabled = true
            strength = 0.05f
            levels = 6
            threshold = true
        }
        view.multiSampleAntiAliasingOptions = view.multiSampleAntiAliasingOptions.apply {
            enabled = true
            sampleCount = 4
        }
        view.antiAliasing = View.AntiAliasing.FXAA
        view.vignetteOptions = view.vignetteOptions.apply {
            enabled = true
            midPoint = 0.55f
            feather = 0.6f
            roundness = 0.5f
        }
        view.colorGrading = ColorGrading.Builder()
            .toneMapper(ToneMapper.ACESLegacy())
            .saturation(1.08f)
            .contrast(1.06f)
            .build(engine)
    }

    fun litInstance(color: Int, material: Material = litMaterial, alpha: Float = 1f): MaterialInstance {
        val mi = material.createInstance()
        setTint(mi, color, alpha)
        mi.setParameter("flash", 0f)
        return mi
    }

    fun setTint(mi: MaterialInstance, color: Int, alpha: Float = 1f) {
        mi.setParameter(
            "tint",
            MeshBuilder.srgbToLinear(color shr 16 and 0xff),
            MeshBuilder.srgbToLinear(color shr 8 and 0xff),
            MeshBuilder.srgbToLinear(color and 0xff),
            alpha,
        )
    }

    /** Uploads a mesh. Interleaved: position (float3), tangent frame (float4), color (ubyte4). */
    fun upload(b: MeshBuilder): Mesh {
        val n = b.vertexCount
        val stride = 32
        val data = ByteBuffer.allocateDirect(n * stride).order(ByteOrder.nativeOrder())
        val q = FloatArray(4)
        for (i in 0 until n) {
            data.putFloat(b.positions[i * 3]); data.putFloat(b.positions[i * 3 + 1]); data.putFloat(b.positions[i * 3 + 2])
            val nx = b.normals[i * 3]; val ny = b.normals[i * 3 + 1]; val nz = b.normals[i * 3 + 2]
            // Any tangent perpendicular to the normal works for flat shading.
            var tx: Float; var ty: Float; var tz: Float
            if (abs(nx) < 0.9f) { tx = 0f; ty = nz; tz = -ny } else { tx = -nz; ty = 0f; tz = nx }
            val tl = sqrt(tx * tx + ty * ty + tz * tz)
            tx /= tl; ty /= tl; tz /= tl
            val bx = ny * tz - nz * ty; val by = nz * tx - nx * tz; val bz = nx * ty - ny * tx
            MathUtils.packTangentFrame(tx, ty, tz, bx, by, bz, nx, ny, nz, q)
            data.putFloat(q[0]); data.putFloat(q[1]); data.putFloat(q[2]); data.putFloat(q[3])
            val c = b.colors[i]
            data.put((MeshBuilder.srgbToLinear(c shr 16 and 0xff) * 255f + 0.5f).toInt().toByte())
            data.put((MeshBuilder.srgbToLinear(c shr 8 and 0xff) * 255f + 0.5f).toInt().toByte())
            data.put((MeshBuilder.srgbToLinear(c and 0xff) * 255f + 0.5f).toInt().toByte())
            data.put(255.toByte())
        }
        data.flip()
        val vb = VertexBuffer.Builder()
            .bufferCount(1)
            .vertexCount(n)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, stride)
            .attribute(VertexBuffer.VertexAttribute.TANGENTS, 0, VertexBuffer.AttributeType.FLOAT4, 12, stride)
            .attribute(VertexBuffer.VertexAttribute.COLOR, 0, VertexBuffer.AttributeType.UBYTE4, 28, stride)
            .normalized(VertexBuffer.VertexAttribute.COLOR)
            .build(engine)
        vb.setBufferAt(engine, 0, data)

        val indices = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until n) indices.putInt(i)
        indices.flip()
        val ib = IndexBuffer.Builder()
            .indexCount(n)
            .bufferType(IndexBuffer.Builder.IndexType.UINT)
            .build(engine)
        ib.setBuffer(engine, indices)

        val cx = (b.minB[0] + b.maxB[0]) / 2; val cy = (b.minB[1] + b.maxB[1]) / 2; val cz = (b.minB[2] + b.maxB[2]) / 2
        val hx = (b.maxB[0] - b.minB[0]) / 2 + 0.01f; val hy = (b.maxB[1] - b.minB[1]) / 2 + 0.01f; val hz = (b.maxB[2] - b.minB[2]) / 2 + 0.01f
        return Mesh(vb, ib, n, Box(cx, cy, cz, hx, hy, hz)).also { meshes += it }
    }

    /** Creates a renderable entity (not yet added to the scene). */
    fun createRenderable(mesh: Mesh, mi: MaterialInstance, shadows: Boolean = true): Int {
        val e = EntityManager.get().create()
        RenderableManager.Builder(1)
            .boundingBox(mesh.box)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, mesh.vb, mesh.ib, 0, mesh.indexCount)
            .material(0, mi)
            .castShadows(shadows)
            .receiveShadows(true)
            .culling(true)
            .build(engine, e)
        engine.transformManager.create(e)
        entities += e
        return e
    }

    fun setTransform(e: Int, x: Float, y: Float, z: Float, yaw: Float = 0f, scale: Float = 1f, pitch: Float = 0f, sy: Float = scale) {
        val tm = engine.transformManager
        val inst = tm.getInstance(e)
        val c = cos(yaw); val s = sin(yaw)
        val cp = cos(pitch); val sp = sin(pitch)
        // R = Ry(yaw) * Rx(pitch), column-major, scaled.
        val m = tmpMat
        m[0] = c * scale; m[1] = 0f; m[2] = -s * scale; m[3] = 0f
        m[4] = s * sp * sy; m[5] = cp * sy; m[6] = c * sp * sy; m[7] = 0f
        m[8] = s * cp * scale; m[9] = -sp * scale; m[10] = c * cp * scale; m[11] = 0f
        m[12] = x; m[13] = y; m[14] = z; m[15] = 1f
        tm.setTransform(inst, m)
    }

    private val tmpMat = FloatArray(16)

    private fun readAsset(context: Context, path: String): ByteBuffer {
        val bytes = context.assets.open(path).use { it.readBytes() }
        return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).also { it.flip() }
    }

    fun destroyEntity(e: Int) {
        scene.remove(e)
        engine.destroyEntity(e)
        EntityManager.get().destroy(e)
        entities -= e
    }

    // ------------------------------------------------------------ camera

    fun eye(): FloatArray {
        val pitch = Math.toRadians(camPitchDeg.toDouble()).toFloat()
        val yaw = Math.toRadians(camYawDeg.toDouble()).toFloat()
        val horiz = camDistance * cos(pitch)
        return floatArrayOf(
            camTargetX + horiz * sin(yaw),
            camDistance * sin(pitch),
            camTargetZ + horiz * cos(yaw),
        )
    }

    fun updateCamera() {
        val e = eye()
        val s = shake.toDouble()
        camera.lookAt(
            e[0] + s, e[1].toDouble(), e[2].toDouble(),
            camTargetX + s, 0.0, camTargetZ.toDouble(),
            0.0, 1.0, 0.0,
        )
        val aspect = width.toDouble() / height.coerceAtLeast(1)
        camera.setProjection(fovDeg, aspect, 1.0, 140.0, Camera.Fov.VERTICAL)
    }

    private fun basis(): Array<FloatArray> {
        val e = eye()
        var fx = camTargetX - e[0]; var fy = -e[1]; var fz = camTargetZ - e[2]
        val fl = sqrt(fx * fx + fy * fy + fz * fz); fx /= fl; fy /= fl; fz /= fl
        // right = forward x up(0,1,0)
        var rx = -fz; val ry = 0f; var rz = fx
        val rl = sqrt(rx * rx + rz * rz); rx /= rl; rz /= rl
        // up = right x forward
        val ux = ry * fz - rz * fy; val uy = rz * fx - rx * fz; val uz = rx * fy - ry * fx
        return arrayOf(e, floatArrayOf(fx, fy, fz), floatArrayOf(rx, ry, rz), floatArrayOf(ux, uy, uz))
    }

    /** Screen pixel -> point on the ground plane (y = planeY). */
    fun screenToGround(sx: Float, sy: Float, planeY: Float = 0f): FloatArray? {
        val (e, f, r, u) = basis()
        val tanHalf = tan(Math.toRadians(fovDeg / 2)).toFloat()
        val aspect = width.toFloat() / height.coerceAtLeast(1)
        val nx = 2f * sx / width - 1f
        val ny = 1f - 2f * sy / height
        val dx = f[0] + r[0] * nx * tanHalf * aspect + u[0] * ny * tanHalf
        val dy = f[1] + r[1] * nx * tanHalf * aspect + u[1] * ny * tanHalf
        val dz = f[2] + r[2] * nx * tanHalf * aspect + u[2] * ny * tanHalf
        if (abs(dy) < 1e-5f) return null
        val t = (planeY - e[1]) / dy
        if (t < 0) return null
        return floatArrayOf(e[0] + dx * t, planeY, e[2] + dz * t)
    }

    /** World point -> screen pixel; null if behind the camera. */
    fun worldToScreen(x: Float, y: Float, z: Float, out: FloatArray): Boolean {
        val (e, f, r, u) = basisCache ?: basis().also { basisCache = it }
        val vx = x - e[0]; val vy = y - e[1]; val vz = z - e[2]
        val depth = vx * f[0] + vy * f[1] + vz * f[2]
        if (depth <= 0.1f) return false
        val tanHalf = tanHalfCache
        val aspect = width.toFloat() / height.coerceAtLeast(1)
        val px = (vx * r[0] + vy * r[1] + vz * r[2]) / (depth * tanHalf * aspect)
        val py = (vx * u[0] + vy * u[1] + vz * u[2]) / (depth * tanHalf)
        out[0] = (px + 1f) / 2f * width
        out[1] = (1f - py) / 2f * height
        return true
    }

    private var basisCache: Array<FloatArray>? = null
    private val tanHalfCache = tan(Math.toRadians(fovDeg / 2)).toFloat()

    // ------------------------------------------------------------ frame

    fun render(frameTimeNanos: Long) {
        basisCache = null
        updateCamera()
        val sc = swapChain ?: return
        if (!uiHelper.isReadyToRender) return
        if (renderer.beginFrame(sc, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
        }
    }

    fun destroy() {
        uiHelper.detach()
        for (e in entities.toList()) destroyEntity(e)
        for (m in meshes) {
            engine.destroyVertexBuffer(m.vb)
            engine.destroyIndexBuffer(m.ib)
        }
        engine.destroyEntity(sunEntity)
        EntityManager.get().destroy(sunEntity)
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(cameraEntity)
        EntityManager.get().destroy(cameraEntity)
        engine.destroy()
    }
}
