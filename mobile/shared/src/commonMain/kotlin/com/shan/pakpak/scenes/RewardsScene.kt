package com.shan.pakpak.scenes

import com.shan.pakpak.G
import com.shan.pakpak.game.Rules
import com.shan.pakpak.gfx.Gfx
import com.shan.pakpak.gfx.Pal
import com.shan.pakpak.gfx.Sprites
import com.shan.pakpak.ui.Ui
import com.shan.pakpak.json.JSONObject

class RewardsScene : UiScene("Rewards & Medals") {
    private var tab = 0
    private var board: List<JSONObject>? = null
    private var boardScope = "global"
    private var boardError: String? = null

    override fun content(g: Gfx, ui: Ui) {
        val (x, y, w, h) = page(g, ui).toList()
        val newTab = ui.tabs(x, y, w, listOf("Medals", "Awards", "Leaders"), tab)
        if (newTab != tab) { tab = newTab; if (tab == 2) loadBoard() }
        val cy = y + 20
        when (tab) {
            0 -> medals(g, x, cy, w)
            1 -> achievements(g, ui, x, cy, w, h - 20)
            else -> leaders(g, ui, x, cy, w, h - 20)
        }
    }

    private fun medals(g: Gfx, x: Int, y0: Int, w: Int) {
        val p = G.account.profile
        var y = y0
        g.text("Best Endless score: ${p.bestScore}", x, y, Pal.INK); y += 10
        if (!G.account.isGuest) { g.text("Leaderboard (verified): ${p.bestScoreVerified}", x, y, Pal.MUTED); y += 10 }
        y += 6
        val t = G.config.medals
        for (i in 0 until 4) {
            val has = p.medals[i] > 0
            g.bitmap(Sprites.medal(if (has) i else -1), x, y, 2)
            val name = Rules.MEDALS[i].replaceFirstChar { it.uppercase() }
            g.text(name, x + 28, y + 2, if (has) Pal.INK else Pal.MUTED)
            g.text("Score ${t[i]}+ in one run", x + 28, y + 12, Pal.MUTED)
            g.textRight("x${p.medals[i]}", x + w, y + 2, Pal.INK)
            y += 30
        }
        y += 4
        val next = t.firstOrNull { it > p.bestScore }
        y += g.paragraph(
            if (next == null) "You've reached Platinum. Alamat ka, lods!"
            else "Next goal: score $next in a single Endless run. Medals are earned by skill only, never by luck.",
            x, y, w, Pal.INK) + 6
        g.text("Medal milestone items:", x, y, Pal.ORANGE_DARK); y += 12
        for ((i, key) in Rules.MEDALS.withIndex()) {
            val item = G.catalog.items.firstOrNull { it.acquisitionKey == "medal_$key" } ?: continue
            val cx = x + i * 34
            itemCell(g, item, p.owns(item.id), cx, y, 30)
        }
    }

    private fun achievements(g: Gfx, ui: Ui, x: Int, y: Int, w: Int, h: Int) {
        val p = G.account.profile
        val list = G.config.achievements
        val got = list.count { p.achievements.containsKey(it.id) }
        g.text("Unlocked $got/${list.size}", x, y, Pal.INK)
        val rowH = 24
        val off = ui.beginScroll("ach", x, y + 12, w, h - 12, list.size * rowH)
        for ((i, a) in list.withIndex()) {
            val ry = y + 12 + i * rowH - off
            val done = p.achievements.containsKey(a.id)
            g.box(x, ry, w, rowH - 2, if (done) Pal.CREAM else Pal.PAPER_DARK, Pal.INK)
            g.bitmap(if (done) Sprites.star else Sprites.starEmpty, x + 4, ry + 7)
            g.text(a.name, x + 15, ry + 3, if (done) Pal.INK else Pal.MUTED)
            g.text(a.desc, x + 15, ry + 12, Pal.MUTED)
            g.bitmap(Sprites.coinSmall, x + w - 32, ry + 7)
            g.text("${a.reward}", x + w - 23, ry + 7, Pal.INK)
        }
        ui.endScroll()
    }

    private fun loadBoard() {
        if (!G.account.signedIn || !G.net.online) { board = null; boardError = "Sign in and go online to see the leaderboard."; return }
        loading = true
        boardError = null
        G.api.get("/api/leaderboard?scope=$boardScope") { r ->
            loading = false
            if (r.ok) {
                val arr = r.obj().getJSONArray("entries")
                board = (0 until arr.length()).map { arr.getJSONObject(it) }
            } else boardError = r.message
        }
    }

    private fun leaders(g: Gfx, ui: Ui, x: Int, y: Int, w: Int, h: Int) {
        if (!G.account.signedIn) {
            g.paragraph("The online leaderboard only counts runs that started online on a signed-in account, so scores are verified by the server.", x, y, w, Pal.INK)
            if (ui.button(x, y + 50, w, 18, "Sign in", com.shan.pakpak.ui.Style.BLUE)) G.scenes.push(AuthScene())
            return
        }
        val scopeIdx = if (boardScope == "global") 0 else 1
        val ns = ui.tabs(x, y, w, listOf("Global", "Friends"), scopeIdx)
        if (ns != scopeIdx) { boardScope = if (ns == 0) "global" else "friends"; loadBoard() }
        boardError?.let { g.paragraph(it, x, y + 20, w, Pal.RED_DARK); return }
        val list = board ?: return
        if (list.isEmpty()) { g.paragraph("No verified scores yet. Be the first!", x, y + 20, w, Pal.MUTED); return }
        val rowH = 18
        val off = ui.beginScroll("board$boardScope", x, y + 18, w, h - 18, list.size * rowH)
        for ((i, e) in list.withIndex()) {
            val ry = y + 18 + i * rowH - off
            val me = e.optLong("id") == G.account.profile.userId
            g.box(x, ry, w, rowH - 2, if (me) Pal.CREAM else Pal.PAPER, Pal.PAPER_DARK)
            g.text("#${e.optInt("rank")}", x + 3, ry + 4, Pal.MUTED)
            g.bitmap(Sprites.bird(skin(e.optString("avatar")), 1), x + 24, ry + 2)
            g.text(e.optString("username"), x + 44, ry + 4, Pal.INK)
            g.textRight(e.optInt("score").toString(), x + w - 4, ry + 4, Pal.ORANGE_DARK)
        }
        ui.endScroll()
    }
}
