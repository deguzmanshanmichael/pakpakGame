package com.shan.pakpak.scenes

import com.shan.pakpak.G
import com.shan.pakpak.data.Rarity
import com.shan.pakpak.data.WishEntry
import com.shan.pakpak.engine.Modal
import com.shan.pakpak.engine.ModalButton
import com.shan.pakpak.game.Rules
import com.shan.pakpak.game.Wish
import com.shan.pakpak.game.WishState
import com.shan.pakpak.gfx.Color
import com.shan.pakpak.gfx.Gfx
import com.shan.pakpak.gfx.Pal
import com.shan.pakpak.gfx.Particles
import com.shan.pakpak.gfx.Sprites
import com.shan.pakpak.ui.Style
import com.shan.pakpak.ui.Ui
import com.shan.pakpak.util.fmt
import kotlin.math.sin
import kotlin.random.Random

/** Tier colors, like a gacha: 3-star blue, 4-star purple, 5-star gold. */
fun tierColor(tier: Int) = when (tier) { 5 -> Pal.GOLD; 4 -> Color.rgb(190, 120, 255); else -> Color.rgb(110, 180, 255) }

/** Hiling ni Tala ("Tala's Wish"): the lucky spin for megabirds, wish skins, coins and collectibles. */
class WishScene : UiScene("Hiling ni Tala") {
    private val fx = Particles(260)
    private var busy = false
    private var results: List<WishEntry>? = null
    private var revealT = 0f
    private var bestTier = 3
    private var consolidated: Pair<Double, Double>? = null

    override fun update(dt: Float) {
        fx.update(dt)
        if (results != null) {
            val before = revealT
            revealT += dt
            if (before < 1.4f && revealT >= 1.4f) {
                G.audio.play(if (bestTier >= 5) "win" else if (bestTier == 4) "rare" else "capsule")
                fx.confetti(G.vw, if (bestTier >= 5) 90 else 30, intArrayOf(tierColor(bestTier), Color.WHITE, Pal.ORANGE))
            }
        }
    }

    override fun back(): Boolean {
        if (results != null) { results = null; return true }
        return false
    }

    override fun content(g: Gfx, ui: Ui) {
        val res = results
        if (res != null) { drawReveal(g, ui, res); return }
        val p = G.account.profile
        val w = G.config.wish
        val (x, y, ww, h) = page(g, ui).toList()
        var cy = y

        // Banner: featured megabird on a starry night sky.
        val featured = G.config.megabird(w.featured)
        val bh = 118
        g.box(x, cy, ww, bh, Color.rgb(20, 16, 60), Pal.INK)
        for (i in 0 until 26) {
            val sx = x + 4 + (i * 53) % (ww - 8)
            val sy = cy + 4 + (i * 37) % (bh - 8)
            if (sin(G.time * 2f + i) > 0.3f) g.rect(sx, sy, 1, 1, Color.WHITE)
        }
        g.text("FEATURED MEGABIRD", x + 6, cy + 6, Pal.GOLD)
        if (featured != null) {
            val bob = (sin(G.time * 3f) * 3f)
            g.disc(x + ww / 2f, cy + 54f + bob, 26f + sin(G.time * 4f) * 2f, g.alpha(featured.aura, 0.3f))
            g.bitmapCentered(Sprites.bird(featured.skin, ((G.time * 8).toInt() % 3)), x + ww / 2f, cy + 54f + bob, 4)
            g.textCenter(featured.name.uppercase(), x + ww / 2, cy + 84, Pal.WHITE, 1, outline = true)
            g.textCenter("Power: ${featured.powerName}", x + ww / 2, cy + 95, featured.aura)
        }
        g.textCenter("Pull 200 = guaranteed megabird", x + ww / 2, cy + bh - 12, Pal.CREAM)
        cy += bh + 6

        // Pity
        g.text("Megabird pity", x, cy, Pal.INK)
        g.textRight("${p.pity5}/${w.hardPity5}", x + ww, cy, Pal.INK)
        cy += 10
        ui.progress(x, cy, ww, 7, p.pity5 / w.hardPity5.toFloat(), if (p.pity5 + 1 >= w.softStart) Pal.ORANGE else Pal.GOLD)
        cy += 10
        val untilFour = (w.hardPity4 - p.pity4).coerceAtLeast(1)
        g.text("4-star or better within $untilFour pull${if (untilFour > 1) "s" else ""}", x, cy, Pal.MUTED)
        cy += 10
        g.text(if (p.guaranteedFeatured) "Next megabird: FEATURED guaranteed" else "Next megabird: 50/50 featured", x, cy,
            if (p.guaranteedFeatured) Pal.GREEN_DARK else Pal.MUTED)
        cy += 14

        // Wallet
        g.bitmap(Sprites.star, x, cy)
        g.text("${p.tokens} ${w.tokenName}${if (p.tokens == 1) "" else "s"}", x + 10, cy, Pal.INK)
        coinLabel(g, x + ww - 60, cy, p.coins, Pal.INK)
        cy += 14

        val half = (ww - 6) / 2
        if (ui.button(x, cy, half, 20, "Buy 1 (${w.tokenPrice})", Style.PLAIN, !busy)) buy(1)
        if (ui.button(x + half + 6, cy, half, 20, "Buy 10 (${w.tokenPrice * 10})", Style.PLAIN, !busy)) buy(10)
        cy += 26
        if (ui.button(x, cy, half, 26, "Wish x1", Style.BLUE, !busy && p.tokens >= 1)) pull(1)
        if (ui.button(x + half + 6, cy, half, 26, "Wish x10", Style.PRIMARY, !busy && p.tokens >= 10)) pull(10)
        cy += 32
        val third = (ww - 8) / 3
        if (ui.button(x, cy, third, 18, "Odds", Style.PLAIN)) showOdds()
        if (ui.button(x + third + 4, cy, third, 18, "History", Style.PLAIN)) showHistory()
        if (ui.button(x + (third + 4) * 2, cy, third, 18, "Megabirds", Style.GREEN)) G.scenes.replace(MegabirdScene())
        cy += 24
        g.paragraph("Tokens cost Pak Coins only, earned by playing. There is no real money in PakPak.", x, cy, ww, Pal.MUTED)
        if (!G.account.isGuest && !G.net.online) g.paragraph("Offline: account wishes need a connection.", x, y + h - 10, ww, Pal.RED_DARK)
        fx.draw(g)
    }

    private fun buy(count: Int) {
        val w = G.config.wish
        G.scenes.confirm("Buy $count ${w.tokenName}${if (count > 1) "s" else ""}?", "Costs ${w.tokenPrice * count} Pak Coins (virtual currency).", "Buy") {
            busy = true
            G.account.buyTokens(count) { ok, msg ->
                busy = false
                if (ok) G.audio.play("buy")
                G.scenes.toast(msg)
            }
        }
    }

    private fun pull(count: Int) {
        busy = true
        G.account.wish(count) { res, msg ->
            busy = false
            if (res == null) { G.scenes.alert("Couldn't wish", msg); return@wish }
            results = res
            bestTier = res.maxOf { it.tier }
            revealT = 0f
            fx.clear()
        }
    }

    private fun drawReveal(g: Gfx, ui: Ui, res: List<WishEntry>) {
        g.rect(0, 0, g.w, g.h, Color.rgb(12, 10, 36))
        for (i in 0 until 50) {
            val sx = (i * 97) % g.w
            val sy = (i * 61 + (G.time * 20).toInt()) % g.h
            g.rect(sx, sy, 1, 1, g.alpha(Color.WHITE, 0.6f))
        }
        if (revealT < 1.4f) {
            // A star falls from the sky in the color of the best prize.
            val t = (revealT / 1.4f)
            val sx = g.w * (0.8f - 0.5f * t)
            val sy = g.h * (0.05f + 0.45f * t)
            val c = tierColor(bestTier)
            for (k in 0 until 12) g.disc(sx + k * 3f, sy - k * 4f, 6f - k * 0.4f, g.alpha(c, 0.5f - k * 0.04f))
            g.disc(sx, sy, 7f, c)
            g.textCenter("Tala is listening...", g.w / 2, g.h - 60, Pal.CREAM)
            if (ui.tapped(0, 0, g.w, g.h)) revealT = 1.4f
            fx.draw(g)
            return
        }
        g.textCenter(if (bestTier == 5) "MEGABIRD!" else "Your wishes", g.w / 2, 30, tierColor(bestTier), 2, outline = true)
        val cell = 44
        val cols = if (res.size == 1) 1 else 2
        val totalW = cols * (g.w / 2 - 12)
        val startX = (g.w - totalW) / 2
        var row = 0
        for ((i, e) in res.withIndex()) {
            val col = i % cols
            row = i / cols
            val ex = startX + col * (g.w / 2 - 12)
            val ey = 60 + row * (cell + 6)
            val bw = g.w / 2 - 16
            g.box(ex, ey, if (cols == 1) g.w - 2 * startX else bw, cell, Color.rgb(30, 26, 60), tierColor(e.tier))
            drawPrizeIcon(g, e, ex + 4, ey + 4, 36)
            val tx = ex + 44
            g.text("${"*".repeat(e.tier)}", tx, ey + 5, tierColor(e.tier))
            g.text(e.name.take(14), tx, ey + 16, Pal.WHITE)
            val sub = when {
                e.duplicate -> "dupe: +${e.amount}c"
                e.type == "megabird" && e.featured -> "featured!"
                e.type == "megabird" -> "lost 50/50"
                e.type == "collectible" -> G.catalog.byId[e.id ?: ""]?.rarity?.label ?: ""
                else -> ""
            }
            g.text(sub, tx, ey + 27, Pal.CREAM)
        }
        fx.draw(g)
        val by = (60 + (row + 1) * (cell + 6) + 10).coerceAtMost(g.h - 30)
        if (ui.button(20, by, g.w - 40, 22, "OK", Style.PRIMARY)) results = null
    }

    private fun drawPrizeIcon(g: Gfx, e: WishEntry, x: Int, y: Int, size: Int) {
        when (e.type) {
            "megabird" -> G.config.megabird(e.id)?.let { m ->
                g.disc(x + size / 2f, y + size / 2f, size / 2f, g.alpha(m.aura, 0.35f))
                g.bitmapCentered(Sprites.bird(m.skin, 1), x + size / 2f, y + size / 2f, 2)
            }
            "skin" -> G.config.skins.firstOrNull { it.id == e.id }?.let { g.bitmapCentered(Sprites.bird(it, 1), x + size / 2f, y + size / 2f, 2) }
            "coins" -> g.bitmapCentered(Sprites.coinSmall, x + size / 2f, y + size / 2f, 3)
            else -> G.catalog.byId[e.id ?: ""]?.let { itemCell(g, it, true, x, y, size) }
        }
    }

    private fun showOdds() {
        val w = G.config.wish
        val cons = consolidated ?: simulate().also { consolidated = it }
        val rarePlus = Rules.odds(G.config.drops, 0).filter { it.first.ordinal >= Rarity.RARE.ordinal }
        val rpTotal = rarePlus.sumOf { it.second }
        val coinTotal = w.coinPrizes.sumOf { it.second }.toDouble()
        val body = buildString {
            append("Each wish uses 1 ${w.tokenName} (${w.tokenPrice} Pak Coins).\n\n")
            append("5-STAR MEGABIRD\n")
            append("Base chance ${pct(w.base5)}. From pull ${w.softStart} the chance rises by ${pct(w.softStep)} every pull, and pull ${w.hardPity5} is guaranteed. ")
            append("On average a megabird takes ${fmt(Wish.expectedPullsFor5(w), 0)} pulls (overall rate ${pct(cons.first)}).\n")
            append("50/50: a megabird is the featured one (${G.config.megabird(w.featured)?.name}) with ${pct(w.featuredChance)}. If not, the next megabird is guaranteed to be featured.\n\n")
            append("4-STAR\n")
            append("Base ${pct(w.base4)}, ${pct(w.base4 + w.softBonus4)} on pull ${w.softAt4}, guaranteed by pull ${w.hardPity4} (overall ${pct(cons.second)}). ")
            append("${pct(w.skinShare)} a wish-only skin (equal chance each), otherwise a Rare+ collectible: ")
            append(rarePlus.joinToString { "${it.first.label} ${fmt(it.second * 100 / rpTotal, 2)}%" }).append(".\n\n")
            append("3-STAR\n")
            append("${pct(w.coinShare)} coins (").append(w.coinPrizes.joinToString { "${it.first}: ${fmt(it.second * 100 / coinTotal, 0)}%" })
            append("), otherwise a collectible with exactly the Endless capsule odds.\n\n")
            append("Duplicates: megabird = ${w.duplicateMegabirdCoins} coins, skin = ${w.duplicateSkinCoins} coins.\n")
            append("Pity carries over between sessions and never expires. Signed in, every wish is rolled by the server.\n\n")
            append("Megabirds fly in Endless only. Megabird runs earn coins and capsules but not medals, Classic best or leaderboard places.")
        }
        G.scenes.show(Modal("${w.name}: odds & rules", body, listOf(ModalButton("Got it", Style.PRIMARY))))
    }

    private fun showHistory() {
        val list = G.account.profile.wishHistory.asReversed()
        val body = if (list.isEmpty()) "No wishes yet." else list.take(40).joinToString("\n") { e ->
            val extra = when {
                e.duplicate -> " (dupe +${e.amount}c)"
                e.type == "megabird" -> if (e.featured) " (featured, pity ${e.pity})" else " (lost 50/50, pity ${e.pity})"
                else -> ""
            }
            "${"*".repeat(e.tier)} ${e.name}$extra"
        }
        G.scenes.show(Modal("Wish history", "Total wishes: ${G.account.profile.totalPulls}\n\n$body", listOf(ModalButton("Close", Style.PLAIN))))
    }

    /** Monte Carlo of the pull rules for the overall 5-star and 4-star rates shown on the odds page. */
    private fun simulate(): Pair<Double, Double> {
        val w = G.config.wish
        val rnd = Random(7)
        var state = WishState(0, 0, false)
        var five = 0
        var four = 0
        val n = 60_000
        val megas = G.config.megabirds.map { it.id }
        val skins = G.config.skins.filter { it.wishOnly }.map { it.id }
        repeat(n) {
            val (p, next) = Wish.roll(state, w, megas, skins, rnd)
            state = next
            if (p.tier == 5) five++ else if (p.tier == 4) four++
        }
        return five.toDouble() / n to four.toDouble() / n
    }

    private fun pct(v: Double) = "${fmt(v * 100, if (v < 0.1) 2 else 1)}%"
}

/** Megabird roster: see powers, pick which bird flies in Endless. */
class MegabirdScene : UiScene("Megabirds") {
    override fun content(g: Gfx, ui: Ui) {
        val p = G.account.profile
        val (x, y, w, h) = page(g, ui).toList()
        val introH = g.paragraph("Birds with powers, won from Hiling ni Tala. Endless only.", x, y, w, Pal.MUTED)
        g.text("Mega best: ${p.bestScoreMega}", x, y + introH + 2, Pal.INK)
        val listY = y + introH + 14
        val rowH = 70
        val entries = listOf<com.shan.pakpak.data.Megabird?>(null) + G.config.megabirds
        val off = ui.beginScroll("megabirds", x, listY, w, y + h - 24 - listY, entries.size * rowH)
        for ((i, m) in entries.withIndex()) {
            val ry = listY + i * rowH - off
            val owned = m == null || p.megabirds.contains(m.id)
            val selected = (m?.id ?: "") == p.selectedMegabird
            g.box(x, ry, w - 3, rowH - 4, if (selected) Pal.CREAM else Pal.PAPER, if (selected) Pal.ORANGE else Pal.PAPER_DARK)
            val skinToDraw = m?.skin ?: skin(p.equippedSkin)
            val cyb = ry + (rowH - 4) / 2f
            if (m != null && owned) g.disc(x + 18f, cyb, 15f + sin(G.time * 4f + i) * 1.5f, g.alpha(m.aura, 0.3f))
            if (owned) g.bitmapCentered(Sprites.bird(skinToDraw, ((G.time * 8).toInt() + i) % 3), x + 18f, cyb, 2)
            else {
                g.bitmapCentered(Sprites.bird(skinToDraw, 1), x + 18f, cyb, 2, 60)
                g.textCenter("?", x + 18, cyb.toInt() - 4, Pal.MUTED)
            }
            val tx = x + 38
            if (m == null) {
                g.text("Classic bird", tx, ry + 4, Pal.INK)
                g.paragraph("Your skin. Counts for medals and the leaderboard.", tx, ry + 15, w - 3 - 38 - 54, Pal.MUTED, 9)
            } else {
                g.text(m.name, tx, ry + 4, if (owned) Pal.INK else Pal.MUTED)
                g.text(m.powerName, tx, ry + 13, darken(m.aura, 0.7f))
                g.paragraph(m.desc, tx, ry + 23, w - 3 - 38 - 54, Pal.MUTED, 8)
            }
            val bx = x + w - 56
            when {
                selected -> g.text("Flying", bx + 8, ry + 24, Pal.GREEN_DARK)
                owned -> if (ui.button(bx, ry + 19, 50, 18, "Fly", Style.GREEN)) { G.account.selectMegabird(m?.id ?: ""); G.scenes.toast("${m?.name ?: "Classic bird"} will fly in Endless") }
                else -> g.text("Locked", bx + 6, ry + 24, Pal.MUTED)
            }
        }
        ui.endScroll()
        if (ui.button(x, y + h - 20, w, 20, "Open Hiling ni Tala", Style.PRIMARY)) G.scenes.replace(WishScene())
    }
}
