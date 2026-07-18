package com.chestmaster.config

import com.chestmaster.ChestMasterMod
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

class ConfigManager {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val configFile: Path = FabricLoader.getInstance().configDir.resolve("chestmaster.json")
    var config: ModConfig = ModConfig()

    fun load() {
        if (!Files.exists(configFile)) {
            save()
            return
        }
        try {
            Files.newBufferedReader(configFile, Charsets.UTF_8).use { reader ->
                // fromJson returns null for empty/blank files — fall back to defaults.
                config = gson.fromJson(reader, ModConfig::class.java) ?: ModConfig()
            }
        } catch (e: Exception) {
            ChestMasterMod.LOGGER.error("Failed to load config: ${e.message}")
            config = ModConfig()
        }
    }

    fun save() {
        try {
            configFile.parent?.let { Files.createDirectories(it) }
            Files.newBufferedWriter(configFile, Charsets.UTF_8).use { writer ->
                gson.toJson(config, writer)
            }
        } catch (e: Exception) {
            ChestMasterMod.LOGGER.error("Failed to save config: ${e.message}")
        }
    }
}
