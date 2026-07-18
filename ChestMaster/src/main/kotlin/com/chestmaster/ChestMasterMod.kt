package com.chestmaster

import com.chestmaster.config.ConfigManager
import com.chestmaster.database.DatabaseManager
import com.chestmaster.valuation.ItemValuator
import net.fabricmc.api.ModInitializer
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ChestMasterMod : ModInitializer {
    companion object {
        const val MOD_ID = "chestmaster"
        val LOGGER: Logger = LoggerFactory.getLogger(MOD_ID)
        val db = DatabaseManager()
        val configManager = ConfigManager()

        // Single-threaded executor for all DB writes so they don't block the render thread.
        val dbExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "ChestMaster-DB").apply { isDaemon = true }
        }

        @JvmStatic
        fun isVerboseLogging(): Boolean = runCatching { configManager.config.verboseLogging }.getOrDefault(false)
    }

    override fun onInitialize() {
        configManager.load()
        ItemValuator.currentMode = runCatching {
            ItemValuator.PriceMode.valueOf(configManager.config.priceMode)
        }.getOrDefault(ItemValuator.PriceMode.SELL_OFFER)
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

        Runtime.getRuntime().addShutdownHook(Thread({
            // Let queued saves finish, then close the connection so the WAL is checkpointed.
            runCatching {
                dbExecutor.shutdown()
                dbExecutor.awaitTermination(3, TimeUnit.SECONDS)
            }
            db.close()
        }, "ChestMaster-Shutdown"))
    }
}
