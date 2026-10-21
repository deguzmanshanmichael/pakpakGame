package com.shan.pakpak.scenes

import com.shan.pakpak.Field
import com.shan.pakpak.G
import com.shan.pakpak.engine.Modal
import com.shan.pakpak.engine.ModalButton
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
            g.paragraph("No friends yet. Use Find to search by username or Player ID (like PK-AB12CD).", x, y, w, Pal.MUTED)
            return
        }
        val off = ui.beginScroll("friends", x, y, w, h, friends.size * 27)
        for ((i, f) in friends.withIndex()) {
            val st = f.optString("status")
            val label = when (st) { "online" -> "online"; "recent" -> "active today"; else -> "offline" }
            if (row(g, ui, f, x, y + i * 27 - off, w - 3, "$label  |  best ${f.optInt("bestScore")}")) G.scenes.push(PlayerScene(f.getLong("id")))
        }
        ui.endScroll()
    }

    private fun requests(g: Gfx, ui: Ui, x: Int, y: Int, w: Int, h: Int) {
        var cy = y
        g.text("Incoming", x, cy, Pal.ORANGE_DARK); cy += 12
        if (incoming.isEmpty()) { g.text("None", x, cy, Pal.MUTED); cy += 12 }
        for (r in incoming) {
            g.box(x, cy, w, 24, Pal.CREAM, Pal.PAPER_DARK)
            g.bitmap(Sprites.bird(skin(r.optString("avatar", "pakpak")), 1), x + 3, cy + 6)
            g.text(r.optString("username"), x + 24, cy + 8, Pal.INK)
            if (ui.button(x + w - 76, cy + 3, 36, 18, "Yes", Style.GREEN)) respond(r.getLong("id"), true)
            if (ui.button(x + w - 38, cy + 3, 34, 18, "No", Style.RED)) respond(r.getLong("id"), false)
            cy += 27
            if (cy > y + h - 60) break
        }
        cy += 6
        g.text("Sent", x, cy, Pal.ORANGE_DARK); cy += 12
        if (outgoing.isEmpty()) g.text("None", x, cy, Pal.MUTED)
        for (r in outgoing) {
            g.box(x, cy, w, 22, Pal.PAPER, Pal.PAPER_DARK)
            g.text(r.optString("username"), x + 6, cy + 7, Pal.INK)
            if (ui.button(x + w - 56, cy + 2, 52, 18, "Cancel", Style.PLAIN)) {
                G.api.delete("/api/friends/requests/${r.getLong("id")}") { load() }
            }
            cy += 25
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
        val off = ui.beginScroll("search", x, cy, w, y + h - cy, list.size * 27)
        for ((i, u) in list.withIndex()) {
            val ry = cy + i * 27 - off
            if (row(g, ui, u, x, ry, w - 3, if (u.optBoolean("friend")) "already friends" else u.optString("playerCode"))) G.scenes.push(PlayerScene(u.getLong("id")))
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
        g.bitmap(Sprites.bird(skin(d.optString("avatar", "pakpak")), ((G.time * 8).toInt() % 3)), x, cy, 2)
        g.text(d.optString("username"), x + 40, cy + 2, Pal.INK, 2)
        val st = d.optString("status")
        g.rect(x + 40, cy + 20, 4, 4, statusColor(st))
        g.text("${st.ifEmpty { "offline" }}  ${d.optString("playerCode")}", x + 47, cy + 19, Pal.MUTED)
        cy += 34
        if (d.optBoolean("visible")) {
            d.optString("bio").takeIf { it.isNotBlank() }?.let { cy += g.paragraph("\"$it\"", x, cy, w, Pal.INK) + 4 }
            val s = d.getJSONObject("stats")
            val m = s.getJSONObject("medals")
            val rows = listOf(
                "Best score" to s.optInt("bestScore").toString(),
                "Runs" to s.optInt("totalRuns").toString(),
                "Obstacles passed" to s.optInt("totalPipes").toString(),
                "Adventure cleared" to "${s.optInt("adventureCompleted")}/${G.levels.levels.size}",
                "Collection" to "${s.optInt("collection")}/${s.optInt("collectionTotal")}",
                "Medals B/S/G/P" to "${m.optInt("bronze")}/${m.optInt("silver")}/${m.optInt("gold")}/${m.optInt("platinum")}",
                "Trades" to s.optInt("tradesCompleted").toString(),
                "Achievements" to "${d.optJSONArray("achievements")?.length() ?: 0}/${G.config.achievements.size}",
            )
            for ((k, v) in rows) { g.text(k, x, cy, Pal.MUTED); g.textRight(v, x + w, cy, Pal.INK); cy += 11 }
        } else {
            cy += g.paragraph("This player's profile is private.", x, cy, w, Pal.MUTED)
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
