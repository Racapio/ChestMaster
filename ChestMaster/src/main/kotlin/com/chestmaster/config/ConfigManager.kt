package com.chestmaster.config

import com.chestmaster.ChestMasterMod
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import java.io.File
import java.io.FileReader
import java.io.FileWriter

class ConfigManager {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val configFile: File = FabricLoader.getInstance().configDir.resolve("chestmaster.json").toFile()
    var config: ModConfig = ModConfig()

    fun load() {
        if (!configFile.exists()) {
            save()
            return
        }
        try {
            FileReader(configFile).use { reader ->
                config = gson.fromJson(reader, ModConfig::class.java)
            }
        } catch (e: Exception) {
            ChestMasterMod.LOGGER.error("Failed to load config: ${e.message}")
            config = ModConfig()
        }
    }

    fun save() {
        try {
            FileWriter(configFile).use { writer ->
                gson.toJson(config, writer)
            }
        } catch (e: Exception) {
            ChestMasterMod.LOGGER.error("Failed to save config: ${e.message}")
        }
    }
}
