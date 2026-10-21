package com.shan.pakpak.data

import com.shan.pakpak.PlatformStorage
import com.shan.pakpak.json.JSONObject

/**
 * Crash-safe JSON saves in app-private storage. Each write is atomic (temp file + rename on
 * Android, atomic NSString write on iOS) and the previous version is kept as .bak, which is used
 * if the main file is ever unreadable. Saves are a few KB, so they are written synchronously.
 */
class SaveStore {
    fun read(name: String): JSONObject? {
        for (f in listOf(name, "$name.bak")) {
            val text = try { PlatformStorage.read(f) } catch (_: Exception) { null } ?: continue
            try {
                return JSONObject(text)
            } catch (_: Exception) {
                println("PakPak: corrupt save $f, trying backup")
            }
        }
        return null
    }

    fun write(name: String, data: JSONObject) {
        val text = data.toString()
        try {
            if (PlatformStorage.read(name) != null) PlatformStorage.copy(name, "$name.bak")
            PlatformStorage.writeAtomic(name, text)
        } catch (e: Exception) {
            println("PakPak: save failed for $name: ${e.message}")
        }
    }

    fun writeBlocking(name: String, data: JSONObject) = write(name, data)

    fun delete(name: String) {
        PlatformStorage.delete(name)
        PlatformStorage.delete("$name.bak")
    }

    fun flush() {}
}
