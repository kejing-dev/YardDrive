package io.embodit.yarddrive.ui.scene

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import io.embodit.yarddrive.domain.Car
import io.embodit.yarddrive.domain.CarModel
import io.embodit.yarddrive.domain.Gaze
import io.embodit.yarddrive.domain.Gear
import io.embodit.yarddrive.domain.HeadPose
import io.embodit.yarddrive.domain.JudgeLine
import io.embodit.yarddrive.domain.Look
import io.embodit.yarddrive.domain.Mirrors
import io.embodit.yarddrive.domain.P2
import io.embodit.yarddrive.domain.Paint as YardPaint
import io.embodit.yarddrive.domain.Pose
import io.embodit.yarddrive.domain.Quat4
import io.embodit.yarddrive.domain.Seat
import io.embodit.yarddrive.domain.Upright
import io.embodit.yarddrive.domain.V3
import io.embodit.yarddrive.domain.Vec3
import io.embodit.yarddrive.domain.Yard
import io.embodit.yarddrive.platform.HeadTracker
import io.embodit.yarddrive.platform.Inputs
import io.embodit.yarddrive.platform.YdLog
import io.embodit.yarddrive.ui.FrameInput
import io.embodit.yarddrive.ui.Screen
import io.embodit.yarddrive.ui.YdController
import io.embodit.yarddrive.ui.YdEvent
import io.embodit.yarddrive.ui.YdUiState
import java.util.Locale
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The scene of the open Stage (debug hooks and the frame system reach it here). Main thread only. */
internal object ActiveScene {
    var current: YardScene? = null
}

/** The one per-frame ECS system of the app (registered in YdScreen). */
class YardFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        ActiveScene.current?.onFrame(context)
    }
}

object PanelIds {
    const val P1 = "P1"
    const val P2 = "P2"
    const val HUB = "HUB"
    const val MRD_L = "MRD-L"
    const val MRD_R = "MRD-R"
    const val MIR_L = "MIR-L"
    const val MIR_R = "MIR-R"
    const val MIR_I = "MIR-I"
    val pool = listOf(P1, P2, HUB, MRD_L, MRD_R, MIR_L, MIR_R, MIR_I)
}

/** Panel sizes in dp and scales (design panels[]; 1 dp = 0.8 mm at scale 1). */
object PanelSizes {
    const val P1_W = 800f
    const val P1_H = 600f
    const val P2_W = 1160f
    const val P2_H = 240f
    const val PANEL_SCALE = 0.8f
    const val HUB_D = 160f
    const val SMALL_SCALE = 0.6f
    const val MRD_W = 200f
    const val MRD_H = 64f
    const val MIR_W = 225f
    const val MIR_H = 138f
    const val MIRI_W = 325f
    const val MIRI_H = 88f
}

/**
 * The 3D side (design entities3d, ecs): the cabin (fixed to your seat), the yard under yardRoot (moved by the inverse
 * car pose every frame: translation + rotation about Y only, never a reflection, D10), the wheel, the gear knob, mirror
 * frames, the ideal path, the line flash, the gaze dot, the comfort vignette (the only head-locked element) and the
 * coach's hand in demos. The panels come from a pre-built pool ([PanelRig]). Per-frame work happens in [onFrame] only.
 */
class YardScene(private val context: Context) {
    val panelShown = mutableStateMapOf<String, Boolean>()
    val bindTick = mutableIntStateOf(0)

    /** The three mirror pictures (20 Hz), read only by the mirror panels. */
    val mirrorPics = mutableStateOf<Map<Mirrors.Id, Mirrors.Picture>>(emptyMap())

    private val main = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private val head = HeadTracker()
    val inputs = Inputs()
    private var stageRoot: Entity? = null
    private var cabinRoot: Entity? = null
    private var yardRoot: Entity? = null
    private var rig: PanelRig? = null
    private var shared: SharedResources? = null
    private var buildJob: Job? = null
    private var attached = false
    private var built = false
    private var paused = false
    private val attachMs = SystemClock.elapsedRealtime()

    private var mats: Mats? = null
    private var wheel: Entity? = null
    private var knob: Entity? = null
    private var coachHand: Entity? = null
    private var linesE: Entity? = null
    private var polesE: Entity? = null
    private var laneE: Entity? = null
    private var idealE: ModelEntity? = null
    private var idealMesh: MeshResource? = null
    private var flashE: ModelEntity? = null
    private var flashMesh: MeshResource? = null
    private var gazeDot: ModelEntity? = null
    private var vignette: ModelEntity? = null
    private var sky: Entity? = null
    private val frames = HashMap<Mirrors.Id, Pair<ModelEntity, UnlitMaterial>>()

    // placement (design container.placement, profile D1 / D9)
    var seat: Seat? = null
        private set
    private var pose: HeadPose? = null
    private var placeStartMs = 0L
    private val placeSamples = ArrayList<HeadPose>()
    private var placed = false
    private var placedAtMs = 0L
    private var lockedAtMs = 0L
    private var reseatRequested = false
    private val logAt = longArrayOf(1_000L, 3_000L, 5_000L)
    private var logIndex = 0

    private var lastFrameMs = 0L
    private var lastLogMs = 0L
    private var frameCount = 0L
    private var mirrorClock = 0L
    private var blockedSinceMs = 0L
    private var lastButtons = Inputs.Buttons()
    private var grabHand: Int = -1
    private var p2Pitch = Seat.P2_PITCH_R
    private var shownKey = ""
    private var idealKey: Any? = null
    private var flashKey: JudgeLine? = null
    private var fadeEpoch = 0
    private var fadeT = 1f
    private var fadeDir = 0
    private var frozenPose: Pose? = null

    /** Debug: fixed head yaw / pitch (degrees) for "look at" tests and the placement test. */
    var debugHead: Pair<Float, Float>? = null

    // =========================================================================================
    // lifecycle
    // =========================================================================================

    fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        if (attached || stageRoot != null) detach()
        this.scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val root = Entity().named("stageRoot")
        content.addEntity(root)
        // an entity added to the SpatialView is not at the Stage origin (Spatial Ruler): move the root once
        val offset = root.convertPositionFrom(Vector3.ZERO, null)
        val rotation = root.convertRotationFrom(Quat.identity(), null)
        root.components[TransformComponent::class.java]?.apply {
            setPosition(offset)
            setQuaternion(rotation)
        }
        stageRoot = root
        val rig = PanelRig(root) { id, shown -> panelShown[id] = shown }
        this.rig = rig
        rig.world(PanelIds.P1, PanelSizes.P1_W, PanelSizes.P1_H, PanelSizes.PANEL_SCALE)
        rig.world(PanelIds.P2, PanelSizes.P2_W, PanelSizes.P2_H, PanelSizes.PANEL_SCALE)
        rig.world(PanelIds.HUB, PanelSizes.HUB_D, PanelSizes.HUB_D, PanelSizes.SMALL_SCALE)
        rig.world(PanelIds.MRD_L, PanelSizes.MRD_W, PanelSizes.MRD_H, PanelSizes.SMALL_SCALE)
        rig.world(PanelIds.MRD_R, PanelSizes.MRD_W, PanelSizes.MRD_H, PanelSizes.SMALL_SCALE)
        rig.world(PanelIds.MIR_L, PanelSizes.MIR_W, PanelSizes.MIR_H, 1f).maxPitch = 1.4f
        rig.world(PanelIds.MIR_R, PanelSizes.MIR_W, PanelSizes.MIR_H, 1f).maxPitch = 1.4f
        rig.world(PanelIds.MIR_I, PanelSizes.MIRI_W, PanelSizes.MIRI_H, 1f).maxPitch = 1.4f
        head.start()
        inputs.start()
        ActiveScene.current = this
        attached = true
        YdController.onReseat = { reseatRequested = true }
        bindPanels(attachments)
        buildJob =
            scope?.launch {
                try {
                    build(root)
                } catch (t: Throwable) {
                    if (t !is CancellationException) YdLog.e("SCENE build failed: $t", t)
                    throw t
                }
            }
        YdLog.i("SCENE attached")
    }

    private suspend fun frame() = delay(16)

    /** Builds in steps (design lifecycle: road -> lines and poles -> cabin -> far props -> panels), resources on IO. */
    private suspend fun build(root: Entity) {
        val t0 = SystemClock.elapsedRealtime()
        val sh = SharedResources()
        shared = sh
        val m = withContext(Dispatchers.IO) { Mats(sh) }
        mats = m
        val cabin = Entity().named("cabinRoot")
        root.addChild(cabin)
        cabinRoot = cabin
        cabin.enabled = false
        val yard = Entity().named("yardRoot")
        cabin.addChild(yard)
        yardRoot = yard
        frame()
        val skyMesh = withContext(Dispatchers.IO) { sh.keep(MeshResource.createSphere(SKY_R)) }
        sky = model(skyMesh, m.sky, "sky", cabin).also { it.setLocal(scale = Vec3(-1f, 1f, 1f)) }
        val ground = withContext(Dispatchers.IO) { sh.keep(groundMesh()) }
        model(ground, m.asphalt, "asphalt", yard)
        val verge = withContext(Dispatchers.IO) { sh.keep(Toon(64).also { verge(it) }.build("verge")) }
        model(verge, m.toon, "verge", yard)
        frame()
        val lines = withContext(Dispatchers.IO) { sh.keep(Toon(4000).also { paint(it, Yard.paint.filter { p -> !p.aux }) }.build("lines")) }
        linesE = model(lines, m.toon, "lines", yard)
        val poles = withContext(Dispatchers.IO) { sh.keep(Toon(3000).also { t -> poles(t); paint(t, Yard.paint.filter { p -> p.aux }) }.build("poles")) }
        polesE = model(poles, m.toon, "poles", yard)
        frame()
        val lane = withContext(Dispatchers.IO) { sh.keep(Toon(400).also { paint(it, Mirrors.lanePaint(Yard.GAP_DEFAULT)) }.build("lane")) }
        laneE = model(lane, m.toon, "lane", yard).also { it.enabled = false }
        frame()
        // the cabin (one mesh), lit from the car's front left
        val cabinMesh = withContext(Dispatchers.IO) { sh.keep(Toon(6000).also { cabinMesh(it) }.build("cabin")) }
        model(cabinMesh, m.toon, "cabin", cabin)
        frame()
        val wheelRoot = Entity().named("wheel")
        cabin.addChild(wheelRoot)
        wheelRoot.setLocal(position = CarModel.WHEEL_CENTRE.f(), rotation = wheelBase)
        val wheelMesh = withContext(Dispatchers.IO) { sh.keep(Toon(3000).also { wheelMesh(it) }.build("wheelMesh")) }
        model(wheelMesh, m.toon, "wheelMesh", wheelRoot)
        wheel = wheelRoot
        val handMesh = withContext(Dispatchers.IO) { sh.keep(Toon(800).also { coachHandMesh(it) }.build("coachHand")) }
        coachHand = model(handMesh, m.coachHand, "coachHand", wheelRoot).also { it.enabled = false }
        val knobRoot = Entity().named("gearKnob")
        cabin.addChild(knobRoot)
        knobRoot.setLocal(position = CarModel.GEAR_KNOB.f())
        val knobMesh = withContext(Dispatchers.IO) { sh.keep(Toon(300).also { knobMesh(it) }.build("knob")) }
        model(knobMesh, m.toon, "knobMesh", knobRoot)
        knob = knobRoot
        frame()
        // mirror frames: a dark plate behind each mirror panel, glows when a cue points at it
        for (spec in Mirrors.all) {
            val mat = sh.keep(UnlitMaterial.create(BlendingMode.OPAQUE).apply { setBaseColor(FRAME_DARK); runCatching { setApplyToneMapping(false) }; setCullingMode(MaterialCullingMode.NONE) })
            val mesh = withContext(Dispatchers.IO) { sh.keep(MeshResource.createBox(Vector3(1f, 1f, 1f), 0f)) }
            val e = model(mesh, mat, "frame${spec.id}", cabin)
            val n = mirrorNormal(spec)
            val c = spec.pos.f() - n * 0.012f
            e.setLocal(position = c, rotation = facing(n), scale = Vec3((spec.wM + 0.024).toFloat(), (spec.hM + 0.024).toFloat(), 0.012f))
            frames[spec.id] = e to mat
        }
        frame()
        val env = withContext(Dispatchers.IO) { sh.keep(Toon(12000).also { env(it) }.build("env")) }
        model(env, m.toon, "env", yard)
        val signMesh = withContext(Dispatchers.IO) { sh.keep(signQuad()) }
        model(signMesh, m.sign, "sign", yard)
        frame()
        // dynamic: ideal path, line flash
        withContext(Dispatchers.IO) {
            idealMesh = sh.keep(MeshBuilder().build("ideal"))
            flashMesh = sh.keep(MeshBuilder().build("flash"))
        }
        idealE = model(idealMesh!!, m.toon, "ideal", yard).also { it.enabled = false }
        flashE = model(flashMesh!!, m.toon, "flash", yard).also { it.enabled = false }
        // gaze dot and the comfort vignette (head-locked, transparent colour only: no texture on a transparent material)
        val dot = withContext(Dispatchers.IO) { sh.keep(MeshResource.createSphere(0.5f)) }
        gazeDot = model(dot, m.gaze, "gazeDot", root).also { it.enabled = false }
        val ring = withContext(Dispatchers.IO) { sh.keep(Shapes.ring("vignette", 0.16f)) }
        vignette = model(ring, m.vignette, "vignette", root).also { it.enabled = false }
        built = true
        YdLog.i("SCENE built ms=${SystemClock.elapsedRealtime() - t0}")
    }

    fun pause() {
        if (paused || !attached) return
        paused = true
        head.stop()
        inputs.stop()
        YdController.sound.setLoops(false, false)
        YdLog.i("SCENE paused")
    }

    fun resume() {
        if (!paused || !attached) return
        paused = false
        head.start()
        inputs.start()
        lastFrameMs = 0L
        YdLog.i("SCENE resumed")
    }

    fun bindPanels(attachments: SpatialViewAttachments) {
        val rig = rig ?: return
        if (!rig.bind(attachments, PanelIds.pool, SystemClock.elapsedRealtime())) {
            main.removeCallbacks(rebind)
            main.postDelayed(rebind, 250L)
        }
    }

    private val rebind = Runnable { if (stageRoot != null) bindTick.intValue++ }

    fun detach() {
        if (!attached && stageRoot == null) return
        attached = false
        if (ActiveScene.current === this) ActiveScene.current = null
        YdController.onReseat = null
        main.removeCallbacksAndMessages(null)
        buildJob?.cancel()
        scope?.cancel()
        scope = null
        head.stop()
        inputs.stop()
        panelShown.clear()
        frames.clear()
        runCatching { rig?.release() }
        runCatching { stageRoot?.destroy(true) }
        mats?.close()
        shared?.close()
        shared = null
        mats = null
        stageRoot = null
        cabinRoot = null
        yardRoot = null
        rig = null
        built = false
        placed = false
        YdLog.i("SCENE detached")
    }

    // =========================================================================================
    // per frame
    // =========================================================================================

    fun onFrame(context: SceneUpdateContext) {
        val root = stageRoot ?: return
        if (root.scene == null || context.scene != root.scene) return
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrameMs == 0L) 0f else ((now - lastFrameMs) / 1000f).coerceIn(0f, 0.1f)
        lastFrameMs = now
        frameCount++
        head.pose()?.let { if (Upright.valid(it)) { rawPose = it; pose = debugPose(it) } }
        val rig = rig ?: return
        rig.onFrame(now)
        pose?.let { rig.stowBehind(it) }
        checkBlocked(now)
        if (!attached || paused) return
        val state = YdController.state.value
        if (!built) return
        stepPlacement(state, now)
        if (!placed) return
        val s = seat ?: return
        val input = frameInput(state, s, dt.toDouble(), now)
        if (YdController.debugAutopilot) YdController.debugDrive(dt.toDouble())
        YdController.tick(input)
        val st = YdController.state.value
        applyCar(st, s, dt)
        placePanels(st, s, dt)
        stepMirrors(st, s, now)
        stepEffects(st, s, input)
        stepStartupLog(now)
        if (now - lastLogMs > LOG_MS) {
            lastLogMs = now
            val r = YdController.run
            YdLog.i(String.format(Locale.US, "FRAME n=%d head=%s state=%s pose=%s wheel=%.0f v=%.2f gear=%s look=%s", frameCount, YdLog.pos(pose?.position), st.designState(), r.pose, r.sim.wheel, r.sim.v, r.sim.gear, input.look))
        }
    }

    private var rawPose: HeadPose? = null

    private fun debugPose(p: HeadPose): HeadPose {
        val d = debugHead ?: return p
        val baseYaw = p.yaw(0f) + Math.toRadians(viewOffsetDeg.toDouble()).toFloat()
        val q = Quat4.axisAngle(Vec3.UP, baseYaw + Math.toRadians(d.first.toDouble()).toFloat()) * Quat4.axisAngle(Vec3.RIGHT, Math.toRadians(d.second.toDouble()).toFloat())
        return HeadPose(p.position, q)
    }

    private fun checkBlocked(now: Long) {
        val reason = head.blockReason()
        if (reason == null || pose != null) {
            blockedSinceMs = 0L
            return
        }
        if (blockedSinceMs == 0L) blockedSinceMs = now
        if (now - blockedSinceMs > BLOCK_AFTER_MS && now - attachMs > BLOCK_AFTER_MS && YdController.state.value.screen != Screen.UNAVAILABLE) {
            YdLog.i("BLOCKED reason=$reason")
            YdController.onEvent(YdEvent.Blocked(reason))
        }
    }

    // ---------------------------------------------------------------- placement (design container.placement)

    private fun stepPlacement(state: YdUiState, now: Long) {
        val p = pose ?: return
        if (!placed) {
            if (placeStartMs == 0L) placeStartMs = now
            placeSamples += p
            if (now - placeStartMs < PLACE_WINDOW_MS || placeSamples.size < 3) return
            applyPlacement(median(placeSamples), "first")
            placed = true
            placedAtMs = now
            lockedAtMs = now + SETTLE_MS
            cabinRoot?.enabled = true
            YdController.onEvent(YdEvent.SceneReady)
            return
        }
        // during the first 2 s follow the head (tracking may still be settling), then lock (lesson: hand-signs v0.1.2)
        val raw = rawPose ?: p
        if (now < lockedAtMs && state.screen == Screen.SIT) applyPlacement(raw, "settle")
        if (reseatRequested) {
            reseatRequested = false
            applyPlacement(raw, "seated")
        }
    }

    private fun median(list: List<HeadPose>): HeadPose {
        val xs = list.map { it.position.x }.sorted()
        val ys = list.map { it.position.y }.sorted()
        val zs = list.map { it.position.z }.sorted()
        return HeadPose(Vec3(xs[xs.size / 2], ys[ys.size / 2], zs[zs.size / 2]), list.last().rotation)
    }

    /** Debug `view DEG` (+ = look right): the emulator camera cannot turn, so the cabin turns the other way at placement. */
    var viewOffsetDeg = 0f

    fun debugView(deg: Float) {
        viewOffsetDeg = deg
        rawPose?.let { applyPlacement(it, "view") }
    }

    private fun applyPlacement(p: HeadPose, why: String) {
        val s0 = Seat.place(p)
        val s = if (viewOffsetDeg == 0f) s0 else s0.copy(yaw = s0.yaw + Math.toRadians(viewOffsetDeg.toDouble()).toFloat())
        seat = s
        cabinRoot?.setLocal(position = s.eye, rotation = s.rotation)
        shownKey = ""
        if (why != "settle") YdLog.i(String.format(Locale.US, "PLACE %s eye=%s yaw=%.1f", why, YdLog.pos(s.eye), Math.toDegrees(s.yaw.toDouble())))
    }

    // ---------------------------------------------------------------- inputs (design steeringModel / driveModel)

    private fun frameInput(state: YdUiState, s: Seat, dt: Double, now: Long): FrameInput {
        val b = inputs.now
        val edge = { cur: Boolean, last: Boolean -> cur && !last }
        val input0 = FrameInput(dt, a = b.a, bEdge = edge(b.b, lastButtons.b), xEdge = edge(b.x, lastButtons.x), yEdge = edge(b.y, lastButtons.y), stickX = b.stickLX)
        val wasButtons = lastButtons
        lastButtons = b
        // gaze (eyes, else head) -> look class
        val gaze = inputs.gaze()
        val hp = pose
        val dirStage = gaze?.forward ?: hp?.forward ?: Vec3.AHEAD
        val originStage = gaze?.position ?: hp?.position ?: s.eye
        val eyeC = s.toCabin(originStage)
        val dirC = s.toCabinDir(dirStage)
        val look = Gaze.classify(V3(eyeC.x.toDouble(), eyeC.y.toDouble(), eyeC.z.toDouble()), V3(dirC.x.toDouble(), dirC.y.toDouble(), dirC.z.toDouble()))
        lastGaze = originStage to dirStage
        // the wheel: grip (controllers) or pinch (hands), only starting inside the grip band of the rim
        var az: Double? = null
        if (!state.settings.hands) {
            for (hand in 0..1) {
                val grip = if (hand == 0) b.gripL else b.gripR
                val was = if (hand == 0) wasButtons.gripL else wasButtons.gripR
                val pos = inputs.controllerPose(hand == 1) ?: continue
                val w = wheelAzimuth(s.toCabin(pos))
                if (grabHand == hand) {
                    if (grip < 0.4f) grabHand = -1 else az = w.first
                } else if (grabHand < 0 && grip >= 0.6f && was < 0.6f && w.second) {
                    grabHand = hand
                    az = w.first
                }
            }
        } else {
            val pinch = inputs.pinch(true)
            // D22: a pinch while you look at a panel is a click, not a grab
            val onUi = look == Look.AHEAD && abs(Math.toDegrees(atan2(dirC.x.toDouble(), -dirC.z.toDouble()))) < 22 && Math.toDegrees(kotlin.math.asin(dirC.y.toDouble().coerceIn(-1.0, 1.0))) in -22.0..6.0
            if (pinch != null) {
                val w = wheelAzimuth(s.toCabin(pinch.first))
                if (grabHand == 1) {
                    if (pinch.second < 0.5f) grabHand = -1 else az = w.first
                } else if (pinch.second > 0.85f && w.second && !onUi) {
                    grabHand = 1
                    az = w.first
                }
            } else grabHand = -1
        }
        val debugAz = debugGrab
        if (debugAz != null) az = debugAz
        val pinchGo = state.settings.hands && state.settings.pinchHold && (inputs.pinch(false)?.second ?: 0f) > 0.85f
        return input0.copy(grabAzimuth = az, look = look, lookByEyes = gaze != null && inputs.debugGaze == null || inputs.debugGaze != null, lost = head.lost(now), pinchGo = pinchGo)
    }

    /** Debug `lost 1`: the head tracker reports no samples (S19). */
    fun debugLost(on: Boolean) {
        head.forceLost = on
    }

    /** Debug `grab=1 angle=…`: a fake hand on the rim at that azimuth. */
    var debugGrab: Double? = null

    private var lastGaze: Pair<Vec3, Vec3>? = null

    /** (azimuth in the wheel plane, degrees counter-clockwise as you see it; inside the grip band?) for a cabin point. */
    private fun wheelAzimuth(h: Vec3): Pair<Double, Boolean> {
        val c = CarModel.WHEEL_CENTRE.f()
        val n = CarModel.WHEEL_AXIS.f()
        val u = Vec3(1f, 0f, 0f)
        val v = n.cross(u).normalized()
        val d = h - c
        val along = d.dot(n)
        val inPlane = d - n * along
        val r = inPlane.length
        val near = abs(r - CarModel.WHEEL_R) < GRIP_BAND && abs(along) < 0.08f
        return Math.toDegrees(atan2(inPlane.dot(v).toDouble(), inPlane.dot(u).toDouble())) to near
    }

    // ---------------------------------------------------------------- the car and the yard

    private fun applyCar(st: YdUiState, s: Seat, dt: Float) {
        val r = YdController.run
        val eyeH = st.settings.eyeH
        // demo "top view only": the yard stays where the run started; only the top view moves
        val p = if (st.screen == Screen.DEMO && st.demoTopOnly) (frozenPose ?: r.pose.also { frozenPose = it }) else r.pose.also { frozenPose = null }
        // a jump (library / back to the last point): 0.5 s fade out and in (design T15)
        if (YdController.fadeEpoch != fadeEpoch) {
            fadeEpoch = YdController.fadeEpoch
            fadeT = 0f
            fadeDir = 1
        }
        if (fadeDir != 0) {
            fadeT = (fadeT + dt / FADE_S).coerceAtMost(1f)
            yardRoot?.setOpacity(fadeT)
            if (fadeT >= 1f) fadeDir = 0
        }
        val o = p.yardOrigin(eyeH)
        yardRoot?.setLocal(position = Vec3(o.x.toFloat(), o.y.toFloat(), o.z.toFloat()), rotation = Quat4.axisAngle(Vec3.UP, p.yardYaw.toFloat()))
        sky?.setLocal(position = Vec3(0f, (-eyeH).toFloat(), 0f))
        wheel?.setLocal(rotation = wheelBase * Quat4.axisAngle(Vec3(0f, 0f, 1f), Math.toRadians(-r.sim.wheel).toFloat()))
        knob?.setLocal(position = CarModel.GEAR_KNOB.f() + Vec3(0f, 0f, if (r.sim.gear == Gear.D) -0.025f else 0.025f))
        coachHand?.enabled = st.screen == Screen.DEMO && st.settings.coachHand
        val lane = r.cfg.lane && st.screen in setOf(Screen.RUN, Screen.DEMO, Screen.REPLAY)
        linesE?.enabled = !lane
        laneE?.enabled = lane
        polesE?.enabled = YdController.polesOn() && !lane
        // ideal path: demo, step-by-step, after a line touch, look-point library
        val showIdeal = st.screen == Screen.DEMO || st.screen == Screen.POINT || (st.screen == Screen.RUN && (st.run.stepByStep || st.run.phase == io.embodit.yarddrive.domain.RunPhase.LINE)) || st.screen == Screen.TOUR
        val path = st.run.idealPath
        if (showIdeal && path.isNotEmpty() && idealKey !== path) {
            idealKey = path
            val b = Toon(path.size * 8).also { idealDashes(it, path) }.b
            swap(b, idealMesh) { idealE?.enabled = true }
        } else if (!showIdeal) idealE?.enabled = false else if (path.isNotEmpty()) idealE?.enabled = true
        // line flash (design entities3d.lineFlash): the touched line in red, 3 blinks
        val fl = st.flashLine
        if (fl != flashKey) {
            flashKey = fl
            if (fl != null) {
                val seg = (if (r.cfg.lane) Yard.laneLines(r.cfg.gap) else Yard.judgeLines())[fl]
                if (seg != null) swap(Toon(64).also { paint(it, listOf(YardPaint(seg, false, width = 0.20)), Palette.RED, 0.012f) }.b, flashMesh) { flashE?.enabled = true }
            } else flashE?.enabled = false
        }
        if (fl != null) flashE?.enabled = ((SystemClock.elapsedRealtime() / 250L) % 2L) == 0L
        // mirror frames glow 0.5 s on a cue, stay lit in the library
        for ((id, fm) in frames) {
            val lit = when (id) { Mirrors.Id.L -> 'L'; Mirrors.Id.R -> 'R'; Mirrors.Id.I -> 'I' } in st.glow
            fm.second.setBaseColor(if (lit) FRAME_GLOW else FRAME_DARK)
        }
    }

    private var swapJob: Job? = null

    private fun swap(b: MeshBuilder, mesh: MeshResource?, then: () -> Unit) {
        val m = mesh ?: return
        scope?.launch {
            withContext(Dispatchers.IO) { b.replace(m) }
            then()
        }
    }

    // ---------------------------------------------------------------- panels (design panels[].placement)

    private fun placePanels(st: YdUiState, s: Seat, dt: Float) {
        val rig = rig ?: return
        val inRun = st.screen in setOf(Screen.RUN, Screen.DEMO, Screen.REPLAY, Screen.POINT) || (st.screen == Screen.TOUR && st.tour.step == 3)
        val wantPitch = if (st.p2Up) Seat.P2_PITCH_D else Seat.P2_PITCH_R
        // 0.4 s glide between the R and D heights (design P2)
        val step = 16.0 / 0.4 * dt
        p2Pitch = if (abs(wantPitch - p2Pitch) <= step) wantPitch else p2Pitch + Math.signum(wantPitch - p2Pitch) * step
        val key = "${st.p1}${st.p2}$inRun${st.screen != Screen.LOADING}"
        val eye = s.eye
        if (key != shownKey) {
            shownKey = key
            rig.faceViewer(eye)
            show(PanelIds.P1, st.p1, s.toStage(Seat.p1Local()))
            show(PanelIds.HUB, st.screen != Screen.LOADING && st.screen != Screen.UNAVAILABLE, s.toStage((CarModel.WHEEL_CENTRE + CarModel.WHEEL_AXIS * 0.04).f()))
            show(PanelIds.MRD_L, inRun, s.toStage(Vec3(-0.42f, -0.26f, -0.60f)))
            show(PanelIds.MRD_R, inRun, s.toStage(Vec3(1.16f, -0.26f, -0.60f)))
            for ((id, spec) in listOf(PanelIds.MIR_L to Mirrors.L, PanelIds.MIR_R to Mirrors.R, PanelIds.MIR_I to Mirrors.I)) {
                val slot = rig[id] ?: continue
                val n = mirrorNormal(spec)
                slot.facePoint = s.toStage(spec.pos.f() + n)
                show(id, true, s.toStage(spec.pos.f()))
            }
        }
        val p2 = rig[PanelIds.P2] ?: return
        if (st.p2) {
            val at = s.toStage(Seat.p2Local(p2Pitch))
            if (!p2.shown) {
                rig.faceViewer(eye)
                p2.show(at)
            } else if ((p2.position - at).length > 1e-4f) {
                p2.moveTo(at)
            }
        } else p2.hide()
    }

    private fun show(id: String, on: Boolean, at: Vec3) {
        val slot = rig?.get(id) ?: return
        if (on) slot.show(at) else slot.hide()
    }

    private fun mirrorNormal(spec: Mirrors.Spec): Vec3 {
        val st = YdController.state.value.settings
        return Mirrors.normal(spec, Mirrors.aim(spec, st.mirrorOut.toDouble(), st.mirrorPitch.toDouble())).f()
    }

    /** Rotation that turns local +Z onto [n] with no roll (local X stays horizontal). */
    private fun facing(n: Vec3): Quat4 {
        val z = n.normalized()
        val x = Vec3.UP.cross(z).normalized()
        val y = z.cross(x).normalized()
        return Quat4.fromBasis(x, y, z)
    }

    // ---------------------------------------------------------------- mirrors (design model.mirrors, 20 Hz)

    private fun stepMirrors(st: YdUiState, s: Seat, now: Long) {
        if (now - mirrorClock < MIRROR_MS) return
        mirrorClock = now
        val r = YdController.run
        val hp = pose ?: return
        val e = s.toCabin(hp.position)
        val eye = V3(e.x.toDouble(), e.y.toDouble(), e.z.toDouble())
        val p = if (st.screen == Screen.DEMO && st.demoTopOnly) frozenPose ?: r.pose else r.pose
        val lane = r.cfg.lane && st.screen in setOf(Screen.RUN, Screen.DEMO, Screen.REPLAY)
        val pics = HashMap<Mirrors.Id, Mirrors.Picture>()
        for (spec in Mirrors.all) {
            pics[spec.id] = Mirrors.picture(spec, p, st.settings.eyeH, eye, st.settings.mirrorOut.toDouble(), st.settings.mirrorPitch.toDouble(), YdController.polesOn(), lane, r.cfg.gap, st.flashLine)
        }
        mirrorPics.value = pics
    }

    // ---------------------------------------------------------------- gaze dot, vignette

    private fun stepEffects(st: YdUiState, s: Seat, input: FrameInput) {
        val g = lastGaze
        val showDot = st.settings.gazeDot || st.screen == Screen.POINT || (st.screen == Screen.TOUR && st.tour.step == 1)
        val dot = gazeDot
        if (dot != null) {
            dot.enabled = showDot && g != null
            if (g != null && showDot) dot.setLocal(position = g.first + g.second.normalized() * GAZE_DIST, scale = Vec3(GAZE_SIZE, GAZE_SIZE, GAZE_SIZE))
        }
        val v = vignette ?: return
        val r = YdController.run
        val moving = st.screen in setOf(Screen.RUN, Screen.DEMO, Screen.REPLAY) && !st.paused
        val k = if (st.settings.comfort && moving) (r.sim.v / r.sim.vSet.coerceAtLeast(0.1)).toFloat().coerceIn(0f, 1f) else 0f
        val hp = pose
        if (k < 0.02f || hp == null) {
            v.enabled = false
            return
        }
        v.enabled = true
        mats?.vignette?.setOpacity(VIGNETTE_MAX * k)
        v.setLocal(position = hp.position + hp.forward * 0.5f, rotation = hp.rotation, scale = Vec3(2.2f, 2.2f, 2.2f))
    }

    private fun stepStartupLog(now: Long) {
        if (logIndex >= logAt.size) return
        if (now - placedAtMs < logAt[logIndex]) return
        logIndex++
        val p = pose
        val parts = ArrayList<String>()
        for (slot in rig?.all ?: emptyList()) {
            if (!slot.shown) continue
            val at = slot.position
            val rel = if (p != null) at - p.position else at
            parts += String.format(Locale.US, "%s=%s d=%.2f", slot.id, YdLog.pos(at), rel.length)
        }
        YdLog.i(String.format(Locale.US, "STARTUP t=%ds head=%s eye=%s cabin=%s panels[%s]", logAt[logIndex - 1] / 1000, YdLog.pos(p?.position), YdLog.pos(seat?.eye), cabinRoot?.enabled, parts.joinToString("; ")))
    }

    // =========================================================================================
    // meshes (yard frame: x east, y up, z south; cabin frame: origin = eye)
    // =========================================================================================

    private fun groundMesh(): MeshResource {
        val b = MeshBuilder(4)
        val n = Vec3.UP
        b.quad(
            b.vertex(Vec3(GX0, 0f, GZ1), n, Vector2(0f, 1f)),
            b.vertex(Vec3(GX1, 0f, GZ1), n, Vector2(1f, 1f)),
            b.vertex(Vec3(GX1, 0f, GZ0), n, Vector2(1f, 0f)),
            b.vertex(Vec3(GX0, 0f, GZ0), n, Vector2(0f, 0f)),
        )
        return b.build("asphalt")
    }

    private fun verge(t: Toon) {
        val r = 400f
        val y = -0.03f
        t.quad(Palette.GRASS, Vec3(-r, y, r), Vec3(r, y, r), Vec3(r, y, -r), Vec3(-r, y, -r), 0)
    }

    /** Painted stripes as flat quads at [lift] (dashed ones in 0.6 / 0.4 m dashes). */
    private fun paint(t: Toon, list: List<YardPaint>, force: Int? = null, lift: Float = 0.006f) {
        for (pt in list) {
            val a = pt.seg.a
            val b = pt.seg.b
            val len = (b - a).length
            if (len < 1e-6) continue
            val dir = (b - a) * (1 / len)
            val side = P2(-dir.z, dir.x) * (pt.width / 2)
            val color = force ?: if (pt.yellow) Palette.YELLOW else Palette.WHITE
            var s = 0.0
            val piece = if (pt.dashed) 0.6 else len
            val gap = if (pt.dashed) 0.4 else 0.0
            while (s < len - 1e-6) {
                val e = minOf(len, s + piece)
                val p0 = a + dir * s
                val p1 = a + dir * e
                val q = listOf(p0 - side, p1 - side, p1 + side, p0 + side).map { Vec3(it.x.toFloat(), lift, it.z.toFloat()) }
                // both windings so the stripe shows from above whatever the direction
                t.quad(color, q[0], q[1], q[2], q[3], 0)
                t.quad(color, q[3], q[2], q[1], q[0], 0)
                s = e + gap
            }
        }
    }

    private fun poles(t: Toon) {
        for (p in Yard.poles) for (k in 0 until 5) t.cylinder(if (k % 2 == 0) Palette.YELLOW else Palette.POLE_DARK, p.x.toFloat(), p.z.toFloat(), 0.025f, 0.3f * k, 0.3f * (k + 1), 12)
    }

    private fun idealDashes(t: Toon, path: List<P2>) {
        var on = true
        for (i in 0 until path.size - 1) {
            val a = path[i]
            val b = path[i + 1]
            val d = b - a
            val l = d.length
            if (l < 1e-4) continue
            if (on) {
                val side = P2(-d.z / l, d.x / l) * 0.025
                val q = listOf(a - side, b - side, b + side, a + side).map { Vec3(it.x.toFloat(), 0.010f, it.z.toFloat()) }
                t.quad(Palette.CYAN, q[0], q[1], q[2], q[3], 0)
                t.quad(Palette.CYAN, q[3], q[2], q[1], q[0], 0)
            }
            on = !on
        }
    }

    private fun env(t: Toon) {
        // fence posts every 3 m and two rails, 20 m out (design: 1.2 m mesh fence)
        fun fence(x0: Float, z0: Float, x1: Float, z1: Float) {
            val len = kotlin.math.hypot(x1 - x0, z1 - z0)
            val n = (len / 3f).toInt()
            for (i in 0..n) {
                val k = i / n.toFloat()
                t.cylinder(Palette.FENCE, x0 + (x1 - x0) * k, z0 + (z1 - z0) * k, 0.04f, 0f, 1.2f, 6)
            }
            val yaw = atan2(-(z1 - z0), x1 - x0)
            for (y in floatArrayOf(0.6f, 1.15f)) t.at(Quat4.axisAngle(Vec3.UP, yaw), Vec3((x0 + x1) / 2, y, (z0 + z1) / 2)) { box(Palette.FENCE, Vec3(-len / 2, -0.02f, -0.02f), Vec3(len / 2, 0.02f, 0.02f)) }
        }
        fence(GX0, GZ0, GX1, GZ0)
        fence(GX1, GZ0, GX1, GZ1)
        fence(GX1, GZ1, GX0, GZ1)
        fence(GX0, GZ1, GX0, GZ0)
        // three parked coach cars 20-30 m out (white body + yellow roof lamp box)
        for ((x, z, yaw) in listOf(Triple(-17f, 9f, 0.3f), Triple(15f, 9.5f, -0.2f), Triple(-15f, -15f, 1.4f))) {
            t.at(Quat4.axisAngle(Vec3.UP, yaw), Vec3(x, 0f, z)) {
                box(Palette.TYRE, Vec3(-1.9f, 0f, -0.8f), Vec3(1.9f, 0.35f, 0.8f))
                box(Palette.COACH, Vec3(-2.25f, 0.3f, -0.85f), Vec3(2.25f, 1.0f, 0.85f))
                box(Palette.COACH, Vec3(-1.1f, 1.0f, -0.75f), Vec3(1.1f, 1.45f, 0.75f))
                box(Palette.YELLOW, Vec3(-0.45f, 1.45f, -0.14f), Vec3(0.45f, 1.62f, 0.14f))
            }
        }
        // two trees
        for ((x, z) in listOf(-19f to -17f, 18f to 11f)) {
            t.cylinder(Palette.TRUNK, x, z, 0.18f, 0f, 2.4f, 8)
            t.cone(Palette.LEAF, x, z, 1.6f, 2.0f, 5.5f, 10)
        }
        // test-centre building to the east (grey box), its sign is a separate textured quad
        t.box(Palette.BUILDING, Vec3(22.5f, 0f, -6f), Vec3(31.5f, 4f, 0f))
    }

    /** The blue sign "考场" on the building's west face, readable from the yard (texture direction checked, D10). */
    private fun signQuad(): MeshResource {
        val b = MeshBuilder(4)
        val n = Vec3(-1f, 0f, 0f)
        val x = 22.45f
        // seen from the west looking east: the viewer's right is +Z (south); u grows with z, v = 0 at the top
        b.quad(
            b.vertex(Vec3(x, 2.65f, -4.6f), n, Vector2(0f, 1f)),
            b.vertex(Vec3(x, 2.65f, -1.4f), n, Vector2(1f, 1f)),
            b.vertex(Vec3(x, 3.55f, -1.4f), n, Vector2(1f, 0f)),
            b.vertex(Vec3(x, 3.55f, -4.6f), n, Vector2(0f, 0f)),
        )
        return b.build("sign")
    }

    /** The cabin and shell boxes of [CarModel], the A pillars and the steering column; lit from the car's front left. */
    private fun cabinMesh(t: Toon) {
        val keep = Palette.SUN
        Palette.SUN = Vec3(-0.6f, 0.7f, -0.4f).normalized()
        for (bx in CarModel.boxes) {
            val c = when (bx.shade) {
                CarModel.Shade.DASH -> Palette.DASH
                CarModel.Shade.TRIM -> Palette.TRIM
                CarModel.Shade.SEAT -> Palette.SEAT
                CarModel.Shade.HEADLINER -> Palette.HEADLINER
                CarModel.Shade.BODY -> Palette.BODY
                CarModel.Shade.BUMPER -> Palette.BUMPER
                CarModel.Shade.TIRE -> Palette.TYRE
                CarModel.Shade.FRAME -> Palette.FRAME
            }
            t.box(c, bx.min.f(), bx.max.f(), bottom = true)
        }
        for ((a, b) in CarModel.pillarsA + listOf(V3(0.0, -0.354 - 0.03 * 0.906, -0.42 + 0.03 * 0.423).let { it to V3(0.0, -0.50, -0.72) })) rod(t, a.f(), b.f(), 0.035f, Palette.TRIM)
        Palette.SUN = keep
    }

    private fun rod(t: Toon, a: Vec3, b: Vec3, r: Float, color: Int) {
        val d = b - a
        val len = d.length
        val q = Quat4.fromTo(Vec3(0f, 0f, 1f), d / len)
        t.at(q, (a + b) * 0.5f) { box(color, Vec3(-r, -r, -len / 2), Vec3(r, r, len / 2), bottom = true) }
    }

    /** Steering wheel in its own frame (local +Z = the column axis towards you): rim, three spokes, hub. */
    private fun wheelMesh(t: Toon) {
        val seg = 36
        val tube = 0.016f
        val rr = CarModel.WHEEL_R.toFloat()
        for (i in 0 until seg) {
            val a0 = 2 * Math.PI * i / seg
            val a1 = 2 * Math.PI * (i + 1) / seg
            val p0 = Vec3((rr * cos(a0)).toFloat(), (rr * sin(a0)).toFloat(), 0f)
            val p1 = Vec3((rr * cos(a1)).toFloat(), (rr * sin(a1)).toFloat(), 0f)
            rod(t, p0, p1, tube, if (i % 9 == 0) Palette.WHEEL_HI else Palette.WHEEL_RIM)
        }
        for (a in intArrayOf(90 + 180, 30, 150)) {
            val ang = Math.toRadians(a.toDouble())
            rod(t, Vec3(0f, 0f, 0f), Vec3((0.17 * cos(ang)).toFloat(), (0.17 * sin(ang)).toFloat(), 0f), 0.012f, Palette.WHEEL_RIM)
        }
        // hub: a short cylinder along the column axis (local Z)
        t.at(Quat4.axisAngle(Vec3(1f, 0f, 0f), (Math.PI / 2).toFloat()), Vec3(0f, 0f, -0.02f)) { cylinder(Palette.WHEEL_RIM, 0f, 0f, 0.07f, -0.015f, 0.015f, 16) }
        // a marker at 12 o'clock so a turn is visible (flush with the rim, nothing sticks up into P1's sight line)
        t.box(Palette.YELLOW, Vec3(-0.012f, rr - 0.012f, 0.012f), Vec3(0.012f, rr + 0.012f, 0.020f))
    }

    /** The coach's hand on the rim at 2 o'clock (translucent, demos only; gate B). */
    private fun coachHandMesh(t: Toon) {
        val a = Math.toRadians(30.0)
        val c = Vec3((0.18 * cos(a)).toFloat(), (0.18 * sin(a)).toFloat(), 0.03f)
        t.at(Quat4.axisAngle(Vec3(0f, 0f, 1f), a.toFloat()), c) {
            box(Palette.SKIN, Vec3(-0.035f, -0.045f, -0.02f), Vec3(0.045f, 0.045f, 0.03f), bottom = true)
            for (k in 0 until 4) box(Palette.SKIN, Vec3(-0.07f, -0.04f + k * 0.022f, -0.03f), Vec3(-0.035f, -0.022f + k * 0.022f, 0.0f), bottom = true)
            box(Palette.SKIN, Vec3(0.0f, 0.045f, -0.01f), Vec3(0.025f, 0.075f, 0.02f), bottom = true)
        }
    }

    private fun knobMesh(t: Toon) {
        t.cylinder(Palette.TRIM, 0f, 0f, 0.012f, -0.10f, 0f, 8)
        t.box(Palette.KNOB, Vec3(-0.025f, 0f, -0.025f), Vec3(0.025f, 0.04f, 0.025f))
    }

    // =========================================================================================

    fun dump(): List<String> {
        val out = ArrayList<String>()
        val r = YdController.run
        out += "SCENE built=$built placed=$placed seat=${seat?.let { YdLog.pos(it.eye) }} yaw=${seat?.let { Math.toDegrees(it.yaw.toDouble()).toInt() }} pose=${r.pose} wheel=${r.sim.wheel} gear=${r.sim.gear}"
        out += "INSTANCE cabin model=procedural boxes=${CarModel.boxes.size} transparent=no opacityComponent=${cabinRoot?.hasOpacityComponent()}"
        out += "INSTANCE yardRoot model=procedural transparent=no opacityComponent=${yardRoot?.hasOpacityComponent()}"
        out += "INSTANCE wheel model=procedural transparent=no; coachHand transparent=yes(intended, demo only) enabled=${coachHand?.enabled}"
        out += "INSTANCE vignette transparent=yes(intended, colour only) enabled=${vignette?.enabled}; gazeDot opaque enabled=${gazeDot?.enabled}"
        rig?.dump()?.let { out += it }
        return out
    }

    // =========================================================================================

    /** Materials: opaque Unlit with the colour atlas for every solid thing; colour-only transparents for the vignette / hand. */
    private class Mats(private val sh: SharedResources) {
        private val atlas = sh.keep(TextureResource.create(flipV(Palette.atlas())))
        val toon: UnlitMaterial = unlit(atlas)
        val asphalt: UnlitMaterial = unlit(sh.keep(TextureResource.create(flipV(asphaltTexture()))))
        val sky: UnlitMaterial = unlit(sh.keep(TextureResource.create(flipV(skyTexture()))))
        val sign: UnlitMaterial = unlit(sh.keep(TextureResource.create(flipV(signTexture()))))
        val gaze: UnlitMaterial = sh.keep(UnlitMaterial.create(BlendingMode.OPAQUE).apply { setBaseColor(Color4(0x7F / 255f, 0xD4 / 255f, 1f, 1f)); runCatching { setApplyToneMapping(false) } })
        val vignette: UnlitMaterial = sheer(Color4(0f, 0f, 0f, 1f), 0f)
        val coachHand: UnlitMaterial = sheer(Color4(0xE0 / 255f, 0xB4 / 255f, 0x9A / 255f, 1f), 0.45f)

        private fun unlit(tex: TextureResource): UnlitMaterial =
            sh.keep(
                UnlitMaterial.create(BlendingMode.OPAQUE).apply {
                    setBaseColor(Color4(1f, 1f, 1f, 1f))
                    setBaseColorTexture(tex)
                    runCatching { setApplyToneMapping(false) }
                    setCullingMode(MaterialCullingMode.NONE)
                }
            )

        private fun sheer(c: Color4, opacity: Float): UnlitMaterial =
            sh.keep(
                UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                    setBaseColor(c)
                    setOpacity(opacity)
                    runCatching { setApplyToneMapping(false) }
                    setCullingMode(MaterialCullingMode.NONE)
                    setDepthWrite(false)
                }
            )

        fun close() = Unit

        companion object {
            /** Asphalt 1024²: fine grain plus two faint tyre tracks (design textures). */
            fun asphaltTexture(): Bitmap {
                val n = 512
                val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
                val r = Random(11)
                val px = IntArray(n * n)
                for (i in px.indices) {
                    val g = 0x4A + r.nextInt(14) - 7
                    px[i] = AColor.rgb(g, g + 2, g + 6)
                }
                bmp.setPixels(px, 0, n, 0, 0, n, n)
                val c = Canvas(bmp)
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                p.color = 0x14000000
                p.strokeWidth = 10f
                for (k in 0 until 3) c.drawLine(0f, n * (0.3f + 0.2f * k), n.toFloat(), n * (0.32f + 0.2f * k), p)
                return bmp
            }

            fun skyTexture(): Bitmap {
                val h = 256
                val bmp = Bitmap.createBitmap(4, h, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                val p = Paint()
                p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0xFF9CC4E6.toInt(), 0xFFBFD9EE.toInt(), 0xFFEAF3FA.toInt(), 0xFFB9BCC0.toInt()), floatArrayOf(0f, 0.35f, 0.5f, 1f), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, 4f, h.toFloat(), p)
                return bmp
            }

            fun signTexture(): Bitmap {
                val w = 512
                val h = 144
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                c.drawColor(0xFF2B5FB3.toInt())
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                p.color = 0xFFFFFFFF.toInt()
                p.textAlign = Paint.Align.CENTER
                p.typeface = Typeface.DEFAULT_BOLD
                p.textSize = 96f
                val fm = p.fontMetrics
                c.drawText("考场", w / 2f, h / 2f - (fm.ascent + fm.descent) / 2, p)
                return bmp
            }
        }
    }

    private val wheelBase: Quat4 by lazy {
        val z = CarModel.WHEEL_AXIS.f().normalized()
        val x = Vec3(1f, 0f, 0f)
        val y = z.cross(x).normalized()
        Quat4.fromBasis(x, y, z)
    }

    companion object {
        const val SKY_R = 150f
        const val GX0 = -20f
        const val GX1 = 20f
        const val GZ0 = -18f
        const val GZ1 = 12f
        const val GRIP_BAND = 0.06f
        const val PLACE_WINDOW_MS = 500L
        const val SETTLE_MS = 2_000L
        const val BLOCK_AFTER_MS = 4_000L
        const val LOG_MS = 5_000L
        const val MIRROR_MS = 50L
        const val FADE_S = 0.5f
        const val GAZE_DIST = 6f
        const val GAZE_SIZE = 0.06f
        const val VIGNETTE_MAX = 0.25f
        val FRAME_DARK = Color4(0x1E / 255f, 0x21 / 255f, 0x25 / 255f, 1f)
        val FRAME_GLOW = Color4(0x5B / 255f, 0xD6 / 255f, 0xC8 / 255f, 1f)
    }
}
