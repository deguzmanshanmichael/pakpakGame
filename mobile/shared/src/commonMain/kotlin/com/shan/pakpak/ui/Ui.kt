package com.shan.pakpak.ui

import com.shan.pakpak.gfx.Color
import com.shan.pakpak.G
import com.shan.pakpak.engine.Input
import com.shan.pakpak.gfx.Gfx
import com.shan.pakpak.gfx.Pal
import com.shan.pakpak.gfx.PixelFont
import kotlin.math.abs

enum class Style(val face: Int, val dark: Int, val light: Int, val text: Int) {
    PRIMARY(Pal.ORANGE, Pal.ORANGE_DARK, Color.rgb(255, 200, 120), Pal.WHITE),
    GREEN(Pal.GREEN, Pal.GREEN_DARK, Color.rgb(170, 236, 140), Pal.WHITE),
    BLUE(Pal.BLUE, Pal.BLUE_DARK, Color.rgb(150, 200, 255), Pal.WHITE),
    RED(Pal.RED, Pal.RED_DARK, Color.rgb(255, 150, 140), Pal.WHITE),
    PLAIN(Pal.PAPER, Pal.PAPER_DARK, Pal.CREAM, Pal.INK),
    DISABLED(Color.rgb(170, 164, 152), Color.rgb(120, 114, 104), Color.rgb(196, 190, 178), Color.rgb(90, 86, 80)),
}

/**
 * Immediate-mode UI: widgets are drawn and hit-tested in the same call each frame.
 * While a modal is open the scene underneath is drawn with [blocked] = true, so it can't be tapped.
 */
class Ui(private val input: Input) {
    lateinit var g: Gfx
    var blocked = false
    private var clipX = 0f
    private var clipY = 0f
    private var clipW = 1e6f
    private var clipH = 1e6f
    private val scrolls = HashMap<String, ScrollState>()

    private class ScrollState { var offset = 0f; var vel = 0f; var dragging = false }

    fun begin(g: Gfx) { this.g = g; blocked = false; resetClip() }

    private fun resetClip() { clipX = 0f; clipY = 0f; clipW = 1e6f; clipH = 1e6f }

    private fun inClip(px: Float, py: Float) = px >= clipX && py >= clipY && px < clipX + clipW && py < clipY + clipH

    /** A tap that both started and ended inside the rect (and inside the current scroll view). */
    fun tapped(x: Int, y: Int, w: Int, h: Int): Boolean {
        if (blocked) return false
        val hit = input.tapped(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat()) && inClip(input.pressX, input.pressY)
        if (hit) input.consumed = true
        return hit
    }

    private fun held(x: Int, y: Int, w: Int, h: Int) =
        !blocked && input.holding(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat()) && inClip(input.pressX, input.pressY)

    fun button(x: Int, y: Int, w: Int, h: Int, label: String, style: Style = Style.PRIMARY, enabled: Boolean = true, badge: Int = 0): Boolean {
        val st = if (enabled) style else Style.DISABLED
        val down = enabled && held(x, y, w, h)
        val oy = if (down) 1 else 0
        g.box(x, y + 1, w, h, st.dark, Pal.INK) // shadow
        g.box(x, y + oy, w, h - 1, st.face, Pal.INK)
        if (!down) g.rect(x + 2, y + 1, w - 4, 1, st.light)
        val lines = PixelFont.wrap(label, w - 6)
        val total = lines.size * PixelFont.LINE - 3
        var ty = y + oy + (h - 1 - total) / 2
        for (l in lines) {
            g.textCenter(l, x + w / 2, ty, st.text)
            ty += PixelFont.LINE
        }
        if (badge > 0) {
            val s = if (badge > 99) "99+" else badge.toString()
            val bw = PixelFont.width(s) + 5
            g.box(x + w - bw + 2, y - 3, bw, 11, Pal.RED, Pal.INK)
            g.text(s, x + w - bw + 5, y - 1, Pal.WHITE)
        }
        val clicked = enabled && tapped(x, y, w, h)
        if (clicked) { G.audio.play("click"); G.haptic() }
        return clicked
    }

    fun panel(x: Int, y: Int, w: Int, h: Int, fill: Int = Pal.PAPER, border: Int = Pal.INK) {
        g.box(x, y + 2, w, h, Color.argb(90, 0, 0, 0), Color.argb(90, 0, 0, 0))
        g.box(x, y, w, h, fill, border)
        g.rect(x + 2, y + 1, w - 4, 1, Pal.CREAM)
    }

    fun progress(x: Int, y: Int, w: Int, h: Int, frac: Float, color: Int, bg: Int = Color.rgb(70, 60, 50)) {
        g.box(x, y, w, h, bg, Pal.INK)
        val fw = ((w - 2) * frac.coerceIn(0f, 1f)).toInt()
        if (fw > 0) {
            g.rect(x + 1, y + 1, fw, h - 2, color)
            g.rect(x + 1, y + 1, fw, 1, g.alpha(Color.WHITE, 0.4f))
        }
    }

    /** Row of tabs. Returns the selected index (possibly changed by a tap). */
    fun tabs(x: Int, y: Int, w: Int, labels: List<String>, selected: Int, badges: List<Int> = emptyList()): Int {
        val tw = w / labels.size
        var sel = selected
        for ((i, l) in labels.withIndex()) {
            val tx = x + i * tw
            val ww = if (i == labels.size - 1) w - tw * i else tw
            val active = i == selected
            g.box(tx, y, ww, 14, if (active) Pal.PAPER else Pal.PAPER_DARK, Pal.INK)
            g.textCenter(l, tx + ww / 2, y + 4, if (active) Pal.INK else Pal.MUTED)
            val b = badges.getOrNull(i) ?: 0
            if (b > 0) g.box(tx + ww - 6, y - 2, 6, 6, Pal.RED, Pal.INK)
            if (tapped(tx, y, ww, 14) && i != selected) { sel = i; G.audio.play("click") }
        }
        return sel
    }

    fun toggle(x: Int, y: Int, w: Int, label: String, value: Boolean): Boolean {
        g.text(label, x, y + 3, Pal.INK)
        val bx = x + w - 26
        g.box(bx, y, 26, 13, if (value) Pal.GREEN else Pal.GRAY, Pal.INK)
        g.box(if (value) bx + 13 else bx + 1, y + 1, 12, 11, Pal.CREAM, Pal.INK)
        if (tapped(x, y - 2, w, 17)) { G.audio.play("click"); return !value }
        return value
    }

    /** -/+ stepper for 0..10 values. */
    fun stepper(x: Int, y: Int, w: Int, label: String, value: Int, min: Int = 0, max: Int = 10): Int {
        g.text(label, x, y + 3, Pal.INK)
        var v = value
        val bx = x + w - 54
        if (button(bx, y - 1, 14, 14, "-", Style.PLAIN, v > min)) v--
        g.textCenter(v.toString(), bx + 27, y + 3, Pal.INK)
        if (button(bx + 40, y - 1, 14, 14, "+", Style.PLAIN, v < max)) v++
        return v
    }

    /**
     * Starts a vertical scroll view. Returns the current offset; draw content at y - offset, then
     * call [endScroll]. Content outside the view is clipped and can't be tapped.
     */
    fun beginScroll(id: String, x: Int, y: Int, w: Int, h: Int, contentH: Int): Int {
        val s = scrolls.getOrPut(id) { ScrollState() }
        val maxOff = (contentH - h).coerceAtLeast(0).toFloat()
        if (!blocked && input.down && input.dragging && input.pressStartedIn(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat())) {
            s.dragging = true
            s.offset -= input.dy
            s.vel = -input.dy * 60f
        } else if (s.dragging && !input.down) {
            s.dragging = false
        } else if (!s.dragging && abs(s.vel) > 5f) {
            s.offset += s.vel / 60f
            s.vel *= 0.92f
        }
        if (s.offset < 0f) { s.offset = 0f; s.vel = 0f }
        if (s.offset > maxOff) { s.offset = maxOff; s.vel = 0f }
        g.clip(x, y, w, h)
        clipX = x.toFloat(); clipY = y.toFloat(); clipW = w.toFloat(); clipH = h.toFloat()
        if (maxOff > 0) {
            // scrollbar
            val barH = (h * h / contentH.toFloat()).toInt().coerceAtLeast(10)
            val barY = y + ((h - barH) * (s.offset / maxOff)).toInt()
            g.rect(x + w - 2, barY, 2, barH, g.alpha(Pal.INK, 0.5f))
        }
        return s.offset.toInt()
    }

    fun endScroll() { g.unclip(); resetClip() }

    fun scrollTo(id: String, offset: Float) { scrolls.getOrPut(id) { ScrollState() }.offset = offset; scrolls[id]?.vel = 0f }
    fun scrollToEnd(id: String) = scrollTo(id, 1e9f)
    fun resetScroll(id: String) { scrolls.remove(id) }
}
