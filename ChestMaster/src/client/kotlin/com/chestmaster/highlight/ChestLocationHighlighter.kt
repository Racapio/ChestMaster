package com.chestmaster.highlight

import com.chestmaster.ChestMasterMod
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import java.util.Locale

object ChestLocationHighlighter {
    private const val REFRESH_INTERVAL_MS = 2000L

    @Volatile
    private var activePositions: List<BlockPos> = emptyList()

    @Volatile
    private var lastRefreshMs: Long = 0

    @Volatile
    private var lastWorldContextKey: String? = null

    fun getActiveMarkerCount(): Int = activePositions.size

    fun clear() {
        activePositions = emptyList()
        val client = Minecraft.getInstance()
        runCatching {
            client.levelRenderer.gameTestBlockHighlightRenderer.clear()
        }.onFailure {
            ChestMasterMod.LOGGER.debug("Failed to clear chest markers: ${it.message}")
        }
    }

    fun onClientTick(client: Minecraft) {
        val contextKey = getWorldContextKey(client)
        val previousContextKey = lastWorldContextKey
        if (previousContextKey == null) {
            lastWorldContextKey = contextKey
        } else if (previousContextKey != contextKey) {
            if (activePositions.isNotEmpty()) {
                if (ChestMasterMod.isVerboseLogging()) {
                    ChestMasterMod.LOGGER.debug(
                        "Clearing chest markers due to world/server change: '$previousContextKey' -> '$contextKey'"
                    )
                }
                clear()
            }
            lastWorldContextKey = contextKey
        }

        val positions = activePositions
        if (positions.isEmpty()) return

        val now = System.currentTimeMillis()
        if (now - lastRefreshMs < REFRESH_INTERVAL_MS) return

        runCatching {
            renderMarkers(client, positions)
            lastRefreshMs = now
        }.onFailure {
            ChestMasterMod.LOGGER.debug("Failed to refresh chest markers: ${it.message}")
        }
    }

    fun highlight(itemName: String, positions: List<BlockPos>): Int {
        if (positions.isEmpty()) {
            clear()
            return 0
        }

        val unique = positions.distinct()
        activePositions = unique
        val client = Minecraft.getInstance()

        return runCatching {
            if (ChestMasterMod.isVerboseLogging()) {
                ChestMasterMod.LOGGER.debug("Highlighting ${unique.size} chest marker(s) for $itemName")
            }
            renderMarkers(client, unique)
            lastRefreshMs = System.currentTimeMillis()
            unique.size
        }.getOrElse {
            if (ChestMasterMod.isVerboseLogging()) {
                ChestMasterMod.LOGGER.debug("Failed to show chest markers: ${it.message}")
            }
            0
        }
    }

    private fun renderMarkers(client: Minecraft, positions: List<BlockPos>) {
        val markerRenderer = client.levelRenderer.gameTestBlockHighlightRenderer
        markerRenderer.clear()
        positions.forEach { pos ->
            // Vanilla block highlight renderer stores markers for a short time.
            // We periodically refresh them instead of adding custom render mixins.
            markerRenderer.highlightPos(pos, pos)
        }
    }

    private fun getWorldContextKey(client: Minecraft): String {
        val serverData = client.currentServer
        val serverKey = when {
            client.isLocalServer -> "singleplayer"
            serverData != null -> "remote:${serverData.ip.lowercase(Locale.ROOT)}"
            else -> "menu"
        }

        val level = client.level
        val levelKey = if (level == null) {
            "level:none"
        } else {
            val dimension = level.dimension().toString()
            "level:$dimension@${System.identityHashCode(level)}"
        }

        return "$serverKey|$levelKey"
    }
}
