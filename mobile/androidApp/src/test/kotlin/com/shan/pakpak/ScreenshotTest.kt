package com.shan.pakpak

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import com.shan.pakpak.engine.Input
import com.shan.pakpak.engine.Scene
import com.shan.pakpak.game.RunState
import com.shan.pakpak.game.World
import com.shan.pakpak.gfx.Gfx
import com.shan.pakpak.scenes.CollectionScene
import com.shan.pakpak.scenes.CompletionScene
import com.shan.pakpak.scenes.LevelSelectScene
import com.shan.pakpak.scenes.MenuScene
import com.shan.pakpak.scenes.PlayScene
import com.shan.pakpak.scenes.ProfileScene
import com.shan.pakpak.scenes.RewardsScene
import com.shan.pakpak.scenes.SettingsScene
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Renders real screens through the actual game code (Robolectric native graphics) and writes
 * PNGs to build/screens. Used to eyeball layout on several screen sizes without a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {
    private val out = File("build/screens").apply { mkdirs() }

    private fun frames(g: Gfx, n: Int, dt: Float = 1f / 60f) { repeat(n) { shadowOf(Looper.getMainLooper()).idle(); G.frame(dt, g) } }

    private fun save(img: ImageBitmap, name: String) {
        val b = img.asAndroidBitmap()
        val big = Bitmap.createScaledBitmap(b, b.width * 3, b.height * 3, false)
        FileOutputStream(File(out, "$name.png")).use { big.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shoot(name: String, w: Int, h: Int, scene: Scene, warm: Int = 40, after: (Gfx) -> Unit = {}) {
        val b = ImageBitmap(w, h)
        val g = Gfx(Canvas(b), w, h)
        G.onResize(w, h)
        G.scenes.reset(scene)
        frames(g, warm)
        after(g)
        save(b, name)
    }

    private fun world(scene: PlayScene): World {
        val f = PlayScene::class.java.getDeclaredField("world")
        f.isAccessible = true
        return f.get(scene) as World
    }

    /** Steers a play scene with taps until a condition is met. */
    private fun steer(g: Gfx, scene: PlayScene, maxFrames: Int, until: (World) -> Boolean) {
        G.input.push(Input.DOWN, 100f, 300f, 0); G.input.push(Input.UP, 100f, 300f, 0)
        repeat(maxFrames) {
            val w = world(scene)
            if (until(w)) return
            val o = w.nextObstacle()
            if (w.state == RunState.PLAYING && o != null && w.birdY > o.gapY + o.gap * 0.2f && w.birdVy > -40f) {
                G.input.push(Input.DOWN, 100f, 300f, 0); G.input.push(Input.UP, 100f, 300f, 0)
            }
            G.frame(1f / 60f, g)
        }
    }

    @Test fun screens() {
        AndroidPlatform.init(RuntimeEnvironment.getApplication())
        G.init(PlatformConfig("", "test", "Android"))
        frames(Gfx(Canvas(ImageBitmap(216, 480)), 216, 480), 130) // splash -> menu

        // Give the guest some progress so screens have content.
        val p = G.account.guest
        G.catalog.items.take(40).forEach { p.items[it.id] = 1 + (it.id.hashCode() and 1) }
        G.catalog.items.filter { it.rarity.ordinal >= 4 }.take(4).forEach { p.items[it.id] = 1 }
        p.items[G.catalog.items.last().id] = 1
        p.coins = 640; p.bestScore = 37; p.medals[0] = 4; p.medals[1] = 2
        G.levels.levels.take(7).forEach { l -> p.level(l.id).apply { completed = true; bonusAllGems = l.index % 2 == 1; bonusFlaps = l.index < 4 } }

        for ((tag, w, h) in listOf(Triple("phone", 216, 480), Triple("small", 240, 426), Triple("tablet", 228, 365))) {
            shoot("menu_$tag", w, h, MenuScene())
            shoot("collection_$tag", w, h, CollectionScene())
        }
        shoot("levels", 216, 480, LevelSelectScene())
        shoot("completion", 216, 480, CompletionScene())
        shoot("rewards", 216, 480, RewardsScene())
        shoot("profile", 216, 480, ProfileScene())
        shoot("settings", 216, 480, SettingsScene())

        val endless = PlayScene.endless()
        shoot("play_ready", 216, 480, endless)
        val b = ImageBitmap(216, 480)
        val g = Gfx(Canvas(b), 216, 480)
        G.scenes.reset(endless); frames(g, 30)
        steer(g, endless, 60 * 20) { it.score >= 6 && it.pickups.any { pk -> !pk.taken && pk.x in 80f..200f } || it.score >= 14 }
        save(b, "play_endless")
        // Fly into the ground to get the results screen.
        repeat(60 * 4) { G.frame(1f / 60f, g) }
        save(b, "results_endless")

        for (id in listOf("2-2", "3-3", "4-2")) {
            val l = G.levels.byId[id]!!
            val s = PlayScene.adventure(l)
            G.scenes.reset(s); frames(g, 30)
            steer(g, s, 60 * 12) { it.score >= 4 && (it.obstacles.any { o -> o.hazard != null && o.x in 60f..200f } || it.winds.any { wz -> wz.x0 in 40f..200f } || it.score >= 7) }
            save(b, "play_$id")
        }
    }
}
