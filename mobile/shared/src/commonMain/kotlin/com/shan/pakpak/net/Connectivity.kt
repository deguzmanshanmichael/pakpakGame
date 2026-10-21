package com.shan.pakpak.net

import com.shan.pakpak.G
import com.shan.pakpak.NetworkMonitor

/**
 * Tracks whether the device has a network path (from the OS) and whether our server answers
 * (from periodic /api/health checks). Online features need both.
 */
class Connectivity(private val api: Api) {
    enum class State(val label: String) { OFFLINE("OFFLINE"), NO_SERVER("NO SERVER"), ONLINE("ONLINE") }

    private val monitor = NetworkMonitor()
    var state = State.OFFLINE
        private set
    private var checking = false
    private var nextCheck = 0f
    private val listeners = ArrayList<(State) -> Unit>()

    init {
        monitor.start { G.post { recheckSoon() } }
        api.onNetworkFailure = { if (state == State.ONLINE) set(State.NO_SERVER); nextCheck = 3f }
    }

    val online get() = state == State.ONLINE

    fun addListener(l: (State) -> Unit) { listeners.add(l) }

    fun recheckSoon() { nextCheck = 0f }

    /** Called every frame on the game thread. */
    fun tick(dt: Float) {
        nextCheck -= dt
        if (nextCheck > 0f || checking) return
        if (!monitor.hasInternet) { set(State.OFFLINE); nextCheck = 5f; return }
        if (!api.configured) { set(State.NO_SERVER); nextCheck = 30f; return }
        checking = true
        api.get("/api/health") { r ->
            checking = false
            set(if (r.ok) State.ONLINE else State.NO_SERVER)
            nextCheck = if (r.ok) 60f else 10f
        }
    }

    private fun set(s: State) {
        if (s == state) return
        state = s
        listeners.forEach { it(s) }
    }

    /** Test hook: pin the state (stops automatic checks until [recheckSoon] is called). */
    fun overrideForTest(s: State) { set(s); nextCheck = 1e9f }

    fun close() = monitor.stop()
}
