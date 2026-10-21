package com.shan.pakpak.net

import com.shan.pakpak.G
import com.shan.pakpak.httpRequest
import com.shan.pakpak.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ApiResult(val status: Int, val json: JSONObject?, val error: String, val message: String) {
    val ok get() = status in 200..299
    /** No response at all: offline, DNS failure, timeout, server down. */
    val network get() = status == 0
    fun obj(): JSONObject = json ?: JSONObject()
}

/**
 * Small JSON-over-HTTPS client. Requests run off the main thread (platform HTTP stack) and
 * callbacks are delivered on the game/main thread. Tokens and bodies are never logged.
 */
class Api(baseUrl: String) {
    val base = baseUrl.trimEnd('/')
    val configured = base.isNotEmpty()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    var token: String? = null
    /** Called whenever the server says our session is no longer valid. */
    var onUnauthorized: (() -> Unit)? = null
    var onNetworkFailure: (() -> Unit)? = null

    fun get(path: String, cb: (ApiResult) -> Unit) = call("GET", path, null, true, cb)
    fun post(path: String, body: JSONObject = JSONObject(), retry: Boolean = false, cb: (ApiResult) -> Unit) = call("POST", path, body, retry, cb)
    fun patch(path: String, body: JSONObject, cb: (ApiResult) -> Unit) = call("PATCH", path, body, false, cb)
    fun delete(path: String, cb: (ApiResult) -> Unit) = call("DELETE", path, null, false, cb)

    /** [retry] should only be true for idempotent requests (GETs, or POSTs carrying a client id). */
    fun call(method: String, path: String, body: JSONObject?, retry: Boolean, cb: (ApiResult) -> Unit) {
        if (!configured) {
            G.post { cb(ApiResult(0, null, "not_configured", "No server is configured for this build.")) }
            return
        }
        val tok = token
        scope.launch {
            var result = request(method, path, body, tok)
            var attempt = 1
            while (retry && attempt < 3 && (result.network || result.status >= 500)) {
                delay(600L * attempt * attempt)
                result = request(method, path, body, tok)
                attempt++
            }
            G.post {
                if (result.status == 401 && tok != null && tok == token) onUnauthorized?.invoke()
                if (result.network) onNetworkFailure?.invoke()
                cb(result)
            }
        }
    }

    suspend fun request(method: String, path: String, body: JSONObject?, tok: String?): ApiResult {
        val headers = HashMap<String, String>()
        headers["Accept"] = "application/json"
        if (tok != null) headers["Authorization"] = "Bearer $tok"
        if (body != null) headers["Content-Type"] = "application/json; charset=utf-8"
        val res = try { httpRequest(method, base + path, headers, body?.toString()) } catch (_: Exception) { null }
        if (res == null || res.status == 0) return ApiResult(0, null, "network", "Can't reach the server. Check your connection.")
        val text = res.body ?: ""
        val json = try { if (text.isNotEmpty()) JSONObject(text) else JSONObject() } catch (_: Exception) { null }
        return if (res.status in 200..299) ApiResult(res.status, json, "", "")
        else ApiResult(res.status, json, json?.optString("error")?.ifEmpty { null } ?: "http_${res.status}",
            json?.optString("message")?.ifEmpty { null } ?: "Server error (${res.status})")
    }
}
