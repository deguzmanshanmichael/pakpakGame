package com.shan.pakpak.scenes

import com.shan.pakpak.Field
import com.shan.pakpak.G
import com.shan.pakpak.engine.Modal
import com.shan.pakpak.engine.ModalButton
import com.shan.pakpak.gfx.Color
import com.shan.pakpak.gfx.Gfx
import com.shan.pakpak.gfx.Pal
import com.shan.pakpak.gfx.Sprites
import com.shan.pakpak.ui.Style
import com.shan.pakpak.ui.Ui
import com.shan.pakpak.json.JSONArray
import com.shan.pakpak.json.JSONObject

private fun JSONArray.list() = (0 until length()).map { getJSONObject(it) }

class FriendsScene : UiScene("Friends") {
    private var tab = 0
    private var friends = emptyList<JSONObject>()
    private var incoming = emptyList<JSONObject>()
    private var outgoing = emptyList<JSONObject>()
    private var results: List<JSONObject>? = null
    private var refreshT = 0f

    override fun enter() = load()

    override fun update(dt: Float) {
        refreshT += dt
        if (refreshT > 30f) load()
    }

    private fun load() {
        refreshT = 0f
        if (!G.account.signedIn) return
        loading = friends.isEmpty()
        G.api.get("/api/friends") { r ->
            loading = false
            if (!r.ok) { error = r.message; return@get }
            error = null
            val o = r.obj()
            friends = o.getJSONArray("friends").list()
            incoming = o.getJSONArray("incoming").list()
            outgoing = o.getJSONArray("outgoing").list()
            G.account.friendRequests = incoming.size
        }
    }

    override fun content(g: Gfx, ui: Ui) {
        if (!G.account.signedIn) { G.scenes.pop(); return }
        val (x, y, w, h) = page(g, ui).toList()
        tab = ui.tabs(x, y, w, listOf("Friends ${friends.size}", "Requests", "Find"), tab, listOf(0, incoming.size, 0))
        val cy = y + 20
        errorLine(g, x, cy, w)
        when (tab) {
            0 -> friendList(g, ui, x, cy, w, h - 20)
            1 -> requests(g, ui, x, cy, w, h - 20)
            else -> find(g, ui, x, cy, w, h - 20)
        }
    }

    private fun row(g: Gfx, ui: Ui, u: JSONObject, x: Int, y: Int, w: Int, sub: String): Boolean {
        g.box(x, y, w, 24, Pal.CREAM, Pal.PAPER_DARK)
        g.bitmap(Sprites.bird(skin(u.optString("avatar", "pakpak")), 1), x + 3, y + 6)
        val status = u.optString("status", "")
        if (status.isNotEmpty()) g.rect(x + 22, y + 4, 3, 3, statusColor(status))
        g.text(u.optString("username"), x + 28, y + 4, Pal.INK)
        g.text(sub, x + 28, y + 14, Pal.MUTED)
        return ui.tapped(x, y, w, 24)
    }

    private fun friendList(g: Gfx, ui: Ui, x: Int, y: Int, w: Int, h: Int) {
        if (friends.isEmpty()) {
            g.paragraph("No friends yet. Tap Find to search by username or Player ID (like PK-AB12CD), or share your own ID from Profile.", x, y, w, Pal.MUTED)
            if (ui.button(x, y + 40, w, 20, "Find players", Style.BLUE)) tab = 2
            return
        }
        val online = friends.count { it.optString("status") == "online" }
        g.text("$online online - tap to chat or trade", x, y, Pal.MUTED)
        val rowH = 36
        val listY = y + 12
        val off = ui.beginScroll("friends", x, listY, w, h - 12, friends.size * (rowH + 3))
        for ((i, f) in friends.withIndex()) {
            val id = f.optLong("id")
            val st = f.optString("status")
            val tapped = personRow(g, ui, x, listY + i * (rowH + 3) - off, w - 3, rowH, f.optString("avatar", "pakpak"), f.optString("username"),
                st, statusLabel(st), "best ${f.optInt("bestScore")}", highlight = st == "online")
            if (tapped && id > 0) G.scenes.push(PlayerScene(id))
        }
        ui.endScroll()
    }

    private fun requests(g: Gfx, ui: Ui, x: Int, y: Int, w: Int, h: Int) {
        var cy = y
        g.text("Incoming", x, cy, Pal.ORANGE_DARK); cy += 12
        if (incoming.isEmpty()) { g.text("None", x, cy, Pal.MUTED); cy += 12 }
        for (r in incoming) {
            personRow(g, ui, x, cy, w, 32, r.optString("avatar", "pakpak"), r.optString("username"), null,
                "wants to be friends", chevron = false, highlight = true)
            if (ui.button(x + w - 84, cy + 7, 40, 18, "Accept", Style.GREEN)) respond(r.optLong("id"), true)
            if (ui.button(x + w - 42, cy + 7, 38, 18, "No", Style.PLAIN)) respond(r.optLong("id"), false)
            cy += 35
            if (cy > y + h - 60) break
        }
        cy += 6
        g.text("Sent", x, cy, Pal.ORANGE_DARK); cy += 12
        if (outgoing.isEmpty()) g.text("None", x, cy, Pal.MUTED)
        for (r in outgoing) {
            personRow(g, ui, x, cy, w, 28, r.optString("avatar", "pakpak"), r.optString("username"), null, "request sent", chevron = false)
            if (ui.button(x + w - 54, cy + 5, 50, 18, "Cancel", Style.PLAIN)) {
                G.api.delete("/api/friends/requests/${r.optLong("id")}") { load() }
            }
            cy += 31
            if (cy > y + h - 24) break
        }
    }

    private fun respond(id: Long, accept: Boolean) {
        G.api.post("/api/friends/requests/$id/respond", JSONObject().put("accept", accept)) { r ->
            if (r.ok) { G.scenes.toast(if (accept) "Friend added!" else "Request declined"); load(); G.account.refreshState() }
            else G.scenes.alert("Couldn't respond", r.message)
        }
    }

    private fun find(g: Gfx, ui: Ui, x: Int, y: Int, w: Int, h: Int) {
        var cy = y
        if (ui.button(x, cy, w, 20, "Search username or Player ID", Style.BLUE)) {
            G.prompt("Find players", "Type at least 2 letters of a username, or a full Player ID like PK-AB12CD.",
                listOf(Field("", "username or PK-XXXXXX", maxLength = 16)), "Search") { v ->
                if (v == null || v[0].trim().length < 2) return@prompt
                loading = true
                G.api.get("/api/users/search?q=${com.shan.pakpak.util.urlEncode(v[0].trim())}") { r ->
                    loading = false
                    if (r.ok) results = r.obj().getJSONArray("users").list() else G.scenes.alert("Search failed", r.message)
                }
            }
        }
        cy += 26
        g.text("Your ID: ${G.account.profile.playerCode}", x, cy, Pal.MUTED)
        cy += 14
        val list = results ?: return
        if (list.isEmpty()) { g.text("No players found.", x, cy, Pal.MUTED); return }
        val off = ui.beginScroll("search", x, cy, w, y + h - cy, list.size * 39)
        for ((i, u) in list.withIndex()) {
            val ry = cy + i * 39 - off
            val st = u.optString("status")
            val tapped = personRow(g, ui, x, ry, w - 3, 36, u.optString("avatar", "pakpak"), u.optString("username"), st,
                if (u.optBoolean("friend")) "already friends" else u.optString("playerCode"))
            if (tapped && u.optLong("id") > 0) G.scenes.push(PlayerScene(u.optLong("id")))
        }
        ui.endScroll()
    }
}

/** Another player's profile with friend, message, trade, invite, block and report actions. */
class PlayerScene(private val userId: Long) : UiScene("Player") {
    private var data: JSONObject? = null

    override fun enter() = load()

    private fun load() {
        loading = true
        G.api.get("/api/users/$userId/profile") { r ->
            loading = false
            if (r.ok) data = r.obj() else error = r.message
        }
    }

    override fun content(g: Gfx, ui: Ui) {
        val (x, y, w, h) = page(g, ui).toList()
        if (errorLine(g, x, y, w) > 0) return
        val d = data ?: return
        val self = userId == G.account.profile.userId
        var cy = y
        // Header card
        g.box(x, cy, w, 52, Color.rgb(94, 170, 230), Pal.INK)
        g.disc(x + 26f, cy + 26f, 20f, g.alpha(Color.WHITE, 0.25f))
        g.bitmapCentered(Sprites.bird(skin(d.optString("avatar", "pakpak")), ((G.time * 8).toInt() % 3)), x + 26f, cy + 26f, 2)
        g.textShadow(fitText(d.optString("username"), w - 60), x + 52, cy + 8, Pal.WHITE)
        val st = d.optString("status")
        var px = x + 52
        px += pill(g, px, cy + 20, statusLabel(st), darken(statusColor(st), 0.8f)) + 3
        if (d.optBoolean("friend")) pill(g, px, cy + 20, "Friend", Pal.GREEN_DARK)
        g.text(d.optString("playerCode"), x + 52, cy + 36, Pal.CREAM)
        cy += 58
        if (d.optBoolean("visible")) {
            d.optString("bio").takeIf { it.isNotBlank() }?.let { cy += g.paragraph("\"$it\"", x, cy, w, Pal.INK) + 4 }
            val s = d.getJSONObject("stats")
            val m = s.getJSONObject("medals")
            val tiles = listOf(
                "Best" to s.optInt("bestScore").toString(),
                "Runs" to s.optInt("totalRuns").toString(),
                "Passed" to s.optInt("totalPipes").toString(),
                "Adventure" to "${s.optInt("adventureCompleted")}/${G.levels.levels.size}",
                "Collection" to "${s.optInt("collection")}/${s.optInt("collectionTotal")}",
                "Trades" to s.optInt("tradesCompleted").toString(),
            )
            val tw = (w - 4) / 3
            for ((i, t) in tiles.withIndex()) {
                val tx = x + (i % 3) * (tw + 2)
                val ty = cy + (i / 3) * 30
                g.box(tx, ty, tw, 28, Pal.CREAM, Pal.PAPER_DARK)
                g.textCenter(t.second, tx + tw / 2, ty + 5, Pal.INK)
                g.textCenter(t.first, tx + tw / 2, ty + 16, Pal.MUTED)
            }
            cy += 62
            g.text("Medals", x, cy + 4, Pal.MUTED)
            val medals = listOf(m.optInt("bronze"), m.optInt("silver"), m.optInt("gold"), m.optInt("platinum"))
            for ((i, n) in medals.withIndex()) {
                val mx = x + 44 + i * 40
                g.bitmap(Sprites.medal(if (n > 0) i else -1), mx, cy - 2)
                g.text("x$n", mx + 12, cy + 4, Pal.INK)
            }
            cy += 18
            g.text("Achievements ${d.optJSONArray("achievements")?.length() ?: 0}/${G.config.achievements.size}", x, cy, Pal.MUTED)
        } else {
            cy += g.paragraph("This player's profile is private. You can still add them as a friend.", x, cy, w, Pal.MUTED)
        }
        if (self) return
        val friend = d.optBoolean("friend")
        val pending = if (d.isNull("pendingRequest")) null else d.optString("pendingRequest")
        var by = y + h - 22
        val half = (w - 4) / 2
        if (ui.button(x, by, half, 20, "Block", Style.RED)) block(d)
        if (ui.button(x + half + 4, by, half, 20, "Report", Style.PLAIN)) report(d, null)
        by -= 24
        if (friend) {
            if (ui.button(x, by, half, 20, "Remove friend", Style.PLAIN)) {
                G.scenes.confirm("Remove ${d.optString("username")}?", "Open trades with them will be cancelled.", "Remove", yesStyle = Style.RED) {
                    G.api.delete("/api/friends/$userId") { load() }
                }
            }
            if (ui.button(x + half + 4, by, half, 20, "Invite...", Style.PLAIN)) invite(d)
            by -= 24
            if (ui.button(x, by, half, 20, "Message", Style.BLUE)) G.scenes.push(ChatScene(userId, d.optString("username")))
            if (ui.button(x + half + 4, by, half, 20, "Trade", Style.PRIMARY)) G.scenes.push(TradeComposeScene(userId, d.optString("username")))
        } else {
            val label = when (pending) { "outgoing" -> "Request sent"; "incoming" -> "Accept request"; else -> "Add friend" }
            if (ui.button(x, by, w, 20, label, Style.GREEN, pending != "outgoing")) {
                G.api.post("/api/friends/requests", JSONObject().put("userId", userId)) { r ->
                    if (r.ok) { G.scenes.toast(if (r.obj().optString("status") == "accepted") "You're now friends!" else "Request sent"); load() }
                    else G.scenes.alert("Couldn't add", r.message)
                }
            }
        }
    }

    private fun invite(d: JSONObject) {
        fun send(activity: String) {
            G.api.post("/api/friends/$userId/invite", JSONObject().put("activity", activity)) { r ->
                if (r.ok) G.scenes.toast("Invite sent") else G.scenes.alert("Couldn't invite", r.message)
            }
        }
        G.scenes.show(Modal("Invite ${d.optString("username")}", "Send an invite to their inbox.", listOf(
            ModalButton("Beat my Endless score", Style.PRIMARY) { send("challenge") },
            ModalButton("Let's trade", Style.PLAIN) { send("trade") },
            ModalButton("Race in Adventure", Style.PLAIN) { send("adventure") },
            ModalButton("Cancel", Style.PLAIN),
        )))
    }

    private fun block(d: JSONObject) {
        G.scenes.confirm("Block ${d.optString("username")}?", "They won't be able to message you, send requests or trade with you. This also removes them as a friend.", "Block", yesStyle = Style.RED) {
            G.api.post("/api/blocks", JSONObject().put("userId", userId)) { r ->
                if (r.ok) { G.scenes.toast("Blocked"); G.scenes.pop() } else G.scenes.alert("Couldn't block", r.message)
            }
        }
    }

    companion object {
        fun report(d: JSONObject, messageId: Long?) {
            val uid = d.optLong("id")
            fun send(category: String) {
                G.prompt("Report", "Tell us what happened (optional). Reports are reviewed by the developer.",
                    listOf(Field("", "details", maxLength = 500, multiline = true)), "Send") { v ->
                    if (v == null) return@prompt
                    val body = JSONObject().put("userId", uid).put("category", category).put("details", v[0])
                    if (messageId != null) body.put("messageId", messageId)
                    G.api.post("/api/reports", body) { r ->
                        if (r.ok) G.scenes.alert("Thanks for reporting", "The developer will review it. You can also block this player.")
                        else G.scenes.alert("Couldn't report", r.message)
                    }
                }
            }
            G.scenes.show(Modal("Report ${d.optString("username")}", "What's wrong?", listOf(
                ModalButton("Harassment or bullying", Style.PLAIN) { send("harassment") },
                ModalButton("Spam or scam", Style.PLAIN) { send("scam") },
                ModalButton("Asking for personal info", Style.PLAIN) { send("personal_info") },
                ModalButton("Inappropriate / other", Style.PLAIN) { send("inappropriate") },
                ModalButton("Cancel", Style.PLAIN),
            )))
        }
    }
}
