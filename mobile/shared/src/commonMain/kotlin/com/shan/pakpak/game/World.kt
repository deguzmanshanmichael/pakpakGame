package com.shan.pakpak.game

import com.shan.pakpak.data.DropConfig
import com.shan.pakpak.data.Level
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

enum class Theme(val key: String) {
    MEADOW("meadow"), BEACH("beach"), CAVE("cave"), SKY("sky");
    companion object { fun of(k: String) = entries.firstOrNull { it.key == k } ?: MEADOW }
}

enum class RunState { READY, PLAYING, DYING, DEAD, WON }
enum class PickupKind { COIN, CAPSULE, GEM }
enum class WorldEvent { FLAP, SCORE, COIN, CAPSULE, GEM, HIT, LAND, WIN, THEME }

class Obstacle(
    var x: Float, val baseGapY: Float, val gap: Float, val index: Int, val theme: Theme,
    val moveAmp: Float, val moveSpeed: Float, val movePhase: Float,
) {
    var gapY = baseGapY
    var passed = false
    var hazard: Hazard? = null
    val top get() = gapY - gap / 2
    val bottom get() = gapY + gap / 2
}

/** A spiky orb that bobs inside a gap (Kweba Caverns and later). */
class Hazard(val amp: Float, val phase: Float) { var y = 0f }

class Pickup(var x: Float, val baseY: Float, val kind: PickupKind, val obstacleIndex: Int, val follow: Obstacle?, val offsetY: Float) {
    var y = baseY
    var taken = false
    var t = 0f
}

class WindZone(var x0: Float, var x1: Float, val force: Float)

/** Endless or adventure parameters. Adventure runs are deterministic for a given level. */
class RunSpec(
    val mode: String,
    val level: Level?,
    val drops: DropConfig,
    val coinChance: Float,
    val seed: Long,
)

/**
 * The simulation for one run. Coordinates are virtual pixels; obstacles scroll left.
 * Physics runs at a fixed 120 Hz so results do not depend on frame rate.
 */
class World(val spec: RunSpec, val viewW: Float, val viewH: Float) {
    companion object {
        const val GROUND_H = 44f
        const val OBSTACLE_W = 26f
        const val BIRD_R = 4.6f
        const val GRAVITY = 900f
        const val FLAP_V = -262f
        const val MAX_FALL = 430f
        const val STEP = 1f / 120f
        const val ENDLESS_SPACING = 112f
        const val THEME_EVERY = 30
    }

    val groundY = viewH - GROUND_H
    val birdX = (viewW * 0.3f).coerceAtLeast(48f)
    var birdY = groundY * 0.45f
    var birdVy = 0f
    var state = RunState.READY
    var time = 0f
    var playTime = 0f
    var distance = 0f
    var score = 0
    var flaps = 0
    var coins = 0
    var gems = 0
    val capsulePipes = ArrayList<Int>()
    val obstacles = ArrayList<Obstacle>()
    val pickups = ArrayList<Pickup>()
    val winds = ArrayList<WindZone>()
    val events = ArrayList<WorldEvent>()
    var gateX = Float.NaN
    var theme: Theme = spec.level?.let { Theme.of(it.theme) } ?: Theme.MEADOW
    var deathCause = ""

    private val rnd = Random(spec.seed)
    private var generated = 0
    private var lastGapY = groundY * 0.5f
    private var lastCapsuleIndex = -100
    private var acc = 0f
    private var pendingFlaps = 0
    private val level = spec.level
    private val endless = spec.mode == "endless"
    private val totalObstacles: Int = when {
        level == null -> Int.MAX_VALUE
        level.objective.type == "survive" -> Int.MAX_VALUE
        else -> level.length
    }
    val gemIndices: Set<Int> = level?.let { l ->
        val span = l.length
        (0 until l.gems).map { k -> ((k + 0.5f) * span / l.gems).toInt() + 1 }.toSet()
    } ?: emptySet()

    val speed: Float get() = level?.speed ?: min(100f, 70f + score * 0.6f)
    private val spacing: Float get() = level?.spacing ?: ENDLESS_SPACING
    val playHeight get() = groundY

    init {
        var x = birdX + viewW * 0.75f + 40f
        while (x < viewW + spacing * 2) { spawnObstacle(x); x += spacing }
    }

    fun flap() { pendingFlaps++ }

    fun update(dt: Float) {
        time += dt
        if (state == RunState.READY) {
            birdY = groundY * 0.45f + sin(time * 6f) * 4f
            if (pendingFlaps > 0) { state = RunState.PLAYING; birdVy = 0f }
            else return
        }
        if (state == RunState.DEAD || state == RunState.WON) { pendingFlaps = 0; return }
        acc += dt
        while (acc >= STEP) {
            step(STEP)
            acc -= STEP
        }
    }

    private fun step(dt: Float) {
        if (state == RunState.DYING) {
            birdVy = min(MAX_FALL * 1.3f, birdVy + GRAVITY * 1.4f * dt)
            birdY += birdVy * dt
            if (birdY + BIRD_R >= groundY) { birdY = groundY - BIRD_R; state = RunState.DEAD; events.add(WorldEvent.LAND) }
            pendingFlaps = 0
            return
        }
        playTime += dt
        if (pendingFlaps > 0) {
            pendingFlaps = 0
            birdVy = FLAP_V
            flaps++
            events.add(WorldEvent.FLAP)
        }
        birdVy = min(MAX_FALL, birdVy + GRAVITY * dt)
        for (wz in winds) if (birdX >= wz.x0 && birdX <= wz.x1) birdVy += wz.force * dt
        birdY += birdVy * dt
        if (birdY < BIRD_R) { birdY = BIRD_R; if (birdVy < 0) birdVy = 0f }

        val dx = speed * dt
        distance += dx
        for (o in obstacles) {
            o.x -= dx
            if (o.moveAmp > 0f) o.gapY = o.baseGapY + sin(playTime * o.moveSpeed + o.movePhase) * o.moveAmp
            o.hazard?.let { h -> h.y = o.gapY + sin(playTime * 2.4f + h.phase) * h.amp }
        }
        for (p in pickups) {
            p.x -= dx
            p.t += dt
            p.y = (p.follow?.gapY ?: p.baseY) + p.offsetY + sin(p.t * 4f) * 1.5f
        }
        for (wz in winds) { wz.x0 -= dx; wz.x1 -= dx }
        if (!gateX.isNaN()) gateX -= dx
        obstacles.removeAll { it.x < -OBSTACLE_W - 8 }
        pickups.removeAll { it.x < -20 || it.taken }
        winds.removeAll { it.x1 < -10 }
        val last = obstacles.lastOrNull()
        if (generated < totalObstacles && (last == null || last.x < viewW + spacing)) spawnObstacle((last?.x ?: viewW) + spacing)

        // Scoring
        for (o in obstacles) {
            if (!o.passed && o.x + OBSTACLE_W / 2 < birdX) {
                o.passed = true
                score++
                events.add(WorldEvent.SCORE)
                if (endless && score % THEME_EVERY == 0) { theme = Theme.entries[(score / THEME_EVERY) % Theme.entries.size]; events.add(WorldEvent.THEME) }
            }
        }
        // Pickups
        for (p in pickups) {
            if (p.taken) continue
            val r = if (p.kind == PickupKind.CAPSULE) 7f else 5f
            val ddx = p.x - birdX
            val ddy = p.y - birdY
            if (ddx * ddx + ddy * ddy <= (r + BIRD_R) * (r + BIRD_R)) {
                p.taken = true
                when (p.kind) {
                    PickupKind.COIN -> { coins++; events.add(WorldEvent.COIN) }
                    PickupKind.GEM -> { gems++; events.add(WorldEvent.GEM) }
                    PickupKind.CAPSULE -> { capsulePipes.add(p.obstacleIndex); events.add(WorldEvent.CAPSULE) }
                }
            }
        }
        // Objectives
        if (level != null) {
            if (level.objective.type == "survive" && playTime >= level.objective.target) { win(); return }
            if (!gateX.isNaN() && gateX < birdX) { win(); return }
        }
        // Collisions
        if (birdY + BIRD_R >= groundY) { die("ground"); return }
        for (o in obstacles) {
            if (circleRect(birdX, birdY, BIRD_R, o.x, -1000f, OBSTACLE_W, o.top + 1000f) ||
                circleRect(birdX, birdY, BIRD_R, o.x, o.bottom, OBSTACLE_W, groundY - o.bottom)) { die("obstacle"); return }
            val h = o.hazard
            if (h != null) {
                val hx = o.x + OBSTACLE_W / 2
                val ddx = hx - birdX
                val ddy = h.y - birdY
                if (sqrt(ddx * ddx + ddy * ddy) < 5f + BIRD_R - 0.5f) { die("spikes"); return }
            }
        }
    }

    private fun win() {
        state = RunState.WON
        events.add(WorldEvent.WIN)
    }

    private fun die(cause: String) {
        deathCause = cause
        state = RunState.DYING
        birdVy = min(birdVy, -60f)
        events.add(WorldEvent.HIT)
    }

    /** Whether the objective (adventure) has been completed. Endless runs never "complete". */
    val completed get() = state == RunState.WON

    private fun spawnObstacle(x: Float) {
        val index = generated + 1
        generated++
        // Survival levels keep spawning past their nominal length; difficulty stays at the level's maximum.
        val progress = if (level != null) ((index - 1f) / max(1, level.length - 1)).coerceIn(0f, 1f) else 0f
        var gap = if (level != null) lerp(level.gapStart, level.gapEnd, progress.coerceIn(0f, 1f))
        else max(72f, 90f - index * 0.35f)
        val difficulty = if (level != null) 0.5f + progress * 0.5f else min(1f, index / 60f)
        // Gap centers stay in a band so very tall screens are not easier or harder.
        val band = min(groundY - 80f, 300f)
        val minY = (groundY - band) / 2 + gap / 2 + 4
        val maxY = (groundY + band) / 2 - gap / 2 - 4
        // Cap the jump between consecutive gaps so a fast climb is always physically possible.
        val maxDelta = 50f + difficulty * 80f
        val roll = rnd.nextFloat() * 2 - 1
        val th = if (level != null) Theme.of(level.theme) else Theme.entries[((index - 1) / THEME_EVERY) % Theme.entries.size]
        var amp = 0f
        var mspeed = 0f
        if (level != null) {
            if (index > 1 && rnd.nextFloat() < level.moveChance) { amp = level.moveAmp; mspeed = 1.2f + rnd.nextFloat() * 0.8f }
        } else if (index > 40 && rnd.nextFloat() < min(0.3f, (index - 40) / 100f)) {
            amp = 8f + min(10f, (index - 40) / 8f); mspeed = 1.3f + rnd.nextFloat() * 0.6f
        }
        // Never stack a spike orb right after a wind gust (wind zones lead into every 3rd obstacle).
        val afterWind = level != null && level.wind > 0f && index > 2 && index % 3 == 0
        val hazardHere = level != null && index > 2 && amp == 0f && !afterWind && rnd.nextFloat() < level.hazardChance
        if (hazardHere) gap += 40f
        // A moving gap's swing counts against the allowed jump from the previous gap.
        var gy = (lastGapY + roll * (maxDelta - amp)).coerceIn(minY + amp, maxY - amp)
        if (index == 1) gy = groundY * 0.48f
        val o = Obstacle(x, gy, gap, index, th, amp, mspeed, rnd.nextFloat() * (2 * PI).toFloat())
        if (hazardHere) o.hazard = Hazard(gap * 0.12f, rnd.nextFloat() * 6f).also { it.y = gy }
        obstacles.add(o)

        val cx = x + OBSTACLE_W / 2
        if (endless) {
            if (index > 1 && rnd.nextFloat() < spec.coinChance) pickups.add(Pickup(cx, gy, PickupKind.COIN, index, o, 0f))
            val d = spec.drops
            if (index >= d.capsuleStartPipe && index - lastCapsuleIndex >= d.capsuleMinGap && rnd.nextFloat() < d.capsuleChance) {
                lastCapsuleIndex = index
                // Placed just before obstacle #index, between its gap and the previous one.
                val px = x - spacing / 2 + OBSTACLE_W / 2
                pickups.add(Pickup(px, (gy + lastGapY) / 2, PickupKind.CAPSULE, index, null, 0f))
            }
        } else if (level != null) {
            if (index in gemIndices) {
                val k = gemIndices.sorted().indexOf(index)
                val off = (gap / 2 - 11f).coerceAtLeast(0f) * (if (k % 2 == 0) -0.8f else 0.8f)
                val follow = if (o.hazard == null) o else null
                pickups.add(Pickup(cx, gy, PickupKind.GEM, index, follow, if (follow == null) -(gap / 2 - 8f) else off))
            }
            if (level.wind > 0f && index > 2 && index % 3 == 0) {
                val dir = if ((index / 3) % 2 == 0) -1f else 1f
                winds.add(WindZone(x - spacing + OBSTACLE_W + 6f, x - 6f, dir * level.wind))
            }
            if (index == totalObstacles) gateX = x + spacing
        }
        lastGapY = gy
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun circleRect(cx: Float, cy: Float, r: Float, rx: Float, ry: Float, rw: Float, rh: Float): Boolean {
        val nx = cx.coerceIn(rx, rx + rw)
        val ny = cy.coerceIn(ry, ry + rh)
        val dx = cx - nx
        val dy = cy - ny
        return dx * dx + dy * dy < r * r
    }

    /** The obstacle the bird is inside or approaching (used by the test autopilot). */
    fun nextObstacle(): Obstacle? = obstacles.firstOrNull { it.x + OBSTACLE_W > birdX - BIRD_R }

    fun windAt(x: Float): Float = winds.firstOrNull { x >= it.x0 && x <= it.x1 }?.force ?: 0f

    fun isDone() = state == RunState.DEAD || state == RunState.WON

    @Suppress("unused")
    fun debugDistanceTo(o: Obstacle) = abs(o.x - birdX)
}
