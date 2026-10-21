package com.shan.pakpak

import com.shan.pakpak.data.RunRecord
import com.shan.pakpak.net.Account
import com.shan.pakpak.net.Api
import com.shan.pakpak.net.Connectivity
import com.shan.pakpak.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Drives the real client classes (Api, Account, sync queue) against a running backend.
 * Start one with `npm start` in backend/ (default http://127.0.0.1:8080, or set PAKPAK_TEST_SERVER).
 * Skipped automatically when no server is reachable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnlineIntegrationTest {
    private val base = System.getenv("PAKPAK_TEST_SERVER") ?: "http://127.0.0.1:8080"

    private fun serverUp() = try {
        (URL("$base/api/health").openConnection() as HttpURLConnection).run { connectTimeout = 1500; responseCode == 200 }
    } catch (_: Exception) { false }

    /** Pumps the game-thread queue until [done] or timeout. */
    private fun pump(timeoutMs: Long = 15000, done: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!done()) {
            shadowOf(Looper.getMainLooper()).idle()
            G.runQueued()
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(20)
        }
    }

    private fun run(score: Int, pickups: List<Int>, ticket: String? = null, durationMs: Long = score * 1500L) =
        RunRecord(UUID.randomUUID().toString(), ticket, "endless", null, score, score, 0, score / 2, score * 3, durationMs,
            score * 11, System.currentTimeMillis(), false, pickups)

    @Test fun fullOnlineFlow() {
        assumeTrue("backend not running at $base", serverUp())
        AndroidPlatform.init(RuntimeEnvironment.getApplication())
        G.init(PlatformConfig("", "test", "Android"))
        val api = Api(base)
        G.api = api
        G.net = Connectivity(api).also { it.overrideForTest(Connectivity.State.ONLINE) }
        G.account = Account(G.store, api)
        val acc = G.account
        val name = "it_" + UUID.randomUUID().toString().take(8).replace("-", "")

        // Register
        var code: String? = null
        var ok = false
        acc.register(name, "flapflap123") { r, c -> ok = r.ok; code = c }
        pump { code != null || (!ok && G.account.signedIn.not() && false) }
        assertTrue(acc.signedIn)
        assertNotNull(code)

        // A ticketed online run: capsules are opened by the server and revealed in the outcome.
        var ticket: String? = null
        acc.startRun("endless", null) { ticket = it }
        pump { ticket != null }
        Thread.sleep(1200)
        val o1 = acc.finishRun(run(12, listOf(4, 10), ticket, durationMs = 1100))
        // Too fast for 12 obstacles -> rejected. Use a plausible duration instead:
        pump { o1.confirmed }
        assertEquals("too_fast", o1.rejectedReason)

        val o2 = acc.finishRun(run(12, listOf(4, 10)))
        assertEquals(2, o2.sealedCapsules)
        pump { o2.confirmed }
        assertEquals(null, o2.rejectedReason)
        assertTrue("server opened both capsules (+ bronze milestone)", o2.items.size >= 3)
        assertEquals(0, acc.pendingCount)
        val coinsAfter = acc.profile.coins
        assertTrue(coinsAfter > 0)
        assertEquals(12, acc.profile.bestScore)

        // Offline: runs queue up locally, then sync when the connection returns.
        G.net.overrideForTest(Connectivity.State.OFFLINE)
        val o3 = acc.finishRun(run(30, listOf(5)))
        assertEquals(1, acc.pendingCount)
        assertTrue(!o3.confirmed)
        assertEquals(30, acc.profile.bestScore) // optimistic
        G.net.overrideForTest(Connectivity.State.ONLINE)
        acc.sync()
        pump { acc.pendingCount == 0 && o3.confirmed }
        assertEquals(30, acc.profile.bestScore)
        assertEquals(2, acc.profile.totalRuns) // the rejected run does not count

        // Friends + messaging between two real accounts.
        val other = Api(base)
        var otherToken: String? = null
        var otherId = 0L
        val otherName = "it_" + UUID.randomUUID().toString().take(8).replace("-", "")
        other.post("/api/auth/register", JSONObject().put("username", otherName).put("password", "flapflap123")) { r ->
            otherToken = r.obj().getString("token"); otherId = r.obj().getJSONObject("state").getJSONObject("user").getLong("id")
        }
        pump { otherToken != null }
        other.token = otherToken
        var reqId = 0L
        api.post("/api/friends/requests", JSONObject().put("userId", otherId)) { r -> reqId = r.obj().getLong("requestId") }
        pump { reqId != 0L }
        var accepted = false
        other.post("/api/friends/requests/$reqId/respond", JSONObject().put("accept", true)) { r -> accepted = r.ok }
        pump { accepted }
        var sent = false
        api.post("/api/messages", JSONObject().put("toUserId", otherId).put("body", "Tara laro!").put("clientMsgId", UUID.randomUUID().toString()), retry = true) { r -> sent = r.ok }
        pump { sent }
        var unread = -1
        other.get("/api/notifications") { r -> unread = r.obj().getInt("unreadMessages") }
        pump { unread >= 0 }
        assertEquals(1, unread)

        // An invalid session signs the client out cleanly (pending runs would be kept on disk).
        api.token = "x".repeat(43)
        var done = false
        api.get("/api/me/state") { done = true }
        pump { done }
        assertTrue(!acc.signedIn)
    }
}
