package com.shan.pakpak.scenes

import com.shan.pakpak.nowMs
import com.shan.pakpak.gfx.Color
import com.shan.pakpak.G
import com.shan.pakpak.data.Item
import com.shan.pakpak.data.Rarity
import com.shan.pakpak.data.Skin
import com.shan.pakpak.engine.Modal
import com.shan.pakpak.engine.ModalButton
import com.shan.pakpak.engine.Scene
import com.shan.pakpak.game.Theme
import com.shan.pakpak.game.World
import com.shan.pakpak.gfx.Backgrounds
import com.shan.pakpak.gfx.Gfx
import com.shan.pakpak.gfx.Pal
import com.shan.pakpak.gfx.PixelFont
import com.shan.pakpak.gfx.Sprites
import com.shan.pakpak.net.Connectivity
import com.shan.pakpak.ui.Style
import com.shan.pakpak.ui.Ui
import kotlin.math.sin

const val HEADER_H = 24

fun skin(id: String): Skin = G.config.skins.firstOrNull { it.id == id } ?: G.config.skins.first()

/** Animated meadow (or other theme) backdrop used behind menus. */
fun drawBackdrop(g: Gfx, theme: Theme = Theme.MEADOW, dim: Float = 0f) {
    val scroll = G.time * 18f
    Backgrounds.drawSky(g, theme, scroll, G.time, World.GROUND_H.toInt())
    Backgrounds.drawGround(g, theme, scroll, World.GROUND_H.toInt())
    if (dim > 0f) g.rect(0, 0, g.w, g.h, Color.argb((dim * 255).toInt(), 20, 14, 30))
}

fun connBadge(g: Gfx, right: Int, y: Int) {
    val st = G.net.state
    val label = when {
        st == Connectivity.State.ONLINE && G.account.signedIn -> "ONLINE"
        st == Connectivity.State.ONLINE -> "ONLINE"
        else -> st.label
    }
    val color = when (st) {
        Connectivity.State.ONLINE -> Pal.GREEN
        Connectivity.State.NO_SERVER -> Pal.YELLOW
        Connectivity.State.OFFLINE -> Pal.GRAY
    }
    val w = PixelFont.width(label) + 12
    g.box(right - w, y, w, 11, Color.argb(160, 20, 16, 30), Color.argb(200, 20, 16, 30))
    g.rect(right - w + 3, y + 4, 3, 3, color)
    g.text(label, right - w + 8, y + 2, Pal.CREAM)
}

fun coinLabel(g: Gfx, x: Int, y: Int, coins: Int, color: Int = Pal.CREAM) {
    g.bitmap(Sprites.coinSmall, x, y)
    g.textShadow(coins.toString(), x + 9, y, color)
}

/** Standard top bar with a back button. Returns true when back was tapped. */
fun header(g: Gfx, ui: Ui, title: String): Boolean {
    g.rect(0, 0, g.w, HEADER_H, Color.argb(220, 40, 30, 52))
    g.rect(0, HEADER_H, g.w, 1, Pal.INK)
    val back = ui.button(4, 4, 22, 16, "<", Style.PLAIN)
    g.textShadow(title, 32, 9, Pal.CREAM)
    connBadge(g, g.w - 4, 7)
    return back
}

/** Gate for account-only features: explains exactly what is missing. */
fun requireOnline(feature: String, onReady: () -> Unit) {
    when {
        !G.api.configured -> G.scenes.alert("$feature is online-only",
            "This build has no game server configured, so online features are off. Offline play still works fully.")
        G.net.state == Connectivity.State.OFFLINE -> G.scenes.alert("You're offline",
            "$feature needs an internet connection. Endless, Adventure and your collection still work offline.")
        G.net.state == Connectivity.State.NO_SERVER -> G.scenes.alert("Server waking up",
            "The PakPak server sleeps when nobody is playing and takes about a minute to wake up. " +
                "Keep playing and try again shortly; your offline progress is safe.")
        !G.account.signedIn -> G.scenes.show(Modal("Sign in needed",
            "$feature needs a PakPak account so friends can find you and your items stay safe. Playing offline never needs an account.",
            listOf(ModalButton("Later", Style.PLAIN), ModalButton("Sign in", Style.PRIMARY) { G.scenes.push(AuthScene()) })))
        else -> onReady()
    }
}

/** A collection cell: rarity frame plus the item sprite (or a silhouette if not discovered). */
fun itemCell(g: Gfx, item: Item, owned: Boolean, x: Int, y: Int, size: Int, count: Int = 0) {
    val r = item.rarity
    val frame = if (owned) r.color else Color.rgb(80, 72, 90)
    g.box(x, y, size, size, if (owned) darken(r.color, 0.35f) else Color.rgb(44, 38, 52), frame)
    val scale = ((size - 4) / 12).coerceAtLeast(1)
    val sp = if (owned) Sprites.item(item) else Sprites.silhouette(item)
    val ox = x + (size - 12 * scale) / 2
    val oy = y + (size - 12 * scale) / 2
    if (owned) rarityAura(g, r, x, y, size)
    g.bitmap(sp, ox, oy, scale)
    if (!owned) g.textCenter("?", x + size / 2, y + size / 2 - 3, Color.rgb(140, 130, 150))
    if (owned && count > 1) {
        val s = "x$count"
        g.rect(x + size - PixelFont.width(s) - 2, y + size - 9, PixelFont.width(s) + 1, 8, Color.argb(180, 0, 0, 0))
        g.text(s, x + size - PixelFont.width(s) - 1, y + size - 8, Pal.WHITE)
    }
}

/** Animated sparkle/glow for higher rarities, so the rarity is visible at a glance. */
fun rarityAura(g: Gfx, r: Rarity, x: Int, y: Int, size: Int) {
    val t = G.time
    when (r) {
        Rarity.RARE, Rarity.ULTRA -> {
            val k = ((t * 3).toInt() % 4)
            val px = intArrayOf(2, size - 4, size - 4, 2)[k]
            val py = intArrayOf(2, 2, size - 4, size - 4)[k]
            g.rect(x + px, y + py, 2, 2, Color.WHITE)
        }
        Rarity.LEGENDARY -> {
            val a = (0.35f + 0.25f * sin(t * 4f))
            g.rect(x + 1, y + 1, size - 2, size - 2, g.alpha(Pal.GOLD, a))
            val s = ((t * 20).toInt() % (size * 2))
            for (i in 0 until size - 2) { val yy = s - i; if (yy in 1 until size - 1) g.rect(x + 1 + i, y + yy, 1, 1, g.alpha(Color.WHITE, 0.6f)) }
        }
        Rarity.CELESTIAL -> {
            g.rect(x + 1, y + 1, size - 2, size - 2, Color.rgb(16, 18, 48))
            for (i in 0 until 6) {
                val sx = ((i * 37 + (t * 6).toInt()) % (size - 4)) + 2
                val sy = ((i * 53) % (size - 4)) + 2
                if (sin(t * 5 + i) > 0) g.rect(x + sx, y + sy, 1, 1, Color.WHITE)
            }
            g.outline(x, y, size, size, Color.HSVToColor(floatArrayOf((t * 90f) % 360f, 0.5f, 1f)))
        }
        else -> {}
    }
}

fun darken(c: Int, f: Float) = Color.rgb((Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())

/** Shows the full detail popup for a collectible. */
fun showItemDetail(item: Item, owned: Int, boundCount: Int = 0) {
    val status = when {
        !item.tradeable -> "Account-bound (cannot be traded)"
        owned > 0 && boundCount >= owned -> "Locked: these copies are account-bound"
        else -> "Tradeable"
    }
    val supply = item.maxSupply?.let { "\nSupply: ${item.issued}/$it issued" } ?: ""
    val body = if (owned > 0)
        "${item.description}\n\nOwned: $owned${if (owned > 1) " (${owned - 1} duplicate${if (owned > 2) "s" else ""})" else ""}\nHow to get: ${item.acquisitionLabel}\n$status$supply"
    else "Not discovered yet.\n\nHow to get: ${item.acquisitionLabel}\n$status$supply"
    G.scenes.show(Modal(if (owned > 0) item.name else "??? (${item.rarity.label})", body, listOf(ModalButton("Close", Style.PLAIN)),
        extra = { g, _, x, y, w ->
            val size = 52
            val cx = x + (w - size) / 2
            itemCell(g, item, owned > 0, cx, y, size)
            g.textCenter(item.rarity.label.uppercase(), x + w / 2, y + size + 4, item.rarity.color)
            g.textCenter(item.id, x + w / 2, y + size + 14, Pal.MUTED)
        }, extraHeight = 78))
}

fun formatAgo(ts: Long): String {
    val d = (nowMs() - ts) / 1000
    return when {
        d < 60 -> "now"
        d < 3600 -> "${d / 60}m"
        d < 86400 -> "${d / 3600}h"
        else -> "${d / 86400}d"
    }
}

fun statusColor(status: String) = when (status) {
    "online" -> Pal.GREEN
    "recent" -> Pal.YELLOW
    else -> Pal.GRAY
}

/** Base for menu-style screens: backdrop, header, and a back action that pops the scene. */
abstract class UiScene(private val title: String) : Scene() {
    protected val top get() = HEADER_H + 6
    protected var loading = false
    protected var error: String? = null

    final override fun draw(g: Gfx, ui: Ui) {
        drawBackdrop(g, Theme.MEADOW, 0.25f)
        content(g, ui)
        if (header(g, ui, title)) G.scenes.pop()
        if (loading) {
            val dots = ".".repeat(((G.time * 3).toInt() % 3) + 1)
            g.box(g.w / 2 - 34, g.h / 2 - 8, 68, 16, Pal.PAPER, Pal.INK)
            g.textCenter("Loading$dots", g.w / 2, g.h / 2 - 3, Pal.INK)
        }
    }

    abstract fun content(g: Gfx, ui: Ui)

    /** Paper panel covering the content area. Returns its inner rect (x, y, w, h). */
    protected fun page(g: Gfx, ui: Ui): IntArray {
        val x = 6
        val y = top
        val w = g.w - 12
        val h = g.h - y - 6
        ui.panel(x, y, w, h)
        return intArrayOf(x + 6, y + 6, w - 12, h - 12)
    }

    protected fun errorLine(g: Gfx, x: Int, y: Int, w: Int): Int {
        val e = error ?: return 0
        return g.paragraph(e, x, y, w, Pal.RED_DARK)
    }
}
