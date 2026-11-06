package io.embodit.yarddrive.ui.scene

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.OpacityControllerComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import io.embodit.yarddrive.domain.Quat4
import io.embodit.yarddrive.domain.Vec3
import io.embodit.yarddrive.platform.YdLog
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// domain <-> SDK types. Every entity hangs below the stage root, whose local space equals Stage space.
// ---------------------------------------------------------------------------------------------

internal fun Vec3.sdk(): Vector3 = Vector3(x, y, z)

internal fun Vector3.domain(): Vec3 = Vec3(x, y, z)

internal fun Quat4.sdk(): Quat = Quat(x, y, z, w)

internal fun Quat.domain(): Quat4 = Quat4(x, y, z, w)

internal fun Color.color4(): Color4 = Color4(red, green, blue, alpha)

internal val Entity.transformOrNull: TransformComponent?
    get() = components[TransformComponent::class.java]

internal fun Entity.setLocal(position: Vec3? = null, rotation: Quat4? = null, scale: Vec3? = null) {
    val t = transformOrNull ?: return
    if (position != null) t.setPosition(position.sdk())
    if (rotation != null) t.setQuaternion(rotation.sdk())
    if (scale != null) t.setScaleVector(scale.sdk())
}

internal fun Entity.setMaterial(material: Material) {
    components[ModelComponent::class.java]?.materials?.set(0, material)
}

/**
 * Overall opacity of an entity and its children. At 1 the component is removed: a mounted OpacityControllerComponent
 * makes the renderer blend even at 1.0 (factory lesson, Inside Orchestra v0.1.4).
 */
internal fun Entity.setOpacity(opacity: Float) {
    val o = opacity.coerceIn(0f, 1f)
    val existing = components[OpacityControllerComponent::class.java]
    if (o >= 0.999f) {
        if (existing != null) runCatching { components.remove(OpacityControllerComponent::class.java) }
        return
    }
    if (existing != null) existing.opacity = o else components.set(OpacityControllerComponent(o))
}

internal fun Entity.hasOpacityComponent(): Boolean = components[OpacityControllerComponent::class.java] != null

/** The engine's texture V runs from the bottom row of the bitmap up: bitmaps are drawn top-down, flipped once on upload. */
internal fun flipV(src: android.graphics.Bitmap): android.graphics.Bitmap {
    val m = android.graphics.Matrix().apply { preScale(1f, -1f) }
    return android.graphics.Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, false)
}

internal fun Entity.named(name: String): Entity = also { runCatching { it.setName(name) } }

/** Metres per dp of an attachment panel at scale 1 (Spatial Ruler feasibility spike). */
internal const val DP_M = 0.0008f

/**
 * Resources shared by entities that come and go. The SDK releases a resource when its last user is
 * destroyed (Spatial Ruler), so shared ones are made global and closed when the scene goes away.
 */
internal class SharedResources {
    private val owned = ArrayList<Resource>()

    fun <T : Resource> keep(resource: T): T {
        resource.toGlobal()
        owned += resource
        return resource
    }

    fun close() {
        owned.forEach { runCatching { it.close() } }
        owned.clear()
    }
}

/** Accumulates triangles and builds (or refills) a mesh. */
internal class MeshBuilder(capacity: Int = 64) {
    val positions = ArrayList<Vector3>(capacity)
    val normals = ArrayList<Vector3>(capacity)
    val uvs = ArrayList<Vector2>(capacity)
    val indices = ArrayList<Int>(capacity * 3 / 2)
    private var minX = Float.MAX_VALUE
    private var minY = Float.MAX_VALUE
    private var minZ = Float.MAX_VALUE
    private var maxX = -Float.MAX_VALUE
    private var maxY = -Float.MAX_VALUE
    private var maxZ = -Float.MAX_VALUE

    val vertexCount: Int
        get() = positions.size

    fun vertex(p: Vec3, n: Vec3, uv: Vector2 = ZERO_UV): Int {
        positions += Vector3(p.x, p.y, p.z)
        normals += Vector3(n.x, n.y, n.z)
        uvs += uv
        if (p.x < minX) minX = p.x
        if (p.y < minY) minY = p.y
        if (p.z < minZ) minZ = p.z
        if (p.x > maxX) maxX = p.x
        if (p.y > maxY) maxY = p.y
        if (p.z > maxZ) maxZ = p.z
        return positions.size - 1
    }

    fun tri(a: Int, b: Int, c: Int) {
        indices += a
        indices += b
        indices += c
    }

    fun quad(a: Int, b: Int, c: Int, d: Int) {
        tri(a, b, c)
        tri(a, c, d)
    }

    fun bounds(): BoundingBox =
        if (positions.isEmpty()) BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(0.01f, 0.01f, 0.01f))
        else BoundingBox(center = Vector3((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2), halfExtent = Vector3(max(0.001f, (maxX - minX) / 2), max(0.001f, (maxY - minY) / 2), max(0.001f, (maxZ - minZ) / 2)))

    /** A degenerate triangle keeps an emptied mesh valid. */
    private fun ensureNotEmpty() {
        if (positions.isNotEmpty()) return
        val n = Vec3(0f, 0f, 1f)
        tri(vertex(Vec3.ZERO, n), vertex(Vec3(0f, 0.0001f, 0f), n), vertex(Vec3(0.0001f, 0f, 0f), n))
    }

    fun model(): MeshModel {
        ensureNotEmpty()
        return MeshModel(positions = positions, triangleIndices = indices, normals = normals, uv0 = uvs)
    }

    fun build(name: String): MeshResource = MeshResource.createWithMeshModel(model = model(), bounds = bounds(), name = name)

    /** Refills [mesh]; returns false when the engine refused (the caller keeps the old one). */
    fun replace(mesh: MeshResource): Boolean =
        try {
            val m = model()
            mesh.replaceWithMeshModel(m, bounds())
        } catch (t: Throwable) {
            YdLog.e("SCENE mesh replace failed: $t", t)
            false
        }

    companion object {
        val ZERO_UV = Vector2(0.5f, 0.5f)
    }
}

/** Programmatic meshes, built once per scene. */
internal object Shapes {
    /** A flat disc of radius 0.5 in local XY facing +Z. */
    fun disc(name: String, seg: Int = 40): MeshResource {
        val b = MeshBuilder(seg + 2)
        val n = Vec3(0f, 0f, 1f)
        // UVs map the disc onto the whole texture (the gaze pool's radial fade)
        val c = b.vertex(Vec3.ZERO, n, Vector2(0.5f, 0.5f))
        for (i in 0..seg) {
            val a = 2 * PI * i / seg
            b.vertex(Vec3((0.5 * cos(a)).toFloat(), (0.5 * sin(a)).toFloat(), 0f), n, Vector2((0.5 + 0.5 * cos(a)).toFloat(), (0.5 - 0.5 * sin(a)).toFloat()))
        }
        for (i in 1..seg) b.tri(c, i, i + 1)
        return b.build(name)
    }

    /** A flat ring (annulus) between radii [inner] and 0.5 in local XY facing +Z. */
    fun ring(name: String, inner: Float, seg: Int = 48): MeshResource {
        val b = MeshBuilder(seg * 2 + 2)
        val n = Vec3(0f, 0f, 1f)
        for (i in 0..seg) {
            val a = 2 * PI * i / seg
            b.vertex(Vec3((inner * cos(a)).toFloat(), (inner * sin(a)).toFloat(), 0f), n)
            b.vertex(Vec3((0.5 * cos(a)).toFloat(), (0.5 * sin(a)).toFloat(), 0f), n)
        }
        for (i in 0 until seg) b.quad(i * 2, i * 2 + 1, i * 2 + 3, i * 2 + 2)
        return b.build(name)
    }

    /**
     * A soft-edged ring: overlapping flat annuli, all out to 0.5, from each radius in [inners] (R4). Drawn with one
     * transparent colour-only material, the opacity adds up step by step towards the outside; no texture (a transparent
     * textured material stalls the 6.1 emulator's render thread).
     */
    fun softRing(name: String, inners: FloatArray, seg: Int = 48): MeshResource {
        val b = MeshBuilder((seg + 1) * 2 * inners.size)
        val n = Vec3(0f, 0f, 1f)
        for ((k, inner) in inners.withIndex()) {
            val base = b.vertexCount
            val z = -0.0004f * k
            for (i in 0..seg) {
                val a = 2 * PI * i / seg
                b.vertex(Vec3((inner * cos(a)).toFloat(), (inner * sin(a)).toFloat(), z), n)
                b.vertex(Vec3((0.5 * cos(a)).toFloat(), (0.5 * sin(a)).toFloat(), z), n)
            }
            for (i in 0 until seg) b.quad(base + i * 2, base + i * 2 + 1, base + i * 2 + 3, base + i * 2 + 2)
        }
        return b.build(name)
    }

    /** A unit cube centred on the origin. */
    fun cube(name: String): MeshResource = MeshResource.createBox(Vector3(1f, 1f, 1f), 0f)

    /** A sphere of diameter 1 (scaled per star). */
    fun ball(name: String): MeshResource = MeshResource.createSphere(0.5f)

    /** A cylinder of diameter 1 and height 1 about local Y (the dial disc; built here so its size is exact). */
    fun cylinder(name: String, seg: Int = 48): MeshResource {
        val b = MeshBuilder(seg * 4 + 2)
        val up = Vec3(0f, 1f, 0f)
        val down = Vec3(0f, -1f, 0f)
        val top = b.vertex(Vec3(0f, 0.5f, 0f), up)
        val bottom = b.vertex(Vec3(0f, -0.5f, 0f), down)
        for (i in 0 until seg) {
            val a0 = 2 * PI * i / seg
            val a1 = 2 * PI * (i + 1) / seg
            val p0 = Vec3((0.5 * cos(a0)).toFloat(), 0f, (0.5 * sin(a0)).toFloat())
            val p1 = Vec3((0.5 * cos(a1)).toFloat(), 0f, (0.5 * sin(a1)).toFloat())
            val h = Vec3(0f, 0.5f, 0f)
            b.tri(top, b.vertex(p1 + h, up), b.vertex(p0 + h, up))
            b.tri(bottom, b.vertex(p0 - h, down), b.vertex(p1 - h, down))
            val n = p0.normalized()
            b.quad(b.vertex(p0 - h, n), b.vertex(p1 - h, n), b.vertex(p1 + h, n), b.vertex(p0 + h, n))
        }
        return b.build(name)
    }
}

/** Places a unit-cube entity as a thin bar between two points. */
internal fun Entity.bar(from: Vec3, to: Vec3, thickness: Float) {
    val d = to - from
    val len = d.length
    if (len < 1e-5f) {
        setLocal(scale = Vec3(1e-4f, 1e-4f, 1e-4f))
        return
    }
    val dir = d / len
    setLocal(position = (from + to) * 0.5f, rotation = Quat4.fromTo(Vec3(0f, 0f, 1f), dir), scale = Vec3(thickness, thickness, len))
}

internal fun model(mesh: MeshResource, material: Material, name: String, parent: Entity): ModelEntity =
    ModelEntity(mesh, material).also {
        runCatching { it.setName(name) }
        parent.addChild(it)
    }
