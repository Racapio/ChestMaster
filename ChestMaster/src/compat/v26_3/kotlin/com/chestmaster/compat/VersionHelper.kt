package com.chestmaster.compat

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.resources.Identifier
import tech.thatgravyboat.skyblockapi.api.events.screen.ScreenKeyReleasedEvent

object VersionHelper {
    /** SkyBlockAPI 4.3 (26.3) exposes the vanilla KeyEvent directly; 4.2 only the raw codes. */
    fun keyEventOf(event: ScreenKeyReleasedEvent): KeyEvent = event.event

    fun resourceLocation(namespace: String, path: String): Identifier =
        Identifier.fromNamespaceAndPath(namespace, path)

    // MC 26.2: the current screen moved from Minecraft to the Gui object.
    fun currentScreen(client: Minecraft): Screen? = client.gui.screen()

    fun setScreen(client: Minecraft, screen: Screen?) {
        client.gui.setScreen(screen)
    }
}
