/*
 * "Search the hovered item" keybind, inspired by SkyOcean's ItemSearch
 * (https://github.com/meowdding/SkyOcean, features/item/search/ItemSearch.kt).
 * Copyright (c) meowdding / SkyOcean contributors, MIT License — see THIRD_PARTY_NOTICES.md.
 */
package com.chestmaster.search

import com.chestmaster.mixin.AbstractContainerScreenAccessor
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.KeyEvent
import tech.thatgravyboat.skyblockapi.api.SkyBlockAPI
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.screen.ScreenKeyReleasedEvent

/**
 * Pressing the "Open ChestMaster" key while hovering an item in any inventory opens
 * the ChestMaster GUI already searching for that item.
 */
object HoverSearch {
    private val formattingRegex = Regex("§.")

    private var keyMapping: KeyMapping? = null
    private var openWithQuery: ((String) -> Unit)? = null

    /**
     * [open] is supplied by the version-specific client entrypoint, because the GUI
     * screen class and the screen-switching API differ between Minecraft versions.
     */
    fun init(key: KeyMapping, open: (String) -> Unit) {
        keyMapping = key
        openWithQuery = open
        SkyBlockAPI.eventBus.register(this)
    }

    @Subscription
    fun onKeyReleased(event: ScreenKeyReleasedEvent) {
        val key = keyMapping ?: return
        if (!key.matches(KeyEvent(event.key, event.scanCode, event.modifiers))) return
        val screen = event.screen as? AbstractContainerScreen<*> ?: return

        val stack = (screen as AbstractContainerScreenAccessor).`chestmaster$getHoveredSlot`()?.item ?: return
        if (stack.isEmpty) return

        val query = formattingRegex.replace(stack.hoverName.string, "")
            .replace(Regex("^\\[Lvl \\d+]\\s*"), "")
            .trim()
        if (query.isEmpty()) return

        val open = openWithQuery ?: return
        // Switch screens on the next frame, outside the key event dispatch.
        Minecraft.getInstance().execute {
            screen.onClose()
            open(query)
        }
    }
}
