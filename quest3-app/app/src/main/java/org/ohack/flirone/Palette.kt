package org.ohack.flirone

/** "Iron" thermal palette: 256 ARGB entries (black-purple-red-orange-yellow-white). */
object Palette {
    val iron: IntArray = IntArray(256) { i ->
        val t = i / 255.0
        val r = clamp(255 * Math.sqrt(t) * 1.2)
        val g = clamp(255 * t * t * 1.1 - 20)
        val b = when {
            t < 0.5 -> clamp(255 * (0.5 + t) * t * 2.2)
            else -> clamp(255 * (t - 0.45) * 3.5 - 130)
        }
        0xFF shl 24 or (r shl 16) or (g shl 8) or b
    }

    private fun clamp(v: Double): Int = v.toInt().coerceIn(0, 255)

    /** Normalize raw16 → palette ARGB pixels. Returns min/max raw in outStats[0..1]. */
    fun colorize(thermal: IntArray, pixels: IntArray, outStats: IntArray) {
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        for (v in thermal) {
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        val span = (hi - lo).coerceAtLeast(1)
        for (i in thermal.indices) {
            val g = (thermal[i] - lo) * 255 / span
            pixels[i] = iron[g]
        }
        outStats[0] = lo; outStats[1] = hi
    }
}
