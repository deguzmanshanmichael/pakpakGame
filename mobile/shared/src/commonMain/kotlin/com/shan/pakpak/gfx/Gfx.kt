package com.shan.pakpak.gfx

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.Color as ComposeColor

/** Pixel-art drawing helpers over the virtual-resolution canvas. Coordinates are virtual pixels. Colors are ARGB ints. */
class Gfx(val c: Canvas, val w: Int, val h: Int) {
    private val fill = Paint().apply { isAntiAlias = false; style = PaintingStyle.Fill }
    private val sprite = Paint().apply { isAntiAlias = false; filterQuality = FilterQuality.None }

    private fun col(argb: Int) = ComposeColor(argb)

    fun clear(color: Int) {
        fill.color = col(color)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fill)
    }

    fun rect(x: Int, y: Int, w: Int, h: Int, color: Int) {
        if (w <= 0 || h <= 0) return
        fill.color = col(color)
        c.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), fill)
    }

    fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) = rect(x.toInt(), y.toInt(), w.toInt(), h.toInt(), color)

    fun outline(x: Int, y: Int, w: Int, h: Int, color: Int) {
        rect(x, y, w, 1, color); rect(x, y + h - 1, w, 1, color)
        rect(x, y, 1, h, color); rect(x + w - 1, y, 1, h, color)
    }

    /** Filled box with a 1px border and the corners knocked out for a pixel-rounded look. */
    fun box(x: Int, y: Int, w: Int, h: Int, fillColor: Int, border: Int) {
        rect(x + 1, y + 1, w - 2, h - 2, fillColor)
        rect(x + 1, y, w - 2, 1, border); rect(x + 1, y + h - 1, w - 2, 1, border)
        rect(x, y + 1, 1, h - 2, border); rect(x + w - 1, y + 1, 1, h - 2, border)
    }

    fun disc(cx: Float, cy: Float, r: Float, color: Int) {
        fill.color = col(color)
        c.drawCircle(Offset(cx, cy), r, fill)
    }

    fun alpha(color: Int, a: Float): Int = Color.argb((Color.alpha(color) * a.coerceIn(0f, 1f)).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    fun text(s: String, x: Int, y: Int, color: Int, scale: Int = 1) = PixelFont.draw(c, s, x, y, color, scale)

    fun textShadow(s: String, x: Int, y: Int, color: Int, shadow: Int = Pal.INK, scale: Int = 1) {
        PixelFont.draw(c, s, x + scale, y + scale, shadow, scale)
        PixelFont.draw(c, s, x, y, color, scale)
    }

    /** Text with a 1-pixel outline all around: used for titles and in-game score. */
    fun textOutlined(s: String, x: Int, y: Int, color: Int, outline: Int = Pal.INK, scale: Int = 1) {
        for (ox in -1..1) for (oy in -1..1) if (ox != 0 || oy != 0) PixelFont.draw(c, s, x + ox * scale, y + oy * scale, outline, scale)
        PixelFont.draw(c, s, x, y, color, scale)
    }

    fun textCenter(s: String, cx: Int, y: Int, color: Int, scale: Int = 1, shadow: Boolean = false, outline: Boolean = false) {
        val x = cx - PixelFont.width(s, scale) / 2
        when {
            outline -> textOutlined(s, x, y, color, Pal.INK, scale)
            shadow -> textShadow(s, x, y, color, Pal.INK, scale)
            else -> text(s, x, y, color, scale)
        }
    }

    fun textRight(s: String, right: Int, y: Int, color: Int, scale: Int = 1) = text(s, right - PixelFont.width(s, scale), y, color, scale)

    /** Draws wrapped text and returns the height used. */
    fun paragraph(s: String, x: Int, y: Int, maxW: Int, color: Int, lineH: Int = PixelFont.LINE): Int {
        val lines = PixelFont.wrap(s, maxW)
        lines.forEachIndexed { i, l -> text(l, x, y + i * lineH, color) }
        return lines.size * lineH
    }

    fun bitmap(b: ImageBitmap, x: Int, y: Int, scale: Int = 1, alpha: Int = 255) {
        sprite.alpha = alpha / 255f
        c.drawImageRect(b, IntOffset.Zero, IntSize(b.width, b.height), IntOffset(x, y), IntSize(b.width * scale, b.height * scale), sprite)
        sprite.alpha = 1f
    }

    fun bitmapCentered(b: ImageBitmap, cx: Float, cy: Float, scale: Int = 1, alpha: Int = 255) =
        bitmap(b, (cx - b.width * scale / 2f).toInt(), (cy - b.height * scale / 2f).toInt(), scale, alpha)

    fun bitmapRegion(b: ImageBitmap, sx: Int, sy: Int, sw: Int, sh: Int, x: Int, y: Int, dw: Int = sw, dh: Int = sh, alpha: Int = 255) {
        if (sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0 || alpha <= 0) return
        sprite.alpha = alpha / 255f
        c.drawImageRect(b, IntOffset(sx, sy), IntSize(sw, sh), IntOffset(x, y), IntSize(dw, dh), sprite)
        sprite.alpha = 1f
    }

    fun bitmapRotated(b: ImageBitmap, cx: Float, cy: Float, degrees: Float, scale: Float = 1f) {
        c.save()
        c.translate(cx.toInt().toFloat(), cy.toInt().toFloat())
        c.rotate(degrees)
        val hw = (b.width * scale / 2f).toInt()
        val hh = (b.height * scale / 2f).toInt()
        c.drawImageRect(b, IntOffset.Zero, IntSize(b.width, b.height), IntOffset(-hw, -hh), IntSize(hw * 2, hh * 2), sprite)
        c.restore()
    }

    fun clip(x: Int, y: Int, w: Int, h: Int) { c.save(); c.clipRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat()) }
    fun unclip() = c.restore()
}

/** Shared UI palette. */
object Pal {
    val INK = Color.rgb(28, 22, 18)
    val WHITE = Color.WHITE
    val CREAM = Color.rgb(255, 248, 225)
    val PAPER = Color.rgb(250, 236, 196)
    val PAPER_DARK = Color.rgb(222, 196, 140)
    val WOOD = Color.rgb(140, 86, 48)
    val WOOD_DARK = Color.rgb(92, 54, 30)
    val MUTED = Color.rgb(120, 104, 84)
    val SKY = Color.rgb(94, 200, 242)
    val ORANGE = Color.rgb(255, 150, 40)
    val ORANGE_DARK = Color.rgb(196, 96, 16)
    val GREEN = Color.rgb(96, 196, 72)
    val GREEN_DARK = Color.rgb(52, 128, 40)
    val BLUE = Color.rgb(64, 150, 240)
    val BLUE_DARK = Color.rgb(32, 92, 176)
    val RED = Color.rgb(232, 72, 64)
    val RED_DARK = Color.rgb(150, 36, 32)
    val GRAY = Color.rgb(150, 150, 150)
    val GRAY_DARK = Color.rgb(96, 96, 96)
    val GOLD = Color.rgb(255, 210, 63)
    val SHADE = Color.argb(150, 10, 8, 20)
    val YELLOW = Color.rgb(255, 230, 90)
}
