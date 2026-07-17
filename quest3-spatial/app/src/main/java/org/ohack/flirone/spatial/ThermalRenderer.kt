package org.ohack.flirone.spatial

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

        private fun buildLut(points: Array<Pair<Float, IntArray>>) = IntArray(256) { i ->
            val t = i / 255f
            var lo = points[0]; var hi = points[points.size - 1]
            for (j in 0 until points.size - 1)
                if (t >= points[j].first && t <= points[j + 1].first) { lo = points[j]; hi = points[j + 1]; break }
            val f = if (hi.first == lo.first) 0f else (t - lo.first) / (hi.first - lo.first)
            val r = (lo.second[0] + f * (hi.second[0] - lo.second[0])).toInt()
            val g = (lo.second[1] + f * (hi.second[1] - lo.second[1])).toInt()
            val b = (lo.second[2] + f * (hi.second[2] - lo.second[2])).toInt()
            0xFF shl 24 or (r shl 16) or (g shl 8) or b
        }

        private val IRON = buildLut(arrayOf(
            0.00f to intArrayOf(0, 0, 0), 0.10f to intArrayOf(32, 0, 66),
            0.25f to intArrayOf(115, 15, 110), 0.45f to intArrayOf(180, 45, 70),
            0.60f to intArrayOf(220, 85, 25), 0.75f to intArrayOf(250, 140, 10),
            0.90f to intArrayOf(255, 210, 60), 1.00f to intArrayOf(255, 255, 255)))
        private val WHITE_HOT = buildLut(arrayOf(
            0.00f to intArrayOf(0, 0, 0), 1.00f to intArrayOf(255, 255, 255)))
        private val BLACK_HOT = buildLut(arrayOf(
            0.00f to intArrayOf(255, 255, 255), 1.00f to intArrayOf(0, 0, 0)))
        private val RAINBOW = buildLut(arrayOf(
            0.00f to intArrayOf(10, 10, 120), 0.25f to intArrayOf(0, 160, 220),
            0.50f to intArrayOf(0, 190, 80), 0.70f to intArrayOf(250, 220, 0),
            0.88f to intArrayOf(250, 100, 0), 1.00f to intArrayOf(255, 0, 40)))

        val PALETTES = arrayOf("iron" to IRON, "white-hot" to WHITE_HOT,
                               "black-hot" to BLACK_HOT, "rainbow" to RAINBOW)

        // dimmed gray used for the non-highlighted region in isotherm modes
        private val ISO_GRAY = IntArray(256) { i ->
            val v = (i * 100) / 255 + 30
            0xFF shl 24 or (v shl 16) or (v shl 8) or v
        }
    }

    enum class IsoMode { OFF, HOT, COLD }

    // --- tunables (adjustable via HTTP /config and panel buttons) ---
    @Volatile var emaAlpha = 0.35f         // temporal smoothing (1.0 = off)
    @Volatile var msxStrength = 0.55f      // 0 = off
    @Volatile var msxScale = 1.0f          // visible->thermal alignment
    @Volatile var msxDx = 0f               // px offset in output space
    @Volatile var msxDy = 0f
    @Volatile var emissivity = 0.95
    @Volatile var paletteIndex = 0
    @Volatile var isoMode = IsoMode.OFF    // highlight hottest/coldest 15% only
    @Volatile var isoFraction = 0.15f
    // physical mounting orientation: rotate sensor data before display
    // (0 = camera in normal landscape; 90/270 for portrait dangle off the port)
    @Volatile var rotationDeg = 0
    // span lock: when set, colors map to this fixed °C range in every frame
    // (essential for comparing snapshots across a room heatmap)
    @Volatile var spanLockC: Pair<Double, Double>? = null

    val paletteName: String get() = PALETTES[paletteIndex].first
    val currentLut: IntArray get() = PALETTES[paletteIndex].second

    // display unit (math stays Celsius internally)
    @Volatile var useFahrenheit = false
    val unitSuffix: String get() = if (useFahrenheit) "°F" else "°C"
    fun disp(c: Double): Double = if (useFahrenheit) c * 9.0 / 5.0 + 32.0 else c

    fun cyclePalette(): String {
        paletteIndex = (paletteIndex + 1) % PALETTES.size
        return paletteName
    }

    /** Locks the color scale to the current frame's range; call again to unlock. */
    fun toggleSpanLock(): String {
        return if (spanLockC == null) {
            spanLockC = Pair(minC, maxC)
            "span locked %.1f–%.1f$unitSuffix".format(disp(minC), disp(maxC))
        } else {
            spanLockC = null
            "span auto"
        }
    }

    /** Nudge a locked-span edge by [deltaC] °C (no-op if unlocked). lo<hi kept. */
    fun nudgeSpan(low: Boolean, deltaC: Double): String {
        val cur = spanLockC ?: return "lock the span first (📏)"
        var (lo, hi) = cur
        if (low) lo = (lo + deltaC).coerceAtMost(hi - 0.5) else hi = (hi + deltaC).coerceAtLeast(lo + 0.5)
        spanLockC = lo to hi
        return "span %.1f–%.1f%s".format(disp(lo), disp(hi), unitSuffix)
    }

    fun cycleIso(): String {
        isoMode = IsoMode.values()[(isoMode.ordinal + 1) % 3]
        return when (isoMode) {
            IsoMode.OFF -> "isotherm off"
            IsoMode.HOT -> "isotherm: hottest ${(isoFraction * 100).toInt()}%"
            IsoMode.COLD -> "isotherm: coldest ${(isoFraction * 100).toInt()}%"
        }
    }

    fun cycleRotation(): String {
        rotationDeg = (rotationDeg + 90) % 360
        return "rotation ${rotationDeg}°"
    }

    val isPortrait: Boolean get() = rotationDeg == 90 || rotationDeg == 270

    // reused per-frame buffers: this pipeline runs ~9x/s and per-frame array
    // churn (~1 MB/frame) causes GC pauses that show up as VR frame hitches
    private var rotBuf: IntArray? = null
    private var sortBuf: FloatArray? = null

    /** Rotate raw sensor data; returns (width, height, data). dst is reused. */
    private fun rotate(src: IntArray, sw: Int, sh: Int, deg: Int): Triple<Int, Int, IntArray> {
        if (deg == 0) return Triple(sw, sh, src)
        val dst = rotBuf?.takeIf { it.size == src.size }
            ?: IntArray(src.size).also { rotBuf = it }
        when (deg) {
            90 -> {   // clockwise
                for (y in 0 until sw) for (x in 0 until sh)
                    dst[y * sh + x] = src[(sh - 1 - x) * sw + y]
                return Triple(sh, sw, dst)
            }
            180 -> {
                for (i in src.indices) dst[i] = src[src.size - 1 - i]
                return Triple(sw, sh, dst)
            }
            else -> { // 270
                for (y in 0 until sw) for (x in 0 until sh)
                    dst[y * sh + x] = src[x * sw + (sw - 1 - y)]
                return Triple(sh, sw, dst)
            }
        }
    }

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

    private val frameLock = Any()

    // latest MSX edge magnitudes (visible-cam Sobel), for capture sidecars
    private var edgeMag: ByteArray? = null
    private var edgeW = 0
    private var edgeH = 0
    // latest visible-camera JPEG (context photo for captures)
    @Volatile private var lastVisibleJpeg: ByteArray? = null

    /** Latest visible-camera frame, rotated to match the display, as JPEG bytes. */
    fun visibleSnapshot(): ByteArray? {
        val jpeg = lastVisibleJpeg ?: return null
        return try {
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            var vis = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: return null
            if (rotationDeg != 0) {
                val m = android.graphics.Matrix().apply { postRotate(rotationDeg.toFloat()) }
                val r = Bitmap.createBitmap(vis, 0, 0, vis.width, vis.height, m, true)
                vis.recycle(); vis = r
            }
            val bos = java.io.ByteArrayOutputStream()
            vis.compress(Bitmap.CompressFormat.JPEG, 80, bos)
            vis.recycle()
            bos.toByteArray()
        } catch (e: Exception) { null }
    }

    /** Thread-safe copy of the latest rendered image (capture path). */
    fun snapshot(): Bitmap? = synchronized(frameLock) {
        output?.copy(Bitmap.Config.ARGB_8888, false)
    }

    /** Thread-safe copy of the latest edge map (w, h, magnitudes 0..255). */
    fun edgeSnapshot(): Triple<Int, Int, ByteArray>? = synchronized(frameLock) {
        edgeMag?.let { Triple(edgeW, edgeH, it.clone()) }
    }

    /** Thread-safe copy of the latest radiometric data (w, h, raw values). */
    fun rawSnapshot(): Triple<Int, Int, FloatArray>? = synchronized(frameLock) {
        ema?.let { Triple(w, h, it.clone()) }
    }

    /** Feed every frame (including FFC ones — they drive calibration). Returns true if a new image was produced. */
    fun process(f: FlirFrame): Boolean = synchronized(frameLock) { processLocked(f) }

    private fun processLocked(f: FlirFrame): Boolean {
        updateCalibration(f)
        val raw = f.thermal ?: return false
        if (f.visibleJpeg != null) lastVisibleJpeg = f.visibleJpeg
        if (f.isFFC) return false

        val (rw, rh, th) = rotate(raw, f.width, f.height, rotationDeg)
        if (w != rw || h != rh) {
            w = rw; h = rh
            ema = FloatArray(w * h)
            th.forEachIndexed { i, v -> ema!![i] = v.toFloat() }
            smallPixels = IntArray(w * h)
            small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            output = Bitmap.createBitmap(w * OUT_SCALE, h * OUT_SCALE, Bitmap.Config.ARGB_8888)
        }
        val acc = ema!!
        val a = emaAlpha
        for (i in th.indices) acc[i] += a * (th[i] - acc[i])

        // color scale bounds: locked °C span, or percentile auto-gain
        // (1% tail clip suppresses dead pixels/outliers)
        val sorted = sortBuf?.takeIf { it.size == acc.size }
            ?: FloatArray(acc.size).also { sortBuf = it }
        acc.copyInto(sorted)
        sorted.sort()
        val autoLo = sorted[(sorted.size * 0.01).toInt()]
        val autoHi = sorted[(sorted.size * 0.99).toInt().coerceAtMost(sorted.size - 1)]
        val lock = spanLockC
        val lo = if (lock != null) celsiusToRaw(lock.first) else autoLo
        val hi = if (lock != null) celsiusToRaw(lock.second) else autoHi
        val span = (hi - lo).coerceAtLeast(1f)

        // isotherm threshold from the scene's own distribution
        val isoRaw = when (isoMode) {
            IsoMode.HOT -> sorted[((sorted.size - 1) * (1f - isoFraction)).toInt()]
            IsoMode.COLD -> sorted[((sorted.size - 1) * isoFraction).toInt()]
            IsoMode.OFF -> 0f
        }

        val lut = PALETTES[paletteIndex].second
        val px = smallPixels!!
        for (i in acc.indices) {
            val v = acc[i]
            val g = (((v - lo) / span) * 255f).toInt().coerceIn(0, 255)
            px[i] = when (isoMode) {
                IsoMode.OFF -> lut[g]
                IsoMode.HOT -> if (v >= isoRaw) lut[g] else ISO_GRAY[g]
                IsoMode.COLD -> if (v <= isoRaw) lut[g] else ISO_GRAY[g]
            }
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
        minC = rawToCelsius(autoLo.toDouble())
        maxC = rawToCelsius(autoHi.toDouble())
        val cal = if (calibrated) "" else " ~"
        c.drawText("%.1f$unitSuffix$cal  (%.1f – %.1f)".format(
            disp(centerC), disp(minC), disp(maxC)), 12f, 36f, textPaint)
        // center crosshair
        c.drawText("+", out.width / 2f - 9f, out.height / 2f + 11f, textPaint)
        drawScaleBar(c, out, lut, lo, hi)
        return true
    }

    /** °C -> raw acc units (inverse of rawToCelsius incl. calibration offset;
     *  emissivity reflection term is negligible for scale bounds). */
    private fun celsiusToRaw(tC: Double): Float =
        ((celsiusToRaw4(tC) - rawOffset) / 4.0).toFloat()

    private val barPaint = Paint()
    private val barText = Paint().apply {
        color = 0xFFFFFFFF.toInt(); textSize = 22f; isAntiAlias = true
        setShadowLayer(3f, 1f, 1f, 0xFF000000.toInt())
    }

    private fun drawScaleBar(c: Canvas, out: Bitmap, lut: IntArray, lo: Float, hi: Float) {
        val barW = 18f
        val x = out.width - barW - 10f
        val top = 56f
        val bottom = out.height - 20f
        val steps = 64
        val stepH = (bottom - top) / steps
        for (s in 0 until steps) {
            barPaint.color = lut[255 - (s * 255 / (steps - 1))]
            c.drawRect(x, top + s * stepH, x + barW, top + (s + 1) * stepH + 1, barPaint)
        }
        val locked = if (spanLockC != null) " 🔒" else ""
        c.drawText("%.0f°$locked".format(disp(rawToCelsius(hi.toDouble()))), x - 42f, top + 8f, barText)
        c.drawText("%.0f°".format(disp(rawToCelsius(lo.toDouble()))), x - 42f, bottom, barText)
    }

    // edge-pipeline reuse (visible frames are a constant size per session)
    private var visReuse: Bitmap? = null
    private var vpRaw: IntArray? = null
    private var vpRot: IntArray? = null
    private var lumaBuf: IntArray? = null
    private var edgesBuf: IntArray? = null
    private var magBuf: ByteArray? = null
    private var edgeBitmap: Bitmap? = null

    private fun overlayEdges(c: Canvas, jpeg: ByteArray, ow: Int, oh: Int) {
        // decode into the previous frame's bitmap when possible
        val vis = (try {
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size,
                BitmapFactory.Options().apply {
                    inSampleSize = 4; inMutable = true
                    visReuse?.let { inBitmap = it }
                })
        } catch (e: IllegalArgumentException) { null })   // inBitmap mismatch
            ?: BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size,
                BitmapFactory.Options().apply { inSampleSize = 4; inMutable = true })
            ?: return
        visReuse = vis
        val rw = vis.width; val rh = vis.height
        val rawPx = vpRaw?.takeIf { it.size == rw * rh }
            ?: IntArray(rw * rh).also { vpRaw = it }
        vis.getPixels(rawPx, 0, rw, 0, 0, rw, rh)
        // rotate by re-indexing pixels (same formulas as the thermal rotate) —
        // a Matrix-rotated Bitmap per frame was ~400 KB of garbage each time
        val vw: Int; val vh: Int; val vp: IntArray
        if (rotationDeg == 0) { vw = rw; vh = rh; vp = rawPx }
        else {
            val dst = vpRot?.takeIf { it.size == rw * rh }
                ?: IntArray(rw * rh).also { vpRot = it }
            when (rotationDeg) {
                90 -> { for (y in 0 until rw) for (x in 0 until rh)
                            dst[y * rh + x] = rawPx[(rh - 1 - x) * rw + y]
                        vw = rh; vh = rw }
                180 -> { for (i in rawPx.indices) dst[i] = rawPx[rawPx.size - 1 - i]
                         vw = rw; vh = rh }
                else -> { for (y in 0 until rw) for (x in 0 until rh)
                              dst[y * rh + x] = rawPx[x * rw + (rw - 1 - y)]
                          vw = rh; vh = rw }
            }
            vp = dst
        }
        // luma + Sobel magnitude -> white-on-transparent edge map
        val luma = lumaBuf?.takeIf { it.size == vw * vh }
            ?: IntArray(vw * vh).also { lumaBuf = it }
        for (i in 0 until vw * vh) {
            val p = vp[i]
            luma[i] = ((p shr 16 and 0xFF) * 77 + (p shr 8 and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
        }
        // the Sobel loop never writes border rows/cols. A rotation toggle
        // swaps vw/vh at the same product, so size-matched reuse would leave
        // last-orientation interior values as permanent border garbage —
        // zero the buffers whenever the dimensions change.
        val edges = edgesBuf?.takeIf { it.size == vw * vh }
            ?: IntArray(vw * vh).also { edgesBuf = it }
        if (vw != edgeW || vh != edgeH) java.util.Arrays.fill(edges, 0)
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
        // keep the magnitudes for capture sidecars (edgeSnapshot clones under
        // frameLock, so reusing this buffer across frames is safe)
        val mag = magBuf?.takeIf { it.size == vw * vh }
            ?: ByteArray(vw * vh).also { magBuf = it }
        for (i in 0 until vw * vh) mag[i] = (edges[i] ushr 24).toByte()
        edgeMag = mag; edgeW = vw; edgeH = vh

        val eb = edgeBitmap?.takeIf { it.width == vw && it.height == vh }
            ?: Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888).also { edgeBitmap = it }
        eb.setPixels(edges, 0, vw, 0, 0, vw, vh)
        val dw = ow * msxScale; val dh = oh * msxScale
        val left = (ow - dw) / 2f + msxDx; val top = (oh - dh) / 2f + msxDy
        c.drawBitmap(eb, null,
            Rect(left.toInt(), top.toInt(), (left + dw).toInt(), (top + dh).toInt()), edgePaint)
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
        // span: "span=15,35" locks; "span=auto" unlocks
        Regex("[?&]span=([\\d.]+),([\\d.]+)").find(query)?.let { m ->
            spanLockC = Pair(m.groupValues[1].toDouble(), m.groupValues[2].toDouble())
        }
        if (query.contains("span=auto")) spanLockC = null
        if (query.contains("unit=f")) useFahrenheit = true
        if (query.contains("unit=c")) useFahrenheit = false
        Regex("[?&](\\w+)=([-\\d.]+)").findAll(query).forEach { m ->
            val v = m.groupValues[2].toFloatOrNull() ?: return@forEach
            when (m.groupValues[1]) {
                "ema" -> emaAlpha = v.coerceIn(0.05f, 1f)
                "msx" -> msxStrength = v.coerceIn(0f, 1f)
                "mscale" -> msxScale = v.coerceIn(0.5f, 2f)
                "mdx" -> msxDx = v
                "mdy" -> msxDy = v
                "emis" -> emissivity = v.toDouble().coerceIn(0.1, 1.0)
                "pal" -> paletteIndex = v.toInt().coerceIn(0, PALETTES.size - 1)
                "iso" -> isoMode = IsoMode.values()[v.toInt().coerceIn(0, 2)]
                "isofrac" -> isoFraction = v.coerceIn(0.02f, 0.5f)
                // +360 keeps it in 0..270: Kotlin % preserves sign and a
                // negative rot desyncs the thermal vs MSX/visible rotation
                "rot" -> rotationDeg = (((v.toInt() / 90 * 90) % 360) + 360) % 360
            }
        }
    }

    fun configJson(): String =
        """{"ema":$emaAlpha,"msx":$msxStrength,"mscale":$msxScale,"mdx":$msxDx,"mdy":$msxDy,""" +
        """"emis":$emissivity,"pal":"$paletteName","iso":"$isoMode","rot":$rotationDeg,""" +
        """"unit":"${if (useFahrenheit) "F" else "C"}",""" +
        """"span":"${spanLockC?.let { "%.1f,%.1f".format(it.first, it.second) } ?: "auto"}",""" +
        """"calibrated":$calibrated,"rawOffset":$rawOffset}"""
}
