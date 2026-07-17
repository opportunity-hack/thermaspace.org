package org.ohack.flirone.spatial

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.util.Log
import kotlin.math.exp
import kotlin.math.ln

/**
 * FLIR One (VID 0x09CB PID 0x1996) driver, EEVblog/flirone-v4l2 protocol.
 *
 * Init = four SET_INTERFACE control transfers (the device abuses alt settings
 * as start/stop commands; works on Android because Linux usbfs passes raw
 * control transfers through), then bulk-read frames from EP 0x85:
 *   28-byte header: magic EFBE0000 | ? | frameSize | thermalSize | jpgSize | statusSize | ?
 *   payload: raw16 thermal, visible-camera JPEG, status JSON.
 * Thermal rows are (w+4) px: [2 pad][w/2 left][2 pad][w/2 right]; last 2 rows telemetry.
 */
class FlirFrame(
    val thermal: IntArray?,     // raw16 values, width*height, row-major
    val width: Int,
    val height: Int,
    val visibleJpeg: ByteArray?,
    val statusJson: String?,
    val thermalRaw: ByteArray? = null,   // undecoded thermal payload (for analysis)
) {
    /** True only while the shutter is closed for flat-field calibration
     *  (status always contains an "ffcState" key — do not match on that). */
    val isFFC: Boolean get() {
        val m = Regex("\"shutterState\"\\s*:\\s*\"([^\"]+)\"").find(statusJson ?: "")
        return m?.groupValues?.get(1)?.startsWith("FFC") == true
    }

    companion object {
        const val PLANCK_R1 = 16528.178
        const val PLANCK_R2 = 0.012258549
        const val PLANCK_B = 1427.5
        const val PLANCK_F = 1.0
        const val PLANCK_O = -1307.0

        fun rawToCelsius(raw: Int, emissivity: Double = 0.95, tRefl: Double = 20.0): Double {
            val r = raw * 4.0
            val rawRefl = PLANCK_R1 / (PLANCK_R2 * (exp(PLANCK_B / (tRefl + 273.15)) - PLANCK_F)) - PLANCK_O
            val rawObj = (r - (1 - emissivity) * rawRefl) / emissivity
            return PLANCK_B / ln(PLANCK_R1 / (PLANCK_R2 * (rawObj + PLANCK_O)) + PLANCK_F) - 273.15
        }
    }
}

class FlirOne(
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val onFrame: (FlirFrame) -> Unit,
    private val onError: (String) -> Unit,
    private val onLog: (String) -> Unit = {},
) {
    companion object {
        const val VID = 0x09CB
        const val PID = 0x1996
        private const val TAG = "FlirOne"
        private val MAGIC = byteArrayOf(0xEF.toByte(), 0xBE.toByte(), 0, 0)
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    private var epFrameIn: UsbEndpoint? = null
    private var epFileioIn: UsbEndpoint? = null
    private var epIapIn: UsbEndpoint? = null

    private fun log(msg: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, msg)
        onLog(msg)
    }

    fun start() {
        try {
            val iap = findInterface(0)
            val fileio = findInterface(1)
            val frame = findInterface(2)
            log("interfaces: iap=${iap != null} fileio=${fileio != null} frame=${frame != null}")

            iap?.let {
                val ok = connection.claimInterface(it, true)
                log("claim iap(0): $ok")
                if (ok) epIapIn = inEndpoint(it)
            }
            if (fileio == null || frame == null) { onError("fileio/frame interface missing"); return }
            val c1 = connection.claimInterface(fileio, true)
            val c2 = connection.claimInterface(frame, true)
            log("claim fileio(1): $c1  frame(2): $c2  (fileio eps=${fileio.endpointCount}, frame eps=${frame.endpointCount})")
            if (!c1 || !c2) { onError("failed to claim USB interfaces"); return }
            epFileioIn = inEndpoint(fileio)
            epFrameIn = inEndpoint(frame)
            log("epFrameIn=0x%02x epFileioIn=0x%02x".format(
                epFrameIn?.address ?: -1, epFileioIn?.address ?: -1))
            if (epFrameIn == null) { onError("frame IN endpoint not found"); return }

            Thread.sleep(500)  // let the camera settle after enumeration
            startSequence()

            running = true
            thread = Thread(::readLoop, "flir-usb").also { it.start() }
        } catch (e: Exception) {
            onError("init failed: $e")
        }
    }

    /** stop frame, stop fileio, start fileio, start frame — exact flirone.c order */
    private fun startSequence() {
        ctrl("stop frame  ", 0, 2, 0)
        ctrl("stop fileio ", 0, 1, 0)
        ctrl("start fileio", 1, 1, 0)
        ctrl("start frame ", 1, 2, 2)
    }

    private val stopped = java.util.concurrent.atomic.AtomicBoolean(false)

    fun stop() {
        // once-only: concurrent callers (usbReceiver + cameraWatch + onError)
        // must not close the connection twice or ctrl() a closed device
        if (!stopped.compareAndSet(false, true)) return
        running = false
        // onError fires ON the reader thread — a self-join would stall it 1 s
        if (Thread.currentThread() !== thread) thread?.join(1000)
        try { ctrl("stop frame", 0, 2, 0) } catch (_: Exception) {}
        try { connection.close() } catch (_: Exception) {}
    }

    /** Prefer the alt-0 descriptor entry: it is the one that lists the endpoints. */
    private fun findInterface(number: Int): UsbInterface? {
        var best: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.id != number) continue
            if (intf.endpointCount > 0) return intf
            if (best == null) best = intf
        }
        return best
    }

    private fun inEndpoint(intf: UsbInterface): UsbEndpoint? {
        for (i in 0 until intf.endpointCount)
            if (intf.getEndpoint(i).direction == UsbConstants.USB_DIR_IN)
                return intf.getEndpoint(i)
        return null
    }

    private fun ctrl(tag: String, value: Int, index: Int, length: Int): Int {
        val buf = if (length > 0) ByteArray(length) else null
        val r = connection.controlTransfer(0x01, 0x0B, value, index, buf, length, 200)
        log("$tag SET_INTERFACE alt=$value intf=$index len=$length -> $r")
        return r
    }

    private fun readLoop() {
        val chunk = ByteArray(16384)
        val side = ByteArray(1024)
        // growable accumulation buffer + length cursor: `buf + data` per 16 KB
        // chunk was ~1-2 MB of copies/garbage per frame on the reader thread
        var buf = ByteArray(256 * 1024)
        var bufLen = 0
        var errorStreak = 0
        var restarts = 0
        var reads = 0
        var frames = 0
        while (running) {
            try {
                // flirone.c: don't change timeout (100 ms), poll side endpoints every loop
                val n = connection.bulkTransfer(epFrameIn, chunk, chunk.size, 100)
                epIapIn?.let { connection.bulkTransfer(it, side, side.size, 10) }
                epFileioIn?.let { connection.bulkTransfer(it, side, side.size, 10) }

                if (n <= 0) {
                    errorStreak++
                    if (errorStreak == 60 || errorStreak == 120) {  // ~6 s of silence
                        restarts++
                        if (restarts > 3) { onError("USB stream stalled (no data after ${restarts - 1} restarts)"); break }
                        log("no data ($errorStreak misses) — re-issuing start sequence (restart $restarts)")
                        startSequence()
                    }
                    continue
                }
                errorStreak = 0
                if (reads < 5) {
                    reads++
                    log("bulk read #$reads: $n bytes, head=%02x%02x%02x%02x".format(
                        chunk[0], chunk[1], chunk[2], chunk[3]))
                }
                if (startsWithMagic(chunk, n) || bufLen > (4 shl 20)) bufLen = 0
                if (bufLen + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, bufLen + n))
                System.arraycopy(chunk, 0, buf, bufLen, n)
                bufLen += n
                if (!startsWithMagic(buf, bufLen) || bufLen < 28) continue

                val frameSize = le32(buf, 8)
                // corrupt header (negative/absurd size) must not wedge or crash
                if (frameSize < 0 || frameSize > (4 shl 20)) { bufLen = 0; continue }
                if (bufLen < 28 + frameSize) continue
                if (frames < 3) {
                    frames++
                    log("frame #$frames: frameSize=$frameSize thermal=${le32(buf, 12)} jpg=${le32(buf, 16)} status=${le32(buf, 20)}")
                }
                parse(buf, bufLen, frameSize)?.let(onFrame)
                bufLen = 0
            } catch (e: Exception) {
                // malformed USB data must never kill the reader thread
                log("readLoop: $e")
                bufLen = 0
            }
        }
        log("read loop exited")
    }

    private fun startsWithMagic(b: ByteArray, len: Int) =
        len >= 4 && b[0] == MAGIC[0] && b[1] == MAGIC[1] && b[2] == MAGIC[2] && b[3] == MAGIC[3]

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
        ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun parse(buf: ByteArray, len: Int, frameSize: Int): FlirFrame? {
        val thermalSize = le32(buf, 12)
        val jpgSize = le32(buf, 16)
        val statusSize = le32(buf, 20)
        // any negative section size would pass a naive sum check and then
        // throw (or worse) while slicing — reject the frame outright
        if (thermalSize < 0 || jpgSize < 0 || statusSize < 0) return null
        if (28L + thermalSize + jpgSize + statusSize > len) return null

        var thermal: IntArray? = null
        var w = 0
        var h = 0
        when (thermalSize) {
            // G2/Pro (Lepton 3): stride 164, row = [2 pad][80 L][2 pad][80 R], +2 telemetry rows
            (160 + 4) * (120 + 2) * 2 -> {
                w = 160; h = 120
                thermal = IntArray(w * h)
                for (y in 0 until h) {
                    val rowBase = 28 + 2 * (y * 164)
                    for (x in 0 until w) {
                        val pad = if (x < 80) 2 else 4
                        val o = rowBase + 2 * (x + pad)
                        thermal[y * w + x] = (buf[o].toInt() and 0xFF) or ((buf[o + 1].toInt() and 0xFF) shl 8)
                    }
                }
            }
            // Pro LT/G3-class (80×60 Lepton, VoSPI): 63 lines × 82 words = [line ID][CRC][80 px];
            // lines 0-59 video, 60-62 telemetry (verified on real hardware)
            82 * 63 * 2 -> {
                w = 80; h = 60
                thermal = IntArray(w * h)
                for (y in 0 until h) {
                    val rowBase = 28 + 2 * (y * 82 + 2)
                    for (x in 0 until w) {
                        val o = rowBase + 2 * x
                        thermal[y * w + x] = (buf[o].toInt() and 0xFF) or ((buf[o + 1].toInt() and 0xFF) shl 8)
                    }
                }
            }
        }
        val jpg = if (jpgSize > 0)
            buf.copyOfRange(28 + thermalSize, 28 + thermalSize + jpgSize) else null
        val status = if (statusSize > 0)
            String(buf, 28 + thermalSize + jpgSize, statusSize).trimEnd(' ') else null
        val raw = if (thermalSize > 0) buf.copyOfRange(28, 28 + thermalSize) else null
        return FlirFrame(thermal, w, h, jpg, status, raw)
    }
}
