package com.chestmaster.command

import com.chestmaster.ChestMasterMod
import com.chestmaster.gui.ChestMasterScreen
import com.chestmaster.highlight.ChestLocationHighlighter
import com.chestmaster.scanner.ChestScanner
import com.chestmaster.valuation.ItemValuator
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.ChestMenu
import java.nio.file.Files
import kotlin.math.max

object ChestMasterCommand {
    @Volatile
    private var pendingGuiOpen = false

    fun register(dispatcher: CommandDispatcher<FabricClientCommandSource>) {
        dispatcher.register(buildRootCommand("chestmaster"))
        dispatcher.register(buildRootCommand("cm"))
    }

    fun onClientTick(client: Minecraft) {
        if (!pendingGuiOpen) return

        pendingGuiOpen = false
        runCatching {
            client.setScreen(ChestMasterScreen())
        }.onFailure { error ->
            ChestMasterMod.LOGGER.error("Failed to open ChestMaster GUI", error)
        }
    }

    private fun buildRootCommand(rootName: String): LiteralArgumentBuilder<FabricClientCommandSource> {
        return ClientCommandManager.literal(rootName)
            .executes { openGui(it.source) }
            .then(
                ClientCommandManager.literal("help")
                    .executes { sendHelp(it.source) }
            )
            .then(buildScanCommand("scan"))
            .then(buildScanCommand("s"))
            .then(
                ClientCommandManager.literal("on")
                    .executes { setAutoScan(it.source, enabled = true) }
            )
            .then(
                ClientCommandManager.literal("off")
                    .executes { setAutoScan(it.source, enabled = false) }
            )
            .then(
                ClientCommandManager.literal("status")
                    .executes { sendScanStatus(it.source) }
            )
            .then(
                ClientCommandManager.literal("now")
                    .executes { scanNow(it.source) }
            )
            .then(buildDbPathCommand("dbpath"))
            .then(buildDbPathCommand("db"))
            .then(buildResetCommand("resetdb"))
            .then(buildResetCommand("reset"))
            .then(buildPricesCommand("prices"))
            .then(buildPricesCommand("p"))
            .then(buildOpenCommand("open"))
            .then(buildOpenCommand("o"))
            .then(buildMarkersCommand("markers"))
            .then(buildMarkersCommand("m"))
            .then(buildLogsCommand("logs"))
            .then(buildLogsCommand("log"))
            .then(
                ClientCommandManager.literal("scan_legacy")
                    .executes { scanNow(it.source) }
            )
    }

    private fun buildOpenCommand(name: String): LiteralArgumentBuilder<FabricClientCommandSource> {
        return ClientCommandManager.literal(name)
            .executes { openGui(it.source) }
    }

    private fun buildDbPathCommand(name: String): LiteralArgumentBuilder<FabricClientCommandSource> {
        return ClientCommandManager.literal(name)
            .executes { showDbPath(it.source) }
    }

    private fun buildResetCommand(name: String): LiteralArgumentBuilder<FabricClientCommandSource> {
        return ClientCommandManager.literal(name)
            .executes { sourceContext ->
                sourceContext.source.sendFeedback(
                    Component.literal("This will delete all saved items. Run /cm reset confirm (or /chestmaster resetdb confirm).")
                )
                1
            }
            .then(
                ClientCommandManager.literal("confirm")
                    .executes { runResetDatabase(it.source) }
            )
    }

    private fun buildPricesCommand(name: String): LiteralArgumentBuilder<FabricClientCommandSource> {
        return ClientCommandManager.literal(name)
            .executes { sendPriceStatus(it.source) }
            .then(
                ClientCommandManager.literal("status")
                    .executes { sendPriceStatus(it.source) }
            )
            .then(
                ClientCommandManager.literal("reload")
                    .executes { sourceContext ->
                        ItemValuator.updateAllPrices()
                        sourceContext.source.sendFeedback(Component.literal("Price reload started."))
                        1
                    }
            )
    }

    private fun buildMarkersCommand(name: String): LiteralArgumentBuilder<FabricClientCommandSource> {
        return ClientCommandManager.literal(name)
            .executes { sourceContext ->
                sourceContext.source.sendFeedback(Component.literal("Usage: /cm $name clear"))
                1
            }
            .then(
                ClientCommandManager.literal("clear")
                    .executes { sourceContext ->
                        ChestLocationHighlighter.clear()
                        sourceContext.source.sendFeedback(Component.literal("Chest markers cleared."))
                        1
                    }
            )
    }

    private fun buildScanCommand(name: String): LiteralArgumentBuilder<FabricClientCommandSource> {
        return ClientCommandManager.literal(name)
            .executes { sendScanStatus(it.source) }
            .then(
                ClientCommandManager.literal("start")
                    .executes { setAutoScan(it.source, enabled = true) }
            )
            .then(
                ClientCommandManager.literal("on")
                    .executes { setAutoScan(it.source, enabled = true) }
            )
            .then(
                ClientCommandManager.literal("stop")
                    .executes { setAutoScan(it.source, enabled = false) }
            )
            .then(
                ClientCommandManager.literal("off")
                    .executes { setAutoScan(it.source, enabled = false) }
            )
            .then(
                ClientCommandManager.literal("status")
                    .executes { sendScanStatus(it.source) }
            )
            .then(
                ClientCommandManager.literal("now")
                    .executes { scanNow(it.source) }
            )
    }

    private fun buildLogsCommand(name: String): LiteralArgumentBuilder<FabricClientCommandSource> {
        return ClientCommandManager.literal(name)
            .executes { sourceContext ->
                val state = if (ChestMasterMod.isVerboseLogging()) "enabled" else "disabled"
                sourceContext.source.sendFeedback(Component.literal("Verbose logging is $state."))
                1
            }
            .then(
                ClientCommandManager.literal("status")
                    .executes { sourceContext ->
                        val state = if (ChestMasterMod.isVerboseLogging()) "enabled" else "disabled"
                        sourceContext.source.sendFeedback(Component.literal("Verbose logging is $state."))
                        1
                    }
            )
            .then(
                ClientCommandManager.literal("on")
                    .executes { sourceContext ->
                        setVerboseLogging(sourceContext.source, enabled = true)
                    }
            )
            .then(
                ClientCommandManager.literal("off")
                    .executes { sourceContext ->
                        setVerboseLogging(sourceContext.source, enabled = false)
                    }
            )
    }

    private fun openGui(source: FabricClientCommandSource): Int {
        pendingGuiOpen = true
        source.sendFeedback(Component.literal("Opening ChestMaster GUI..."))
        return 1
    }

    private fun sendHelp(source: FabricClientCommandSource): Int {
        source.sendFeedback(
            Component.literal(
                "ChestMaster: /cm (or /chestmaster), /cm s on|off|status|now, /cm db, /cm reset confirm, /cm p status|reload, /cm m clear, /cm logs on|off|status"
            )
        )
        return 1
    }

    private fun setVerboseLogging(source: FabricClientCommandSource, enabled: Boolean): Int {
        val config = ChestMasterMod.configManager.config
        config.verboseLogging = enabled
        ChestMasterMod.configManager.save()
        val state = if (enabled) "enabled" else "disabled"
        source.sendFeedback(Component.literal("Verbose logging $state."))
        return 1
    }

    private fun setAutoScan(source: FabricClientCommandSource, enabled: Boolean): Int {
        val changed = if (enabled) ChestScanner.enableAutoScan() else ChestScanner.disableAutoScan()
        if (enabled) {
            source.sendFeedback(
                Component.literal(
                    if (changed) "Chest auto-scan enabled." else "Chest auto-scan is already enabled."
                )
            )
        } else {
            source.sendFeedback(
                Component.literal(
                    if (changed) "Chest auto-scan disabled." else "Chest auto-scan is already disabled."
                )
            )
        }
        return 1
    }

    private fun sendScanStatus(source: FabricClientCommandSource): Int {
        val state = if (ChestScanner.isAutoScanEnabled()) "enabled" else "disabled"
        val pendingSuffix = if (ChestScanner.isScanPending()) " (scan pending)" else ""
        source.sendFeedback(Component.literal("Chest auto-scan is $state$pendingSuffix."))
        return 1
    }

    private fun scanNow(source: FabricClientCommandSource): Int {
        val client = source.client
        val screen = client.screen

        if (screen !is AbstractContainerScreen<*>) {
            source.sendError(Component.literal("Open a chest and run /cm now or /cm s now."))
            return 0
        }

        val menu = screen.menu
        if (menu !is ChestMenu) {
            source.sendError(Component.literal("Opened container is not a chest."))
            return 0
        }

        if (!ChestScanner.canScanCurrentScreen(screen.title.string)) {
            source.sendError(Component.literal("This container is not recognized as a chest inventory."))
            return 0
        }

        val scanned = ChestScanner.scanNow(screen, menu)
        if (scanned > 0) {
            source.sendFeedback(Component.literal("Scanned $scanned items."))
        } else {
            val message = if (ChestScanner.isScanPending()) {
                "No items yet. Retrying scan for a short time..."
            } else {
                "Chest scanned, no items found."
            }
            source.sendFeedback(Component.literal(message))
        }
        return 1
    }

    private fun showDbPath(source: FabricClientCommandSource): Int {
        val path = ChestMasterMod.db.getDatabasePath()
        if (path == null) {
            source.sendError(Component.literal("Database path is not initialized yet."))
            return 0
        }

        val exists = Files.exists(path)
        val status = if (exists) "exists" else "not created yet"
        source.sendFeedback(Component.literal("ChestMaster DB: $path ($status)"))
        return 1
    }

    private fun runResetDatabase(source: FabricClientCommandSource): Int {
        val result = ChestMasterMod.db.resetItemsAndCompact()
        source.sendFeedback(
            Component.literal(
                "ChestMaster database reset complete. Deleted ${result.deletedRecords} record(s). " +
                    "DB size: ${formatBytes(result.sizeBeforeBytes)} -> ${formatBytes(result.sizeAfterBytes)} " +
                    "(freed ${formatBytes(result.freedBytes)})."
            )
        )
        return 1
    }

    private fun sendPriceStatus(source: FabricClientCommandSource): Int {
        source.sendFeedback(Component.literal("Price status: ${ItemValuator.getDebugStatus()}"))
        return 1
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val bytesDouble = bytes.toDouble()
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        return when {
            bytesDouble >= gb -> String.format("%.2f GB", bytesDouble / gb)
            bytesDouble >= mb -> String.format("%.2f MB", bytesDouble / mb)
            bytesDouble >= kb -> String.format("%.1f KB", bytesDouble / kb)
            else -> "${max(1L, bytes)} B"
        }
    }
}
