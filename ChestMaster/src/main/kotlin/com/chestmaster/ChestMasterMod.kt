package com.chestmaster

import com.chestmaster.config.ConfigManager
import com.chestmaster.database.DatabaseManager
import net.fabricmc.api.ModInitializer
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class ChestMasterMod : ModInitializer {
    companion object {
        const val MOD_ID = "chestmaster"
        val LOGGER: Logger = LoggerFactory.getLogger(MOD_ID)
        val db = DatabaseManager()
        val configManager = ConfigManager()

        @JvmStatic
        fun isVerboseLogging(): Boolean = runCatching { configManager.config.verboseLogging }.getOrDefault(false)
    }

    override fun onInitialize() {
        configManager.load()
        if (isVerboseLogging()) {
            val version = runCatching {
                FabricLoader.getInstance()
                    .getModContainer(MOD_ID)
                    .orElse(null)
                    ?.metadata
                    ?.version
                    ?.friendlyString
                    ?: "unknown"
            }.getOrDefault("unknown")
            val minecraftVersion = runCatching {
                FabricLoader.getInstance()
                    .getModContainer("minecraft")
                    .orElse(null)
                    ?.metadata
                    ?.version
                    ?.friendlyString
                    ?: "unknown"
            }.getOrDefault("unknown")
            LOGGER.info("ChestMaster v$version initializing (Minecraft $minecraftVersion)...")
        }
        db.init()
    }
}
