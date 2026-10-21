package com.shan.pakpak.scenes

import com.shan.pakpak.util.newId
import com.shan.pakpak.nowMs
import com.shan.pakpak.gfx.Color
import com.shan.pakpak.G
import com.shan.pakpak.Tracks
import com.shan.pakpak.data.Level
import com.shan.pakpak.data.Rarity
import com.shan.pakpak.data.RunRecord
import com.shan.pakpak.engine.Scene
import com.shan.pakpak.game.Obstacle
import com.shan.pakpak.game.PickupKind
import com.shan.pakpak.game.Rules
import com.shan.pakpak.game.RunOutcome
import com.shan.pakpak.game.RunSpec
import com.shan.pakpak.game.RunState
import com.shan.pakpak.game.Theme
import com.shan.pakpak.game.World
import com.shan.pakpak.game.WorldEvent
import com.shan.pakpak.gfx.Backgrounds
import com.shan.pakpak.gfx.Gfx
import com.shan.pakpak.gfx.Pal
import com.shan.pakpak.gfx.Particles
import com.shan.pakpak.gfx.PixelFont
import com.shan.pakpak.gfx.Sprites
import com.shan.pakpak.ui.Style
import com.shan.pakpak.ui.Ui
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class PlayScene private constructor(private val mode: String, private val level: Level?) : Scene() {
    companion object {
        fun endless() = PlayScene("endless", null)
        fun adventure(l: Level) = PlayScene("adventure", l)
        private val THEME_NAMES = mapOf(Theme.MEADOW to "Bukid Meadows", Theme.BEACH to "Baybay Shores", Theme.CAVE to "Kweba Caverns", Theme.SKY to "Bituin Skies")
    }

    override val music get() = Tracks.GAME

    private lateinit var world: World
    private val fx = Particles()
    private var runId = ""
    private var ticket: String? = null
    private var playedAt = 0L
    private var paused = false
    private var finished = false
    private var outcome: RunOutcome? = null
    private var resultsT = 0f
    private var shake = 0f
    private var flash = 0f
    private var banner: String? = null
    private var bannerT = 0f
    private var revealed = 0
    private var revealTimer = 0f
    private var worldW = 0
    private var worldH = 0

    override fun enter() = start()

    private fun start() {
        worldW = G.vw
        worldH = G.vh
        val seed = level?.seed ?: Random.nextLong()
        world = World(RunSpec(mode, level, G.config.drops, G.config.economy.coinChance, seed), worldW.toFloat(), worldH.toFloat())
        runId = newId()
        ticket = null
        playedAt = nowMs()
        paused = false
        finished = false
        outcome = null
        resultsT = 0f
        revealed = 0
        fx.clear()
        banner = level?.let { "${it.id}  ${it.name}" }
        bannerT = if (level != null) 2.5f else 0f
        val myRun = runId
        G.account.startRun(mode, level?.id) { id -> if (runId == myRun && !finished) ticket = id }
    }

    override fun onAppPause() { if (world.state == RunState.PLAYING) paused = true }

    override fun back(): Boolean {
        when {
            finished -> G.scenes.pop()
            world.state == RunState.PLAYING -> paused = !paused
            else -> G.scenes.pop()
        }
        return true
    }

    private val pauseRect get() = intArrayOf(G.vw - 24, 18, 20, 18)

    override fun update(dt: Float) {
        fx.update(dt)
        if (shake > 0) shake -= dt
        if (flash > 0) flash -= dt
        if (bannerT > 0) bannerT -= dt
        if (paused) return
        val inp = G.input
        if (!finished && inp.taps > 0) {
            val r = pauseRect
            val onPause = inp.pressed && inp.pressX >= r[0] - 4 && inp.pressY <= r[1] + r[3] + 4
            val flaps = if (onPause && world.state == RunState.PLAYING) inp.taps - 1 else inp.taps
            if (world.state == RunState.READY || world.state == RunState.PLAYING) repeat(max(0, flaps)) { world.flap() }
        }
        world.update(dt)
        handleEvents()
        if (world.isDone() && !finished) finish()
        if (finished) {
            resultsT += dt
            val o = outcome ?: return
            if (resultsT > 0.9f && revealed < o.items.size) {
                revealTimer -= dt
                if (revealTimer <= 0f) {
                    val item = o.items[revealed].first
                    revealed++
                    revealTimer = 0.45f
                    val big = item.rarity.ordinal >= Rarity.RARE.ordinal
                    G.audio.play(if (big) "rare" else "capsule")
                    fx.burst(G.vw / 2f, G.vh / 2f, if (big) 40 else 14, intArrayOf(item.rarity.color, Color.WHITE, Pal.GOLD), 90f, 0.9f, 60f, if (big) 2 else 1)
                    if (item.rarity.ordinal >= Rarity.LEGENDARY.ordinal) { flash = 0.6f; fx.confetti(G.vw, 80, intArrayOf(item.rarity.color, Pal.GOLD, Color.WHITE, Pal.RED)) }
                }
            }
        }
    }

    private fun handleEvents() {
        val bx = world.birdX
        val by = world.birdY
        for (e in world.events) when (e) {
            WorldEvent.FLAP -> { G.audio.play("flap"); fx.burst(bx - 6, by + 2, 2, intArrayOf(Color.WHITE), 20f, 0.3f, 40f) }
            WorldEvent.SCORE -> G.audio.play("score")
            WorldEvent.COIN -> { G.audio.play("coin"); fx.burst(bx, by, 8, intArrayOf(Pal.GOLD, Pal.YELLOW), 50f, 0.4f) }
            WorldEvent.GEM -> { G.audio.play("gem"); fx.burst(bx, by, 10, intArrayOf(Color.rgb(80, 220, 255), Color.WHITE), 60f, 0.5f) }
            WorldEvent.CAPSULE -> {
                G.audio.play("capsule")
                fx.burst(bx, by, 16, intArrayOf(Color.rgb(255, 92, 160), Color.WHITE, Pal.GOLD), 70f, 0.6f)
                banner = "Mystery capsule!"; bannerT = 1.2f
            }
            WorldEvent.HIT -> {
                G.audio.play("hit"); shake = 0.25f; flash = 0.12f; G.haptic()
                fx.burst(bx, by, 14, skin(G.account.profile.equippedSkin).colors.copyOfRange(1, 4), 80f, 0.7f, 200f)
            }
            WorldEvent.LAND -> G.audio.play("die")
            WorldEvent.WIN -> {
                G.audio.play("win")
                fx.confetti(G.vw, 70, intArrayOf(Pal.GOLD, Pal.RED, Pal.GREEN, Pal.BLUE, Color.WHITE))
            }
            WorldEvent.THEME -> { flash = 0.5f; banner = "Now entering ${THEME_NAMES[world.theme]}"; bannerT = 2f }
        }
        world.events.clear()
    }

    private fun finish() {
        finished = true
        val w = world
        val rec = RunRecord(
            clientRunId = runId, ticketId = ticket, mode = mode, levelId = level?.id, score = w.score, pipes = w.score,
            gems = w.gems, coinsCollected = w.coins, flaps = w.flaps, durationMs = (w.playTime * 1000).toLong(),
            distance = (w.distance / 10f).toInt(), playedAt = playedAt, completed = w.completed, pickups = w.capsulePipes.toList(),
        )
        outcome = G.account.finishRun(rec)
        revealTimer = 0f
    }

    // ------------------------------------------------------------------ drawing

    override fun draw(g: Gfx, ui: Ui) {
        if (g.w != worldW || g.h != worldH) {
            // Screen size changed (rare: split screen / fold). Restart cleanly instead of stretching the world.
            if (!finished) { start(); return }
        }
        val w = world
        val sx = if (shake > 0) (Random.nextInt(3) - 1) * 2 else 0
        g.c.save()
        g.c.translate(sx.toFloat(), 0f)
        val gh = World.GROUND_H.toInt()
        Backgrounds.drawSky(g, w.theme, w.distance, G.time, gh)
        drawWind(g)
        for (o in w.obstacles) drawObstacle(g, o)
        for (p in w.pickups) {
            if (p.taken) continue
            when (p.kind) {
                PickupKind.COIN -> g.bitmapCentered(Sprites.coin[((G.time * 6).toInt() + p.obstacleIndex) % 2], p.x, p.y)
                PickupKind.GEM -> g.bitmapCentered(Sprites.gem, p.x, p.y)
                PickupKind.CAPSULE -> {
                    g.disc(p.x, p.y, 8f + sin(G.time * 6f), g.alpha(Color.rgb(255, 150, 200), 0.35f))
                    g.bitmapCentered(Sprites.capsule, p.x, p.y)
                }
            }
        }
        if (!w.gateX.isNaN()) {
            for (k in 0..((w.groundY) / 12).toInt()) g.bitmap(Sprites.gate, w.gateX.toInt(), k * 12)
            g.textCenter("GOAL", w.gateX.toInt() + 6, (w.groundY / 2).toInt(), Pal.WHITE, 1, outline = true)
        }
        drawBird(g)
        fx.draw(g)
        Backgrounds.drawGround(g, w.theme, w.distance, gh)
        g.c.restore()
        if (flash > 0) g.rect(0, 0, g.w, g.h, g.alpha(Color.WHITE, min(1f, flash * 2f)))

        drawHud(g, ui)
        if (w.state == RunState.READY) drawReady(g)
        if (paused) drawPause(g, ui)
        if (finished && resultsT > 0.7f) drawResults(g, ui)
    }

    private fun drawObstacle(g: Gfx, o: Obstacle) {
        val body = Sprites.body(o.theme)
        val x = o.x.toInt()
        val bx = x - (body.width - World.OBSTACLE_W.toInt()) / 2
        val topCap = Sprites.cap(o.theme, true)
        val botCap = Sprites.cap(o.theme, false)
        val tile = Sprites.BODY_TILE_H
        val topEnd = o.top.toInt() - topCap.height
        var y = topEnd
        while (y > 0) {
            val h = min(tile, y)
            g.bitmapRegion(body, 0, tile - h, body.width, h, bx, y - h)
            y -= tile
        }
        g.bitmap(topCap, x - (topCap.width - World.OBSTACLE_W.toInt()) / 2, topEnd)
        val botStart = o.bottom.toInt()
        g.bitmap(botCap, x - (botCap.width - World.OBSTACLE_W.toInt()) / 2, botStart)
        y = botStart + botCap.height
        val ground = world.groundY.toInt()
        while (y < ground) {
            val h = min(tile, ground - y)
            g.bitmapRegion(body, 0, 0, body.width, h, bx, y)
            y += tile
        }
        o.hazard?.let { h -> g.bitmapCentered(Sprites.spikeOrb, o.x + World.OBSTACLE_W / 2, h.y) }
    }

    private fun drawWind(g: Gfx) {
        for (wz in world.winds) {
            val up = wz.force < 0
            val x0 = wz.x0.toInt()
            val x1 = wz.x1.toInt()
            if (x1 < 0 || x0 > g.w) continue
            g.rect(x0, 0, x1 - x0, world.groundY.toInt(), g.alpha(if (up) Color.rgb(160, 220, 255) else Color.rgb(255, 190, 220), 0.12f))
            for (k in 0 until 8) {
                val sx = x0 + ((k * 13) % max(1, x1 - x0))
                val phase = ((G.time * 80 + k * 37) % world.groundY)
                val sy = if (up) (world.groundY - phase).toInt() else phase.toInt()
                g.rect(sx, sy, 1, 6, g.alpha(Color.WHITE, 0.6f))
            }
            g.textCenter(if (up) "^" else "v", (x0 + x1) / 2, 40, Pal.WHITE, 1, outline = true)
        }
    }

    private fun drawBird(g: Gfx) {
        val w = world
        val sk = skin(G.account.profile.equippedSkin)
        val frame = when {
            w.state == RunState.DYING || w.state == RunState.DEAD -> 1
            w.state == RunState.READY -> ((G.time * 8).toInt() % 3)
            w.birdVy < 50 -> ((G.time * 14).toInt() % 3)
            else -> 1
        }
        val angle = when (w.state) {
            RunState.READY -> 0f
            RunState.DEAD, RunState.DYING -> 80f
            else -> (w.birdVy / World.MAX_FALL * 70f).coerceIn(-22f, 75f)
        }
        g.bitmapRotated(Sprites.bird(sk, frame), w.birdX, w.birdY, angle)
    }

    private fun drawHud(g: Gfx, ui: Ui) {
        val w = world
        if (w.state != RunState.READY && !finished) g.textCenter(w.score.toString(), g.w / 2, if (level != null) 52 else 30, Pal.WHITE, 3, outline = true)
        connBadge(g, g.w - 4, 3)
        var y = 4
        if (level != null) {
            g.textShadow(level.id, 4, y, Pal.GOLD); y += 10
            val obj = level.objective
            val progress = when (obj.type) {
                "survive" -> "time ${min(obj.target, w.playTime.toInt())}/${obj.target}s"
                "gems" -> "pass ${w.score}/${level.length}, need ${obj.target} gems"
                else -> "pass ${w.score}/${level.length}"
            }
            g.textShadow(progress, 4, y, Pal.CREAM); y += 10
            g.bitmap(Sprites.gem, 4, y); g.textShadow("${w.gems}/${level.gems}", 13, y, Pal.CREAM); y += 10
            g.textShadow("flaps ${w.flaps}/${level.maxFlaps}", 4, y, if (w.flaps > level.maxFlaps) Pal.RED else Pal.CREAM)
        } else {
            g.bitmap(Sprites.coinSmall, 4, y); g.textShadow(w.coins.toString(), 13, y, Pal.CREAM); y += 10
            if (w.capsulePipes.isNotEmpty()) { g.bitmap(Sprites.capsule, 3, y); g.textShadow("x${w.capsulePipes.size}", 14, y + 1, Pal.CREAM) }
        }
        if (!finished && w.state == RunState.PLAYING) {
            val r = pauseRect
            if (ui.button(r[0], r[1], r[2], r[3], "II", Style.PLAIN)) paused = true
        }
        val b = banner
        if (b != null && bannerT > 0f) {
            val bw = PixelFont.width(b) + 14
            g.box(g.w / 2 - bw / 2, 58, bw, 15, g.alpha(Color.rgb(40, 30, 56), 0.85f), Pal.CREAM)
            g.textCenter(b, g.w / 2, 62, Pal.CREAM)
        }
    }

    private fun drawReady(g: Gfx) {
        val cy = (world.groundY * 0.45f).toInt()
        g.textCenter(if (level != null) level.name.uppercase() else "GET READY", g.w / 2, cy - 70, Pal.GOLD, 2, outline = true)
        if (level != null) g.textCenter(level.objective.label, g.w / 2, cy - 48, Pal.WHITE, 1, outline = true)
        else g.textCenter("Best: ${G.account.profile.bestScore}", g.w / 2, cy - 48, Pal.WHITE, 1, outline = true)
        val bob = (sin(G.time * 5) * 2).toInt()
        g.textCenter("TAP TO FLAP", g.w / 2, cy + 30 + bob, Pal.WHITE, 1, outline = true)
        g.textCenter("Pass the gaps. Don't touch anything!", g.w / 2, cy + 44, Pal.CREAM, 1, outline = true)
    }

    private fun drawPause(g: Gfx, ui: Ui) {
        g.rect(0, 0, g.w, g.h, Pal.SHADE)
        val pw = 150
        val ph = 120
        val x = (g.w - pw) / 2
        val y = (g.h - ph) / 2
        ui.panel(x, y, pw, ph)
        g.textCenter("PAUSED", g.w / 2, y + 10, Pal.ORANGE_DARK, 2)
        if (ui.button(x + 12, y + 34, pw - 24, 20, "Resume", Style.GREEN)) paused = false
        if (ui.button(x + 12, y + 60, pw - 24, 20, "Restart", Style.PLAIN)) start()
        if (ui.button(x + 12, y + 86, pw - 24, 20, "Quit (run lost)", Style.RED)) G.scenes.pop()
    }

    private fun drawResults(g: Gfx, ui: Ui) {
        val o = outcome ?: return
        val w = world
        g.rect(0, 0, g.w, g.h, g.alpha(Pal.INK, min(0.55f, (resultsT - 0.7f) * 2f)))
        val pw = g.w - 20
        val x = 10
        val perRowEst = ((pw - 16) / 34).coerceAtLeast(1)
        val itemsRows = (o.items.size + perRowEst - 1) / perRowEst
        val achLines = if (o.achievements.size > 3) 2 else o.achievements.size
        val header = if (level == null) 78 else 30 + 3 * 22
        val ph = min(g.h - 30, header + 16 + (if (o.sealedCapsules > 0) 22 else 0) + itemsRows * 34 + (if (o.items.isNotEmpty()) 12 else 0) +
            achLines * 10 + o.notes.size * 20 + 40)
        val y = max(20, (g.h - ph) / 2 - 10)
        ui.panel(x, y, pw, ph)
        var cy = y + 8
        val title = when {
            level == null -> "GAME OVER"
            w.completed -> "LEVEL CLEAR!"
            else -> "TRY AGAIN"
        }
        g.textCenter(title, g.w / 2, cy, if (w.completed) Pal.GREEN_DARK else Pal.ORANGE_DARK, 2)
        cy += 22
        if (level == null) {
            val medalIdx = o.medal
            g.bitmap(Sprites.medal(medalIdx), x + 12, cy, 3)
            val mx = x + 52
            g.text("SCORE", mx, cy, Pal.MUTED); g.text(w.score.toString(), mx + 40, cy, Pal.INK)
            g.text("BEST", mx, cy + 11, Pal.MUTED); g.text(G.account.profile.bestScore.toString(), mx + 40, cy + 11, Pal.INK)
            if (o.newBest) g.text("NEW!", mx + 70, cy + 11, Pal.RED)
            g.text("DIST", mx, cy + 22, Pal.MUTED); g.text("${(w.distance / 10).toInt()}m", mx + 40, cy + 22, Pal.INK)
            val medalName = if (medalIdx >= 0) Rules.MEDALS[medalIdx].replaceFirstChar { it.uppercase() } + " medal" else nextMedalHint(w.score)
            g.text(medalName, mx, cy + 33, if (medalIdx >= 0) Pal.ORANGE_DARK else Pal.MUTED)
            cy += 48
        } else {
            val stars = listOf(
                w.completed to level.objective.label,
                (w.completed && w.gems >= level.gems) to "${level.bonusGemsLabel} (${w.gems}/${level.gems})",
                (w.completed && w.flaps <= level.maxFlaps) to "${level.bonusFlapsLabel} (${w.flaps})",
            )
            for ((done, label) in stars) {
                g.bitmap(if (done) Sprites.star else Sprites.starEmpty, x + 10, cy)
                cy += PixelFont.wrap(label, pw - 34).size.let { n -> g.paragraph(label, x + 22, cy, pw - 34, if (done) Pal.INK else Pal.MUTED); n * 10 } + 2
            }
            if (o.mastered) { g.text("MASTERED!", x + 10, cy, Pal.ORANGE_DARK); cy += 10 }
            cy += 4
        }
        g.bitmap(Sprites.coinSmall, x + 10, cy)
        val coinText = when {
            o.rejectedReason != null -> "Run not accepted by server (${o.rejectedReason})"
            !G.account.isGuest && !o.confirmed -> "+${o.coins} (syncing...)"
            else -> "+${o.coins} ${G.config.economy.currencyName}"
        }
        g.text(coinText, x + 20, cy, Pal.INK)
        cy += 12
        if (o.sealedCapsules > 0) {
            g.bitmap(Sprites.capsule, x + 9, cy - 1)
            val msg = if (G.net.online) "${o.sealedCapsules} capsule(s) opening..." else "${o.sealedCapsules} sealed capsule(s): they open when you're back online"
            cy += g.paragraph(msg, x + 22, cy, pw - 32, Pal.INK) + 2
        }
        val shownCount = min(revealed, o.items.size)
        if (o.items.isNotEmpty()) {
            val cell = 30
            val perRow = ((pw - 16) / (cell + 4)).coerceAtLeast(1)
            for ((i, pair) in o.items.withIndex()) {
                if (i >= shownCount) break
                val cx = x + 8 + (i % perRow) * (cell + 4)
                val ry = cy + (i / perRow) * (cell + 4)
                itemCell(g, pair.first, true, cx, ry, cell)
                if (ui.tapped(cx, ry, cell, cell)) showItemDetail(pair.first, G.account.profile.items[pair.first.id] ?: 1)
            }
            cy += ((o.items.size + perRow - 1) / perRow) * (cell + 4)
            if (shownCount > 0) {
                val last = o.items[shownCount - 1].first
                g.text("${last.rarity.label}: ${last.name}", x + 8, cy, last.rarity.color.let { if (it == Rarity.COMMON.color) Pal.MUTED else darken(it, 0.8f) })
                cy += 12
            }
        }
        if (o.achievements.size in 1..3) {
            for (a in o.achievements) { g.text("* ${a.name} +${a.reward}".take((pw - 16) / 6), x + 8, cy, Pal.GREEN_DARK); cy += 10 }
        } else if (o.achievements.isNotEmpty()) {
            cy += g.paragraph("${o.achievements.size} achievements unlocked! +${o.achievements.sumOf { it.reward }} coins. See Rewards.", x + 8, cy, pw - 16, Pal.GREEN_DARK)
        }
        for (n in o.notes) cy += g.paragraph(n, x + 8, cy, pw - 16, Pal.MUTED)

        val by = y + ph - 28
        val bw = (pw - 16 - 12) / 3
        if (ui.button(x + 8, by, bw, 20, "Retry", Style.GREEN)) start()
        val next = level?.let { G.levels.next(it) }
        if (level != null && w.completed && next != null) {
            if (ui.button(x + 14 + bw, by, bw, 20, "Next", Style.PRIMARY)) G.scenes.replace(adventure(next))
        } else if (ui.button(x + 14 + bw, by, bw, 20, "Rewards", Style.PLAIN)) G.scenes.replace(RewardsScene())
        if (ui.button(x + 20 + bw * 2, by, bw, 20, if (level != null) "Levels" else "Menu", Style.PLAIN)) G.scenes.pop()
    }

    private fun nextMedalHint(score: Int): String {
        val t = G.config.medals
        val next = t.firstOrNull { it > score } ?: return ""
        return "Bronze at ${t[0]}".takeIf { score < t[0] } ?: "Next medal at $next"
    }
}
