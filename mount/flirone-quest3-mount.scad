// FLIR One PRO LT (USB-C, 435-0013) cradle + GoPro mounts for Meta Quest 3
// =========================================================================
// Parts (set PART, or "all" for preview):
//   "cradle" - grips the camera; GoPro 2-finger MALE knuckle on the back
//   "base"   - flat GoPro 3-finger FEMALE plate -> stick to the visor top
//              with 3M VHB foam tape (RECOMMENDED with the Elite Strap: it
//              has no top strap, and thick VHB conforms to the visor curve)
//   "saddle" - tape-free alternative: bridge over the visor top with front
//              and rear lips (measure VISOR_TOP_DEPTH first)
//
// The GoPro knuckle axis is horizontal (left-right) => the M5 thumbscrew
// gives PITCH adjustment - that's how you align the camera's optical axis
// to your gaze for the AR overlay.
//
// Camera dims from the FLIR ONE Pro LT datasheet (435-0013-03). The front
// is a picture-frame opening, so exact lens/button positions don't matter:
// only the outer few mm of the front face are covered.

PART = "all";        // "cradle" | "base" | "saddle" | "all"

// ---------------- camera (verify with calipers) ----------------
BODY_W = 68.0;       // datasheet: 68 x 34 x 14 mm
BODY_H = 34.0;
BODY_D = 14.0;
TOL    = 0.35;       // per-side clearance (PETG 0.35, ASA 0.25)

// ---------------- cradle ----------------
WALL     = 2.4;      // side + front-frame wall
FLOOR    = 2.4;
BACK     = 3.2;      // back wall (carries the knuckle)
FRAME    = 6.0;      // front frame rail width, sides
FRAME_TB = 4.0;      // front frame rails, top/bottom
BOTTOM_SLOT_W = 30;  // centered charge-port / button clearance

// ---------------- GoPro geometry ----------------
GP_MALE_T   = 2.85;  // male finger thickness
GP_MALE_GAP = 3.30;  // gap between the two male fingers
GP_FEM_T    = 3.00;  // female finger thickness
GP_FEM_SLOT = 3.15;  // female slot width (accepts 2.85 male)
GP_R        = 7.5;   // knuckle radius
GP_HOLE     = 5.3;   // M5 through-hole
GP_STEM     = 8.0;   // knuckle center to mounting face

// ---------------- saddle (measure your headset) ----------------
VISOR_TOP_DEPTH = 42.0;  // front-to-back across the visor top at center
SADDLE_W        = 50.0;
LIP_FRONT       = 10.0;
LIP_REAR        = 13.0;
LIP_T           = 3.0;

$fn = 64;

iw = BODY_W + 2*TOL;
ih = BODY_H + 2*TOL;
id = BODY_D + 2*TOL;
ow = iw + 2*WALL;
oh = ih + FLOOR;
od = id + WALL + BACK;

// ================================================== GoPro knuckles
// Finger built flat: 2D in XY (circle at origin, stem down to y=-stem-1),
// extruded along +Z by thickness t. Hole axis = Z.
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

// Oriented knuckle sets: hole/stack axis along X (= pitch hinge),
// stem pointing -Z, knuckle center at origin. Mount by translating so the
// stem foot (z = -GP_STEM) overlaps the carrying wall/plate.
module gopro_male_x() {
    rotate([0, 90, 0])                       // Z -> X
        for (s = [-1, 1])
            translate([0, 0, s*(GP_MALE_GAP + GP_MALE_T)/2 - GP_MALE_T/2])
                finger(GP_MALE_T, GP_STEM);
}

module gopro_female_x() {
    rotate([0, 90, 0])
        for (o = [-(GP_FEM_SLOT + GP_FEM_T), 0, GP_FEM_SLOT + GP_FEM_T])
            translate([0, 0, o - GP_FEM_T/2])
                finger(GP_FEM_T, GP_STEM);
}

// ================================================== cradle
module cradle() {
    difference() {
        cube([ow, od, oh]);

        // cavity, open top (front wall = WALL, back wall = BACK)
        translate([WALL, WALL, FLOOR]) cube([iw, id, ih + 1]);

        // front picture-frame window
        translate([WALL + FRAME, -1, FLOOR + FRAME_TB])
            cube([iw - 2*FRAME, WALL + 2, ih - 2*FRAME_TB]);

        // bottom slot: charge port / power button access, centered
        translate([ow/2 - BOTTOM_SLOT_W/2, WALL - 1, -1])
            cube([BOTTOM_SLOT_W, id + 2, FLOOR + 2]);

        // side thumb reliefs to push the camera out
        for (x = [-1, ow - WALL - 1])
            translate([x, WALL + id/2 - 8, FLOOR + ih*0.4])
                cube([WALL + 2, 16, ih]);

        // velcro strap slots (10.5 x 3 mm) through both side walls
        for (x = [-1, ow - WALL - 1])
            translate([x, WALL + id/2 - 1.5, FLOOR + ih - 12])
                cube([WALL + 2, 3, 10.5]);
    }
    // male knuckle behind the back wall, pitch axis horizontal (X),
    // stem rotated to point -Y into the wall (1.5 mm overlap)
    translate([ow/2, od + GP_STEM - 1.5, oh/2])
        rotate([-90, 0, 0]) gopro_male_x();
}

// ================================================== flat VHB base
module base() {
    pw = 40; pl = 52; pt = 3.2;
    difference() {
        hull() for (x = [-pw/2 + 5, pw/2 - 5], y = [-pl/2 + 5, pl/2 - 5])
            translate([x, y, 0]) cylinder(r = 5, h = pt);
        // 0.6 mm recess on the underside so VHB tape sits flush
        translate([-pw/2 + 3, -pl/2 + 3, -0.01]) cube([pw - 6, pl - 6, 0.6]);
    }
    // female knuckle standing on the plate, stem overlapping 1 mm
    translate([0, 0, pt + GP_STEM - 1]) gopro_female_x();
}

// ================================================== visor saddle
module saddle() {
    top_t = 3.2;
    depth = VISOR_TOP_DEPTH + 2*LIP_T;
    // bridge
    translate([-SADDLE_W/2, 0, 0]) cube([SADDLE_W, depth, top_t]);
    // front lip (hooks over the visor front edge)
    translate([-SADDLE_W/2, 0, -LIP_FRONT]) cube([SADDLE_W, LIP_T, LIP_FRONT]);
    // rear lip
    translate([-SADDLE_W/2, depth - LIP_T, -LIP_REAR])
        cube([SADDLE_W, LIP_T, LIP_REAR]);
    // female knuckle on top, centered
    translate([0, depth/2, top_t + GP_STEM - 1]) gopro_female_x();
}

// ================================================== output
if (PART == "cradle") cradle();
else if (PART == "base") base();
else if (PART == "saddle") saddle();
else {
    cradle();
    translate([110, 0, 0]) base();
    translate([210, 20, 16.2]) saddle();
}
