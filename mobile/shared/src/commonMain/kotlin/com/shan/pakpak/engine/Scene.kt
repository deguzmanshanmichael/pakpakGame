package com.shan.pakpak.engine

import com.shan.pakpak.gfx.Color
import com.shan.pakpak.G
import com.shan.pakpak.Tracks
import com.shan.pakpak.gfx.Gfx
import com.shan.pakpak.gfx.Pal
import com.shan.pakpak.gfx.PixelFont
import com.shan.pakpak.ui.Style
import com.shan.pakpak.ui.Ui

abstract class Scene {
    open val music: String get() = Tracks.MENU
    open fun enter() {}
    open fun exit() {}
    /** Called when the app goes to the background while this scene is on top. */
    open fun onAppPause() {}
    open fun update(dt: Float) {}
    abstract fun draw(g: Gfx, ui: Ui)
    /** Return true if the scene handled the back button itself. */
    open fun back(): Boolean = false
}

class ModalButton(val label: String, val style: Style = Style.PRIMARY, val action: () -> Unit = {})

class Modal(
    val title: String,
    val body: String,
    val buttons: List<ModalButton>,
    /** Optional custom content drawn under the body in a box [extraHeight] tall: (g, ui, x, y, w). */
    val extra: ((Gfx, Ui, Int, Int, Int) -> Unit)? = null,
    val extraHeight: Int = 0,
    val dismissable: Boolean = true,
)

/** Scene stack with fade transitions, modal dialogs and toast banners. */
class SceneManager {
    private val stack = ArrayList<Scene>()
    private var pending: (() -> Unit)? = null
    private var fade = 0f
    private var fadingOut = false
    private val modals = ArrayList<Modal>()
    private var toastText: String? = null
    private var toastTime = 0f

    val top: Scene? get() = stack.lastOrNull()
    val modalOpen get() = modals.isNotEmpty()

    fun push(s: Scene) = transition { stack.add(s); s.enter() }
    fun replace(s: Scene) = transition { stack.removeLastOrNull()?.exit(); stack.add(s); s.enter() }
    fun reset(s: Scene) = transition { while (stack.isNotEmpty()) stack.removeAt(stack.size - 1).exit(); stack.add(s); s.enter() }
    fun pop() = transition { if (stack.size > 1) stack.removeAt(stack.size - 1).exit() }

    /** Pops scenes until one of the given type is on top (or only one is left). */
    fun popTo(predicate: (Scene) -> Boolean) = transition {
        while (stack.size > 1 && !predicate(stack.last())) stack.removeAt(stack.size - 1).exit()
    }

    private fun transition(change: () -> Unit) {
        if (stack.isEmpty()) { change(); G.audio.music(top?.music ?: Tracks.NONE); return }
        modals.clear()
        pending = change
        fadingOut = true
        G.input.clear()
    }

    fun alert(title: String, body: String, ok: String = "OK", action: () -> Unit = {}) =
        show(Modal(title, body, listOf(ModalButton(ok, Style.PRIMARY, action))))

    fun confirm(title: String, body: String, yes: String, no: String = "Cancel", yesStyle: Style = Style.PRIMARY, onYes: () -> Unit) =
        show(Modal(title, body, listOf(ModalButton(no, Style.PLAIN), ModalButton(yes, yesStyle, onYes))))

    fun show(m: Modal) { modals.add(m) }
    fun closeModal() { modals.removeLastOrNull() }

    fun toast(text: String) { toastText = text; toastTime = 2.8f }

    fun back(): Boolean {
        if (modals.isNotEmpty()) { if (modals.last().dismissable) modals.removeAt(modals.size - 1); return true }
        val t = top ?: return false
        if (t.back()) return true
        if (stack.size > 1) { pop(); return true }
        return false
    }

    fun onAppPause() { top?.onAppPause() }

    fun update(dt: Float) {
        if (toastTime > 0f) toastTime -= dt
        if (fadingOut) {
            fade += dt / 0.14f
            if (fade >= 1f) {
                fade = 1f
                fadingOut = false
                pending?.invoke()
                pending = null
                G.audio.music(top?.music ?: Tracks.NONE)
            }
            return
        }
        if (fade > 0f) fade = (fade - dt / 0.18f).coerceAtLeast(0f)
        if (!modalOpen) top?.update(dt)
    }

    fun draw(g: Gfx, ui: Ui) {
        ui.blocked = modalOpen || fade > 0.5f
        top?.draw(g, ui)
        ui.blocked = fade > 0.5f
        if (modalOpen) drawModal(g, ui, modals.last())
        toastText?.let { if (toastTime > 0f) drawToast(g, it) }
        if (fade > 0f) g.rect(0, 0, g.w, g.h, Color.argb((fade * 255).toInt().coerceIn(0, 255), 16, 12, 24))
        ui.blocked = false
    }

    private fun drawModal(g: Gfx, ui: Ui, m: Modal) {
        g.rect(0, 0, g.w, g.h, Pal.SHADE)
        val w = (g.w - 20).coerceAtMost(260)
        val x = (g.w - w) / 2
        val bodyLines = if (m.body.isEmpty()) emptyList() else PixelFont.wrap(m.body, w - 16)
        val maxBodyLines = ((g.h - 140) / PixelFont.LINE).coerceAtLeast(4)
        val shown = bodyLines.take(maxBodyLines)
        val titleLines = PixelFont.wrap(m.title, w - 16)
        val extraH = if (m.extra != null) m.extraHeight else 0
        val btnRows = if (m.buttons.size <= 2) 1 else m.buttons.size
        val h = 14 + titleLines.size * 10 + 6 + shown.size * PixelFont.LINE + extraH + 8 + btnRows * 22 + 4
        val y = ((g.h - h) / 2).coerceAtLeast(4)
        ui.panel(x, y, w, h)
        var cy = y + 8
        for (l in titleLines) { g.textCenter(l, x + w / 2, cy, Pal.ORANGE_DARK); cy += 10 }
        g.rect(x + 8, cy + 1, w - 16, 1, Pal.PAPER_DARK)
        cy += 6
        for (l in shown) { g.text(l, x + 8, cy, Pal.INK); cy += PixelFont.LINE }
        if (m.extra != null) { m.extra.invoke(g, ui, x + 8, cy, w - 16); cy += extraH }
        cy += 6
        if (m.buttons.size <= 2) {
            val bw = (w - 16 - (m.buttons.size - 1) * 6) / m.buttons.size.coerceAtLeast(1)
            for ((i, b) in m.buttons.withIndex()) {
                if (ui.button(x + 8 + i * (bw + 6), cy, bw, 18, b.label, b.style)) { modals.remove(m); b.action() }
            }
        } else {
            for (b in m.buttons) {
                if (ui.button(x + 8, cy, w - 16, 18, b.label, b.style)) { modals.remove(m); b.action() }
                cy += 22
            }
        }
    }

    private fun drawToast(g: Gfx, text: String) {
        val lines = PixelFont.wrap(text, g.w - 32)
        val h = lines.size * 10 + 8
        val w = (lines.maxOf { PixelFont.width(it) } + 16).coerceAtMost(g.w - 16)
        val x = (g.w - w) / 2
        val slide = ((2.8f - toastTime) * 8f).coerceAtMost(1f).coerceAtMost(toastTime * 4f)
        val y = (-h + (h + 8) * slide).toInt()
        g.box(x, y, w, h, Color.rgb(40, 32, 56), Pal.CREAM)
        lines.forEachIndexed { i, l -> g.textCenter(l, g.w / 2, y + 5 + i * 10, Pal.CREAM) }
    }
}
