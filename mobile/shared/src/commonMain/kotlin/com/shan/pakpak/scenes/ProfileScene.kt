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
import com.shan.pakpak.json.JSONObject

class ProfileScene : UiScene("Profile") {
    private var tab = 0
    private var contentH = 400

    override fun enter() { G.account.refreshState() }

    override fun content(g: Gfx, ui: Ui) {
        val (x, y, w, h) = page(g, ui).toList()
        tab = ui.tabs(x, y, w, listOf("Stats", "Skins", "Account"), tab)
        when (tab) {
            0 -> stats(g, ui, x, y + 20, w, h - 20)
            1 -> skins(g, ui, x, y + 20, w, h - 20)
            else -> account(g, ui, x, y + 20, w, h - 20)
        }
    }

    private fun stats(g: Gfx, ui: Ui, x: Int, y: Int, w: Int, h: Int) {
        val p = G.account.profile
        val off = ui.beginScroll("stats", x, y, w, h, contentH)
        var cy = y - off
        g.bitmap(Sprites.bird(skin(p.equippedSkin), ((G.time * 8).toInt() % 3)), x, cy, 2)
        g.text(if (G.account.isGuest) "Guest" else p.username, x + 40, cy + 2, Pal.INK, 2)
        g.text(if (G.account.isGuest) "Offline save" else "Player ID: ${p.playerCode}", x + 40, cy + 18, Pal.MUTED)
        cy += 34
        val levelsDone = G.levels.levels.count { p.adventure[it.id]?.completed == true }
        val mastered = G.levels.levels.count { p.adventure[it.id]?.mastered == true }
        val rows = listOf(
            "Best Endless score" to p.bestScore.toString(),
            "Runs played" to p.totalRuns.toString(),
            "Obstacles passed" to p.totalPipes.toString(),
            "Distance flown" to "${p.totalDistance}m",
            "Flaps" to p.totalFlaps.toString(),
            "Adventure cleared" to "$levelsDone/${G.levels.levels.size}",
            "Adventure mastered" to "$mastered/${G.levels.levels.size}",
            "Medals" to p.medals.joinToString(" / "),
            "Collectibles" to "${p.distinctOwned}/${G.catalog.enabled.size}",
            "Total copies" to p.items.values.sum().toString(),
            G.config.economy.currencyName to p.coins.toString(),
            "Achievements" to "${p.achievements.size}/${G.config.achievements.size}",
            "Friends" to if (G.account.isGuest) "-" else p.friends.toString(),
            "Trades completed" to if (G.account.isGuest) "-" else p.tradesCompleted.toString(),
        )
        for ((k, v) in rows) {
            g.text(k, x, cy, Pal.MUTED)
            g.textRight(v, x + w - 4, cy, Pal.INK)
            cy += 11
        }
        cy += 4
        g.paragraph("Pak Coins are virtual in-game currency. They have no real-money value and can't be bought or cashed out.", x, cy, w - 4, Pal.MUTED)
        cy += 30
        contentH = cy + off - y
        ui.endScroll()
    }

    private fun skins(g: Gfx, ui: Ui, x: Int, y: Int, w: Int, h: Int) {
        val p = G.account.profile
        coinLabel(g, x, y, p.coins, Pal.INK)
        val rowH = 30
        val list = G.config.skins
        val off = ui.beginScroll("skins", x, y + 12, w, h - 12, list.size * rowH)
        for ((i, s) in list.withIndex()) {
            val ry = y + 12 + i * rowH - off
            val owned = p.skins.contains(s.id) || s.price == 0
            val equipped = p.equippedSkin == s.id
            g.box(x, ry, w, rowH - 3, if (equipped) Pal.CREAM else Pal.PAPER, Pal.PAPER_DARK)
            g.bitmap(Sprites.bird(s, ((G.time * 8).toInt() % 3)), x + 4, ry + 1, 2)
            g.text(s.name, x + 44, ry + 5, Pal.INK)
            g.text(when { owned -> "Owned"; s.wishOnly -> "From Tala Wish"; else -> "${s.price} coins" }, x + 44, ry + 15, if (s.wishOnly && !owned) Pal.BLUE_DARK else Pal.MUTED)
            val bx = x + w - 60
            when {
                equipped -> g.text("Equipped", bx + 4, ry + 10, Pal.GREEN_DARK)
                owned -> if (ui.button(bx, ry + 4, 56, 18, "Equip", Style.GREEN)) G.account.equipSkin(s.id)
                s.wishOnly -> if (ui.button(bx, ry + 4, 56, 18, "Wish", Style.BLUE)) G.scenes.push(WishScene())
                else -> if (ui.button(bx, ry + 4, 56, 18, "Buy", Style.PRIMARY, p.coins >= s.price)) {
                    G.scenes.confirm("Buy ${s.name}?", "Spend ${s.price} Pak Coins (virtual currency) on this cosmetic skin? Skins don't change gameplay.", "Buy") {
                        G.account.buySkin(s.id, s.price) { ok, msg -> if (ok) G.audio.play("buy"); G.scenes.toast(msg) }
                    }
                }
            }
        }
        ui.endScroll()
    }

    private fun account(g: Gfx, ui: Ui, x: Int, y: Int, w: Int, h: Int) {
        val acc = G.account
        var cy = y
        if (acc.isGuest) {
            cy += g.paragraph("You're playing as a guest. Everything works offline. An account adds friends, messages, trading, the online leaderboard and verified collection claims.", x, cy, w, Pal.INK) + 6
            if (ui.button(x, cy, w, 20, "Sign in / Create account", Style.BLUE)) requireOnlineNoAuth { G.scenes.push(AuthScene()) }
            cy += 28
            g.paragraph("Server: ${if (G.api.configured) G.platform.apiBaseUrl else "not configured in this build"}", x, cy, w, Pal.MUTED)
            return
        }
        val p = acc.profile
        g.text("Signed in as ${p.username}", x, cy, Pal.INK); cy += 10
        g.text("Player ID: ${p.playerCode}", x, cy, Pal.MUTED); cy += 10
        g.text("Pending runs to sync: ${acc.pendingCount}", x, cy, Pal.MUTED); cy += 14
        if (ui.button(x, cy, w, 18, "Share ID (copy)", Style.PLAIN)) { G.copyToClipboard("PakPak ID", p.playerCode); G.scenes.toast("Copied ${p.playerCode}") }
        cy += 22
        g.text("Privacy", x, cy, Pal.ORANGE_DARK); cy += 12
        val visLabel = mapOf("public" to "Everyone", "friends" to "Friends only", "private" to "Nobody")
        if (ui.button(x, cy, w, 18, "Profile visible to: ${visLabel[p.profileVisibility]}", Style.PLAIN)) {
            val next = when (p.profileVisibility) { "public" -> "friends"; "friends" -> "private"; else -> "public" }
            updateProfile(JSONObject().put("profileVisibility", next))
        }
        cy += 22
        if (ui.button(x, cy, w, 18, "Friend requests: ${if (p.friendRequests == "everyone") "Anyone" else "Nobody"}", Style.PLAIN)) {
            updateProfile(JSONObject().put("friendRequests", if (p.friendRequests == "everyone") "nobody" else "everyone"))
        }
        cy += 22
        if (ui.button(x, cy, w, 18, "Edit bio", Style.PLAIN)) {
            G.prompt("Bio", "Keep it friendly. Don't share your address, school, phone number or other personal info.",
                listOf(Field("", "Short bio", maxLength = 80, initial = p.bio)), "Save") { v -> if (v != null) updateProfile(JSONObject().put("bio", v[0])) }
        }
        cy += 22
        if (!p.guestImported && (acc.guest.totalRuns > 0 || acc.guest.items.isNotEmpty())) {
            if (ui.button(x, cy, w, 18, "Import guest progress (once)", Style.GREEN)) importGuest()
            cy += 22
        }
        if (ui.button(x, cy, w, 18, "Change password", Style.PLAIN)) changePassword()
        cy += 22
        if (ui.button(x, cy, w, 18, "Log out", Style.RED)) {
            G.scenes.confirm("Log out?", if (acc.pendingCount > 0) "${acc.pendingCount} run(s) haven't synced yet. They stay on this device and sync next time you sign in." else "You can keep playing as a guest.", "Log out", yesStyle = Style.RED) {
                acc.logout()
                G.scenes.toast("Logged out")
            }
        }
    }

    private fun requireOnlineNoAuth(then: () -> Unit) {
        when {
            !G.api.configured -> G.scenes.alert("No server", "This build has no game server configured.")
            !G.net.online -> G.scenes.alert("You're offline", "Connect to the internet to sign in.")
            else -> then()
        }
    }

    private fun updateProfile(body: JSONObject) {
        requireOnline("Profile settings") {
            loading = true
            G.api.patch("/api/me/profile", body) { r ->
                loading = false
                if (r.ok) { G.account.acct?.applyServerState(r.obj()); G.account.save(); G.scenes.toast("Saved") }
                else G.scenes.alert("Couldn't save", r.message)
            }
        }
    }

    private fun importGuest() {
        G.scenes.show(Modal("Import guest progress?",
            "Copies this device's guest collection and Adventure progress into your account, one time only. Imported items are account-bound: they can't be traded and don't count for the completion prize. Coins are capped at 1000.",
            listOf(ModalButton("Cancel", Style.PLAIN), ModalButton("Import", Style.GREEN) {
                requireOnline("Import") {
                    loading = true
                    G.account.importGuest { r ->
                        loading = false
                        if (r.ok) {
                            val i = r.obj().getJSONObject("imported")
                            G.scenes.alert("Imported!", "${i.optInt("copies")} items, ${i.optInt("levels")} levels and ${i.optInt("coins")} coins moved to your account.")
                        } else G.scenes.alert("Import failed", r.message)
                    }
                }
            })))
    }

    private fun changePassword() {
        G.prompt("Change password", "At least 8 characters with a letter and a number.",
            listOf(Field("Current password", password = true, maxLength = 128), Field("New password", password = true, maxLength = 128)), "Change") { v ->
            if (v == null) return@prompt
            requireOnline("Changing your password") {
                G.api.post("/api/auth/change-password", JSONObject().put("currentPassword", v[0]).put("newPassword", v[1])) { r ->
                    if (r.ok) G.scenes.alert("Password changed", "Other devices were signed out.") else G.scenes.alert("Couldn't change password", r.message)
                }
            }
        }
    }
}
