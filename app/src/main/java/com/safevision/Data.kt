package com.safevision

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.safevision.ai.Thresholds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.store by preferencesDataStore("settings")

data class Settings(
    val image: Boolean = true,
    val video: Boolean = true,
    val web: Boolean = true,
    val sensitivity: Float = 0.5f
) {
    /** Higher sensitivity lowers the thresholds (blocks more). 0.5 gives 0.30 / 0.70. */
    val thresholds: Thresholds
        get() {
            val inappropriate = 0.9f - 0.4f * sensitivity
            return Thresholds((inappropriate - 0.4f).coerceAtLeast(0.1f), inappropriate)
        }
    val anyOn: Boolean get() = image || video || web
}

private val K_IMAGE = booleanPreferencesKey("image")
private val K_VIDEO = booleanPreferencesKey("video")
private val K_WEB = booleanPreferencesKey("web")
private val K_SENS = floatPreferencesKey("sensitivity")

fun Context.settingsFlow(): Flow<Settings> = store.data.map { p ->
    Settings(p[K_IMAGE] ?: true, p[K_VIDEO] ?: true, p[K_WEB] ?: true, p[K_SENS] ?: 0.5f)
}

suspend fun Context.saveSettings(s: Settings) {
    store.edit {
        it[K_IMAGE] = s.image
        it[K_VIDEO] = s.video
        it[K_WEB] = s.web
        it[K_SENS] = s.sensitivity
    }
}

/** Domain blocklist. Accepts hosts-file lines ("0.0.0.0 domain") or one domain per line. */
class Blocklist(private val hosts: Set<String>) {
    fun isBlocked(host: String?): Boolean {
        if (host == null) return false
        var h = host.lowercase().trimEnd('.')
        while (true) {
            if (h in hosts) return true
            val i = h.indexOf('.')
            if (i < 0) return false
            h = h.substring(i + 1)
        }
    }

    companion object {
        private val skip = setOf("localhost", "local", "broadcasthost", "ip6-localhost", "ip6-loopback", "0.0.0.0")

        fun parse(lines: Sequence<String>): Blocklist {
            val set = HashSet<String>()
            for (raw in lines) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val parts = line.split(Regex("\\s+"))
                val d = (if (parts.size >= 2) parts[1] else parts[0]).lowercase()
                if (d in skip || !d.contains('.')) continue
                set.add(d)
            }
            return Blocklist(set)
        }
    }
}
