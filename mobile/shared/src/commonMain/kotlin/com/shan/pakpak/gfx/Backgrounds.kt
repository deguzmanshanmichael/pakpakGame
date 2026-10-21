package com.shan.pakpak.gfx

import androidx.compose.ui.graphics.ImageBitmap
import com.shan.pakpak.game.Theme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Procedurally drawn parallax backgrounds (sky, far layer, near layer, ground) for each theme.
 * Layers are seamless tiles rendered once per screen size.
 */
object Backgrounds {
    private class Set(val sky: ImageBitmap, val far: ImageBitmap, val near: ImageBitmap, val ground: ImageBitmap)
    private val sets = HashMap<String, Set>()
    private const val FAR_W = 192
    private const val NEAR_W = 160
    private const val GROUND_TILE = 32

    private fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())

    private fun skyColors(t: Theme) = when (t) {
        Theme.MEADOW -> Color.rgb(84, 192, 240) to Color.rgb(200, 240, 255)
        Theme.BEACH -> Color.rgb(110, 200, 255) to Color.rgb(255, 228, 180)
        Theme.CAVE -> Color.rgb(28, 20, 40) to Color.rgb(66, 50, 86)
        Theme.SKY -> Color.rgb(20, 16, 64) to Color.rgb(96, 52, 140)
    }

    private fun build(t: Theme, w: Int, h: Int, groundH: Int): Set {
        val skyH = h - groundH
        // Sky: 8 colour bands with a 2-row dither between them for a retro look.
        val sky = Pix(1, skyH)
        val (top, bottom) = skyColors(t)
        val bands = 8
        for (y in 0 until skyH) {
            val f = y.toFloat() / skyH * bands
            var band = f.toInt().coerceAtMost(bands - 1)
            if (f - band > 0.85f && y % 2 == 0) band = (band + 1).coerceAtMost(bands - 1)
            sky.setPixel(0, y, mix(top, bottom, band / (bands - 1f)))
        }
        val far = Pix(FAR_W, skyH)
        val near = Pix(NEAR_W, skyH)
        val rnd = Random(t.ordinal * 977 + 13)
        fun px(b: Pix, x: Int, y: Int, c: Int) { if (y in 0 until b.height) b.setPixel(((x % b.width) + b.width) % b.width, y, c) }
        fun column(b: Pix, x: Int, fromY: Int, toY: Int, c: Int) { for (y in max(0, fromY) until toY.coerceAtMost(b.height)) px(b, x, y, c) }

        when (t) {
            Theme.MEADOW, Theme.BEACH -> {
                // clouds on the far layer
                repeat(5) {
                    val cx = rnd.nextInt(FAR_W)
                    val cy = (skyH * 0.08f + rnd.nextInt((skyH * 0.35f).toInt().coerceAtLeast(1))).toInt()
                    val r = 6 + rnd.nextInt(6)
                    for (dx in -r * 2..r * 2) for (dy in -r..r / 2) {
                        val e = (dx * dx) / (4f * r * r) + (dy * dy).toFloat() / (r * r)
                        if (e <= 1f) px(far, cx + dx, cy + dy, if (dy > r / 4) Color.rgb(225, 240, 250) else Color.WHITE)
                    }
                }
            }
            else -> {}
        }
        when (t) {
            Theme.MEADOW -> {
                for (x in 0 until FAR_W) {
                    val hh = (18 + 10 * sin(x * 2 * PI / FAR_W * 2) + 6 * sin(x * 2 * PI / FAR_W * 5 + 1)).toInt()
                    column(far, x, skyH - hh - 10, skyH, Color.rgb(140, 210, 110))
                    px(far, x, skyH - hh - 10, Color.rgb(170, 230, 130))
                }
                for (x in 0 until NEAR_W) {
                    val hh = (8 + 5 * abs(sin(x * PI / 20))).toInt()
                    column(near, x, skyH - hh, skyH, Color.rgb(78, 160, 60))
                    px(near, x, skyH - hh, Color.rgb(110, 190, 80))
                }
                // little bamboo silhouettes and rice terraces stripes
                for (k in 0 until 3) {
                    val bx = 20 + k * 52
                    for (y in skyH - 46 until skyH - 8) { px(near, bx, y, Color.rgb(60, 130, 50)); px(near, bx + 1, y, Color.rgb(60, 130, 50)) }
                    for (y in skyH - 46 until skyH - 8 step 8) { px(near, bx - 1, y, Color.rgb(40, 100, 36)); px(near, bx + 2, y, Color.rgb(40, 100, 36)) }
                }
            }
            Theme.BEACH -> {
                // sea band with sparkles
                val seaTop = skyH - 34
                for (x in 0 until FAR_W) {
                    column(far, x, seaTop, skyH, Color.rgb(46, 150, 210))
                    if ((x * 7) % 23 < 3) px(far, x, seaTop + 4 + (x % 5) * 5, Color.rgb(200, 240, 255))
                    px(far, x, seaTop, Color.rgb(120, 200, 240))
                }
                // island
                for (x in 40..90) { val hh = (12 * sin((x - 40) * PI / 50)).toInt(); column(far, x, seaTop - hh, seaTop, Color.rgb(90, 160, 90)) }
                // dunes
                for (x in 0 until NEAR_W) {
                    val hh = (9 + 4 * sin(x * 2 * PI / NEAR_W * 2)).toInt()
                    column(near, x, skyH - hh, skyH, Color.rgb(240, 210, 140))
                    px(near, x, skyH - hh, Color.rgb(255, 232, 170))
                }
            }
            Theme.CAVE -> {
                for (x in 0 until FAR_W) {
                    val top = (20 + 12 * sin(x * 2 * PI / FAR_W * 3) + 6 * sin(x * 0.4)).toInt()
                    column(far, x, 0, top, Color.rgb(52, 40, 68))
                    val bot = (24 + 10 * sin(x * 2 * PI / FAR_W * 2 + 2)).toInt()
                    column(far, x, skyH - bot, skyH, Color.rgb(52, 40, 68))
                }
                for (x in 0 until NEAR_W) {
                    // stalactites
                    val k = x % 20
                    val len = if (k < 10) (16 - abs(k - 5) * 3).coerceAtLeast(0) + (x / 20 % 3) * 6 else 0
                    column(near, x, 0, len + 4, Color.rgb(74, 58, 94))
                }
                repeat(14) {
                    val x = rnd.nextInt(FAR_W)
                    val y = 30 + rnd.nextInt((skyH - 60).coerceAtLeast(1))
                    val c = if (rnd.nextBoolean()) Color.rgb(120, 230, 255) else Color.rgb(255, 130, 220)
                    px(far, x, y, c); px(far, x + 1, y, c); px(far, x, y + 1, c)
                }
            }
            Theme.SKY -> {
                repeat(60) {
                    val x = rnd.nextInt(FAR_W)
                    val y = rnd.nextInt((skyH * 0.8f).toInt().coerceAtLeast(1))
                    px(far, x, y, if (rnd.nextInt(4) == 0) Color.rgb(255, 240, 180) else Color.WHITE)
                }
                // purple clouds near layer
                for (x in 0 until NEAR_W) {
                    val hh = (14 + 8 * sin(x * 2 * PI / NEAR_W * 3) + 4 * sin(x * 0.7)).toInt()
                    column(near, x, skyH - hh, skyH, Color.rgb(130, 100, 200))
                    px(near, x, skyH - hh, Color.rgb(180, 150, 240))
                }
                // moon
                for (dx in -7..7) for (dy in -7..7) {
                    val d = dx * dx + dy * dy
                    val d2 = (dx - 3) * (dx - 3) + (dy + 2) * (dy + 2)
                    if (d <= 49 && d2 > 36) px(far, 150 + dx, 30 + dy, Color.rgb(255, 245, 210))
                }
            }
        }

        val ground = Pix(GROUND_TILE, groundH)
        val gr = Random(t.ordinal + 100)
        for (y in 0 until groundH) for (x in 0 until GROUND_TILE) {
            val c = when (t) {
                Theme.MEADOW -> when {
                    y == 0 -> Color.rgb(40, 90, 30)
                    y < 6 -> if ((x + y) % 8 < 4) Color.rgb(124, 200, 72) else Color.rgb(104, 180, 60)
                    y == 6 -> Color.rgb(80, 140, 50)
                    else -> if (gr.nextInt(12) == 0) Color.rgb(196, 140, 80) else Color.rgb(222, 168, 104)
                }
                Theme.BEACH -> when {
                    y == 0 -> Color.rgb(160, 120, 60)
                    y < 4 -> Color.rgb(255, 236, 170)
                    else -> if (gr.nextInt(10) == 0) Color.rgb(220, 190, 120) else Color.rgb(240, 212, 140)
                }
                Theme.CAVE -> when {
                    y == 0 -> Color.rgb(20, 14, 26)
                    y < 4 -> if ((x / 3 + y) % 3 == 0) Color.rgb(90, 140, 90) else Color.rgb(70, 110, 74)
                    else -> if (gr.nextInt(7) == 0) Color.rgb(60, 48, 74) else Color.rgb(78, 62, 94)
                }
                Theme.SKY -> when {
                    y == 0 -> Color.rgb(90, 70, 160)
                    y < 5 -> Color.rgb(236, 230, 255)
                    else -> if ((x + y * 3) % 11 == 0) Color.rgb(210, 200, 245) else Color.rgb(196, 184, 240)
                }
            }
            ground.setPixel(x, y, c)
        }
        return Set(sky.toImage(), far.toImage(), near.toImage(), ground.toImage())
    }

    private fun set(t: Theme, w: Int, h: Int, groundH: Int) = sets.getOrPut("$t:$w:$h:$groundH") {
        if (sets.size > 8) sets.clear()
        build(t, w, h, groundH)
    }

    /** Draws sky and parallax layers (not the ground). [scroll] is world distance in px. */
    fun drawSky(g: Gfx, t: Theme, scroll: Float, time: Float, groundH: Int, alpha: Int = 255) {
        val s = set(t, g.w, g.h, groundH)
        val skyH = g.h - groundH
        g.bitmapRegion(s.sky, 0, 0, 1, skyH, 0, 0, g.w, skyH)
        tile(g, s.far, scroll * 0.12f, alpha)
        if (t == Theme.SKY) {
            // twinkling stars
            val rnd = Random(5)
            repeat(10) {
                val x = rnd.nextInt(g.w)
                val y = rnd.nextInt((skyH * 0.6f).toInt().coerceAtLeast(1))
                if (sin(time * 3 + it) > 0.6) g.rect(x, y, 1, 1, Color.WHITE)
            }
        }
        tile(g, s.near, scroll * 0.35f, alpha)
    }

    fun drawGround(g: Gfx, t: Theme, scroll: Float, groundH: Int) {
        val s = set(t, g.w, g.h, groundH)
        val y = g.h - groundH
        val off = (scroll % GROUND_TILE).toInt()
        var x = -off
        while (x < g.w) { g.bitmap(s.ground, x, y); x += GROUND_TILE }
    }

    private fun tile(g: Gfx, b: ImageBitmap, scroll: Float, alpha: Int) {
        val off = (scroll % b.width).toInt()
        var x = -off
        while (x < g.w) { g.bitmap(b, x, 0, 1, alpha); x += b.width }
    }

    fun clear() = sets.clear()
}
