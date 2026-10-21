package com.shan.pakpak.scenes

import com.shan.pakpak.gfx.Color
import com.shan.pakpak.G
import com.shan.pakpak.data.Level
import com.shan.pakpak.engine.Modal
import com.shan.pakpak.engine.ModalButton
import com.shan.pakpak.gfx.Gfx
import com.shan.pakpak.gfx.Pal
import com.shan.pakpak.gfx.Sprites
import com.shan.pakpak.ui.Style
import com.shan.pakpak.ui.Ui

class LevelSelectScene : UiScene("Adventure") {
    override fun content(g: Gfx, ui: Ui) {
        val p = G.account.profile
        val r = page(g, ui)
        val (x, y, w, h) = r.toList()
        val done = G.levels.levels.count { p.adventure[it.id]?.completed == true }
        val mastered = G.levels.levels.count { p.adventure[it.id]?.mastered == true }
        g.text("Cleared $done/${G.levels.levels.size}   Mastered $mastered", x, y, Pal.INK)
        ui.progress(x, y + 10, w, 6, done / G.levels.levels.size.toFloat(), Pal.GREEN)
        val listY = y + 22
        val listH = h - 22
        val tile = ((w - 4 * 6) / 5).coerceAtMost(36)
        val chapterH = 14 + tile + 26
        val off = ui.beginScroll("levels", x, listY, w, listH, G.levels.chapters.size * chapterH)
        for ((ci, ch) in G.levels.chapters.withIndex()) {
            val cy = listY + ci * chapterH - off
            g.text("Chapter ${ch.first}: ${ch.second}", x, cy, Pal.ORANGE_DARK)
            val lv = G.levels.levels.filter { it.chapter == ch.first }.sortedBy { it.index }
            for ((i, l) in lv.withIndex()) {
                val tx = x + i * (tile + 6)
                val ty = cy + 12
                drawTile(g, l, tx, ty, tile)
                if (ui.tapped(tx, ty, tile, tile + 12)) openLevel(l)
            }
        }
        ui.endScroll()
    }

    private fun drawTile(g: Gfx, l: Level, x: Int, y: Int, size: Int) {
        val p = G.account.profile
        val unlocked = G.progress.isUnlocked(p, l.id)
        val lp = p.adventure[l.id]
        val color = when {
            !unlocked -> Color.rgb(120, 110, 100)
            lp?.mastered == true -> Pal.GOLD
            lp?.completed == true -> Pal.GREEN
            else -> Pal.BLUE
        }
        g.box(x, y, size, size, color, Pal.INK)
        g.rect(x + 2, y + 1, size - 4, 1, g.alpha(Color.WHITE, 0.5f))
        if (!unlocked) g.bitmapCentered(Sprites.lock, x + size / 2f, y + size / 2f, 2)
        else g.textCenter("${l.index}", x + size / 2, y + size / 2 - 6, Pal.WHITE, 2, outline = true)
        // three stars: clear, all gems, flap budget
        val stars = lp?.stars ?: 0
        for (s in 0 until 3) g.bitmap(if (s < stars) Sprites.star else Sprites.starEmpty, x + (size - 23) / 2 + s * 8, y + size + 2)
    }

    private fun openLevel(l: Level) {
        val p = G.account.profile
        if (!G.progress.isUnlocked(p, l.id)) {
            val prev = G.levels.prev(l)
            G.scenes.alert("Locked", "Clear ${prev?.id} ${prev?.name} first to unlock this level.")
            return
        }
        val lp = p.adventure[l.id]
        val mastery = l.masteryItem?.let { G.catalog.byId[it] }
        val body = buildString {
            append("${l.chapterName}\n\nGoal: ${l.objective.label}\n")
            append("Bonus: ${l.bonusGemsLabel}\nBonus: ${l.bonusFlapsLabel}\n\n")
            append(if (lp?.completed == true) "Replay reward: ${G.config.economy.replayReward} coins (daily cap applies)\n"
                   else "First clear: ${l.reward} coins + a mystery capsule\n")
            append("Each bonus (first time): ${l.bonusReward} coins\n")
            if (mastery != null) append("Mastery reward: ${mastery.name} (${mastery.rarity.label})\n")
            val features = listOfNotNull(
                "moving obstacles".takeIf { l.moveChance > 0 }, "spike orbs".takeIf { l.hazardChance > 0 }, "wind gusts".takeIf { l.wind > 0 })
            if (features.isNotEmpty()) append("\nWatch out for: ${features.joinToString(", ")}")
        }
        G.scenes.show(Modal("${l.id}  ${l.name}", body, listOf(
            ModalButton("Close", Style.PLAIN),
            ModalButton("Play", Style.GREEN) { G.scenes.push(PlayScene.adventure(l)) },
        )))
    }
}
