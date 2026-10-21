package com.shan.pakpak.game

import com.shan.pakpak.nowMs
import com.shan.pakpak.data.Achievement
import com.shan.pakpak.data.Catalog
import com.shan.pakpak.data.GameConfig
import com.shan.pakpak.data.Item
import com.shan.pakpak.data.Levels
import com.shan.pakpak.data.Profile
import com.shan.pakpak.data.Rarity
import com.shan.pakpak.data.RunRecord
import com.shan.pakpak.util.utcDay
import kotlin.math.min
import kotlin.random.Random

/** What a run earned, shown on the results screen. */
class RunOutcome {
    var coins = 0
    var medal = -1
    var newBest = false
    val items = ArrayList<Pair<Item, String>>() // item, reason
    var sealedCapsules = 0
    var overflowCapsules = 0
    val achievements = ArrayList<Achievement>()
    var firstClear = false
    var bonusGems = false
    var bonusFlaps = false
    var mastered = false
    val notes = ArrayList<String>()
    /** Set when the server has answered for an account run. */
    var confirmed = false
    var rejectedReason: String? = null
}

/**
 * Applies a finished run to a profile using the same rules as the server.
 * For guests this is final. For accounts it is an optimistic preview: capsules stay sealed and
 * the server's response replaces these numbers on the next sync.
 */
class Progress(private val catalog: Catalog, private val cfg: GameConfig, private val levels: Levels) {

    fun apply(p: Profile, run: RunRecord, rnd: Random, rollCapsules: Boolean, now: Long = nowMs()): RunOutcome {
        val out = RunOutcome()
        rollDay(p, now)
        val eco = cfg.economy
        p.totalRuns++
        p.totalPipes += run.pipes
        p.totalDistance += run.distance
        p.totalFlaps += run.flaps

        if (run.mode == "endless") {
            out.medal = Rules.medalFor(run.score, cfg.medals)
            if (run.score > p.bestScore) { out.newBest = p.bestScore > 0; p.bestScore = run.score }
            val raw = run.coinsCollected + Rules.milestoneCoins(run.score, eco)
            val coins = min(raw, (eco.dailyEndlessCoinCap - p.dailyEndlessCoins).coerceAtLeast(0))
            if (coins < raw) out.notes.add("Daily coin cap reached")
            p.dailyEndlessCoins += coins
            out.coins += coins
            var room = (eco.dailyCapsuleCap - p.dailyCapsules).coerceAtLeast(0)
            for (pipe in run.pickups) {
                if (room <= 0) { out.overflowCapsules++; continue }
                room--
                p.dailyCapsules++
                if (rollCapsules) {
                    Rules.rollCapsule(catalog, cfg.drops, pipe, rnd)?.let { grant(p, it); out.items.add(it to "capsule") }
                } else {
                    out.sealedCapsules++
                    p.sealedCapsules++
                }
            }
            if (out.overflowCapsules > 0) {
                out.coins += out.overflowCapsules * eco.overflowCapsuleCoins
                out.notes.add("Daily capsule cap reached: extra capsules became coins")
            }
            if (out.medal >= 0) {
                p.medals[out.medal]++
                for (m in 0..out.medal) milestoneItem("medal_${Rules.MEDALS[m]}")?.let { item ->
                    if (!p.owns(item.id)) { grant(p, item); out.items.add(item to "medal") }
                }
            }
        } else {
            val level = levels.byId[run.levelId]
            if (level != null && run.completed) {
                val lp = p.level(level.id)
                if (!lp.completed) {
                    out.firstClear = true
                    lp.completed = true
                    out.coins += level.reward
                    if (cfg.drops.adventureFirstClearCapsule) {
                        if (rollCapsules) Rules.rollCapsule(catalog, cfg.drops, level.order * 5, rnd)?.let { grant(p, it); out.items.add(it to "first clear") }
                        else { out.sealedCapsules++; p.sealedCapsules++ }
                    }
                } else {
                    val c = min(eco.replayReward, (eco.dailyReplayCap - p.dailyReplayCoins).coerceAtLeast(0))
                    p.dailyReplayCoins += c
                    out.coins += c
                }
                if (run.gems >= level.gems) { out.bonusGems = true; if (!lp.bonusAllGems) { lp.bonusAllGems = true; out.coins += level.bonusReward } }
                if (run.flaps <= level.maxFlaps) { out.bonusFlaps = true; if (!lp.bonusFlaps) { lp.bonusFlaps = true; out.coins += level.bonusReward } }
                lp.bestGems = maxOf(lp.bestGems, run.gems)
                lp.bestFlaps = if (lp.bestFlaps == 0) run.flaps else minOf(lp.bestFlaps, run.flaps)
                if (lp.mastered) {
                    level.masteryItem?.let { id ->
                        val item = catalog.byId[id]
                        if (item != null && !p.owns(id)) { grant(p, item); out.items.add(item to "mastery"); out.mastered = true }
                    }
                }
            }
        }
        p.coins += out.coins
        out.achievements.addAll(checkAchievements(p))
        out.achievements.forEach { out.coins += it.reward }
        return out
    }

    fun grant(p: Profile, item: Item) { p.items[item.id] = (p.items[item.id] ?: 0) + 1 }

    private fun milestoneItem(key: String): Item? = catalog.items.firstOrNull { it.acquisitionType == "milestone" && it.acquisitionKey == key && it.enabled }

    /** Unlocks newly met achievements and pays their coins into the profile. */
    fun checkAchievements(p: Profile): List<Achievement> {
        val unlocked = ArrayList<Achievement>()
        for (a in cfg.achievements) {
            if (p.achievements.containsKey(a.id) || !met(p, a)) continue
            p.achievements[a.id] = nowMs()
            p.coins += a.reward
            unlocked.add(a)
        }
        return unlocked
    }

    private fun met(p: Profile, a: Achievement): Boolean {
        val v = a.value
        return when (a.type) {
            "runs" -> p.totalRuns >= v.toInt()
            "pipes" -> p.totalPipes >= v.toInt()
            "medal" -> { val idx = Rules.MEDALS.indexOf(v); idx >= 0 && (idx until 4).any { p.medals[it] > 0 } }
            "chapter" -> levels.levels.filter { it.chapter == v.toInt() }.all { p.adventure[it.id]?.completed == true }
            "masterAll" -> levels.levels.all { p.adventure[it.id]?.mastered == true }
            "distinct" -> p.distinctOwned >= v.toInt()
            "rarity" -> { val r = Rarity.of(v); p.items.any { (id, n) -> n > 0 && (catalog.byId[id]?.rarity?.ordinal ?: -1) >= r.ordinal } }
            "friends" -> p.friends >= v.toInt()
            "trades" -> p.tradesCompleted >= v.toInt()
            else -> false
        }
    }

    fun isUnlocked(p: Profile, levelId: String): Boolean {
        val l = levels.byId[levelId] ?: return false
        val prev = levels.prev(l) ?: return true
        return p.adventure[prev.id]?.completed == true
    }

    companion object {
        /** Daily caps reset at 00:00 UTC, the same day boundary the server uses. */
        fun rollDay(p: Profile, now: Long) {
            val day = utcDay(now)
            if (p.dailyDay != day) {
                p.dailyDay = day
                p.dailyEndlessCoins = 0
                p.dailyCapsules = 0
                p.dailyReplayCoins = 0
            }
        }
    }
}
