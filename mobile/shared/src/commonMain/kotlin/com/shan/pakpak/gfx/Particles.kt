package com.shan.pakpak.gfx

import kotlin.random.Random

/** Tiny square particles: feathers, sparkles, confetti, dust. Pooled to avoid allocations mid-run. */
class Particles(private val max: Int = 300) {
    private class P { var x = 0f; var y = 0f; var vx = 0f; var vy = 0f; var life = 0f; var total = 1f; var color = 0; var size = 1; var grav = 0f }
    private val pool = Array(max) { P() }
    private var count = 0
    private val rnd = Random(42)

    fun burst(x: Float, y: Float, n: Int, colors: IntArray, speed: Float = 60f, life: Float = 0.6f, grav: Float = 120f, size: Int = 1) {
        repeat(n) {
            if (count >= max) return
            val p = pool[count++]
            val a = rnd.nextFloat() * 6.283f
            val s = speed * (0.3f + rnd.nextFloat() * 0.7f)
            p.x = x; p.y = y
            p.vx = kotlin.math.cos(a) * s
            p.vy = kotlin.math.sin(a) * s
            p.life = life * (0.6f + rnd.nextFloat() * 0.4f)
            p.total = p.life
            p.color = colors[rnd.nextInt(colors.size)]
            p.size = size
            p.grav = grav
        }
    }

    /** One directed particle (trails). */
    fun spawn(x: Float, y: Float, vx: Float, vy: Float, life: Float, color: Int, size: Int = 1, grav: Float = 0f) {
        if (count >= max) return
        val p = pool[count++]
        p.x = x; p.y = y; p.vx = vx; p.vy = vy
        p.life = life; p.total = life; p.color = color; p.size = size; p.grav = grav
    }

    /** Confetti falling from the top of the screen. */
    fun confetti(w: Int, n: Int, colors: IntArray) {
        repeat(n) {
            if (count >= max) return
            val p = pool[count++]
            p.x = rnd.nextFloat() * w; p.y = -rnd.nextFloat() * 40f
            p.vx = (rnd.nextFloat() - 0.5f) * 30f; p.vy = 30f + rnd.nextFloat() * 40f
            p.life = 3f + rnd.nextFloat() * 2f; p.total = p.life
            p.color = colors[rnd.nextInt(colors.size)]; p.size = 1 + rnd.nextInt(2); p.grav = 10f
        }
    }

    fun update(dt: Float) {
        var i = 0
        while (i < count) {
            val p = pool[i]
            p.life -= dt
            if (p.life <= 0f) {
                // swap-remove
                count--
                val t = pool[i]; pool[i] = pool[count]; pool[count] = t
                continue
            }
            p.vy += p.grav * dt
            p.x += p.vx * dt
            p.y += p.vy * dt
            i++
        }
    }

    fun draw(g: Gfx) {
        for (i in 0 until count) {
            val p = pool[i]
            val a = (p.life / p.total).coerceIn(0f, 1f)
            g.rect(p.x.toInt(), p.y.toInt(), p.size, p.size, if (a < 0.3f) g.alpha(p.color, a / 0.3f) else p.color)
        }
    }

    fun clear() { count = 0 }
    val active get() = count
}
