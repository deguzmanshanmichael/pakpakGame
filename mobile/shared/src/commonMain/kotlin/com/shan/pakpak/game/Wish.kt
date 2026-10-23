package com.shan.pakpak.game

import com.shan.pakpak.data.Catalog
import com.shan.pakpak.data.GameConfig
import com.shan.pakpak.data.Profile
import com.shan.pakpak.data.Rarity
import com.shan.pakpak.data.WishConfig
import com.shan.pakpak.data.WishEntry
import kotlin.random.Random

/** Pity state carried between pulls. */
data class WishState(val pity5: Int, val pity4: Int, val guaranteedFeatured: Boolean)

class WishPull(
    val tier: Int, val type: String, val id: String? = null, val amount: Int = 0,
    val featured: Boolean = false, val minRarity: Rarity = Rarity.COMMON, val pityAtPull: Int,
)

/**
 * Hiling ni Tala pull rules. Mirrors backend/src/wish.js exactly; accounts always roll on the server,
 * guests (offline) roll here.
 */
object Wish {
    fun star5Rate(n: Int, w: WishConfig): Double = when {
        n >= w.hardPity5 -> 1.0
        n >= w.softStart -> minOf(1.0, w.base5 + (n - w.softStart + 1) * w.softStep)
        else -> w.base5
    }

    fun star4Rate(n: Int, w: WishConfig): Double = when {
        n >= w.hardPity4 -> 1.0
        n >= w.softAt4 -> minOf(1.0, w.base4 + w.softBonus4)
        else -> w.base4
    }

    /** Average pulls needed for a megabird, including soft and hard pity (shown on the odds page). */
    fun expectedPullsFor5(w: WishConfig): Double {
        var reach = 1.0
        var expected = 0.0
        for (n in 1..w.hardPity5) {
            val p = star5Rate(n, w)
            expected += reach * p * n
            reach *= 1 - p
        }
        return expected
    }

    private fun pickWeighted(pairs: List<Pair<Int, Int>>, u: Double): Int {
        val total = pairs.sumOf { it.second }
        var x = u * total
        for ((v, wt) in pairs) {
            if (x < wt) return v
            x -= wt
        }
        return pairs.last().first
    }

    fun roll(state: WishState, w: WishConfig, megabirds: List<String>, wishSkins: List<String>, rnd: Random): Pair<WishPull, WishState> {
        val n5 = state.pity5 + 1
        val n4 = state.pity4 + 1
        if (rnd.nextDouble() < star5Rate(n5, w)) {
            val others = megabirds.filter { it != w.featured }
            val featured = state.guaranteedFeatured || others.isEmpty() || rnd.nextDouble() < w.featuredChance
            val id = if (featured) w.featured else others[minOf(others.size - 1, (rnd.nextDouble() * others.size).toInt())]
            return WishPull(5, "megabird", id, featured = featured, pityAtPull = n5) to WishState(0, 0, !featured)
        }
        if (rnd.nextDouble() < star4Rate(n4, w)) {
            val next = WishState(n5, 0, state.guaranteedFeatured)
            if (wishSkins.isNotEmpty() && rnd.nextDouble() < w.skinShare) {
                val id = wishSkins[minOf(wishSkins.size - 1, (rnd.nextDouble() * wishSkins.size).toInt())]
                return WishPull(4, "skin", id, pityAtPull = n5) to next
            }
            return WishPull(4, "collectible", minRarity = Rarity.RARE, pityAtPull = n5) to next
        }
        val next = WishState(n5, n4, state.guaranteedFeatured)
        if (rnd.nextDouble() < w.coinShare) return WishPull(3, "coins", amount = pickWeighted(w.coinPrizes, rnd.nextDouble()), pityAtPull = n5) to next
        return WishPull(3, "collectible", minRarity = Rarity.COMMON, pityAtPull = n5) to next
    }

    /** Guest/offline pulls: spends tokens and applies prizes to the local profile. */
    fun pullLocal(p: Profile, count: Int, catalog: Catalog, cfg: GameConfig, progress: Progress, rnd: Random = Random.Default): List<WishEntry> {
        require(p.tokens >= count) { "not enough tokens" }
        val w = cfg.wish
        val megas = cfg.megabirds.map { it.id }
        val wishSkins = cfg.skins.filter { it.wishOnly }.map { it.id }
        var state = WishState(p.pity5, p.pity4, p.guaranteedFeatured)
        val out = ArrayList<WishEntry>()
        repeat(count) {
            val (pull, next) = roll(state, w, megas, wishSkins, rnd)
            state = next
            var id = pull.id
            var amount = pull.amount
            var dup = false
            var name: String
            when (pull.type) {
                "megabird" -> {
                    name = cfg.megabird(id)?.name ?: id.orEmpty()
                    if (!p.megabirds.add(id!!)) { dup = true; amount = w.duplicateMegabirdCoins; p.coins += amount }
                }
                "skin" -> {
                    name = cfg.skins.firstOrNull { it.id == id }?.name ?: id.orEmpty()
                    if (!p.skins.add(id!!)) { dup = true; amount = w.duplicateSkinCoins; p.coins += amount }
                }
                "coins" -> { name = "$amount ${cfg.economy.currencyName}"; p.coins += amount }
                else -> {
                    val item = Rules.rollCapsule(catalog, cfg.drops, 0, rnd, pull.minRarity)
                    if (item != null) progress.grant(p, item)
                    id = item?.id
                    name = item?.name ?: "?"
                }
            }
            out.add(WishEntry(pull.tier, pull.type, id, name, amount, dup, pull.featured, pull.pityAtPull))
        }
        p.tokens -= count
        p.pity5 = state.pity5
        p.pity4 = state.pity4
        p.guaranteedFeatured = state.guaranteedFeatured
        p.totalPulls += count
        p.wishHistory.addAll(out)
        while (p.wishHistory.size > 60) p.wishHistory.removeAt(0)
        progress.checkAchievements(p)
        return out
    }
}
