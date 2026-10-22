@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.shan.pakpak

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.shan.pakpak.data.Settings
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryAmbient
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSBundle
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSFileManager
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.dataUsingEncoding
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume

actual fun nowMs(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

actual fun formatTimestamp(epochMs: Long): String {
    val f = NSDateFormatter()
    f.dateFormat = "MMM d HH:mm"
    return f.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0))
}

/** Bundled files: the Xcode project copies shared/src/commonMain/assets into the app as "assets/". */
actual fun readAssetText(path: String): String {
    val full = (NSBundle.mainBundle.resourcePath ?: "") + "/assets/" + path
    return NSString.stringWithContentsOfFile(full, NSUTF8StringEncoding, null) ?: error("missing bundled asset $path")
}

actual fun imageFromArgb(width: Int, height: Int, argb: IntArray): ImageBitmap {
    val bytes = ByteArray(width * height * 4)
    for (i in argb.indices) {
        val c = argb[i]
        bytes[i * 4] = (c shr 16).toByte()
        bytes[i * 4 + 1] = (c shr 8).toByte()
        bytes[i * 4 + 2] = c.toByte()
        bytes[i * 4 + 3] = (c ushr 24).toByte()
    }
    val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
    return Image.makeRaster(info, bytes, width * 4).toComposeImageBitmap()
}

actual object PlatformStorage {
    private val dir: String by lazy {
        val base = (NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).firstOrNull() as? String) ?: ""
        val d = "$base/PakPak"
        NSFileManager.defaultManager.createDirectoryAtPath(d, withIntermediateDirectories = true, attributes = null, error = null)
        d
    }

    actual fun read(name: String): String? = NSString.stringWithContentsOfFile("$dir/$name", NSUTF8StringEncoding, null)

    /** NSString's atomic write goes through a temp file and a rename, like the Android version. */
    actual fun writeAtomic(name: String, text: String) {
        val ok = NSString.create(string = text).writeToFile("$dir/$name", atomically = true, encoding = NSUTF8StringEncoding, error = null)
        if (!ok) throw IllegalStateException("could not write $name")
    }

    actual fun copy(from: String, to: String) {
        val fm = NSFileManager.defaultManager
        if (!fm.fileExistsAtPath("$dir/$from")) return
        fm.removeItemAtPath("$dir/$to", null)
        fm.copyItemAtPath("$dir/$from", "$dir/$to", null)
    }

    actual fun delete(name: String) {
        NSFileManager.defaultManager.removeItemAtPath("$dir/$name", null)
    }
}

actual suspend fun httpRequest(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse =
    suspendCancellableCoroutine { cont ->
        val nsUrl = NSURL.URLWithString(url)
        if (nsUrl == null) {
            cont.resume(HttpResponse(0, null))
            return@suspendCancellableCoroutine
        }
        val req = NSMutableURLRequest.requestWithURL(nsUrl)
        req.setHTTPMethod(method)
        req.setTimeoutInterval(12.0)
        for ((k, v) in headers) req.setValue(v, forHTTPHeaderField = k)
        if (body != null) req.setHTTPBody(NSString.create(string = body).dataUsingEncoding(NSUTF8StringEncoding))
        val task = NSURLSession.sharedSession.dataTaskWithRequest(req) { data, response, error ->
            val http = response as? NSHTTPURLResponse
            if (error != null || http == null) {
                if (cont.isActive) cont.resume(HttpResponse(0, null))
            } else {
                val text = data?.let { NSString.create(data = it, encoding = NSUTF8StringEncoding)?.toString() }
                if (cont.isActive) cont.resume(HttpResponse(http.statusCode.toInt(), text))
            }
        }
        cont.invokeOnCancellation { task.cancel() }
        task.resume()
    }

actual class NetworkMonitor actual constructor() {
    private val monitor = nw_path_monitor_create()
    private var satisfied = true

    actual val hasInternet: Boolean get() = satisfied

    actual fun start(onChange: () -> Unit) {
        nw_path_monitor_set_update_handler(monitor) { path ->
            satisfied = nw_path_get_status(path) == nw_path_status_satisfied
            onChange()
        }
        nw_path_monitor_set_queue(monitor, dispatch_get_main_queue())
        nw_path_monitor_start(monitor)
    }

    actual fun stop() = nw_path_monitor_cancel(monitor)
}

/** AVAudioPlayer for everything; each effect has a few players so quick repeats can overlap. */
actual class Audio actual constructor(private val settings: () -> Settings) {
    private val sfx = HashMap<String, List<AVAudioPlayer>>()
    private val next = HashMap<String, Int>()
    private var player: AVAudioPlayer? = null
    private var currentTrack = Tracks.NONE
    private var paused = false

    init {
        // Ambient: respects the silent switch and mixes with other apps' audio, as games usually do.
        AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryAmbient, error = null)
        for (name in listOf("flap", "score", "coin", "hit", "die", "capsule", "gem", "rare", "click", "win", "buy")) {
            val players = (0 until 3).mapNotNull { load("sfx_$name") }
            if (players.isNotEmpty()) sfx[name] = players
        }
    }

    private fun load(file: String): AVAudioPlayer? {
        val path = (NSBundle.mainBundle.resourcePath ?: return null) + "/assets/audio/$file.wav"
        val p = AVAudioPlayer(contentsOfURL = NSURL.fileURLWithPath(path), error = null)
        p.prepareToPlay()
        return p
    }

    actual fun play(name: String, rate: Float) {
        val s = settings()
        if (!s.sfx || s.sfxVolume == 0) return
        val players = sfx[name] ?: return
        val i = (next[name] ?: 0) % players.size
        next[name] = i + 1
        val p = players[i]
        p.volume = s.sfxVolume / 10f
        p.currentTime = 0.0
        p.play()
    }

    actual fun music(track: String) {
        if (track == currentTrack && player != null) return
        stopMusic()
        currentTrack = track
        startMusicIfAllowed()
    }

    private fun startMusicIfAllowed() {
        val s = settings()
        if (!s.music || s.musicVolume == 0 || currentTrack.isEmpty() || paused) return
        val p = load(currentTrack) ?: return
        p.numberOfLoops = -1
        p.volume = s.musicVolume / 10f * 0.6f
        p.play()
        player = p
    }

    private fun stopMusic() {
        player?.stop()
        player = null
    }

    actual fun applySettings() {
        val s = settings()
        if (!s.music || s.musicVolume == 0) { stopMusic(); return }
        val p = player
        if (p == null) startMusicIfAllowed() else p.volume = s.musicVolume / 10f * 0.6f
    }

    actual fun pause() {
        paused = true
        player?.pause()
    }

    actual fun resume() {
        paused = false
        if (player == null) startMusicIfAllowed() else player?.play()
    }

    actual fun release() { stopMusic(); sfx.clear() }
}
