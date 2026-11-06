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
import io.embodit.yarddrive.domain.Comfort
import io.embodit.yarddrive.domain.Gaze
import io.embodit.yarddrive.domain.Gear
import io.embodit.yarddrive.domain.HeadPose
import io.embodit.yarddrive.domain.JudgeLine
import io.embodit.yarddrive.domain.Look
import io.embodit.yarddrive.domain.MirrorFollower
import io.embodit.yarddrive.domain.Mirrors
import io.embodit.yarddrive.domain.P2
import io.embodit.yarddrive.domain.Paint as YardPaint
import io.embodit.yarddrive.domain.PanelCone
import io.embodit.yarddrive.domain.Pose
import io.embodit.yarddrive.domain.Quat4
import io.embodit.yarddrive.domain.Seat
import io.embodit.yarddrive.domain.Settings
import io.embodit.yarddrive.domain.SeatFlow
import io.embodit.yarddrive.domain.StartSeat
import io.embodit.yarddrive.domain.Upright
import io.embodit.yarddrive.domain.V3
import io.embodit.yarddrive.domain.Yaw
import io.embodit.yarddrive.domain.Vec3
import io.embodit.yarddrive.domain.Yard
import io.embodit.yarddrive.platform.HeadTracker
import io.embodit.yarddrive.platform.Inputs
import io.embodit.yarddrive.platform.YdLog
import io.embodit.yarddrive.ui.debug.Probe
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
    const val HINT = "HINT"
    val pool = listOf(P1, P2, HUB, MRD_L, MRD_R, MIR_L, MIR_R, MIR_I, HINT)
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
    const val MRD_R_W = 280f
    const val MRD_R_H = 72f
    const val MRD_R_SCALE = 1f
    const val MIR_W = 225f
    const val MIR_H = 138f
    const val MIRI_W = 325f
    const val MIRI_H = 88f
    const val HINT_W = 620f
    const val HINT_H = 72f
}

/**
 * The 3D side (design entities3d, ecs): the cabin (fixed to your seat), the yard under yardRoot (moved by the inverse
 * car pose every frame: translation + rotation about Y only, never a reflection, D10), the wheel, the gear knob, mirror
 * frames, the ideal path, the line flash, the gaze dot and the comfort vignette (the only head-locked element); r5 dropped
 * the coach's hand. The panels come from a pre-built pool ([PanelRig]). Per-frame work happens in [onFrame] only.
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
    private var wheelSolid: Entity? = null
    private var wheelGhost: List<Entity> = emptyList()
    private val wheelFade = PanelCone.Fade()
    private var knob: Entity? = null
    private var linesE: Entity? = null
    private var polesE: Entity? = null
    private var laneE: Entity? = null
    private var idealE: ModelEntity? = null
    private var idealMesh: MeshResource? = null
    private var flashE: ModelEntity? = null
    private var flashMesh: MeshResource? = null
    private var gazeDot: ModelEntity? = null
    private var vignette: ModelEntity? = null
    private var vignetteK = 0f
    private var sky: Entity? = null
    private val frames = HashMap<Mirrors.Id, Pair<ModelEntity, UnlitMaterial>>()
    private val struts = HashMap<Mirrors.Id, ModelEntity>()

    // placement (design container.placement, profile D1 / D9)
    var seat: Seat? = null
        private set
    private var pose: HeadPose? = null
    private val startSeat = StartSeat()
    private var placed = false
    private var placedAtMs = 0L
    private var awayFromMs = 0L

    /** "I'm seated" and "Re-seat": the one placing flow (profile D29). */
    private val seatFlow = SeatFlow()
    private var placeRequested = false

    /** No head pose: the eye is a guess, so the inside of the car stays out of the way of S20's panel (R11). */
    private var seatIsGuess = false
    private var cabinMeshE: Entity? = null
    private var readySent = false
    private var p1LoggedMs = false

    /** About the last second of the head (D29: never the head yaw of the very moment a button is pressed). */
    private val recent = ArrayDeque<Pair<Long, HeadPose>>()
    private val logAt = longArrayOf(1_000L, 3_000L, 5_000L)
    private var logIndex = 0

    private var lastFrameMs = 0L
    private var lastLogMs = 0L
    private var frameCount = 0L
    private var mirrorClock = 0L
    private var blockedSinceMs = 0L
    private var lastButtons = Inputs.Buttons()
    private var p2Pitch = Seat.P2_PITCH_R
    private var shownKey = ""
    private var idealKey: Any? = null
    private var flashKey: Any? = null
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
        rig.world(PanelIds.MRD_R, PanelSizes.MRD_R_W, PanelSizes.MRD_R_H, PanelSizes.MRD_R_SCALE)
        rig.world(PanelIds.HINT, PanelSizes.HINT_W, PanelSizes.HINT_H, Seat.HINT_SCALE)
        rig.world(PanelIds.MIR_L, PanelSizes.MIR_W, PanelSizes.MIR_H, 1f).maxPitch = 1.4f
        rig.world(PanelIds.MIR_R, PanelSizes.MIR_W, PanelSizes.MIR_H, 1f).maxPitch = 1.4f
        rig.world(PanelIds.MIR_I, PanelSizes.MIRI_W, PanelSizes.MIRI_H, 1f).maxPitch = 1.4f
        head.start()
        inputs.start()
        ActiveScene.current = this
        attached = true
        YdController.onPlace = { placeRequested = true }
        YdController.headReady = { pose != null && headLive }
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

    /** Debug builds, `adb shell setprop <name> <seconds>`: hold the scene build here (a longer S00 to look at). */
    private suspend fun debugHold(name: String) {
        if (!Probe.enabled) return
        val v = runCatching { Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String }.getOrDefault("").toLongOrNull() ?: return
        YdLog.i("SCENE hold $name ${v}s")
        delay(v * 1000)
    }

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
        // D1 / R5: sky and road appear as they are built; the seat may already be known, the panels do not wait for this
        val known = seat
        cabin.enabled = known != null
        if (known != null) cabin.setLocal(position = known.eye, rotation = known.rotation)
        val yard = Entity().named("yardRoot")
        cabin.addChild(yard)
        yardRoot = yard
        frame()
        debugHold("debug.yarddrive.holdsky")
        val skyMesh = withContext(Dispatchers.IO) { sh.keep(MeshResource.createSphere(SKY_R)) } // gate:allow geometry 天空穹顶，反面朝内的球，不是物件
        sky = model(skyMesh, m.sky, "sky", cabin).also { it.setLocal(scale = Vec3(-1f, 1f, 1f)) }
        debugHold("debug.yarddrive.holdroad")
        val ground = withContext(Dispatchers.IO) { sh.keep(groundMesh()) }
        model(ground, m.asphalt, "asphalt", yard)
        val verge = withContext(Dispatchers.IO) { sh.keep(Toon(64).also { verge(it) }.build("verge")) }
        model(verge, m.toon, "verge", yard)
        YdLog.i("SCENE road ms=${SystemClock.elapsedRealtime() - t0}")
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
        cabinMeshE = model(cabinMesh, m.toon, "cabin", cabin)
        frame()
        val wheelRoot = Entity().named("wheel")
        cabin.addChild(wheelRoot)
        wheelRoot.setLocal(position = CarModel.WHEEL_CENTRE.f(), rotation = wheelBase)
        // B12 / user 2026-11-05: a real ring, smooth-shaded (Smooth.kt): rim torus 72 x 16, elliptic spokes, rounded hub
        val rimR = CarModel.WHEEL_R.toFloat()
        val wheelMesh = withContext(Dispatchers.IO) { sh.keep(Smooth(6000, RoundParts::wheelLight).also { RoundParts.wheel(it, rimR) }.build("wheelMesh")) }
        wheelSolid = model(wheelMesh, m.smooth, "wheelMesh", wheelRoot)
        wheel = wheelRoot
        // design D20: what shows while the wheel is in a panel's line of sight, colour only (no texture on a transparent material):
        // the same smooth geometry, one mesh per colour of the solid wheel (rim + spokes + hub, the yellow arc)
        val ghostMesh = withContext(Dispatchers.IO) { sh.keep(Smooth(6000, RoundParts::wheelLight).also { RoundParts.wheel(it, rimR, RoundParts.WheelPart.RIM) }.build("wheelGhost")) }
        val markMesh = withContext(Dispatchers.IO) { sh.keep(Smooth(200, RoundParts::wheelLight).also { RoundParts.wheel(it, rimR, RoundParts.WheelPart.MARK) }.build("wheelMark")) }
        wheelGhost = listOf(
            model(ghostMesh, m.wheelGhost, "wheelGhost", wheelRoot).also { it.enabled = false },
            model(markMesh, m.markGhost, "wheelMarkGhost", wheelRoot).also { it.enabled = false },
        )
        val knobRoot = Entity().named("gearKnob")
        cabin.addChild(knobRoot)
        knobRoot.setLocal(position = CarModel.GEAR_KNOB.f())
        val knobMesh = withContext(Dispatchers.IO) { sh.keep(Smooth(800) { _, n -> Ramp.shade(n, CABIN_SUN) }.also { RoundParts.gearKnob(it) }.build("knob")) }
        model(knobMesh, m.smooth, "knobMesh", knobRoot)
        val columnMesh = withContext(Dispatchers.IO) {
            sh.keep(
                Smooth(1200) { _, n -> Ramp.shade(n, CABIN_SUN) }.also { s ->
                    s.tube(Palette.TRIM, CarModel.column.first.f(), CarModel.column.second.f(), Vec3(1f, 0f, 0f), CarModel.ROD_R.toFloat(), CarModel.ROD_R.toFloat(), 16)
                    for (bx in CarModel.boxes.filter { it.shade == CarModel.Shade.TIRE }) {
                        val cx = ((bx.min.x + bx.max.x) / 2).toFloat()
                        val cz = ((bx.min.z + bx.max.z) / 2).toFloat()
                        RoundParts.tyre(s, cx, cz, ((bx.max.y - bx.min.y) / 2).toFloat(), (bx.max.x - bx.min.x).toFloat(), if (cx < 0) -1f else 1f, bx.min.y.toFloat())
                    }
                }.build("column")
            )
        }
        model(columnMesh, m.smooth, "column", cabin)
        knob = knobRoot
        frame()
        // mirror frames: a dark plate behind each mirror panel, glows when a cue points at it
        // The frames are mesh and d35 goes over them; only the right inner lower tip at the default aim is the accepted exception (design r4, gate B).
        for (spec in Mirrors.all) {
            val mat = sh.keep(UnlitMaterial.create(BlendingMode.OPAQUE).apply { setBaseColor(FRAME_DARK); runCatching { setApplyToneMapping(false) }; setCullingMode(MaterialCullingMode.NONE) })
            val mesh = withContext(Dispatchers.IO) { sh.keep(MeshResource.createBox(Vector3(1f, 1f, 1f), 0f)) } // gate:allow geometry 镜框板和连接件；设计包写明车内全程序生成，零外部模型
            val e = model(mesh, mat, "frame${spec.id}", cabin)
            frames[spec.id] = e to mat
            if (spec.id != Mirrors.Id.I) {
                // the side mirror's bar back to the shell; hidden behind the face from the eye (CarModel.mirrorStrut)
                val barMat = sh.keep(UnlitMaterial.create(BlendingMode.OPAQUE).apply { setBaseColor(FRAME_DARK); runCatching { setApplyToneMapping(false) }; setCullingMode(MaterialCullingMode.NONE) })
                struts[spec.id] = model(mesh, barMat, "strut${spec.id}", cabin)
            }
        }
        mirrorFollower.reset()
        YdController.state.value.settings.let { mirrorFollower.step(it.mirrorOut, it.mirrorPitch) }
        frame()
        val env = withContext(Dispatchers.IO) { sh.keep(Toon(12000).also { env(it) }.build("env")) }
        model(env, m.toon, "env", yard)
        // three parked coach cars 20-30 m out: rounded bodies, round tyres, smooth-shaded by the yard's sun (B12)
        val coaches = withContext(Dispatchers.IO) { sh.keep(Smooth(12000) { _, n -> Ramp.shade(n, YARD_SUN) }.also { coachCars(it) }.build("coaches")) }
        model(coaches, m.smooth, "coaches", yard)
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
        val dot = withContext(Dispatchers.IO) { sh.keep(MeshResource.createSphere(0.5f)) } // gate:allow geometry 目光光点，抽象指示，不是物件
        gazeDot = model(dot, m.gaze, "gazeDot", root).also { it.enabled = false }
        val ring = withContext(Dispatchers.IO) { sh.keep(Shapes.softRing("vignette", VIGNETTE_INNERS)) }
        vignette = model(ring, m.vignette, "vignette", root).also { it.enabled = false }
        built = true
        YdLog.i("SCENE built ms=${SystemClock.elapsedRealtime() - t0}")
    }

    fun pause() {
        if (paused || !attached) return
        paused = true
        // in the background the flow ends: P1 comes back where it was when you return
        if (seatFlow.active || placeRequested) {
            seatFlow.cancel()
            placeRequested = false
            YdController.placingCancelled()
            rig?.get(PanelIds.HINT)?.hide()
            YdLog.i("SEAT cancelled background")
        }
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
        // the eye-tracking dialog and the like stop the app: nothing from before that says where the head is now
        recent.clear()
        pose = null
        YdLog.i("SCENE resumed, head samples cleared")
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
        YdController.onPlace = null
        YdController.headReady = null
        seatFlow.cancel()
        placeRequested = false
        YdController.placingCancelled()
        main.removeCallbacksAndMessages(null)
        buildJob?.cancel()
        scope?.cancel()
        scope = null
        head.stop()
        inputs.stop()
        panelShown.clear()
        frames.clear()
        struts.clear()
        mirrorFollower.reset()
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
        readySent = false
        startSeat.reset()
        recent.clear()
        wheelGhost = emptyList()
        wheelSolid = null
        cabinMeshE = null
        seatIsGuess = false
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
        head.pose()?.let {
            if (Upright.valid(it)) {
                rawPose = it
                val p = debugPose(it)
                pose = p
                // HeadTracker keeps answering with its last sample: only a live one goes in (a stale tail cancels the flow, R5).
                // Judged here, right after reading the head, not between frames: at a low frame rate that would call it lost
                headLive = !head.lost(SystemClock.elapsedRealtime())
                if (headLive) recent.addLast(now to p)
                while (recent.size > 1 && now - recent.first().first > RECENT_MS) recent.removeFirst()
            }
        }
        val rig = rig ?: return
        rig.onFrame(now)
        pose?.let { rig.stowBehind(it) }
        checkBlocked(now)
        if (!attached || paused) return
        val state = YdController.state.value
        // R5 / D1: place first, then show what exists: P1's loading page and the road do not wait for the whole scene
        stepPlacement(state, now)
        if (!placed) return
        val s = seat ?: return
        val input = frameInput(state, s, dt.toDouble(), now)
        if (YdController.debugAutopilot) YdController.debugDrive(dt.toDouble())
        YdController.tick(input)
        val st = YdController.state.value
        applyCar(st, s, dt)
        stepPerf(st, dt, now)
        placePanels(st, s, dt)
        if (st.screen != Screen.LOADING) stepMirrors(st, s, now)
        stepEffects(st, s, input)
        stepWheelFade(st, s, dt)
        if (built && !readySent && now - placedAtMs >= MIN_LOADING_MS) {
            readySent = true
            YdController.onEvent(YdEvent.SceneReady)
        }
        stepStartupLog(now)
        if (now - lastLogMs > LOG_MS) {
            lastLogMs = now
            val r = YdController.run
            YdLog.i(String.format(Locale.US, "FRAME n=%d head=%s yaw=%.1f pitch=%.1f state=%s pose=%s wheel=%.0f v=%.2f gear=%s look=%s gapL=%s gapR=%s", frameCount, YdLog.pos(pose?.position), headDeg(pose?.yaw(0f)), headDeg(pose?.pitch), st.designState(), r.pose, r.sim.wheel, r.sim.v, r.sim.gear, input.look, st.run.gapL?.let { String.format(Locale.US, "%.3f", it) }, st.run.gapR?.let { String.format(Locale.US, "%.3f", it) }))
        }
    }

    private var rawPose: HeadPose? = null
    private var headLive = false

    /** Debug `headoff x,y,z`: the head moved in the Stage, for the D20 fade (the emulator camera stays where it is). */
    var debugHeadOffset: Vec3? = null

    /** Debug `vignette 0..1`: hold the comfort vignette at this strength to look at its edge. */
    var debugVignette: Float? = null

    private fun debugPose(p0: HeadPose): HeadPose {
        val p = debugHeadOffset?.let { HeadPose(p0.position + it, p0.rotation) } ?: p0
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
        if (state.screen == Screen.UNAVAILABLE && (seatFlow.active || placeRequested)) {
            seatFlow.cancel()
            placeRequested = false
            rig?.get(PanelIds.HINT)?.hide()
        }
        val p = pose
        if (p == null) {
            // R11 / D1: no head pose at all (not in Full Space): S20 still has to be seen, from a default eye
            if (!placed && state.screen == Screen.UNAVAILABLE) {
                applySeat(Seat(DEFAULT_EYE, 0f), "default")
                seatIsGuess = true
                placed = true
                placedAtMs = now
                cabinRoot?.enabled = true
            }
            return
        }
        if (!placed) {
            val first = startSeat.offer(now, p) ?: return
            applySeat(first, "first")
            placed = true
            placedAtMs = now
            cabinRoot?.enabled = true
            return
        }
        if (placeRequested) {
            placeRequested = false
            seatFlow.start(now, seatKey(state))
            YdLog.i("SEAT start page=${seatKey(state)} ${state.designState()}")
        }
        if (seatFlow.active) {
            val driving = state.screen in setOf(Screen.RUN, Screen.DEMO) && !state.paused
            when (val step = seatFlow.step(now, recent.toList(), seatKey(state), driving)) {
                is SeatFlow.Step.Waiting -> {
                    YdController.setLookLeft(step.leftS)
                    showLookHint(p)
                }
                is SeatFlow.Step.Place -> {
                    YdLog.i(String.format(Locale.US, "SEAT look=%.1f", seat?.lookRightDeg(step.seat.yaw) ?: 0.0))
                    applySeat(step.seat, "placed")
                    rig?.get(PanelIds.HINT)?.hide()
                    YdController.placingDone()
                }
                is SeatFlow.Step.Cancelled -> {
                    rig?.get(PanelIds.HINT)?.hide()
                    YdController.placingCancelled(step.lost)
                    YdLog.i("SEAT cancelled page=${seatKey(state)} lost=${step.lost}")
                }
                SeatFlow.Step.Idle -> Unit
            }
            return
        }
        stepPanelAway(state, p, now)
    }

    private fun seatKey(st: YdUiState) = "${st.screen}${st.paused}"

    /** The only line that stays with the head, and only for the few seconds of the flow (registered exception to D21). */
    private fun showLookHint(p: HeadPose) {
        val slot = rig?.get(PanelIds.HINT) ?: return
        val at = hintSpot(p)
        if (slot.shown) slot.moveTo(at) else slot.show(at)
        slot.face(p.position) // this panel only: HUB and the readouts stay turned to the seated eye
    }

    private fun hintSpot(p: HeadPose): Vec3 {
        val s = seat ?: return p.position + Yaw.dir(p.yaw(0f))
        return p.position + s.toStageDir(Seat.hintDir(s.lookRightDeg(p.yaw(0f))))
    }

    /**
     * S01 only: the panel out of view for a second gets one line where you are looking; the car stays put (R5, D21). The
     * line stands still and is only moved when you have looked well away from it.
     */
    private fun stepPanelAway(state: YdUiState, p: HeadPose, now: Long) {
        val slot = rig?.get(PanelIds.HINT) ?: return
        val cur = seat
        val off = if (cur != null && state.screen == Screen.SIT) cur.lookRightDeg(p.yaw(0f)) - Seat.P1_YAW_DEG else 0.0
        val out = abs(off) > if (state.panelAway != 0) AWAY_BACK_DEG else AWAY_OUT_DEG
        if (!out) {
            awayFromMs = 0L
            YdController.setPanelAway(0)
            slot.hide()
            return
        }
        if (awayFromMs == 0L) awayFromMs = now
        if (now - awayFromMs < AWAY_MS) return
        YdController.setPanelAway(if (off > 0) -1 else 1)
        val at = hintSpot(p)
        if (!slot.shown || (slot.position - at).length > HINT_MOVE_M) {
            slot.show(at)
            slot.face(p.position)
        }
    }

    private fun applySeat(s: Seat, why: String) {
        seat = s
        cabinRoot?.enabled = true
        cabinRoot?.setLocal(position = s.eye, rotation = s.rotation)
        shownKey = ""
        YdLog.i(String.format(Locale.US, "PLACE %s eye=%s yaw=%.1f", why, YdLog.pos(s.eye), Math.toDegrees(s.yaw.toDouble())))
    }

    /**
     * Debug `view DEG` (+ = look right): stands in for a camera the emulator cannot turn, by turning the cabin the other way.
     * It is not a way of placing the car: [viewOffsetDeg] stays 0 in a release build, the only placing path is [SeatFlow].
     */
    var viewOffsetDeg = 0f
    private var viewPitchDeg = 0f

    fun debugView(deg: Float, pitchDeg: Float = 0f) {
        viewOffsetDeg = if (deg == 0f && pitchDeg != 0f) 0.0001f else deg
        viewPitchDeg = pitchDeg
        rawPose?.let { applyPlacement(it, "view") }
    }

    private fun applyPlacement(p: HeadPose, why: String) {
        val s0 = Seat.place(p)
        val s = if (viewOffsetDeg == 0f) s0 else s0.copy(yaw = s0.yaw + Math.toRadians(viewOffsetDeg.toDouble()).toFloat(), debugPitch = Math.toRadians(-viewPitchDeg.toDouble()).toFloat())
        applySeat(s, why)
    }

    // ---------------------------------------------------------------- inputs (design steeringModel / driveModel)

    private fun frameInput(state: YdUiState, s: Seat, dt: Double, now: Long): FrameInput {
        val b = inputs.now
        val edge = { cur: Boolean, last: Boolean -> cur && !last }
        val input0 = FrameInput(dt, aEdge = edge(b.a, lastButtons.a), xEdge = edge(b.x, lastButtons.x), yEdge = edge(b.y, lastButtons.y), stickX = b.stickLX)
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
        return input0.copy(look = look, lookByEyes = gaze != null && inputs.debugGaze == null || inputs.debugGaze != null, lost = head.lost(now))
    }

    /** Debug `lost 1`: the head tracker reports no samples (S19). */
    fun debugLost(on: Boolean) {
        head.forceLost = on
    }

    private var lastGaze: Pair<Vec3, Vec3>? = null

    // ---------------------------------------------------------------- what the headset really does while driving (user, 2026-11-05: dizzy)

    private var perfFrames = 0
    private var perfMirrors = 0
    private var perfDropped = 0
    private var perfWorstMs = 0f
    private var perfSince = 0L
    private var driveLogAt = 0L

    private fun stepPerf(st: YdUiState, dt: Float, now: Long) {
        perfFrames++
        val ms = dt * 1000f
        if (ms > perfWorstMs) perfWorstMs = ms
        if (ms > PERF_DROP_MS) perfDropped++
        val r = YdController.run
        if (st.screen == Screen.RUN && now - driveLogAt >= DRIVE_LOG_MS) {
            driveLogAt = now
            YdLog.i(String.format(Locale.US, "DRIVE x=%.3f z=%.3f psi=%.1f wheel=%.0f v=%.2f gear=%s phase=%s seg=%s go=%s", r.pose.rear.x, r.pose.rear.z, r.pose.psiDeg, r.sim.wheel, r.sim.v, r.sim.gear, r.phase, r.seg, r.sim.go))
        }
        if (perfSince == 0L) perfSince = now
        val span = now - perfSince
        if (span < PERF_LOG_MS) return
        val k = 1000f / span
        // the world moves in this same frame system, once per rendered frame (applyCar), so its rate is the frame rate
        YdLog.i(String.format(Locale.US, "PERF fps=%.1f worldHz=%.1f mirrorHz=%.1f dropped=%d worstMs=%.0f v=%.2f yawDegS=%.1f vignette=%.2f screen=%s", perfFrames * k, perfFrames * k, perfMirrors * k, perfDropped, perfWorstMs, r.sim.v, Comfort.yawDegS(r.sim), vignetteK, st.screen))
        perfFrames = 0
        perfMirrors = 0
        perfDropped = 0
        perfWorstMs = 0f
        perfSince = now
    }

    // ---------------------------------------------------------------- the car and the yard

    private fun applyCar(st: YdUiState, s: Seat, dt: Float) {
        val r = YdController.run
        val eyeH = r.cfg.eyeH(st.settings)
        if (seatIsGuess) {
            cabinMeshE?.enabled = false
            wheel?.enabled = false
            knob?.enabled = false
            for ((_, fm) in frames) fm.first.enabled = false
            for ((_, bar) in struts) bar.enabled = false
        }
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
        // the yard tour's lines blink the same way, in their own colour made brighter: red is for a line you touched, cyan for the mirror frames (R3)
        val flashNow: Any? = if (st.tourFlash >= 0) st.tourFlash else fl
        if (flashNow != flashKey) {
            flashKey = flashNow
            when (flashNow) {
                is Int -> swap(Toon(64).also { paint(it, Yard.tourLines(flashNow).map { seg -> YardPaint(seg, false, width = 0.24) }, if (Yard.tourYellow(flashNow)) Palette.FLASH_YELLOW else Palette.FLASH_WHITE, 0.012f) }.b, flashMesh) { flashE?.enabled = true }
                is JudgeLine -> {
                    val seg = (if (r.cfg.lane) Yard.laneLines(r.cfg.gap) else Yard.judgeLines())[flashNow]
                    if (seg != null) swap(Toon(64).also { paint(it, listOf(YardPaint(seg, false, width = 0.20)), Palette.RED, 0.012f) }.b, flashMesh) { flashE?.enabled = true }
                }
                else -> flashE?.enabled = false
            }
        }
        if (flashNow != null) flashE?.enabled = YdController.debugFlashHold || ((SystemClock.elapsedRealtime() / 250L) % 2L) == 0L
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
        val up = st.screen != Screen.LOADING && st.screen != Screen.UNAVAILABLE
        val key = "${st.p1}${st.p2}$inRun$up"
        val eye = s.eye
        if (key != shownKey) {
            shownKey = key
            rig.faceViewer(eye)
            show(PanelIds.P1, st.p1, s.toStage(Seat.p1Local()))
            if (st.p1 && !p1LoggedMs) {
                p1LoggedMs = true
                YdLog.i("STARTUP p1 placed ms=${SystemClock.elapsedRealtime() - attachMs} state=${st.designState()} built=$built")
            }
            show(PanelIds.HUB, up, s.toStage(Seat.hubLocal()))
            show(PanelIds.MRD_L, inRun, s.toStage(Seat.MRD_L))
            show(PanelIds.MRD_R, inRun, s.toStage(Seat.MRD_R))
            for (spec in Mirrors.all) show(panelOf(spec), up, s.toStage(spec.pos.f()))
            mirrorFollower.reset()
        }
        mirrorFollower.step(st.settings.mirrorOut, st.settings.mirrorPitch)
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
    /** Mirror face, frame plate and bar turn together, in one call, from one normal (profile D35, critic5-B1). */
    private val mirrorFollower = MirrorFollower { spec, nd ->
        val n = nd.f()
        fun put(e: ModelEntity?, p: CarModel.Solid) = e?.setLocal(position = p.c.f(), rotation = facing(n), scale = Vec3((p.hx * 2).toFloat(), (p.hy * 2).toFloat(), (p.hz * 2).toFloat()))
        put(frames[spec.id]?.first, CarModel.framePlate(spec, nd))
        if (spec.id != Mirrors.Id.I) put(struts[spec.id], CarModel.mirrorStrut(spec, nd))
        val s = seat
        if (s != null) rig?.get(panelOf(spec))?.turnTo(s.toStage(spec.pos.f() + n))
    }

    private fun panelOf(spec: Mirrors.Spec) = when (spec.id) { Mirrors.Id.L -> PanelIds.MIR_L; Mirrors.Id.R -> PanelIds.MIR_R; Mirrors.Id.I -> PanelIds.MIR_I }

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
        perfMirrors++
        val r = YdController.run
        val hp = pose ?: return
        val e = s.toCabin(hp.position)
        val eye = V3(e.x.toDouble(), e.y.toDouble(), e.z.toDouble())
        val p = if (st.screen == Screen.DEMO && st.demoTopOnly) frozenPose ?: r.pose else r.pose
        val lane = r.cfg.lane && st.screen in setOf(Screen.RUN, Screen.DEMO, Screen.REPLAY)
        val pics = HashMap<Mirrors.Id, Mirrors.Picture>()
        val inLines = st.screen == Screen.TOUR && st.tour.step == 2
        val named = if (inLines) Yard.tourLines(st.tour.line) else emptyList()
        for (spec in Mirrors.all) {
            pics[spec.id] = Mirrors.picture(spec, p, r.cfg.eyeH(st.settings), eye, st.settings.mirrorOut.toDouble(), st.settings.mirrorPitch.toDouble(), YdController.polesOn(), lane, r.cfg.gap, st.flashLine, named, Yard.tourYellow(st.tour.line))
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
        val want = if (st.settings.comfort && moving) Comfort.vignette(r.sim.v, r.sim.vSet, Comfort.yawDegS(r.sim)) else 0f
        // no pop: it follows over about a third of a second
        vignetteK += (want - vignetteK) * (1f - kotlin.math.exp(-input.dt.toFloat() / 0.33f))
        val k = debugVignette ?: vignetteK
        val hp = pose
        if (k < 0.02f || hp == null) {
            v.enabled = false
            return
        }
        v.enabled = true
        // the layers multiply up to VIGNETTE_MAX * k at the outer edge (design said 25 %; user 2026-11-05: dizzy, so stronger when turning)
        mats?.vignette?.setOpacity(1f - Math.pow(1.0 - VIGNETTE_MAX * k, 1.0 / VIGNETTE_INNERS.size).toFloat())
        v.setLocal(position = hp.position + hp.forward * 0.5f, rotation = hp.rotation, scale = Vec3(2.2f, 2.2f, 2.2f))
    }

    /** Design D20: the wheel in the line of sight of a shown panel fades to 25 %. */
    private fun stepWheelFade(st: YdUiState, s: Seat, dt: Float) {
        val hp = pose ?: return
        val eye = s.toCabin(hp.position)
        var clear = 90.0
        if (st.p1) clear = minOf(clear, PanelCone.clearDeg(eye, PanelCone.p1()))
        if (st.p2) clear = minOf(clear, PanelCone.clearDeg(eye, PanelCone.p2(p2Pitch)))
        val was = wheelFade.faded
        val o = wheelFade.step(clear, dt)
        if (wheelFade.faded != was) YdLog.i(String.format(Locale.US, "FADE wheel faded=%s clear=%.1f eye=%s", wheelFade.faded, clear, YdLog.pos(eye)))
        val ghost = o < 0.999f
        wheelSolid?.enabled = !ghost
        for (g in wheelGhost) g.enabled = ghost
        if (ghost) {
            mats?.wheelGhost?.setOpacity(o)
            mats?.markGhost?.setOpacity(o)
        }
    }

    /** Debug `dump`: what the cone test sees right now. */
    fun fadeInfo(): String = "wheelOpacity=${wheelFade.opacity} faded=${wheelFade.faded}"

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
        YdLog.i(String.format(Locale.US, "STARTUP t=%ds head=%s yaw=%.1f pitch=%.1f eye=%s cabin=%s panels[%s]", logAt[logIndex - 1] / 1000, YdLog.pos(p?.position), headDeg(p?.yaw(0f)), headDeg(p?.pitch), YdLog.pos(seat?.eye), cabinRoot?.enabled, parts.joinToString("; ")))
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
            // your own tyres are round (Smooth, columnMesh's mesh); their boxes stay in CarModel for the sight-line tests
            if (bx.shade != CarModel.Shade.TIRE) t.box(c, bx.min.f(), bx.max.f(), bottom = true)
        }
        // A pillars: flat-sided trim, as on a real car; the steering column is round (columnMesh)
        for ((a, b) in CarModel.pillarsA) rod(t, a.f(), b.f(), CarModel.ROD_R.toFloat(), Palette.TRIM)
        Palette.SUN = keep
    }

    private fun rod(t: Toon, a: Vec3, b: Vec3, r: Float, color: Int) {
        val d = b - a
        val len = d.length
        val q = Quat4.fromTo(Vec3(0f, 0f, 1f), d / len)
        t.at(q, (a + b) * 0.5f) { box(color, Vec3(-r, -r, -len / 2), Vec3(r, r, len / 2), bottom = true) }
    }

    /** The three parked coach cars (yard frame): positions and headings as in v0.1.6, the cars themselves are [RoundParts.coachCar]. */
    private fun coachCars(t: Smooth) {
        for ((x, z, yaw) in COACHES) t.at(Quat4.axisAngle(Vec3.UP, yaw + (Math.PI / 2).toFloat()), Vec3(x, 0f, z)) { RoundParts.coachCar(this) }
    }

    // =========================================================================================

    /** Degrees for the log; NaN with no head pose. */
    private fun headDeg(rad: Float?): Double = rad?.let { Math.toDegrees(it.toDouble()) } ?: Double.NaN

    fun dump(): List<String> {
        val out = ArrayList<String>()
        val r = YdController.run
        // what the tracker says, before the debug head / view: a picture that sits low with pitch != 0 here is the emulator's camera, not the app
        val hp = runCatching { head.pose() }.getOrNull()
        out += "SETTINGS ${YdController.state.value.settings}"
        for (spec in Mirrors.all) {
            val n = mirrorNormal(spec)
            val z = rig?.get(panelOf(spec))?.facing?.rotation?.rotate(Vec3(0f, 0f, 1f))
            val want = seat?.toStageDir(n)
            val off = if (z != null && want != null) Math.toDegrees(kotlin.math.acos(z.dot(want.normalized()).coerceIn(-1f, 1f)).toDouble()) else Double.NaN
            out += String.format(Locale.US, "MIRROR %s face off the plate's normal by %.2f deg", spec.id, off)
        }
        out += "READOUT pose=${r.pose} wheel=${r.sim.wheel} gapL=${YdController.state.value.run.gapL} gapR=${YdController.state.value.run.gapR} advice=${YdController.state.value.run.advice}"
        out += String.format(Locale.US, "HEAD %s yaw=%.1f pitch=%.1f", YdLog.pos(hp?.position), headDeg(hp?.yaw(0f)), headDeg(hp?.pitch))
        out += "SCENE built=$built placed=$placed seat=${seat?.let { YdLog.pos(it.eye) }} yaw=${seat?.let { Math.toDegrees(it.yaw.toDouble()).toInt() }} pose=${r.pose} wheel=${r.sim.wheel} gear=${r.sim.gear}"
        out += "INSTANCE cabin model=procedural boxes=${CarModel.boxes.size} transparent=no opacityComponent=${cabinRoot?.hasOpacityComponent()}"
        out += "INSTANCE yardRoot model=procedural transparent=no opacityComponent=${yardRoot?.hasOpacityComponent()}"
        out += "INSTANCE wheel model=procedural torus rim=${RoundParts.RIM_SEG}x${RoundParts.TUBE_SEG} spokes=3 elliptic x12 hub=lathe40 mark=arc ${RoundParts.MARK_STEPS} steps smooth normals transparent=no"
        out += "INSTANCE gearKnob model=procedural lathe ball 24x12 smooth transparent=no; coaches model=procedural lofted x3 smooth transparent=no"
        out += "INSTANCE vignette transparent=yes(intended, colour only) enabled=${vignette?.enabled}; gazeDot opaque enabled=${gazeDot?.enabled}"
        out += "INSTANCE wheelGhost transparent=yes(intended, colour only, D20) ${fadeInfo()}"
        rig?.dump()?.let { out += it }
        return out
    }

    // =========================================================================================

    /** Materials: opaque Unlit with the colour atlas for every solid thing; colour-only transparents for the vignette / hand. */
    private class Mats(private val sh: SharedResources) {
        private val atlas = sh.keep(TextureResource.create(flipV(Palette.atlas())))
        val toon: UnlitMaterial = unlit(atlas)

        /** The smooth parts (Smooth.kt): the same colours as a ramp from shade to light, interpolated between vertices. */
        val smooth: UnlitMaterial = unlit(sh.keep(TextureResource.create(flipV(Ramp.bitmap()))))
        val asphalt: UnlitMaterial = unlit(sh.keep(TextureResource.create(flipV(asphaltTexture()))))
        val sky: UnlitMaterial = unlit(sh.keep(TextureResource.create(flipV(skyTexture()))))
        val sign: UnlitMaterial = unlit(sh.keep(TextureResource.create(flipV(signTexture()))))
        val gaze: UnlitMaterial = sh.keep(UnlitMaterial.create(BlendingMode.OPAQUE).apply { setBaseColor(Color4(0x7F / 255f, 0xD4 / 255f, 1f, 1f)); runCatching { setApplyToneMapping(false) } })
        val vignette: UnlitMaterial = sheer(Color4(0f, 0f, 0f, 1f), 0f)
        // front faces only: with the backs too a box blends several layers and 25 % looks like 60 %
        val wheelGhost: UnlitMaterial = sheer(paletteColor(Palette.WHEEL_RIM), 1f, MaterialCullingMode.BACK)
        val markGhost: UnlitMaterial = sheer(paletteColor(Palette.YELLOW), 1f, MaterialCullingMode.BACK)

        /** The atlas colour at its middle shade: what most of the solid wheel's faces show. */
        private fun paletteColor(c: Int): Color4 {
            val a = Palette.argb(c)
            val k = 0.86f / 255f
            return Color4(((a shr 16) and 255) * k, ((a shr 8) and 255) * k, (a and 255) * k, 1f)
        }

        private fun unlit(tex: TextureResource): UnlitMaterial =
            sh.keep(
                UnlitMaterial.create(BlendingMode.OPAQUE).apply {
                    setBaseColor(Color4(1f, 1f, 1f, 1f))
                    setBaseColorTexture(tex)
                    runCatching { setApplyToneMapping(false) }
                    setCullingMode(MaterialCullingMode.NONE)
                }
            )

        private fun sheer(c: Color4, opacity: Float, cull: MaterialCullingMode = MaterialCullingMode.NONE): UnlitMaterial =
            sh.keep(
                UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                    setBaseColor(c)
                    setOpacity(opacity)
                    runCatching { setApplyToneMapping(false) }
                    setCullingMode(cull)
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
        const val PLACE_WINDOW_MS = 300L
        const val SETTLE_MS = 2_000L
        const val MIN_LOADING_MS = 700L
        const val RECENT_MS = 1_000L
        const val AWAY_OUT_DEG = 60.0
        const val AWAY_BACK_DEG = 45.0
        const val AWAY_MS = 1_000L
        const val HINT_MOVE_M = 0.6f
        val DEFAULT_EYE = Vec3(0f, 1.4f, 0f)

        /** R4: eight overlapping rings, 3.5 % each; at 0.5 m in front of the head these inner edges are about 30°-50° off the view axis. */
        val VIGNETTE_INNERS = floatArrayOf(0.130f, 0.150f, 0.170f, 0.190f, 0.210f, 0.230f, 0.250f, 0.270f)
        const val BLOCK_AFTER_MS = 4_000L
        const val LOG_MS = 5_000L
        const val MIRROR_MS = 50L
        const val PERF_LOG_MS = 5_000L
        const val PERF_DROP_MS = 25f
        const val DRIVE_LOG_MS = 200L
        const val FADE_S = 0.5f
        const val GAZE_DIST = 6f
        const val GAZE_SIZE = 0.06f
        const val VIGNETTE_MAX = 0.40f
        /** Toward the sun: cabin frame (from the car's front left, as cabinMesh) and yard frame (Palette.SUN's default). */
        val CABIN_SUN = Vec3(-0.6f, 0.7f, -0.4f).normalized()
        val YARD_SUN = Vec3(-0.5f, 0.7071f, 0.5f).normalized()

        /** The parked coach cars: x, z (yard), heading about +Y. */
        val COACHES = listOf(Triple(-17f, 9f, 0.3f), Triple(15f, 9.5f, -0.2f), Triple(-15f, -15f, 1.4f))
        val FRAME_DARK = Color4(0x1E / 255f, 0x21 / 255f, 0x25 / 255f, 1f)
        val FRAME_GLOW = Color4(0x5B / 255f, 0xD6 / 255f, 0xC8 / 255f, 1f)
    }
}
