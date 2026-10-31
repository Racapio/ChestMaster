package com.chestmaster.compat

import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.resources.Identifier
import tech.thatgravyboat.skyblockapi.api.events.screen.ScreenKeyReleasedEvent

object VersionHelper {
    /** SkyBlockAPI 4.3 (26.3) exposes the vanilla KeyEvent directly; 4.2 only the raw codes. */
    fun keyEventOf(event: ScreenKeyReleasedEvent): KeyEvent = KeyEvent(event.key, event.scanCode, event.modifiers)

    fun getLinesBuffer(consumers: MultiBufferSource): VertexConsumer =
        consumers.getBuffer(RenderTypes.LINES)

    fun resourceLocation(namespace: String, path: String): Identifier =
        Identifier.fromNamespaceAndPath(namespace, path)

    fun currentScreen(client: Minecraft): Screen? = client.screen

    fun setScreen(client: Minecraft, screen: Screen?) {
        client.setScreen(screen)
    }
}
