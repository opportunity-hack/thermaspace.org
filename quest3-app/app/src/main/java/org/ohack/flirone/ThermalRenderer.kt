package org.ohack.flirone

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Turns FlirFrames into presentable bitmaps:
 *  - EMA temporal denoising of raw16 (the 80x60 sensor is noisy)
 *  - percentile-based auto-gain (outlier-resistant, less flicker)
 *  - proper iron palette
 *  - filtered upscale to OUT_W x OUT_H
 *  - optional MSX-style edge overlay from the visible camera
 *  - FFC shutter-based radiometric offset calibration
 */
class ThermalRenderer {
    companion object {
        const val OUT_SCALE = 8            // 80x60 -> 640x480 output
        // Iron palette control points (pos 0..1 -> RGB)
        private val IRON = arrayOf(
            0.00f to intArrayOf(0, 0, 0),
            0.10f to intArrayOf(32, 0, 66),
            0.25f to intArrayOf(115, 15, 110),
            0.45f to intArrayOf(180, 45, 70),
            0.60f to intArrayOf(220, 85, 25),
            0.75f to intArrayOf(250, 140, 10),
            0.90f to intArrayOf(255, 210, 60),
            1.00f to intArrayOf(255, 255, 255),
        )
        val LUT = IntArray(256) { i ->
            val t = i / 255f
            var lo = IRON[0]; var hi = IRON[IRON.size - 1]
            for (j in 0 until IRON.size - 1)
                if (t >= IRON[j].first && t <= IRON[j + 1].first) { lo = IRON[j]; hi = IRON[j + 1]; break }
            val f = if (hi.first == lo.first) 0f else (t - lo.first) / (hi.first - lo.first)
            val r = (lo.second[0] + f * (hi.second[0] - lo.second[0])).toInt()
            val g = (lo.second[1] + f * (hi.second[1] - lo.second[1])).toInt()
            val b = (lo.second[2] + f * (hi.second[2] - lo.second[2])).toInt()
            0xFF shl 24 or (r shl 16) or (g shl 8) or b
        }
    }

    // --- tunables (adjustable via HTTP /config) ---
    @Volatile var emaAlpha = 0.35f         // temporal smoothing (1.0 = off)
    @Volatile var msxStrength = 0.55f      // 0 = off
    @Volatile var msxScale = 1.0f          // visible->thermal alignment
    @Volatile var msxDx = 0f               // px offset in output space
    @Volatile var msxDy = 0f
    @Volatile var emissivity = 0.95

    // --- state ---
    private var ema: FloatArray? = null
    private var w = 0
    private var h = 0
    private var small: Bitmap? = null
    private var smallPixels: IntArray? = null
    var output: Bitmap? = null; private set
    private val canvasPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val edgePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val textPaint = Paint().apply {
        color = 0xFFFFFFFF.toInt(); textSize = 30f; isAntiAlias = true
        setShadowLayer(4f, 1f, 1f, 0xFF000000.toInt())
    }

    // FFC radiometric calibration
    private var rawOffset = 0.0            // added to raw*4 before Planck
    private var ffcRawSum = 0.0
    private var ffcRawCount = 0
    private var ffcShutterC = Double.NaN
    var calibrated = false; private set

    // last-computed temps
    var centerC = 0.0; private set
    var minC = 0.0; private set
    var maxC = 0.0; private set

    fun rawToCelsius(raw: Double): Double {
        val r = raw * 4.0 + rawOffset
        val rawRefl = FlirFrame.PLANCK_R1 /
            (FlirFrame.PLANCK_R2 * (exp(FlirFrame.PLANCK_B / (20.0 + 273.15)) - FlirFrame.PLANCK_F)) -
            FlirFrame.PLANCK_O
        val rawObj = (r - (1 - emissivity) * rawRefl) / emissivity
        return FlirFrame.PLANCK_B /
            ln(FlirFrame.PLANCK_R1 / (FlirFrame.PLANCK_R2 * (rawObj + FlirFrame.PLANCK_O)) + FlirFrame.PLANCK_F) -
            273.15
    }

    private fun celsiusToRaw4(tC: Double): Double =
        FlirFrame.PLANCK_R1 /
            (FlirFrame.PLANCK_R2 * (exp(FlirFrame.PLANCK_B / (tC + 273.15)) - FlirFrame.PLANCK_F)) -
            FlirFrame.PLANCK_O

    /** Feed every frame (including FFC ones — they drive calibration). Returns true if a new image was produced. */
    fun process(f: FlirFrame): Boolean {
        updateCalibration(f)
        val th = f.thermal ?: return false
        if (f.isFFC) return false

        if (w != f.width || h != f.height) {
            w = f.width; h = f.height
            ema = FloatArray(w * h)
            th.forEachIndexed { i, v -> ema!![i] = v.toFloat() }
            smallPixels = IntArray(w * h)
            small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            output = Bitmap.createBitmap(w * OUT_SCALE, h * OUT_SCALE, Bitmap.Config.ARGB_8888)
        }
        val acc = ema!!
        val a = emaAlpha
        for (i in th.indices) acc[i] += a * (th[i] - acc[i])

        // percentile auto-gain: clip 1% tails to suppress dead pixels/outliers
        val sorted = acc.clone(); sorted.sort()
        val lo = sorted[(sorted.size * 0.01).toInt()]
        val hi = sorted[(sorted.size * 0.99).toInt().coerceAtMost(sorted.size - 1)]
        val span = (hi - lo).coerceAtLeast(1f)

        val px = smallPixels!!
        for (i in acc.indices) {
            val g = (((acc[i] - lo) / span) * 255f).toInt().coerceIn(0, 255)
            px[i] = LUT[g]
        }
        small!!.setPixels(px, 0, w, 0, 0, w, h)

        val out = output!!
        val c = Canvas(out)
        c.drawBitmap(small!!, null, Rect(0, 0, out.width, out.height), canvasPaint)

        if (msxStrength > 0f && f.visibleJpeg != null)
            overlayEdges(c, f.visibleJpeg, out.width, out.height)

        // temperatures (EMA-smoothed values for stability)
        val cIdx = (h / 2) * w + w / 2
        centerC = rawToCelsius(acc[cIdx].toDouble())
        minC = rawToCelsius(lo.toDouble())
        maxC = rawToCelsius(hi.toDouble())
        val cal = if (calibrated) "" else " ~"
        c.drawText("%.1f°C$cal  (%.1f – %.1f)".format(centerC, minC, maxC), 12f, 36f, textPaint)
        // center crosshair
        c.drawText("+", out.width / 2f - 9f, out.height / 2f + 11f, textPaint)
        return true
    }

    private fun overlayEdges(c: Canvas, jpeg: ByteArray, ow: Int, oh: Int) {
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }   // 640x480 -> 320x240
        val vis = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: return
        val vw = vis.width; val vh = vis.height
        val vp = IntArray(vw * vh)
        vis.getPixels(vp, 0, vw, 0, 0, vw, vh)
        vis.recycle()
        // luma + Sobel magnitude -> white-on-transparent edge map
        val luma = IntArray(vw * vh)
        for (i in vp.indices) {
            val p = vp[i]
            luma[i] = ((p shr 16 and 0xFF) * 77 + (p shr 8 and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
        }
        val edges = IntArray(vw * vh)
        val k = (255 * msxStrength).toInt()
        for (y in 1 until vh - 1) {
            val r0 = (y - 1) * vw; val r1 = y * vw; val r2 = (y + 1) * vw
            for (x in 1 until vw - 1) {
                val gx = luma[r0 + x + 1] + 2 * luma[r1 + x + 1] + luma[r2 + x + 1] -
                         luma[r0 + x - 1] - 2 * luma[r1 + x - 1] - luma[r2 + x - 1]
                val gy = luma[r2 + x - 1] + 2 * luma[r2 + x] + luma[r2 + x + 1] -
                         luma[r0 + x - 1] - 2 * luma[r0 + x] - luma[r0 + x + 1]
                var m = (sqrt((gx * gx + gy * gy).toFloat()) * 0.5f).toInt()
                if (m < 40) m = 0                       // noise floor
                m = (m * k / 255).coerceAtMost(200)
                edges[r1 + x] = (m shl 24) or 0xFFFFFF  // white with edge alpha
            }
        }
        val eb = Bitmap.createBitmap(edges, vw, vh, Bitmap.Config.ARGB_8888)
        val dw = ow * msxScale; val dh = oh * msxScale
        val left = (ow - dw) / 2f + msxDx; val top = (oh - dh) / 2f + msxDy
        c.drawBitmap(eb, null,
            Rect(left.toInt(), top.toInt(), (left + dw).toInt(), (top + dh).toInt()), edgePaint)
        eb.recycle()
    }

    private fun updateCalibration(f: FlirFrame) {
        val status = f.statusJson ?: return
        val shutterState = Regex("\"shutterState\"\\s*:\\s*\"([^\"]+)\"")
            .find(status)?.groupValues?.get(1) ?: return
        val inFFC = shutterState.startsWith("FFC")
        if (inFFC) {
            // sensor is looking at the shutter, whose temperature is reported
            try {
                val t = JSONObject(status).optDouble("shutterTemperature", Double.NaN)
                if (!t.isNaN()) ffcShutterC = t - 273.15
            } catch (_: Exception) {}
            f.thermal?.let { th ->
                var s = 0.0; for (v in th) s += v
                ffcRawSum += s / th.size; ffcRawCount++
            }
        } else if (ffcRawCount > 0 && !ffcShutterC.isNaN()) {
            val observedRaw4 = (ffcRawSum / ffcRawCount) * 4.0
            val expectedRaw4 = celsiusToRaw4(ffcShutterC)
            val off = expectedRaw4 - observedRaw4
            if (off > -4000 && off < 4000) {           // plausibility guard
                rawOffset = off
                calibrated = true
            }
            ffcRawSum = 0.0; ffcRawCount = 0
        }
    }

    fun applyConfig(query: String) {
        Regex("[?&](\\w+)=([-\\d.]+)").findAll(query).forEach { m ->
            val v = m.groupValues[2].toFloatOrNull() ?: return@forEach
            when (m.groupValues[1]) {
                "ema" -> emaAlpha = v.coerceIn(0.05f, 1f)
                "msx" -> msxStrength = v.coerceIn(0f, 1f)
                "mscale" -> msxScale = v.coerceIn(0.5f, 2f)
                "mdx" -> msxDx = v
                "mdy" -> msxDy = v
                "emis" -> emissivity = v.toDouble().coerceIn(0.1, 1.0)
            }
        }
    }

    fun configJson(): String =
        """{"ema":$emaAlpha,"msx":$msxStrength,"mscale":$msxScale,"mdx":$msxDx,"mdy":$msxDy,"emis":$emissivity,"calibrated":$calibrated,"rawOffset":$rawOffset}"""
}
