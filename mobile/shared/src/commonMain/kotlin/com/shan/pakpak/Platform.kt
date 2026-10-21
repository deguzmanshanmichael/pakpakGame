package com.shan.pakpak

import androidx.compose.ui.graphics.ImageBitmap
import com.shan.pakpak.data.Settings

/** Values each platform's launcher passes in (from BuildConfig on Android, Info.plist on iOS). */
data class PlatformConfig(val apiBaseUrl: String, val versionName: String, val platformName: String)

/** Wall-clock time in epoch milliseconds. */
expect fun nowMs(): Long

/** Local date/time for chat bubbles, e.g. "Oct 22 14:05". */
expect fun formatTimestamp(epochMs: Long): String

/** Reads a bundled file from commonMain/assets (Android assets, iOS app bundle). */
expect fun readAssetText(path: String): String

/** Builds an image from ARGB pixels (0xAARRGGBB, row-major). */
expect fun imageFromArgb(width: Int, height: Int, argb: IntArray): ImageBitmap

/** App-private file storage. [writeAtomic] must never leave a half-written file behind. */
expect object PlatformStorage {
    fun read(name: String): String?
    fun writeAtomic(name: String, text: String)
    fun copy(from: String, to: String)
    fun delete(name: String)
}

class HttpResponse(val status: Int, val body: String?)

/** One HTTP request with ~8s connect / 12s read timeouts. Returns status 0 on any network failure. */
expect suspend fun httpRequest(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse

/** Reports whether the OS thinks we have a usable network path. */
expect class NetworkMonitor() {
    val hasInternet: Boolean
    fun start(onChange: () -> Unit)
    fun stop()
}

/** Sound effects plus one looping music track. Files live in assets/audio/<name>.wav. */
expect class Audio(settings: () -> Settings) {
    fun play(name: String, rate: Float = 1f)
    fun music(track: String)
    fun applySettings()
    fun pause()
    fun resume()
    fun release()
}

object Tracks {
    const val NONE = ""
    const val MENU = "music_menu"
    const val GAME = "music_game"
}
