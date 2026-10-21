package com.shan.pakpak.scenes

import com.shan.pakpak.Field
import com.shan.pakpak.G
import com.shan.pakpak.engine.Modal
import com.shan.pakpak.engine.ModalButton
import com.shan.pakpak.gfx.Gfx
import com.shan.pakpak.gfx.Pal
import com.shan.pakpak.ui.Style
import com.shan.pakpak.ui.Ui
import com.shan.pakpak.json.JSONArray
import com.shan.pakpak.json.JSONObject

private fun JSONArray.objs() = (0 until length()).map { getJSONObject(it) }

private fun linesOf(arr: JSONArray?): List<Pair<String, Int>> = arr?.objs()?.map { it.getString("collectibleId") to it.optInt("qty", 1) } ?: emptyList()

/** Draws a row of item cells for one side of a trade. Returns height used. */
private fun drawSide(g: Gfx, ui: Ui, label: String, lines: List<Pair<String, Int>>, x: Int, y: Int, w: Int): Int {
    g.text(label, x, y, Pal.ORANGE_DARK)
    if (lines.isEmpty()) { g.text("(nothing)", x, y + 12, Pal.MUTED); return 24 }
    val cell = 28
    val per = ((w) / (cell + 3)).coerceAtLeast(1)
    for ((i, l) in lines.withIndex()) {
        val item = G.catalog.byId[l.first] ?: continue
        val cx = x + (i % per) * (cell + 3)
        val cy = y + 11 + (i / per) * (cell + 3)
        itemCell(g, item, true, cx, cy, cell, l.second)
        if (ui.tapped(cx, cy, cell, cell)) showItemDetail(item, G.account.profile.items[item.id] ?: 0, G.account.profile.boundCounts[item.id] ?: 0)
    }
    return 12 + ((lines.size + per - 1) / per) * (cell + 3)
}

class TradingScene : UiScene("Trading") {
    private var tab = 0
    private var open = emptyList<JSONObject>()
    private var history = emptyList<JSONObject>()
    private var friends = emptyList<JSONObject>()

    override fun enter() = load()

    private fun load() {
        if (!G.account.signedIn) return
        loading = true
        G.api.get("/api/trades") { r ->
            loading = false
            if (r.ok) { open = r.obj().getJSONArray("trades").objs(); error = null } else error = r.message
        }
        G.api.get("/api/trades?status=history") { r -> if (r.ok) history = r.obj().getJSONArray("trades").objs() }
        G.api.get("/api/friends") { r -> if (r.ok) friends = r.obj().getJSONArray("friends").objs() }
    }

    override fun content(g: Gfx, ui: Ui) {
        if (!G.account.signedIn) { G.scenes.pop(); return }
        val (x, y, w, h) = page(g, ui).toList()
        val waiting = open.count { needsMe(it) }
        val nt = ui.tabs(x, y, w, listOf("Open ${open.size}", "History", "New"), tab, listOf(waiting, 0, 0))
        if (nt != tab) { tab = nt; load() }
        val cy = y + 20
        if (errorLine(g, x, cy, w) > 0) return
        when (tab) {
            0 -> list(g, ui, open, x, cy, w, h - 20, "No open trades. Start one from the New tab.")
            1 -> list(g, ui, history, x, cy, w, h - 20, "No finished trades yet.")
            else -> pickFriend(g, ui, x, cy, w, h - 20)
        }
    }

    private fun needsMe(t: JSONObject): Boolean {
        val mine = t.optString("mySide")
        return t.optString("status") == "open" && !t.getJSONObject(mine).optBoolean("confirmed")
    }

    private fun list(g: Gfx, ui: Ui, trades: List<JSONObject>, x: Int, y: Int, w: Int, h: Int, empty: String) {
        if (trades.isEmpty()) { g.paragraph(empty, x, y, w, Pal.MUTED); return }
        val rowH = 30
        val off = ui.beginScroll("trades$tab", x, y, w, h, trades.size * rowH)
        for ((i, t) in trades.withIndex()) {
            val ry = y + i * rowH - off
            val mine = t.optString("mySide")
            val other = t.getJSONObject(if (mine == "proposer") "recipient" else "proposer")
            val status = t.optString("status")
            val sub = when {
                status == "open" && needsMe(t) -> "waiting for YOUR confirmation"
                status == "open" -> "waiting for ${other.optString("username")}"
                status == "failed" -> "failed: ${t.optString("failReason")}"
                else -> status
            }
            g.box(x, ry, w - 3, rowH - 3, if (needsMe(t)) Pal.CREAM else Pal.PAPER, Pal.PAPER_DARK)
            g.text("#${t.optLong("id")} with ${other.optString("username")}", x + 4, ry + 4, Pal.INK)
            g.text(sub.take(38), x + 4, ry + 15, if (needsMe(t)) Pal.ORANGE_DARK else Pal.MUTED)
            if (ui.tapped(x, ry, w, rowH - 3)) G.scenes.push(TradeDetailScene(t))
        }
        ui.endScroll()
    }

    private fun pickFriend(g: Gfx, ui: Ui, x: Int, y: Int, w: Int, h: Int) {
        g.paragraph("Pick a friend to trade with. You can trade tradeable items only; account-bound items (earned rewards, Celestials, imported guest items) stay with you.", x, y, w, Pal.MUTED)
        val ly = y + 44
        if (friends.isEmpty()) { g.paragraph("Add friends first to trade.", x, ly, w, Pal.INK); return }
        val off = ui.beginScroll("tradefriends", x, ly, w, h - 44, friends.size * 24)
        for ((i, f) in friends.withIndex()) {
            val ry = ly + i * 24 - off
            if (ui.button(x, ry, w - 4, 20, f.optString("username"), Style.PLAIN)) G.scenes.push(TradeComposeScene(f.getLong("id"), f.optString("username")))
        }
        ui.endScroll()
    }
}

class TradeDetailScene(private var trade: JSONObject) : UiScene("Trade #${trade.optLong("id")}") {
    private var busy = false

    override fun enter() = reload()

    private fun reload() {
        G.api.get("/api/trades/${trade.optLong("id")}") { r -> if (r.ok) trade = r.obj() else error = r.message }
    }

    override fun content(g: Gfx, ui: Ui) {
        val (x, y, w, h) = page(g, ui).toList()
        val t = trade
        val mine = t.optString("mySide")
        val me = t.getJSONObject(mine)
        val other = t.getJSONObject(if (mine == "proposer") "recipient" else "proposer")
        val iGive = linesOf(t.optJSONArray(if (mine == "proposer") "proposerGives" else "recipientGives"))
        val iGet = linesOf(t.optJSONArray(if (mine == "proposer") "recipientGives" else "proposerGives"))
        var cy = y
        g.text("With ${other.optString("username")}  |  ${t.optString("status").uppercase()}", x, cy, Pal.INK); cy += 12
        if (t.optString("note").isNotBlank()) cy += g.paragraph("Note: ${t.optString("note")}", x, cy, w, Pal.MUTED) + 2
        cy += drawSide(g, ui, "You give", iGive, x, cy, w) + 6
        cy += drawSide(g, ui, "You get", iGet, x, cy, w) + 8
        g.text("${me.optString("username")} (you): ${if (me.optBoolean("confirmed")) "CONFIRMED" else "not confirmed"}", x, cy, if (me.optBoolean("confirmed")) Pal.GREEN_DARK else Pal.MUTED); cy += 10
        g.text("${other.optString("username")}: ${if (other.optBoolean("confirmed")) "CONFIRMED" else "not confirmed"}", x, cy, if (other.optBoolean("confirmed")) Pal.GREEN_DARK else Pal.MUTED); cy += 14
        if (t.optString("status") == "failed") cy += g.paragraph("Failed: ${t.optString("failReason")}. No items moved.", x, cy, w, Pal.RED_DARK)
        if (t.optString("status") == "completed") cy += g.paragraph("Done! Items were swapped by the server.", x, cy, w, Pal.GREEN_DARK)
        errorLine(g, x, cy, w)
        if (t.optString("status") != "open") return
        val half = (w - 4) / 2
        val by = y + h - 20
        if (ui.button(x, by, half, 20, if (mine == "proposer") "Cancel trade" else "Decline", Style.RED, !busy)) act("cancel")
        if (!me.optBoolean("confirmed")) {
            if (ui.button(x + half + 4, by, half, 20, "Confirm", Style.GREEN, !busy)) {
                val summary = "You give: ${iGive.joinToString { "${it.second}x ${G.catalog.byId[it.first]?.name}" }}\n\nYou get: ${iGet.joinToString { "${it.second}x ${G.catalog.byId[it.first]?.name}" }}\n\nThe swap happens when both of you confirm. Trades can't be undone."
                G.scenes.confirm("Final check", summary, "Confirm", yesStyle = Style.GREEN) { act("confirm") }
            }
        } else g.text("Waiting for them...", x + half + 8, by + 6, Pal.MUTED)
    }

    private fun act(action: String) {
        busy = true
        G.api.post("/api/trades/${trade.optLong("id")}/$action") { r ->
            busy = false
            if (r.ok) {
                trade = r.obj()
                if (trade.optString("status") == "completed") { G.audio.play("win"); G.account.refreshState() }
            } else G.scenes.alert("Couldn't $action", r.message)
        }
    }
}

class TradeComposeScene(private val friendId: Long, private val friendName: String) : UiScene("Trade with $friendName") {
    private var mine = emptyMap<String, Int>()
    private var theirs = emptyMap<String, Int>()
    private val give = LinkedHashMap<String, Int>()
    private val want = LinkedHashMap<String, Int>()
    private var side = 0
    private var busy = false

    override fun enter() {
        loading = true
        G.api.get("/api/trades/inventory/${G.account.profile.userId}") { r ->
            if (r.ok) mine = r.obj().getJSONArray("items").objs().associate { it.getString("collectibleId") to it.getInt("qty") }
            G.api.get("/api/trades/inventory/$friendId") { r2 ->
                loading = false
                if (r2.ok) theirs = r2.obj().getJSONArray("items").objs().associate { it.getString("collectibleId") to it.getInt("qty") }
                else error = r2.message
            }
        }
    }

    override fun content(g: Gfx, ui: Ui) {
        val (x, y, w, h) = page(g, ui).toList()
        side = ui.tabs(x, y, w, listOf("You give (${give.values.sum()})", "You want (${want.values.sum()})"), side)
        var cy = y + 18
        g.text(if (side == 0) "Tap your items to offer them" else "Tap $friendName's items to ask", x, cy, Pal.MUTED)
        cy += 12
        val inv = if (side == 0) mine else theirs
        val sel = if (side == 0) give else want
        val ids = inv.keys.sortedWith(compareByDescending<String> { G.catalog.byId[it]?.rarity?.ordinal ?: 0 }.thenBy { it })
        val cell = 30
        val cols = (w / (cell + 3)).coerceAtLeast(1)
        val listH = h - (cy - y) - 26
        if (ids.isEmpty() && !loading) g.paragraph(if (side == 0) "You have no tradeable items yet." else "$friendName has no tradeable items.", x, cy, w, Pal.INK)
        val off = ui.beginScroll("compose$side", x, cy, w, listH, ((ids.size + cols - 1) / cols) * (cell + 3))
        for ((i, id) in ids.withIndex()) {
            val item = G.catalog.byId[id] ?: continue
            val ix = x + (i % cols) * (cell + 3)
            val iy = cy + (i / cols) * (cell + 3) - off
            val have = inv[id] ?: 0
            val chosen = sel[id] ?: 0
            itemCell(g, item, true, ix, iy, cell, have)
            if (chosen > 0) { g.outline(ix - 1, iy - 1, cell + 2, cell + 2, Pal.GREEN); g.box(ix, iy, 12, 9, Pal.GREEN, Pal.INK); g.text("$chosen", ix + 3, iy + 1, Pal.WHITE) }
            if (ui.tapped(ix, iy, cell, cell)) {
                val next = if (chosen >= have) 0 else chosen + 1
                if (next == 0) sel.remove(id) else if (sel.size < 8 || sel.containsKey(id)) sel[id] = next
                else G.scenes.toast("Up to 8 different items per side")
            }
        }
        ui.endScroll()
        val ok = give.isNotEmpty() && want.isNotEmpty() && !busy
        if (ui.button(x, y + h - 20, w, 20, if (ok) "Review offer" else "Pick items on both sides", Style.PRIMARY, ok)) review()
    }

    private fun review() {
        val fmt = { m: Map<String, Int> -> m.entries.joinToString { "${it.value}x ${G.catalog.byId[it.key]?.name} (${G.catalog.byId[it.key]?.rarity?.label})" } }
        G.scenes.show(Modal("Offer summary", "You give: ${fmt(give)}\n\nYou get: ${fmt(want)}\n\n$friendName must accept, then you both confirm the final trade.",
            listOf(ModalButton("Edit", Style.PLAIN), ModalButton("Send offer", Style.GREEN) { send() })))
    }

    private fun send() {
        G.prompt("Add a note (optional)", null, listOf(Field("", "e.g. swap duplicates?", maxLength = 120)), "Send") { v ->
            if (v == null) return@prompt
            busy = true
            val body = JSONObject().put("toUserId", friendId).put("note", v[0])
                .put("give", JSONArray(give.map { JSONObject().put("collectibleId", it.key).put("qty", it.value) }))
                .put("want", JSONArray(want.map { JSONObject().put("collectibleId", it.key).put("qty", it.value) }))
            G.api.post("/api/trades", body) { r ->
                busy = false
                if (r.ok) { G.scenes.toast("Offer sent!"); G.scenes.replace(TradeDetailScene(r.obj())) }
                else G.scenes.alert("Couldn't create trade", r.message)
            }
        }
    }
}
