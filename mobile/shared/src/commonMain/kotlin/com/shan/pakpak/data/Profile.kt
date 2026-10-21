package com.shan.pakpak.data

import com.shan.pakpak.nowMs
import com.shan.pakpak.json.JSONArray
import com.shan.pakpak.json.JSONObject

class LevelProgress(
    var completed: Boolean = false,
    var bonusAllGems: Boolean = false,
    var bonusFlaps: Boolean = false,
    var bestGems: Int = 0,
    var bestFlaps: Int = 0,
) {
    val mastered get() = completed && bonusAllGems && bonusFlaps
    val stars get() = (if (completed) 1 else 0) + (if (bonusAllGems) 1 else 0) + (if (bonusFlaps) 1 else 0)
}

/** A finished run waiting to be submitted to the server (accounts only). */
class RunRecord(
    val clientRunId: String,
    var ticketId: String?,
    val mode: String,
    val levelId: String?,
    val score: Int,
    val pipes: Int,
    val gems: Int,
    val coinsCollected: Int,
    val flaps: Int,
    val durationMs: Long,
    val distance: Int,
    val playedAt: Long,
    val completed: Boolean,
    val pickups: List<Int>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("clientRunId", clientRunId); put("ticketId", ticketId ?: JSONObject.NULL); put("mode", mode)
        if (levelId != null) put("levelId", levelId)
        put("score", score); put("pipes", pipes); put("gems", gems); put("coinsCollected", coinsCollected)
        put("flaps", flaps); put("durationMs", durationMs); put("distance", distance); put("playedAt", playedAt)
        put("completed", completed); put("pickups", JSONArray(pickups))
    }

    companion object {
        fun fromJson(o: JSONObject) = RunRecord(
            o.getString("clientRunId"), if (o.isNull("ticketId")) null else o.optString("ticketId").ifEmpty { null },
            o.getString("mode"), o.optString("levelId").ifEmpty { null }, o.getInt("score"), o.getInt("pipes"), o.optInt("gems"),
            o.optInt("coinsCollected"), o.optInt("flaps"), o.getLong("durationMs"), o.optInt("distance"), o.getLong("playedAt"),
            o.optBoolean("completed"), o.optJSONArray("pickups")?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList(),
        )
    }
}

/**
 * Player progress. The guest profile is the source of truth for offline play. An account profile is
 * a local cache of the server's state plus optimistic updates for runs that are still queued.
 */
class Profile(val kind: String) {
    var coins = 0
    var bestScore = 0
    var bestScoreVerified = 0
    var totalRuns = 0
    var totalPipes = 0
    var totalDistance = 0L
    var totalFlaps = 0L
    var tradesCompleted = 0
    var friends = 0
    val medals = IntArray(4)
    val items = HashMap<String, Int>()
    /** Account only: per-item copies that are bound (not tradeable). */
    val boundCounts = HashMap<String, Int>()
    /** Account only: designs whose ownership the server has verified for the completion prize. */
    var verifiedDistinct = 0
    val adventure = HashMap<String, LevelProgress>()
    val achievements = HashMap<String, Long>()
    val skins = HashSet<String>().apply { add("pakpak") }
    var equippedSkin = "pakpak"
    var dailyDay = ""
    var dailyEndlessCoins = 0
    var dailyCapsules = 0
    var dailyReplayCoins = 0
    var sealedCapsules = 0
    val pendingRuns = ArrayList<RunRecord>()
    // Account fields
    var userId = 0L
    var username = ""
    var playerCode = ""
    var role = "player"
    var bio = ""
    var profileVisibility = "friends"
    var friendRequests = "everyone"
    var guestImported = false
    var redemptionStatus: String? = null
    var redemptionReason: String? = null
    var lastSync = 0L
    var guestImportOffered = false

    val distinctOwned get() = items.count { it.value > 0 }
    fun owns(id: String) = (items[id] ?: 0) > 0
    fun level(id: String) = adventure.getOrPut(id) { LevelProgress() }

    fun toJson(): JSONObject = JSONObject().apply {
        put("v", 1); put("kind", kind); put("coins", coins); put("bestScore", bestScore); put("bestScoreVerified", bestScoreVerified)
        put("totalRuns", totalRuns); put("totalPipes", totalPipes); put("totalDistance", totalDistance); put("totalFlaps", totalFlaps)
        put("tradesCompleted", tradesCompleted); put("friends", friends); put("medals", JSONArray(medals.toList()))
        put("items", JSONObject(items as Map<*, *>)); put("bound", JSONObject(boundCounts as Map<*, *>)); put("verifiedDistinct", verifiedDistinct)
        put("adventure", JSONObject().also { a ->
            for ((k, p) in adventure) a.put(k, JSONObject().put("c", p.completed).put("g", p.bonusAllGems).put("f", p.bonusFlaps)
                .put("bg", p.bestGems).put("bf", p.bestFlaps))
        })
        put("achievements", JSONObject(achievements as Map<*, *>)); put("skins", JSONArray(skins.toList())); put("equippedSkin", equippedSkin)
        put("dailyDay", dailyDay); put("dailyEndlessCoins", dailyEndlessCoins); put("dailyCapsules", dailyCapsules); put("dailyReplayCoins", dailyReplayCoins)
        put("sealedCapsules", sealedCapsules); put("pendingRuns", JSONArray(pendingRuns.map { it.toJson() }))
        put("userId", userId); put("username", username); put("playerCode", playerCode); put("role", role); put("bio", bio)
        put("profileVisibility", profileVisibility); put("friendRequests", friendRequests); put("guestImported", guestImported)
        put("redemptionStatus", redemptionStatus ?: JSONObject.NULL); put("redemptionReason", redemptionReason ?: JSONObject.NULL)
        put("lastSync", lastSync); put("guestImportOffered", guestImportOffered)
    }

    companion object {
        fun fromJson(o: JSONObject): Profile {
            val p = Profile(o.optString("kind", "guest"))
            p.coins = o.optInt("coins"); p.bestScore = o.optInt("bestScore"); p.bestScoreVerified = o.optInt("bestScoreVerified")
            p.totalRuns = o.optInt("totalRuns"); p.totalPipes = o.optInt("totalPipes"); p.totalDistance = o.optLong("totalDistance")
            p.totalFlaps = o.optLong("totalFlaps"); p.tradesCompleted = o.optInt("tradesCompleted"); p.friends = o.optInt("friends")
            o.optJSONArray("medals")?.let { for (i in 0 until minOf(4, it.length())) p.medals[i] = it.getInt(i) }
            o.optJSONObject("items")?.let { j -> for (k in j.keys()) p.items[k] = j.getInt(k) }
            o.optJSONObject("bound")?.let { j -> for (k in j.keys()) p.boundCounts[k] = j.getInt(k) }
            p.verifiedDistinct = o.optInt("verifiedDistinct")
            o.optJSONObject("adventure")?.let { j ->
                for (k in j.keys()) {
                    val l = j.getJSONObject(k)
                    p.adventure[k] = LevelProgress(l.optBoolean("c"), l.optBoolean("g"), l.optBoolean("f"), l.optInt("bg"), l.optInt("bf"))
                }
            }
            o.optJSONObject("achievements")?.let { j -> for (k in j.keys()) p.achievements[k] = j.getLong(k) }
            o.optJSONArray("skins")?.let { a -> for (i in 0 until a.length()) p.skins.add(a.getString(i)) }
            p.equippedSkin = o.optString("equippedSkin", "pakpak")
            p.dailyDay = o.optString("dailyDay"); p.dailyEndlessCoins = o.optInt("dailyEndlessCoins")
            p.dailyCapsules = o.optInt("dailyCapsules"); p.dailyReplayCoins = o.optInt("dailyReplayCoins")
            p.sealedCapsules = o.optInt("sealedCapsules")
            o.optJSONArray("pendingRuns")?.let { a -> for (i in 0 until a.length()) runCatching { p.pendingRuns.add(RunRecord.fromJson(a.getJSONObject(i))) } }
            p.userId = o.optLong("userId"); p.username = o.optString("username"); p.playerCode = o.optString("playerCode")
            p.role = o.optString("role", "player"); p.bio = o.optString("bio")
            p.profileVisibility = o.optString("profileVisibility", "friends"); p.friendRequests = o.optString("friendRequests", "everyone")
            p.guestImported = o.optBoolean("guestImported")
            p.redemptionStatus = if (o.isNull("redemptionStatus")) null else o.optString("redemptionStatus").ifEmpty { null }
            p.redemptionReason = if (o.isNull("redemptionReason")) null else o.optString("redemptionReason").ifEmpty { null }
            p.lastSync = o.optLong("lastSync"); p.guestImportOffered = o.optBoolean("guestImportOffered")
            return p
        }
    }

    /** Replaces cached values with the server's authoritative state (pending runs are kept). */
    fun applyServerState(s: JSONObject) {
        val user = s.getJSONObject("user")
        userId = user.getLong("id"); username = user.getString("username"); playerCode = user.getString("playerCode"); role = user.optString("role")
        val prof = s.getJSONObject("profile")
        coins = prof.getInt("coins"); bio = prof.optString("bio"); equippedSkin = prof.optString("equippedSkin", "pakpak")
        profileVisibility = prof.optString("profileVisibility", "friends"); friendRequests = prof.optString("friendRequests", "everyone")
        guestImported = prof.optBoolean("guestImported")
        val st = s.getJSONObject("stats")
        bestScore = st.getInt("bestScore"); bestScoreVerified = st.getInt("bestScoreVerified"); totalRuns = st.getInt("totalRuns")
        totalPipes = st.getInt("totalPipes"); totalDistance = st.getLong("totalDistance"); totalFlaps = st.getLong("totalFlaps")
        tradesCompleted = st.optInt("tradesCompleted"); friends = st.optInt("friends")
        val m = st.getJSONObject("medals")
        medals[0] = m.getInt("bronze"); medals[1] = m.getInt("silver"); medals[2] = m.getInt("gold"); medals[3] = m.getInt("platinum")
        val col = s.getJSONObject("collection")
        items.clear(); boundCounts.clear()
        val arr = col.getJSONArray("items")
        for (i in 0 until arr.length()) {
            val it = arr.getJSONObject(i)
            items[it.getString("id")] = it.getInt("count")
            boundCounts[it.getString("id")] = it.optInt("bound")
        }
        verifiedDistinct = col.optInt("verifiedDistinct")
        adventure.clear()
        val adv = s.getJSONArray("adventure")
        for (i in 0 until adv.length()) {
            val a = adv.getJSONObject(i)
            adventure[a.getString("levelId")] = LevelProgress(!a.isNull("completedAt"), a.optBoolean("bonusAllGems"), a.optBoolean("bonusFlaps"),
                a.optInt("bestGems"), a.optInt("bestFlaps"))
        }
        achievements.clear()
        val ach = s.getJSONArray("achievements")
        for (i in 0 until ach.length()) ach.getJSONObject(i).let { achievements[it.getString("id")] = it.getLong("unlockedAt") }
        skins.clear()
        val sk = s.getJSONArray("skins")
        for (i in 0 until sk.length()) skins.add(sk.getString(i))
        val red = s.optJSONObject("redemption")
        redemptionStatus = red?.optString("status")
        redemptionReason = red?.let { if (it.isNull("decisionReason")) null else it.optString("decisionReason") }
        sealedCapsules = 0
        lastSync = nowMs()
    }
}

class Settings {
    var music = true
    var sfx = true
    var musicVolume = 6
    var sfxVolume = 8
    var haptics = true
    var showFps = false

    fun toJson() = JSONObject().put("music", music).put("sfx", sfx).put("musicVolume", musicVolume).put("sfxVolume", sfxVolume)
        .put("haptics", haptics).put("showFps", showFps)

    companion object {
        fun fromJson(o: JSONObject?) = Settings().apply {
            if (o == null) return@apply
            music = o.optBoolean("music", true); sfx = o.optBoolean("sfx", true)
            musicVolume = o.optInt("musicVolume", 6).coerceIn(0, 10); sfxVolume = o.optInt("sfxVolume", 8).coerceIn(0, 10)
            haptics = o.optBoolean("haptics", true); showFps = o.optBoolean("showFps", false)
        }
    }
}
