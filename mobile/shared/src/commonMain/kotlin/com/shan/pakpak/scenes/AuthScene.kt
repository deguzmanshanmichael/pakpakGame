package com.shan.pakpak.scenes

import com.shan.pakpak.Field
import com.shan.pakpak.G
import com.shan.pakpak.engine.Modal
import com.shan.pakpak.engine.ModalButton
import com.shan.pakpak.gfx.Gfx
import com.shan.pakpak.gfx.Pal
import com.shan.pakpak.ui.Style
import com.shan.pakpak.ui.Ui

class AuthScene : UiScene("Account") {
    override fun update(dt: Float) {
        if (G.account.signedIn && !G.scenes.modalOpen && !loading) G.scenes.pop()
    }

    override fun content(g: Gfx, ui: Ui) {
        val (x, y, w, _) = page(g, ui).toList()
        var cy = y
        cy += g.paragraph("Sign in to add friends, chat, trade collectibles, and get server-verified scores and items. Offline play never needs an account.", x, cy, w, Pal.INK) + 8
        val ready = G.net.online && G.api.configured
        if (!ready) {
            cy += g.paragraph(if (!G.api.configured) "This build has no server configured." else "You're offline. Connect to sign in.", x, cy, w, Pal.RED_DARK) + 6
        }
        if (ui.button(x, cy, w, 22, "Sign in", Style.BLUE, ready && !loading)) login()
        cy += 28
        if (ui.button(x, cy, w, 22, "Create account", Style.GREEN, ready && !loading)) register()
        cy += 28
        if (ui.button(x, cy, w, 18, "Forgot password? Use recovery code", Style.PLAIN, ready && !loading)) recover()
        cy += 26
        cy += g.paragraph("Safety tips: pick a username that isn't your real name. Never share your password or recovery code. We never ask for your email, phone or address.", x, cy, w, Pal.MUTED) + 4
        errorLine(g, x, cy, w)
    }

    private fun login() {
        G.prompt("Sign in", null, listOf(Field("Username", maxLength = 16), Field("Password", password = true, maxLength = 128)), "Sign in") { v ->
            if (v == null) return@prompt
            if (v[0].isBlank() || v[1].isEmpty()) { error = "Enter your username and password."; return@prompt }
            loading = true; error = null
            G.account.login(v[0].trim(), v[1]) { r ->
                loading = false
                if (r.ok) afterSignIn() else error = r.message
            }
        }
    }

    private fun register() {
        G.prompt("Create account", "Username: 3-16 letters, numbers or _. Password: 8+ characters with a letter and a number.",
            listOf(Field("Username", maxLength = 16), Field("Password", password = true, maxLength = 128), Field("Repeat password", password = true, maxLength = 128)), "Create") { v ->
            if (v == null) return@prompt
            val u = v[0].trim()
            when {
                !Regex("^[A-Za-z0-9_]{3,16}$").matches(u) -> { error = "Username must be 3-16 letters, numbers or _."; return@prompt }
                v[1].length < 8 || !v[1].any { it.isLetter() } || !v[1].any { it.isDigit() } -> { error = "Password needs 8+ characters with a letter and a number."; return@prompt }
                v[1] != v[2] -> { error = "Passwords don't match."; return@prompt }
            }
            loading = true; error = null
            G.account.register(u, v[1]) { r, code ->
                loading = false
                if (r.ok && code != null) showRecoveryCode(code, true) else error = r.message
            }
        }
    }

    private fun recover() {
        G.prompt("Reset password", "Enter the recovery code you saved when you created the account.",
            listOf(Field("Username", maxLength = 16), Field("Recovery code", "XXXX-XXXX-XXXX-XXXX", maxLength = 24), Field("New password", password = true, maxLength = 128)), "Reset") { v ->
            if (v == null) return@prompt
            loading = true; error = null
            G.account.recover(v[0].trim(), v[1].trim(), v[2]) { r, code ->
                loading = false
                if (r.ok && code != null) showRecoveryCode(code, false) else error = r.message
            }
        }
    }

    private fun showRecoveryCode(code: String, isNew: Boolean) {
        G.scenes.show(Modal(if (isNew) "Account created!" else "Password reset!",
            "Your ${if (isNew) "" else "NEW "}recovery code:\n\n$code\n\nWrite it down somewhere safe. It's the only way to reset your password, and it's shown only once. Each code works one time.",
            listOf(
                ModalButton("Copy", Style.PLAIN) { G.copyToClipboard("PakPak recovery code", code); G.scenes.toast("Copied. Store it safely!"); afterSignIn() },
                ModalButton("I saved it", Style.GREEN) { afterSignIn() },
            ), dismissable = false))
    }

    private fun afterSignIn() {
        val acc = G.account
        val p = acc.acct ?: return
        if (!p.guestImported && !p.guestImportOffered && (acc.guest.totalRuns > 0 || acc.guest.items.isNotEmpty())) {
            p.guestImportOffered = true
            acc.save()
            G.scenes.show(Modal("Bring your guest progress?",
                "You have offline guest progress on this device. You can import it into this account once (Profile > Account). Imported items are account-bound and don't count for the completion prize.",
                listOf(ModalButton("OK", Style.PRIMARY))))
        }
        G.scenes.toast("Welcome, ${p.username}!")
    }
}
