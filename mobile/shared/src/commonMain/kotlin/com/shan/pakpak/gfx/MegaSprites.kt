package com.shan.pakpak.gfx

import androidx.compose.ui.graphics.ImageBitmap
import com.shan.pakpak.data.Megabird
import kotlin.math.cos
import kotlin.math.sin

/**
 * Hand-drawn megabird sprites (22x16), each with its own silhouette. Every bird has 3 wing frames
 * and 2 "special" frames: pixels marked 'a' show on special frame 0 and 'A' on frame 1, which makes
 * crests sway, flames flicker, sparks crackle and so on.
 *
 * Grid legend: o outline, b body, l light/belly, w wing, k beak, e eye, p pupil,
 * c accent, d second accent, a/A animated accent.
 */
object MegaSprites {
    private val GRIDS: Map<String, Array<String>> = mapOf(
        // Philippine eagle: shaggy crest, hooked blue-gray beak, yellow talons.
        "haribon" to arrayOf(
            ".....a.a.a............",
            "....cAcAcAc...........",
            "...cccccccco..........",
            "..ccooolllllloo.......",
            ".oobbbbllllleeoo......",
            "obbbbbbbllllepeo......",
            "obbbbbbbbllllleeokk...",
            "obbwwwwwbbbllllooookk.",
            "obwwwwwwwbbbllllo.okkk",
            "obwwwwwwwwbbbllo...okk",
            ".owwwwwwwwbbbbo.....o.",
            "..oowwwwwbbbbbo.......",
            "....oooobbbbbo........",
            "......ooddoddo........",
            ".......d..d...........",
            "......................",
        ),
        // Minokawa: giant mythic bird with a flowing flame tail and a feather crown.
        "minokawa" to arrayOf(
            "..........c.c.c.......",
            "..........ocococ......",
            "........oobbbbboo.....",
            ".......obbbbbbeeeo....",
            "a.....obbbbbbeeppeo...",
            "Aa...obbbbbbbbeeeeokk.",
            "dAa.oowwwwwbbbbbbbokkk",
            "ddAacowwwwwwwbbbllloo.",
            "AddcccwwwwwwwwbblllO..",
            "aAddcoowwwwwwbbbllo...",
            ".aAdcc.oowwwbbbbbo....",
            "..aAdc...ooobbbbo.....",
            "...aA.......oddo......",
            "............d..d......",
            "......................",
            "......................",
        ),
        // Kalaw (hornbill): huge red-yellow casque, black body, white tail band.
        "kalaw" to arrayOf(
            "..........occcco......",
            ".........occddddco....",
            "........oobbboddddco..",
            ".......obbbbeeooookkk.",
            "......obbbbbepeokkkkko",
            ".....obbbbbbeeeokkkkko",
            ".loolbbbbbbbbbbokkkoo.",
            "lllwwwwwbbbbbbbooo....",
            "ollwwwwwwbbbbbbbo.....",
            ".olwwwwwwwbbbbbbo.....",
            "..owwwwwwwwbbbbo......",
            "...oowwwwwbbbbo.......",
            ".....oooobbbbo........",
            ".......oddoddo........",
            "........d..d..........",
            "......................",
        ),
        // Kidlat: storm bird with a zigzag lightning crest and tail.
        "kidlat" to arrayOf(
            ".......a..A...........",
            "......cAcaAc..........",
            ".......ccccco.........",
            "......oobbbbboo.......",
            ".....obbbbbbeeeo......",
            "....obbbbbbeepeo......",
            "a..obbbbbbbbeeeokk....",
            "Ac.obbwwwwwbbbbookkk..",
            ".ccowwwwwwwwbblllooo..",
            "Acoowwwwwwwwwbllo.....",
            ".a.ccowwwwwwbbbo......",
            "....A.oowwwbbbo.......",
            ".......ooobbbo........",
            ".........oddo.........",
            ".........d..d.........",
            "......................",
        ),
        // Ulap: a round, fluffy cloud dove with rosy cheeks and drifting puffs.
        "ulap" to arrayOf(
            "......................",
            "......a....A..........",
            ".....oooo.oooo........",
            "....olllloolllloo.....",
            "...ollbbbbbbbbeelo....",
            "..olbbbbbbbbbeepelo...",
            ".olbbbbbbbbbbbeeedkk..",
            "olbbwwwwwbbbbbbbbokkk.",
            "olwwwwwwwwbbbbbbbbo...",
            "olwwwwwwwwwbbbbbblo...",
            ".olwwwwwwwbbbbbbllo...",
            "..oolllllbbbbblloo....",
            "....oolloolllloo......",
            "......oo..oooo...A....",
            "..a...................",
            "......................",
        ),
        // Gintong Pipit: a tiny golden sparrow with a crown and twinkling sparkles.
        "pipit" to arrayOf(
            "...a..............A...",
            "..aAa.....c.c.c..AaA..",
            "...a......ccccc...A...",
            ".........ocdcdco......",
            "........obbbbbbeo.....",
            ".......obbbbbbepeo....",
            "......obbbbbbbbeeokk..",
            ".....obbwwwwwbbbbookkk",
            ".....owwwwwwwwblllooo.",
            ".....owwwwwwwwwlllo...",
            "......owwwwwwwbllo....",
            ".......oowwwwbblo.....",
            ".........oooobbo...a..",
            "...A........oddo..aAa.",
            "..AaA.......d..d...a..",
            "...A..................",
        ),
    )

    /** Extra palette per megabird: accent, second accent, eye white, pupil. */
    private fun extras(m: Megabird): IntArray = when (m.id) {
        "haribon" -> intArrayOf(Color.rgb(150, 110, 70), Color.rgb(255, 200, 40), Color.rgb(200, 220, 255), Color.rgb(20, 30, 60))
        "minokawa" -> intArrayOf(Color.rgb(255, 200, 40), Color.rgb(255, 90, 30), Color.rgb(255, 230, 120), Color.rgb(140, 60, 10))
        "kalaw" -> intArrayOf(Color.rgb(230, 40, 50), Color.rgb(255, 200, 40), Color.rgb(255, 255, 255), Color.rgb(160, 20, 20))
        "kidlat" -> intArrayOf(Color.rgb(255, 230, 60), Color.rgb(255, 230, 60), Color.rgb(255, 255, 255), Color.rgb(10, 20, 60))
        "ulap" -> intArrayOf(Color.rgb(255, 255, 255), Color.rgb(255, 150, 190), Color.rgb(255, 255, 255), Color.rgb(40, 50, 80))
        else -> intArrayOf(Color.rgb(255, 240, 120), Color.rgb(255, 150, 40), Color.rgb(255, 255, 255), Color.rgb(60, 40, 0))
    }

    private val cache = HashMap<String, ImageBitmap>()

    fun has(m: Megabird) = GRIDS.containsKey(m.id)

    /** [wing] 0..2 (up, mid, down), [special] 0..1. */
    fun frame(m: Megabird, wing: Int, special: Int): ImageBitmap = cache.getOrPut("${m.id}:$wing:$special") {
        val base = GRIDS[m.id] ?: GRIDS.getValue("haribon")
        val rows = base.map { it.padEnd(22, '.').take(22).toCharArray() }
        if (wing != 1) {
            val dy = if (wing == 0) -1 else 1
            val wingPx = ArrayList<Pair<Int, Int>>()
            for (y in rows.indices) for (x in rows[y].indices) if (rows[y][x] == 'w') wingPx.add(x to y)
            for ((x, y) in wingPx) rows[y][x] = 'b'
            for ((x, y) in wingPx) { val ny = y + dy; if (ny in rows.indices && rows[ny][x] != '.') rows[ny][x] = 'w' }
        }
        val c = m.colors
        val ex = extras(m)
        val pal = mapOf('o' to c[0], 'b' to c[1], 'l' to c[2], 'w' to c[3], 'k' to c[4], 'O' to c[0],
            'c' to ex[0], 'd' to ex[1], 'e' to ex[2], 'p' to ex[3])
        val pix = Pix(22, rows.size)
        for ((y, row) in rows.withIndex()) for ((x, ch) in row.withIndex()) {
            val col = when (ch) {
                'a' -> if (special == 0) ex[0] else null
                'A' -> if (special == 1) ex[if (m.id == "minokawa") 1 else 0] else null
                else -> pal[ch]
            }
            if (col != null) pix.setPixel(x, y, col)
        }
        pix.toImage()
    }
}

/** Per-megabird animated extras: auras drawn behind, sparks in front, and particle trails while flying. */
object MegaFx {
    /** Shapes behind the sprite. [s] = pixel scale (1 in game, 2-4 in menus). */
    fun drawBehind(g: Gfx, m: Megabird, x: Float, y: Float, t: Float, s: Int = 1) {
        when (m.id) {
            "haribon" -> g.disc(x, y, (11f + sin(t * 3f) * 1f) * s, g.alpha(m.aura, 0.18f))
            "minokawa" -> {
                // A soft fiery halo that breathes.
                g.disc(x, y, (13f + sin(t * 5f) * 2f) * s, g.alpha(Color.rgb(255, 120, 30), 0.18f))
                g.disc(x, y, (9f + sin(t * 7f) * 1.5f) * s, g.alpha(m.aura, 0.22f))
            }
            "kalaw" -> {
                // Magnet field rings pulsing outward.
                for (k in 0 until 2) {
                    val r = ((t * 18f + k * 9f) % 18f + 6f) * s
                    val a = 0.35f * (1f - ((t * 18f + k * 9f) % 18f) / 18f)
                    ring(g, x, y, r, g.alpha(m.aura, a), s)
                }
            }
            "kidlat" -> g.disc(x, y, (10f + if (((t * 12f).toInt() % 5) == 0) 4f else 0f) * s, g.alpha(m.aura, 0.22f))
            "ulap" -> for (k in 0 until 4) {
                val a = t * 1.2f + k * 1.57f
                g.disc(x + cos(a) * 11f * s, y + 3f * s + sin(a) * 4f * s, (3.5f + sin(t * 2f + k)) * s, g.alpha(Color.WHITE, 0.55f))
            }
            "pipit" -> g.disc(x, y, (10f + sin(t * 6f) * 1f) * s, g.alpha(Pal.GOLD, 0.2f))
        }
    }

    /** Effects drawn over the sprite. */
    fun drawFront(g: Gfx, m: Megabird, x: Float, y: Float, t: Float, s: Int = 1) {
        when (m.id) {
            "kidlat" -> if (((t * 10f).toInt() % 4) == 0) bolt(g, x + 6f * s, y - 9f * s, s, Pal.YELLOW)
            "pipit" -> for (k in 0 until 3) {
                val ph = (t * 2f + k * 0.33f) % 1f
                if (ph < 0.5f) {
                    val a = k * 2.1f + t * 0.5f
                    star(g, (x + cos(a) * 12f * s).toInt(), (y + sin(a) * 9f * s).toInt(), s, g.alpha(Color.WHITE, 1f - ph * 2f))
                }
            }
            "kalaw" -> {
                val a = t * 4f
                g.rect((x + cos(a) * 14f * s).toInt(), (y + sin(a) * 14f * s).toInt(), 2 * s, 2 * s, Color.rgb(230, 60, 70))
                g.rect((x - cos(a) * 14f * s).toInt(), (y - sin(a) * 14f * s).toInt(), 2 * s, 2 * s, Color.rgb(60, 120, 255))
            }
        }
    }

    /** Emits trail particles while flying (call every frame). */
    fun trail(fx: Particles, m: Megabird, x: Float, y: Float, t: Float) {
        val tick = (t * 60f).toInt()
        when (m.id) {
            "haribon" -> if (tick % 14 == 0) fx.spawn(x - 6f, y + 2f, -25f, 10f, 1.2f, Color.rgb(150, 110, 70), 2, 20f)
            "minokawa" -> if (tick % 2 == 0) fx.spawn(x - 9f, y + 2f, -60f - (tick % 5) * 6f, -20f + (tick % 7) * 6f, 0.5f,
                if (tick % 4 == 0) Color.rgb(255, 220, 60) else Color.rgb(255, 110, 30), 2, -30f)
            "kalaw" -> if (tick % 6 == 0) fx.spawn(x - 4f, y, -40f, (tick % 3 - 1) * 20f, 0.6f, if (tick % 12 == 0) Color.rgb(230, 60, 70) else Color.rgb(60, 120, 255), 1, 0f)
            "kidlat" -> if (tick % 3 == 0) fx.spawn(x - 6f + (tick % 5), y + (tick % 7 - 3), -50f, (tick % 9 - 4) * 15f, 0.25f,
                if (tick % 6 == 0) Color.WHITE else Pal.YELLOW, 1, 0f)
            "ulap" -> if (tick % 8 == 0) fx.spawn(x - 8f, y + 4f, -30f, 4f, 1.0f, Color.WHITE, 3, -6f)
            "pipit" -> if (tick % 5 == 0) fx.spawn(x - 6f, y + (tick % 7 - 3), -35f, 0f, 0.8f, if (tick % 10 == 0) Color.WHITE else Pal.GOLD, 1, 10f)
        }
    }

    private fun ring(g: Gfx, x: Float, y: Float, r: Float, color: Int, s: Int) {
        val n = 16
        for (i in 0 until n) {
            val a = i * 6.283f / n
            g.rect((x + cos(a) * r).toInt(), (y + sin(a) * r).toInt(), s, s, color)
        }
    }

    private fun bolt(g: Gfx, x: Float, y: Float, s: Int, color: Int) {
        val pts = intArrayOf(0, 0, -2, 3, 1, 3, -1, 7)
        for (i in 0 until pts.size / 2) g.rect((x + pts[i * 2] * s).toInt(), (y + pts[i * 2 + 1] * s).toInt(), 2 * s, 2 * s, color)
    }

    private fun star(g: Gfx, x: Int, y: Int, s: Int, color: Int) {
        g.rect(x, y - s, s, 3 * s, color)
        g.rect(x - s, y, 3 * s, s, color)
    }
}

/** Draws a megabird with its animation and extras, for menus and the wish screen. */
fun drawMegabird(g: Gfx, m: Megabird, cx: Float, cy: Float, scale: Int, t: Float, offset: Int = 0) {
    MegaFx.drawBehind(g, m, cx, cy, t, scale)
    val wing = ((t * 8f).toInt() + offset) % 3
    val special = ((t * 4f).toInt() + offset) % 2
    g.bitmapCentered(MegaSprites.frame(m, wing, special), cx, cy, scale)
    MegaFx.drawFront(g, m, cx, cy, t, scale)
}
