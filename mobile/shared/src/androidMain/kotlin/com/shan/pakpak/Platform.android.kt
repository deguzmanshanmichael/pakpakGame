package com.shan.pakpak

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.shan.pakpak.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Holds the application context; set by MainActivity before the game starts. */
@SuppressLint("StaticFieldLeak")
object AndroidPlatform {
    lateinit var context: Context
    fun init(ctx: Context) { context = ctx.applicationContext }
}

actual fun nowMs(): Long = System.currentTimeMillis()

actual fun formatTimestamp(epochMs: Long): String = SimpleDateFormat("MMM d HH:mm", Locale.US).format(Date(epochMs))

actual fun readAssetText(path: String): String =
    AndroidPlatform.context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }

actual fun imageFromArgb(width: Int, height: Int, argb: IntArray): ImageBitmap =
    Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()

actual object PlatformStorage {
    private val dir get() = AndroidPlatform.context.filesDir

    actual fun read(name: String): String? = File(dir, name).takeIf { it.exists() }?.readText()

    /** Temp file + fsync + rename, so a crash or power loss never leaves a half-written save. */
    actual fun writeAtomic(name: String, text: String) {
        val tmp = File(dir, "$name.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(File(dir, name))) throw IOException("rename failed for $name")
    }

    actual fun copy(from: String, to: String) {
        val src = File(dir, from)
        if (src.exists()) src.copyTo(File(dir, to), overwrite = true)
    }

    actual fun delete(name: String) {
        File(dir, name).delete()
    }
}

actual suspend fun httpRequest(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse =
    withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 8000
                readTimeout = 12000
                useCaches = false
                for ((k, v) in headers) setRequestProperty(k, v)
                if (body != null) doOutput = true
            }
            if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            HttpResponse(code, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() })
        } catch (_: Exception) {
            HttpResponse(0, null)
        } finally {
            conn?.disconnect()
        }
    }

actual class NetworkMonitor actual constructor() {
    private val cm = AndroidPlatform.context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    @Volatile private var validated = currentlyConnected()
    private var callback: ConnectivityManager.NetworkCallback? = null

    actual val hasInternet: Boolean get() = validated || currentlyConnected()

    private fun currentlyConnected(): Boolean {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    actual fun start(onChange: () -> Unit) {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val ok = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (ok != validated) { validated = ok; onChange() }
            }
            override fun onLost(network: Network) { validated = false; onChange() }
        }
        callback = cb
        try { cm.registerDefaultNetworkCallback(cb) } catch (_: Exception) {}
    }

    actual fun stop() {
        callback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }
    }
}

/** Sound effects via SoundPool and looping music via MediaPlayer, read straight from the APK's assets. */
actual class Audio actual constructor(private val settings: () -> Settings) {
    private val ctx = AndroidPlatform.context
    private val pool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val ids = HashMap<String, Int>()
    private var player: MediaPlayer? = null
    private var currentTrack = Tracks.NONE
    private var paused = false

    init {
        for (name in listOf("flap", "score", "coin", "hit", "die", "capsule", "gem", "rare", "click", "win", "buy")) {
            try { ctx.assets.openFd("audio/sfx_$name.wav").use { ids[name] = pool.load(it, 1) } } catch (_: Exception) {}
        }
    }

    actual fun play(name: String, rate: Float) {
        val s = settings()
        if (!s.sfx || s.sfxVolume == 0) return
        val id = ids[name] ?: return
        val v = s.sfxVolume / 10f
        pool.play(id, v, v, 1, 0, rate)
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
        try {
            player = MediaPlayer().apply {
                ctx.assets.openFd("audio/$currentTrack.wav").use { setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                isLooping = true
                val v = s.musicVolume / 10f * 0.6f
                setVolume(v, v)
                prepare()
                start()
            }
        } catch (_: Exception) { player = null }
    }

    private fun stopMusic() {
        try { player?.stop() } catch (_: Exception) {}
        player?.release()
        player = null
    }

    actual fun applySettings() {
        val s = settings()
        if (!s.music || s.musicVolume == 0) { stopMusic(); return }
        val p = player
        if (p == null) startMusicIfAllowed() else { val v = s.musicVolume / 10f * 0.6f; p.setVolume(v, v) }
    }

    actual fun pause() {
        paused = true
        try { player?.pause() } catch (_: Exception) {}
        pool.autoPause()
    }

    actual fun resume() {
        paused = false
        pool.autoResume()
        if (player == null) startMusicIfAllowed() else try { player?.start() } catch (_: Exception) {}
    }

    actual fun release() { stopMusic(); pool.release() }
}
