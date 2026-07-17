# FLIR One Pro LT → Quest 3 (Elite Strap) mount

**Print-ready files (verified renders, for Bambu Studio):**

M5 hardware (standard GoPro thumbscrews):
- `flirone-cradle.stl` / `flirone-base.stl` / `flirone-saddle.stl`
- source: `flirone-quest3-mount.scad`

M6 hardware (M6 bolt + wing nut; hole 6.4 mm, knuckles otherwise GoPro-sized):
- `flirone-cradle-m6.stl` / `flirone-base-m6.stl` / `flirone-saddle-m6.stl`
- source: `flirone-quest3-mount-m6.scad` — use an M6 × 25–30 mm bolt

**Tape-free hanging mounts** (source: `flirone-quest3-mount-hang.scad`;
M6 variants have the `-m6` suffix):
- `flirone-hook.stl` — **strap hang**: plate rests on the visor front, a
  35 mm tongue slides between the top head strap and the shell, rear lip
  retains it. ⚠ Needs a strap that CROSSES THE VISOR TOP (stock top strap,
  BoboVR/Kiwi halo band…) — the bare Elite Strap has no top strap, so use
  the clip below with it.
- `flirone-fork.stl` — **strap staple**: solid tongue ending in a sharp 90°
  turn DOWNWARD (side profile ⊓, all square corners — see
  `flirone-fork-preview.png`). Drop it over any head strap from above; the
  strap sits in the ~31 mm channel between the front plate and the rear
  drop arm. `FORK_REACH` (35) / `DROP_LEN` (22) / `DROP_T` in the .scad.
- `flirone-clip.stl` (Quest 3) / `flirone-clip-q3s.stl` (Quest 3S) — **top
  clip**: bridges the top plastic shell, front drop plate + sprung rear lip
  gripping in front of the facial interface. Works with any strap. Measure
  `TOP_DEPTH` (front-to-back across the visor top at center; presets
  Q3 = 42 mm, Q3S = 58 mm are estimates) before printing.
- Both hang the same GoPro female knuckle at top-center-front, raised so the
  camera body sits mostly ABOVE the top edge — clear of the Quest 3 sensor
  pills (center pill = depth projector!) and the 3S camera clusters. After
  mounting, sanity-check passthrough/hand tracking once and pitch the camera
  up if anything occludes.
- Pad shell-contact faces with 1–2 mm craft foam.
- `flirone-clip-m6-H2S.3mf` — **print-ready H2S project** (sliced,
  BambuStudio CLI): side orientation (⊓ profile flat → all clip faces are
  vertical walls, knuckle bore prints as a clean vertical hole), 0.24 mm
  Standard, PETG HF, textured PEI, tree(auto) supports, 2 walls / 10 %
  gyroid. **45 min 47 s, 14.7 g** incl. supports. (3 walls / 15 % variant
  measured 49 m 43 s / 18.7 g — reslice if you want extra margin.)

(OpenSCAD installed at `/Applications/OpenSCAD-2021.01.app`)

Sized for the **FLIR One Pro LT** (435-0013: 68 × 34 × 14 mm, 36.5 g — confirmed
against the official datasheet; the camera's 80×60 stream confirms the LT).
The front is a picture-frame opening, so exact lens/button positions don't
matter. Camera drops in from the top; USB-C OneFit plug points up.

**Why GoPro knuckle:** the M5 thumbscrew hinge axis is horizontal → **pitch
adjustment**, which is how you align the camera's optical axis to your gaze.
Your Elite Strap has **no top strap**, so the flat base + 3M VHB foam tape on
the visor top-center is the recommended attachment (36.5 g is nothing; VHB
conforms to the visor curve and is removable).

## Hardware
- 3M VHB foam tape (1 mm+) — for the base plate
- M5 × 20 bolt + wing/thumb nut (or a GoPro thumbscrew)
- Short **right-angle USB-C male→female extension** (camera top plug → Quest
  left-side port)

## Bambu H2S settings
- **PETG or ASA** (warm camera + headset, PLA creeps), 0.2 mm layer,
  4 walls, 40 % infill
- Cradle: print with the **back face down** (knuckle fingers vertical — layer
  lines run along the fingers, strongest in the clamp direction)
- Base: flat side down; Saddle: bridge top down, supports for the knuckle
- Clearances assume PETG (`TOL = 0.35`); use 0.25 for ASA

## Before printing, verify with calipers (edit .scad + re-export if off)
- Body 68 × 34 × 14 (datasheet value; check yours incl. rubber bumper)
- If snapping the saddle instead of the base: `VISOR_TOP_DEPTH` (front-to-back
  across the visor top at center; default 42 is a guess)

## Alignment procedure (once mounted)
1. In FLIR Spatial, enable **Overlay**; look at a hot object ~1.5 m away.
2. Loosen the thumbscrew, tilt until the hot spot in the overlay sits on the
   real object, tighten.
3. Residual fixed offset (camera sits above eye line) gets a software
   correction next (`pitch/yoff` config — planned).

Re-export after edits:
```
/Applications/OpenSCAD-2021.01.app/Contents/MacOS/OpenSCAD \
  -o flirone-cradle.stl -D 'PART="cradle"' flirone-quest3-mount.scad
```
