package com.client

import com.chestmaster.ChestMasterMod
import com.chestmaster.command.ChestMasterCommand
import com.chestmaster.compat.VersionHelper
import com.chestmaster.gui.ChestMasterScreen
import com.chestmaster.highlight.ChestLocationHighlighter
import com.chestmaster.scanner.ChestScanner
import com.chestmaster.valuation.ItemValuator
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.minecraft.client.KeyMapping
import org.lwjgl.glfw.GLFW

class chestmasterClient : ClientModInitializer {
    override fun onInitializeClient() {
        // Register the chest-highlight renderer before any ticks fire.
        ChestLocationHighlighter.init()

        // Keybinding to open the GUI (no default key — configurable by the player in Options → Controls).
        val guiCategory = KeyMapping.Category.register(
            VersionHelper.resourceLocation("chestmaster", "controls")
        )
        val openGuiKey = KeyBindingHelper.registerKeyBinding(
            KeyMapping(
                "key.chestmaster.open",
                GLFW.GLFW_KEY_UNKNOWN,
                guiCategory
            )
        )

        // Register commands only on the client side.
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            ChestMasterCommand.register(dispatcher)
        }

        // Restore persisted auto-scan state so players don't have to re-enable it each session.
        val savedAutoScan = runCatching { ChestMasterMod.configManager.config.autoScan }.getOrDefault(false)
        if (savedAutoScan) {
            ChestScanner.setAutoScanEnabled(true)
        }

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            ChestMasterCommand.onClientTick(client)
            ChestScanner.onClientTick(client)
            ChestLocationHighlighter.onClientTick(client)

            // Check the GUI hotkey each tick.
            while (openGuiKey.consumeClick()) {
                if (client.screen == null) {
                    client.setScreen(ChestMasterScreen())
                }
            }
        }

        // Start async market data loading for pricing.
        ItemValuator.updateAllPrices()
    }
}
