# FLIR One project context

Goal: stream IR from a FLIR One USB-C (09cb:1996, serial T13M6R001CE) — on Mac and
ultimately inside a Quest 3 native app.

## State (2026-07-11) — WORKING end-to-end on Quest 3
Quest app streams live thermal in-headset and to the Mac (http://192.168.68.93:8080,
endpoints /stream /status /log /raw). This unit is a **FLIR One Pro LT**
(model 435-0013, per Greg; 68×34×14 mm, FOV 50°×38°, visible cam 1440×1080 —
datasheet 435-0013-03; 80×60 stream confirms LT, not full Pro):
thermal payload = 10332 B = 63 lines × 82 words, VoSPI-style `[line ID][CRC][80 px]`,
lines 60–62 telemetry. Gotchas fixed: status JSON *always* contains "ffcState":"FFC_…"
— only skip frames when `shutterState` starts with FFC; USB attach needs
launchMode=singleTask or a second instance fights over port 8080.
adb over Wi-Fi is blocked by macOS Local Network TCC for the adb binary — relay through
Apple-signed nc: `mkfifo p; while true; do nc -l 127.0.0.1 5556 <p | nc <quest-ip> 5555 >p; done`
then `adb connect 127.0.0.1:5556`. Build: gradle 8.7 in scratchpad + Studio JBR.

## quest3-spatial (Meta Spatial SDK heatmap app, port 8081)
Passthrough scene; grabbable live-thermal control panel; Capture button places the
current frame as a world-locked grabbable quad sized to the G3's real FOV (50°×38°
→ 0.93×0.70 m at 1 m along head gaze); snapshots persist as {PNG, pose} in
filesDir/snaps (LOCAL_FLOOR space — no public Spatial SDK anchor-persistence API yet).
2D app got: ThermalRenderer (EMA denoise, percentile auto-gain, real iron LUT, MSX
Sobel edges from visible cam, FFC shutter-temp calibration), tunable via
/config?ema=&msx=&mscale=&mdx=&mdy=&emis=.
Spatial SDK 0.13.1 gotchas: needs gradle 8.13 + Kotlin 2.1.0 + AGP 8.11.1 (gradle-8.13
in scratchpad); NEVER put com.meta.spatial.plugin on the ROOT build.gradle.kts classpath
(its kotlin-compiler-embeddable breaks KGP with IncrementalCompilationFeatures errors) —
apply it with version at module level only, like Meta's samples.
Verified APIs: getHmd via PlayerBodyAttachmentSystem→tryGetLocalPlayerAvatarBody().head;
Entity.createPanelEntity(R.id.x, Transform, Grabbable()); LayoutXMLPanelRegistration
with panelSetupWithRootView; scene.enablePassthrough(true); forward = pose.q*Vector3(0,0,1).

v9.0 (2026-07-14, INSTALLED via USB 900/9.0, boots clean; /export verified
live from device; in-headset 🔒-icon click test PENDING): Lock rework +
store-prep perf sweep. REMOVED subsystems (user request; ~1100 lines, NO git
— pre-surgery snapshot only in session tmp, gone after reboot):
WatchedRegion/PaintedSurface/AnnotationSurface/SyntheticPlane/baseline/diff/
splat machinery, /regions endpoint, region/paint/synthetic store methods,
region_panel/region_label/paint_panel layouts, btn_lock. LIVE LOCK replaces
watch regions: snapshot_panel.xml gained snap_lock button (🔓/🔒 top-left,
placed captures only — recalls pass snapId=null); locked + gazed (enter
dot>0.92 / exit 0.86 hysteresis, 0.25–6 m) → the quad's ImageView shows
renderer.output live @9 fps (updateLockedSnaps in onFrame's UI hop; per-
binding `bound` tracks bitmap identity so rotation-change swaps rebind);
look-away/unlock freezes a renderer.snapshot() copy; Snap.locked persisted
("locked" in index.json, cleared on unplace/Clear). pendingBitmaps FIFO →
pendingByEntity map keyed by panel Entity — panelSetupWithRootView's 3rd
lambda param IS the Entity (verified via javap on toolkit jar); bind retries
once via mainHandler.post in case inflation beat the map put.
Perf fixes (2 review agents, all high-impact findings fixed): ThermalRenderer
reuses ALL per-frame buffers (rot/sort/vp/luma/edges/mag/edgeBitmap +
inBitmap JPEG decode; visible rotation now index-based, no Matrix bitmap;
edges buffer must be zero-filled when dims change — rotation swaps vw/vh at
same product and stale interiors become permanent border garbage); FlirOne:
growable read buffer, negative-size USB header rejection + try/catch around
readLoop (malformed frame killed the process), Log.d gated DEBUG;
SnapshotStore: @Synchronized + in-memory index cache (was full JSON re-parse
per mutation on UI thread) + mintId()/save(id,…) so captures spawn instantly
and persist on ioExecutor("snap-io") + batch updatePoses(); recall set
renders off-main (recallGen supersede guard); /export STREAMS the ZIP on the
HTTP thread (hasExport/exportTo hooks, no Content-Length; onMainSync
deleted); MJPEG: JPEG encode only while streamClients>0, stream loop exits
when sharing turns off (privacy claim now true), latestJpeg nulled when last
client leaves (stale-frame fix); camera: cameraWatch(quiet=true + resumed
gate) — was re-spawning the USB permission dialog every 4 s and streaming
while paused; onPause → stopCameraAsync (stop() joins reader + 200 ms ctrl
transfer = UI freeze inline; reconnect on resume takes ~1-2 s);
resolveAnchoredSnapshots: anchor-exists check BEFORE PNG decode, retry cap
10, single chain; inkReleased hops to main (CME with queued held-ticks);
rayPickInk: per-Ink bounding-sphere gate + scalar quaternion math (was 100k+
Vector3 allocs/s while aiming); ControllerInput/ControllerHint/HeadLock
systems cache avatar entity + PlayerBodyAttachmentSystem (Query.eval every
render frame); gallery rows: subsampled thumb decode (loadThumb ÷4), raw
check = File.exists (was full raw read per row), SimpleDateFormat hoisted.
NOT done: release keystore (last store blocker), thumb LruCache.
Docs updated: API.md (no /regions), STORE.md (live lock copy + shot list),
in-app help. Mounts: mount/flirone-quest3-mount-hang.scad — "hook" (35 mm
tongue slides under a strap crossing the visor top + rear lip; NOT the bare
Elite Strap — no top strap) and "clip" (top-shell bridge + sprung rear lip,
TOP_DEPTH presets q3=42/q3s=58 — MEASURE first); knuckle raised above the
top edge (Quest 3 center pill = depth projector starts ~20-25 mm below the
edge; Quest 3S clusters are lateral — keep-outs documented in the .scad);
6 STLs rendered (hook/clip × M5/M6 + clip-q3s). "fork" iterated 3x per Greg:
v1 = 2 straight prongs (rejected); v2 = in-plane L (rejected — he meant the
SIDE profile); v3 (per annotated screenshot) = STRAP STAPLE: solid tongue +
sharp 90° turn DOWNWARD at the end (profile ⊓, square corners everywhere,
DROP_LEN 22, channel ≈31 mm between plate and drop arm — drops over any
strap from above); flirone-fork{,-m6}.stl + flirone-fork-preview.png.
v9.0.1 (2026-07-14, INSTALLED 901): joystick phantom-capture fix — runtime
raises ButtonThumbRClick during plain right-stick DEFLECTION (field report:
any stick direction fired 📸). ControllerInputSystem now accepts the click
edge only when the stick is direction-neutral (RU|RD|RL|RR clear) this frame
AND the previous frame. ButtonBits values verified via javap: RL/RR/RU/RD =
1024/2048/16384/32768, RClick = 131072 — constants don't overlap, the
overlap happens in native buttonState population. Side effect: clicking
while deflected no longer captures (intended).
v9.0.2 (2026-07-16): DRAW WAND — ink now emits 150 mm ahead of the right
controller at the tip of a visible wand (2 crossed 45° slim boxes ≈ octagonal
shaft — SDK has NO cylinder mesh primitive, only compositor cylinder layers —
+ 7 mm yellow tip Sphere = ink color); entities TransformParent'ed to
AvatarBody.rightHand, created/destroyed by updateWand() driven from
setDrawMode + the 20 Hz rightAim tick (shows only when drawMode && physical
right controller && resumed && sceneIsReady); WAND_LEN replaced INK_TIP
(0.04). ⚠ /private/tmp cleaner emptied the old-session gradle dirs (files
>3 days) — project now has a REAL GRADLE WRAPPER (quest3-spatial/gradlew,
8.13): use `JAVA_HOME=<Studio JBR> ./gradlew assembleDebug`; no scratchpad
gradle needed ever again.
v9.1.0 (2026-07-16, INSTALLED via USB 910, boots clean; NUX verified live on
device — nux_start/nux_step_0 counted after fake-prox wake; in-headset
walkthrough PENDING): NUX + analytics. NUX = 5-step tour card (nux_card at
bottom of control panel FrameLayout, added last = z-top): welcome → connect
camera → overlay → first capture → done; steps 1-3 AUTO-ADVANCE on the real
event (setCamState STREAMING → nuxAdvance(1), setOverlay(true) → 2,
captureInner → 3); prefs "nuxStep" (-1=done) resumes mid-tour; connect step
auto-skips when already streaming; replaced the helpSeen auto-open (pref now
unused); existing installs see the tour ONCE after update (nuxStep absent →
0 — intended, lets Greg field-test). Analytics.kt = LOCAL-ONLY singleton:
counters + sessions/sessionSecs → filesDir/analytics.json (10 s debounced
tmp+rename; flush on onPause), ~30 events (capture, capture_src_{btn,stick,
thumb}, capture_{surface,float}, overlay_on, draw_on, nux_*, gallery_open,
recall(_multi), rename, delete, lock_on/off, share_on, palette, rotate,
span_toggle, iso, unit, undo, clear, summon, tools_toggle, help_open,
camera_connected); debug-only /stats endpoint + index link
(MjpegServer.statsJson hook). Meta Platform SDK "In-App Analytics" EXISTS
(verified in meta docs: horizon.platform.inappanalytics.InAppAnalytics —
suspend segments/metric events/counters; dep com.meta.horizon.platform.sdk:
core-kotlin:0.2.2 + HorizonServiceConnection.connect(APP_ID)) but needs App
ID + approved Data Use Checkup + store distribution AND breaks the "does not
collect" privacy path → NOT wired; recipe in Analytics.kt header + STORE.md.
Gotchas: local.properties went missing (recreate:
sdk.dir=$HOME/Library/Android/sdk); an ASLEEP headset never inflates panels
(bindControlPanel doesn't run) — wake for tests with `adb shell input
keyevent KEYCODE_WAKEUP` + `am broadcast -a
com.oculus.vrpowermanager.prox_close`, undo with automation_disable.
Slicing (2026-07-16): BambuStudio 02.07 CLI works headless — but (a) system
profile jsons use "inherits" chains the CLI does NOT resolve when loaded as
files (filament_density=0, weight=0.00g) → flatten chains first (python
resolver in session scratchpad; parent = "<name>.json" in same profiles/BBL
subdir); (b) PETG validates only with "curr_bed_type":"Textured PEI Plate"
in the process json (default Cool Plate errors out); (c) gcode support
marker is "; FEATURE: Support" (case matters when grepping). Recipe:
BambuStudio <stl> --load-settings "machine.json;process.json"
--load-filaments filament.json --rotate-y 90 --ensure-on-bed --arrange 1
--slice 0 --export-3mf out.3mf --outputdir <dir>; estimates in
Metadata/plate_1.gcode header. flirone-clip-m6-H2S.3mf = side-oriented,
0.24mm/PETG-HF/tree(auto)/2 walls/10%: 45m47s, 14.7g (3w/15%: 49m43s,
18.7g). Side print = knuckle bore vertical + clip flex loads in-plane.

thermaspace.org site (2026-07-16, built per thermaspace.org/CLAUDE.md brief):
static single-page site in thermaspace.org/ — index.html + styles.css +
script.js + privacy.html + robots/sitemap/vercel.json; deploy = `vercel --prod`
from that dir (no build step). Media pipeline: example_images_video/* →
media/ (ffmpeg 1280w crf28 loops ~1-2MB + posters; magick 1600w stills,
first_start brightened +35%, og.jpg 1200×630, mount-fork.png = trimmed
mount/flirone-fork-preview.png). STLs copied to stl/ (hook/clip/clip-q3s/fork
× M5/M6, download links on page). Design: iron-LUT palette on #0a0812,
Chakra Petch/IBM Plex, signature = fixed scroll-tracking span bar (71.2→
103.4°F) + mono temp eyebrows per section. JSON-LD: SoftwareApplication +
Product + FAQPage; copy sourced from store/STORE.md. PLACEHOLDERS Greg must
confirm: mount $19 shipped, orders + beta via mailto:greg@ohack.org
(swap to Stripe Payment Link later); app CTA = beta email, store "coming soon". GA4 (2026-07-17, G-C0W09F6KMZ):
gtag in both html heads (debug_mode auto-on off-prod hostnames); script.js sends
join_beta/mount_order (value 19 USD)/contact_email (mailto subject sniff),
file_download for /stl/ (.stl not in GA's auto list), section_view funnel,
faq_open; privacy.html discloses site-only GA + Stripe checkout. Greg TODO in
GA admin: mark join_beta + mount_order as key events; Admin→Data filters→
activate Developer traffic filter.
Stripe (2026-07-17, LIVE mode, Opportunity Hack Inc. acct_1Pu5xqLSFKt9iFWc):
mount ordering = Payment Link https://buy.stripe.com/14AfZi2OBb7D3gEe0f1RC00
(price_1TuDYoLSFKt9iFWcXyGGcjol $19 / prod_Uu1cFY6OPxCx1Y; US shipping addr,
qty 1-10, dropdowns variant/headset/thumbscrew) — replaced the order mailto;
mount_order GA event now fires on buy.stripe.com clicks. ALL greg@ohack.org
mailtos replaced (2026-07-17, per Greg) by Google Form
https://forms.gle/mUWpW4UMTXoNnEnE7 (beta buttons, Talk to us, FAQ, footer
"Contact", privacy, FAQPage JSON-LD, Stripe confirmation msg); GA events:
forms.gle click → join_beta (link text has "beta") else contact_form
(contact_email event retired). Mount section (2026-07-17): real product photo
media/mount-photo.jpg (from Greg's PXL_20260717_154055259, 1600×2000 q82) is
the section figure + Product JSON-LD image; CLIP is now the ONLY variant per
Greg — fork ("not what I wanted — could not get it right") + hook removed
from copy/JSON-LD, their 4 STLs deleted from stl/ (clip Q3/Q3S × M5/M6
remain; hook/fork sources still in mount/*.scad), Stripe plink variant
dropdown removed (headset+thumbscrew stay; product description still says
hook/clip/fork — no product-update op in MCP scope, fix in dashboard); copy
notes mount seats on visor with a Command strip (Home Depot link,
.mount-note). Temp callouts
REMOVED per Greg (UX): eyebrows now label-only ("+ HARDWARE"), spanbar keeps
gradient+tick but the scroll-tracking °F readout is gone (#spanReadout,
.spanbar-readout, LO/HI js deleted). Hero thermal figcaption temps kept (real
capture data). Stripe Tax ENABLED on the plink (price tax_behavior=exclusive; tax
line renders "Enter address to calculate" — verified via Playwright, page
clean, promo field + all 3 dropdowns + US shipping present). Promo codes
allowed; a single-use 100%-off test coupon exists (name/code kept out of this
public repo — see Stripe dashboard; $0 orders need no card:
payment_method_collection=if_required). Stripe MCP quirks: PostProducts
+ GetTaxSettings denied but PostPrices+product_data / plink update work.
GA4 key-event marking (join_beta, mount_order) = manual, no GA API access. DEPLOYED to Vercel prod (project thermaspace-org, ohack team;
thermaspace-org.vercel.app live). ⚠ DNS PENDING: thermaspace.org still serves
Squarespace — Greg must set A @ → 76.76.21.21 (+ CNAME www →
cname.vercel-dns.com) in Squarespace DNS, or move NS to ns{1,2}.vercel-dns.com;
domain+www already attached to the project.

PUBLIC GITHUB (2026-07-17): pushed to
https://github.com/opportunity-hack/thermaspace.org (main, gh auth as gregv).
Pre-push secret sweep clean; root .gitignore excludes build//.gradle/
local.properties/keystores/.venv/.vercel/.playwright-mcp. The Stripe test-
coupon name/code was scrubbed from this file (public repo) — it lives only in
the Stripe dashboard. GA4 ID + Payment Link in site HTML are public by design.
⚠ This file is now public: keep secrets/coupon codes out of it.

v2 features (all built+installed): MRUK snapping — MRUKFeature(this, systemManager),
runtime USE_SCENE permission → loadSceneFromDevice(); capture raycasts gaze via
raycastRoom(room.anchor.uuid, …, SurfaceType.PLANE_VOLUME) with raycastEnvironment
(depth) fallback; snapshot flush on surface, Scale = hit distance (quad sized per-1m
FOV), TransformParent = nearest MRUKAnchor entity + local pose + uuid persisted
(drift-free across sessions). Head-locked overlay: non-hittable Panel
(Panel(id).apply{hittable=MeshCollision.NoCollision}) + HeadLockSystem nlerp-follow.
Pinch capture: PinchCaptureSystem, AvatarBody.leftHand Controller, ButtonBits.
ButtonTriggerL 500ms hold. More gotchas: Query.eval() returns Sequence (not List);
xrGetSpaceRoomLayoutFB/xrRequestSceneCaptureFB "not supported" logcat warnings are
benign; scene-permission dialog must be accepted in-headset before MRUK loads;
Quaternion 4-arg ctor is (w,x,y,z), 3-arg is euler degrees (pitch,yaw,roll).
surfaceRotation floor/ceiling euler signs are best-guess — verify on device.

v8.0 (2026-07-14, INSTALLED via USB, stable): hands + UX overhaul per field
test. **Hand-input root cause**: for hand tracking the runtime maps index
pinches onto ButtonX (left) / ButtonA (right) — v7's face-button bindings ATE
the pinches (Greg: "can't click menus, pinch does undo/overlay"). Fixes:
(1) PinchCaptureSystem.kt now = ControllerInputSystem, gates ALL button/trigger
handling on Controller.type == ControllerType.CONTROLLER; (2) IsdkFeature(this,
spatial, systemManager) added to registerFeatures → pinch/ray click panels;
(3) microgestures: toolkit MicrogesturesSystem is AUTO-REGISTERED by the SDK —
tryFindSystem + addListener, NEVER registerSystem a second one (crash:
"Duplicate Systems"). Bindings: R thumb-tap=overlay, L thumb-tap=capture,
swipe L/R (overlay on)=palette cycle (cyclePaletteBy: 3 fwd steps = back),
swipe fwd/back=span-Hi ±1 display unit when locked; ButtonMenu (left) =
summonPanel. Hand hint cards: hand_hints_{left,right}.xml chosen by
ControllerType in ControllerHintSystem (per-side active-card swap).
Long-pinch capture REMOVED (pinch = UI). Drawing REPLACED: wall-projection
retired (mirror saga) → free-space ink a la Open Brush: controller tip
(pose+q*(0,0,0.04)) polylines, segments = Entity(Mesh("mesh://box"),
Box(min,max), Material{baseColor=Color4 yellow}, lookRotation(dir) with
vertical-dir perturbation guard); caps 600 pts/stroke, 5000 total; persists
world-space ink.json (SnapshotStore.saveInk/loadInk), restores in
onSceneReady; Undo pops stroke, Clear wipes. Paint/Baseline/Diff UI REMOVED
(parked; splat machinery kept for regions; STORE.md description/shot-list
updated, help rewritten). Gallery: refreshes live on capture; recalls get
center crosshair; row checkboxes + "▶ Recall ticked" multi-recall
(selectedIds; recycled-row listener reset); grid spacing fixed for 0.93m
quads (1.05m x, 0.78m y, 1.35m dist — was clipping). Tools = 2 rows:
[Draw/Lock/Gallery/Share] + imaging row. Verified: 8.0/800 stable on device.
v8.0.1: microgesture listener fires on several consecutive frames per physical
swipe → per-bit 400ms debounce in onMicrogestureEvent (one swipe = one action);
this IS the official API (toolkit MicrogesturesSystem wraps
XR_META_hand_tracking_microgestures). Installed, 8.0.1/801 stable.
Verified via bytecode: the SDK dispatcher already edge-detects
(changedMicrogestures & state) — multi-fire = recognizer chatter, so the
debounce is ours (neither Meta docs nor Dilmer demos prescribe one; Meta
design doc only suggests posture/context gating).
v8.0.2: recall bitmaps get center spot temperature next to the crosshair
(rawToCelsius of center raw — uses CURRENT calibration offset, same as span
labels). v8.0.3 (built, install pending headset wake — watcher polling):
(1) Lock "2 windows" bug: the separate floating readout label was placed
"above" in PLANE-LOCAL coords — on grass/floors that's a horizontal offset →
looked like a second disconnected window on the ground. Fix: ONE panel per
region (region_panel.xml: readout strip atop the thermal image; 2x display
buffer for crisp text, strip height 44px, quad grows by stripM =
heightM*44/(texH*2), center rides up stripM/2 so the image stays 1:1 on the
rect; labelEntity gone, region_label.xml unused). (2) Movable drawings: each
ink stroke gets a grabbable yellow sphere handle (mesh://sphere,
Sphere(0.022), Grabbable) at its start; segments are TransformParent children
with LOCAL poses; ink.json still stores WORLD points (inkWorldStrokes applies
current handle pose at save — moved drawings reload where parked); 10s sync
persists handle moves.
v8.0.4 (INSTALLED via USB, 804 stable): right thumbstick CLICK
(ButtonBits.ButtonThumbRClick) = 📸 capture on controllers (trigger is UI-
select under ISDK, so it can't double as capture); right hint card + help
updated; hint cards grown to 0.20×0.176m / 232×204dp (5 rows).
v8.0.5 (INSTALLED, 805 stable): drawing push/pull — right stick ⇅
(ButtonThumbRU/RD bits, continuous while deflected) moves a drawing along the
controller→ball axis at ~1.5 m/s, clamped 0.25–6m: target = handle with
Grabbable.isGrabbed (verified via javap) else ray-picked ball (12cm aim
assist, nearest within 6m). Caveat: Transform writes DURING an active ISDK
grab may be overridden by the grab loop — the point-at path is the guaranteed
fallback; verify grab-path behavior on device. Stick click stays the ONLY
controller capture trigger.
v8.0.6 (built — watcher queued, Quest offline): point-don't-grab manipulation.
rayPickInk targets EVERY stroke point (10cm aim assist, nearest within 6m),
not just the ball; ~20Hz onRightAim hover pass brightens the hovered ball's
Material (Color4 1,1,0.75) so users see the stick target; stick ⇄
(ButtonThumbRL/RR) yaws the drawing (Quaternion(0, ±1.4°, 0) premul per tick
≈ 60°/s about the ball); ⇅ push/pull unchanged; close-range hand grab kept.
onStickMove(dx,dy,pose) replaced onPushPull.
⚠ QUEST IP CHANGED: now 192.168.68.75 (was .93 — DHCP; this is why Wi-Fi
watchers silently never fired). Check the IP in the in-app guide/user's URL
before trusting any reachability probe.
v8.0.9 (INSTALLED over Wi-Fi relay to .75, verified live): index page = link
bar (export/config/status + log/raw in debug) over the stream; /export fixed —
gated on gallery non-empty (was paintSurfaces/snapEntities → "nothing to
export" after Clear), now ships gallery/<id>.{png,raw,photo.jpg} + name/
palette/capturedAt meta; zip renamed thermaspace-export.zip; /regions kept but
unlisted (only way to set Lock alerts). Verified: ZIP downloaded from device,
3 captures × 3 files each. v8.0.10 (INSTALLED, verified): mojibake fix —
respond() now sends charset=utf-8 for text/json + <meta charset> in index
(bodies are UTF-8 but the header didn't say so → browsers guessed Latin-1
and emoji rendered as ðŸ”¥).
v8.0.7/8.0.8 (built — watcher active, Quest offline): recall header text
shrink-to-fit (Paint.measureText vs width-16, base 24f, floor 14f — timestamp
ran off the image; crosshair/spot-temp sizes untouched). Hint cards: HintLabel
now 0dp+weight+ellipsize (can never overflow the card), cards widened to
0.24m/280dp (same ~1160dp/m density), microcopy tightened (chip carries the
gesture, label carries the action — no redundant "thumb tap —" prefixes).

v7.3 (2026-07-13, superseded by v8): code-review
release. **REAL mirror root cause** (v7.1/7.2 theories incomplete): mesh panels
glue view-x to panel −X (what makes text panels readable), while splat/tx map
plane +X → column +X ⇒ ALL physically-mapped rasters (paint/regions/drawings)
displayed reflected about the plane's vertical centerline since v4. Fix:
`iv.scaleX = -1f` on the paint/region/annotation ImageViews only (NOT
snapshots/labels — photo-style content was correct); /export PNGs flipped via
Matrix preScale(-1,1) to match reality. Timestamps: SnapshotStore ids were
launch-time-seeded counter → save() now stamps max(now, nextId) (also kills
restart id-collision overwrite); gallery/recall dates read file.lastModified().
Review remediation: atomic index writes (tmp+rename; corrupt index quarantined
to index.bad.json — was silently wiping the gallery on next save);
resumed+sceneIsReady guards centralized in headPose() + drawHeldInner +
resolve/restore fns + onResume re-kick (dead-DataModel JNI crash class);
onMainSync(latch) marshals regionsJson/regionCsv/regionAlert/exporter off HTTP
threads (CME + torn PNG export); flir @Volatile + onError hops to main;
FlirOne.stop() AtomicBoolean once + no self-join (onError fires on reader
thread — 1s stall); relayoutRecalls clears pendingBitmaps (stale-FIFO wrong
image) + recycles intermediate bmp; PinchCaptureSystem gates on isActive with
prev=-1 sentinel (double-undo across tracking blips) + left-dropout resets
capture hold; lookRotation zero-vector guards (summon/recall/hints); manifest:
supportedDevices quest3|quest3s + usb.host required=false (store filtering) —
BOUNDARYLESS_APP stays required=true per Meta doc; 8-char session key;
/config rot normalized to 0..270; /raw wired (was always empty);
clearPaint clears baselines; region history 5760 (true 24h) + CSV cursor
absolute (ring-wrap drop); PaintedSurface texW/H ≤4096 + fw<2 splat guard;
odd-stroke redraw guard. Deferred (documented): panel-registration growth (no
unregister API), live-view bitmap tear (cosmetic @9fps), keystore/icon/
screenshots (STORE.md). NOTE: painted/drawn data persisted before v7.3
displays x-flipped after this fix — clear and repaint.

v7.2 (2026-07-13, INSTALLED via USB + running, versionName now real: 7.2/720;
boot logs version to /log): v7.1 never reached the device (Wi-Fi adb port 5555
was closed — Greg had installed v7 himself over USB; ALWAYS verify installed
versionCode before trusting field reports). Fixes on top of v7.1:
(a) paint stickiness — paintSurfaceRef held per right-trigger hold (rayHitsRef
gate), cleared in onPaintReleased; coincident planes had traded alternate
splat ticks → doubled image. (b) Z-LAYER SIGN: readable face is -Z ⇒ NEGATIVE
local z renders toward viewer; +z offsets had stacked content INSIDE the wall
in reverse order (paint occluded regions → "clipping artifacts"). Now paint
-0.008, region -0.02, annotation -0.032, label -0.045. (c) synthetic-wall
suppression: skip creation when a parallel MRUK plane is within 0.5m (depth
hits past plane edges created coincident 5×5m layers); synthetic must beat
MRUK by >0.30m to win a ray. (d) gallery rows: date (SimpleDateFormat of
snap.id ≈ capture ms) + palette (Snap.pal, new "pal" field) + real-photo thumb
(row_thumb_vis). (e) capture saves sidecars: id.edge (MSX Sobel mag, 8B hdr)
+ id.vis.jpg (rotated visible frame; ThermalRenderer.lastVisibleJpeg +
visibleSnapshot()). (f) multi-recall: recalledSnaps set + relayoutRecalls()
grid (3/row, 0.62m x, 0.55m y, recallOrigin = head at first recall), pooled
p2/p98 shared span, renderRecallBitmap at OUT_SCALE=8 composites edge sidecar
(msxScale/mdx/mdy placement) + photo inset (top-right 32%, white border) +
name/span/date; Undo pops last + relayouts. Old data note: pre-v7.2 captures
have no edge/vis sidecars (rows show no photo; recalls have no lines).
(1) mirrored double drawing ROOT-CAUSED: readable panel face is **-Z**
(ironclad: head-locked overlay uses q=head.q and is readable ⇒ -Z faces
viewer), so MRUK plane +Z points INTO the wall; a wall scanned from two rooms
has a coincident plane from EACH side and draw ticks alternated between them
(near-side line + far-side mirrored back face via DOUBLE_SIDED). Fixes:
testPlane rejects back-side hits (o.z > -0.02 → null), synthetic loses ties
to MRUK (<5cm), stroke stickiness (drawSurfaceRef + rayHitsRef keeps a stroke
on its start surface until release), gazedRegion front-side check. (2) same
-Z logic ⇒ synthetic ground/ceiling q were flipped (mirror confirmed in
field): ground=(90,0,0) (+Z down), ceiling=(-90,0,0); loadSynthetics migrates
old saves; snapshot surfaceRotation floor/ceiling pitch also swapped.
(3) rename: system keyboard does NOT reach panel EditTexts on-device →
chip name composer (chip_row1/2 in rename_bar; Bedroom/Bathroom/…/Yard +
1-4/Window/Door/Wall/Ceiling/Floor/Grass, ⌫ pops last word; chips built
programmatically in bindControlPanel). (4) duplicate "Capture N" names:
persistent prefs counter capSeq (entity counts reset on Clear/restart).
(5) recall stretched portrait captures: spawnRecalled picks
panel_snapshot_portrait when bmp.height>width. (6) Lock "too oblique" on
grass: projectFootprint null → 0.9×0.9m square around the gaze-ray hit on
the plane (regions refresh on sight regardless of lock angle).

v7 (2026-07-13, built debug+release — installed on device, field-tested):
Greg's field-test fixes. (1) boundary: manifest
`com.oculus.feature.BOUNDARYLESS_APP required=true` (verified via meta_docs —
passthrough-only apps may disable the boundary) → no "reset boundary"/lost-
controller interruptions between rooms. (2) synthetic WALLS: surfaceTarget
depth fallback also creates `wall-N` SyntheticPlane (|n.y|<0.4 → wall, 5×5m,
+Z into wall = lookRotation(-n horiz); reuse match: normal dot>0.85 +
off-plane<0.35m + lateral<4.5m) so Draw/Paint/Lock work in UNSCANNED rooms;
slopes still skipped; SyntheticPlane.half now kind-dependent. (3) gallery-safe
Clear: Snap.inWorld ("world" in index.json); Undo/Clear → setInWorld(false)/
unplaceAll(), files never deleted; only gallery 🗑 deletes (also destroys the
placed entity); restore paths filter inWorld. (4) rename fixed: AlertDialog
never renders inside spatial panels → inline rename_bar (EditText + ✔/✕) atop
gallery list. (5) buttons: PinchCaptureSystem edge-detects A/B/X/Y (ButtonBits
verified via javap; local prev-state ints, not changedButtons): A=overlay,
B=draw mode, X=undo, Y=summonPanel. (6) controller hint cards:
ControllerHintSystem + controller_hints_{left,right}.xml (HintRow/HintChip/
HintLabel styles, hint_card/hint_chip drawables) — 0.20×0.152m Mesh panels,
NoCollision, float 0.13m above a controller when gazed (dot>0.92 show /
<0.85 hide hysteresis, dist<1.1m, Controller.isActive gate), nlerp-follow,
billboard = lookRotation(cardPos−head); right card's trigger row live-updates
with mode (hintRTrigger). Device-verify: hint card size/legibility, synthetic-
wall draw in an unscanned room, rename summons system keyboard, boundaryless
actually suppresses the prompt.

v6 (2026-07-12, installed pending device): 6 features. (A) span editor: renderer
.nudgeSpan(low,deltaC), span_row steppers visible when locked, prefs spanLo/Hi.
(B) camera UX: CamState SEARCHING/PERMISSION/ASLEEP/STREAMING → camera_placeholder
TextView over live_view; cameraWatch() 4s poll auto-connects + detects sleep
(no frames >4s); USB attach/detach receiver actions added; onError→ASLEEP.
(C) drawing: AnnotationSurface.kt (yellow strokes, plane-local meters, draw-uuid
.json), ✏️ Draw button, controller-ray via PinchCaptureSystem onPaintHeld(Pose?)
+onPaintReleased; mutually exclusive w/ paint. (D) double-sided: makeDoubleSided()
sets SceneMaterial.setSidedness(DOUBLE_SIDED) on content panels (through-wall
viewing) + anchor world-pos logged. (E) SurfaceRef/SyntheticPlane: surfaceTarget
(origin,dir) unifies MRUK+depth; raycastEnvironment normal ±Y → ground-N/ceiling-N
8×8m world-locked planes (synthetic.json); paint/draw/lock/diff all work on them;
region low alert alertLowC + ❄ + /regions?alertlow. (F) gallery: Snap.name +
renameSnap (AlertDialog EditText), gallery_row.xml ListView, Recall floats a
grabbable copy, 2nd Recall = side-by-side recolorized from .raw to shared
percentile span (true comparison). Panel now 0.62×0.70m/720×815dp. Risk flags:
ceiling flip orientation + through-wall visibility unverified on device;
outdoor tracking needs dusk.

v5.2 (2026-07-12): HTTP server gated for store. MjpegServer: sharingEnabled/
requireKey/sessionKey/debugEndpoints; 403 with hint unless enabled+keyed;
index html injects ?key= into stream src. Debug builds (BuildConfig.DEBUG,
needs buildFeatures{buildConfig=true} in gradle): always-on, no key, /log+/raw
exist — dev workflow unchanged. Release: 📡 Share button in Tools mints
4-digit session key, shows URL in status, auto-off in onPause; /log+/raw
compiled out (debugEndpoints=false). assembleRelease compiles (unsigned — still
needs keystore). STORE.md: gating marked DONE + ready-to-publish privacy-policy
draft (satisfies VRC.Quest.Privacy.4 "does not collect" path). API.md: new
Access control section.

v5.1 (2026-07-12): code review of paint/lock/diff + Tools submenu. Fixes:
region history throttled to 15s appends (was 2Hz → 24h ring filled in 24min;
lastSample still updates live), regionsJson used unsynchronized history.size
from HTTP thread (→ sampleCount accessor), paint-in-diff-mode used hardcoded
40-count range (→ lastDiffRange from applyDiff), baselineArmedMs reset after
save (third-tap re-arms), clearAll resets diffMode, toneGen released in
onDestroy. UX: control panel = 2 simple rows (Capture/Overlay/Tools/? +
Snap/Undo/Clear) with Paint/Lock/Baseline/Diff + imaging row collapsed behind
"🔬 Tools ▾" (tools_rows visibility, prefs toolsOpen, default closed).

v5 (2026-07-12): SLAM-lock WATCH REGIONS + multi-session DIFF (both installed,
in-headset test pending). WatchedRegion.kt wraps a dense (128 texel/m)
PaintedSurface + history ring (2880 samples) + alertC w/ 2°C hysteresis +
floating readout label panel (region_label.xml, red when alerted, ToneGenerator
beep). Lock btn: paintTarget → PaintedSurface.projectFootprint (factored out of
splat) → AABB clamped to plane = region; Lock-while-gazing-region removes it.
regionTick 500ms: camera awake (lastFrameMs<3s) + dist<4m + gaze dot>0.93 →
splat+colorize+recordSample. Persistence: regions.json + region-<id>.{raw,edge,
csv} (CSV drained on 10s timer). HTTP: /regions (JSON), /regions?id=N (CSV),
/regions?id=N&alert=140|off (display units). Diff: 💾 Baseline copies
paint-*.raw → baseline-*.raw (two-tap overwrite guard); 🧭 Diff recolorizes via
colorizeDiff(DIVERGING blue-white-red LUT, current−baseline, ±p95|delta|, floor
20 counts ≈ 0.5°C; ~40 raw counts ≈ 1°C on this unit); diffMode guards in
recolorizeAllPaint + paint tick; /export adds baseline/. Control panel now 4
button rows (0.62×0.62m, 720×725dp). Caveat: regions/baseline die with room
re-scan (uuid-keyed).

v4.3 (2026-07-12): "paint looks nothing like overlay" — validated remotely by
comparing /stream frame vs /export raw distributions. Two causes: (1) paint
bounds were abs min/max over all data → dead-pixel outliers crushed contrast;
now p1/p99 percentiles of painted samples (same clipping live view uses).
(2) most overlay "detail" is MSX edges, which paint lacked → PaintedSurface now
has an edge ByteArray channel: renderer stores latest Sobel magnitudes
(edgeSnapshot()), splat samples them per texel, colorize() composites
additively; persisted as paint-<uuid>.edge. Remaining truth: a thermally
uniform wall (p5-p95 = 29 counts ≈ 1.3°F) is genuinely flat on a global scale —
per-frame autogain contrast is unreproducible in a comparable survey.

v4.2 (2026-07-12): paint quads looked stretched — per-surface panels used the
DEFAULT display density, so multi-meter walls requested huge view buffers
(clamped → wrong aspect). Fix: DpDisplayOptions(texW, texH) pins the buffer to
the texture resolution (1dp/texel). Rotation-restore stretch: renderer prefs
(rot/pal/°F) now load in onCreate BEFORE onSceneReady builds the overlay quad
(was loaded at panel-bind → overlay born landscape while renderer portrait).
Splat diagnostics: /log gets "splat WALL_FACE d=1.2m bbox …" lines while
spraying (bbox fraction of wall proves buffer geometry vs display issues).

v4.1 (2026-07-12): paint colorize bug — walls painted BLACK because bounds came
from the live view's span (hot object in view → walls below scale). Fix:
paintBounds() = locked span else combined vMin/vMax of ALL painted data (+5%
pad), recolorizeAllPaint() on palette/span change + throttled as bounds widen;
PaintedSurface tracks vMin/vMax (recomputeRange() after load); monster-splat
guard (corner t > 3×center t → skip). °C/°F toggle (btn_unit, display-only,
prefs+/config?unit=f|c). Mount: M6 variant flirone-*-m6.{scad,stl} (6.4mm hole),
M5 originals unchanged. Paint texture is source-limited: 80×60 over FOV ≈ 4cm/px
at 3m — paint from ≤1.5m for crisp texels, not a resolution bug.

v4 (2026-07-12): **ThermaSpace** (renamed for store; label+icon in manifest,
mipmaps from store/icon-*.png; store kit in store/STORE.md — name verified
unique, no real-thermal Quest apps exist). Scene painting: PaintedSurface.kt
(per-MRUK-plane radiometric float buffer @96 texel/m, frustum-corner projection
+ iterative inverse-bilinear splat, latest-wins, grazing guard >75°); paint =
Paint button + hold RIGHT trigger (PinchCaptureSystem onPaintHeld → 180ms
throttled tick); surfaces = per-surface RUNTIME panel registrations (registerPanel() on
AppSystemActivity + View.generateViewId()) at exact wall dims — the earlier
Scale(w,h,1)-stretched shared registration displayed as a 1×1m quad at wall
center ("paint appears where I'm not looking"); view binds via closure, no
pending queue. Control panel: fixed DpDisplayOptions(720×675) on a
0.62×0.58m quad + styles.xml PanelButton (singleLine, textAllCaps=false) —
DpPerMeterDisplayOptions default was too dense → cramped/wrapping buttons; persist paint-<uuid>.raw + paint.json via savePaint on 10s
timer; /export = ZIP (surface PNGs+raw, snapshots, heatmap.json). Entering
paint auto-locks span. Clear wipes paint too. install.sh reinstalls both APKs
(Horizon OS culled the sideloaded app once). Opportunity Hack blurb in help.
Store TODO before submission: release keystore, 1440×1440 icon, screenshots,
privacy URL, gate debug HTTP server off in release.

v3: black-screen fix = snapshot panels MUST use PanelRenderMode.Mesh (compositor
layer cap ~16 → XR_ERROR_LAYER_LIMIT_EXCEEDED kills ALL rendering incl.
passthrough). MRUK anchor queries hang forever without manifest permission
com.oculus.permission.USE_ANCHOR_API; depth raycast needs explicit
mrukFeature.startEnvironmentRaycaster(); loadSceneFromDevice must run after
onSceneReady. Imaging modes in ThermalRenderer: 4 palettes, span lock
(°C-fixed color scale for comparable heatmap snapshots), isotherm HOT/COLD
(top/bottom 15%), rotation 0/90/180/270 (portrait dangle vs landscape cradle;
swaps overlay+snapshot quad aspect via *_portrait panel registrations), scale
bar; radiometric .raw sidecars saved per capture (8B header + LE u16).
/config adds pal, iso, isofrac, rot, span=lo,hi|auto.

- `flirone.py`: complete Python driver (pyusb + libusb-package backend; Homebrew
  libusb here is x86_64-only — don't use it). Protocol verified against
  flirone-v4l2. Streams on Linux hosts only.
- `quest3-app/`: complete Kotlin USB-host app (viewer + MJPEG server :8080),
  untested — local Android SDK is too old to build (build-tools ≤29); use
  Android Studio. No Quest was connected via adb this session.
- `mac_viewer.py`: shows the Quest MJPEG stream on the Mac.

## Hard-won facts (don't re-litigate)
- **macOS cannot stream this device.** Firmware uses SET_INTERFACE alt1 as
  "start" but declares endpoints only in alt0. IOUSBHostFamily intercepts every
  SET_INTERFACE (libusb AND raw encodings: recipient=device/endpoint/class,
  wValue/wIndex high-byte garbage) → pipes torn down or firmware crash+re-enum.
  fileio (intf 1) and frame (intf 2) EPs are dead (stall/IO-err) until "started".
  Raw rosebud JSON on intf 0 (EP 0x02/0x81, flirone.c's channel) is accepted
  (writes OK) but macOS `accessoryd` has the firmware in iAP2-link mode → device
  resets. libusb#729 = same wall, closed unresolved.
- Firmware is fragile: malformed EP0 traffic (esp. zero-length disguised
  requests, GET_INTERFACE while stopped) crashes it; it re-enumerates in ~5-10s.
- Device is stable when idle; `accessoryd` holds intf 0 (iAP) — never claim it.
- Untried Mac ideas: sudo libusb capture (kills accessoryd grip; frames still
  blocked, but rosebud JSON/fileio might work → CameraFiles.zip calibration),
  VMware/Parallels passthrough (unknown if they re-validate alt descriptors).

## Env
- Use `.venv/bin/python` (arm64). Deps in requirements.txt.
- RETRO.md = distilled post-mortem (dead ends, undocumented SDK gotchas,
  tooling traps) — read it before debugging anything Quest/SDK-shaped.
