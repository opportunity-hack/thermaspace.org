#!/usr/bin/env python3
"""
FLIR One (USB-C, VID 0x09CB PID 0x1996) thermal camera driver.

Protocol (reverse-engineered, EEVblog / flirone-v4l2 lineage):
  Config 3 exposes 3 vendor interfaces:
    intf 0 'iAP Interface'            EP 0x81 IN / 0x02 OUT
    intf 1 'com.flir.rosebud.fileio'  EP 0x83 IN / 0x04 OUT
    intf 2 'com.flir.rosebud.frame'   EP 0x85 IN / 0x06 OUT
  Init: ctrl(0x01, 0x0b, wValue=0, wIndex=2)   stop frame
        ctrl(0x01, 0x0b, wValue=0, wIndex=1)   stop fileio
        ctrl(0x01, 0x0b, wValue=1, wIndex=1)   start fileio
        ctrl(0x01, 0x0b, wValue=1, wIndex=2)   start frame stream
  Then bulk-read EP 0x85. Frames:
    [0:4]   magic EF BE 00 00
    [8:12]  frame_size (payload after 28-byte header)
    [12:16] thermal_size   raw16 LE; rows are (width+4) px wide with a
            4-byte gap in the middle of each row (skip after x==width/2)
    [16:20] jpg_size       visible-camera JPEG
    [20:24] status_size    JSON telemetry (battery, FFC/shutter state, ...)
"""
import argparse
import json
import struct
import sys
import time

import numpy as np
import usb.core
import usb.util

try:
    import libusb_package
    _BACKEND = libusb_package.get_libusb1_backend()
except ImportError:
    _BACKEND = None  # fall back to pyusb default discovery

VID, PID = 0x09CB, 0x1996
MAGIC = b"\xEF\xBE\x00\x00"
HDR = 28

# Planck calibration constants (from FLIR One EXIF; per-unit factory values
# differ slightly — good enough for +/- a few degrees C)
PLANCK_R1 = 16528.178
PLANCK_R2 = 0.012258549
PLANCK_B = 1427.5
PLANCK_F = 1.0
PLANCK_O = -1307.0


def raw2celsius(raw, emissivity=0.95, t_reflected=20.0):
    """Convert raw16 sensor values (scalar or ndarray) to degrees Celsius."""
    raw = np.asarray(raw, dtype=np.float64) * 4.0  # gain correction factor
    raw_refl = PLANCK_R1 / (PLANCK_R2 * (np.exp(PLANCK_B / (t_reflected + 273.15)) - PLANCK_F)) - PLANCK_O
    raw_obj = (raw - (1.0 - emissivity) * raw_refl) / emissivity
    return PLANCK_B / np.log(PLANCK_R1 / (PLANCK_R2 * (raw_obj + PLANCK_O)) + PLANCK_F) - 273.15


class Frame:
    __slots__ = ("thermal", "visible_jpeg", "status", "seq")

    def __init__(self, thermal, visible_jpeg, status, seq):
        self.thermal = thermal            # np.uint16 (H, W) raw values, or None
        self.visible_jpeg = visible_jpeg  # bytes (JPEG), or None
        self.status = status              # dict, or None
        self.seq = seq

    @property
    def ffc(self):
        """True while the shutter (flat-field correction) is closed."""
        if not self.status:
            return False
        return "FFC" in str(self.status.get("shutterState", ""))


class FlirOne:
    # thermal geometries by payload size: {thermal_size: (width, height, kind)}
    # 'interleaved': rows (w+4) px = [2 pad][w/2][2 pad][w/2], +2 telemetry rows
    # 'vospi':       rows (w+2) words = [line ID][CRC][w px], +3 telemetry rows
    GEOMETRIES = {
        (160 + 4) * (120 + 2) * 2: (160, 120, "interleaved"),  # 40016 — G2/Pro
        (80 + 2) * (60 + 3) * 2: (80, 60, "vospi"),            # 10332 — G3 (verified)
    }

    def __init__(self, verbose=False):
        self.verbose = verbose
        self.dev = None
        self._buf = bytearray()
        self._seq = 0
        self._last_status = None

    def open(self):
        self.dev = usb.core.find(idVendor=VID, idProduct=PID, backend=_BACKEND)
        if self.dev is None:
            raise IOError("FLIR One not found — is it plugged in and powered on "
                          "(press the side button; the LED should blink)?")
        try:
            self.dev.set_configuration(3)
        except usb.core.USBError as e:
            self._log(f"set_configuration(3): {e} (continuing)")
        # interface 0 (iAP) is deliberately left alone: it belongs to Apple's
        # accessoryd on macOS and is not needed for streaming
        for intf in (1, 2):
            try:
                usb.util.claim_interface(self.dev, intf)
            except usb.core.USBError as e:
                self._log(f"claim interface {intf}: {e} (continuing)")

        ctrl = self.dev.ctrl_transfer
        ctrl(0x01, 0x0B, 0, 2, None, 100)          # stop frame
        try:
            ctrl(0x01, 0x0B, 0, 1, None, 100)      # stop fileio
        except usb.core.USBError:
            pass
        ctrl(0x01, 0x0B, 1, 1, None, 100)          # start fileio
        ctrl(0x01, 0x0B, 1, 2, b"\x00\x00", 200)   # start frame stream
        self._log("stream started")
        return self

    def close(self):
        if self.dev is not None:
            try:
                self.dev.ctrl_transfer(0x01, 0x0B, 0, 2, None, 100)
            except usb.core.USBError:
                pass
            usb.util.dispose_resources(self.dev)
            self.dev = None

    def __enter__(self):
        return self.open()

    def __exit__(self, *exc):
        self.close()

    def frames(self):
        """Generator yielding Frame objects forever."""
        while True:
            f = self.read_frame()
            if f is not None:
                yield f

    _MACOS_HELP = (
        "bulk read failed ({err}). On macOS this is expected: the FLIR One "
        "declares its streaming endpoints only in alt-setting 0 but streams in "
        "alt-setting 1, and the macOS kernel (IOUSBHostFamily) tears down the "
        "pipes on every SET_INTERFACE it sees, so the frame stream can never be "
        "read from userspace (libusb issue #729, never solved). Use a Linux "
        "host or the Quest 3 app in quest3-app/ (its MJPEG server streams to "
        "this Mac: open http://<quest-ip>:8080 or run mac_viewer.py).")

    def read_frame(self, timeout_ms=200):
        """Read bulk chunks until one complete frame is assembled."""
        consecutive_errors = 0
        while True:
            try:
                chunk = self.dev.read(0x85, 65536, timeout_ms)
                consecutive_errors = 0
            except usb.core.USBTimeoutError:
                self._drain()
                continue
            except usb.core.USBError as e:
                if e.errno in (60, 110):  # timeouts on mac/linux
                    continue
                consecutive_errors += 1
                if sys.platform == "darwin" or consecutive_errors > 10:
                    raise IOError(self._MACOS_HELP.format(err=e)) from e
                time.sleep(0.05)
                continue
            data = bytes(chunk)
            if not data:
                continue
            if data[:4] == MAGIC or len(self._buf) > 4 << 20:
                self._buf.clear()
            self._buf.extend(data)
            if len(self._buf) < HDR or bytes(self._buf[:4]) != MAGIC:
                self._buf.clear()
                continue
            frame_size, thermal_size, jpg_size, status_size = struct.unpack_from(
                "<IIII", self._buf, 8)
            if len(self._buf) < HDR + frame_size:
                continue  # incomplete, keep reading
            payload = bytes(self._buf[HDR:HDR + frame_size])
            self._buf.clear()
            self._seq += 1
            return self._parse(payload, thermal_size, jpg_size, status_size)

    def _parse(self, payload, thermal_size, jpg_size, status_size):
        thermal = None
        if thermal_size and thermal_size <= len(payload):
            geom = self.GEOMETRIES.get(thermal_size)
            if geom:
                w, h, kind = geom
                arr = np.frombuffer(payload, np.uint16, count=thermal_size // 2)
                if kind == "interleaved":
                    arr = arr.reshape(-1, w + 4)  # row: [2 pad][w/2][2 pad][w/2]
                    left = arr[:h, 2:2 + w // 2]
                    right = arr[:h, 4 + w // 2:4 + w]
                    thermal = np.hstack((left, right))
                else:                             # vospi: [id][crc][w pixels]
                    thermal = arr.reshape(-1, w + 2)[:h, 2:]
            else:
                self._log(f"unknown thermal_size={thermal_size}; raw kept")
                thermal = np.frombuffer(payload, np.uint16,
                                        count=thermal_size // 2)

        jpg = payload[thermal_size:thermal_size + jpg_size] if jpg_size else None

        status = None
        if status_size:
            raw = payload[thermal_size + jpg_size:
                          thermal_size + jpg_size + status_size]
            txt = raw.split(b"\x00")[0].decode("utf-8", "replace")
            try:
                status = json.loads(txt)
            except ValueError:
                status = {"_raw": txt}
        self._last_status = status or self._last_status
        return Frame(thermal, jpg, status, self._seq)

    def _drain(self):
        """Keep EP 0x83 (fileio) from stalling the device."""
        try:
            self.dev.read(0x83, 512, 5)
        except usb.core.USBError:
            pass

    def _log(self, msg):
        if self.verbose:
            print(f"[flirone] {msg}", file=sys.stderr)


# ---------------------------------------------------------------- CLI ----

def colorize(thermal, colormap_name="iron"):
    """Normalize raw16 → 8-bit and apply a colormap. Returns BGR image."""
    import cv2
    lo, hi = int(thermal.min()), int(thermal.max())
    span = max(hi - lo, 1)
    gray = ((thermal.astype(np.int32) - lo) * 255 // span).astype(np.uint8)
    cmaps = {"iron": cv2.COLORMAP_INFERNO, "rainbow": cv2.COLORMAP_JET,
             "gray": None, "hot": cv2.COLORMAP_HOT}
    cm = cmaps.get(colormap_name, cv2.COLORMAP_INFERNO)
    if cm is None:
        return cv2.cvtColor(gray, cv2.COLOR_GRAY2BGR)
    return cv2.applyColorMap(gray, cm)


def cmd_live(args):
    import cv2
    with FlirOne(verbose=args.verbose) as cam:
        print("Streaming — q quits, s saves a snapshot, c cycles colormap")
        cmaps = ["iron", "rainbow", "hot", "gray"]
        ci = cmaps.index(args.colormap) if args.colormap in cmaps else 0
        t0, n = time.time(), 0
        for frame in cam.frames():
            if frame.thermal is None:
                continue
            n += 1
            th = frame.thermal
            img = colorize(th, cmaps[ci])
            scale = args.scale
            img = cv2.resize(img, (th.shape[1] * scale, th.shape[0] * scale),
                             interpolation=cv2.INTER_CUBIC)
            # spot meter: center temp + min/max
            h, w = th.shape
            c = raw2celsius(th[h // 2 - 1:h // 2 + 1, w // 2 - 1:w // 2 + 1].mean())
            tmin, tmax = raw2celsius(th.min()), raw2celsius(th.max())
            fps = n / max(time.time() - t0, 1e-6)
            label = f"{c:.1f}C  (min {tmin:.1f} max {tmax:.1f})  {fps:.1f} fps"
            if frame.ffc:
                label += "  [FFC]"
            cv2.putText(img, label, (8, 24), cv2.FONT_HERSHEY_SIMPLEX, 0.6,
                        (255, 255, 255), 1, cv2.LINE_AA)
            cv2.drawMarker(img, (img.shape[1] // 2, img.shape[0] // 2),
                           (255, 255, 255), cv2.MARKER_CROSS, 16, 1)
            cv2.imshow("FLIR One - thermal", img)

            if frame.visible_jpeg and args.visible:
                vis = cv2.imdecode(np.frombuffer(frame.visible_jpeg, np.uint8),
                                   cv2.IMREAD_COLOR)
                if vis is not None:
                    cv2.imshow("FLIR One - visible", vis)

            k = cv2.waitKey(1) & 0xFF
            if k == ord("q"):
                break
            elif k == ord("c"):
                ci = (ci + 1) % len(cmaps)
            elif k == ord("s"):
                ts = time.strftime("%Y%m%d-%H%M%S")
                cv2.imwrite(f"thermal-{ts}.png", img)
                np.save(f"thermal-raw16-{ts}.npy", th)
                if frame.visible_jpeg:
                    open(f"visible-{ts}.jpg", "wb").write(frame.visible_jpeg)
                print(f"saved thermal-{ts}.png (+raw16 .npy, visible .jpg)")
        cv2.destroyAllWindows()


def cmd_capture(args):
    import cv2
    with FlirOne(verbose=args.verbose) as cam:
        got, skipped = 0, 0
        for frame in cam.frames():
            if frame.thermal is None or frame.ffc:
                skipped += 1
                if skipped > 400:
                    raise SystemExit("no usable frames received")
                continue
            got += 1
            ts = time.strftime("%Y%m%d-%H%M%S") + f"-{got:03d}"
            np.save(f"thermal-raw16-{ts}.npy", frame.thermal)
            cv2.imwrite(f"thermal-{ts}.png", colorize(frame.thermal))
            if frame.visible_jpeg:
                open(f"visible-{ts}.jpg", "wb").write(frame.visible_jpeg)
            if frame.status:
                json.dump(frame.status, open(f"status-{ts}.json", "w"), indent=2)
            t = raw2celsius(frame.thermal)
            print(f"frame {got}: thermal {frame.thermal.shape[1]}x{frame.thermal.shape[0]} "
                  f"temps {t.min():.1f}..{t.max():.1f}C  "
                  f"jpeg {len(frame.visible_jpeg or b'')}B")
            if got >= args.count:
                break


def cmd_info(args):
    with FlirOne(verbose=args.verbose) as cam:
        hdr_seen = 0
        for frame in cam.frames():
            print(f"--- frame {frame.seq} ---")
            if frame.thermal is not None:
                sh = frame.thermal.shape
                print(f" thermal: {sh}  raw {frame.thermal.min()}..{frame.thermal.max()}")
            print(f" visible jpeg: {len(frame.visible_jpeg or b'')} bytes")
            print(f" status: {json.dumps(frame.status, indent=1) if frame.status else None}")
            hdr_seen += 1
            if hdr_seen >= args.count:
                break


def main():
    p = argparse.ArgumentParser(description="FLIR One USB thermal camera tool")
    p.add_argument("-v", "--verbose", action="store_true")
    sub = p.add_subparsers(dest="cmd", required=True)

    lp = sub.add_parser("live", help="live view (OpenCV windows)")
    lp.add_argument("--scale", type=int, default=4, help="thermal upscale factor")
    lp.add_argument("--colormap", default="iron",
                    choices=["iron", "rainbow", "hot", "gray"])
    lp.add_argument("--no-visible", dest="visible", action="store_false",
                    help="hide the visible-camera window")
    lp.set_defaults(func=cmd_live)

    cp = sub.add_parser("capture", help="save frames to disk")
    cp.add_argument("-n", "--count", type=int, default=3)
    cp.set_defaults(func=cmd_capture)

    ip = sub.add_parser("info", help="print frame metadata / device status")
    ip.add_argument("-n", "--count", type=int, default=5)
    ip.set_defaults(func=cmd_info)

    args = p.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
