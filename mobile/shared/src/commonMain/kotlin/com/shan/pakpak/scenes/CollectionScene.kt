package com.shan.pakpak.scenes

import com.shan.pakpak.util.fmt
import com.shan.pakpak.gfx.Color
import com.shan.pakpak.G
import com.shan.pakpak.data.Rarity
import com.shan.pakpak.engine.Modal
import com.shan.pakpak.engine.ModalButton
import com.shan.pakpak.game.Rules
import com.shan.pakpak.gfx.Gfx
import com.shan.pakpak.gfx.Pal
import com.shan.pakpak.ui.Style
import com.shan.pakpak.ui.Ui

/** The collection book: browse found and undiscovered collectibles by rarity. */
class CollectionScene : UiScene("Collection Book") {
    private var filter = -1 // -1 = all rarities

    override fun content(g: Gfx, ui: Ui) {
        val p = G.account.profile
        val (x, y, w, h) = page(g, ui).toList()
        val all = G.catalog.enabled
        val owned = all.count { p.owns(it.id) }
        val pct = owned * 100f / all.size.coerceAtLeast(1)
        g.text("Collected $owned/${all.size} (${fmt(pct, 1)}%)", x, y, Pal.INK)
        ui.progress(x, y + 10, w, 7, owned / all.size.toFloat(), Pal.ORANGE)
        var cy = y + 22

        // Rarity selector: < ALL >
        if (ui.button(x, cy, 18, 16, "<", Style.PLAIN)) filter = if (filter <= -1) Rarity.entries.size - 1 else filter - 1
        if (ui.button(x + w - 18, cy, 18, 16, ">", Style.PLAIN)) filter = if (filter >= Rarity.entries.size - 1) -1 else filter + 1
        val label: String
        val color: Int
        val items = if (filter < 0) { label = "ALL RARITIES"; color = Pal.INK; all } else {
            val r = Rarity.entries[filter]
            val list = G.catalog.byRarity[r].orEmpty()
            label = "${r.label.uppercase()} ${list.count { p.owns(it.id) }}/${list.size}"
            color = darken(r.color, 0.75f)
            list
        }
        g.textCenter(label, x + w / 2, cy + 5, color)
        cy += 22

        // Per-rarity mini progress
        if (filter < 0) {
            val cw = w / 7
            for ((i, r) in Rarity.entries.withIndex()) {
                val list = G.catalog.byRarity[r].orEmpty()
                val n = list.count { p.owns(it.id) }
                ui.progress(x + i * cw + 1, cy, cw - 2, 5, if (list.isEmpty()) 0f else n / list.size.toFloat(), r.color)
                g.textCenter(r.label.take(3).uppercase(), x + i * cw + cw / 2, cy + 7, darken(r.color, 0.7f))
                g.textCenter("$n", x + i * cw + cw / 2, cy + 16, Pal.MUTED)
            }
            cy += 28
        }

        val bh = 18
        val gridBottom = y + h - bh - 6
        val cell = 30
        val cols = ((w - 2) / (cell + 3)).coerceAtLeast(1)
        val rows = (items.size + cols - 1) / cols
        val gx = x + (w - (cols * (cell + 3) - 3)) / 2
        val off = ui.beginScroll("collection$filter", x, cy, w, gridBottom - cy, rows * (cell + 3))
        for ((i, item) in items.withIndex()) {
            val ix = gx + (i % cols) * (cell + 3)
            val iy = cy + (i / cols) * (cell + 3) - off
            if (iy + cell < cy || iy > gridBottom) continue
            val n = p.items[item.id] ?: 0
            itemCell(g, item, n > 0, ix, iy, cell, n)
            if (ui.tapped(ix, iy, cell, cell)) showItemDetail(item, n, p.boundCounts[item.id] ?: 0)
        }
        ui.endScroll()

        val bw = (w - 8) / 3
        if (ui.button(x, y + h - bh, bw, bh, "Odds", Style.PLAIN)) showOdds()
        if (ui.button(x + bw + 4, y + h - bh, bw, bh, "Notice", Style.PLAIN)) showNotice()
        if (ui.button(x + (bw + 4) * 2, y + h - bh, bw, bh, "Complete", Style.PRIMARY)) G.scenes.push(CompletionScene())
    }

    private fun showOdds() {
        val d = G.config.drops
        val base = Rules.odds(d, 0)
        val deep = Rules.odds(d, 1000)
        val body = buildString {
            append("Mystery capsules appear in Endless runs (from obstacle ${d.capsuleStartPipe}, at most one every ${d.capsuleMinGap}). ")
            append("Each capsule rolls one rarity, then one design of that rarity at random.\n\n")
            append("Per capsule (start / deep runs):\n")
            for (i in base.indices) {
                val r = base[i].first
                append("${r.label}: ${fmt(base[i].second)}% / ${fmt(deep[i].second)}%\n")
            }
            append("\nGoing further in a run raises the odds of ${d.depthFrom.label} and rarer (up to x${d.depthMax}). ")
            append("There is no pity timer: a Celestial is never guaranteed.\n\n")
            append("Daily limits (reset 00:00 UTC): ${G.config.economy.dailyCapsuleCap} capsules, ${G.config.economy.dailyEndlessCoinCap} Endless coins. ")
            append("Extra capsules become coins.\n\n")
            append("Some items are earned, not found: first medals and Adventure mastery give special account-bound items. ")
            append("Celestials are account-bound. Duplicates are kept and can be traded if the design is tradeable.\n\n")
            append("Signed in: capsules are opened by the server, so items are verified. Guest/offline items stay on this device.")
        }
        G.scenes.show(Modal("Odds & rules", body, listOf(ModalButton("Got it", Style.PRIMARY))))
    }

    private fun showNotice() {
        val n = G.config.notice
        G.scenes.show(Modal(n.title, "${n.body}\n\n${n.eligibility}\n\n${n.process}", listOf(
            ModalButton("Close", Style.PLAIN), ModalButton("Completion", Style.PRIMARY) { G.scenes.push(CompletionScene()) })))
    }

    private fun fmt(v: Double) = if (v >= 1) fmt(v, 1) else fmt(v, 2)
}

/** Collection completion page with the developer's (server-configurable) Taglish notice. */
class CompletionScene : UiScene("Completion") {
    private var celebrated = false
    private val fx = com.shan.pakpak.gfx.Particles(220)
    private var submitting = false
    private var contentH = 900

    override fun update(dt: Float) {
        fx.update(dt)
        val p = G.account.profile
        val complete = p.distinctOwned >= G.catalog.enabled.size
        if (complete && !celebrated) {
            celebrated = true
            G.audio.play("win")
        }
        if (complete && fx.active < 120 && (G.time * 10).toInt() % 3 == 0) {
            fx.confetti(G.vw, 4, intArrayOf(Pal.GOLD, Pal.RED, Pal.GREEN, Pal.BLUE, Color.WHITE, Rarity.CELESTIAL.color))
        }
    }

    override fun content(g: Gfx, ui: Ui) {
        val p = G.account.profile
        val (x, y, w, h) = page(g, ui).toList()
        val total = G.catalog.enabled.size
        val owned = p.distinctOwned
        val complete = owned >= total
        val n = G.config.notice
        val buttonsH = 24
        val off = ui.beginScroll("completion", x, y, w, h - buttonsH, contentH)
        var cy = y - off
        g.textCenter(if (complete) "COMPLETE!" else "${(owned * 100 / total.coerceAtLeast(1))}% COMPLETE", x + w / 2, cy, if (complete) Pal.GREEN_DARK else Pal.ORANGE_DARK, 2)
        cy += 20
        ui.progress(x, cy, w, 8, owned / total.toFloat(), if (complete) Pal.GREEN else Pal.ORANGE)
        cy += 12
        g.text("$owned of $total designs, ${total - owned} to go", x, cy, Pal.INK)
        cy += 12
        for (r in Rarity.entries) {
            val list = G.catalog.byRarity[r].orEmpty()
            val missing = list.count { !p.owns(it.id) }
            g.rect(x, cy + 1, 5, 5, r.color)
            g.text("${r.label}: ${list.size - missing}/${list.size}${if (missing > 0) "  ($missing left)" else "  done"}", x + 8, cy, Pal.INK)
            cy += 10
        }
        if (!G.account.isGuest) {
            cy += 2
            g.text("Server-verified: ${p.verifiedDistinct}/$total", x, cy, if (p.verifiedDistinct >= total) Pal.GREEN_DARK else Pal.MUTED)
            cy += 10
        }
        cy += 8
        g.box(x - 2, cy - 4, w + 4, 2, Pal.PAPER_DARK, Pal.PAPER_DARK)
        cy += 4
        cy += g.paragraph(n.title, x, cy, w, Pal.RED_DARK) + 4
        cy += g.paragraph(n.body, x, cy, w, Pal.INK) + 8
        g.text("ELIGIBILITY", x, cy, Pal.ORANGE_DARK); cy += 10
        cy += g.paragraph(n.eligibility, x, cy, w, Pal.INK) + 6
        g.text("HOW IT WORKS", x, cy, Pal.ORANGE_DARK); cy += 10
        cy += g.paragraph(n.process, x, cy, w, Pal.INK) + 6
        g.text("CONTACT", x, cy, Pal.ORANGE_DARK); cy += 10
        cy += g.paragraph(n.contact, x, cy, w, Pal.INK) + 6
        val status = p.redemptionStatus
        if (status != null) {
            g.text("YOUR CLAIM", x, cy, Pal.ORANGE_DARK); cy += 10
            val text = when (status) {
                "submitted" -> "Submitted. Waiting for Shan's manual review. Nothing is approved yet."
                "verified" -> "Verified as eligible by Shan. The prize itself is handled manually by Shan; it is not paid by the game."
                "rejected" -> "Not approved. Reason: ${p.redemptionReason ?: "-"}"
                "fulfilled" -> "Shan marked your prize as fulfilled. Salamat sa paglalaro!"
                else -> status
            }
            cy += g.paragraph(text, x, cy, w, Pal.INK) + 6
        }
        contentH = cy + off - y
        ui.endScroll()
        fx.draw(g)

        val by = y + h - 20
        val canClaim = !G.account.isGuest && (status == null || status == "rejected")
        val label = when {
            G.account.isGuest -> "Sign in to claim"
            !canClaim -> "Claim ${status ?: ""}"
            else -> "Submit claim"
        }
        if (ui.button(x, by, w, 20, label, if (canClaim && complete) Style.PRIMARY else Style.PLAIN, !submitting)) claim(complete)
    }

    private fun claim(localComplete: Boolean) {
        if (G.account.isGuest) {
            G.scenes.alert("Account needed", "Claims are verified on the server, so you need an account and items earned while signed in. " +
                "Items found as a guest stay on this device and can't be verified.") { G.scenes.push(AuthScene()) }
            return
        }
        val status = G.account.profile.redemptionStatus
        if (status != null && status != "rejected") { G.scenes.alert("Claim already sent", "Your claim is $status. Check back here for updates."); return }
        if (!localComplete) { G.scenes.alert("Not complete yet", "Collect all ${G.catalog.enabled.size} designs first. Kaya mo 'yan!"); return }
        requireOnline("Claims") {
            G.api.get("/api/redemptions/eligibility") { r ->
                if (!r.ok) { G.scenes.alert("Couldn't check", r.message); return@get }
                if (!r.obj().optBoolean("eligible")) { G.scenes.alert("Not eligible yet", r.obj().optString("reason")); return@get }
                G.prompt("Submit claim",
                    "Give a contact handle Shan can reach you at (e.g. a username on the official page). Never share passwords, PINs, OTPs or bank logins.",
                    listOf(com.shan.pakpak.Field("Contact handle", "e.g. FB: yourname", maxLength = 80),
                        com.shan.pakpak.Field("Note (optional)", "", maxLength = 300, multiline = true)), "Submit") { v ->
                    if (v == null) return@prompt
                    submitting = true
                    G.api.post("/api/redemptions", com.shan.pakpak.json.JSONObject().put("contactHandle", v[0]).put("note", v[1])) { res ->
                        submitting = false
                        if (res.ok) {
                            G.account.profile.redemptionStatus = "submitted"
                            G.account.save()
                            G.scenes.alert("Claim submitted!", "Shan will review your account manually. This is a personal promise from the developer, not an automatic payment. Status updates appear on this page.")
                        } else G.scenes.alert("Couldn't submit", res.message)
                    }
                }
            }
        }
    }
}
