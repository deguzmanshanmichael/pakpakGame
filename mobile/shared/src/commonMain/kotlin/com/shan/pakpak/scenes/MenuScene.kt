package com.shan.pakpak.scenes

import com.shan.pakpak.gfx.Color
import com.shan.pakpak.G
import com.shan.pakpak.engine.Scene
import com.shan.pakpak.game.Theme
import com.shan.pakpak.gfx.Gfx
import com.shan.pakpak.gfx.Pal
import com.shan.pakpak.gfx.Sprites
import com.shan.pakpak.ui.Style
import com.shan.pakpak.ui.Ui
import kotlin.math.sin

class SplashScene : Scene() {
    private var t = 0f
    override fun update(dt: Float) {
        t += dt
        if (t > 1.8f || (t > 0.4f && G.input.released)) G.scenes.reset(MenuScene())
    }

    override fun draw(g: Gfx, ui: Ui) {
        g.clear(Pal.SKY)
        drawBackdrop(g)
        val bob = sin(t * 6f) * 3f
        g.bitmapCentered(Sprites.bird(skin("pakpak"), ((t * 10).toInt() % 3)), g.w / 2f, g.h / 2f - 40 + bob, 4)
        g.textCenter("PAKPAK", g.w / 2, g.h / 2, Pal.GOLD, 3, outline = true)
        g.textCenter("a pixel flap game by Shan", g.w / 2, g.h / 2 + 30, Pal.CREAM, shadow = true)
    }
}

class MenuScene : Scene() {
    override fun draw(g: Gfx, ui: Ui) {
        drawBackdrop(g, Theme.MEADOW)
        val acc = G.account
        val p = acc.profile
        // Top bar
        g.rect(0, 0, g.w, 20, Color.argb(150, 30, 22, 40))
        coinLabel(g, 5, 6, p.coins)
        connBadge(g, g.w - 4, 4)
        val who = if (acc.signedIn) p.username else "Guest (offline save)"
        g.textShadow(who, 5, 24, Pal.CREAM)

        val compact = g.h < 420
        // Total block height is ~300px; centre it between the top bar and the ground on tall screens.
        val blockH = 324
        val free = g.h - 44 - 40 - blockH
        val titleY = if (compact) 56 else 64 + (free / 2).coerceIn(0, 80)
        val bob = sin(G.time * 5f) * 3f
        g.bitmapCentered(Sprites.bird(skin(p.equippedSkin), ((G.time * 9).toInt() % 3)), g.w / 2f + 58, titleY - 6 + bob, 2)
        g.textCenter("PAKPAK", g.w / 2 - 10, titleY - 10, Pal.GOLD, 3, outline = true)
        g.textCenter("Lipad, lods! Tap to flap.", g.w / 2, titleY + 16, Pal.CREAM, shadow = true)

        val bx = 14
        val bw = g.w - 28
        var y = titleY + (if (compact) 30 else 44)
        val flying = G.config.megabird(p.selectedMegabird)?.takeIf { p.megabirds.contains(it.id) }
        if (ui.button(bx, y, bw, 24, if (flying != null) "PLAY ENDLESS - ${flying.name.uppercase()}" else "PLAY ENDLESS", Style.PRIMARY)) G.scenes.push(PlayScene.endless())
        y += 28
        if (ui.button(bx, y, bw, 22, "ADVENTURE", Style.GREEN)) G.scenes.push(LevelSelectScene())
        y += 28

        val half = (bw - 6) / 2
        val rowH = 20
        val gap = 24
        val cDistinct = p.distinctOwned
        if (ui.button(bx, y, half, rowH, "Collectibles $cDistinct/${G.catalog.enabled.size}", Style.PLAIN)) G.scenes.push(CollectionScene())
        if (ui.button(bx + half + 6, y, half, rowH, "Rewards & Medals", Style.PLAIN)) G.scenes.push(RewardsScene())
        y += gap
        if (ui.button(bx, y, half, rowH, "Profile", Style.PLAIN)) G.scenes.push(ProfileScene())
        if (ui.button(bx + half + 6, y, half, rowH, "Settings", Style.PLAIN)) G.scenes.push(SettingsScene())
        y += gap
        val tokens = if (p.tokens > 0) " (${p.tokens})" else ""
        if (ui.button(bx, y, half, rowH, "* Tala Wish$tokens", Style.BLUE)) G.scenes.push(WishScene())
        if (ui.button(bx + half + 6, y, half, rowH, "Megabirds ${p.megabirds.size}/${G.config.megabirds.size}", Style.GREEN)) G.scenes.push(MegabirdScene())
        y += gap + 6

        g.textShadow("ONLINE (account)", bx, y, Pal.CREAM)
        g.rect(bx + 100, y + 3, bw - 100, 1, Pal.CREAM)
        y += 12
        val third = (bw - 8) / 3
        val onStyle = if (acc.signedIn && G.net.online) Style.BLUE else Style.DISABLED
        // Online buttons stay tappable when unavailable so the player gets an explanation.
        if (ui.button(bx, y, third, rowH, "Friends", onStyle, true, acc.friendRequests)) requireOnline("Friends") { G.scenes.push(FriendsScene()) }
        if (ui.button(bx + third + 4, y, third, rowH, "Messages", onStyle, true, acc.unreadMessages)) requireOnline("Messages") { G.scenes.push(MessagesScene()) }
        if (ui.button(bx + (third + 4) * 2, y, third, rowH, "Trading", onStyle, true, acc.tradesAwaiting)) requireOnline("Trading") { G.scenes.push(TradingScene()) }
        y += gap + 2

        val status = when {
            acc.signedIn && G.net.online -> "Signed in. Progress syncs automatically."
            acc.signedIn && acc.pendingCount > 0 -> "Offline: ${acc.pendingCount} run(s) will sync when you're back online."
            acc.signedIn -> "Signed in, offline. Playing still works."
            else -> "Offline play needs no account."
        }
        g.paragraph(status, bx, y, bw, Pal.CREAM)
        if (!acc.signedIn && G.net.online) {
            if (ui.button(bx, g.h - 66, bw, 18, "Sign in / Create account", Style.BLUE)) G.scenes.push(AuthScene())
        }
    }

    override fun back(): Boolean = false
}
