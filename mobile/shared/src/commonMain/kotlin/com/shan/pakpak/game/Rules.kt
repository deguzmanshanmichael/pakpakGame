package com.shan.pakpak.game

import com.shan.pakpak.data.Catalog
import com.shan.pakpak.data.DropConfig
import com.shan.pakpak.data.Economy
import com.shan.pakpak.data.Item
import com.shan.pakpak.data.Rarity
import kotlin.math.floor
import kotlin.math.min
import kotlin.random.Random

/** Game formulas. They mirror backend/src/rules.js; for accounts the server's numbers win. */
object Rules {
    val MEDALS = listOf("bronze", "silver", "gold", "platinum")

    /** Index into [MEDALS] for a score, or -1 for no medal. */
    fun medalFor(score: Int, thresholds: IntArray): Int {
        var best = -1
        for (i in thresholds.indices) if (score >= thresholds[i]) best = i
        return best
    }

    fun milestoneCoins(score: Int, eco: Economy): Int = eco.milestones.filter { score >= it.first }.sumOf { it.second }

    fun depthMultiplier(pipeIndex: Int, d: DropConfig): Double =
        min(d.depthMax, 1 + floor(pipeIndex.toDouble() / d.depthPerPipes) * d.depthStep)

    fun weights(d: DropConfig, pipeIndex: Int): List<Pair<Rarity, Double>> {
        val mult = depthMultiplier(pipeIndex, d)
        return Rarity.entries.map { r -> r to (d.weights[r] ?: 0.0) * (if (r.ordinal >= d.depthFrom.ordinal) mult else 1.0) }
    }

    fun pickRarity(weights: List<Pair<Rarity, Double>>, u: Double): Rarity {
        val total = weights.sumOf { it.second }
        var x = u * total
        for ((r, w) in weights) {
            if (x < w) return r
            x -= w
        }
        return weights.first().first
    }

    /** Percent chance per capsule for each rarity at a given depth, for the transparent odds page. */
    fun odds(d: DropConfig, pipeIndex: Int): List<Pair<Rarity, Double>> {
        val w = weights(d, pipeIndex)
        val total = w.sumOf { it.second }.coerceAtLeast(1e-9)
        return w.map { it.first to it.second * 100.0 / total }
    }

    /** Local (guest/offline) capsule roll. Falls back to a more common rarity if a pool is empty. */
    fun rollCapsule(catalog: Catalog, d: DropConfig, pipeIndex: Int, rnd: Random): Item? {
        val r = pickRarity(weights(d, pipeIndex), rnd.nextDouble())
        for (idx in r.ordinal downTo 0) {
            val pool = catalog.pool(Rarity.entries[idx])
            if (pool.isNotEmpty()) return pool[rnd.nextInt(pool.size)]
        }
        return null
    }
}
