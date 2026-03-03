package com.chestmaster.scanner

import com.chestmaster.ChestMasterMod
import com.chestmaster.database.ItemRecord
import com.chestmaster.util.ItemUtils
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
        var ticksUntilAttempt: Int,
        var attemptsLeft: Int
    )

    @Volatile
    private var autoScanEnabled = false

    @Volatile
    private var lastScanKey = ""

    @Volatile
    private var lastScanTimeMs = 0L

    @Volatile
    private var pendingScan: PendingScan? = null

    private const val DUPLICATE_SCAN_WINDOW_MS = 1000L
    private const val INITIAL_SCAN_DELAY_TICKS = 6
    private const val RETRY_DELAY_TICKS = 3
    private const val MAX_SCAN_ATTEMPTS = 12
    private const val CHEST_SEARCH_RADIUS_XZ = 6
    private const val CHEST_SEARCH_RADIUS_Y = 4

    private val allowedChestTitleKeywords = listOf(
        "chest",
        "ender chest"
    )

    private val blockedContainerKeywords = listOf(
        "minion",
        "auction",
        "bazaar",
        "pet",
        "museum",
        "wardrobe",
        "trade",
        "menu",
        "craft",
        "profile",
        "bank",
        "dungeon"
    )

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

    fun onScreenOpen(screen: AbstractContainerScreen<*>, handler: ChestMenu) {
        if (!autoScanEnabled) return

        val title = screen.title.string
        val focusedChestPos = resolveFocusedStoragePos()
        if (!isScannableContainerTitle(title, focusedChestPos)) {
            if (ChestMasterMod.isVerboseLogging()) {
                ChestMasterMod.LOGGER.debug("Skipped non-chest container: $title")
            }
            return
        }

        scheduleScan(screen, handler, allowDuplicateGuard = true, chestPosHint = focusedChestPos)
    }

    fun scanNow(screen: AbstractContainerScreen<*>, handler: ChestMenu): Int {
        val title = screen.title.string
        val focusedChestPos = resolveFocusedStoragePos()
        if (!isScannableContainerTitle(title, focusedChestPos)) {
            return 0
        }

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
        return isScannableContainerTitle(screenTitle, resolveFocusedStoragePos())
    }

    fun onClientTick(client: Minecraft) {
        val task = pendingScan ?: return

        val current = client.screen
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
            forcedChestPos = task.chestPos
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
            ticksUntilAttempt = initialDelay.coerceAtLeast(0),
            attemptsLeft = MAX_SCAN_ATTEMPTS
        )
    }

    private fun scanInternal(
        screen: AbstractContainerScreen<*>,
        handler: ChestMenu,
        deduplicate: Boolean,
        forcedChestPos: BlockPos? = null
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
                        label = title
                    )
                )
            }
        }

        if (itemsToSave.isEmpty()) return 0

        Thread(
            {
                try {
                    ChestMasterMod.db.saveItems(itemsToSave)
                    if (ChestMasterMod.isVerboseLogging()) {
                        ChestMasterMod.LOGGER.debug("Saved ${itemsToSave.size} items from $title")
                    }
                } catch (e: Exception) {
                    ChestMasterMod.LOGGER.error("Failed to save items to database", e)
                }
            },
            "ChestMaster-DB-Save"
        ).start()

        return itemsToSave.size
    }

    private fun resolveCurrentChestPos(): BlockPos? {
        val client = Minecraft.getInstance()
        val level = client.level ?: return null
        val player = client.player ?: return null

        resolveFocusedStoragePos()?.let { return it }

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

        return normalizeStoragePos(nearest)
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

    private fun isScannableContainerTitle(title: String, focusedStoragePos: BlockPos?): Boolean {
        val normalized = title.lowercase()
        if (blockedContainerKeywords.any { normalized.contains(it) }) {
            return false
        }
        if (allowedChestTitleKeywords.any { normalized.contains(it) }) {
            return true
        }
        // Allow custom chest names when the looked-at block is real storage.
        return focusedStoragePos != null
    }

    private fun isStorageBlockId(blockId: String): Boolean {
        return blockId.contains("chest") ||
            blockId.contains("barrel") ||
            blockId.contains("shulker_box")
    }
}
