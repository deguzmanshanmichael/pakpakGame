package com.shan.pakpak.gfx

/**
 * ARGB color ints (0xAARRGGBB), with the same helper names the game used from android.graphics.Color.
 * Game code keeps colors as plain Ints; [Gfx] converts them when drawing.
 */
object Color {
    const val WHITE = -0x1
    const val BLACK = -0x1000000
    const val TRANSPARENT = 0

    fun rgb(r: Int, g: Int, b: Int): Int = argb(255, r, g, b)
    fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    fun alpha(c: Int) = (c ushr 24) and 0xFF
    fun red(c: Int) = (c shr 16) and 0xFF
    fun green(c: Int) = (c shr 8) and 0xFF
    fun blue(c: Int) = c and 0xFF

    /** "#RRGGBB" or "#AARRGGBB". */
    fun parseColor(s: String): Int {
        val hex = s.removePrefix("#")
        val v = hex.toLong(16).toInt()
        return when (hex.length) {
            6 -> v or (0xFF shl 24)
            8 -> v
            else -> throw IllegalArgumentException("Unknown color $s")
        }
    }

    /** hsv = [hue 0..360, saturation 0..1, value 0..1]. */
    fun HSVToColor(hsv: FloatArray): Int {
        val h = ((hsv[0] % 360f) + 360f) % 360f / 60f
        val s = hsv[1]
        val v = hsv[2]
        val i = h.toInt()
        val f = h - i
        val p = v * (1 - s)
        val q = v * (1 - s * f)
        val t = v * (1 - s * (1 - f))
        val (r, g, b) = when (i) {
            0 -> Triple(v, t, p); 1 -> Triple(q, v, p); 2 -> Triple(p, v, t)
            3 -> Triple(p, q, v); 4 -> Triple(t, p, v); else -> Triple(v, p, q)
        }
        return rgb((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())
    }
}
