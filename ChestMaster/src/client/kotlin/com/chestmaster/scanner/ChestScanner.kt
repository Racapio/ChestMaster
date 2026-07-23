package com.chestmaster.scanner

import com.chestmaster.ChestMasterMod
import com.chestmaster.compat.VersionHelper
import com.chestmaster.database.ItemRecord
import com.chestmaster.util.ContainerFilters
import com.chestmaster.util.ItemUtils
import com.chestmaster.util.WorldUtils
import com.chestmaster.util.skyblockId
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.state.properties.ChestType
import net.minecraft.world.phys.BlockHitResult

object ChestScanner {
    private data class PendingScan(
        val screen: AbstractContainerScreen<*>,
        val handler: ChestMenu,
        val title: String,
        val chestPos: BlockPos?,
        val serverKey: String,
        var ticksUntilAttempt: Int,
        var attemptsLeft: Int
    )

    @Volatile
    private var autoScanEnabled = false

    @Volatile
    private var lastScanKey = ""

    @Volatile
    private var lastScanTimeMs = 0L

    // All access to pendingScan is from the client tick thread, so @Volatile is sufficient.
    @Volatile
    private var pendingScan: PendingScan? = null

    // Cache the resolved chest position for up to 1 second to avoid re-scanning 1521 blocks
    // on every retry tick.
    @Volatile
    private var cachedChestPos: BlockPos? = null

    @Volatile
    private var cachedChestPosTimestampMs = 0L

    private const val CHEST_POS_CACHE_MS = 1000L
    private const val DUPLICATE_SCAN_WINDOW_MS = 1000L
    private const val INITIAL_SCAN_DELAY_TICKS = 6
    private const val RETRY_DELAY_TICKS = 3
    private const val MAX_SCAN_ATTEMPTS = 12
    private const val CHEST_SEARCH_RADIUS_XZ = 6
    private const val CHEST_SEARCH_RADIUS_Y = 4

    // Only real storage containers keep their vanilla localized title on Hypixel
    // ("Large Chest" / "Большой сундук"); every server menu (loadouts, auction
    // dialogs, sacks, bazaar, …) uses a custom title. We therefore whitelist by
    // exact vanilla name instead of trusting the crosshair, which falsely matched
    // any menu opened while facing a chest.
    private val vanillaStorageTranslationKeys = listOf(
        "container.chest",
        "container.chestDouble",
        "container.barrel",
        "container.shulkerBox"
    )

    // Vanilla names resolve against the client language; cache them and refresh if the
    // resolved set changes (e.g. the player switches language mid-session).
    @Volatile
    private var cachedVanillaStorageTitles: Set<String> = emptySet()

    private fun vanillaStorageTitles(): Set<String> {
        val resolved = vanillaStorageTranslationKeys
            .map { net.minecraft.network.chat.Component.translatable(it).string.trim().lowercase() }
            .filterTo(HashSet()) { it.isNotBlank() }
        if (resolved != cachedVanillaStorageTitles && resolved.isNotEmpty()) {
            cachedVanillaStorageTitles = resolved
        }
        return if (resolved.isNotEmpty()) resolved else cachedVanillaStorageTitles
    }

    fun isAutoScanEnabled(): Boolean = autoScanEnabled
    fun isScanPending(): Boolean = pendingScan != null

    fun enableAutoScan(): Boolean {
        if (autoScanEnabled) return false
        autoScanEnabled = true
        return true
    }

    fun disableAutoScan(): Boolean {
        if (!autoScanEnabled) return false
        autoScanEnabled = false
        pendingScan = null
        return true
    }

    fun setAutoScanEnabled(enabled: Boolean) {
        autoScanEnabled = enabled
        if (!enabled) pendingScan = null
    }

    fun onScreenOpen(screen: AbstractContainerScreen<*>, handler: ChestMenu) {
        if (!autoScanEnabled) return

        val title = screen.title.string
        if (!isScannableContainerTitle(title)) {
            if (ChestMasterMod.isVerboseLogging()) {
                ChestMasterMod.LOGGER.debug("Skipped non-chest container: $title")
            }
            return
        }

        val focusedChestPos = resolveFocusedStoragePos()
        scheduleScan(screen, handler, allowDuplicateGuard = true, chestPosHint = focusedChestPos)
    }

    fun scanNow(screen: AbstractContainerScreen<*>, handler: ChestMenu): Int {
        val title = screen.title.string
        if (!isScannableContainerTitle(title)) {
            return 0
        }

        val focusedChestPos = resolveFocusedStoragePos()
        val scanned = scanInternal(
            screen = screen,
            handler = handler,
            deduplicate = false,
            forcedChestPos = focusedChestPos ?: resolveCurrentChestPos()
        )

        if (scanned == 0) {
            // On Hypixel, container content can arrive a few ticks after opening.
            scheduleScan(
                screen,
                handler,
                allowDuplicateGuard = false,
                initialDelay = 2,
                chestPosHint = focusedChestPos
            )
        }

        return scanned
    }

    fun canScanCurrentScreen(screenTitle: String): Boolean {
        return isScannableContainerTitle(screenTitle)
    }

    fun onClientTick(client: Minecraft) {
        val task = pendingScan ?: return

        val current = VersionHelper.currentScreen(client)
        if (current !== task.screen) {
            pendingScan = null
            return
        }

        val currentMenu = task.screen.menu
        if (currentMenu !== task.handler) {
            pendingScan = null
            return
        }

        if (task.ticksUntilAttempt > 0) {
            task.ticksUntilAttempt -= 1
            return
        }

        val scanned = scanInternal(
            screen = task.screen,
            handler = task.handler,
            deduplicate = false,
            forcedChestPos = task.chestPos,
            serverKeyOverride = task.serverKey
        )
        if (scanned > 0) {
            pendingScan = null
            return
        }

        task.attemptsLeft -= 1
        if (task.attemptsLeft <= 0) {
            if (ChestMasterMod.isVerboseLogging()) {
                ChestMasterMod.LOGGER.debug("No items detected after delayed retries for chest: ${task.title}")
            }
            pendingScan = null
            return
        }

        task.ticksUntilAttempt = RETRY_DELAY_TICKS
    }

    private fun scheduleScan(
        screen: AbstractContainerScreen<*>,
        handler: ChestMenu,
        allowDuplicateGuard: Boolean,
        initialDelay: Int = INITIAL_SCAN_DELAY_TICKS,
        chestPosHint: BlockPos? = null
    ) {
        val title = screen.title.string
        val normalizedPos = normalizeStoragePos(chestPosHint ?: resolveCurrentChestPos())
        val posKey = normalizedPos?.let { "${it.x},${it.y},${it.z}" } ?: "unknown"
        val scanKey = "$posKey:$title:${handler.rowCount}"
        val now = System.currentTimeMillis()

        if (allowDuplicateGuard && scanKey == lastScanKey && now - lastScanTimeMs < DUPLICATE_SCAN_WINDOW_MS) {
            return
        }

        if (allowDuplicateGuard) {
            lastScanKey = scanKey
            lastScanTimeMs = now
        }

        pendingScan = PendingScan(
            screen = screen,
            handler = handler,
            title = title,
            chestPos = normalizedPos,
            serverKey = WorldUtils.getCurrentServerKey(),
            ticksUntilAttempt = initialDelay.coerceAtLeast(0),
            attemptsLeft = MAX_SCAN_ATTEMPTS
        )
    }

    private fun scanInternal(
        screen: AbstractContainerScreen<*>,
        handler: ChestMenu,
        deduplicate: Boolean,
        forcedChestPos: BlockPos? = null,
        serverKeyOverride: String? = null
    ): Int {
        val title = screen.title.string
        val now = System.currentTimeMillis()
        val normalizedPos = normalizeStoragePos(forcedChestPos ?: resolveCurrentChestPos())
        val posKey = normalizedPos?.let { "${it.x},${it.y},${it.z}" } ?: "unknown"
        val scanKey = "$posKey:$title:${handler.rowCount}"

        if (deduplicate && scanKey == lastScanKey && now - lastScanTimeMs < DUPLICATE_SCAN_WINDOW_MS) {
            return 0
        }

        lastScanKey = scanKey
        lastScanTimeMs = now

        val pos = normalizedPos ?: BlockPos(0, 0, 0)
        if (pos.x == 0 && pos.y == 0 && pos.z == 0 && ChestMasterMod.isVerboseLogging()) {
            ChestMasterMod.LOGGER.debug("Chest position could not be resolved for '$title'; using legacy 0,0,0.")
        }

        val serverKey = serverKeyOverride ?: WorldUtils.getCurrentServerKey()
        val scanTime = System.currentTimeMillis()
        val itemsToSave = mutableListOf<ItemRecord>()
        val rows = handler.rowCount
        val chestSize = rows * 9
        for (i in 0 until chestSize) {
            val slot = handler.getSlot(i)
            val stack = slot.item
            if (!stack.isEmpty) {
                val baseItemId = ItemUtils.getItemId(stack)
                val nbt = ItemUtils.getNbtString(stack)
                val normalizedSkyblockId = ItemUtils.normalizeSkyblockId(stack.skyblockId)
                    ?: ItemUtils.extractSkyblockIdFromNbtString(nbt)
                    ?: baseItemId
                val displayName = ItemUtils.getDisplayName(stack)
                itemsToSave.add(
                    ItemRecord(
                        id = 0,
                        itemId = normalizedSkyblockId,
                        baseItemId = baseItemId,
                        displayName = displayName,
                        itemNbt = nbt,
                        count = stack.count,
                        chestX = pos.x,
                        chestY = pos.y,
                        chestZ = pos.z,
                        label = title,
                        serverKey = serverKey,
                        lastSeen = scanTime
                    )
                )
            }
        }

        if (itemsToSave.isEmpty()) return 0

        ChestMasterMod.dbExecutor.execute {
            try {
                ChestMasterMod.db.saveItems(itemsToSave)
                if (ChestMasterMod.isVerboseLogging()) {
                    ChestMasterMod.LOGGER.debug("Saved ${itemsToSave.size} items from $title")
                }
            } catch (e: Exception) {
                ChestMasterMod.LOGGER.error("Failed to save items to database", e)
            }
        }

        return itemsToSave.size
    }

    private fun resolveCurrentChestPos(): BlockPos? {
        val client = Minecraft.getInstance()
        val level = client.level ?: return null
        val player = client.player ?: return null

        resolveFocusedStoragePos()?.let { return it }

        // Return the cached position if it is still fresh.
        val now = System.currentTimeMillis()
        if (now - cachedChestPosTimestampMs < CHEST_POS_CACHE_MS) {
            return cachedChestPos
        }

        val center = player.blockPosition()
        var nearest: BlockPos? = null
        var nearestDist = Double.MAX_VALUE

        for (x in (center.x - CHEST_SEARCH_RADIUS_XZ)..(center.x + CHEST_SEARCH_RADIUS_XZ)) {
            for (y in (center.y - CHEST_SEARCH_RADIUS_Y)..(center.y + CHEST_SEARCH_RADIUS_Y)) {
                for (z in (center.z - CHEST_SEARCH_RADIUS_XZ)..(center.z + CHEST_SEARCH_RADIUS_XZ)) {
                    val pos = BlockPos(x, y, z)
                    val blockId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).block).toString()
                    if (!isStorageBlockId(blockId)) continue

                    val distance = pos.distToCenterSqr(player.position())
                    if (distance < nearestDist) {
                        nearestDist = distance
                        nearest = pos.immutable()
                    }
                }
            }
        }

        val result = normalizeStoragePos(nearest)
        cachedChestPos = result
        cachedChestPosTimestampMs = now
        return result
    }

    private fun resolveFocusedStoragePos(): BlockPos? {
        val client = Minecraft.getInstance()
        val level = client.level ?: return null

        val hitResult = client.hitResult
        if (hitResult is BlockHitResult) {
            val hitPos = hitResult.blockPos
            val blockId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(hitPos).block).toString()
            if (isStorageBlockId(blockId)) {
                return normalizeStoragePos(hitPos)
            }
        }

        return null
    }

    private fun normalizeStoragePos(rawPos: BlockPos?): BlockPos? {
        if (rawPos == null) return null

        val client = Minecraft.getInstance()
        val level = client.level ?: return rawPos.immutable()
        val state = level.getBlockState(rawPos)

        if (state.block !is ChestBlock) {
            return rawPos.immutable()
        }

        val chestType = state.getValue(ChestBlock.TYPE)
        if (chestType == ChestType.SINGLE) {
            return rawPos.immutable()
        }

        val connectedDirection = ChestBlock.getConnectedDirection(state)
        val connectedPos = rawPos.relative(connectedDirection)
        val connectedState = level.getBlockState(connectedPos)
        if (connectedState.block !is ChestBlock) {
            return rawPos.immutable()
        }

        return minBlockPos(rawPos, connectedPos)
    }

    private fun minBlockPos(a: BlockPos, b: BlockPos): BlockPos {
        return when {
            a.x != b.x -> if (a.x < b.x) a.immutable() else b.immutable()
            a.y != b.y -> if (a.y < b.y) a.immutable() else b.immutable()
            else -> if (a.z <= b.z) a.immutable() else b.immutable()
        }
    }

    private fun isScannableContainerTitle(title: String): Boolean {
        if (ContainerFilters.isBlockedTitle(title)) {
            return false
        }
        // Strict whitelist: the title must be exactly a vanilla storage name.
        // This is the only reliable signal on Hypixel, where real chests keep their
        // vanilla name and every menu (loadouts, auction/sell dialogs, sacks, …) is
        // a custom-titled ChestMenu that would otherwise slip through.
        val normalized = title.trim().lowercase()
        return normalized in vanillaStorageTitles()
    }

    private fun isStorageBlockId(blockId: String): Boolean {
        return blockId.contains("chest") ||
            blockId.contains("barrel") ||
            blockId.contains("shulker_box")
    }
}
