package com.shan.pakpak.gfx

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.shan.pakpak.imageFromArgb
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * Original 5x7 pixel font for ASCII, plus accented Latin letters (base glyph + mark) and a
 * couple of pixel emoji, so text renders identically on Android and iOS.
 */
object PixelFont {
    const val ADVANCE = 6
    const val HEIGHT = 7
    const val LINE = 10

    // Rows are 5-bit masks, bit 4 = leftmost pixel. Index 0 = ' ' (32).
    private val GLYPHS = arrayOf(
        intArrayOf(0, 0, 0, 0, 0, 0, 0), intArrayOf(4, 4, 4, 4, 4, 0, 4), intArrayOf(10, 10, 0, 0, 0, 0, 0),
        intArrayOf(10, 10, 31, 10, 31, 10, 10), intArrayOf(4, 15, 20, 14, 5, 30, 4), intArrayOf(24, 25, 2, 4, 8, 19, 3),
        intArrayOf(12, 18, 20, 8, 21, 18, 13), intArrayOf(4, 4, 0, 0, 0, 0, 0), intArrayOf(2, 4, 8, 8, 8, 4, 2),
        intArrayOf(8, 4, 2, 2, 2, 4, 8), intArrayOf(0, 4, 21, 14, 21, 4, 0), intArrayOf(0, 4, 4, 31, 4, 4, 0),
        intArrayOf(0, 0, 0, 0, 4, 4, 8), intArrayOf(0, 0, 0, 31, 0, 0, 0), intArrayOf(0, 0, 0, 0, 0, 0, 4),
        intArrayOf(0, 1, 2, 4, 8, 16, 0),
        // 0-9
        intArrayOf(14, 17, 19, 21, 25, 17, 14), intArrayOf(4, 12, 4, 4, 4, 4, 14), intArrayOf(14, 17, 1, 2, 4, 8, 31),
        intArrayOf(31, 2, 4, 2, 1, 17, 14), intArrayOf(2, 6, 10, 18, 31, 2, 2), intArrayOf(31, 16, 30, 1, 1, 17, 14),
        intArrayOf(6, 8, 16, 30, 17, 17, 14), intArrayOf(31, 1, 2, 4, 8, 8, 8), intArrayOf(14, 17, 17, 14, 17, 17, 14),
        intArrayOf(14, 17, 17, 15, 1, 2, 12),
        // : ; < = > ? @
        intArrayOf(0, 4, 0, 0, 0, 4, 0), intArrayOf(0, 4, 0, 0, 4, 4, 8), intArrayOf(2, 4, 8, 16, 8, 4, 2),
        intArrayOf(0, 0, 31, 0, 31, 0, 0), intArrayOf(8, 4, 2, 1, 2, 4, 8), intArrayOf(14, 17, 1, 2, 4, 0, 4),
        intArrayOf(14, 17, 1, 13, 21, 21, 14),
        // A-Z
        intArrayOf(14, 17, 17, 31, 17, 17, 17), intArrayOf(30, 17, 17, 30, 17, 17, 30), intArrayOf(14, 17, 16, 16, 16, 17, 14),
        intArrayOf(28, 18, 17, 17, 17, 18, 28), intArrayOf(31, 16, 16, 30, 16, 16, 31), intArrayOf(31, 16, 16, 30, 16, 16, 16),
        intArrayOf(14, 17, 16, 23, 17, 17, 15), intArrayOf(17, 17, 17, 31, 17, 17, 17), intArrayOf(14, 4, 4, 4, 4, 4, 14),
        intArrayOf(7, 2, 2, 2, 2, 18, 12), intArrayOf(17, 18, 20, 24, 20, 18, 17), intArrayOf(16, 16, 16, 16, 16, 16, 31),
        intArrayOf(17, 27, 21, 21, 17, 17, 17), intArrayOf(17, 17, 25, 21, 19, 17, 17), intArrayOf(14, 17, 17, 17, 17, 17, 14),
        intArrayOf(30, 17, 17, 30, 16, 16, 16), intArrayOf(14, 17, 17, 17, 21, 18, 13), intArrayOf(30, 17, 17, 30, 20, 18, 17),
        intArrayOf(15, 16, 16, 14, 1, 1, 30), intArrayOf(31, 4, 4, 4, 4, 4, 4), intArrayOf(17, 17, 17, 17, 17, 17, 14),
        intArrayOf(17, 17, 17, 17, 17, 10, 4), intArrayOf(17, 17, 17, 21, 21, 21, 10), intArrayOf(17, 17, 10, 4, 10, 17, 17),
        intArrayOf(17, 17, 17, 10, 4, 4, 4), intArrayOf(31, 1, 2, 4, 8, 16, 31),
        // [ \ ] ^ _ `
        intArrayOf(14, 8, 8, 8, 8, 8, 14), intArrayOf(0, 16, 8, 4, 2, 1, 0), intArrayOf(14, 2, 2, 2, 2, 2, 14),
        intArrayOf(4, 10, 17, 0, 0, 0, 0), intArrayOf(0, 0, 0, 0, 0, 0, 31), intArrayOf(8, 4, 2, 0, 0, 0, 0),
        // a-z
        intArrayOf(0, 0, 14, 1, 15, 17, 15), intArrayOf(16, 16, 22, 25, 17, 17, 30), intArrayOf(0, 0, 14, 16, 16, 17, 14),
        intArrayOf(1, 1, 13, 19, 17, 17, 15), intArrayOf(0, 0, 14, 17, 31, 16, 14), intArrayOf(6, 9, 8, 28, 8, 8, 8),
        intArrayOf(0, 15, 17, 17, 15, 1, 14), intArrayOf(16, 16, 22, 25, 17, 17, 17), intArrayOf(4, 0, 12, 4, 4, 4, 14),
        intArrayOf(2, 0, 6, 2, 2, 18, 12), intArrayOf(16, 16, 18, 20, 24, 20, 18), intArrayOf(12, 4, 4, 4, 4, 4, 14),
        intArrayOf(0, 0, 26, 21, 21, 17, 17), intArrayOf(0, 0, 22, 25, 17, 17, 17), intArrayOf(0, 0, 14, 17, 17, 17, 14),
        intArrayOf(0, 0, 30, 17, 30, 16, 16), intArrayOf(0, 0, 13, 19, 15, 1, 1), intArrayOf(0, 0, 22, 25, 16, 16, 16),
        intArrayOf(0, 0, 14, 16, 14, 1, 30), intArrayOf(8, 8, 28, 8, 8, 9, 6), intArrayOf(0, 0, 17, 17, 17, 19, 13),
        intArrayOf(0, 0, 17, 17, 17, 10, 4), intArrayOf(0, 0, 17, 17, 21, 21, 10), intArrayOf(0, 0, 17, 10, 4, 10, 17),
        intArrayOf(0, 0, 17, 17, 15, 1, 14), intArrayOf(0, 0, 31, 2, 4, 8, 31),
        // { | } ~
        intArrayOf(2, 4, 4, 8, 4, 4, 2), intArrayOf(4, 4, 4, 4, 4, 4, 4), intArrayOf(8, 4, 4, 2, 4, 4, 8),
        intArrayOf(0, 0, 8, 21, 2, 0, 0),
    )

    private val atlas: ImageBitmap by lazy {
        val w = GLYPHS.size * ADVANCE
        val px = IntArray(w * HEIGHT)
        for ((i, g) in GLYPHS.withIndex()) {
            for (row in 0 until HEIGHT) for (col in 0 until 5) {
                if (g[row] and (16 shr col) != 0) px[row * w + i * ADVANCE + col] = Color.WHITE
            }
        }
        imageFromArgb(w, HEIGHT, px)
    }
    private val tintPaints = HashMap<Int, Paint>()
    private val solidPaints = HashMap<Int, Paint>()

    private fun paintFor(color: Int): Paint = tintPaints.getOrPut(color) {
        if (tintPaints.size > 64) tintPaints.clear()
        Paint().apply {
            filterQuality = FilterQuality.None
            colorFilter = ColorFilter.tint(ComposeColor(color), BlendMode.SrcIn)
        }
    }

    private fun solid(color: Int): Paint = solidPaints.getOrPut(color) {
        if (solidPaints.size > 64) solidPaints.clear()
        Paint().apply { this.color = ComposeColor(color) }
    }

    // Accented Latin letters are drawn as the base glyph plus a mark above it
    // (marks: 1 acute, 2 grave, 3 circumflex, 4 tilde, 5 diaeresis, 6 ring, 0 none).
    private val ACCENTS: Map<Char, Pair<Char, Int>> = buildMap {
        fun add(chars: String, base: Char, mark: Int) { for (c in chars) put(c, base to mark) }
        add("á", 'a', 1); add("à", 'a', 2); add("â", 'a', 3); add("ã", 'a', 4); add("ä", 'a', 5); add("å", 'a', 6)
        add("Á", 'A', 1); add("À", 'A', 2); add("Â", 'A', 3); add("Ã", 'A', 4); add("Ä", 'A', 5); add("Å", 'A', 6)
        add("é", 'e', 1); add("è", 'e', 2); add("ê", 'e', 3); add("ë", 'e', 5)
        add("É", 'E', 1); add("È", 'E', 2); add("Ê", 'E', 3); add("Ë", 'E', 5)
        add("í", 'i', 1); add("ì", 'i', 2); add("î", 'i', 3); add("ï", 'i', 5)
        add("Í", 'I', 1); add("Ì", 'I', 2); add("Î", 'I', 3); add("Ï", 'I', 5)
        add("ó", 'o', 1); add("ò", 'o', 2); add("ô", 'o', 3); add("õ", 'o', 4); add("ö", 'o', 5)
        add("Ó", 'O', 1); add("Ò", 'O', 2); add("Ô", 'O', 3); add("Õ", 'O', 4); add("Ö", 'O', 5)
        add("ú", 'u', 1); add("ù", 'u', 2); add("û", 'u', 3); add("ü", 'u', 5)
        add("Ú", 'U', 1); add("Ù", 'U', 2); add("Û", 'U', 3); add("Ü", 'U', 5)
        add("ñ", 'n', 4); add("Ñ", 'N', 4); add("ç", 'c', 0); add("Ç", 'C', 0)
        add("‘’", '\'', 0); add("“”", '"', 0); add("–—", '-', 0)
        add("…", '.', 0); add(" ", ' ', 0)
    }

    private fun drawMark(c: Canvas, mark: Int, x: Int, y: Int, color: Int, scale: Int) {
        val p = solid(color)
        fun dot(dx: Int, dy: Int) = c.drawRect((x + dx * scale).toFloat(), (y + dy * scale).toFloat(), (x + (dx + 1) * scale).toFloat(), (y + (dy + 1) * scale).toFloat(), p)
        when (mark) {
            1 -> { dot(2, -2); dot(3, -3) }
            2 -> { dot(2, -2); dot(1, -3) }
            3 -> { dot(1, -2); dot(2, -3); dot(3, -2) }
            4 -> { dot(0, -2); dot(1, -3); dot(2, -2); dot(3, -3) }
            5 -> { dot(1, -2); dot(3, -2) }
            6 -> { dot(2, -3); dot(1, -2); dot(3, -2) }
        }
    }

    /** Visits each character as (glyph index, accent mark, special: 0 = normal, 1 = confetti, 2 = star). */
    private inline fun forEachGlyph(text: String, visit: (glyph: Int, mark: Int, special: Int) -> Unit) {
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            var cp = ch.code
            var len = 1
            if (ch.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                cp = 0x10000 + ((ch.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00)
                len = 2
            }
            i += len
            when {
                cp in 32..126 -> visit(cp - 32, 0, 0)
                cp == 0xFE0F || cp == 0x200D -> {} // emoji variation selector / joiner
                cp == 0x1F389 || cp == 0x1F38A -> visit(0, 0, 1) // party popper / confetti ball
                cp in 0x1F300..0x1FAFF || cp in 0x2600..0x27BF -> visit(0, 0, 2) // any other emoji: a little star
                else -> {
                    val acc = ACCENTS[ch]
                    if (acc != null) visit(acc.first.code - 32, acc.second, 0) else visit('?'.code - 32, 0, 0)
                }
            }
        }
    }

    fun width(text: String, scale: Int = 1): Int {
        var w = 0
        forEachGlyph(text) { _, _, special -> w += if (special != 0) ADVANCE + 2 else ADVANCE }
        return w * scale
    }

    fun draw(c: Canvas, text: String, x: Int, y: Int, color: Int, scale: Int = 1) {
        val img = atlas
        val paint = paintFor(color)
        var cx = x
        forEachGlyph(text) { gi, mark, special ->
            if (special != 0) {
                drawEmoji(c, special, cx, y, scale)
                cx += (ADVANCE + 2) * scale
            } else {
                if (gi != 0) c.drawImageRect(img, IntOffset(gi * ADVANCE, 0), IntSize(5, HEIGHT), IntOffset(cx, y), IntSize(5 * scale, HEIGHT * scale), paint)
                if (mark != 0) drawMark(c, mark, cx, y, color, scale)
                cx += ADVANCE * scale
            }
        }
    }

    private val EMOJI_COLORS = mapOf('y' to Color.rgb(255, 200, 40), 'b' to Color.rgb(255, 120, 40), 'r' to Color.rgb(230, 60, 90), 'g' to Color.rgb(80, 200, 120))
    private val CONFETTI = arrayOf("....r..", "..y....", ".yyb..g", "yyybb..", "yyb.r..", "yb....g", "b......")
    private val STAR = arrayOf("...y...", "..yyy..", "yyyyyyy", ".yyyyy.", "..yyy..", ".yy.yy.", ".y...y.")

    private fun drawEmoji(c: Canvas, kind: Int, x: Int, y: Int, scale: Int) {
        val rows = if (kind == 1) CONFETTI else STAR
        for ((ry, row) in rows.withIndex()) for ((rx, ch) in row.withIndex()) {
            val col = EMOJI_COLORS[ch] ?: continue
            c.drawRect((x + rx * scale).toFloat(), (y + ry * scale).toFloat(), (x + (rx + 1) * scale).toFloat(), (y + (ry + 1) * scale).toFloat(), solid(col))
        }
    }

    /** Greedy word wrap. Honors explicit newlines and splits words longer than a line. */
    fun wrap(text: String, maxWidth: Int, scale: Int = 1): List<String> {
        val out = ArrayList<String>()
        for (para in text.split('\n')) {
            if (para.isEmpty()) { out.add(""); continue }
            var line = StringBuilder()
            for (word0 in para.split(' ')) {
                var word = word0
                while (width(word, scale) > maxWidth) {
                    var cut = word.length
                    while (cut > 1 && width(word.substring(0, cut), scale) > maxWidth) cut--
                    if (line.isNotEmpty()) { out.add(line.toString()); line = StringBuilder() }
                    out.add(word.substring(0, cut))
                    word = word.substring(cut)
                }
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (width(candidate, scale) <= maxWidth) {
                    line = StringBuilder(candidate)
                } else {
                    out.add(line.toString())
                    line = StringBuilder(word)
                }
            }
            out.add(line.toString())
        }
        return out
    }
}
