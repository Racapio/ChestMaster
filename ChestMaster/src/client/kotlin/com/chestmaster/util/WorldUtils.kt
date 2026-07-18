package com.chestmaster.util

import net.minecraft.client.Minecraft
import java.util.Locale

object WorldUtils {
    fun getCurrentServerKey(): String = try {
        val client = Minecraft.getInstance()
        when {
            client.isLocalServer -> "singleplayer"
            client.currentServer != null -> "remote:${client.currentServer!!.ip.lowercase(Locale.ROOT)}"
            else -> "unknown"
        }
    } catch (_: Exception) {
        "unknown"
    }

    fun formatLastSeen(ms: Long): String {
        if (ms <= 0L) return "never"
        val ago = System.currentTimeMillis() - ms
        return when {
            ago < 60_000L -> "${ago / 1000}s ago"
            ago < 3_600_000L -> "${ago / 60_000}m ago"
            ago < 86_400_000L -> "${ago / 3_600_000}h ago"
            else -> "${ago / 86_400_000}d ago"
        }
    }
}
