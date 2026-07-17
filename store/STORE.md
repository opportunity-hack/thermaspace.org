# ThermaSpace — Meta Horizon Store submission kit

## Identity
- **App name:** ThermaSpace
- **Store title (SEO):** ThermaSpace: Real Thermal Vision & Room Heatmaps
- **Package:** org.ohack.flirone.spatial (matches ohack.org — keep)
- **Verified unique (2026-07-12):** no app named ThermaSpace on the store or web;
  no real-thermal-camera Quest app exists at all — competitors are *simulations*.
  Keyword targets: thermal, thermal camera, heat vision, FLIR, heatmap,
  mixed reality, home energy, thermal monitor, temperature alert,
  home inspection, insulation.

## One-liner
See the invisible. ThermaSpace turns a FLIR One camera + Quest 3 into real
mixed-reality thermal vision: watch live temperatures on the things you care
about, pin thermal captures to your walls, and compare rooms on one scale.

## Description (draft)
ThermaSpace connects a FLIR One (USB-C) thermal camera to your Quest 3 and
overlays REAL thermal imaging — not a simulation — onto passthrough:

🔒 Live lock — tap the lock icon on any placed capture and it becomes a live
   thermal instrument: whenever you look at it, the image refreshes to what
   the camera sees right now (breaker panel, 3D printer, pipe…)
🔥 Thermal vision mode — a head-locked heat overlay on the real world, with
   hand-tracking microgestures: thumb-tap to toggle, thumb-swipe to change
   palettes or tune the temperature scale
📸 Radiometric captures — thermal snapshots pinned to walls at true scale,
   real temperatures in °C or °F, four palettes, isotherm alarms, locked
   color scales for comparable surveys
🗂 Gallery — every capture keeps its date, palette, and a real photo for
   context; recall several at once, laid out on ONE shared temperature scale
   so identical colors mean identical temperatures
✏️ 3D marker — draw notes and circles in mid-air that stay anchored in your
   room across sessions
🏠 Export everything to your computer — images, raw radiometric data, and
   context photos, in one ZIP over your own Wi-Fi

Requires: FLIR One Gen 3 / Pro / Pro LT (USB-C) camera and a room scan
(the app launches Space Setup for you if none exists).

**Made for Opportunity Hack (ohack.org)** — a nonprofit hackathon community
using code for good. ThermaSpace exists to spark interest in computer science:
every layer, from USB reverse-engineering to 3D room mapping, is open for
students to learn from. Capture the temperature history of your spaces and
understand the energy of the world around you.

## Store checklist (manual steps, in order)
1. **Meta developer account** with verified org at developers.meta.com
   (org name suggestion: "Opportunity Hack").
2. **App creation**: Horizon Store → Create app → Quest platform → "ThermaSpace".
3. **Release build**: needs a release keystore + signed AAB/APK
   (`gradle assembleRelease` after adding signingConfig; keep the keystore safe —
   store identity is permanent).
4. **Data Safety & Privacy**: app collects nothing, transmits nothing off-device.
   Privacy policy URL required — host a one-pager at
   ohack.org/thermaspace/privacy. Ready-to-publish draft:

   > **ThermaSpace Privacy Policy.** ThermaSpace does not collect, store, or
   > transmit any personal data to the developer or any third party. All
   > thermal images, room data, snapshots, and temperature histories are
   > stored only on your headset, inside the app's private storage, and are
   > deleted when you clear them in-app or uninstall the app. The optional
   > "Share" feature, off by default, lets you view your own data from another
   > device on your own local network; it requires a one-time code shown in
   > the headset, sends data to no one else, and switches off automatically
   > when you leave the app. Room-scan data is accessed via Meta's Spatial
   > Data permission solely to attach thermal imagery to your walls and never
   > leaves the device. Since no data is collected, there is nothing for the
   > developer to delete; uninstalling the app removes all stored data.
   > Questions: contact ohack.org.

   This wording satisfies VRC.Quest.Privacy.4 via the "does not collect or
   store user data" path (verified against the current VRC text).

   **Analytics note (v9.1)**: feature-usage counters are LOCAL-ONLY
   (Analytics.kt → filesDir/analytics.json; debug-build `/stats` endpoint) —
   consistent with the policy above. Meta's Platform SDK "In-App Analytics"
   (segments/events/counters sent to the Meta backend) can be wired into the
   same choke point later, but requires an App ID + approved Data Use Checkup
   AND switching the privacy policy off the "does not collect" path — see the
   header comment in Analytics.kt for the verified integration recipe.
5. **Assets** (this folder):
   - logo-1024.png / logo-512.png (icon; store requires 1440×1440 too — regenerate
     with the same script at that size)
   - Screenshots: capture in-headset via `npx metavr capture screenshot`
     (need 5+ at 2560×1440 landscape, no passthrough-privacy violations —
     staged room shots)
   - 2-3 min trailer video optional but boosts featuring odds
6. **App Lab vs full store**: submit as App Lab first (lighter review, live in
   days); full store listing after traction.
7. **Upload**: `ovr-platform-util upload-quest-build --app-id <ID> --apk <path>
   --channel ALPHA` then promote.
8. Store metadata: category Utilities (or Productivity), comfort rating
   Comfortable, supported devices Quest 3/3S (passthrough + depth required),
   permissions justification: USB host (thermal camera), spatial data (room
   anchoring), internet (LAN viewer).
9. **Boundaryless** (v7): manifest declares
   `com.oculus.feature.BOUNDARYLESS_APP` — permitted because the app is
   passthrough-only with zero immersive content (Meta's documented condition).
   If review asks: cite the boundaryless best-practices doc; the app never
   occludes the real world (all content is anchored panels over passthrough).

## Review video shot list (hardware-dependent app — reviewers need this)
1. Plug in camera → USB permission → live thermal panel
2. Overlay mode walking the room
3. Capture something warm onto a wall → tap its 🔒 icon → walk away → return
   and look at it → the image refreshes live; tap 🔒 again to freeze
4. Draw a 3D circle around a vent with the right trigger; restart app → it's
   still there
5. Gallery: tick two captures → Recall ticked → side-by-side on one scale
   (photo insets visible)
6. Hands only: pinch to click buttons, thumb-tap for overlay, swipe palettes
7. /export on a laptop showing PNGs + CSV history

## Known review risks
- USB peripheral requirement: store review needs the camera to test → provide
  a review video showing full flow (Meta accepts hardware-dependent apps with
  demonstration video).
- `USE_ANCHOR_API`/`USE_SCENE`: justified (room-locked content) — describe in
  the review notes.
- ~~Gate the HTTP server~~ **DONE (v5.2)**: release builds ship with the
  server OFF by default; the user enables "📡 Share" in Tools, which mints a
  per-session random key required on every request and auto-disables when the
  app pauses. /log and /raw exist only in debug builds. Review notes should
  state exactly this.
- Audio: alert beep uses ToneGenerator on the notification stream — benign,
  but mention it in review notes so it isn't mistaken for background audio.
