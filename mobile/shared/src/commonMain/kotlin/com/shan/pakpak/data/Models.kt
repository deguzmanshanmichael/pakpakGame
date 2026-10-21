package com.shan.pakpak.data

import com.shan.pakpak.gfx.Color
import com.shan.pakpak.json.JSONArray
import com.shan.pakpak.json.JSONObject

enum class Rarity(val key: String, val label: String, val color: Int) {
    COMMON("common", "Common", Color.rgb(154, 165, 177)),
    UNCOMMON("uncommon", "Uncommon", Color.rgb(95, 207, 98)),
    ELITE("elite", "Elite", Color.rgb(63, 167, 240)),
    RARE("rare", "Rare", Color.rgb(176, 108, 242)),
    ULTRA("ultra", "Ultra", Color.rgb(242, 92, 155)),
    LEGENDARY("legendary", "Legendary", Color.rgb(255, 181, 46)),
    CELESTIAL("celestial", "Celestial", Color.rgb(125, 249, 255));

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: COMMON
    }
}

class Item(
    val id: String,
    val name: String,
    val rarity: Rarity,
    val shape: String,
    val palette: IntArray,
    val pattern: String,
    val description: String,
    val acquisitionType: String,
    val acquisitionLabel: String,
    val acquisitionKey: String?,
    val acquisitionLevel: String?,
    val tradeable: Boolean,
    val inDropPool: Boolean,
    val maxSupply: Int?,
    val issued: Int,
    val enabled: Boolean,
)

class Catalog(val version: Long, val items: List<Item>) {
    val byId = items.associateBy { it.id }
    val enabled = items.filter { it.enabled }
    val byRarity: Map<Rarity, List<Item>> = Rarity.entries.associateWith { r -> enabled.filter { it.rarity == r } }
    fun pool(r: Rarity) = byRarity[r].orEmpty().filter { it.inDropPool && (it.maxSupply == null || it.issued < it.maxSupply) }

    companion object {
        fun parse(o: JSONObject): Catalog {
            val arr = o.getJSONArray("items")
            val items = (0 until arr.length()).map { i ->
                val it = arr.getJSONObject(i)
                val pal = it.getJSONArray("palette")
                val acq = it.getJSONObject("acquisition")
                Item(
                    id = it.getString("id"), name = it.getString("name"), rarity = Rarity.of(it.getString("rarity")),
                    shape = it.getString("shape"), palette = IntArray(pal.length()) { k -> Color.parseColor(pal.getString(k)) },
                    pattern = it.optString("pattern", "plain"), description = it.getString("description"),
                    acquisitionType = acq.optString("type", "capsule"), acquisitionLabel = acq.optString("label", ""),
                    acquisitionKey = acq.optString("key").ifEmpty { null }, acquisitionLevel = acq.optString("level").ifEmpty { null },
                    tradeable = it.optBoolean("tradeable", true), inDropPool = it.optBoolean("inDropPool", true),
                    maxSupply = if (it.isNull("maxSupply") || !it.has("maxSupply")) null else it.getInt("maxSupply"),
                    issued = it.optInt("issued", 0), enabled = it.optBoolean("enabled", true),
                )
            }
            return Catalog(o.optLong("version", 1), items)
        }
    }
}

class Objective(val type: String, val target: Int, val label: String)

class Level(
    val id: String, val chapter: Int, val chapterName: String, val index: Int, val order: Int, val name: String,
    val theme: String, val obstacle: String, val seed: Long, val length: Int, val speed: Float, val spacing: Float,
    val gapStart: Float, val gapEnd: Float, val moveAmp: Float, val moveChance: Float, val hazardChance: Float,
    val wind: Float, val gems: Int, val objective: Objective, val bonusGemsLabel: String, val maxFlaps: Int,
    val bonusFlapsLabel: String, val reward: Int, val bonusReward: Int, val masteryItem: String?,
)

class Levels(val levels: List<Level>, val chapters: List<Pair<Int, String>>) {
    val byId = levels.associateBy { it.id }
    fun next(l: Level) = levels.firstOrNull { it.order == l.order + 1 }
    fun prev(l: Level) = levels.firstOrNull { it.order == l.order - 1 }

    companion object {
        fun parse(o: JSONObject): Levels {
            val arr = o.getJSONArray("levels")
            val levels = (0 until arr.length()).map { i ->
                val l = arr.getJSONObject(i)
                val obj = l.getJSONObject("objective")
                val bonus = l.getJSONArray("bonus")
                var gemsLabel = ""
                var flapsLabel = ""
                var maxFlaps = 999
                for (k in 0 until bonus.length()) {
                    val b = bonus.getJSONObject(k)
                    if (b.getString("type") == "allGems") gemsLabel = b.getString("label")
                    else { flapsLabel = b.getString("label"); maxFlaps = b.getInt("target") }
                }
                Level(
                    id = l.getString("id"), chapter = l.getInt("chapter"), chapterName = l.getString("chapterName"),
                    index = l.getInt("index"), order = l.getInt("order"), name = l.getString("name"), theme = l.getString("theme"),
                    obstacle = l.getString("obstacle"), seed = l.getLong("seed"), length = l.getInt("length"),
                    speed = l.getDouble("speed").toFloat(), spacing = l.getDouble("spacing").toFloat(),
                    gapStart = l.getDouble("gapStart").toFloat(), gapEnd = l.getDouble("gapEnd").toFloat(),
                    moveAmp = l.getDouble("moveAmp").toFloat(), moveChance = l.getDouble("moveChance").toFloat(),
                    hazardChance = l.getDouble("hazardChance").toFloat(), wind = l.getDouble("wind").toFloat(),
                    gems = l.getInt("gems"), objective = Objective(obj.getString("type"), obj.getInt("target"), obj.getString("label")),
                    bonusGemsLabel = gemsLabel, maxFlaps = maxFlaps, bonusFlapsLabel = flapsLabel,
                    reward = l.getInt("reward"), bonusReward = l.getInt("bonusReward"),
                    masteryItem = if (l.isNull("masteryItem")) null else l.getString("masteryItem"),
                )
            }
            val ch = o.getJSONArray("chapters")
            val chapters = (0 until ch.length()).map { ch.getJSONObject(it).let { c -> c.getInt("n") to c.getString("name") } }
            return Levels(levels, chapters)
        }
    }
}

class Skin(val id: String, val name: String, val price: Int, val colors: IntArray)
class Achievement(val id: String, val name: String, val desc: String, val type: String, val value: String, val reward: Int)
class Notice(val title: String, val body: String, val eligibility: String, val process: String, val contact: String)

class DropConfig(
    val capsuleChance: Float, val capsuleStartPipe: Int, val capsuleMinGap: Int,
    val weights: Map<Rarity, Double>, val depthPerPipes: Int, val depthStep: Double, val depthMax: Double,
    val depthFrom: Rarity, val adventureFirstClearCapsule: Boolean,
)

class Economy(
    val currencyName: String, val coinChance: Float, val milestones: List<Pair<Int, Int>>, val dailyEndlessCoinCap: Int,
    val dailyCapsuleCap: Int, val replayReward: Int, val dailyReplayCap: Int, val overflowCapsuleCoins: Int,
)

class GameConfig(
    val raw: JSONObject,
    val drops: DropConfig,
    val economy: Economy,
    val medals: IntArray, // bronze, silver, gold, platinum
    val skins: List<Skin>,
    val achievements: List<Achievement>,
    val notice: Notice,
    val minMsPerPipe: Int,
) {
    companion object {
        fun parse(o: JSONObject): GameConfig {
            val d = o.getJSONObject("drops")
            val w = d.getJSONObject("weights")
            val db = d.getJSONObject("depthBonus")
            val drops = DropConfig(
                capsuleChance = d.getDouble("capsuleChance").toFloat(), capsuleStartPipe = d.getInt("capsuleStartPipe"),
                capsuleMinGap = d.getInt("capsuleMinGap"),
                weights = Rarity.entries.associateWith { w.optDouble(it.key, 0.0) },
                depthPerPipes = db.getInt("perPipes"), depthStep = db.getDouble("step"), depthMax = db.getDouble("max"),
                depthFrom = Rarity.of(db.getString("fromRarity")), adventureFirstClearCapsule = d.optBoolean("adventureFirstClearCapsule", true),
            )
            val e = o.getJSONObject("economy")
            val ms = e.getJSONArray("endlessMilestones")
            val economy = Economy(
                currencyName = e.optString("currencyName", "Pak Coins"), coinChance = e.getDouble("coinChance").toFloat(),
                milestones = (0 until ms.length()).map { ms.getJSONArray(it).let { p -> p.getInt(0) to p.getInt(1) } },
                dailyEndlessCoinCap = e.getInt("dailyEndlessCoinCap"), dailyCapsuleCap = e.getInt("dailyCapsuleCap"),
                replayReward = e.getInt("replayReward"), dailyReplayCap = e.getInt("dailyReplayCap"),
                overflowCapsuleCoins = e.getInt("overflowCapsuleCoins"),
            )
            val m = o.getJSONObject("medals")
            val sk = o.getJSONArray("skins")
            val skins = (0 until sk.length()).map { i ->
                val s = sk.getJSONObject(i)
                val c = s.getJSONArray("colors")
                Skin(s.getString("id"), s.getString("name"), s.getInt("price"), IntArray(c.length()) { Color.parseColor(c.getString(it)) })
            }
            val ac = o.getJSONArray("achievements")
            val achievements = (0 until ac.length()).map { i ->
                val a = ac.getJSONObject(i)
                Achievement(a.getString("id"), a.getString("name"), a.getString("desc"), a.getString("type"), a.get("value").toString(), a.getInt("reward"))
            }
            val n = o.getJSONObject("notice")
            val notice = Notice(n.getString("title"), n.getString("body"), n.optString("eligibility"), n.optString("process"), n.optString("contact"))
            val minMs = o.optJSONObject("antiCheat")?.optInt("minMsPerPipe", 700) ?: 700
            return GameConfig(o, drops, economy, intArrayOf(m.getInt("bronze"), m.getInt("silver"), m.getInt("gold"), m.getInt("platinum")),
                skins, achievements, notice, minMs)
        }
    }
}

fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
