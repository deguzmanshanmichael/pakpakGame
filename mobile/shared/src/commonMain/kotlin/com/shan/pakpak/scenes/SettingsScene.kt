package com.shan.pakpak.scenes

import com.shan.pakpak.G
import com.shan.pakpak.data.Profile
import com.shan.pakpak.gfx.Gfx
import com.shan.pakpak.gfx.Pal
import com.shan.pakpak.ui.Style
import com.shan.pakpak.ui.Ui

class SettingsScene : UiScene("Settings") {
    override fun exit() { G.saveSettings() }

    override fun content(g: Gfx, ui: Ui) {
        val s = G.settings
        val (x, y, w, _) = page(g, ui).toList()
        var cy = y
        g.text("Sound", x, cy, Pal.ORANGE_DARK); cy += 14
        val music = ui.toggle(x, cy, w, "Music", s.music); cy += 18
        val mv = ui.stepper(x, cy, w, "Music volume", s.musicVolume); cy += 20
        val sfx = ui.toggle(x, cy, w, "Sound effects", s.sfx); cy += 18
        val sv = ui.stepper(x, cy, w, "Effects volume", s.sfxVolume); cy += 22
        if (music != s.music || mv != s.musicVolume) { s.music = music; s.musicVolume = mv; G.audio.applySettings() }
        if (sfx != s.sfx || sv != s.sfxVolume) { s.sfx = sfx; s.sfxVolume = sv; G.audio.play("coin") }
        g.text("Other", x, cy, Pal.ORANGE_DARK); cy += 14
        s.haptics = ui.toggle(x, cy, w, "Vibration on tap", s.haptics); cy += 18
        s.screenShake = ui.toggle(x, cy, w, "Screen shake", s.screenShake); cy += 18
        s.showFps = ui.toggle(x, cy, w, "Show frame time", s.showFps); cy += 24

        g.text("About", x, cy, Pal.ORANGE_DARK); cy += 12
        cy += g.paragraph("PakPak ${G.platform.versionName}. Original pixel art, music and sounds. Made by Shan. " +
            "Not affiliated with any other flappy game.", x, cy, w, Pal.INK) + 4
        cy += g.paragraph("Privacy: the app uses only the internet. No contacts, location, camera or files. " +
            "Accounts store a username and a hashed password only.", x, cy, w, Pal.MUTED) + 8
        val label = if (G.account.isGuest) "Reset guest progress" else "Reset guest save (not your account)"
        if (ui.button(x, cy, w, 18, label, Style.RED)) {
            G.scenes.confirm("Reset guest progress?",
                "Deletes the offline guest save on this device: collection, coins, daily limits, levels, skins, megabirds, tokens and wish pity. " +
                    "Signed-in accounts are not affected (their pity and items live on the server). This can't be undone.",
                "Reset", yesStyle = Style.RED) {
                G.account.resetGuest()
                G.scenes.toast("Guest progress reset")
            }
        }
    }
}
