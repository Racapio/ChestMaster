package com.client

import com.chestmaster.command.ChestMasterCommand
import com.chestmaster.highlight.ChestLocationHighlighter
import com.chestmaster.scanner.ChestScanner
import com.chestmaster.valuation.ItemValuator
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents

class chestmasterClient : ClientModInitializer {
    override fun onInitializeClient() {
        // Register commands only on the client side.
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            ChestMasterCommand.register(dispatcher)
        }

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            ChestMasterCommand.onClientTick(client)
            ChestScanner.onClientTick(client)
            ChestLocationHighlighter.onClientTick(client)
        }

        // Start async market data loading for pricing.
        ItemValuator.updateAllPrices()
    }
}
