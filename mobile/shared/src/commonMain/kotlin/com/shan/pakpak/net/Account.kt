package com.shan.pakpak.net

import com.shan.pakpak.G
import com.shan.pakpak.data.Profile
import com.shan.pakpak.data.RunRecord
import com.shan.pakpak.data.SaveStore
import com.shan.pakpak.game.RunOutcome
import com.shan.pakpak.json.JSONArray
import com.shan.pakpak.json.JSONObject
import kotlin.random.Random

/**
 * Owns the guest profile, the signed-in account (if any), the offline run queue and sync.
 *
 * Conflict rules: the guest profile is device-local and authoritative for offline play. For an
 * account the server is authoritative; local values are optimistic until the queued run is
 * acknowledged, then replaced wholesale by the server's state. Each run carries a client UUID so
 * retries can never be rewarded twice.
 */
class Account(private val store: SaveStore, private val api: Api) {
    val guest: Profile = store.read(GUEST_FILE)?.let { runCatching { Profile.fromJson(it) }.getOrNull() } ?: Profile("guest")
    var acct: Profile? = null
        private set
    private var token: String? = null

    val profile: Profile get() = acct ?: guest
    val signedIn get() = acct != null && token != null
    val isGuest get() = acct == null

    var unreadMessages = 0
    var friendRequests = 0
    var tradesAwaiting = 0
    private var lastNotifiedMessage = 0L
    /** User id whose chat is open, so we don't pop a banner for messages you're already reading. */
    var activeChatUser = 0L

    private val outcomes = HashMap<String, RunOutcome>()
    private var syncing = false
    private var syncTimer = 2f
    private var pollTimer = 3f

    init {
        store.read(SESSION_FILE)?.let { s ->
            val t = s.optString("token")
            val uid = s.optLong("userId")
            if (t.isNotEmpty() && uid > 0) {
                token = t
                api.token = t
                acct = store.read(accountFile(uid))?.let { runCatching { Profile.fromJson(it) }.getOrNull() } ?: Profile("account").also { it.userId = uid }
            }
        }
        api.onUnauthorized = { sessionExpired() }
    }

    fun save() {
        store.write(GUEST_FILE, guest.toJson())
        acct?.let { store.write(accountFile(it.userId), it.toJson()) }
    }

    fun saveBlocking() {
        store.writeBlocking(GUEST_FILE, guest.toJson())
        acct?.let { store.writeBlocking(accountFile(it.userId), it.toJson()) }
    }

    // ---------------------------------------------------------------- runs

    /** Asks the server for a run ticket so the run can count for the online leaderboard. */
    fun startRun(mode: String, levelId: String?, onTicket: (String) -> Unit) {
        if (!signedIn || !G.net.online) return
        val body = JSONObject().put("mode", mode)
        if (levelId != null) body.put("levelId", levelId)
        api.post("/api/runs/start", body) { r -> if (r.ok) onTicket(r.obj().getString("ticketId")) }
    }

    fun finishRun(rec: RunRecord): RunOutcome {
        val p = profile
        val outcome = G.progress.apply(p, rec, Random.Default, rollCapsules = isGuest)
        if (!isGuest) {
            p.pendingRuns.add(rec)
            outcomes[rec.clientRunId] = outcome
            if (outcomes.size > 20) outcomes.remove(outcomes.keys.first())
            sync()
        }
        save()
        return outcome
    }

    val pendingCount get() = acct?.pendingRuns?.size ?: 0

    fun sync() {
        val a = acct ?: return
        if (!signedIn || syncing || !G.net.online) return
        syncing = true
        if (a.pendingRuns.isEmpty()) {
            api.get("/api/me/state") { r ->
                syncing = false
                if (r.ok && acct === a && a.pendingRuns.isEmpty()) { a.applyServerState(r.obj()); save() }
            }
            return
        }
        val batch = a.pendingRuns.take(50)
        val body = JSONObject().put("runs", JSONArray(batch.map { it.toJson() }))
        api.post("/api/runs/submit", body, retry = true) { r ->
            syncing = false
            if (acct !== a) return@post
            if (r.ok) {
                val results = r.obj().getJSONArray("results")
                val done = HashSet<String>()
                for (i in 0 until results.length()) {
                    val res = results.getJSONObject(i)
                    val id = res.optString("clientRunId")
                    done.add(id)
                    outcomes[id]?.let { applyResult(it, res) }
                }
                // Runs the server could not even parse are dropped too, so one bad record can't block the queue.
                a.pendingRuns.removeAll { it.clientRunId in done || batch.contains(it) }
                a.applyServerState(r.obj().getJSONObject("state"))
                save()
                if (a.pendingRuns.isNotEmpty()) G.post { sync() }
            }
        }
    }

    private fun applyResult(o: RunOutcome, res: JSONObject) {
        o.confirmed = true
        if (res.optString("status") != "accepted") {
            o.rejectedReason = res.optString("reason", "rejected")
            o.coins = 0
            o.items.clear()
            o.sealedCapsules = 0
            return
        }
        o.coins = res.optInt("coins")
        o.sealedCapsules = 0
        o.items.clear()
        val items = res.optJSONArray("items") ?: JSONArray()
        for (i in 0 until items.length()) {
            val it = items.getJSONObject(i)
            G.catalog.byId[it.getString("collectibleId")]?.let { item -> o.items.add(item to it.optString("reason")) }
        }
        o.overflowCapsules = res.optInt("overflowCapsules")
        o.achievements.clear()
        val ach = res.optJSONArray("achievements") ?: JSONArray()
        for (i in 0 until ach.length()) G.config.achievements.firstOrNull { it.id == ach.getJSONObject(i).getString("id") }?.let { o.achievements.add(it) }
    }

    fun refreshState(cb: (() -> Unit)? = null) {
        val a = acct ?: return
        if (!G.net.online) return
        api.get("/api/me/state") { r ->
            if (r.ok && acct === a && a.pendingRuns.isEmpty()) { a.applyServerState(r.obj()); save() }
            cb?.invoke()
        }
    }

    fun tick(dt: Float) {
        if (!signedIn || !G.net.online) return
        syncTimer -= dt
        pollTimer -= dt
        if (syncTimer <= 0f) { syncTimer = 90f; sync() }
        if (pollTimer <= 0f) { pollTimer = 20f; pollNotifications() }
    }

    fun pollSoon() { pollTimer = 0f }

    private fun pollNotifications() {
        api.get("/api/notifications") { r ->
            if (!r.ok) return@get
            val o = r.obj()
            unreadMessages = o.optInt("unreadMessages")
            friendRequests = o.optInt("friendRequests")
            tradesAwaiting = o.optInt("tradesAwaiting")
            val latest = o.optJSONObject("latestMessage")
            if (latest != null) {
                val id = latest.optLong("id")
                if (id > lastNotifiedMessage) {
                    if (lastNotifiedMessage != 0L && latest.optLong("user_id") != activeChatUser) {
                        G.scenes.toast("New message from ${latest.optString("username")}")
                        G.audio.play("coin")
                    }
                    lastNotifiedMessage = id
                }
            }
        }
    }

    // ---------------------------------------------------------------- auth

    fun register(username: String, password: String, cb: (ApiResult, String?) -> Unit) {
        api.post("/api/auth/register", JSONObject().put("username", username).put("password", password)) { r ->
            if (r.ok) onAuthenticated(r.obj())
            cb(r, r.json?.optString("recoveryCode"))
        }
    }

    fun login(username: String, password: String, cb: (ApiResult) -> Unit) {
        api.post("/api/auth/login", JSONObject().put("username", username).put("password", password)) { r ->
            if (r.ok) onAuthenticated(r.obj())
            cb(r)
        }
    }

    fun recover(username: String, code: String, newPassword: String, cb: (ApiResult, String?) -> Unit) {
        api.post("/api/auth/recover", JSONObject().put("username", username).put("recoveryCode", code).put("newPassword", newPassword)) { r ->
            if (r.ok) onAuthenticated(r.obj())
            cb(r, r.json?.optString("recoveryCode"))
        }
    }

    private fun onAuthenticated(o: JSONObject) {
        val t = o.getString("token")
        val state = o.getJSONObject("state")
        val uid = state.getJSONObject("user").getLong("id")
        token = t
        api.token = t
        store.write(SESSION_FILE, JSONObject().put("token", t).put("userId", uid))
        // Keep any runs this account queued on this device before (e.g. if the session expired offline).
        val p = store.read(accountFile(uid))?.let { runCatching { Profile.fromJson(it) }.getOrNull() } ?: Profile("account")
        p.applyServerState(state)
        acct = p
        unreadMessages = 0; friendRequests = 0; tradesAwaiting = 0; lastNotifiedMessage = 0
        save()
        sync()
        pollSoon()
    }

    fun logout(cb: (() -> Unit)? = null) {
        val t = token
        if (t != null && G.net.online) api.post("/api/auth/logout") { cb?.invoke() } else cb?.invoke()
        save()
        clearSession()
    }

    private fun clearSession() {
        token = null
        api.token = null
        acct = null
        store.delete(SESSION_FILE)
        unreadMessages = 0; friendRequests = 0; tradesAwaiting = 0
    }

    private fun sessionExpired() {
        if (token == null) return
        save()
        clearSession()
        G.scenes.toast("Session expired. Please sign in again.")
    }

    /** One-time upload of guest progress into a new account. Imported items stay account-bound. */
    fun importGuest(cb: (ApiResult) -> Unit) {
        val g = guest
        val body = JSONObject()
            .put("items", JSONObject(g.items as Map<*, *>))
            .put("adventure", JSONArray(g.adventure.filter { it.value.completed }.keys.map { JSONObject().put("levelId", it).put("completed", true) }))
            .put("bestScore", g.bestScore)
            .put("coins", g.coins)
        api.post("/api/me/import-guest", body) { r ->
            if (r.ok) { acct?.applyServerState(r.obj().getJSONObject("state")); save() }
            cb(r)
        }
    }

    fun buySkin(id: String, price: Int, cb: (Boolean, String) -> Unit) {
        val p = profile
        if (isGuest) {
            if (p.coins < price) return cb(false, "Not enough coins")
            p.coins -= price
            p.skins.add(id)
            save()
            return cb(true, "Unlocked!")
        }
        if (!G.net.online) return cb(false, "Go online to buy skins on your account")
        api.post("/api/shop/buy-skin", JSONObject().put("skinId", id)) { r ->
            if (r.ok) { acct?.applyServerState(r.obj()); save() }
            cb(r.ok, if (r.ok) "Unlocked!" else r.message)
        }
    }

    fun equipSkin(id: String) {
        val p = profile
        p.equippedSkin = id
        save()
        if (!isGuest && G.net.online) api.post("/api/shop/equip-skin", JSONObject().put("skinId", id)) { r ->
            if (r.ok) { acct?.applyServerState(r.obj()); save() }
        }
    }

    // ---------------------------------------------------------------- Hiling ni Tala

    fun buyTokens(count: Int, cb: (Boolean, String) -> Unit) {
        val p = profile
        val price = G.config.wish.tokenPrice * count
        if (isGuest) {
            if (p.coins < price) return cb(false, "Not enough Pak Coins")
            p.coins -= price
            p.tokens += count
            save()
            return cb(true, "Bought $count ${G.config.wish.tokenName}${if (count > 1) "s" else ""}")
        }
        if (!G.net.online) return cb(false, "Go online to buy tokens on your account")
        if (p.coins < price) return cb(false, "Not enough Pak Coins")
        val body = JSONObject().put("count", count).put("clientBuyId", com.shan.pakpak.util.newId())
        api.post("/api/wish/buy-tokens", body, retry = true) { r ->
            if (r.ok) { acct?.applyServerState(r.obj().getJSONObject("state")); save() }
            cb(r.ok, if (r.ok) "Bought $count ${G.config.wish.tokenName}${if (count > 1) "s" else ""}" else r.message)
        }
    }

    /** Pulls [count] wishes. Guests roll locally; accounts roll on the server (pity is stored there). */
    fun wish(count: Int, cb: (List<com.shan.pakpak.data.WishEntry>?, String) -> Unit) {
        val p = profile
        if (p.tokens < count) return cb(null, "You need $count ${G.config.wish.tokenName}${if (count > 1) "s" else ""}")
        if (isGuest) {
            val res = com.shan.pakpak.game.Wish.pullLocal(p, count, G.catalog, G.config, G.progress)
            save()
            return cb(res, "")
        }
        if (!G.net.online) return cb(null, "Go online to wish on your account. Guest wishes work offline.")
        // The client id makes retries safe: the server returns the same results instead of pulling again.
        val body = JSONObject().put("count", count).put("clientWishId", com.shan.pakpak.util.newId())
        api.post("/api/wish/pull", body, retry = true) { r ->
            if (!r.ok) return@post cb(null, r.message)
            val arr = r.obj().getJSONArray("results")
            val res = (0 until arr.length()).map { i ->
                val e = arr.getJSONObject(i)
                com.shan.pakpak.data.WishEntry(e.optInt("tier", 3), e.optString("type"), if (e.isNull("id")) null else e.optString("id"),
                    e.optString("name"), e.optInt("amount"), e.optBoolean("duplicate"), e.optBoolean("featured"), e.optInt("pity"))
            }
            acct?.let { a ->
                a.applyServerState(r.obj().getJSONObject("state"))
                a.wishHistory.addAll(res)
                while (a.wishHistory.size > 60) a.wishHistory.removeAt(0)
            }
            save()
            cb(res, "")
        }
    }

    fun selectMegabird(id: String) {
        profile.selectedMegabird = id
        save()
    }

    companion object {
        const val GUEST_FILE = "guest.json"
        const val SESSION_FILE = "session.json"
        fun accountFile(uid: Long) = "account_$uid.json"
    }
}
