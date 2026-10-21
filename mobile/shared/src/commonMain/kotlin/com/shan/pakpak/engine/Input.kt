package com.shan.pakpak.engine

import kotlin.math.abs

/**
 * Touch state for one frame, in virtual pixels. Events arrive on the UI thread and are applied
 * at the start of each frame on the game thread.
 */
class Input {
    companion object {
        const val DOWN = 0
        const val MOVE = 1
        const val UP = 2
        const val CANCEL = 3
        const val DRAG_SLOP = 5f
    }

    private class Ev(val type: Int, val x: Float, val y: Float, val pointer: Int)
    // Touches arrive from the UI toolkit on the main thread, the same thread that runs frames.
    private val queue = ArrayDeque<Ev>()

    var primaryPointer = 0

    var x = 0f; private set
    var y = 0f; private set
    var down = false; private set
    var pressX = 0f; private set
    var pressY = 0f; private set
    /** Pointer went down this frame. */
    var pressed = false; private set
    /** Pointer went up this frame (a tap or the end of a drag). */
    var released = false; private set
    /** The current gesture moved far enough to count as a drag; taps are suppressed. */
    var dragging = false; private set
    var dy = 0f; private set
    var dx = 0f; private set
    /** Every touch-down this frame, including extra fingers: used for flapping. */
    var taps = 0; private set
    /** Set when something (a button, a modal) consumed this frame's release. */
    var consumed = false

    fun push(type: Int, x: Float, y: Float, pointer: Int) { queue.addLast(Ev(type, x, y, pointer)) }

    fun beginFrame() {
        pressed = false
        released = false
        consumed = false
        taps = 0
        dx = 0f
        dy = 0f
        while (true) {
            val e = queue.removeFirstOrNull() ?: break
            when (e.type) {
                DOWN -> {
                    taps++
                    if (!down) {
                        primaryPointer = e.pointer
                        down = true
                        pressed = true
                        dragging = false
                        pressX = e.x; pressY = e.y
                        x = e.x; y = e.y
                    }
                }
                MOVE -> if (down && e.pointer == primaryPointer) {
                    dx += e.x - x
                    dy += e.y - y
                    x = e.x; y = e.y
                    if (abs(x - pressX) > DRAG_SLOP || abs(y - pressY) > DRAG_SLOP) dragging = true
                }
                UP -> if (down && e.pointer == primaryPointer) {
                    x = e.x; y = e.y
                    down = false
                    released = true
                }
                CANCEL -> { down = false; dragging = true; released = false }
            }
        }
    }

    /** True if a tap (not a drag) ended inside the rect this frame and nothing consumed it yet. */
    fun tapped(rx: Float, ry: Float, rw: Float, rh: Float): Boolean =
        released && !dragging && !consumed && inside(pressX, pressY, rx, ry, rw, rh) && inside(x, y, rx, ry, rw, rh)

    /** True while the pointer is held inside a rect it was pressed in. */
    fun holding(rx: Float, ry: Float, rw: Float, rh: Float): Boolean =
        down && !dragging && inside(pressX, pressY, rx, ry, rw, rh) && inside(x, y, rx, ry, rw, rh)

    fun pressStartedIn(rx: Float, ry: Float, rw: Float, rh: Float) = inside(pressX, pressY, rx, ry, rw, rh)

    private fun inside(px: Float, py: Float, rx: Float, ry: Float, rw: Float, rh: Float) =
        px >= rx && py >= ry && px < rx + rw && py < ry + rh

    fun clear() { queue.clear(); down = false; pressed = false; released = false; taps = 0 }
}
