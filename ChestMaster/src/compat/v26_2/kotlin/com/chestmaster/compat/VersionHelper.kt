package com.chestmaster.compat

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.resources.Identifier

object VersionHelper {
    fun resourceLocation(namespace: String, path: String): Identifier =
        Identifier.fromNamespaceAndPath(namespace, path)

    // MC 26.2: the current screen moved from Minecraft to the Gui object.
    fun currentScreen(client: Minecraft): Screen? = client.gui.screen()

    fun setScreen(client: Minecraft, screen: Screen?) {
        client.gui.setScreen(screen)
    }
}
