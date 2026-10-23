package com.shan.pakpak

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.shan.pakpak.engine.Input
import com.shan.pakpak.engine.Scene
import com.shan.pakpak.gfx.Gfx
import com.shan.pakpak.json.JSONObject
import com.shan.pakpak.net.Account
import com.shan.pakpak.net.Api
import com.shan.pakpak.net.Connectivity
import com.shan.pakpak.scenes.ChatScene
import com.shan.pakpak.scenes.FriendsScene
import com.shan.pakpak.scenes.MessagesScene
import com.shan.pakpak.scenes.PlayerScene
import com.shan.pakpak.scenes.TradeComposeScene
import com.shan.pakpak.scenes.TradeDetailScene
import com.shan.pakpak.scenes.TradingScene
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Renders the online social screens (friends, profile, chat, trading) with two real accounts on a
 * running backend, so their layout and flows can be reviewed. Skipped when no server is running.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SocialScreensTest {
    private val base = System.getenv("PAKPAK_TEST_SERVER") ?: "http://127.0.0.1:8080"
    private val out = File("build/screens").apply { mkdirs() }
    private val img = ImageBitmap(216, 480)
    private val g = Gfx(Canvas(img), 216, 480)

    private fun serverUp() = try {
        (URL("$base/api/health").openConnection() as HttpURLConnection).run { connectTimeout = 1500; responseCode == 200 }
    } catch (_: Exception) { false }

    private fun frames(n: Int, waitMs: Long = 0) = repeat(n) {
        shadowOf(Looper.getMainLooper()).idle()
        G.frame(1f / 60f, g)
        if (waitMs > 0) Thread.sleep(waitMs)
    }

    private fun pumpUntil(timeoutMs: Long = 10000, done: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!done() && System.currentTimeMillis() < end) frames(1, 20)
    }

    private fun save(name: String) {
        val b = img.asAndroidBitmap()
        FileOutputStream(File(out, "$name.png")).use { Bitmap.createScaledBitmap(b, b.width * 3, b.height * 3, false).compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun show(name: String, scene: Scene, settleFrames: Int = 120) {
        G.scenes.reset(scene)
        frames(settleFrames, 15)
        save(name)
    }

    private fun tap(x: Float, y: Float) {
        G.input.push(Input.DOWN, x, y, 0); G.input.push(Input.UP, x, y, 0)
        frames(30, 10)
    }

    private fun call(api: Api, method: String, path: String, body: JSONObject? = null): JSONObject {
        var res: JSONObject? = null
        api.call(method, path, body, false) { r -> res = r.obj() }
        pumpUntil { res != null }
        return res ?: JSONObject()
    }

    @Test fun socialScreens() {
        assumeTrue("backend not running at $base", serverUp())
        AndroidPlatform.init(RuntimeEnvironment.getApplication())
        G.init(PlatformConfig(base, "test", "Android"))
        val api = Api(base)
        G.api = api
        G.net = Connectivity(api).also { it.overrideForTest(Connectivity.State.ONLINE) }
        G.account = Account(G.store, api)
        G.onResize(216, 480)

        val tag = UUID.randomUUID().toString().take(6).replace("-", "")
        val bob = Api(base)
        val bobReg = call(bob, "POST", "/api/auth/register", JSONObject().put("username", "bob_$tag").put("password", "flapflap123"))
        bob.token = bobReg.getString("token")
        val bobId = bobReg.getJSONObject("state").getJSONObject("user").getLong("id")
        var ok = false
        G.account.register("alice_$tag", "flapflap123") { r, _ -> ok = r.ok }
        pumpUntil { ok }
        // Give both some tradeable items through real runs, then befriend and chat.
        for (who in listOf(api, bob)) {
            val runs = com.shan.pakpak.json.JSONArray()
            repeat(3) { runs.put(JSONObject().put("clientRunId", UUID.randomUUID().toString()).put("mode", "endless").put("score", 20).put("pipes", 20)
                .put("coinsCollected", 5).put("flaps", 60).put("durationMs", 40000).put("distance", 220).put("playedAt", System.currentTimeMillis())
                .put("pickups", com.shan.pakpak.json.JSONArray(listOf(3, 9, 15)))) }
            call(who, "POST", "/api/runs/submit", JSONObject().put("runs", runs))
        }
        val req = call(api, "POST", "/api/friends/requests", JSONObject().put("userId", bobId))
        call(bob, "POST", "/api/friends/requests/${req.getLong("requestId")}/respond", JSONObject().put("accept", true))
        call(bob, "POST", "/api/messages", JSONObject().put("toUserId", G.account.profile.userId).put("body", "Uy! Tara trade tayo ng duplicates mo? :)").put("clientMsgId", UUID.randomUUID().toString()))
        call(api, "POST", "/api/messages", JSONObject().put("toUserId", bobId).put("body", "Sige! Ano gusto mo?").put("clientMsgId", UUID.randomUUID().toString()))
        call(bob, "POST", "/api/friends/${G.account.profile.userId}/invite", JSONObject().put("activity", "challenge"))
        // Bob offers one of his items for one of Alice's, so the trade list and detail have content.
        val bobItems = call(bob, "GET", "/api/trades/inventory/$bobId").optJSONArray("items")
        val aliceItems = call(bob, "GET", "/api/trades/inventory/${G.account.profile.userId}").optJSONArray("items")
        var trade: JSONObject? = null
        if (bobItems != null && aliceItems != null && bobItems.length() > 0 && aliceItems.length() > 0) {
            trade = call(bob, "POST", "/api/trades", JSONObject().put("toUserId", G.account.profile.userId).put("note", "swap?")
                .put("give", com.shan.pakpak.json.JSONArray(listOf(JSONObject().put("collectibleId", bobItems.getJSONObject(0).getString("collectibleId")).put("qty", 1))))
                .put("want", com.shan.pakpak.json.JSONArray(listOf(JSONObject().put("collectibleId", aliceItems.getJSONObject(0).getString("collectibleId")).put("qty", 1)))))
        }
        G.account.refreshState()
        frames(60, 15)

        show("social_friends", FriendsScene())
        // Tap the first friend row like a player would.
        tap(60f, 85f)
        save("social_after_tap_friend")
        show("social_profile", PlayerScene(bobId))
        show("social_messages", MessagesScene())
        show("social_chat", ChatScene(bobId, "bob_$tag"), 200)
        show("social_trading", TradingScene())
        trade?.optLong("id")?.takeIf { it > 0 }?.let { id -> show("social_trade_detail", TradeDetailScene(call(api, "GET", "/api/trades/$id"))) }
        show("social_trade_compose", TradeComposeScene(bobId, "bob_$tag"), 200)
    }
}
