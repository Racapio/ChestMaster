package com.chestmaster.gui

import com.chestmaster.ChestMasterMod
import com.chestmaster.scanner.ChestScanner
import com.chestmaster.valuation.ItemValuator
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/** Settings screen opened from Mod Menu (and reachable via ChestMasterScreen in the future). */
class ChestMasterConfigScreen(private val parent: Screen?) : Screen(Component.literal("ChestMaster Settings")) {

    private enum class SortModeOption(val id: String, val label: String) {
        DEFAULT("DEFAULT", "Default"),
        PRICE_DESC("PRICE_DESC", "Price ↓"),
        NAME_ASC("NAME_ASC", "Name A-Z"),
        COUNT_DESC("COUNT_DESC", "Count ↓");

        fun next(): SortModeOption = entries[(ordinal + 1) % entries.size]

        companion object {
            fun fromId(id: String): SortModeOption =
                entries.firstOrNull { it.id == id } ?: PRICE_DESC
        }
    }

    private val intervalPresets = listOf(60, 120, 300, 600, 900, 1800)

    private var autoScanButton: StyledButton? = null
    private var verboseButton: StyledButton? = null
    private var sortButton: StyledButton? = null
    private var priceModeButton: StyledButton? = null
    private var intervalButton: StyledButton? = null

    private inner class StyledButton(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        message: Component,
        private val onPressAction: () -> Unit
    ) : AbstractWidget(x, y, width, height, message) {
        override fun extractWidgetRenderState(guiGraphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
            val hovered = isHoveredOrFocused
            val topColor = when {
                !active -> 0x7A1B2633.toInt()
                hovered -> 0xCC35608F.toInt()
                else -> 0xB32A4B71.toInt()
            }
            val bottomColor = when {
                !active -> 0x7A141C26.toInt()
                hovered -> 0xCC224164.toInt()
                else -> 0xB31D344E.toInt()
            }
            val borderColor = when {
                !active -> 0x665C7088
                hovered -> 0xFF9AC8F0.toInt()
                else -> 0xCC6E95BB.toInt()
            }

            guiGraphics.fillGradient(x, y, x + width, y + height, topColor, bottomColor)
            guiGraphics.fill(x, y, x + width, y + 1, borderColor)
            guiGraphics.fill(x, y + height - 1, x + width, y + height, borderColor)
            guiGraphics.fill(x, y, x + 1, y + height, borderColor)
            guiGraphics.fill(x + width - 1, y, x + width, y + height, borderColor)

            val textColor = if (active) 0xFFF2F8FF.toInt() else 0xFF9CB0C8.toInt()
            val textY = y + (height - 8) / 2
            guiGraphics.centeredText(font, message.string, x + width / 2, textY, textColor)
        }

        override fun onClick(mouseButtonEvent: MouseButtonEvent, bl: Boolean) {
            if (!active || !visible) return
            onPressAction()
            playDownSound(Minecraft.getInstance().soundManager)
        }

        override fun updateWidgetNarration(narrationElementOutput: NarrationElementOutput) {
            defaultButtonNarrationText(narrationElementOutput)
        }
    }

    override fun init() {
        super.init()

        val buttonWidth = 220
        val buttonHeight = 20
        val x = width / 2 - buttonWidth / 2
        var y = 62
        val step = 26

        autoScanButton = addButton(x, y, buttonWidth, buttonHeight, autoScanLabel()) {
            val config = ChestMasterMod.configManager.config
            val enabled = !config.autoScan
            config.autoScan = enabled
            ChestScanner.setAutoScanEnabled(enabled)
            ChestMasterMod.configManager.save()
            autoScanButton?.setMessage(Component.literal(autoScanLabel()))
        }
        y += step

        verboseButton = addButton(x, y, buttonWidth, buttonHeight, verboseLabel()) {
            val config = ChestMasterMod.configManager.config
            config.verboseLogging = !config.verboseLogging
            ChestMasterMod.configManager.save()
            verboseButton?.setMessage(Component.literal(verboseLabel()))
        }
        y += step

        sortButton = addButton(x, y, buttonWidth, buttonHeight, sortLabel()) {
            val config = ChestMasterMod.configManager.config
            config.sortMode = SortModeOption.fromId(config.sortMode).next().id
            ChestMasterMod.configManager.save()
            sortButton?.setMessage(Component.literal(sortLabel()))
        }
        y += step

        priceModeButton = addButton(x, y, buttonWidth, buttonHeight, priceModeLabel()) {
            ItemValuator.togglePriceMode()
            priceModeButton?.setMessage(Component.literal(priceModeLabel()))
        }
        y += step

        intervalButton = addButton(x, y, buttonWidth, buttonHeight, intervalLabel()) {
            val config = ChestMasterMod.configManager.config
            val currentIndex = intervalPresets.indexOf(config.bazaarUpdateInterval)
            val nextIndex = if (currentIndex < 0) 0 else (currentIndex + 1) % intervalPresets.size
            config.bazaarUpdateInterval = intervalPresets[nextIndex]
            ChestMasterMod.configManager.save()
            intervalButton?.setMessage(Component.literal(intervalLabel()))
        }
        y += step + 8

        addButton(x, y, buttonWidth, buttonHeight, "Done") { onClose() }
    }

    private fun addButton(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        text: String,
        onPress: () -> Unit
    ): StyledButton {
        val button = StyledButton(x, y, width, height, Component.literal(text), onPress)
        addRenderableWidget(button)
        return button
    }

    private fun autoScanLabel(): String =
        "Auto-scan chests: ${if (ChestMasterMod.configManager.config.autoScan) "ON" else "OFF"}"

    private fun verboseLabel(): String =
        "Verbose logging: ${if (ChestMasterMod.configManager.config.verboseLogging) "ON" else "OFF"}"

    private fun sortLabel(): String =
        "Default sort: ${SortModeOption.fromId(ChestMasterMod.configManager.config.sortMode).label}"

    private fun priceModeLabel(): String =
        "Price mode: ${ItemValuator.currentMode.label}"

    private fun intervalLabel(): String {
        val seconds = ChestMasterMod.configManager.config.bazaarUpdateInterval
        val label = if (seconds % 60 == 0) "${seconds / 60}m" else "${seconds}s"
        return "Price refresh: $label"
    }

    override fun extractRenderState(guiGraphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        guiGraphics.fillGradient(0, 0, width, height, 0xB40A0F17.toInt(), 0xE0141D2B.toInt())
        guiGraphics.fillGradient(0, 0, width, 50, 0xA0224A6A.toInt(), 0x20224A6A.toInt())

        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick)

        guiGraphics.centeredText(font, title, width / 2, 20, 0xFFF4FAFF.toInt())
        guiGraphics.centeredText(
            font,
            "Changes are saved immediately",
            width / 2,
            34,
            0xFFB7C7DD.toInt()
        )
    }

    override fun onClose() {
        Minecraft.getInstance().setScreen(parent)
    }
}
