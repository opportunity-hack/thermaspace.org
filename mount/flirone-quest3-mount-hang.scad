// FLIR One PRO LT hanging mounts for Meta Quest 3 / Quest 3S
// ============================================================
// Two TAPE-FREE alternatives to the stick-on VHB base plate. Both hang the
// standard GoPro female knuckle at the top-center of the visor FRONT, so the
// existing cradle (flirone-quest3-mount.scad "cradle") clips straight on and
// pitch-adjusts with the M5/M6 thumbscrew as before.
//
// Parts (set PART):
//   "hook" - STRAP HANG: a plate rests on the visor front; a 35 mm tongue
//            slides between the top head strap and the shell, so the mount
//            hangs from the strap — nothing glued. Works with any strap that
//            crosses the top of the visor (stock top strap, Elite-style top
//            band, BoboVR/Kiwi etc.). The rear up-lip keeps the tongue from
//            sliding back out; strap tension pins it down when worn.
//   "fork" - STRAP STAPLE: a SOLID tongue that ends in a sharp 90° turn
//            DOWNWARD (side profile = ⊓, mirroring the front plate's own
//            90°). Drop it over any head strap from above: the strap sits
//            in the channel between the front plate and the rear drop arm.
//            All corners are square 90° — no chamfers, no rounding.
//   "clip" - TOP CLIP: bridges the top plastic shell (front drop + sprung
//            rear lip that grips just in front of the facial interface).
//            No strap needed. Set DEVICE to pick the Quest 3 / 3S preset,
//            and MEASURE the front-to-back depth across the visor top at
//            center with calipers first (shells are curved; ±2 mm matters).
//
// CAMERA KEEP-OUT (why everything stays high and centered):
//   Quest 3  — three vertical sensor pills across the mid-face (center pill
//              = depth projector, at x = 0!) starting roughly 20-25 mm below
//              the top edge, plus downward tracking cams on the bottom edge.
//   Quest 3S — two sensor clusters LEFT and RIGHT of center; top-center is
//              clear plastic.
//   Both designs therefore: (a) keep the front plate short (PLATE_H), and
//   (b) raise the knuckle so the camera body sits mostly ABOVE the top edge.
//   After mounting, check passthrough + hand tracking once — if anything
//   occludes, pitch the camera up or increase KNUCKLE_RAISE and reprint.
//
// Print: PETG/ASA, plate face down where possible, supports under the
// knuckle fingers. Add a strip of 1-2 mm craft foam on the surfaces that
// touch the shell (protects the plastic, adds grip).

PART = "all";        // "hook" | "fork" | "clip" | "all"
DEVICE = "q3";       // "q3" | "q3s"  (clip depth preset)
GP_HOLE = 5.3;       // 5.3 = M5 thumbscrew, 6.4 = M6

// ---------------- shared front plate ----------------
PLATE_W = 40;        // lateral — clears both headsets' sensor areas
PLATE_H = 26;        // KEEP SHORT: Quest 3's center depth pill starts
                     // ~20-25 mm below the top edge (see keep-out above)
PLATE_T = 4;

// ---------------- "hook" (strap hang) ----------------
HOOK_REACH = 35;     // tongue length over the visor top (requested 35 mm)
HOOK_T     = 3.5;    // tongue thickness (sits between strap and shell)
HOOK_W     = 34;     // fits 25-28 mm straps with margin
HOOK_LIP   = 8;      // rear up-lip height (strap retention)
HOOK_RAISE = 4;      // knuckle center above the visor top edge

// ---------------- "fork" (strap staple) ----------------
FORK_REACH = 35;     // solid tongue length to the 90° turn (the 35 mm reach)
DROP_LEN   = 22;     // rear drop arm: how far the 90° turn goes down
DROP_T     = 3.5;    // drop arm thickness

// ---------------- "clip" (top shell) ----------------
TOP_DEPTH  = (DEVICE == "q3s") ? 58 : 42;  // MEASURE YOUR HEADSET (mm,
                     // front-to-back across the visor top at center)
CLIP_W     = 50;     // bridge width
CLIP_TOP_T = 3.2;
REAR_LIP   = 14;     // drops behind the shell, in front of the face pad
REAR_LIP_T = 2.6;    // thin = springy grip
CLIP_RAISE = 6;      // knuckle center above the shell top

// ---------------- GoPro geometry (matches flirone-quest3-mount.scad) ----
GP_FEM_T    = 3.00;
GP_FEM_SLOT = 3.15;
GP_R        = 7.5;
GP_STEM     = 8.0;

$fn = 64;

// ================================================== GoPro female knuckle
module finger(t, stem) {
    linear_extrude(height = t)
        difference() {
            hull() {
                circle(r = GP_R);
                translate([-GP_R, -stem - 1]) square([2*GP_R, 1]);
            }
            circle(d = GP_HOLE);
        }
}

// hole axis along X (pitch hinge), stem pointing -Z, center at origin
module gopro_female_x() {
    rotate([0, 90, 0])
        for (o = [-(GP_FEM_SLOT + GP_FEM_T), 0, GP_FEM_SLOT + GP_FEM_T])
            translate([0, 0, o - GP_FEM_T/2])
                finger(GP_FEM_T, GP_STEM);
}

// ================================================== shared pieces
// Coordinates: x lateral (centered), +y rearward (toward the head),
// z up. The plate's REAR face (y = PLATE_T) rests on the visor front;
// the visor's top edge is at z = 0.

// vertical plate on the visor front + knuckle riser wall above z=0.
// Knuckle stem points +y into the wall (1.5 mm overlap, the same graze
// convention the proven cradle/base parts use).
module front_plate(raise) {
    // hanging plate (rounded corners)
    hull()
        for (x = [-PLATE_W/2 + 4, PLATE_W/2 - 4], z = [-PLATE_H + 4, -4])
            translate([x, 0, z]) rotate([-90, 0, 0]) cylinder(r = 4, h = PLATE_T);
    // riser wall carrying the knuckle above the top edge
    riser_h = raise + 10;   // wall continues above the knuckle stem line
    translate([-11, 0, 0]) cube([22, PLATE_T, riser_h]);
    // knuckle: center raised, forward of the plate face
    translate([0, -(GP_STEM - 1.5), raise]) rotate([90, 0, 0]) gopro_female_x();
}

// ================================================== "hook" — strap hang
module hook() {
    front_plate(HOOK_RAISE);
    // tongue: underside (z=0) rides on the visor top shell, the head strap
    // passes OVER it — slide it under the strap from the front to install
    translate([-HOOK_W/2, 0, 0]) cube([HOOK_W, PLATE_T + HOOK_REACH, HOOK_T]);
    // rear up-lip: the strap has to be lifted over it to come off
    translate([-HOOK_W/2, PLATE_T + HOOK_REACH - HOOK_T, HOOK_T])
        cube([HOOK_W, HOOK_T, HOOK_LIP]);
    // side gussets stiffen the tongue root (camera torque)
    for (s = [-1, 1])
        translate([s*(HOOK_W/2) - (s > 0 ? 3 : 0), 0, 0])
            cube([3, PLATE_T + 12, HOOK_T + 4]);
}

// ================================================== "fork" — strap staple
// Side profile (all square 90° corners):
//      ___tongue___________
//     |                    |          The head strap sits in the channel
//   plate            drop arm         between the plate and the drop arm;
//     |    (channel)       |          the mount hangs from it. Channel
//   knuckle→               |          opening ≈ FORK_REACH − DROP_T, so it
//                                     swallows even thick padded straps.
module fork() {
    front_plate(HOOK_RAISE);
    // solid tongue (underside at z = 0 rides on the shell/strap)
    translate([-HOOK_W/2, 0, 0])
        cube([HOOK_W, PLATE_T + FORK_REACH, HOOK_T]);
    // sharp 90° turn downward at the tongue's end — mirrors the plate's 90°
    translate([-HOOK_W/2, PLATE_T + FORK_REACH - DROP_T, -DROP_LEN])
        cube([HOOK_W, DROP_T, DROP_LEN + HOOK_T]);
}

// ================================================== "clip" — top shell
module clip() {
    front_plate(CLIP_RAISE);
    depth = PLATE_T + TOP_DEPTH + REAR_LIP_T;
    // bridge across the visor top (underside at z=0 sits on the shell)
    translate([-CLIP_W/2, 0, 0]) cube([CLIP_W, depth, CLIP_TOP_T]);
    // sprung rear lip: hooks down behind the shell (in front of the face
    // pad). Slightly proud tip → snap fit; sand if too tight.
    translate([-CLIP_W/2, PLATE_T + TOP_DEPTH, -REAR_LIP]) {
        cube([CLIP_W, REAR_LIP_T, REAR_LIP + CLIP_TOP_T]);
        // inward grip nub at the tip
        translate([0, -1.2, 0]) cube([CLIP_W, 1.2, 3]);
    }
}

// ================================================== output
if (PART == "hook") hook();
else if (PART == "fork") fork();
else if (PART == "clip") clip();
else {
    hook();
    translate([90, 0, 0]) fork();
    translate([180, 0, 0]) clip();
}
