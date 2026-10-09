/*
 * In-inventory item highlighting inspired by SkyOcean's ItemHighlighter
 * (https://github.com/meowdding/SkyOcean, features/item/search/highlight/ItemHighlighter.kt).
 * Copyright (c) meowdding / SkyOcean contributors, MIT License — see THIRD_PARTY_NOTICES.md.
 */
package com.chestmaster.highlight

import com.chestmaster.ChestMasterMod
import com.chestmaster.database.ItemRecord
import com.chestmaster.mixin.AbstractContainerScreenAccessor
import com.chestmaster.util.ItemUtils
import com.chestmaster.util.skyblockId
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.item.ItemStack
import tech.thatgravyboat.skyblockapi.api.SkyBlockAPI
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.render.RenderScreenForegroundEvent
import java.util.WeakHashMap

/**
 * While an item is selected in the ChestMaster GUI, every matching stack in any open
 * inventory (chests, storage, player inventory) gets a coloured overlay. The highlight —
 * together with the in-world chest markers — clears itself after a configurable time.
 */
object SearchHighlight {
    private const val OVERLAY_COLOR = 0x6600FF59
    private const val BORDER_COLOR = 0xCC00FF59.toInt()

    @Volatile
    private var targetKey: String? = null

    @Volatile
    private var expiresAtMs: Long = 0L

    // Per-stack match cache: ItemStack has identity equality, so this is keyed by instance
    // and avoids serialising NBT for every slot on every frame.
    private val matchCache = WeakHashMap<ItemStack, Boolean>()

    fun init() {
        SkyBlockAPI.eventBus.register(this)
    }

    fun isActive(): Boolean = targetKey != null

    fun set(record: ItemRecord) {
        val key = matchKey(record.itemId, record.baseItemId, record.itemNbt)
        synchronized(matchCache) { matchCache.clear() }
        targetKey = key
        val seconds = runCatching { ChestMasterMod.configManager.config.highlightSeconds }.getOrDefault(60)
        expiresAtMs = if (seconds <= 0) Long.MAX_VALUE else System.currentTimeMillis() + seconds * 1000L
    }

    fun clear() {
        targetKey = null
        synchronized(matchCache) { matchCache.clear() }
    }

    /** Called every client tick: expires the item highlight and the chest markers together. */
    fun onClientTick() {
        if (targetKey != null && System.currentTimeMillis() >= expiresAtMs) {
            clear()
            ChestLocationHighlighter.clear()
        }
    }

    @Subscription
    fun onRenderScreen(event: RenderScreenForegroundEvent) {
        val key = targetKey ?: return
        val screen = event.screen as? AbstractContainerScreen<*> ?: return
        val accessor = screen as AbstractContainerScreenAccessor
        val left = accessor.`chestmaster$getLeftPos`()
        val top = accessor.`chestmaster$getTopPos`()
        val graphics = event.graphics

        for (slot in screen.menu.slots) {
            val stack = slot.item
            if (stack.isEmpty || !matches(stack, key)) continue
            val x = left + slot.x
            val y = top + slot.y
            graphics.fill(x, y, x + 16, y + 16, OVERLAY_COLOR)
            graphics.fill(x, y, x + 16, y + 1, BORDER_COLOR)
            graphics.fill(x, y + 15, x + 16, y + 16, BORDER_COLOR)
            graphics.fill(x, y, x + 1, y + 16, BORDER_COLOR)
            graphics.fill(x + 15, y, x + 16, y + 16, BORDER_COLOR)
        }
    }

    private fun matches(stack: ItemStack, key: String): Boolean = synchronized(matchCache) {
        matchCache.getOrPut(stack) {
            runCatching {
                val baseId = ItemUtils.getItemId(stack)
                val nbt = ItemUtils.getNbtString(stack)
                val id = ItemUtils.normalizeSkyblockId(stack.skyblockId) ?: baseId
                matchKey(id, baseId, nbt) == key
            }.getOrDefault(false)
        }
    }

    /**
     * Loose identity used for highlighting: the same SkyBlock item, with pets split by
     * type+tier and enchanted books by their enchantments — not an exact NBT match, so
     * e.g. selecting "Jaderald" lights up every Jaderald regardless of its timestamp/uuid.
     */
    private fun matchKey(itemId: String, baseItemId: String, nbt: String): String {
        val extra = ItemUtils.extractExtraAttributesFromNbtString(nbt)
        ItemUtils.extractPetKey(extra)?.let { return "pet:$it" }
        val id = ItemUtils.normalizeSkyblockId(itemId) ?: baseItemId
        if (id == "ENCHANTED_BOOK") {
            val enchants = extra?.getCompound("enchantments")?.orElse(null)?.toString().orEmpty()
            return "book:$enchants"
        }
        if (id == "ATTRIBUTE_SHARD") {
            ItemUtils.shardMarketKeyFromNbt(nbt)?.let { return "shard:$it" }
        }
        return "id:$id"
    }
}
