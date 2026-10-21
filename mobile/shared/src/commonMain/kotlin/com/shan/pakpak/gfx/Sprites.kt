package com.shan.pakpak.gfx

import androidx.compose.ui.graphics.ImageBitmap
import com.shan.pakpak.data.Item
import com.shan.pakpak.data.Rarity
import com.shan.pakpak.data.Skin
import com.shan.pakpak.game.Theme
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

/** All pixel art, drawn from character grids or procedurally, and cached as small bitmaps. */
object Sprites {
    private val cache = HashMap<String, ImageBitmap>()
    private val pixCache = HashMap<String, Pix>()

    fun grid(rows: Array<String>, colors: Map<Char, Int>, w: Int = rows.maxOf { it.length }): Pix {
        val b = Pix(w, rows.size)
        for ((y, row0) in rows.withIndex()) {
            val row = row0.padEnd(w, '.').take(w)
            for ((x, ch) in row.withIndex()) colors[ch]?.let { b.setPixel(x, y, it) }
        }
        return b
    }

    private fun img(rows: Array<String>, colors: Map<Char, Int>): ImageBitmap = grid(rows, colors).toImage()

    private inline fun cached(key: String, make: () -> Pix): ImageBitmap =
        cache.getOrPut(key) { make().also { pixCache[key] = it }.toImage() }

    // ------------------------------------------------------------------ bird
    private val BIRD = arrayOf(
        "......ooooo......",
        "....oobbbbboo....",
        "...obbbbbboeeo...",
        "..obbbbbbboepeo..",
        ".oooobbbbboeeeo..",
        "owwwwobbbbbooooo.",
        "owwwwwobbbokkkkko",
        "owwwwobbbbooooo..",
        ".oooolllllokkko..",
        "..olllllllloooo..",
        "...ooolllllo.....",
        "......ooooo......",
    )

    /** Wing frames: 0 = up, 1 = middle, 2 = down. Wing pixels shift one row inside the body. */
    private fun birdFrame(frame: Int): Array<String> {
        if (frame == 1) return BIRD
        val dy = if (frame == 0) -1 else 1
        val rows = BIRD.map { it.toCharArray() }
        val wing = ArrayList<Pair<Int, Int>>()
        for (y in BIRD.indices) for (x in BIRD[y].indices) if (BIRD[y][x] == 'w') wing.add(x to y)
        for ((x, y) in wing) rows[y][x] = 'b'
        for ((x, y) in wing) {
            val ny = y + dy
            if (ny in rows.indices && rows[ny][x] != '.') rows[ny][x] = 'w'
        }
        return rows.map { it.concatToString() }.toTypedArray()
    }

    fun bird(skin: Skin, frame: Int): ImageBitmap = cached("bird:${skin.id}:$frame") {
        val c = skin.colors
        grid(birdFrame(frame), mapOf('o' to c[0], 'b' to c[1], 'l' to c[2], 'w' to c[3], 'k' to c[4], 'e' to Color.WHITE, 'p' to Pal.INK))
    }

    // ------------------------------------------------------------------ collectibles
    private val SHAPES: Map<String, Array<String>> = mapOf(
        "feather" to arrayOf(
            ".........##.", "........#hb#", ".......#hbs#", "......#hbs#.", ".....#hbs#..", "....#hbs#...",
            "...#hbs#....", "..#hbs#.....", "..#bs#......", ".#s##.......", "#.#.........", "............"),
        "gem" to arrayOf(
            "............", "...######...", "..#hhbbbs#..", ".#hhbbbbss#.", "############", ".#bbbbbbss#.",
            "..#bbbbss#..", "...#bbbs#...", "....#bs#....", ".....##.....", "............", "............"),
        "star" to arrayOf(
            ".....##.....", ".....#h#....", "....#hb#....", "#####hbb####", "#hhhbbbbbss#", ".#bbbbbbbs#.",
            "..#bbbbbs#..", "..#bbs#bbs#.", ".#bs#..#bs#.", ".##.....##..", "............", "............"),
        "orb" to arrayOf(
            "....####....", "..##hhbb##..", ".#hhbbbbbs#.", ".#hbbbbbbs#.", "#hbbbbbbbss#", "#bbbbbbbbss#",
            "#bbbbbbbsss#", "#bbbbbbssss#", ".#bbbbssss#.", ".#sssssss#..", "..##ssss##..", "....####...."),
        "shell" to arrayOf(
            "............", "....####....", "..##bhbh##..", ".#bhbbhbbh#.", "#bhbbhbbhbb#", "#bhbbhbbhbs#",
            "#bhbshbshbs#", ".#bsbsbsbs#.", "..#sbsbss#..", "...######...", "....#ss#....", "....####...."),
        "leaf" to arrayOf(
            "..........##", "........##b#", "......##hbb#", ".....#hbbbs#", "....#hbbbs#.", "...#hbbbss#.",
            "..#hbbbss#..", "..#bbbss#...", ".#bbss##....", ".#ss##......", "#.##........", "#..........."),
        "mask" to arrayOf(
            "............", ".##########.", "#hbbbbbbbbs#", "#bbbbbbbbbs#", "#b##bbbb##s#", "#b#.#bb#.#s#",
            "#bb##bb##bs#", "#bbbbbbbbbs#", ".#bbb##bbs#.", "..#bb##bs#..", "...#ssss#...", "....####...."),
        "crown" to arrayOf(
            "............", "#....##....#", "##..#hh#..##", "#h#.#hb#.#h#", "#hb##hbb##b#", "#hbbbbbbbbs#",
            "#hbbbbbbbbs#", "#bhhbhhbhhs#", "#bbbbbbbbbs#", "############", "............", "............"),
        "key" to arrayOf(
            ".####.......", "#hbbs#......", "#b##s#......", "#bs#s#......", ".#ss#.......", "..#b#.......",
            "..#b####....", "..#bbbbs#...", "..#b####....", "..#bs#......", "..#b#.......", "...#........"),
        "fish" to arrayOf(
            "............", ".....###....", "...##hbb#...", "..#hbbbbs#.#", ".#h#bbbbbs#b", "#hbbbbbbbbsb",
            "#bbbbbbbbs#b", ".#bbbbbss#.#", "..##sssss#..", "....#####...", "............", "............"),
        "mushroom" to arrayOf(
            "...######...", "..#hhbbbs#..", ".#hbhbbbbs#.", "#hbbbbhbbbs#", "#bhbbbbbhbs#", "############",
            "...#hhhs#...", "...#hhhs#...", "...#hhss#...", "...#hhss#...", "...######...", "............"),
        "potion" to arrayOf(
            "....####....", "....#hs#....", "....####....", "...#h..s#...", "..#h....s#..", ".#hbbbbbbs#.",
            ".#bhbbbbbs#.", ".#bbbbbbss#.", ".#bbbbbsss#.", "..#bbsssss#.", "...######...", "............"),
        "bell" to arrayOf(
            ".....##.....", "....#hh#....", "...#hbbs#...", "..#hbbbbs#..", "..#hbbbbs#..", "..#bbbbbs#..",
            ".#hbbbbbbs#.", ".#bbbbbbbs#.", "############", ".....##.....", "....#ss#....", ".....##....."),
        "ring" to arrayOf(
            "....#hh#....", "...#hbbs#...", "....####....", "..##hhbb##..", ".#hb####bs#.", "#hb#....#bs#",
            "#b#......#s#", "#b#......#s#", "#bs#....#ss#", ".#bs####ss#.", "..##ssss##..", "....####...."),
        "shard" to arrayOf(
            "......#.....", ".....#h#....", "....#hbs#...", "....#hbs#...", "...#hbbbs#..", "...#hbbss#..",
            "..#hbbbss#..", "..#hbbss#...", ".#hbbss#....", ".#bbss#.....", ".#bs##......", "..##........"),
        "egg" to arrayOf(
            "....####....", "...#hhbb#...", "..#hhbbbb#..", ".#hbbbbbbs#.", ".#hbbsbbbs#.", "#hbbbbbbsbs#",
            "#bbbsbbbbss#", "#bbbbbbsbss#", "#bbbbbbbsss#", ".#bbbbbsss#.", "..##sssss#..", "....####...."),
        "flower" to arrayOf(
            "...##..##...", "..#hb##hb#..", "..#bbbbbs#..", ".##bs##bs##.", "#hbb#hh#bbs#", "#bbs#hs#bss#",
            ".##bs##bs##.", "..#bbbbbs#..", "..#bs##bs#..", "...##..##...", "............", "............"),
        "heart" to arrayOf(
            "............", ".###...###..", "#hhb#.#hbb#.", "#hbbb#bbbs#.", "#hbbbbbbbs#.", "#bbbbbbbbs#.",
            ".#bbbbbbs#..", "..#bbbbs#...", "...#bbs#....", "....#s#.....", ".....#......", "............"),
    )

    /** Procedural 12x12 masks for the three Celestial designs. */
    private fun celestialShape(shape: String): Array<String> {
        val rows = Array(12) { CharArray(12) { '.' } }
        val c = 5.5
        for (y in 0 until 12) for (x in 0 until 12) {
            val dx = x - c
            val dy = y - c
            val d = hypot(dx, dy)
            when (shape) {
                "sun" -> {
                    if (d <= 3.6) rows[y][x] = if (dx + dy < -2) 'h' else if (dx + dy > 2) 's' else 'b'
                    else if (d <= 4.4) rows[y][x] = '#'
                    else if (d <= 6.2 && (abs(dx) < 0.8 || abs(dy) < 0.8 || abs(abs(dx) - abs(dy)) < 0.8)) rows[y][x] = 'h'
                }
                "moon" -> {
                    val d2 = hypot(dx - 2.4, dy - 1.4)
                    if (d <= 5.0 && d2 > 3.8) rows[y][x] = if (d > 4.2 || d2 < 4.5) '#' else if (dx < -2) 'h' else 'b'
                }
                else -> { // comet
                    val hx = x - 8.0
                    val hy = y - 3.5
                    val hd = hypot(hx, hy)
                    if (hd <= 2.4) rows[y][x] = if (hd > 1.8) '#' else 'h'
                    else {
                        // tail toward the bottom-left
                        val along = (-(hx) + hy) / 1.414
                        val across = abs(hx + hy) / 1.414
                        if (along in 0.0..8.5 && across < 2.2 - along * 0.2) rows[y][x] = if (across < 0.8) 'b' else 's'
                    }
                }
            }
        }
        return rows.map { it.concatToString() }.toTypedArray()
    }

    private fun patterned(item: Item): Array<String> {
        val base = SHAPES[item.shape] ?: celestialShape(item.shape)
        if (item.pattern != "stripe" && item.pattern != "spots") return base
        return Array(base.size) { y ->
            val row = base[y].toCharArray()
            for (x in row.indices) {
                if (row[x] != 'b') continue
                if (item.pattern == "stripe" && y % 3 == 0) row[x] = 's'
                if (item.pattern == "spots" && (x * 7 + y * 13) % 5 == 0) row[x] = 'h'
            }
            row.concatToString()
        }
    }

    fun item(item: Item): ImageBitmap = cached("item:${item.id}") {
        val p = item.palette
        grid(patterned(item), mapOf('#' to p[0], 'b' to p[1], 's' to p[2], 'h' to p[3]), 12)
    }

    /** Dark silhouette for undiscovered items. */
    fun silhouette(item: Item): ImageBitmap = cached("sil:${item.id}") {
        item(item)
        val src = pixCache.getValue("item:${item.id}")
        val b = Pix(src.width, src.height)
        for (y in 0 until src.height) for (x in 0 until src.width) {
            if (Color.alpha(src.getPixel(x, y)) > 0) b.setPixel(x, y, Color.rgb(70, 62, 80))
        }
        b
    }

    // ------------------------------------------------------------------ pickups and props
    val coin: Array<ImageBitmap> by lazy {
        val c = mapOf('#' to Color.rgb(120, 70, 0), 'b' to Color.rgb(255, 200, 40), 'h' to Color.rgb(255, 245, 170), 's' to Color.rgb(220, 140, 20))
        arrayOf(
            img(arrayOf("..###..", ".#hbb#.", "#hbsbs#", "#hbsbs#", "#bbsbs#", ".#bss#.", "..###.."), c),
            img(arrayOf("..###..", "..#hb#.", ".#hbs#.", ".#hbs#.", ".#bbs#.", "..#s#..", "..###.."), c),
        )
    }

    val gem: ImageBitmap by lazy {
        img(arrayOf("..###..", ".#hhb#.", "#hhbbs#", "#bbbss#", ".#bss#.", "..#s#..", "...#..."),
            mapOf('#' to Color.rgb(20, 60, 110), 'b' to Color.rgb(80, 220, 255), 'h' to Color.WHITE, 's' to Color.rgb(40, 140, 220)))
    }

    val capsule: ImageBitmap by lazy {
        img(arrayOf(
            "...####...", "..#hhbb#..", ".#hbbbbbs#", ".#bbbbbbs#", "##########", ".#wwwwwwg#",
            ".#wwwwwgg#", "..#wwggg#.", "...####..."),
            mapOf('#' to Pal.INK, 'b' to Color.rgb(255, 92, 160), 'h' to Color.rgb(255, 200, 225), 's' to Color.rgb(200, 40, 110),
                'w' to Color.WHITE, 'g' to Color.rgb(200, 200, 215)))
    }

    val spikeOrb: ImageBitmap by lazy {
        img(arrayOf(
            ".....#.....", "..#..#..#..", "...#####...", "..#hbbbs#..", "###bhbbs###", "..#bbbbs#..",
            "###bbbss###", "..#bsss#...", "...#####...", "..#..#..#..", ".....#....."),
            mapOf('#' to Color.rgb(40, 20, 50), 'b' to Color.rgb(170, 90, 200), 'h' to Color.rgb(230, 190, 255), 's' to Color.rgb(110, 50, 140)))
    }

    val lock: ImageBitmap by lazy {
        img(arrayOf("..###..", ".#...#.", ".#...#.", "#######", "#yyyyy#", "#yy#yy#", "#yy#yy#", "#######"),
            mapOf('#' to Pal.INK, 'y' to Pal.GOLD))
    }

    val check: ImageBitmap by lazy {
        img(arrayOf("......##", ".....##.", "##..##..", ".####...", "..##...."), mapOf('#' to Pal.GREEN))
    }

    val star: ImageBitmap by lazy {
        img(arrayOf("...#...", "..#y#..", "##yyy##", "#yyyyy#", ".#yyy#.", ".#y#y#.", ".#...#."), mapOf('#' to Pal.INK, 'y' to Pal.GOLD))
    }
    val starEmpty: ImageBitmap by lazy {
        img(arrayOf("...#...", "..#y#..", "##yyy##", "#yyyyy#", ".#yyy#.", ".#y#y#.", ".#...#."), mapOf('#' to Pal.GRAY_DARK, 'y' to Color.rgb(90, 80, 70)))
    }

    val coinSmall: ImageBitmap get() = coin[0]

    private val MEDAL_COLORS = arrayOf(
        intArrayOf(Color.rgb(90, 45, 15), Color.rgb(205, 127, 50), Color.rgb(240, 180, 120), Color.rgb(160, 90, 30)),
        intArrayOf(Color.rgb(60, 66, 80), Color.rgb(200, 208, 220), Color.rgb(255, 255, 255), Color.rgb(140, 150, 165)),
        intArrayOf(Color.rgb(110, 70, 0), Color.rgb(255, 205, 40), Color.rgb(255, 245, 170), Color.rgb(210, 150, 0)),
        intArrayOf(Color.rgb(30, 70, 90), Color.rgb(160, 240, 255), Color.rgb(255, 255, 255), Color.rgb(90, 190, 220)),
    )
    private val MEDAL = arrayOf(
        ".rr....rr.", ".rR....Rr.", "..rR..Rr..", "...rRRr...", "...####...", "..#hhbb#..",
        ".#hhbbbbs#", ".#hbbhbbs#", ".#bbhhhbs#", ".#bbbhbss#", "..#bbsss#.", "...####...")

    /** 0 bronze .. 3 platinum, -1 for an empty slot. */
    fun medal(kind: Int): ImageBitmap = cached("medal:$kind") {
        if (kind < 0) grid(MEDAL, mapOf('#' to Pal.GRAY_DARK, 'b' to Color.rgb(90, 84, 76), 'h' to Color.rgb(110, 102, 92), 's' to Color.rgb(80, 74, 66),
            'r' to Pal.GRAY_DARK, 'R' to Color.rgb(110, 102, 92)))
        else {
            val c = MEDAL_COLORS[kind]
            grid(MEDAL, mapOf('#' to c[0], 'b' to c[1], 'h' to c[2], 's' to c[3], 'r' to Color.rgb(160, 30, 40), 'R' to Color.rgb(230, 70, 70)))
        }
    }

    val gate: ImageBitmap by lazy {
        img(arrayOf(
            "##........", "#rrrrr....", "#rwwrrrr..", "#rrrwwrrr.", "#rrrrrr...", "#rrr......", "##........",
            "##........", "##........", "##........", "##........", "##........"),
            mapOf('#' to Pal.WOOD_DARK, 'r' to Pal.RED, 'w' to Color.WHITE))
    }

    // ------------------------------------------------------------------ obstacles
    const val CAP_H = 8
    const val BODY_TILE_H = 16

    private fun themeColors(t: Theme) = when (t) {
        Theme.MEADOW -> intArrayOf(Color.rgb(26, 70, 24), Color.rgb(94, 184, 74), Color.rgb(62, 140, 50), Color.rgb(180, 236, 120))
        Theme.BEACH -> intArrayOf(Color.rgb(60, 34, 14), Color.rgb(168, 112, 62), Color.rgb(124, 78, 40), Color.rgb(214, 164, 108))
        Theme.CAVE -> intArrayOf(Color.rgb(30, 22, 36), Color.rgb(118, 102, 132), Color.rgb(84, 70, 98), Color.rgb(160, 146, 176))
        Theme.SKY -> intArrayOf(Color.rgb(30, 40, 90), Color.rgb(140, 220, 255), Color.rgb(90, 160, 230), Color.rgb(240, 252, 255))
    }

    /** Real bamboo: yellow-green culm, bulging node rings every tile, no pipe-like lip. */
    private fun bambooBody(): Pix {
        val w = 28
        val h = BODY_TILE_H
        val outline = Color.rgb(40, 74, 22)
        val base = Color.rgb(146, 196, 66)
        val shade = Color.rgb(98, 150, 42)
        val light = Color.rgb(206, 236, 130)
        val node = Color.rgb(70, 112, 34)
        val b = Pix(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val isNode = y >= h - 3
            // The culm is 26 px wide; node rings bulge 1 px out on each side.
            if (!isNode && (x == 0 || x == w - 1)) continue
            val col = when {
                x == 0 || x == w - 1 -> outline
                x == 1 || x == w - 2 -> outline
                y == h - 3 -> light
                y == h - 2 -> node
                y == h - 1 -> shade
                x in 4..6 -> light
                x >= w - 8 -> shade
                (x + y) % 11 == 0 -> shade
                else -> base
            }
            b.setPixel(x, y, col)
        }
        return b
    }

    /** Slanted cut end of a bamboo pole showing the hollow inside. */
    private fun bambooCap(top: Boolean): Pix {
        val w = 28
        val h = 10
        val outline = Color.rgb(40, 74, 22)
        val base = Color.rgb(146, 196, 66)
        val shade = Color.rgb(98, 150, 42)
        val light = Color.rgb(206, 236, 130)
        val hollow = Color.rgb(58, 92, 28)
        val rim = Color.rgb(232, 246, 180)
        val b = Pix(w, h)
        for (yy in 0 until h) for (x in 1 until w - 1) {
            val y = if (top) h - 1 - yy else yy
            val cut = x * 5 / (w - 1)
            val col: Int? = when {
                y < cut -> null
                y == cut -> outline
                y == cut + 1 -> if (x in 3..w - 4) hollow else rim
                y == cut + 2 -> if (x in 5..w - 6) hollow else rim
                x == 1 || x == w - 2 -> outline
                x in 4..6 -> light
                x >= w - 8 -> shade
                else -> base
            }
            if (col != null) b.setPixel(x, yy, col)
        }
        return b
    }

    /** Repeating vertical tile for an obstacle body (26 x 16; bamboo is 28 wide for its nodes). */
    fun body(t: Theme): ImageBitmap = cached("body:$t") {
        if (t == Theme.MEADOW) return@cached bambooBody()
        val w = 26
        val h = BODY_TILE_H
        val c = themeColors(t)
        val b = Pix(w, h)
        val rnd = Random(t.ordinal * 31 + 7)
        for (y in 0 until h) for (x in 0 until w) {
            var col = when {
                x == 0 || x == w - 1 -> c[0]
                x in 2..4 -> c[3]
                x >= w - 6 -> c[2]
                else -> c[1]
            }
            when (t) {
                Theme.MEADOW -> {}
                Theme.BEACH -> if ((x + y * 2) % 8 == 0 && x in 1 until w - 1) col = c[2]
                Theme.CAVE -> if (x in 1 until w - 1 && rnd.nextInt(9) == 0) col = c[2]
                Theme.SKY -> if (x in 1 until w - 1 && (x - y + 16) % 13 == 0) col = Color.WHITE
            }
            b.setPixel(x, y, col)
        }
        b
    }

    /** Obstacle end cap (30 x 8). [top] is the cap at the bottom end of a hanging obstacle. */
    fun cap(t: Theme, top: Boolean): ImageBitmap = cached("cap:$t:$top") {
        if (t == Theme.MEADOW) return@cached bambooCap(top)
        val w = 30
        val h = CAP_H
        val c = themeColors(t)
        val b = Pix(w, h)
        for (yy in 0 until h) for (x in 0 until w) {
            // Build the cap for a bottom obstacle (pointing up), then flip for top ones.
            val y = if (top) h - 1 - yy else yy
            val col: Int? = when (t) {
                Theme.MEADOW -> null
                Theme.BEACH -> {
                    // palm fronds
                    val frond = (y >= 3) || (abs(x - 15) < 5 + y * 2 && y >= 1) || (x % 7 in 2..4 && y >= 2)
                    if (!frond) null else if (y == h - 1 || x == 0 || x == w - 1) Color.rgb(20, 70, 30)
                    else if ((x + y) % 5 == 0) Color.rgb(140, 220, 90) else Color.rgb(64, 160, 70)
                }
                Theme.CAVE -> {
                    val peak = abs((x % 10) - 5)
                    if (y < peak - 1) null else if (y == peak - 1 || x == 0 || x == w - 1) c[0] else if (x % 10 < 4) c[3] else c[1]
                }
                Theme.SKY -> {
                    val inset = (h - 1 - y).coerceAtMost(4)
                    if (x < 4 - inset || x > w - 5 + inset) null
                    else if (x == 4 - inset || x == w - 5 + inset || y == h - 1) c[0]
                    else if (x < w / 2) c[3] else c[2]
                }
            }
            if (col != null) b.setPixel(x, yy, col)
        }
        b
    }

    fun clear() = cache.clear()
}
