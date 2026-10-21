package com.shan.pakpak

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.shan.pakpak.data.Catalog
import com.shan.pakpak.data.GameConfig
import com.shan.pakpak.data.Levels
import com.shan.pakpak.data.SaveStore
import com.shan.pakpak.data.Settings
import com.shan.pakpak.engine.Input
import com.shan.pakpak.engine.SceneManager
import com.shan.pakpak.game.Progress
import com.shan.pakpak.gfx.Gfx
import com.shan.pakpak.gfx.Pal
import com.shan.pakpak.json.JSONObject
import com.shan.pakpak.net.Account
import com.shan.pakpak.net.Api
import com.shan.pakpak.net.Connectivity
import com.shan.pakpak.scenes.MenuScene
import com.shan.pakpak.scenes.SplashScene
import com.shan.pakpak.ui.Ui

/** One text field in a native text-entry dialog. */
data class Field(
    val label: String,
    val hint: String = "",
    val password: Boolean = false,
    val maxLength: Int = 64,
    val initial: String = "",
    val multiline: Boolean = false,
)

/** A pending text-entry dialog, shown by the Compose overlay in [PakPakApp]. */
class PromptRequest(
    val title: String,
    val message: String?,
    val fields: List<Field>,
    val okLabel: String,
    val onResult: (List<String>?) -> Unit,
)

/**
 * Game-wide services. Everything runs on the platform main thread: the Compose frame clock drives
 * [frame], touch events and network callbacks are queued and handled at the start of each frame.
 */
object G {
    val input = Input()
    val ui = Ui(input)
    val scenes = SceneManager()
    lateinit var platform: PlatformConfig
    lateinit var store: SaveStore
    lateinit var settings: Settings
    lateinit var catalog: Catalog
    lateinit var config: GameConfig
    lateinit var levels: Levels
    lateinit var progress: Progress
    lateinit var audio: Audio
    lateinit var api: Api
    lateinit var net: Connectivity
    lateinit var account: Account
    var vw = 216
    var vh = 480
    var time = 0f
    var lastFrameMs = 0f
    var initialized = false
        private set
    private var remoteLoaded = false
    private val queue = ArrayDeque<() -> Unit>()

    /** Set by the Compose host so the game can trigger haptics/clipboard without platform code. */
    var hapticImpl: () -> Unit = {}
    var clipboardImpl: (String) -> Unit = {}
    var promptState by mutableStateOf<PromptRequest?>(null)

    fun init(cfg: PlatformConfig) {
        if (initialized) return
        platform = cfg
        store = SaveStore()
        settings = Settings.fromJson(store.read("settings.json"))
        levels = Levels.parse(asset("data/levels.json"))
        config = store.read("config_cache.json")?.let { runCatching { GameConfig.parse(it) }.getOrNull() } ?: GameConfig.parse(asset("data/config.json"))
        catalog = store.read("catalog_cache.json")?.let { runCatching { Catalog.parse(it) }.getOrNull() } ?: Catalog.parse(asset("data/catalog.json"))
        progress = Progress(catalog, config, levels)
        audio = Audio { settings }
        api = Api(cfg.apiBaseUrl)
        net = Connectivity(api)
        account = Account(store, api)
        net.addListener { s ->
            if (s == Connectivity.State.ONLINE) {
                loadRemoteData()
                account.sync()
                account.pollSoon()
            }
        }
        scenes.reset(SplashScene())
        initialized = true
    }

    private fun asset(path: String): JSONObject = JSONObject(readAssetText(path))

    /** Pulls the live config (drop rates, notice text, ...) and catalog from the server and caches them for offline use. */
    fun loadRemoteData() {
        if (remoteLoaded) return
        remoteLoaded = true
        api.get("/api/config") { r ->
            if (!r.ok) { remoteLoaded = false; return@get }
            runCatching { GameConfig.parse(r.obj()) }.onSuccess {
                config = it
                progress = Progress(catalog, config, levels)
                store.write("config_cache.json", r.obj())
                if (r.obj().optLong("catalogVersion") != catalog.version) loadCatalog()
            }
        }
    }

    private fun loadCatalog() {
        api.get("/api/catalog") { r ->
            if (!r.ok) return@get
            runCatching { Catalog.parse(r.obj()) }.onSuccess {
                catalog = it
                progress = Progress(catalog, config, levels)
                store.write("catalog_cache.json", r.obj())
            }
        }
    }

    fun post(r: () -> Unit) { queue.addLast(r) }

    fun runQueued() {
        while (true) {
            val r = queue.removeFirstOrNull() ?: break
            try { r() } catch (e: Throwable) { reportCrash(e) }
        }
    }

    fun frame(dt: Float, g: Gfx) {
        val start = nowMs()
        time += dt
        runQueued()
        input.beginFrame()
        ui.begin(g)
        net.tick(dt)
        account.tick(dt)
        scenes.update(dt)
        scenes.draw(g, ui)
        if (settings.showFps) g.textShadow("${lastFrameMs.toInt()}ms", 2, g.h - 10, Pal.WHITE)
        lastFrameMs = lastFrameMs * 0.9f + (nowMs() - start) * 0.1f
    }

    fun onResize(w: Int, h: Int) { vw = w; vh = h }

    /** Back button (Android). Returns false when the app should go to the background. */
    fun back(): Boolean {
        promptState?.let { promptState = null; it.onResult(null); return true }
        return scenes.back()
    }

    fun onPause() {
        if (!initialized) return
        scenes.onAppPause()
        runQueued()
        audio.pause()
        account.saveBlocking()
        saveSettings()
    }

    fun onResume() {
        if (!initialized) return
        audio.resume()
        net.recheckSoon()
    }

    fun saveSettings() = store.write("settings.json", settings.toJson())

    fun haptic() { if (settings.haptics) hapticImpl() }

    fun copyToClipboard(@Suppress("UNUSED_PARAMETER") label: String, text: String) = clipboardImpl(text)

    /** Shows a text-entry dialog. [onResult] gets the values, or null if cancelled. */
    fun prompt(title: String, message: String?, fields: List<Field>, okLabel: String, onResult: (List<String>?) -> Unit) {
        promptState = PromptRequest(title, message, fields, okLabel, onResult)
        input.clear()
    }

    /** Last line of defence: log, keep the player's data, and return to the menu instead of crashing. */
    fun reportCrash(e: Throwable) {
        println("PakPak: unexpected error: $e")
        e.printStackTrace()
        try {
            account.save()
            scenes.reset(MenuScene())
            scenes.toast("Oops, something went wrong. Your progress is safe.")
        } catch (_: Throwable) {}
    }
}
