# ThermaSpace Retro — what hurt, what was undocumented, what never worked

FLIR One Pro LT → Quest 3 thermal AR, ~1 week of sessions (2026-07). Companion to
CLAUDE.md; this is the distilled "don't step on these again" list.

## Never worked — stop trying

- **macOS cannot stream the FLIR One. Period.** The firmware uses
  `SET_INTERFACE alt1` as its "start streaming" command but only declares
  endpoints in alt0 — macOS `IOUSBHostFamily` intercepts *every* SET_INTERFACE
  encoding we tried (libusb, raw recipient=device/endpoint/class, garbage
  wValue high bytes) and either tears the pipes down or crashes the firmware
  into re-enumeration. `accessoryd` simultaneously holds intf 0 in iAP2 mode.
  libusb issue #729 hit the same wall and closed unresolved. **Days** went here.
  Escape hatch: Android USB host (Quest) or Linux, where the protocol works
  first try.

## Top time-wasters (ranked by iteration count)

1. **The mirror saga — 3 releases (v7.1 → v7.3) to root-cause one bug.**
   Spatial SDK panels' readable face is **−Z**, nowhere stated in docs. Cascade
   of consequences discovered one release at a time: MRUK wall planes have +Z
   pointing INTO the wall; a wall scanned from two rooms gets a coincident
   plane from EACH side (draws alternated between them → "mirrored double"
   drawings); +z content offsets stack INSIDE walls in reverse order; synthetic
   floor/ceiling rotations were flipped; and the true root cause — mesh panels
   glue view-x to panel −X, so **every physically-mapped raster displayed
   x-flipped since v4** (fix: `iv.scaleX = -1f`). Lesson: establish the render
   coordinate convention with a throwaway test panel on day 1, not from bug
   reports.
2. **Hand tracking silently maps pinches onto ButtonX/ButtonA.** v7 bound face
   buttons to actions → pinches fired undo/overlay and users "couldn't click
   any menu." Not in the input docs. Fixes: gate ALL button handling on
   `Controller.type == ControllerType.CONTROLLER`, and add `IsdkFeature` —
   without it hands can grab but never *click* panels.
3. **v7.1 field-test debugging was 100% wasted — the build was never on the
   device.** Wi-Fi adb port was closed; Greg had sideloaded v7 himself. Now
   versionName/Code is logged at boot to `/log`. **Always verify installed
   versionCode before trusting a field report.** Related: DHCP changed the
   Quest's IP (.93 → .75) and Wi-Fi watchers silently never fired.
4. **Physical-part iteration from verbal geometry — fork mount took 3 prints.**
   "Two prongs" → wrong plane → wrong profile. An annotated screenshot resolved
   in one shot what two rounds of prose couldn't. Ask for a sketch first.

## Undocumented / misleading in Meta docs (each cost a debug cycle)

- **Compositor layer cap (~16) turns the screen black.** Default panels each
  eat a layer; exceed the cap and `xrEndFrame` fails with
  `XR_ERROR_LAYER_LIMIT_EXCEEDED` — ALL rendering dies including passthrough.
  Snapshots must use `PanelRenderMode.Mesh()`. Docs never connect "many
  panels" to "black screen."
- **MRUK anchor queries hang forever** (no error, no timeout) without manifest
  `com.oculus.permission.USE_ANCHOR_API`. `loadSceneFromDevice` must run after
  `onSceneReady`; depth raycasts need an explicit
  `startEnvironmentRaycaster()` call.
- **MicrogesturesSystem is auto-registered by the toolkit** — registering your
  own crashes with "Duplicate Systems" (attach a listener to
  `tryFindSystem` instead). The recognizer also re-fires one physical swipe on
  several consecutive frames; the SDK dispatcher edge-detects but does NOT
  debounce (verified via bytecode) — we needed our own 400 ms per-bit debounce.
- **`ButtonThumbRClick` is raised during plain right-stick deflection**
  (phantom captures on any stick flick). Bits don't overlap (verified via
  javap) — the overlap happens in native buttonState population. Fix: accept
  the click edge only when direction bits were neutral this frame AND last.
- **Spatial SDK gradle plugin on the ROOT classpath breaks Kotlin compilation**
  (its bundled kotlin-compiler-embeddable → `IncrementalCompilationFeatures`
  errors). Apply it at module level only, exactly like Meta's samples. Version
  matrix that finally worked: Spatial SDK 0.13.1 + gradle 8.13 + Kotlin 2.1.0 +
  AGP 8.11.1.
- **Panel display density is a trap.** Default `DpPerMeterDisplayOptions` on a
  multi-meter panel requests a huge view buffer that gets silently clamped →
  stretched/wrong-aspect content. Pin with `DpDisplayOptions(texW, texH)`.
- **No cylinder mesh primitive** (`mesh://box`/`sphere` only; cylinders exist
  only as compositor layers) — the draw wand is 2 boxes crossed at 45°.
- **Android UI doesn't fully work inside spatial panels:** AlertDialogs never
  render; the system keyboard can't reach panel EditTexts (→ word-chip name
  composer). `Query.eval()` returns a Sequence, not a List. Quaternion ctor:
  4-arg is (w,x,y,z) but 3-arg is euler *degrees* — silent misuse hazard.
- **`panelSetupWithRootView`'s 3rd lambda param is the panel's Entity** —
  found via `javap` on the toolkit jar, not docs. This unlocked correct
  per-entity payload binding (a shared FIFO mis-paired bitmaps).
- **Native DataModel dies with the VR session:** any entity access from a
  callback queued before onPause → JNI assert crash. Everything needs
  `resumed && sceneIsReady` guards.
- **Transform writes during an active ISDK grab can be overridden** by the
  grab loop — point-at-and-stick manipulation is the reliable path.
- **`BOUNDARYLESS_APP` (required=true) kills boundary interruptions** for
  passthrough-only apps — documented, but buried; transformed multi-room use.
- Benign-but-scary logcat: `xrGetSpaceRoomLayoutFB`/`xrRequestSceneCaptureFB`
  "not supported" warnings are normal.
- **In-App Analytics exists but is triple-gated** (this week): App ID +
  approved Data Use Checkup + Store-distributed build; also incompatible with
  a "does not collect" privacy policy. Local-only counters shipped instead;
  recipe parked in Analytics.kt.
- **An asleep headset never inflates panels** even though ActivityManager says
  the activity is resumed — smoke tests silently show nothing happening. Wake
  for automation: `adb shell input keyevent KEYCODE_WAKEUP` +
  `am broadcast -a com.oculus.vrpowermanager.prox_close`
  (undo: `automation_disable`).

## Mac/tooling traps

- **macOS Local Network TCC blocks adb over Wi-Fi** (GUI-less binary can't be
  granted permission) — relay through Apple-signed nc:
  `mkfifo p; while true; do nc -l 127.0.0.1 5556 <p | nc <quest-ip> 5555 >p; done`.
- **/private/tmp cleaner deletes files >3 days** — it ate our scratchpad
  gradle installs mid-project. Fixed permanently with a real gradle wrapper in
  the repo. Separately, `local.properties` vanished once; recreate with
  `sdk.dir=$HOME/Library/Android/sdk`.
- Homebrew libusb is x86_64-only on this machine — use `libusb-package` in the
  arm64 venv.
- Build JDK: Android Studio's JBR (`JAVA_HOME=/Applications/Android
  Studio.app/Contents/jbr/Contents/Home`).

## Device quirks (FLIR One Pro LT)

- Status JSON **always** contains `"ffcState":"FFC_…"` — filtering frames on it
  blanks the stream forever; only skip while `shutterState` starts with FFC.
- USB attach relaunches the activity: without `launchMode=singleTask` two
  instances fight over the HTTP port.
- Camera sleeps ~1 h into idle with no detach event — detect via frame-gap
  (>4 s) watchdog, not USB state. ~40 raw counts ≈ 1 °C on this unit.
- Firmware is fragile: malformed EP0 traffic crashes it (re-enumerates in
  5–10 s). A malformed frame once killed the whole app → reader loop is now
  fully try/caught with size sanity checks.

## Claude Code tooling that built this (what pulled weight, what didn't)

- **meta-vr plugin (metavr MCP server)** — the workhorse external tool.
  `meta_docs_search` / `meta_docs_get_page` against developers.meta.com
  verified BOUNDARYLESS_APP before shipping it (v7), confirmed In-App
  Analytics exists + its App-ID/DUC/store gating (v9.1), and supplied exact
  Platform SDK coordinates. Paired with the **`hz-quest-verify-first` skill**,
  which forces a docs check before any Quest answer — the right reflex, since
  training data predates Spatial SDK 0.13 (renamed classes, moved menus).
- **Subagents (Agent tool)** — 2 parallel code-review agents drove the v9.0
  perf sweep (every high-impact finding fixed: buffer reuse, off-main IO,
  alloc-free ray picking); Explore agents for codebase sweeps.
- **Plain built-ins did ~90% of the work**: Read/Edit/Write for Kotlin/XML,
  Bash for gradle builds, adb install/dumpsys, curl probes of the on-device
  `/log`/`/stats` endpoints (that debug HTTP server was our best remote
  test rig), the nc TCC relay, and `javap` on SDK jars — the single best
  verifier of undocumented APIs (ButtonBits values, `panelSetupWithRootView`
  params, `Grabbable.isGrabbed`, microgesture dispatcher internals).
- **Background watchers** (long-running Bash) queued installs while the Quest
  was offline — and taught the DHCP lesson when the IP moved and they polled
  a dead address forever.
- **CLAUDE.md + auto-memory** — the real cross-session state. Every gotcha in
  this retro survived only because it was written down the day it happened;
  the project has no git history, so CLAUDE.md *is* the changelog.
- **Not used / didn't help**: metavr's device/app MCP tools (plain adb was
  already wired via USB + relay); Unity/Unreal skills (Kotlin Spatial SDK
  path); no scaffolding skill — the app grew from a blank
  `AppSystemActivity`. Non-Claude tooling that mattered: OpenSCAD (mounts),
  BambuStudio CLI (slicing), Python/pyusb (protocol reverse-engineering on
  Linux).

## Process lessons

- **Verify before believing:** installed versionCode before field debugging;
  API signatures via javap/bytecode when docs are silent; device IP before
  trusting a "watcher never fired."
- **Big deletions need a real snapshot.** The ~1100-line v9.0 surgery ran with
  no git history (project is not a repo) — the only backup was session tmp,
  which the OS cleaner later deleted. `git init` would have cost 10 seconds.
- **Field reports beat theory:** every input-system bug (pinch-eats-buttons,
  phantom stick capture, multi-fire swipes) was invisible in code review and
  obvious in 5 minutes of headset time.
