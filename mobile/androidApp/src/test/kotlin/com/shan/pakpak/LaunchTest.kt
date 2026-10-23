package com.shan.pakpak

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Launches the real MainActivity (Compose host, window setup, lifecycle) as a phone would.
 * Guards against startup crashes like calling the insets controller before setContent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LaunchTest {
    @Test(timeout = 120_000)
    fun mainActivityStartsAndSurvivesLifecycleWithoutCrashing() {
        // create() runs onCreate (window + system bar setup, setContent); the startup crash happened here.
        // We stop short of visible(): Compose's endless frame loop can't be drained by a paused test looper.
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        assertTrue(controller.get().window.decorView != null)
        controller.pause().stop()
        controller.restart().resume()
        controller.pause().stop().destroy()
    }
}
