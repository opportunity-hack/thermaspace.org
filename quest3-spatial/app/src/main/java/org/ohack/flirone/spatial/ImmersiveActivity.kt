package org.ohack.flirone.spatial

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import com.meta.spatial.core.Color4
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Query
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.Vector3
import com.meta.spatial.isdk.IsdkFeature
import com.meta.spatial.runtime.MicrogestureBits
import com.meta.spatial.toolkit.Box
import com.meta.spatial.toolkit.Material
import com.meta.spatial.toolkit.MicrogesturesSystem
import com.meta.spatial.mruk.MRUKAnchor
import com.meta.spatial.mruk.MRUKEnvironmentRaycastHitResult
import com.meta.spatial.mruk.MRUKFeature
import com.meta.spatial.mruk.MRUKLoadDeviceResult
import com.meta.spatial.mruk.SurfaceType
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.DpDisplayOptions
import com.meta.spatial.toolkit.Grabbable
import com.meta.spatial.toolkit.LayoutXMLPanelRegistration
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelRenderMode
import com.meta.spatial.toolkit.PlayerBodyAttachmentSystem
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.TransformParent
import com.meta.spatial.toolkit.UIPanelRenderOptions
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.toolkit.createPanelEntity
import com.meta.spatial.vr.VRFeature
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * FLIR One spatial heatmap.
 *
 * UX model:
 *  - passthrough room + grabbable control panel (live thermal + buttons)
 *  - "Overlay" head-locks a semi-transparent live thermal quad at the camera's
 *    real FOV, so the thermal image sits on top of what you're looking at
 *  - capture (button or ½-second left pinch) raycasts your gaze against the
 *    MRUK room; the snapshot lands flush on the wall/floor/table it hit,
 *    scaled to the true projected size, and is parented to that scene anchor
 *    so it survives recenters and sessions; free-floats at 1 m if no surface
 *  - snapshots stay grabbable for manual fine-alignment
 */
class ImmersiveActivity : AppSystemActivity() {

    private companion object {
        const val ACTION_USB = "org.ohack.flirone.spatial.USB_PERMISSION"
        const val PERMISSION_USE_SCENE = "com.oculus.permission.USE_SCENE"
        const val REQUEST_USE_SCENE = 1
        // FLIR One G3 thermal FOV ~50°x38° -> quad size per 1 m of distance
        const val CAPTURE_DIST = 1.0f
        const val SNAP_W = 0.93f
        const val SNAP_H = 0.70f
        const val MAX_RAY_DIST = 6f
        const val SURFACE_GAP = 0.02f
        // free-space ink + wand
        // 150 mm per field preference — long enough that small wrist rotations
        // give fine tip control (pen lever), short enough that hand tremor at
        // the tip stays manageable (Tilt/Open Brush pointers sit at 80-120 mm)
        const val WAND_LEN = 0.15f       // ink emits at the wand tip
        const val WAND_R = 0.003f        // shaft half-width (octagonal ≈ round)
        const val INK_HALF = 0.006f      // stroke half-thickness (m)
        const val INK_STEP = 0.015f      // min distance between points
        const val INK_MAX_STROKE = 600   // points per stroke
        const val INK_MAX_TOTAL = 5000   // segments across all strokes
        const val INK_HANDLE_R = 0.022f  // grab-knob radius
    }

    private lateinit var mrukFeature: MRUKFeature
    @Volatile private var flir: FlirOne? = null   // mutated only on main thread
    private val renderer = ThermalRenderer()
    private val server = MjpegServer(8081)
    private lateinit var store: SnapshotStore
    private val mainHandler = Handler(Looper.getMainLooper())
    private val headLock = HeadLockSystem()

    // views inside panels
    private var liveView: ImageView? = null
    private var overlayView: ImageView? = null
    private var statusText: TextView? = null
    private var overlayBtn: Button? = null
    private var snapBtn: Button? = null
    private var helpScroll: ScrollView? = null
    private var helpBtn: Button? = null

    // per-entity payload for snapshot panels: bitmap + gallery id when a
    // placed capture (the id enables its 🔒 live-lock icon; recalls pass null).
    // Keyed by entity — the old shared FIFO mis-paired content (and would now
    // mis-pair lock bindings) whenever an entity died before its panel bound.
    private class PendingPanel(val bmp: Bitmap, val snapId: Long?)
    private val pendingByEntity = java.util.concurrent.ConcurrentHashMap<Entity, PendingPanel>()
    private val snapEntities = LinkedHashMap<Long, Entity>()
    // slow persistence (PNG/JPEG encode, index writes) stays off the UI thread
    private val ioExecutor = java.util.concurrent.Executors.newSingleThreadExecutor {
        r -> Thread(r, "snap-io")
    }
    private var controlPanel: Entity? = null
    private var overlayEntity: Entity? = null
    private var overlayOn = false
    private var snapToSurfaces = true
    private var sceneIsReady = false
    @Volatile private var resumed = false

    private var statusMs = 0L               // statusThrottled timestamp
    @Volatile private var lastFrameMs = 0L
    private var shareBtn: Button? = null

    // live-locked captures: placed snapshots that refresh from the camera
    // whenever you gaze at them (the camera rides on your head, so looking at
    // the quad points the FLIR at that same spot)
    private class LockBinding(val image: ImageView, val button: Button) {
        var live = false            // gazed right now (live-updating)
        var bound: Bitmap? = null   // what the ImageView currently shows
    }
    private val lockedIds = mutableSetOf<Long>()
    private val lockViews = HashMap<Long, LockBinding>()
    private val snapParents = HashMap<Long, Entity?>()   // for world-pose gaze tests

    // free-space ink drawing (Open Brush-style: strokes follow the controller
    // tip in mid-air — no surface projection at all)
    @Volatile private var drawMode = false
    private var drawBtn: Button? = null
    private class Ink {
        val points = ArrayList<Vector3>()   // LOCAL to the handle
        val entities = ArrayList<Entity>()  // segment children of the handle
        var handle: Entity? = null          // grabbable knob — moves the group
        var radius = 0f                     // bounding-sphere radius (handle-local)
        fun grow(p: Vector3) {
            val r = sqrt(p.x * p.x + p.y * p.y + p.z * p.z)
            if (r > radius) radius = r
        }
    }
    private val inks = ArrayList<Ink>()
    @Volatile private var inking = false           // trigger currently held
    @Volatile private var inkDirty = false
    private var lastDrawMs = 0L

    // span editor + gallery
    private var spanRow: View? = null
    private var spanBtnRef: Button? = null
    private var galleryView: View? = null
    private var galleryList: android.widget.ListView? = null
    private var placeholderView: TextView? = null
    private val recalledEntities = ArrayDeque<Entity>()
    private val recalledSnaps = ArrayList<SnapshotStore.Snap>()   // current compare set
    private var recallOrigin: Pose? = null                        // grid anchor (first recall)
    private var renameBar: View? = null
    private var renameInput: android.widget.EditText? = null
    private var renameTargetId: Long? = null

    // date formats hoisted out of list-row binds (SimpleDateFormat is not
    // thread-safe: one instance per thread that formats)
    private val galleryDateFmt =
        java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.US)  // UI thread
    private val recallDateFmt =
        java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.US)  // IO thread

    // controller hint cards
    private val hintSystem = ControllerHintSystem()
    private var hintRTrigger: TextView? = null
    private var palBtnRef: Button? = null
    private val selectedIds = mutableSetOf<Long>()   // gallery multi-select

    // camera connection state
    private enum class CamState { SEARCHING, PERMISSION, ASLEEP, STREAMING }
    @Volatile private var camState = CamState.SEARCHING

    // first-launch guided tour (NUX): learn-by-doing steps on one card;
    // steps 1-3 auto-advance when the user actually does the thing
    private var nuxCard: View? = null
    private var nuxTitle: TextView? = null
    private var nuxBody: TextView? = null
    private var nuxProgress: TextView? = null
    private var nuxNextBtn: Button? = null
    private var nuxStep = -1   // -1 = tour not running
    private class NuxStep(val title: String, val body: String, val next: String)
    private val nuxSteps = listOf(
        NuxStep("👋 Welcome to ThermaSpace",
            "See the invisible: live thermal vision for your home, from a FLIR One " +
            "camera on your headset. This one-minute tour teaches the basics by doing.",
            "▸ Start"),
        NuxStep("🔌 1 · Connect the camera",
            "Plug the FLIR One into the headset's USB-C port and press its side " +
            "button — the LED pulses. Allow USB access when asked. This step " +
            "finishes by itself when heat appears below.",
            "▸ Next"),
        NuxStep("👁 2 · Thermal overlay",
            "Tap 👁 Overlay below (or press the A button). Heat covers the room as " +
            "you look around — try your hand, a window, or a hot drink.",
            "▸ Next"),
        NuxStep("📸 3 · Stamp heat onto the room",
            "Look at something interesting and press 📸 Capture (or click the right " +
            "thumbstick 🕹). The image sticks to the room right where you looked — " +
            "walk around it!",
            "▸ Next"),
        NuxStep("🎉 You're ready",
            "Captures are saved in 🔬 Tools → 🗂 Gallery: name them, recall them, " +
            "compare on one temperature scale. Look at a controller or hand to see " +
            "what its buttons do. Press ? anytime for the full guide.",
            "✓ Done"),
    )

    // ---------------------------------------------------------- lifecycle

    override fun registerFeatures(): List<SpatialFeature> {
        mrukFeature = MRUKFeature(this, systemManager)
        // IsdkFeature makes hand pinches (and controller rays) drive panel UI —
        // without it hands could grab but never CLICK buttons
        return listOf(VRFeature(this), IsdkFeature(this, spatial, systemManager), mrukFeature)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SnapshotStore(this)
        Analytics.init(java.io.File(filesDir, "analytics.json"), { server.log(it) })
        server.statsJson = { Analytics.statsJson() }
        // renderer prefs must load BEFORE onSceneReady creates the overlay
        // entity, whose quad aspect depends on rotation (portrait vs landscape)
        getSharedPreferences("flir", MODE_PRIVATE).let { prefs ->
            renderer.rotationDeg = prefs.getInt("rot", 0)
            renderer.paletteIndex = prefs.getInt("pal", 0)
            renderer.useFahrenheit = prefs.getBoolean("fahrenheit", false)
        }
        server.renderer = renderer
        // export streams on the HTTP thread: SnapshotStore is synchronized and
        // capture files are immutable, so no main-thread marshalling needed
        server.hasExport = { store.loadIndex().isNotEmpty() }
        server.exportTo = { out -> buildExportZip(out) }
        // debug builds: server open (dev workflow); release: opt-in + session key
        server.debugEndpoints = BuildConfig.DEBUG
        server.requireKey = !BuildConfig.DEBUG
        server.sharingEnabled = BuildConfig.DEBUG
        server.start()
        // version in /log so we always know which build is being field-tested
        server.log("ThermaSpace v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE}) boot")
        registerReceiver(usbReceiver, IntentFilter(ACTION_USB).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        }, RECEIVER_NOT_EXPORTED)
        mainHandler.postDelayed(::cameraWatch, 4000)

        systemManager.registerSystem(headLock)
        systemManager.registerSystem(hintSystem)
        // hands: thumb microgestures (pinches are reserved for UI selection).
        // The SDK auto-registers this system — attach, don't re-register.
        (systemManager.tryFindSystem<MicrogesturesSystem>()
            ?: MicrogesturesSystem().also { systemManager.registerSystem(it) })
            .addListener(::onMicrogestureEvent)
        // physical controllers: face buttons + right-trigger air drawing
        systemManager.registerSystem(ControllerInputSystem(
            onInkHeld = { pose -> inkHeld(pose) },
            onInkReleased = ::inkReleased,
            onButtonA = { runOnUiThread { setOverlay(!overlayOn) } },
            onButtonB = { runOnUiThread { setDrawMode(!drawMode) } },
            onButtonX = { runOnUiThread { undo() } },
            onButtonY = { runOnUiThread { summonPanel() } },
            onMenu = { runOnUiThread { summonPanel() } },
            onStickR = { runOnUiThread { capture("stick") } },
            onStickMove = ::stickMove,
            onRightAim = ::rightAim,
        ))

        if (checkSelfPermission(PERMISSION_USE_SCENE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(PERMISSION_USE_SCENE), REQUEST_USE_SCENE)
        }
        mainHandler.postDelayed(::syncMovedSnapshots, 10_000)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_USE_SCENE) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) loadMrukScene()
            else setStatus("Scene permission denied — captures will float freely")
        }
    }

    /** Load the room scan. Must run after the XR session is up (onSceneReady);
     *  transient failures are retried — result is surfaced to the user. */
    private var mrukLoadAttempts = 0
    private var sceneCaptureRequested = false
    private fun loadMrukScene() {
        if (!sceneIsReady) { mainHandler.postDelayed(::loadMrukScene, 1000); return }
        if (checkSelfPermission(PERMISSION_USE_SCENE) != PackageManager.PERMISSION_GRANTED) return
        mrukFeature.loadSceneFromDevice().whenComplete { result: MRUKLoadDeviceResult, err ->
            server.log("MRUK loadSceneFromDevice #$mrukLoadAttempts: $result err=$err")
            mainHandler.post {
                if (result == MRUKLoadDeviceResult.SUCCESS) {
                    setStatus("Room scan loaded — captures snap to walls and persist")
                    resolveAnchoredSnapshots()
                } else if (result == MRUKLoadDeviceResult.ERROR_NO_ROOMS_FOUND) {
                    if (!sceneCaptureRequested) {
                        // launch the system Space Setup flow directly, then reload
                        sceneCaptureRequested = true
                        setStatus("No room scan — starting Space Setup, follow the guided scan…")
                        mrukFeature.requestSceneCapture().whenComplete { _, _ ->
                            mainHandler.post { mrukLoadAttempts = 0; loadMrukScene() }
                        }
                    } else {
                        setStatus("Still no room scan — painting and wall-snap are disabled until one exists")
                    }
                } else if (++mrukLoadAttempts < 5) {
                    mainHandler.postDelayed(::loadMrukScene, 3000)
                } else {
                    setStatus("Room scan failed to load ($result) — captures use depth sensor")
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        Analytics.sessionStart()
        // camera may have been plugged in while we were paused
        if (flir == null && statusText != null) findAndOpenCamera()
        // restores skipped while paused (resumed guard) get another chance
        resolveRetries = 0
        if (sceneIsReady) mainHandler.post(::resolveAnchoredSnapshots)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // USB_DEVICE_ATTACHED relaunches this singleTask activity
        if (flir == null && statusText != null) findAndOpenCamera()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        Analytics.sessionEnd()
        // sharing is session-scoped: never stays on when the app leaves focus
        if (!BuildConfig.DEBUG && server.sharingEnabled) setSharing(false)
        // don't keep streaming (and draining both batteries) while
        // backgrounded — onResume/cameraWatch reconnect automatically
        stopCameraAsync(CamState.SEARCHING)
    }

    override fun onDestroy() {
        super.onDestroy()
        resumed = false
        mainHandler.removeCallbacksAndMessages(null)
        unregisterReceiver(usbReceiver)
        stopCameraAsync(CamState.SEARCHING)
        server.stop()
        ioExecutor.shutdown()
    }

    override fun onSceneReady() {
        super.onSceneReady()
        scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)
        scene.enablePassthrough(true)

        controlPanel = Entity.createPanelEntity(
            R.id.panel_control, Transform(Pose(Vector3(0f, 1.2f, 1.2f))), Grabbable())

        createOverlayEntity()

        // hint cards (hidden until the user looks at a controller or hand)
        fun hintCard(id: Int) = Entity.create(
            Panel(id).apply { hittable = MeshCollision.NoCollision },
            Transform(Pose(Vector3(0f, -10f, 0f))), Visible(false))
        hintSystem.leftCard = hintCard(R.id.panel_hints_left)
        hintSystem.rightCard = hintCard(R.id.panel_hints_right)
        hintSystem.leftHandCard = hintCard(R.id.panel_hints_hand_left)
        hintSystem.rightHandCard = hintCard(R.id.panel_hints_hand_right)

        restoreInk()   // world-locked strokes need no MRUK

        sceneIsReady = true
        loadMrukScene()
        val depthResult = mrukFeature.startEnvironmentRaycaster()
        server.log("startEnvironmentRaycaster: $depthResult")
        mainHandler.postDelayed({ placeInFrontOfUser(controlPanel, 0.75f, tiltDeg = 15f) }, 2000)

        // world-posed snapshots restore immediately; anchored ones wait for MRUK
        for (snap in store.loadIndex().filter { it.anchorUuid == null && it.inWorld }) {
            val bmp = store.loadBitmap(snap) ?: continue
            if (snap.locked) lockedIds.add(snap.id)
            spawnSnapshotEntity(snap.id, bmp, snap.pose, parent = null, scale = snap.scale)
        }
    }

    override fun onRecenter(isUserInitiated: Boolean) {
        super.onRecenter(isUserInitiated)
        mainHandler.postDelayed({ placeInFrontOfUser(controlPanel, 0.75f, tiltDeg = 15f) }, 500)
    }

    /** Re-parent persisted snapshots to their MRUK anchors once the room is loaded. */
    private var resolveRetries = 0
    private var resolvePending = false
    private fun resolveAnchoredSnapshots() {
        resolvePending = false
        if (!resumed || !sceneIsReady) return   // retried from onResume
        val anchored = store.loadIndex().filter {
            it.anchorUuid != null && it.inWorld && !snapEntities.containsKey(it.id)
        }
        if (anchored.isEmpty()) return
        val anchorsByUuid = mrukAnchorEntities().associateBy { (_, a) -> a.uuid.toString() }
        var missing = 0
        for (snap in anchored) {
            // anchor check BEFORE the PNG decode: a capture whose anchor is
            // gone (re-scanned room) must not cost a full decode per retry
            val parent = anchorsByUuid[snap.anchorUuid]?.first
            if (parent == null) { missing++; continue }
            val bmp = store.loadBitmap(snap) ?: continue
            if (snap.locked) lockedIds.add(snap.id)
            spawnSnapshotEntity(snap.id, bmp, snap.pose, parent, snap.scale)
        }
        // bounded, single retry chain — this used to retry every 3 s forever
        // (and onResume stacked extra chains) when an anchor no longer exists
        if (missing > 0 && !resolvePending && resolveRetries++ < 10) {
            resolvePending = true
            server.log("$missing anchored snapshots not yet resolvable (retry $resolveRetries)")
            mainHandler.postDelayed(::resolveAnchoredSnapshots, 3000)
        }
    }

    private fun mrukAnchorEntities(): List<Pair<Entity, MRUKAnchor>> =
        Query.where { has(MRUKAnchor.id, Transform.id) }
            .eval()
            .map { it to it.getComponent<MRUKAnchor>() }
            .toList()

    // ---------------------------------------------------------- panels

    override fun registerPanels(): List<PanelRegistration> = listOf(
        LayoutXMLPanelRegistration(
            R.id.panel_control,
            layoutIdCreator = { R.layout.control_panel },
            settingsCreator = {
                UIPanelSettings(
                    shape = QuadShapeOptions(width = 0.62f, height = 0.70f),
                    // fixed dp resolution: buttons get real estate regardless of
                    // physical panel size (0.62 m / 720 dp ≈ comfortable 46 dp rows)
                    display = DpDisplayOptions(width = 720f, height = 815f),
                )
            },
            panelSetupWithRootView = { rootView, _, _ -> bindControlPanel(rootView) },
        ),
        snapshotRegistration(R.id.panel_snapshot, portrait = false),
        snapshotRegistration(R.id.panel_snapshot_portrait, portrait = true),
        overlayRegistration(R.id.panel_overlay, portrait = false),
        overlayRegistration(R.id.panel_overlay_portrait, portrait = true),
        hintRegistration(R.id.panel_hints_left, R.layout.controller_hints_left),
        hintRegistration(R.id.panel_hints_right, R.layout.controller_hints_right),
        hintRegistration(R.id.panel_hints_hand_left, R.layout.hand_hints_left),
        hintRegistration(R.id.panel_hints_hand_right, R.layout.hand_hints_right),
    )

    private fun hintRegistration(id: Int, layout: Int) =
        LayoutXMLPanelRegistration(
            id,
            layoutIdCreator = { layout },
            settingsCreator = {
                UIPanelSettings(
                    // 280dp at the same density (~1160dp/m) = 0.24m wide:
                    // room for the longest hand-gesture label without shrinking text
                    shape = QuadShapeOptions(width = 0.24f, height = 0.176f),
                    display = DpDisplayOptions(width = 280f, height = 204f),
                    rendering = UIPanelRenderOptions(PanelRenderMode.Mesh()),
                )
            },
            panelSetupWithRootView = { rootView, _, _ ->
                // right card's trigger row follows the active mode
                hintRTrigger = rootView.findViewById(R.id.hint_r_trigger) ?: hintRTrigger
            },
        )

    private fun snapshotRegistration(id: Int, portrait: Boolean) =
        LayoutXMLPanelRegistration(
            id,
            layoutIdCreator = { R.layout.snapshot_panel },
            settingsCreator = {
                UIPanelSettings(
                    shape = if (portrait) QuadShapeOptions(width = SNAP_H, height = SNAP_W)
                            else QuadShapeOptions(width = SNAP_W, height = SNAP_H),
                    // in-scene mesh, NOT a compositor layer: the XR runtime
                    // hard-caps layers (~16) and each default panel eats one —
                    // with many snapshots every xrEndFrame fails
                    // (XR_ERROR_LAYER_LIMIT_EXCEEDED) and the view goes black
                    rendering = UIPanelRenderOptions(PanelRenderMode.Mesh()),
                )
            },
            panelSetupWithRootView = { rootView, _, entity ->
                // the payload is mapped right AFTER createPanelEntity returns —
                // if inflation ran synchronously, retry on the next main tick
                if (!bindSnapshotPanel(rootView, entity))
                    mainHandler.post { bindSnapshotPanel(rootView, entity) }
            },
        )

    /** Wire a snapshot panel to its per-entity payload; false if the payload
     *  isn't registered yet. */
    private fun bindSnapshotPanel(rootView: View, entity: Entity): Boolean {
        val p = pendingByEntity.remove(entity) ?: return false
        val iv = rootView.findViewById<ImageView>(R.id.snap_image)
        iv.setImageBitmap(p.bmp)
        val snapId = p.snapId
        if (snapId != null) {       // placed capture: enable 🔒
            val lockBtn = rootView.findViewById<Button>(R.id.snap_lock)
            lockBtn.visibility = View.VISIBLE
            lockViews[snapId] = LockBinding(iv, lockBtn).also { it.bound = p.bmp }
            styleLockButton(snapId)
            lockBtn.setOnClickListener { toggleSnapLock(snapId) }
        }
        return true
    }

    private fun overlayRegistration(id: Int, portrait: Boolean) =
        LayoutXMLPanelRegistration(
            id,
            layoutIdCreator = { R.layout.overlay_panel },
            settingsCreator = {
                UIPanelSettings(
                    shape = if (portrait) QuadShapeOptions(width = SNAP_H, height = SNAP_W)
                            else QuadShapeOptions(width = SNAP_W, height = SNAP_H))
            },
            panelSetupWithRootView = { rootView, _, _ ->
                overlayView = rootView.findViewById(R.id.overlay_image)
            },
        )

    private fun bindControlPanel(root: View) {
        liveView = root.findViewById(R.id.live_view)
        statusText = root.findViewById(R.id.status_text)
        overlayBtn = root.findViewById(R.id.btn_overlay)
        snapBtn = root.findViewById(R.id.btn_snap)
        drawBtn = root.findViewById(R.id.btn_draw)
        drawBtn?.setOnClickListener { setDrawMode(!drawMode) }
        placeholderView = root.findViewById(R.id.camera_placeholder)
        galleryView = root.findViewById(R.id.gallery_view)
        galleryList = root.findViewById(R.id.gallery_list)
        root.findViewById<Button>(R.id.btn_gallery).setOnClickListener { toggleGallery() }
        root.findViewById<Button>(R.id.btn_recall_sel).setOnClickListener { recallSelected() }
        root.findViewById<Button>(R.id.btn_untick).setOnClickListener {
            selectedIds.clear(); refreshGallery()
        }
        // inline rename bar (dialogs don't render inside spatial panels)
        renameBar = root.findViewById(R.id.rename_bar)
        renameInput = root.findViewById(R.id.rename_input)
        val commitRename = {
            renameTargetId?.let { id ->
                store.renameSnap(id, renameInput?.text?.toString()?.trim() ?: "")
                Analytics.event("rename")
                refreshGallery()
                setStatus("capture renamed")
            }
            renameTargetId = null
            renameBar?.visibility = View.GONE
        }
        root.findViewById<Button>(R.id.rename_save).setOnClickListener { commitRename() }
        root.findViewById<Button>(R.id.rename_cancel).setOnClickListener {
            renameTargetId = null
            renameBar?.visibility = View.GONE
        }
        renameInput?.setOnEditorActionListener { _, _, _ -> commitRename(); true }
        // word chips: names are composed by tapping (the system keyboard does
        // not reach EditTexts inside spatial panels)
        root.findViewById<Button>(R.id.rename_back).setOnClickListener {
            val cur = renameInput?.text?.toString()?.trimEnd() ?: ""
            renameInput?.setText(cur.substringBeforeLast(' ', ""))
        }
        val dp = { v: Int -> (v * root.resources.displayMetrics.density).toInt() }
        fun addChip(row: android.widget.LinearLayout, label: String) {
            val b = Button(this)
            b.text = label; b.isAllCaps = false; b.textSize = 13f
            b.setTextColor(0xFFF2F5FA.toInt())
            b.backgroundTintList =
                android.content.res.ColorStateList.valueOf(0xFF2A3038.toInt())
            b.minWidth = dp(44); b.minimumWidth = dp(44)
            b.setPadding(dp(8), 0, dp(8), 0)
            b.layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)
            ).apply { marginEnd = dp(4) }
            b.setOnClickListener {
                val cur = renameInput?.text?.toString()?.trim() ?: ""
                renameInput?.setText(if (cur.isEmpty()) label else "$cur $label")
            }
            row.addView(b)
        }
        val chipRow1 = root.findViewById<android.widget.LinearLayout>(R.id.chip_row1)
        val chipRow2 = root.findViewById<android.widget.LinearLayout>(R.id.chip_row2)
        if (chipRow1.childCount == 0) {
            listOf("Bedroom", "Bathroom", "Kitchen", "Living", "Office", "Garage", "Yard")
                .forEach { addChip(chipRow1, it) }
            listOf("1", "2", "3", "4", "Window", "Door", "Wall", "Ceiling", "Floor", "Grass")
                .forEach { addChip(chipRow2, it) }
        }
        helpScroll = root.findViewById(R.id.help_scroll)
        helpBtn = root.findViewById(R.id.btn_help)
        root.findViewById<TextView>(R.id.help_text).text = helpText()
        root.findViewById<Button>(R.id.btn_capture).setOnClickListener { capture("btn") }
        root.findViewById<Button>(R.id.btn_undo).setOnClickListener { undo() }
        root.findViewById<Button>(R.id.btn_clear).setOnClickListener { clearAll() }
        overlayBtn?.setOnClickListener { setOverlay(!overlayOn) }
        helpBtn?.setOnClickListener {
            val show = helpScroll?.visibility != View.VISIBLE
            if (show) Analytics.event("help_open")
            setHelp(show)
        }
        shareBtn = root.findViewById(R.id.btn_share)
        shareBtn?.text = if (server.sharingEnabled) shareLabelOn() else "📡 Off"
        shareBtn?.setOnClickListener { setSharing(!server.sharingEnabled) }

        // 🔬 Tools: pro features live in a collapsible section so the default
        // panel stays approachable (Capture / Overlay / Snap / Undo / Clear)
        val toolsRows = root.findViewById<View>(R.id.tools_rows)
        val toolsBtn = root.findViewById<Button>(R.id.btn_tools)
        fun setTools(open: Boolean) {
            toolsRows.visibility = if (open) View.VISIBLE else View.GONE
            toolsBtn.text = if (open) "🔬 Tools ▴" else "🔬 Tools ▾"
            getSharedPreferences("flir", MODE_PRIVATE)
                .edit().putBoolean("toolsOpen", open).apply()
        }
        setTools(getSharedPreferences("flir", MODE_PRIVATE).getBoolean("toolsOpen", false))
        toolsBtn.setOnClickListener {
            Analytics.event("tools_toggle")
            setTools(toolsRows.visibility != View.VISIBLE)
        }
        snapBtn?.setOnClickListener {
            Analytics.event("snap_toggle")
            snapToSurfaces = !snapToSurfaces
            snapBtn?.text = if (snapToSurfaces) "🧲 Snap On" else "🧲 Snap Off"
            setStatus(if (snapToSurfaces)
                "Snap ON — captures stick to walls at true size"
            else "Snap OFF — captures float 1 m in front of you")
        }

        // imaging mode controls (renderer state already loaded in onCreate —
        // here we only sync the button labels)
        val prefs = getSharedPreferences("flir", MODE_PRIVATE)
        val unitBtn = root.findViewById<Button>(R.id.btn_unit)
        unitBtn.text = renderer.unitSuffix
        unitBtn.setOnClickListener {
            Analytics.event("unit")
            renderer.useFahrenheit = !renderer.useFahrenheit
            unitBtn.text = renderer.unitSuffix
            prefs.edit().putBoolean("fahrenheit", renderer.useFahrenheit).apply()
            setStatus("temperatures now in ${if (renderer.useFahrenheit) "Fahrenheit" else "Celsius"}")
        }
        val rotBtn = root.findViewById<Button>(R.id.btn_rotate)
        val palBtn = root.findViewById<Button>(R.id.btn_palette)
        palBtnRef = palBtn   // microgesture palette swipes update the label too
        val spanBtn = root.findViewById<Button>(R.id.btn_span)
        val isoBtn = root.findViewById<Button>(R.id.btn_iso)
        rotBtn.text = "⟳ ${renderer.rotationDeg}°"
        palBtn.text = "🎨 " + renderer.paletteName
        rotBtn.setOnClickListener {
            Analytics.event("rotate")
            setStatus(renderer.cycleRotation())
            rotBtn.text = "⟳ ${renderer.rotationDeg}°"
            prefs.edit().putInt("rot", renderer.rotationDeg).apply()
            createOverlayEntity()   // overlay quad aspect follows orientation
        }
        palBtn.setOnClickListener {
            Analytics.event("palette")
            palBtn.text = "🎨 " + renderer.cyclePalette()
            prefs.edit().putInt("pal", renderer.paletteIndex).apply()
        }
        spanBtnRef = spanBtn
        spanRow = root.findViewById(R.id.span_row)
        spanBtn.setOnClickListener {
            Analytics.event("span_toggle")
            setStatus(renderer.toggleSpanLock())
            spanBtn.text = if (renderer.spanLockC != null) "🔒 Locked" else "📏 Auto"
            updateSpanRow()
            persistSpan(prefs)
        }
        val stepC = { low: Boolean, up: Boolean ->
            val stepDisp = if (up) 1.0 else -1.0
            val deltaC = if (renderer.useFahrenheit) stepDisp * 5.0 / 9.0 else stepDisp
            setStatus(renderer.nudgeSpan(low, deltaC))
            persistSpan(prefs)
        }
        root.findViewById<Button>(R.id.btn_lo_dn).setOnClickListener { stepC(true, false) }
        root.findViewById<Button>(R.id.btn_lo_up).setOnClickListener { stepC(true, true) }
        root.findViewById<Button>(R.id.btn_hi_dn).setOnClickListener { stepC(false, false) }
        root.findViewById<Button>(R.id.btn_hi_up).setOnClickListener { stepC(false, true) }
        // restore a persisted locked span
        if (prefs.contains("spanLo")) {
            renderer.spanLockC = prefs.getFloat("spanLo", 15f).toDouble() to
                                 prefs.getFloat("spanHi", 35f).toDouble()
            spanBtn.text = "🔒 Locked"
        }
        updateSpanRow()
        isoBtn.setOnClickListener {
            Analytics.event("iso")
            setStatus(renderer.cycleIso())
            isoBtn.text = when (renderer.isoMode) {
                ThermalRenderer.IsoMode.OFF -> "Iso Off"
                ThermalRenderer.IsoMode.HOT -> "Iso 🔥"
                ThermalRenderer.IsoMode.COLD -> "Iso ❄️"
            }
        }

        // first launch: guided tour instead of dumping the full manual
        nuxCard = root.findViewById(R.id.nux_card)
        nuxTitle = root.findViewById(R.id.nux_title)
        nuxBody = root.findViewById(R.id.nux_body)
        nuxProgress = root.findViewById(R.id.nux_progress)
        nuxNextBtn = root.findViewById(R.id.nux_next)
        nuxNextBtn?.setOnClickListener {
            if (nuxStep < 0) return@setOnClickListener
            if (nuxStep >= nuxSteps.size - 1) endNux(done = true)
            else showNuxStep(nuxStep + 1)
        }
        root.findViewById<Button>(R.id.nux_skip).setOnClickListener {
            if (nuxStep >= 0) endNux(done = false)
        }
        startNuxIfNeeded(prefs)
        findAndOpenCamera()
    }

    private fun setHelp(show: Boolean) {
        helpScroll?.visibility = if (show) View.VISIBLE else View.GONE
        helpBtn?.text = if (show) "✕" else "?"
    }

    private fun helpText(): String = """
        |🔥 THERMASPACE v${BuildConfig.VERSION_NAME} — QUICK START
        |1. Plug the FLIR One into the headset's USB-C port and press its power button (LED pulses). Allow USB access when asked.
        |2. The live thermal image appears behind this guide (✕ closes it).
        |3. Look at a surface and press 📸 Capture — the thermal image is stamped onto the room right where you looked.
        |
        |BUTTONS
        |📸 Capture — place a thermal snapshot in the room
        |👁 Overlay — head-locked thermal vision: heat overlays the real world as you look around; capturing stamps exactly what you see
        |🧲 Snap ON/OFF — ON: snapshots stick flush to walls/floors at true size (uses your room scan, or the depth sensor). OFF: they float 1 m in front of you
        |↩ Undo — take the latest thing out of the room (recalled copy, drawing stroke, or snapshot — captures stay in the Gallery) ・ 🗑 Clear — empty the room: snapshots and drawings. Saved captures are NEVER deleted by Clear; delete them one-by-one with 🗑 inside the Gallery
        |🔬 Tools — opens the pro features below (drawing, gallery, sharing, imaging modes)
        |? — this guide
        |
        |IMAGING MODES (bottom row)
        |Rot — rotate the image 90° at a time to match how the camera is mounted (portrait dangle vs. landscape cradle); remembered across launches
        |Palette — iron / white-hot / black-hot / rainbow
        |Span — 🔒 locks the color scale to the current temperature range so ALL snapshots use comparable colors (essential for a room heatmap); press again for auto
        |Iso — isotherm alarm: color only the hottest 🔥 or coldest ❄️ 15% of the scene, everything else gray — great for finding drafts, leaks, and hot wiring
        |°C/°F — switch temperature units (remembered)
        |The color bar on the right shows the temperature range of the colors
        |
        |— 🔬 TOOLS (tap Tools to show these) —
        |
        |✏️ DRAW (3D marker)
        |Turn on Draw (or press B): a slim wand extends from your RIGHT controller, tipped with a yellow ball — that's where the ink comes out. Hold the RIGHT trigger and draw in mid-air like a light pen — circle a vent, arrow at a leak, write a number. To MOVE a drawing: just point the RIGHT controller ray at it (its yellow ball brightens to show it's targeted) and use the 🕹 stick — up/down sends it away or reels it in, left/right turns it. No grabbing needed; park a sketch in a far corner without walking. Up close you can still grab its yellow ball (grip or pinch) and carry it by hand. Strokes persist where you leave them. ↩ Undo removes the last stroke; 🗑 Clear wipes them all.
        |
        |🗂 GALLERY (name, recall, compare)
        |Every capture is saved with its date, palette, center crosshair, and a REAL PHOTO from the camera (next to the thermal thumbnail — that's how you remember where it was taken). The list updates live as you capture. Tap ✎ and build a name from the word chips — Bedroom + 2 + Window = "Bedroom 2 Window" (⌫ removes the last word). Tick ✓ several rows and press ▶ Recall ticked (or Recall them one at a time): they arrange in a roomy grid, ALL ON ONE SHARED TEMPERATURE SCALE (identical colors = identical temperatures), each with its photo inset, edge lines, crosshair, name, and date. ↩ Undo removes the last recalled; 🗑 on a row deletes that capture for good (Clear never touches the Gallery).
        |
        |🔒 LIVE LOCK (on every placed capture)
        |Each capture you place in the room has a small 🔓 icon in its top corner. Tap it: the capture becomes a live thermal instrument — whenever you look at it (camera on), the image refreshes to what the FLIR sees right now. Great for keeping an eye on a breaker panel, 3D printer, or pipe while you work nearby. Look away and it holds the last view; tap 🔒 to freeze it again. Recalled gallery copies never change, and the saved capture in the Gallery is never modified.
        |
        |HANDS & CONTROLLERS
        |Look at a controller OR your hand — a card pops up showing exactly what it does.
        |CONTROLLERS:
        |• A = 👁 Overlay on/off ・ B = ✏️ Draw on/off ・ 🕹 click = 📸 Capture ・ point at a drawing + 🕹 ⇅ = push/pull, 🕹 ⇄ = turn (right)
        |• X = ↩ Undo ・ Y or ☰ = 🎛 bring this panel to you (left)
        |• Trigger = point & click buttons ・ hold RIGHT trigger = ✏️ draw in the air
        |• Grip = ✋ grab panels & snapshots
        |HANDS (controllers down):
        |• 🤏 Pinch = point & click buttons, grab things
        |• 👍 RIGHT thumb tap = 👁 Overlay ・ LEFT thumb tap = 📸 Capture
        |• Thumb swipe ⇄ (overlay on) = 🎨 change palette
        |• Thumb swipe forward/back = Hi temp up/down (when span is 🔒 locked)
        |• ☰ palm menu gesture = 🎛 bring this panel to you
        |
        |BUILDING A ROOM HEATMAP
        |Walk the room and capture each area of interest. If the status says "on surface (anchored)", the snapshot is tied to your room scan and will be in the same spot next session. For best results run Settings → Physical Space → Space Setup once, then restart this app.
        |
        |TIPS
        |• No boundary needed: ThermaSpace is passthrough-only and runs boundaryless — walk the whole house (and yard) without boundary interruptions
        |• Camera battery ≈ 1 h; it sleeps when idle — press its button to wake, then reopen this app
        |• A ~ next to temperatures means "not calibrated yet" — it clears after the camera's first shutter click (~1 min)
        |• Occasional clicking from the camera is normal (self-calibration)
        |• 📡 Share (in Tools) lets your computer view the stream and download surveys over your own Wi-Fi: turn it on and open the link shown in the status line (it includes a one-time code). Sharing is OFF by default and switches off whenever you leave the app — nothing ever leaves your network.
        |• With sharing on: that link = live stream ・ /config = image tuning ・ /export = the whole gallery as a ZIP
        |
        |ABOUT THERMASPACE 💛
        |Made for Opportunity Hack (ohack.org) — showing how coding and computer science turn a $200 thermal camera and a VR headset into something new: see the invisible heat all around you, understand your home's energy story, and build a lasting thermal record of your spaces. Every layer of this app — USB protocol reverse-engineering, radiometric math, 3D room mapping — is code you could learn to write. Come build with us!
    """.trimMargin()

    private fun localIp(): String =
        try {
            java.net.NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
                ?.hostAddress ?: "<headset-ip>"
        } catch (e: Exception) { "<headset-ip>" }

    private fun setOverlay(on: Boolean) {
        overlayOn = on
        headLock.enabled = on
        overlayEntity?.setComponent(Visible(on))
        overlayBtn?.text = if (on) "👁 On" else "👁 Overlay"
        if (on) { Analytics.event("overlay_on"); nuxAdvance(2) }
    }

    /** Overlay quad matches the camera FOV; portrait rotation swaps its aspect,
     *  which needs the portrait panel registration -> recreate the entity. */
    private fun createOverlayEntity() {
        overlayEntity?.destroy()
        val id = if (renderer.isPortrait) R.id.panel_overlay_portrait else R.id.panel_overlay
        overlayEntity = Entity.create(
            Panel(id).apply { hittable = MeshCollision.NoCollision },
            Transform(Pose(Vector3(0f, 1.2f, 1.0f))),
            Visible(overlayOn),
        )
        headLock.target = overlayEntity
        headLock.distance = CAPTURE_DIST
    }

    private fun setStatus(msg: String) = runOnUiThread { statusText?.text = msg }

    // ---------------------------------------------------------- Wi-Fi sharing

    private fun shareLabelOn() = if (BuildConfig.DEBUG) "📡 Dev" else "📡 On"

    private fun setSharing(on: Boolean) {
        if (BuildConfig.DEBUG) {
            // debug builds are always-on for the dev workflow; button informs only
            setStatus("debug build — server always on at http://${localIp()}:8081")
            return
        }
        if (on) {
            Analytics.event("share_on")
            // 8-char alphanumeric: a 4-digit PIN is brute-forceable on a LAN
            val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
            server.sessionKey = (1..8).map { alphabet.random() }.joinToString("")
            server.sharingEnabled = true
            setStatus("📡 sharing ON: http://${localIp()}:8081/?key=${server.sessionKey} — " +
                      "anyone on your Wi-Fi with this link can view; turns off when you leave the app")
        } else {
            server.sharingEnabled = false
            setStatus("📡 sharing off")
        }
        runOnUiThread { shareBtn?.text = if (on) shareLabelOn() else "📡 Off" }
    }

    private fun updateHintTrigger() = runOnUiThread {
        hintRTrigger?.text = if (drawMode) "hold — ✏️ Drawing in the air"
                             else "hold — ✏️ Draw (press B first)"
    }

    // ---------------------------------------------------------- ink drawing

    private fun setDrawMode(on: Boolean) {
        drawMode = on
        if (on) Analytics.event("draw_on")
        drawBtn?.text = if (on) "✏️ Drawing" else "✏️ Draw"
        updateHintTrigger()
        if (!on) updateWand(false)   // wand disappears with the mode
        setStatus(if (on) "Draw mode — a wand extends from your RIGHT controller; hold the trigger and ink flows from its tip (B toggles)"
                  else "Draw mode off")
    }

    // ------------------------------------------------------------ wand
    // Draw-mode wand: a slender pole parented to the right controller, so the
    // user sees exactly where ink will appear (strokes used to start at an
    // invisible point next to the controller). Octagonal shaft (two 45°-
    // crossed boxes — the SDK has no cylinder mesh primitive) + a tip ball in
    // the ink color marking the emission point.
    @Volatile private var wandParts: List<Entity> = emptyList()

    private fun updateWand(rightControllerActive: Boolean) {
        val want = drawMode && rightControllerActive && resumed && sceneIsReady
        if (want && wandParts.isEmpty()) {
            val hand = systemManager.tryFindSystem<PlayerBodyAttachmentSystem>()
                ?.tryGetLocalPlayerAvatarBody()?.rightHand ?: return
            fun shaft(rollDeg: Float) = Entity.create(
                Mesh(android.net.Uri.parse("mesh://box")),
                Box(Vector3(-WAND_R, -WAND_R, 0.02f),
                    Vector3(WAND_R, WAND_R, WAND_LEN - 0.006f)),
                Material().apply { baseColor = Color4(0.55f, 0.58f, 0.62f, 1f) },
                Transform(Pose(Vector3(0f, 0f, 0f), Quaternion(0f, 0f, rollDeg))),
                TransformParent(hand),
            )
            wandParts = listOf(
                shaft(0f), shaft(45f),
                Entity.create(
                    Mesh(android.net.Uri.parse("mesh://sphere")),
                    com.meta.spatial.toolkit.Sphere(0.007f),
                    Material().apply { baseColor = Color4(1.0f, 0.86f, 0.25f, 1.0f) },
                    Transform(Pose(Vector3(0f, 0f, WAND_LEN), Quaternion(0f, 0f, 0f))),
                    TransformParent(hand),
                ),
            )
        } else if (!want && wandParts.isNotEmpty()) {
            wandParts.forEach { it.destroy() }
            wandParts = emptyList()
        }
    }

    /** Called each frame while the RIGHT trigger is held (pose = controller). */
    private fun inkHeld(rightPose: Pose?) {
        if (!drawMode || !resumed || rightPose == null) return
        val now = System.currentTimeMillis()
        if (now - lastDrawMs < 30) return
        lastDrawMs = now
        mainHandler.post {
            try { inkHeldInner(rightPose) } catch (e: Exception) { server.log("ink: $e") }
        }
    }

    private fun inkHeldInner(pose: Pose) {
        if (!resumed || !sceneIsReady || !drawMode) return
        val tip = pose.t + pose.q * Vector3(0f, 0f, WAND_LEN)
        if (!inking) { inks.add(Ink()); inking = true }
        val ink = inks.last()
        if (ink.handle == null) {
            // grab-knob at the stroke start: moving it moves the whole drawing
            ink.handle = inkHandle(tip)
            ink.points.add(Vector3(0f, 0f, 0f))
            return
        }
        val hp = ink.handle?.tryGetComponent<Transform>()?.transform ?: return
        val local = hp.q.inverse() * (tip - hp.t)
        val last = ink.points.last()
        val d = local - last
        val len = sqrt(d.x * d.x + d.y * d.y + d.z * d.z)
        if (len < INK_STEP) return
        if (ink.points.size > INK_MAX_STROKE ||
            inks.sumOf { it.entities.size } > INK_MAX_TOTAL) {
            statusThrottled("✏️ drawing limit reached — Undo or Clear to keep going")
            return
        }
        ink.entities.add(inkSegment(last, local, len, ink.handle!!))
        ink.points.add(local)
        ink.grow(local)
        inkDirty = true
    }

    private fun inkHandle(at: Vector3): Entity = Entity.create(
        Mesh(android.net.Uri.parse("mesh://sphere")),
        com.meta.spatial.toolkit.Sphere(INK_HANDLE_R),
        Material().apply { baseColor = Color4(1.0f, 0.86f, 0.25f, 1.0f) },
        Transform(Pose(at, Quaternion(0f, 0f, 0f))),
        Grabbable(),
    )

    /** One thin box from a to b, LOCAL to the stroke's handle. */
    private fun inkSegment(a: Vector3, b: Vector3, len: Float, parent: Entity): Entity {
        var dir = (b - a).normalize()
        // lookRotation degenerates when the segment is parallel to world-up
        // (vertical strokes are common) — a hair of x-tilt is invisible at 1.5 cm
        if (abs(dir.y) > 0.999f) dir = Vector3(0.02f, dir.y, 0f).normalize()
        return Entity.create(
            Mesh(android.net.Uri.parse("mesh://box")),
            Box(Vector3(-INK_HALF, -INK_HALF, 0f), Vector3(INK_HALF, INK_HALF, len)),
            Material().apply { baseColor = Color4(1.0f, 0.86f, 0.25f, 1.0f) },
            Transform(Pose(a, Quaternion.lookRotation(dir))),
            TransformParent(parent),
        )
    }

    /** Strokes persist in WORLD space (current handle pose applied), so a
     *  drawing you moved into a corner reloads in that corner. */
    private fun inkWorldStrokes(): List<List<Vector3>> = inks.mapNotNull { ink ->
        val hp = ink.handle?.tryGetComponent<Transform>()?.transform
            ?: return@mapNotNull null
        ink.points.map { p -> hp.q * p + hp.t }
    }

    private fun inkReleased() {
        // fires on the render thread — hop to main like every other ink
        // mutation (handler FIFO also orders it after any queued held-ticks)
        mainHandler.post {
            inking = false
            if (inkDirty) {
                inkDirty = false
                val strokes = inkWorldStrokes()
                ioExecutor.execute { store.saveInk(strokes) }
            }
        }
    }

    // ------------------- point-at-a-drawing manipulation (no grab required)

    private var lastStickMs = 0L
    private var lastAimMs = 0L
    @Volatile private var hoveredInk: Ink? = null   // read on the render thread (idle gate)

    /** The drawing under the controller ray: EVERY stroke point is a target
     *  (with aim assist), not just the little ball — point at the thing you
     *  drew. A ball actively held in the hand always wins. Runs up to ~60 Hz,
     *  so the point scan is bounding-sphere-gated and allocation-free (the
     *  naive Vector3 version generated 100k+ allocations/s on big drawings). */
    private fun rayPickInk(pose: Pose): Ink? {
        inks.firstOrNull { it.handle?.tryGetComponent<Grabbable>()?.isGrabbed == true }
            ?.let { return it }
        val d = (pose.q * Vector3(0f, 0f, 1f)).normalize()
        var best: Ink? = null
        var bestT = 6f
        for (ink in inks) {
            val hp = ink.handle?.tryGetComponent<Transform>()?.transform ?: continue
            // coarse gate: ray vs the stroke's bounding sphere
            val cx = hp.t.x - pose.t.x
            val cy = hp.t.y - pose.t.y
            val cz = hp.t.z - pose.t.z
            val tC = cx * d.x + cy * d.y + cz * d.z
            val reach = ink.radius + 0.10f
            if (tC + reach < 0.2f || tC - reach > bestT) continue
            val ox0 = cx - d.x * tC; val oy0 = cy - d.y * tC; val oz0 = cz - d.z * tC
            if (ox0 * ox0 + oy0 * oy0 + oz0 * oz0 > reach * reach) continue
            val qx = hp.q.x; val qy = hp.q.y; val qz = hp.q.z; val qw = hp.q.w
            for (p in ink.points) {
                // w = hp.q * p + hp.t, expanded scalar (no allocations)
                val tx = 2f * (qy * p.z - qz * p.y)
                val ty = 2f * (qz * p.x - qx * p.z)
                val tz = 2f * (qx * p.y - qy * p.x)
                val wx = p.x + qw * tx + (qy * tz - qz * ty) + hp.t.x
                val wy = p.y + qw * ty + (qz * tx - qx * tz) + hp.t.y
                val wz = p.z + qw * tz + (qx * ty - qy * tx) + hp.t.z
                val tox = wx - pose.t.x
                val toy = wy - pose.t.y
                val toz = wz - pose.t.z
                val t = tox * d.x + toy * d.y + toz * d.z
                if (t < 0.2f || t > bestT) continue
                val ox = tox - d.x * t; val oy = toy - d.y * t; val oz = toz - d.z * t
                if (ox * ox + oy * oy + oz * oz < 0.10f * 0.10f) {
                    bestT = t; best = ink
                }
            }
        }
        return best
    }

    /** ~20 Hz hover pass: brighten the hovered drawing's ball so the user
     *  knows what the stick will move. */
    private fun rightAim(rightPose: Pose?) {
        // idle: no controller, nothing highlighted, no wand → don't post 20x/s
        if (rightPose == null && hoveredInk == null && wandParts.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastAimMs < 50) return
        lastAimMs = now
        mainHandler.post {
            if (!resumed || !sceneIsReady) return@post
            try {
                updateWand(rightPose != null)
                val hover = if (rightPose == null || inks.isEmpty()) null
                            else rayPickInk(rightPose)
                if (hover !== hoveredInk) {
                    hoveredInk?.handle?.setComponent(
                        Material().apply { baseColor = Color4(1.0f, 0.86f, 0.25f, 1.0f) })
                    hover?.handle?.setComponent(
                        Material().apply { baseColor = Color4(1.0f, 1.0f, 0.75f, 1.0f) })
                    hoveredInk = hover
                }
            } catch (e: Exception) { server.log("aim: $e") }
        }
    }

    /** Right stick on the hovered/held drawing: ⇅ push/pull along the ray
     *  axis (~1.5 m/s, clamped 0.25–6 m), ⇄ turn it (~60°/s). */
    private fun stickMove(dx: Int, dy: Int, rightPose: Pose?) {
        if (rightPose == null) return
        val now = System.currentTimeMillis()
        if (now - lastStickMs < 16) return
        lastStickMs = now
        mainHandler.post {
            try { stickMoveInner(dx, dy, rightPose) } catch (e: Exception) { server.log("stick: $e") }
        }
    }

    private fun stickMoveInner(dx: Int, dy: Int, pose: Pose) {
        if (!resumed || !sceneIsReady || inks.isEmpty()) return
        val target = hoveredInk?.takeIf { inks.contains(it) } ?: rayPickInk(pose) ?: return
        val handle = target.handle ?: return
        val hp = handle.tryGetComponent<Transform>()?.transform ?: return
        var t = hp.t
        var q = hp.q
        if (dy != 0) {
            val to = hp.t - pose.t
            val dist = sqrt(to.x * to.x + to.y * to.y + to.z * to.z)
            if (dist > 1e-3f) {
                val newDist = (dist + dy * 0.035f).coerceIn(0.25f, 6f)
                t = pose.t + to * (newDist / dist)
            }
        }
        if (dx != 0) q = Quaternion(0f, dx * 1.4f, 0f) * q   // yaw about the ball
        handle.setComponent(Transform(Pose(t, q)))
        inkDirty = true
        statusThrottled(when {
            dy > 0 -> "🕹⇡ pushing drawing away"
            dy < 0 -> "🕹⇣ pulling drawing closer"
            else -> "🕹⇄ turning drawing"
        })
    }

    private fun restoreInk() {
        if (inks.isNotEmpty()) return
        for (stroke in store.loadInk()) {
            if (stroke.size < 2) continue
            val ink = Ink()
            ink.handle = inkHandle(stroke[0])
            for (p in stroke) {
                val local = p - stroke[0]                      // handle-local
                ink.points.add(local)
                ink.grow(local)
            }
            for (i in 1 until ink.points.size) {
                val a = ink.points[i - 1]; val b = ink.points[i]
                val d = b - a
                val len = sqrt(d.x * d.x + d.y * d.y + d.z * d.z)
                if (len < 1e-4f) continue
                ink.entities.add(inkSegment(a, b, len, ink.handle!!))
            }
            inks.add(ink)
        }
        if (inks.isNotEmpty()) server.log("ink: restored ${inks.size} strokes")
    }

    // ---------------------------------------------------------- microgestures

    // written on the SDK's event thread (before the runOnUiThread hop)
    private val microLastFire = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    /** One action per physical gesture: the detector re-reports an ongoing
     *  swipe on several consecutive frames, so without this debounce a single
     *  swipe cycles the palette 3-4 times. Per-bit, so a deliberate left-then-
     *  right pair still lands; 400 ms is faster than a human can re-swipe. */
    private fun onMicrogestureEvent(bit: Int, active: Boolean) {
        if (!active) return
        val now = System.currentTimeMillis()
        if (now - (microLastFire[bit] ?: 0L) < 400) return
        microLastFire[bit] = now
        onMicrogesture(bit)
    }

    /** Hand-tracking thumb gestures (pinches are reserved for UI clicks). */
    private fun onMicrogesture(bit: Int) = runOnUiThread {
        when (bit) {
            MicrogestureBits.RightMicrogestureTapThumb ->
                setOverlay(!overlayOn).also {
                    setStatus(if (overlayOn) "👁 overlay on — swipe your thumb ⇄ to change palettes"
                              else "👁 overlay off")
                }
            MicrogestureBits.LeftMicrogestureTapThumb -> capture("thumb")
            MicrogestureBits.LeftMicrogestureSwipeLeft,
            MicrogestureBits.RightMicrogestureSwipeLeft ->
                if (overlayOn) cyclePaletteBy(-1)
                else setStatus("👍 tap your right thumb to turn on Overlay, then swipe ⇄ for palettes")
            MicrogestureBits.LeftMicrogestureSwipeRight,
            MicrogestureBits.RightMicrogestureSwipeRight ->
                if (overlayOn) cyclePaletteBy(1)
                else setStatus("👍 tap your right thumb to turn on Overlay, then swipe ⇄ for palettes")
            MicrogestureBits.LeftMicrogestureSwipeForward,
            MicrogestureBits.RightMicrogestureSwipeForward -> nudgeHiByGesture(+1.0)
            MicrogestureBits.LeftMicrogestureSwipeBack,
            MicrogestureBits.RightMicrogestureSwipeBack -> nudgeHiByGesture(-1.0)
        }
    }

    private fun cyclePaletteBy(dir: Int) {
        Analytics.event("palette")
        // cyclePalette() only steps forward; stepping N-1 times goes back
        val steps = if (dir >= 0) 1 else 3
        var name = ""
        repeat(steps) { name = renderer.cyclePalette() }
        palBtnRef?.text = "🎨 $name"
        getSharedPreferences("flir", MODE_PRIVATE)
            .edit().putInt("pal", renderer.paletteIndex).apply()
        setStatus("🎨 $name")
    }

    private fun nudgeHiByGesture(stepDisp: Double) {
        if (renderer.spanLockC == null) {
            setStatus("📏 lock the span first (Tools → Span) to adjust the Hi temp by swiping")
            return
        }
        val deltaC = if (renderer.useFahrenheit) stepDisp * 5.0 / 9.0 else stepDisp
        setStatus(renderer.nudgeSpan(low = false, deltaC = deltaC))
        persistSpan(getSharedPreferences("flir", MODE_PRIVATE))
    }

    // ---------------------------------------------------------- span editor

    private fun updateSpanRow() = runOnUiThread {
        spanRow?.visibility = if (renderer.spanLockC != null) View.VISIBLE else View.GONE
    }

    private fun persistSpan(prefs: android.content.SharedPreferences) {
        val s = renderer.spanLockC
        if (s == null) prefs.edit().remove("spanLo").remove("spanHi").apply()
        else prefs.edit().putFloat("spanLo", s.first.toFloat())
            .putFloat("spanHi", s.second.toFloat()).apply()
    }

    // ---------------------------------------------------------- NUX tour

    /** Resume the tour wherever it left off; nuxStep pref -1 = done/skipped. */
    private fun startNuxIfNeeded(prefs: android.content.SharedPreferences) {
        val step = prefs.getInt("nuxStep", 0)
        if (step < 0) return
        if (step == 0) Analytics.event("nux_start")
        showNuxStep(step.coerceIn(0, nuxSteps.size - 1))
    }

    private fun showNuxStep(i: Int) {
        // camera already streaming: nothing to teach in the connect step
        if (i == 1 && camState == CamState.STREAMING) { showNuxStep(2); return }
        nuxStep = i
        getSharedPreferences("flir", MODE_PRIVATE).edit().putInt("nuxStep", i).apply()
        Analytics.event("nux_step_$i")
        val s = nuxSteps[i]
        nuxCard?.visibility = View.VISIBLE
        nuxTitle?.text = s.title
        nuxBody?.text = s.body
        nuxProgress?.text = "${i + 1} / ${nuxSteps.size}"
        nuxNextBtn?.text = s.next
    }

    /** Event-driven advance: only fires if the tour is sitting on `from`. */
    private fun nuxAdvance(from: Int) = runOnUiThread {
        if (nuxStep != from) return@runOnUiThread
        if (from + 1 >= nuxSteps.size) endNux(done = true) else showNuxStep(from + 1)
    }

    private fun endNux(done: Boolean) {
        Analytics.event(if (done) "nux_done" else "nux_skip_at_$nuxStep")
        nuxStep = -1
        getSharedPreferences("flir", MODE_PRIVATE).edit().putInt("nuxStep", -1).apply()
        nuxCard?.visibility = View.GONE
        setStatus(if (done) "🎉 tour complete — press ? anytime for the full guide"
                  else "tour skipped — press ? anytime for the full guide")
    }

    // ---------------------------------------------------------- camera state UX

    private fun setCamState(s: CamState) {
        if (camState == s) return
        camState = s
        if (s == CamState.STREAMING) { Analytics.event("camera_connected"); nuxAdvance(1) }
        runOnUiThread {
            val msg = when (s) {
                CamState.SEARCHING -> "🔌 Connect your FLIR One\n\n" +
                    "Plug it into the headset's USB-C port and press its side button.\n" +
                    "The LED pulses when it's on. It connects automatically."
                CamState.PERMISSION -> "🔐 Allow USB access\n\n" +
                    "Accept the permission dialog to let ThermaSpace use the camera."
                CamState.ASLEEP -> "😴 Camera asleep\n\n" +
                    "The FLIR One sleeps to save battery (~1 h).\n" +
                    "Press its side button — it reconnects on its own."
                CamState.STREAMING -> ""
            }
            placeholderView?.let {
                it.text = msg
                it.visibility = if (s == CamState.STREAMING) View.GONE else View.VISIBLE
            }
        }
    }

    private fun statusThrottled(msg: String) {
        val now = System.currentTimeMillis()
        if (now - statusMs > 700) { statusMs = now; setStatus(msg) }
    }

    /** ZIP of the full GALLERY: thermal PNG + radiometric raw + context photo
     *  per capture, with names/dates. Streams straight to the HTTP socket on
     *  the HTTP thread (SnapshotStore is synchronized; capture files are
     *  immutable) — building the whole ZIP in RAM on the main thread froze
     *  the app for seconds on big galleries. */
    private fun buildExportZip(out: java.io.OutputStream) {
        java.util.zip.ZipOutputStream(out).use { zip ->
            val meta = org.json.JSONObject()
            val snaps = org.json.JSONArray()
            for (snap in store.loadIndex()) {
                zip.putNextEntry(java.util.zip.ZipEntry("gallery/${snap.id}.png"))
                zip.write(snap.file.readBytes())
                zip.closeEntry()
                // radiometric + context-photo sidecars, when present
                val dir = snap.file.parentFile
                for ((suffix, entry) in listOf(
                    ".raw" to "gallery/${snap.id}.raw",
                    ".vis.jpg" to "gallery/${snap.id}.photo.jpg")) {
                    val f = java.io.File(dir, "${snap.id}$suffix")
                    if (f.exists()) {
                        zip.putNextEntry(java.util.zip.ZipEntry(entry))
                        zip.write(f.readBytes())
                        zip.closeEntry()
                    }
                }
                snaps.put(org.json.JSONObject().apply {
                    put("id", snap.id)
                    put("name", snap.name); put("note", snap.note)
                    put("palette", snap.pal)
                    put("capturedAt", snap.file.lastModified())
                    put("anchor", snap.anchorUuid ?: ""); put("scale", snap.scale)
                })
            }
            meta.put("snapshots", snaps)
            meta.put("span", renderer.spanLockC?.let { "%.1f,%.1f".format(it.first, it.second) } ?: "auto")
            meta.put("palette", renderer.paletteName)
            meta.put("exportedAt", System.currentTimeMillis())
            zip.putNextEntry(java.util.zip.ZipEntry("heatmap.json"))
            zip.write(meta.toString(2).toByteArray())
            zip.closeEntry()
        }
    }

    // ---------------------------------------------------------- FLIR One

    private fun findAndOpenCamera(quiet: Boolean = false) {
        if (flir != null) return
        val usb = getSystemService(Context.USB_SERVICE) as UsbManager
        val dev = usb.deviceList.values.firstOrNull {
            it.vendorId == FlirOne.VID && it.productId == FlirOne.PID
        } ?: run { setCamState(CamState.SEARCHING); return }
        if (!usb.hasPermission(dev)) {
            setCamState(CamState.PERMISSION)
            if (!quiet) {
                val pi = PendingIntent.getBroadcast(this, 0,
                    Intent(ACTION_USB).setPackage(packageName), PendingIntent.FLAG_MUTABLE)
                usb.requestPermission(dev, pi)
            }
            return
        }
        openCamera(usb, dev)
    }

    /** Poll for the camera while it's absent; auto-connects when plugged/woken.
     *  quiet=true: this must never re-spam the USB permission dialog every 4 s
     *  after the user dismissed it (attach/resume paths do the asking). */
    private fun cameraWatch() {
        if (resumed) {
            if (flir == null) findAndOpenCamera(quiet = true)
            else if (System.currentTimeMillis() - lastFrameMs > 4000) {
                // streaming stopped without a hard error — camera likely slept
                stopCameraAsync(CamState.ASLEEP)
            }
        }
        mainHandler.postDelayed(::cameraWatch, 4000)
    }

    /** Tear the camera down off the main thread: stop() joins the USB reader
     *  and issues a 200 ms control transfer — inline it froze the UI. */
    private fun stopCameraAsync(next: CamState) {
        val f = flir ?: return
        flir = null
        setCamState(next)
        Thread({ try { f.stop() } catch (_: Exception) {} }, "flir-stop").start()
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val usb = getSystemService(Context.USB_SERVICE) as UsbManager
            when (intent.action) {
                ACTION_USB -> {
                    val dev: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && dev != null)
                        openCamera(usb, dev)
                    else setStatus("USB permission denied")
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val dev: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (dev?.vendorId == FlirOne.VID && dev.productId == FlirOne.PID)
                        stopCameraAsync(CamState.SEARCHING)
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> findAndOpenCamera(quiet = false)
            }
        }
    }

    private fun openCamera(usb: UsbManager, dev: UsbDevice) {
        if (flir != null) return
        val conn = usb.openDevice(dev) ?: run { setStatus("openDevice failed"); return }
        setStatus("FLIR One connected — streaming")
        flir = FlirOne(dev, conn, ::onFrame,
            onError = { msg ->
                // fires on the USB reader thread — hop to main so all `flir`
                // mutations stay single-threaded (no torn reconnect races)
                server.log("flir error: $msg")
                mainHandler.post { stopCameraAsync(CamState.ASLEEP) }
            },
            onLog = { line -> server.log(line) })
            .also { it.start() }
    }

    private fun onFrame(f: FlirFrame) {
        f.statusJson?.let { server.latestStatus = it }
        f.thermalRaw?.let { server.latestThermalRaw = it }   // debug /raw endpoint
        lastFrameMs = System.currentTimeMillis()
        if (camState != CamState.STREAMING) setCamState(CamState.STREAMING)
        if (!renderer.process(f)) return
        val out = renderer.output ?: return
        runOnUiThread {
            liveView?.setImageBitmap(out); liveView?.invalidate()
            if (overlayOn) { overlayView?.setImageBitmap(out); overlayView?.invalidate() }
            updateLockedSnaps(out)
        }
        // JPEG-encoding every frame is wasted CPU unless a browser is
        // actually watching /stream right now
        if (server.wantsFrames) {
            val bos = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, 85, bos)
            server.latestJpeg = bos.toByteArray()
        }
    }

    // ---------------------------------------------------------- live lock

    /** 🔒 icon on a placed capture: while locked, the quad refreshes from the
     *  camera whenever you gaze at it. Unlocking freezes it at the latest
     *  view; the saved gallery capture is never modified. */
    private fun toggleSnapLock(id: Long) {
        val binding = lockViews[id] ?: return
        if (lockedIds.remove(id)) {
            if (binding.live) {
                binding.live = false
                // freeze a private copy — the shared live bitmap keeps mutating
                renderer.snapshot()?.let {
                    binding.image.setImageBitmap(it); binding.bound = it
                }
            }
            ioExecutor.execute { store.setLocked(id, false) }
            Analytics.event("lock_off")
            setStatus("🔓 lock off — capture frozen at its latest view (Gallery copy unchanged)")
        } else {
            lockedIds.add(id)
            Analytics.event("lock_on")
            ioExecutor.execute { store.setLocked(id, true) }
            setStatus("🔒 LIVE — this capture refreshes whenever you look at it (camera on)")
        }
        styleLockButton(id)
    }

    private fun styleLockButton(id: Long) {
        val b = lockViews[id]?.button ?: return
        val locked = lockedIds.contains(id)
        b.text = if (locked) "🔒" else "🔓"
        b.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (locked) 0xE6B33430.toInt() else 0x99202830.toInt())
    }

    private fun releaseLock(id: Long) {
        lockedIds.remove(id)
        lockViews.remove(id)
        snapParents.remove(id)
    }

    /** World pose of a placed snapshot (Transform is anchor-local when parented). */
    private fun snapWorldPose(id: Long): Pose? {
        val e = snapEntities[id] ?: return null
        val local = e.tryGetComponent<Transform>()?.transform ?: return null
        val pp = snapParents[id]?.tryGetComponent<Transform>()?.transform ?: return local
        return Pose(pp.t + pp.q * local.t, pp.q * local.q)
    }

    /** Per camera frame (~9 fps, UI thread): a locked capture the user is
     *  looking at shows the live image; looking away freezes the last view
     *  (the head-mounted camera no longer sees that spot anyway). */
    private fun updateLockedSnaps(out: Bitmap) {
        if (lockedIds.isEmpty()) return
        val head = headPose() ?: return
        val fwd = head.q * Vector3(0f, 0f, 1f)
        for (id in lockedIds) {
            val binding = lockViews[id] ?: continue
            val pose = snapWorldPose(id) ?: continue
            val to = pose.t - head.t
            val dist = sqrt(to.x * to.x + to.y * to.y + to.z * to.z)
            val dot = if (dist < 1e-3f) -1f
                      else (fwd.x * to.x + fwd.y * to.y + fwd.z * to.z) / dist
            // hysteresis: enter live harder than you leave it, so head jitter
            // at the cone edge doesn't flip-flop (each exit copies a bitmap)
            val gazed = dist > 0.25f && dist < 6f &&
                dot > (if (binding.live) 0.86f else 0.92f)
            if (gazed) {
                binding.live = true
                if (binding.bound !== out) {   // also rebinds after a rotation
                    binding.image.setImageBitmap(out)   // change swaps renderer.output
                    binding.bound = out
                }
                binding.image.invalidate()
            } else if (binding.live) {
                binding.live = false
                renderer.snapshot()?.let {
                    binding.image.setImageBitmap(it); binding.bound = it
                }
            }
        }
    }

    // ---------------------------------------------------------- capture

    private fun headPose(): Pose? {
        // the native DataModel dies with the VR session — never touch entities
        // from callbacks that were queued before a pause (JNI crash)
        if (!resumed || !sceneIsReady) return null
        val head = systemManager
            .tryFindSystem<PlayerBodyAttachmentSystem>()
            ?.tryGetLocalPlayerAvatarBody()
            ?.head ?: return null
        val pose = head.tryGetComponent<Transform>()?.transform ?: return null
        return if (pose == Pose()) null else pose
    }

    /** Y button: bring the control panel to wherever the user is now. */
    private fun summonPanel() {
        Analytics.event("summon")
        placeInFrontOfUser(controlPanel, 0.75f, tiltDeg = 15f)
        setStatus("panel is here — press Y anytime to summon it")
    }

    private fun placeInFrontOfUser(entity: Entity?, dist: Float, tiltDeg: Float = 0f) {
        val head = headPose() ?: return
        val fwd = head.q * Vector3(0f, 0f, 1f)
        fwd.y = 0f
        // gazing near-vertical: flattened forward degenerates → NaN rotation
        if (fwd.x * fwd.x + fwd.z * fwd.z < 1e-6f) fwd.z = 1f
        val q = Quaternion.lookRotation(fwd) * Quaternion(tiltDeg, 0f, 0f)
        val t = head.t + q * Vector3(0f, 0f, dist)
        entity?.setComponent(Transform(Pose(t, q)))
    }

    private data class SurfaceHit(val point: Vector3, val normal: Vector3)

    private fun raycastGaze(head: Pose): SurfaceHit? {
        val dir = (head.q * Vector3(0f, 0f, 1f)).normalize()
        val room = mrukFeature.getCurrentRoom()
        server.log("capture: room=${room?.anchor?.uuid ?: "NULL"} anchors=${mrukAnchorEntities().size}")
        if (room != null) {
            val hit = mrukFeature.raycastRoom(
                room.anchor.uuid, head.t, dir, MAX_RAY_DIST, SurfaceType.PLANE_VOLUME)
                ?: mrukFeature.raycastRoom(
                    room.anchor.uuid, head.t, dir, MAX_RAY_DIST, SurfaceType.MESH)
            server.log("capture: room raycast hit=${hit != null}")
            if (hit != null) return SurfaceHit(hit.hitPosition, hit.hitNormal.normalize())
        }
        // depth-sensor fallback: works even without a scene scan
        val depth = mrukFeature.raycastEnvironment(head.t, dir)
        server.log("capture: depth raycast=${depth.result}")
        if (depth.result == MRUKEnvironmentRaycastHitResult.SUCCESS)
            return SurfaceHit(depth.point, depth.normal.normalize())
        return null
    }

    /** Rotation for a quad lying flush on a surface, image upright toward the viewer. */
    private fun surfaceRotation(normal: Vector3, head: Pose): Quaternion {
        return if (abs(normal.y) < 0.7f) {
            // wall-like: face out of the wall, upright
            val out = Vector3(-normal.x, 0f, -normal.z).normalize()
            Quaternion.lookRotation(out)
        } else {
            // floor/ceiling: keep the image's up pointing away from the viewer's yaw.
            // Readable face is -Z, so +Z goes INTO the surface (down for floor,
            // up for ceiling) — the opposite signs showed the mirrored back face.
            val fwd = head.q * Vector3(0f, 0f, 1f)
            val yawDeg = Math.toDegrees(atan2(fwd.x.toDouble(), fwd.z.toDouble())).toFloat()
            val pitch = if (normal.y > 0f) 90f else -90f
            Quaternion(pitch, yawDeg, 0f)
        }
    }

    private fun capture(src: String = "btn") {
        try {
            captureInner(src)
        } catch (e: Exception) {
            server.log("capture failed: $e")
            setStatus("capture failed: ${e.message}")
        }
    }

    private fun captureInner(src: String) {
        val bmp = renderer.snapshot() ?: run { setStatus("no thermal image yet"); return }
        val head = headPose() ?: run { setStatus("head pose not ready"); return }
        val note = "%.1f..%.1f%s".format(
            renderer.disp(renderer.minC), renderer.disp(renderer.maxC), renderer.unitSuffix)

        var pose: Pose
        var scale = 1f
        var parent: Entity? = null
        var anchorUuid: String? = null

        val hit = if (snapToSurfaces) raycastGaze(head) else null
        if (hit != null) {
            val toHit = hit.point - head.t
            val dist = sqrt(toHit.x * toHit.x + toHit.y * toHit.y + toHit.z * toHit.z)
            scale = dist.coerceIn(0.35f, 4f)   // quad sized to real projected extent
            pose = Pose(hit.point + hit.normal * SURFACE_GAP, surfaceRotation(hit.normal, head))

            // parent to nearest scene anchor so the snapshot survives recenters/sessions
            val near = mrukAnchorEntities().minByOrNull { (e, _) ->
                val p = e.tryGetComponent<Transform>()?.transform?.t ?: return@minByOrNull Float.MAX_VALUE
                val d = p - hit.point
                d.x * d.x + d.y * d.y + d.z * d.z
            }
            if (near != null) {
                val parentPose = near.first.tryGetComponent<Transform>()?.transform
                if (parentPose != null) {
                    parent = near.first
                    anchorUuid = near.second.uuid.toString()
                    pose = worldToLocal(parentPose, pose)
                }
            }
        } else {
            // free-float at the camera-FOV distance, facing back along the gaze
            pose = Pose(head.t + head.q * Vector3(0f, 0f, CAPTURE_DIST), head.q)
        }

        // persistent counter: entity counts reset on Clear/restart and produced
        // duplicate "Capture N" names in the gallery
        val prefs = getSharedPreferences("flir", MODE_PRIVATE)
        val seq = prefs.getInt("capSeq", 0) + 1
        prefs.edit().putInt("capSeq", seq).apply()
        val defaultName = "Capture $seq"
        // spawn instantly with a minted id; PNG/JPEG/raw persistence is
        // 100-300 ms and runs on the IO thread so the click never hitches
        val id = store.mintId()
        spawnSnapshotEntity(id, bmp, pose, parent, scale)
        val raw = renderer.rawSnapshot()
        val edge = renderer.edgeSnapshot()
        val palName = renderer.paletteName
        ioExecutor.execute {
            try {
                store.save(id, bmp, pose, note, anchorUuid, scale, defaultName, palName)
                raw?.let { (rw, rh, data) -> store.saveRaw(id, rw, rh, data) }
                // sidecars so recalls reproduce the overlay look + context
                edge?.let { (ew, eh, mag) -> store.saveEdge(id, ew, eh, mag) }
                renderer.visibleSnapshot()?.let { store.saveVisible(id, it) }
                runOnUiThread {
                    if (galleryView?.visibility == View.VISIBLE) refreshGallery()
                }
            } catch (e: Exception) { server.log("capture persist: $e") }
        }
        val where = when {
            parent != null -> "on surface (anchored)"
            hit != null -> "on surface"
            else -> "floating"
        }
        Analytics.event("capture")
        Analytics.event("capture_src_$src")
        Analytics.event("capture_" + if (hit != null) "surface" else "float")
        nuxAdvance(3)
        setStatus("captured #${snapEntities.size} $where ($note) — name it in 🗂 Gallery")
    }

    private fun spawnSnapshotEntity(
        id: Long, bmp: Bitmap, pose: Pose, parent: Entity?, scale: Float) {
        val regId = if (bmp.height > bmp.width) R.id.panel_snapshot_portrait
                    else R.id.panel_snapshot
        val e = if (parent != null)
            Entity.createPanelEntity(
                regId, Transform(pose), TransformParent(parent),
                Grabbable(), Scale(Vector3(scale, scale, scale)))
        else
            Entity.createPanelEntity(
                regId, Transform(pose),
                Grabbable(), Scale(Vector3(scale, scale, scale)))
        pendingByEntity[e] = PendingPanel(bmp, id)
        snapEntities[id] = e
        snapParents[id] = parent
    }

    private fun worldToLocal(parent: Pose, world: Pose): Pose {
        val qInv = parent.q.inverse()
        return Pose(qInv * (world.t - parent.t), qInv * world.q)
    }

    private fun undo() {
        Analytics.event("undo")
        // recalled compare copies first (transient), then drawing strokes in
        // draw mode, then snapshots
        if (recalledSnaps.isNotEmpty()) {
            val removed = recalledSnaps.removeAt(recalledSnaps.size - 1)
            relayoutRecalls()
            setStatus("removed recalled '${removed.name.ifEmpty { "capture" }}' " +
                "(${recalledSnaps.size} left)")
            return
        }
        if (inks.isNotEmpty()) {
            val ink = inks.removeAt(inks.size - 1)
            ink.entities.forEach { it.destroy() }
            ink.handle?.destroy()
            store.saveInk(inkWorldStrokes())
            setStatus("removed drawing stroke (${inks.size} left)")
            return
        }
        val last = snapEntities.entries.lastOrNull() ?: run {
            setStatus("nothing to undo"); return
        }
        pendingByEntity.remove(last.value)
        last.value.destroy()
        snapEntities.remove(last.key)
        releaseLock(last.key)
        val id = last.key
        ioExecutor.execute { store.setInWorld(id, false) }   // out of the room, stays in gallery
        setStatus("removed from the room — still in 🗂 Gallery (${snapEntities.size} placed)")
    }

    private fun clearAll() {
        Analytics.event("clear")
        snapEntities.values.forEach { pendingByEntity.remove(it); it.destroy() }
        snapEntities.clear()
        lockedIds.clear(); lockViews.clear(); snapParents.clear()
        recalledEntities.forEach { pendingByEntity.remove(it); it.destroy() }
        recalledEntities.clear()
        recalledSnaps.clear(); recallOrigin = null
        inks.forEach { ink -> ink.entities.forEach { it.destroy() }; ink.handle?.destroy() }
        inks.clear()
        ioExecutor.execute {
            store.unplaceAll()   // gallery keeps every capture; delete there via 🗑
            store.saveInk(emptyList())
        }
        setStatus("room cleared (snapshots, drawings) — captures are safe in 🗂 Gallery")
    }

    // ---------------------------------------------------------- gallery

    private fun toggleGallery() {
        val show = galleryView?.visibility != View.VISIBLE
        galleryView?.visibility = if (show) View.VISIBLE else View.GONE
        if (show) { Analytics.event("gallery_open"); setHelp(false); refreshGallery() }
        else { renameBar?.visibility = View.GONE; renameTargetId = null }
    }

    private fun refreshGallery() {
        val snaps = store.loadIndex()
        val adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = snaps.size
            override fun getItem(p: Int) = snaps[p]
            override fun getItemId(p: Int) = snaps[p].id
            override fun getView(p: Int, cv: View?, parent: android.view.ViewGroup): View {
                val row = cv ?: layoutInflater.inflate(R.layout.gallery_row, parent, false)
                val snap = snaps[p]
                val cb = row.findViewById<android.widget.CheckBox>(R.id.row_check)
                cb.setOnCheckedChangeListener(null)   // recycled rows keep stale listeners
                cb.isChecked = selectedIds.contains(snap.id)
                cb.setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedIds.add(snap.id) else selectedIds.remove(snap.id)
                }
                // subsampled decodes: full 640x480 bitmaps per 72dp row thrash
                // memory and stall the UI thread while scrolling
                row.findViewById<ImageView>(R.id.row_thumb)
                    .setImageBitmap(store.loadThumb(snap.file, 4))
                row.findViewById<TextView>(R.id.row_name).text =
                    snap.name.ifEmpty { "Capture ${snap.id % 100000}" }
                val comparable = store.hasRaw(snap.id)
                // PNG mtime = true capture time (correct even for captures made
                // before ids carried real timestamps)
                val dateStr = galleryDateFmt.format(java.util.Date(snap.file.lastModified()))
                row.findViewById<TextView>(R.id.row_sub).text = buildString {
                    append(snap.note)
                    if (snap.pal.isNotEmpty()) append("  · ").append(snap.pal)
                    append("  · ").append(dateStr)
                    if (snap.inWorld) append("  · in room")
                    if (!comparable) append("  (not comparable)")
                }
                val visThumb = row.findViewById<ImageView>(R.id.row_thumb_vis)
                val visBmp = store.loadThumb(store.visibleFile(snap.id), 4)
                visThumb.visibility = if (visBmp != null) View.VISIBLE else View.GONE
                visThumb.setImageBitmap(visBmp)
                row.findViewById<Button>(R.id.row_recall).setOnClickListener { recallSnap(snap) }
                row.findViewById<Button>(R.id.row_rename).setOnClickListener { renameSnap(snap) }
                row.findViewById<Button>(R.id.row_delete).setOnClickListener {
                    Analytics.event("delete")
                    snapEntities.remove(snap.id)?.also {      // take it off the wall too
                        pendingByEntity.remove(it); it.destroy()
                    }
                    releaseLock(snap.id)
                    store.remove(snap.id); refreshGallery()
                }
                return row
            }
        }
        runOnUiThread { galleryList?.adapter = adapter }
    }

    /** Inline rename: names are built by tapping word chips (dialogs don't
     *  render in spatial panels; the system keyboard doesn't reach them either). */
    private fun renameSnap(snap: SnapshotStore.Snap) {
        renameTargetId = snap.id
        renameBar?.visibility = View.VISIBLE
        renameInput?.setText(snap.name)
        setStatus("tap word chips to build a name (Bedroom 2 Window), then ✔ Save")
    }

    /** Recall every ticked gallery row in one tap (shared scale, one grid). */
    private fun recallSelected() {
        val picks = store.loadIndex().filter {
            selectedIds.contains(it.id) && recalledSnaps.none { r -> r.id == it.id }
        }
        if (picks.isEmpty()) { setStatus("tick ✓ some captures in the list first"); return }
        Analytics.event("recall_multi")
        if (recalledSnaps.isEmpty()) {
            val head = headPose() ?: run { setStatus("head pose not ready"); return }
            val fwd = head.q * Vector3(0f, 0f, 1f); fwd.y = 0f
            if (fwd.x * fwd.x + fwd.z * fwd.z < 1e-6f) fwd.z = 1f
            recallOrigin = Pose(head.t, Quaternion.lookRotation(fwd))
        }
        recalledSnaps.addAll(picks)
        relayoutRecalls()
        selectedIds.clear()
        refreshGallery()
        setStatus("${recalledSnaps.size} recalled on ONE temperature scale — ↩ Undo removes")
    }

    /** Add a capture to the floating compare set: the whole set re-renders on
     *  ONE shared temperature span and lays out as a neat grid (3 per row). */
    private fun recallSnap(snap: SnapshotStore.Snap) {
        if (recalledSnaps.any { it.id == snap.id }) {
            setStatus("'${snap.name.ifEmpty { "capture" }}' is already recalled"); return
        }
        Analytics.event("recall")
        if (recalledSnaps.isEmpty()) {
            val head = headPose() ?: run { setStatus("head pose not ready"); return }
            val fwd = head.q * Vector3(0f, 0f, 1f); fwd.y = 0f
            if (fwd.x * fwd.x + fwd.z * fwd.z < 1e-6f) fwd.z = 1f
            recallOrigin = Pose(head.t, Quaternion.lookRotation(fwd))
        }
        recalledSnaps.add(snap)
        relayoutRecalls()
        setStatus(if (recalledSnaps.size == 1)
            "recalled '${snap.name.ifEmpty { "capture" }}' — recall more to compare on one scale"
        else "${recalledSnaps.size} recalled on ONE temperature scale — ↩ Undo removes")
    }

    /** Destroy and re-spawn the whole recall set: shared span + grid layout.
     *  The file reads + composite rendering run on the IO thread (recalling
     *  six captures was ~1 s of frozen UI inline). */
    private var recallGen = 0
    private fun relayoutRecalls() {
        recalledEntities.forEach { pendingByEntity.remove(it); it.destroy() }
        recalledEntities.clear()
        if (recalledSnaps.isEmpty()) { recallOrigin = null; return }
        val origin = recallOrigin ?: return
        val gen = ++recallGen
        val snaps = recalledSnaps.toList()

        ioExecutor.execute {
            // one span across every recalled capture (pooled percentiles): the
            // same color = the same temperature in every quad
            val raws = snaps.map { store.loadSnapRaw(it.id) }
            val pool = ArrayList<Float>()
            for (raw in raws) {
                val data = (raw ?: continue).third
                val stride = (data.size / 2000).coerceAtLeast(1)
                var i = 0
                while (i < data.size) { if (data[i] > 0f) pool.add(data[i]); i += stride }
            }
            pool.sort()
            val shared = if (pool.size > 50)
                pool[(pool.size * 0.02).toInt()] to
                pool[(pool.size * 0.98).toInt().coerceAtMost(pool.size - 1)]
            else null
            val bmps = snaps.mapIndexed { i, s ->
                raws[i]?.let { renderRecallBitmap(it, s, shared) }
                    ?: android.graphics.BitmapFactory.decodeFile(s.file.absolutePath)
            }

            runOnUiThread {
                if (gen != recallGen) return@runOnUiThread   // superseded relayout
                val n = snaps.size
                for ((i, bmp) in bmps.withIndex()) {
                    if (bmp == null) continue
                    // quads are 0.93×0.70 m — spacing must exceed that or they clip
                    val col = i % 3; val row = i / 3
                    val inRow = minOf(3, n - row * 3)
                    val q = origin.q
                    val right = q * Vector3(1f, 0f, 0f)
                    val pos = origin.t + q * Vector3(0f, 0f, 1.35f + row * 0.06f) +
                        right * ((col - (inRow - 1) / 2f) * 1.05f)
                    pos.y = origin.t.y + 0.15f - row * 0.78f
                    spawnRecalled(bmp, Pose(pos, q))
                }
            }
        }
    }

    /** Recall rendering: raw → shared-span LUT at the live view's scale, plus
     *  the capture's saved MSX edges (same look as the overlay), the real-photo
     *  inset, and name/span/date burned in. */
    private fun renderRecallBitmap(raw: Triple<Int, Int, FloatArray>,
                                   snap: SnapshotStore.Snap,
                                   sharedSpan: Pair<Float, Float>?): Bitmap {
        val (w, h, data) = raw
        val (lo, hi) = sharedSpan ?: run {
            val samples = data.filter { it > 0f }.sorted()
            samples.getOrElse((samples.size * 0.02).toInt()) { 0f } to
            samples.getOrElse((samples.size * 0.98).toInt().coerceAtMost(samples.size - 1)) { 1f }
        }
        val span = (hi - lo).coerceAtLeast(1f)
        val lut = renderer.currentLut
        val px = IntArray(w * h)
        for (i in data.indices) {
            val g = (((data[i] - lo) / span) * 255f).toInt().coerceIn(0, 255)
            px[i] = lut[g]
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        // OUT_SCALE matches the live view, so the saved MSX alignment holds
        val sc = ThermalRenderer.OUT_SCALE
        val scaled = Bitmap.createScaledBitmap(bmp, w * sc, h * sc, true)
        bmp.recycle()
        val c = Canvas(scaled)

        // MSX edges from the capture's sidecar — the "lines" of the overlay look
        store.loadEdge(snap.id)?.let { (ew, eh, mag) ->
            val edges = IntArray(ew * eh)
            for (i in mag.indices) edges[i] = ((mag[i].toInt() and 0xFF) shl 24) or 0xFFFFFF
            val eb = Bitmap.createBitmap(edges, ew, eh, Bitmap.Config.ARGB_8888)
            val ow = scaled.width.toFloat(); val oh = scaled.height.toFloat()
            val dw = ow * renderer.msxScale; val dh = oh * renderer.msxScale
            val left = (ow - dw) / 2f + renderer.msxDx; val top = (oh - dh) / 2f + renderer.msxDy
            c.drawBitmap(eb, null, android.graphics.Rect(
                left.toInt(), top.toInt(), (left + dw).toInt(), (top + dh).toInt()),
                Paint(Paint.FILTER_BITMAP_FLAG))
            eb.recycle()
        }

        // real-world photo inset (top-right) for "where was this?"
        store.loadVisible(snap.id)?.let { vis ->
            val insetW = scaled.width * 0.32f
            val insetH = insetW * vis.height / vis.width
            val m = 10f
            val dst = android.graphics.RectF(
                scaled.width - insetW - m, m, scaled.width - m, m + insetH)
            c.drawBitmap(vis, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            c.drawRect(dst, Paint().apply {
                style = Paint.Style.STROKE; strokeWidth = 3f; color = 0xE6FFFFFF.toInt()
            })
            vis.recycle()
        }

        // center crosshair + spot temperature — same readout the live overlay shows
        val crossPaint = Paint().apply {
            color = 0xFFFFFFFF.toInt(); textSize = 40f; isAntiAlias = true
            setShadowLayer(4f, 1f, 1f, 0xFF000000.toInt())
        }
        c.drawText("+", scaled.width / 2f - 13f, scaled.height / 2f + 15f, crossPaint)
        val centerRaw = data.getOrNull((h / 2) * w + w / 2) ?: 0f
        if (centerRaw > 0f) {
            crossPaint.textSize = 30f
            c.drawText("%.1f%s".format(
                renderer.disp(renderer.rawToCelsius(centerRaw.toDouble())),
                renderer.unitSuffix),
                scaled.width / 2f + 22f, scaled.height / 2f + 12f, crossPaint)
        }

        val date = recallDateFmt.format(java.util.Date(snap.file.lastModified()))
        val header = "${snap.name.ifEmpty { "capture" }}  %.0f–%.0f%s · $date".format(
            renderer.disp(renderer.rawToCelsius(lo.toDouble())),
            renderer.disp(renderer.rawToCelsius(hi.toDouble())), renderer.unitSuffix)
        val headerPaint = Paint().apply {
            color = 0xFFFFFFFF.toInt(); textSize = 24f; isAntiAlias = true
            setShadowLayer(4f, 1f, 1f, 0xFF000000.toInt())
        }
        // shrink-to-fit: long names + timestamp must never run off the image
        val maxW = scaled.width - 16f
        val measured = headerPaint.measureText(header)
        if (measured > maxW) headerPaint.textSize = (24f * maxW / measured).coerceAtLeast(14f)
        c.drawText(header, 8f, 30f, headerPaint)
        return scaled
    }

    private fun spawnRecalled(bmp: Bitmap, pose: Pose) {
        // keep the capture's aspect: portrait captures need the portrait quad
        val regId = if (bmp.height > bmp.width) R.id.panel_snapshot_portrait
                    else R.id.panel_snapshot
        val e = Entity.createPanelEntity(regId, Transform(pose), Grabbable())
        pendingByEntity[e] = PendingPanel(bmp, null)   // recalls get no 🔒 icon
        recalledEntities.addLast(e)
    }

    /** Grab-moved snapshots: periodically write their (local) poses back to disk.
     *  Only touch the ECS while resumed — the native DataModel dies with the
     *  VR session and stale reads crash with a JNI assert. */
    private fun syncMovedSnapshots() {
        if (resumed && sceneIsReady) {
            try {
                if (snapEntities.isNotEmpty()) {
                    val index = store.loadIndex().associateBy { it.id }
                    val moved = HashMap<Long, Pose>()
                    for ((id, e) in snapEntities) {
                        val current = e.tryGetComponent<Transform>()?.transform ?: continue
                        val saved = index[id] ?: continue
                        if (saved.pose != current) moved[id] = current
                    }
                    // one batched index write, off the UI thread
                    if (moved.isNotEmpty()) ioExecutor.execute { store.updatePoses(moved) }
                }
                // persist ink (also captures grab-moved handles' new poses)
                if (!inking && inks.isNotEmpty()) {
                    inkDirty = false
                    val strokes = inkWorldStrokes()
                    ioExecutor.execute { store.saveInk(strokes) }
                }
            } catch (e: Exception) {
                server.log("syncMovedSnapshots: ${e.message}")
            }
        }
        mainHandler.postDelayed(::syncMovedSnapshots, 10_000)
    }
}
