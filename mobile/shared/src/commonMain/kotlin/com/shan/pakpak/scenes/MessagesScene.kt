package com.shan.pakpak.scenes

import com.shan.pakpak.util.newId
import com.shan.pakpak.Field
import com.shan.pakpak.G
import com.shan.pakpak.engine.Modal
import com.shan.pakpak.engine.ModalButton
import com.shan.pakpak.gfx.Gfx
import com.shan.pakpak.gfx.Pal
import com.shan.pakpak.gfx.PixelFont
import com.shan.pakpak.gfx.Sprites
import com.shan.pakpak.ui.Style
import com.shan.pakpak.ui.Ui
import com.shan.pakpak.json.JSONObject
import com.shan.pakpak.formatTimestamp

class MessagesScene : UiScene("Messages") {
    private var convs = emptyList<JSONObject>()
    private var refreshT = 0f

    override fun enter() = load()
    override fun update(dt: Float) { refreshT += dt; if (refreshT > 10f) load() }

    private fun load() {
        refreshT = 0f
        if (!G.account.signedIn) return
        loading = convs.isEmpty() && error == null
        G.api.get("/api/conversations") { r ->
            loading = false
            if (r.ok) {
                val a = r.obj().getJSONArray("conversations")
                convs = (0 until a.length()).map { a.getJSONObject(it) }
                G.account.unreadMessages = convs.sumOf { it.optInt("unread") }
                error = null
            } else error = r.message
        }
    }

    override fun content(g: Gfx, ui: Ui) {
        if (!G.account.signedIn) { G.scenes.pop(); return }
        val (x, y, w, h) = page(g, ui).toList()
        if (errorLine(g, x, y, w) > 0) return
        val introH = g.paragraph("Chat with friends. Be kind, and never share personal info like your address, school or phone number.", x, y, w, Pal.MUTED)
        val listY = y + introH + 4
        if (convs.isEmpty()) {
            g.paragraph("No conversations yet. Open a friend's profile and tap Message.", x, listY, w, Pal.INK)
            if (ui.button(x, listY + 30, w, 18, "Go to Friends", Style.BLUE)) G.scenes.replace(FriendsScene())
            return
        }
        val rowH = 36
        val off = ui.beginScroll("convs", x, listY, w, y + h - listY, convs.size * (rowH + 3))
        for ((i, c) in convs.withIndex()) {
            val ry = listY + i * (rowH + 3) - off
            val u = c.getJSONObject("user")
            val unread = c.optInt("unread")
            val last = (if (c.optBoolean("lastFromMe")) "You: " else "") + c.optString("lastMessage").replace('\n', ' ')
            val tapped = personRow(g, ui, x, ry, w - 3, rowH, u.optString("avatar", "pakpak"), u.optString("username"), u.optString("status"),
                last, formatAgo(c.optLong("updatedAt")), highlight = unread > 0, badge = unread)
            if (tapped && u.optLong("id") > 0) G.scenes.push(ChatScene(u.optLong("id"), u.optString("username")))
        }
        ui.endScroll()
    }
}

class ChatScene(private val userId: Long, private val username: String) : UiScene(username) {
    private val messages = ArrayList<JSONObject>()
    private var lastId = 0L
    private var pollT = 0f
    private var sending = false
    private var stickToBottom = true

    override fun enter() {
        G.account.activeChatUser = userId
        poll()
    }

    override fun exit() { G.account.activeChatUser = 0L }

    override fun update(dt: Float) {
        pollT += dt
        // Poll quickly while the chat is open; the server rate-limits generously for this.
        if (pollT > 4f) poll()
    }

    private fun poll() {
        pollT = 0f
        val q = if (lastId > 0) "?after=$lastId" else ""
        G.api.get("/api/conversations/$userId/messages$q") { r ->
            if (!r.ok) { error = r.message; return@get }
            error = null
            val a = r.obj().getJSONArray("messages")
            var gotIncoming = false
            for (i in 0 until a.length()) {
                val m = a.getJSONObject(i)
                val id = m.getLong("id")
                if (lastId == 0L || id > lastId) {
                    messages.removeAll { it.optLong("id") == id }
                    messages.add(m)
                    if (!m.optBoolean("fromMe")) gotIncoming = true
                }
            }
            if (lastId == 0L || gotIncoming || a.length() > 0) stickToBottom = true
            lastId = messages.maxOfOrNull { it.getLong("id") } ?: lastId
            if (gotIncoming) G.api.post("/api/conversations/$userId/read") { G.account.pollSoon() }
            // Refresh delivery status of my recent messages occasionally.
            if (a.length() == 0 && messages.any { it.optBoolean("fromMe") && it.optString("status") != "read" }) refreshStatuses()
        }
    }

    private fun refreshStatuses() {
        G.api.get("/api/conversations/$userId/messages") { r ->
            if (!r.ok) return@get
            val a = r.obj().getJSONArray("messages")
            for (i in 0 until a.length()) {
                val m = a.getJSONObject(i)
                val idx = messages.indexOfFirst { it.optLong("id") == m.getLong("id") }
                if (idx >= 0) messages[idx] = m
            }
        }
    }

    override fun content(g: Gfx, ui: Ui) {
        val (x, y, w, h) = page(g, ui).toList()
        val inputH = 22
        val listH = h - inputH - 4
        // Layout pass: wrap each message.
        val maxBubble = (w * 0.78f).toInt()
        val laid = messages.map { m -> m to PixelFont.wrap(m.optString("body"), maxBubble - 8) }
        val heights = laid.map { (m, lines) -> lines.size * 10 + 16 + if (m.optString("kind") == "invite") 20 else 0 }
        val total = heights.sum() + 4
        if (stickToBottom) { ui.scrollToEnd("chat$userId"); stickToBottom = false }
        val off = ui.beginScroll("chat$userId", x, y, w, listH, total)
        var cy = y + 2 - off
        if (messages.isEmpty()) g.paragraph("Say hi! Messages are only visible to you and $username.", x, y + 4, w, Pal.MUTED)
        for ((i, pair) in laid.withIndex()) {
            val (m, lines) = pair
            val hh = heights[i]
            if (cy + hh >= y && cy <= y + listH) drawBubble(g, ui, m, lines, x, cy, w, maxBubble)
            cy += hh
        }
        ui.endScroll()
        errorLine(g, x, y + listH - 12, w)
        if (ui.button(x, y + h - inputH, w, inputH, if (sending) "Sending..." else "Write a message...", Style.BLUE, !sending)) compose()
    }

    private fun drawBubble(g: Gfx, ui: Ui, m: JSONObject, lines: List<String>, x: Int, y: Int, w: Int, maxW: Int) {
        val mine = m.optBoolean("fromMe")
        val invite = m.optString("kind") == "invite"
        val bw = ((lines.maxOfOrNull { PixelFont.width(it) } ?: 10) + 8).coerceAtLeast(if (invite) 110 else 40).coerceAtMost(maxW)
        val bh = lines.size * 10 + 4 + if (invite) 20 else 0
        val bx = if (mine) x + w - bw - 4 else x
        g.box(bx, y, bw, bh, if (mine) Pal.BLUE else if (invite) Pal.GOLD else Pal.CREAM, Pal.INK)
        lines.forEachIndexed { i, l -> g.text(l, bx + 4, y + 3 + i * 10, if (mine) Pal.WHITE else Pal.INK) }
        val created = m.optLong("createdAt")
        val meta = formatTimestamp(created) + if (mine) "  ${m.optString("status")}" else ""
        if (mine) g.textRight(meta, x + w - 4, y + bh + 2, Pal.MUTED) else g.text(meta, bx, y + bh + 2, Pal.MUTED)
        if (invite) {
            val act = m.optJSONObject("meta")?.optString("activity") ?: ""
            val label = when (act) { "trade" -> "Open trading"; "adventure" -> "Play Adventure"; else -> "Play Endless" }
            if (ui.button(bx + 4, y + bh - 20, bw - 8, 16, label, Style.PRIMARY)) {
                when (act) {
                    "trade" -> G.scenes.push(TradingScene())
                    "adventure" -> G.scenes.push(LevelSelectScene())
                    else -> G.scenes.push(PlayScene.endless())
                }
            }
        } else if (!mine && ui.tapped(bx, y, bw, bh)) {
            // Tap someone else's message for safety options.
            G.scenes.show(Modal("Message from $username", m.optString("body"), listOf(
                ModalButton("Report message", Style.RED) { PlayerScene.report(JSONObject().put("id", userId).put("username", username), m.optLong("id")) },
                ModalButton("View profile", Style.PLAIN) { G.scenes.push(PlayerScene(userId)) },
                ModalButton("Close", Style.PLAIN),
            )))
        }
    }

    private fun compose() {
        G.prompt("Message $username", null, listOf(Field("", "Type here", maxLength = 500, multiline = true)), "Send") { v ->
            val text = v?.get(0)?.trim().orEmpty()
            if (text.isEmpty()) return@prompt
            sending = true
            // The client id makes retries safe: the server stores the message once.
            val body = JSONObject().put("toUserId", userId).put("body", text).put("clientMsgId", newId())
            G.api.post("/api/messages", body, retry = true) { r ->
                sending = false
                if (r.ok) { poll(); G.audio.play("click") }
                else G.scenes.alert("Not sent", r.message)
            }
        }
    }
}
