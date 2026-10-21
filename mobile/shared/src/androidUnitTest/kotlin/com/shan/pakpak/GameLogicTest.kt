package com.shan.pakpak

import com.shan.pakpak.data.Catalog
import com.shan.pakpak.data.GameConfig
import com.shan.pakpak.data.Levels
import com.shan.pakpak.data.Profile
import com.shan.pakpak.data.Rarity
import com.shan.pakpak.data.RunRecord
import com.shan.pakpak.game.Progress
import com.shan.pakpak.game.Rules
import com.shan.pakpak.game.RunSpec
import com.shan.pakpak.game.RunState
import com.shan.pakpak.game.World
import com.shan.pakpak.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.random.Random

class GameLogicTest {
    private fun json(name: String) = JSONObject(File("src/commonMain/assets/data/$name").readText())
    private val catalog = Catalog.parse(json("catalog.json"))
    private val config = GameConfig.parse(json("config.json"))
    private val levels = Levels.parse(json("levels.json"))

    private fun endless(seed: Long, h: Float = 480f) =
        World(RunSpec("endless", null, config.drops, config.economy.coinChance, seed), 216f, h)

    /**
     * A simple autopilot: flap when the bird is below its target height and falling.
     * Avoids spike orbs by aiming for the half of the gap the orb is not in.
     */
    private fun autopilot(w: World, maxSeconds: Float, stopAt: Int = Int.MAX_VALUE) {
        w.flap()
        var t = 0f
        val dt = 1f / 60f
        var cooldown = 0f
        val side = HashMap<Int, Boolean>()
        while (t < maxSeconds && !w.isDone() && w.score < stopAt) {
            val o = w.nextObstacle()
            var target = o?.gapY ?: (w.groundY / 2)
            var margin = (o?.gap ?: 80f) * 0.2f
            if (o != null && o.moveAmp > 0f) {
                // Lead a moving gap: aim where it will be a moment from now.
                val eta = ((o.x - w.birdX) / w.speed).coerceAtLeast(0f) + 0.15f
                target = o.baseGapY + kotlin.math.sin((w.playTime + eta) * o.moveSpeed + o.movePhase) * o.moveAmp
            }
            margin -= w.windAt(w.birdX) * 0.04f // updraft: flap later; downdraft: flap sooner
            val hz = o?.hazard
            val dxOrb = if (o != null) o.x + World.OBSTACLE_W / 2 - w.birdX else 999f
            if (o != null && hz != null && dxOrb < 70f && dxOrb > -14f) {
                // Predict the orb's height as we pass it, pick the roomier side once, aim at its middle.
                val eta = (dxOrb / w.speed).coerceAtLeast(0f)
                val orbY = o.gapY + kotlin.math.sin((w.playTime + eta) * 2.4f + hz.phase) * hz.amp
                val above = side.getOrPut(o.index) { orbY > o.gapY }
                // Flap from near the bottom of the free zone so the rise stays inside it.
                val zoneBottom = if (above) orbY - 10f else o.bottom - 5f
                target = zoneBottom - 6f
                margin = 0f
            }
            cooldown -= dt
            if (w.state == RunState.PLAYING && w.birdY > target + margin && w.birdVy > (if (w.birdY > target + 30f) -120f else -40f) && cooldown <= 0f) { w.flap(); cooldown = 0.16f }
            w.update(dt)
            w.events.clear()
            t += dt
        }
    }

    @Test fun birdFallsToTheGroundWithoutInput() {
        val w = endless(1)
        w.flap()
        repeat(60 * 5) { w.update(1f / 60f) }
        assertEquals(RunState.DEAD, w.state)
        assertEquals("ground", w.deathCause)
        assertEquals(0, w.score)
    }

    @Test fun endlessIsBeatableToPlatinumAcrossSeedsAndScreenSizes() {
        for ((seed, h) in listOf(1L to 480f, 2L to 426f, 3L to 533f, 4L to 365f, 5L to 480f)) {
            val w = endless(seed, h)
            autopilot(w, 400f, stopAt = config.medals[3])
            assertTrue("seed $seed h $h reached ${w.score} (${w.deathCause})", w.score >= config.medals[3])
        }
    }

    @Test fun difficultyRampsUp() {
        val w = endless(9)
        autopilot(w, 400f, stopAt = 80)
        assertTrue(w.speed > 100f - 0.01f)
        assertTrue("moving obstacles appear late", w.obstacles.any { it.moveAmp > 0f })
    }

    @Test fun capsulesFollowSpacingRules() {
        val w = endless(11)
        autopilot(w, 600f, stopAt = 150)
        val p = w.capsulePipes
        assertTrue("some capsules were collected", p.isNotEmpty())
        assertTrue(p.all { it >= config.drops.capsuleStartPipe && it <= w.score + 1 })
        assertTrue(p.zipWithNext().all { (a, b) -> b - a >= config.drops.capsuleMinGap })
        assertTrue(w.coins <= w.score + 1)
    }

    /** Stateless steering heuristic used as the rollout policy for the search below. */
    private fun wantsFlap(w: World): Boolean {
        val o = w.nextObstacle() ?: return w.birdY > w.groundY / 2
        var target = o.gapY
        if (o.moveAmp > 0f) {
            val eta = ((o.x - w.birdX) / w.speed).coerceAtLeast(0f) + 0.15f
            target = o.baseGapY + kotlin.math.sin((w.playTime + eta) * o.moveSpeed + o.movePhase) * o.moveAmp
        }
        return w.state == RunState.PLAYING && w.birdY > target + o.gap * 0.2f && w.birdVy > -40f
    }

    private fun newWorld(l: com.shan.pakpak.data.Level) = World(RunSpec("adventure", l, config.drops, config.economy.coinChance, l.seed), 216f, 480f)

    /** Replays [inputs] from a fresh (deterministic) world, then follows the heuristic for [extra] frames. */
    private fun survives(l: com.shan.pakpak.data.Level, inputs: List<Boolean>, extra: Int): Boolean {
        val w = newWorld(l)
        w.flap()
        val dt = 1f / 60f
        for (f in inputs) { if (f) w.flap(); w.update(dt); w.events.clear(); if (w.isDone() || w.state == RunState.DYING) return w.completed }
        repeat(extra) { if (wantsFlap(w)) w.flap(); w.update(dt); w.events.clear(); if (w.isDone() || w.state == RunState.DYING) return w.completed }
        return true
    }

    /**
     * Proves each level can be finished: at every frame choose "flap" or "wait" so that the run
     * still survives the next 0.75 s. Because levels are deterministic, a found input sequence is
     * a real solution a skilled player could perform.
     */
    @Test fun everyAdventureLevelIsBeatable() {
        val dt = 1f / 60f
        for (l in levels.levels) {
            val inputs = ArrayList<Boolean>()
            val live = newWorld(l)
            live.flap()
            var frames = 0
            while (!live.isDone() && live.state != RunState.DYING && frames < 60 * 120) {
                val preferred = wantsFlap(live)
                val choice = listOf(preferred, !preferred).firstOrNull { c -> survives(l, inputs + c, 45) } ?: preferred
                inputs.add(choice)
                if (choice) live.flap()
                live.update(dt)
                live.events.clear()
                frames++
            }
            assertTrue("level ${l.id} not beaten: score ${live.score}/${l.length} (${live.deathCause})", live.completed)
            if (l.objective.type == "finish") assertTrue(live.score >= l.length)
            if (l.objective.type == "survive") assertTrue(live.playTime >= l.objective.target)
        }
    }

    @Test fun adventureLevelsAreDeterministic() {
        val l = levels.byId["3-2"]!!
        val a = World(RunSpec("adventure", l, config.drops, 0f, l.seed), 216f, 480f)
        val b = World(RunSpec("adventure", l, config.drops, 0f, l.seed), 216f, 480f)
        assertEquals(a.obstacles.map { it.gapY }, b.obstacles.map { it.gapY })
    }

    @Test fun rarityDistributionAndCelestialRate() {
        val rnd = Random(123)
        val n = 300_000
        val counts = IntArray(Rarity.entries.size)
        repeat(n) { counts[Rules.rollCapsule(catalog, config.drops, 0, rnd)!!.rarity.ordinal]++ }
        val total = config.drops.weights.values.sum()
        for (r in Rarity.entries) {
            val expected = config.drops.weights[r]!! / total
            val got = counts[r.ordinal].toDouble() / n
            assertTrue("$r expected $expected got $got", kotlin.math.abs(got - expected) < maxOf(0.0004, expected * 0.08))
        }
        assertTrue("celestial must be exceptionally rare", counts[Rarity.CELESTIAL.ordinal].toDouble() / n < 0.002)
        // Odds shown to players sum to 100%.
        assertEquals(100.0, Rules.odds(config.drops, 0).sumOf { it.second }, 1e-6)
        // Non-pool (earned) items never drop.
        val earned = catalog.items.filter { !it.inDropPool }.map { it.id }.toSet()
        val r2 = Random(5)
        repeat(50_000) { assertFalse(Rules.rollCapsule(catalog, config.drops, 0, r2)!!.id in earned) }
    }

    @Test fun catalogMatchesSpec() {
        val want = mapOf(Rarity.COMMON to 50, Rarity.UNCOMMON to 30, Rarity.ELITE to 20, Rarity.RARE to 15, Rarity.ULTRA to 10, Rarity.LEGENDARY to 5, Rarity.CELESTIAL to 3)
        for ((r, n) in want) assertEquals(r.name, n, catalog.byRarity[r]!!.size)
        assertEquals(133, catalog.items.size)
    }

    private fun run(mode: String, score: Int, pickups: List<Int> = emptyList(), level: String? = null, gems: Int = 0, flaps: Int = 50, completed: Boolean = false, coins: Int = 0) =
        RunRecord(UUID.randomUUID().toString(), null, mode, level, score, score, gems, coins, flaps, score * 1500L, score * 11, System.currentTimeMillis(), completed, pickups)

    @Test fun guestEndlessRewardsMedalsAndCaps() {
        val prog = Progress(catalog, config, levels)
        val p = Profile("guest")
        val o = prog.apply(p, run("endless", 26, listOf(4, 12), coins = 9), Random(1), rollCapsules = true)
        assertEquals(1, o.medal) // silver
        assertEquals(26, p.bestScore)
        assertTrue(o.items.count { it.second == "capsule" } == 2)
        assertTrue("bronze+silver milestone items", o.items.count { it.second == "medal" } == 2)
        assertTrue(o.achievements.any { it.id == "runs_1" })
        assertTrue(p.coins >= 9 + 15)
        // A second silver doesn't regrant milestone items.
        val o2 = prog.apply(p, run("endless", 30), Random(2), rollCapsules = true)
        assertEquals(0, o2.items.count { it.second == "medal" })
        // Daily capsule cap.
        repeat(20) { prog.apply(p, run("endless", 20, listOf(3, 9, 15)), Random(it), rollCapsules = true) }
        assertEquals(config.economy.dailyCapsuleCap, p.dailyCapsules)
        assertTrue(p.dailyEndlessCoins <= config.economy.dailyEndlessCoinCap)
    }

    @Test fun accountRunsKeepCapsulesSealedUntilServerOpensThem() {
        val prog = Progress(catalog, config, levels)
        val p = Profile("account")
        val o = prog.apply(p, run("endless", 12, listOf(5)), Random(1), rollCapsules = false)
        assertEquals(1, o.sealedCapsules)
        assertTrue(o.items.none { it.second == "capsule" })
    }

    @Test fun adventureFirstClearReplayAndMastery() {
        val prog = Progress(catalog, config, levels)
        val p = Profile("guest")
        val boss = levels.byId["1-5"]!!
        assertFalse(prog.isUnlocked(p, "1-2"))
        for (l in levels.levels.filter { it.chapter == 1 }) {
            val o = prog.apply(p, run("adventure", l.length, level = l.id, gems = l.gems, flaps = 10, completed = true), Random(1), rollCapsules = true)
            assertTrue(o.firstClear)
            assertTrue(o.coins >= l.reward + 2 * l.bonusReward)
        }
        assertTrue(prog.isUnlocked(p, "2-1"))
        assertTrue(p.owns(boss.masteryItem!!))
        assertTrue(p.achievements.containsKey("chapter_1"))
        val replay = prog.apply(p, run("adventure", boss.length, level = boss.id, gems = 0, flaps = 999, completed = true), Random(1), rollCapsules = true)
        assertFalse(replay.firstClear)
        assertEquals(config.economy.replayReward, replay.coins - replay.achievements.sumOf { it.reward })
    }

    @Test fun profileSurvivesSerialization() {
        val prog = Progress(catalog, config, levels)
        val p = Profile("guest")
        prog.apply(p, run("endless", 55, listOf(4, 12, 20)), Random(3), rollCapsules = true)
        val copy = Profile.fromJson(JSONObject(p.toJson().toString()))
        assertEquals(p.coins, copy.coins)
        assertEquals(p.items, copy.items)
        assertEquals(p.medals.toList(), copy.medals.toList())
        assertEquals(p.achievements.keys, copy.achievements.keys)
    }
}
