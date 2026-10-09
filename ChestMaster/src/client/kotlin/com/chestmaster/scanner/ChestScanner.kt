/*
 * Chest tracking approach ported from SkyOcean's ChestTracker
 * (https://github.com/meowdding/SkyOcean, src/main/kotlin/me/owdding/skyocean/features/misc/ChestTracker.kt).
 * Copyright (c) meowdding / SkyOcean contributors, MIT License — see THIRD_PARTY_NOTICES.md.
 */
package com.chestmaster.scanner

import com.chestmaster.ChestMasterMod
import com.chestmaster.database.ItemRecord
import com.chestmaster.util.ItemUtils
import com.chestmaster.util.WorldUtils
import com.chestmaster.util.skyblockId
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.contents.TranslatableContents
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.ChestType
import tech.thatgravyboat.skyblockapi.api.SkyBlockAPI
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.level.BlockChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.level.RightClickBlockEvent
import tech.thatgravyboat.skyblockapi.api.events.screen.ContainerCloseEvent
import tech.thatgravyboat.skyblockapi.api.events.screen.InventoryChangeEvent
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import tech.thatgravyboat.skyblockapi.api.location.SkyBlockIsland

/**
 * Indexes the contents of chests on the player's Private Island.
 *
 * How a chest is recognised and located (the source of every earlier bug):
 *  - position comes from the right-click on the block itself, not from the crosshair
 *    or a radius search — so the stored coordinates are exact;
 *  - a container counts as a chest only when its title is the vanilla *translation key*
 *    `container.chest` / `container.chestDouble`. Hypixel menus (loadouts, sell dialogs,
 *    sacks, bazaar…) send plain-text titles, so they can never match — in any language;
 *  - contents are saved when the container closes, when everything has been received,
 *    instead of polling for a few ticks after opening;
 *  - each half of a double chest stores the items that are physically in it;
 *  - breaking a chest removes its items from the database.
 */
object ChestScanner {
    private var first: BlockPos? = null
    private var second: BlockPos? = null
    private var trackedSlots: List<Slot>? = null
    private var trackedTitle: String = ""

    @Volatile
    private var autoScanEnabled = false

    fun init() {
        SkyBlockAPI.eventBus.register(this)
    }

    fun isAutoScanEnabled(): Boolean = autoScanEnabled

    /** Saving now happens synchronously on close, so nothing is ever "pending". */
    fun isScanPending(): Boolean = false

    fun enableAutoScan(): Boolean {
        if (autoScanEnabled) return false
        autoScanEnabled = true
        return true
    }

    fun disableAutoScan(): Boolean {
        if (!autoScanEnabled) return false
        autoScanEnabled = false
        reset()
        return true
    }

    fun setAutoScanEnabled(enabled: Boolean) {
        autoScanEnabled = enabled
        if (!enabled) reset()
    }

    private fun isTrackingAllowed(): Boolean =
        autoScanEnabled && SkyBlockIsland.PRIVATE_ISLAND.inIsland() && !LocationAPI.isGuest

    @Subscription
    fun onRightClickBlock(event: RightClickBlockEvent) {
        if (!isTrackingAllowed()) return
        val level = Minecraft.getInstance().level ?: return
        val state = level.getBlockState(event.pos)
        if (state.block !is ChestBlock) return

        val pos = event.pos.immutable()
        first = pos
        second = null

        val chestType = state.getValue(BlockStateProperties.CHEST_TYPE)
        if (chestType == ChestType.SINGLE) return

        // In a double chest the top 27 slots belong to the RIGHT half (the "first" block).
        val other = pos.relative(ChestBlock.getConnectedDirection(state)).immutable()
        if (chestType == ChestType.RIGHT) {
            second = other
        } else {
            first = other
            second = pos
        }
    }

    @Subscription
    fun onInventoryChange(event: InventoryChangeEvent) {
        if (!isTrackingAllowed() || first == null) return
        if (!isChestTitle(event.titleComponent)) return
        trackedSlots = event.inventory
        trackedTitle = event.title
    }

    @Subscription(event = [ContainerCloseEvent::class])
    fun onContainerClose() {
        saveTracked()
        reset()
    }

    @Subscription
    fun onBlockChange(event: BlockChangeEvent) {
        if (!SkyBlockIsland.PRIVATE_ISLAND.inIsland() || LocationAPI.isGuest) return
        val level = Minecraft.getInstance().level ?: return
        // The event fires before the change is applied: the level still holds the old state.
        if (level.getBlockState(event.pos).isChest() && !event.state.isChest()) {
            val serverKey = WorldUtils.getCurrentServerKey()
            val pos = event.pos.immutable()
            ChestMasterMod.dbExecutor.execute {
                runCatching { ChestMasterMod.db.deleteChests(listOf(pos), serverKey) }
                    .onFailure { ChestMasterMod.LOGGER.error("Failed to remove broken chest at $pos", it) }
            }
        }
    }

    /**
     * Manual `/cm now`: saves the currently open chest immediately (it is saved again on close).
     * Only works for a chest that was opened by right-clicking it on the Private Island.
     */
    fun scanNow(screen: AbstractContainerScreen<*>, handler: ChestMenu): Int {
        if (first == null || !isChestTitle(screen.title)) return 0
        trackedSlots = handler.slots
        trackedTitle = screen.title.string
        return saveTracked()
    }

    fun canScanScreen(screen: AbstractContainerScreen<*>): Boolean = isChestTitle(screen.title)

    /** Kept for API compatibility with the tick loop; tracking is fully event-driven now. */
    @Suppress("UNUSED_PARAMETER")
    fun onClientTick(client: Minecraft) = Unit

    private fun saveTracked(): Int {
        val slots = trackedSlots ?: return 0
        val firstPos = first ?: return 0
        val secondPos = second
        val serverKey = WorldUtils.getCurrentServerKey()
        val scanTime = System.currentTimeMillis()

        val records = mutableListOf<ItemRecord>()
        for (slot in slots) {
            if (slot.container is Inventory) continue // player inventory part of the menu
            val stack = slot.item
            if (stack.isEmpty) continue

            val pos = when {
                slot.index < 27 -> firstPos
                secondPos != null -> secondPos
                else -> {
                    ChestMasterMod.LOGGER.warn("Item in slot ${slot.index} has no chest half at $firstPos")
                    continue
                }
            }

            val baseItemId = ItemUtils.getItemId(stack)
            val nbt = ItemUtils.getNbtString(stack)
            val skyblockId = ItemUtils.normalizeSkyblockId(stack.skyblockId)
                ?: ItemUtils.extractSkyblockIdFromNbtString(nbt)
                ?: baseItemId
            records += ItemRecord(
                id = 0,
                itemId = skyblockId,
                baseItemId = baseItemId,
                displayName = ItemUtils.getDisplayName(stack),
                itemNbt = nbt,
                count = stack.count,
                chestX = pos.x,
                chestY = pos.y,
                chestZ = pos.z,
                label = trackedTitle,
                serverKey = serverKey,
                lastSeen = scanTime
            )
        }

        // Replace both halves even when empty, so taken-out items disappear from the index.
        val positions = listOfNotNull(firstPos, secondPos)
        ChestMasterMod.dbExecutor.execute {
            try {
                ChestMasterMod.db.replaceChests(positions, serverKey, records)
                if (ChestMasterMod.isVerboseLogging()) {
                    ChestMasterMod.LOGGER.debug("Saved ${records.size} items from chest at $positions")
                }
            } catch (e: Exception) {
                ChestMasterMod.LOGGER.error("Failed to save chest contents", e)
            }
        }
        return records.size
    }

    private fun reset() {
        first = null
        second = null
        trackedSlots = null
        trackedTitle = ""
    }

    private fun isChestTitle(title: Component): Boolean {
        val contents = (title as? MutableComponent)?.contents as? TranslatableContents ?: return false
        return contents.key.startsWith("container.chest")
    }

    private fun BlockState.isChest(): Boolean = block is ChestBlock
}
