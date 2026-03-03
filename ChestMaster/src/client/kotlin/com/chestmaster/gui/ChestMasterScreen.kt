package com.chestmaster.gui

import com.chestmaster.ChestMasterMod
import com.chestmaster.database.ItemRecord
import com.chestmaster.highlight.ChestLocationHighlighter
import com.chestmaster.util.ItemUtils
import com.chestmaster.valuation.ItemValuator
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class ChestMasterScreen : Screen(Component.literal("ChestMaster Explorer")) {
    private data class Layout(
        val listLeft: Int,
        val listTop: Int,
        val listRight: Int,
        val listBottom: Int,
        val detailLeft: Int,
        val detailTop: Int,
        val detailRight: Int,
        val detailBottom: Int
    ) {
        val listHeight: Int get() = listBottom - listTop
        val detailHeight: Int get() = detailBottom - detailTop
    }

    private enum class SortMode(val label: String) {
        DEFAULT("Default"),
        PRICE_DESC("Price Desc")
    }

    private enum class ItemSourceFilter(val label: String) {
        ALL("All"),
        BAZAAR("Bazaar"),
        AUCTION("Auction"),
        UNKNOWN("Unknown");

        fun next(): ItemSourceFilter {
            val values = entries
            return values[(ordinal + 1) % values.size]
        }
    }

    private data class SourceStats(
        val total: Int = 0,
        val bazaar: Int = 0,
        val auction: Int = 0,
        val npc: Int = 0,
        val unknown: Int = 0
    )

    private data class ScrollbarMetrics(
        val trackLeft: Int,
        val trackRight: Int,
        val trackTop: Int,
        val trackBottom: Int,
        val thumbTop: Int,
        val thumbHeight: Int,
        val maxOffset: Int
    )

    private enum class SkyblockRarity(val aliases: List<String>, val color: Int) {
        VERY_SPECIAL(listOf("VERY_SPECIAL", "VERY SPECIAL"), 0xFFFF5555.toInt()),
        SUPREME(listOf("SUPREME"), 0xFFFF5555.toInt()),
        SPECIAL(listOf("SPECIAL"), 0xFFFF5555.toInt()),
        DIVINE(listOf("DIVINE"), 0xFF55FFFF.toInt()),
        MYTHIC(listOf("MYTHIC"), 0xFFFF55FF.toInt()),
        LEGENDARY(listOf("LEGENDARY"), 0xFFFFAA00.toInt()),
        EPIC(listOf("EPIC"), 0xFFAA00AA.toInt()),
        RARE(listOf("RARE"), 0xFF5555FF.toInt()),
        UNCOMMON(listOf("UNCOMMON"), 0xFF55FF55.toInt()),
        COMMON(listOf("COMMON"), 0xFFF2F7FF.toInt())
    }

    private val defaultItemNameColor = 0xFFF2F7FF.toInt()
    private val defaultSelectedNameColor = 0xFFF3F8FF.toInt()
    private val petTierRegex = Regex("""tier"\s*:\s*"([A-Za-z_ ]+)"""")
    private val loreTextRegex = Regex("""text:"((?:\\.|[^"])*)"""")
    private val whitespaceRegex = Regex("""\s+""")

    private var searchField: EditBox? = null
    private var sourceItems: List<ItemRecord> = emptyList()
    private var items: List<ItemRecord> = emptyList()
    private var scrollOffset = 0
    private val itemHeight = 26
    private var totalValue = 0.0
    private val priceCache = LinkedHashMap<String, Double>()
    private val displayLabelCache = LinkedHashMap<String, String>()
    private val itemNameColorCache = LinkedHashMap<String, Int>()
    private var pricesWereLoaded = false
    private var modeButton: StyledButton? = null
    private var filterButton: StyledButton? = null
    private var sortButton: StyledButton? = null
    private var sortMode = SortMode.PRICE_DESC
    private var sourceFilter = ItemSourceFilter.ALL
    private val sourceCache = LinkedHashMap<String, ItemValuator.PriceSource>()
    private var sourceStats = SourceStats()

    private var selectedItemKey: String? = null
    private var selectedItemName: String? = null
    private var selectedBreakdown: ItemValuator.PriceBreakdown? = null
    private var selectedSource = ItemValuator.PriceSource.UNKNOWN
    private var selectedUnitPrice = 0.0
    private var selectedStackPrice = 0.0
    private var selectedChestCount = 0
    private var highlightedChestCount = 0
    private var selectedItemNameColor = defaultSelectedNameColor

    private var isDraggingListScrollbar = false
    private var scrollbarDragOffsetY = 0

    private inner class StyledButton(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        message: Component,
        private val onPressAction: () -> Unit
    ) : AbstractWidget(x, y, width, height, message) {
        override fun renderWidget(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
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
            val label = ellipsize(message.string, width - 10)
            val textY = y + (height - 8) / 2
            guiGraphics.drawCenteredString(font, label, x + width / 2, textY, textColor)
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

        val centerX = width / 2

        searchField = EditBox(
            font,
            centerX - 130,
            34,
            260,
            20,
            Component.literal("Search...")
        )
        searchField?.setResponder { query -> refreshItems(query) }
        addRenderableWidget(searchField!!)

        val refreshButton = createStyledButton(centerX + 136, 34, 74, 20, "Refresh") {
            priceCache.clear()
            displayLabelCache.clear()
            itemNameColorCache.clear()
            sourceCache.clear()
            pricesWereLoaded = false
            ItemValuator.updateAllPrices()
            refreshItems(searchField?.value ?: "")
        }
        addRenderableWidget(refreshButton)

        modeButton = createStyledButton(centerX - 200, 62, 132, 20, "Mode: ${ItemValuator.currentMode.label}") {
            ItemValuator.currentMode = if (ItemValuator.currentMode == ItemValuator.PriceMode.SELL_OFFER) {
                ItemValuator.PriceMode.BUY_ORDER
            } else {
                ItemValuator.PriceMode.SELL_OFFER
            }
            updateModeButtonLabel()
            priceCache.clear()
            displayLabelCache.clear()
            itemNameColorCache.clear()
            applySortAndRecalculate()
        }
        addRenderableWidget(modeButton!!)

        filterButton = createStyledButton(centerX - 60, 62, 118, 20, "Show: ${sourceFilter.label}") {
            sourceFilter = sourceFilter.next()
            updateFilterButtonLabel()
            applySortAndRecalculate()
        }
        addRenderableWidget(filterButton!!)

        sortButton = createStyledButton(centerX + 66, 62, 118, 20, "Sort: ${sortMode.label}") {
            sortMode = if (sortMode == SortMode.DEFAULT) SortMode.PRICE_DESC else SortMode.DEFAULT
            updateSortButtonLabel()
            applySortAndRecalculate()
        }
        addRenderableWidget(sortButton!!)

        if (!ItemValuator.arePricesLoaded()) {
            ItemValuator.updateAllPrices()
        }

        refreshItems("")
        pricesWereLoaded = ItemValuator.arePricesLoaded()
    }

    override fun removed() {
        super.removed()
        // Keep markers active after closing GUI. They are cleared manually via command.
    }

    private fun updateModeButtonLabel() {
        modeButton?.setMessage(Component.literal("Mode: ${ItemValuator.currentMode.label}"))
    }

    private fun updateFilterButtonLabel() {
        filterButton?.setMessage(Component.literal("Show: ${sourceFilter.label}"))
    }

    private fun updateSortButtonLabel() {
        sortButton?.setMessage(Component.literal("Sort: ${sortMode.label}"))
    }

    private fun createStyledButton(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        text: String,
        onPress: () -> Unit
    ): StyledButton {
        return StyledButton(x, y, width, height, Component.literal(text), onPress)
    }

    private fun refreshItems(query: String) {
        try {
            sourceItems = ChestMasterMod.db.searchItems(query)
            displayLabelCache.clear()
            itemNameColorCache.clear()
            scrollOffset = 0
            applySortAndRecalculate()
        } catch (e: Exception) {
            ChestMasterMod.LOGGER.error("Failed to refresh items in GUI", e)
        }
    }

    private fun calculateTotalValue() {
        totalValue = items.sumOf { record -> getCachedPrice(record) * record.count.toDouble() }
    }

    private fun applySortAndRecalculate() {
        val filtered = sourceItems.filter { matchesSourceFilter(it) }

        items = when (sortMode) {
            SortMode.DEFAULT -> filtered
            SortMode.PRICE_DESC -> filtered.sortedWith(
                compareByDescending<ItemRecord> { getCachedPrice(it) * it.count.toDouble() }
                    .thenBy { it.displayName.lowercase() }
            )
        }
        sourceStats = buildSourceStats()

        val maxOffset = maxScrollOffset(computeLayout())
        scrollOffset = scrollOffset.coerceIn(0, maxOffset)
        calculateTotalValue()
        refreshSelectedBreakdown()
    }

    private fun refreshSelectedBreakdown() {
        val selected = items.firstOrNull { recordKey(it) == selectedItemKey }
        if (selected == null) {
            selectedItemKey = null
            selectedItemName = null
            selectedBreakdown = null
            selectedSource = ItemValuator.PriceSource.UNKNOWN
            selectedUnitPrice = 0.0
            selectedStackPrice = 0.0
            selectedChestCount = 0
            highlightedChestCount = ChestLocationHighlighter.getActiveMarkerCount()
            selectedItemNameColor = defaultSelectedNameColor
            return
        }

        selectedItemName = getDisplayLabel(selected)
        selectedItemNameColor = getItemNameColor(selected)
        selectedSource = getItemSource(selected)
        selectedUnitPrice = getCachedPrice(selected)
        selectedStackPrice = selectedUnitPrice * selected.count.toDouble()
        selectedBreakdown = ItemValuator.getPriceBreakdownFromNbt(selected.itemId, selected.itemNbt)
        selectedChestCount = ChestMasterMod.db.findChestLocationsForItem(
            selected.itemId,
            selected.baseItemId,
            selected.itemNbt,
            selected.displayName
        ).size
        highlightedChestCount = ChestLocationHighlighter.getActiveMarkerCount()
    }

    private fun getCachedPrice(record: ItemRecord): Double {
        val cacheKey = "${record.itemId}@@${record.baseItemId}@@${record.itemNbt}"
        return priceCache.getOrPut(cacheKey) {
            try {
                val fromBreakdown = ItemValuator.getPriceFromNbt(record.itemId, record.itemNbt)
                if (fromBreakdown >= 0.0) {
                    return@getOrPut fromBreakdown
                }

                val baseId = record.baseItemId.ifBlank { record.itemId }
                val stack = ItemUtils.deserializeItemStack(baseId, record.itemNbt)
                val fromStack = ItemValuator.getPrice(stack)
                if (fromStack >= 0.0) {
                    return@getOrPut fromStack
                }

                val extractedSkyblockId = ItemUtils.extractSkyblockIdFromNbtString(record.itemNbt)
                if (!extractedSkyblockId.isNullOrBlank()) {
                    val fromExtracted = ItemValuator.getPriceBySkyblockId(extractedSkyblockId)
                    if (fromExtracted >= 0.0) {
                        return@getOrPut fromExtracted
                    }
                }

                val byItemId = ItemValuator.getPriceBySkyblockId(record.itemId)
                if (byItemId >= 0.0) byItemId else 0.0
            } catch (_: Exception) {
                0.0
            }
        }
    }

    private fun getDisplayLabel(record: ItemRecord): String {
        return displayLabelCache.getOrPut(recordKey(record)) {
            val baseName = record.displayName.ifBlank { record.itemId.ifBlank { "Unknown Item" } }

            val normalizedItemId = ItemUtils.normalizeSkyblockId(record.itemId)
            val normalizedBaseItemId = record.baseItemId.lowercase()
            val isGenericBook = normalizedItemId == "ENCHANTED_BOOK" ||
                normalizedItemId == "BOOK" ||
                normalizedBaseItemId == "minecraft:enchanted_book" ||
                normalizedBaseItemId == "minecraft:book" ||
                baseName.equals("Enchanted Book", ignoreCase = true) ||
                baseName.equals("Book", ignoreCase = true)

            if (!isGenericBook) {
                return@getOrPut baseName
            }

            val breakdown = ItemValuator.getPriceBreakdownFromNbt(record.itemId, record.itemNbt)
            val bestComponent = breakdown.upgradeComponents.maxByOrNull { it.value }
            val upgradeName = bestComponent?.label?.takeIf { it.isNotBlank() } ?: return@getOrPut baseName
            "Enchanted Book ($upgradeName)"
        }
    }

    private fun getItemNameColor(record: ItemRecord): Int {
        return itemNameColorCache.getOrPut(recordKey(record)) {
            val rarity = detectRarity(record)
            rarity?.color ?: defaultItemNameColor
        }
    }

    private fun detectRarity(record: ItemRecord): SkyblockRarity? {
        detectRarityFromPetTier(record.itemNbt)?.let { return it }
        detectRarityFromLore(record.itemNbt)?.let { return it }
        if (record.itemNbt.isBlank() || record.itemNbt == "{}") {
            return detectRarityFromDisplayName(record.displayName)
        }
        return null
    }

    private fun detectRarityFromPetTier(itemNbt: String): SkyblockRarity? {
        val tierRaw = petTierRegex.find(itemNbt)?.groupValues?.getOrNull(1) ?: return null
        val normalizedTier = normalizeRarityToken(tierRaw)
        return findRarityByExactAlias(normalizedTier)
    }

    private fun detectRarityFromLore(itemNbt: String): SkyblockRarity? {
        val lorePayload = extractLorePayload(itemNbt) ?: return null
        var detected: SkyblockRarity? = null

        for (match in loreTextRegex.findAll(lorePayload)) {
            val line = normalizeLoreLine(match.groupValues[1])
            if (line.isBlank() || !isAllUppercaseWords(line)) continue

            val (rarity, alias) = matchRarityPrefix(line) ?: continue
            val suffix = line.removePrefix(alias).trim()
            if (!isLikelyRaritySuffix(suffix)) continue
            detected = rarity
        }

        return detected
    }

    private fun detectRarityFromDisplayName(displayName: String): SkyblockRarity? {
        val normalized = normalizeLoreLine(displayName)
        if (normalized.isBlank()) return null
        val (rarity, _) = matchRarityPrefix(normalized) ?: return null
        return rarity
    }

    private fun extractLorePayload(itemNbt: String): String? {
        val marker = "\"minecraft:lore\":["
        val markerIndex = itemNbt.indexOf(marker)
        if (markerIndex < 0) return null

        val arrayStart = markerIndex + marker.length - 1
        val contentStart = arrayStart + 1
        var depth = 0
        var inString = false
        var escaped = false

        for (i in arrayStart until itemNbt.length) {
            val c = itemNbt[i]
            if (escaped) {
                escaped = false
                continue
            }

            if (c == '\\' && inString) {
                escaped = true
                continue
            }

            if (c == '"') {
                inString = !inString
                continue
            }

            if (inString) continue

            if (c == '[') {
                depth += 1
                continue
            }

            if (c == ']') {
                depth -= 1
                if (depth == 0) {
                    return itemNbt.substring(contentStart, i)
                }
            }
        }

        return null
    }

    private fun normalizeLoreLine(raw: String): String {
        val unescaped = raw.replace("\\\"", "\"").replace("\\\\", "\\")
        val noFormatting = unescaped.replace(Regex("§."), "")
        return whitespaceRegex.replace(noFormatting, " ").trim().uppercase(Locale.ROOT)
    }

    private fun normalizeRarityToken(raw: String): String {
        val normalized = raw.replace('_', ' ')
        return whitespaceRegex.replace(normalized, " ").trim().uppercase(Locale.ROOT)
    }

    private fun findRarityByExactAlias(token: String): SkyblockRarity? {
        for (rarity in SkyblockRarity.entries) {
            if (rarity.aliases.any { it == token }) return rarity
        }
        return null
    }

    private fun matchRarityPrefix(line: String): Pair<SkyblockRarity, String>? {
        for (rarity in SkyblockRarity.entries) {
            val aliases = rarity.aliases.sortedByDescending { it.length }
            for (alias in aliases) {
                if (line == alias || line.startsWith("$alias ")) {
                    return rarity to alias
                }
            }
        }
        return null
    }

    private fun isAllUppercaseWords(line: String): Boolean {
        val lettersOnly = line.filter { it.isLetter() }
        if (lettersOnly.isBlank()) return false
        return lettersOnly == lettersOnly.uppercase(Locale.ROOT)
    }

    private fun isLikelyRaritySuffix(suffix: String): Boolean {
        if (suffix.isEmpty()) return true
        if (suffix.length > 48) return false

        return suffix.all {
            it.isUpperCase() ||
                it.isDigit() ||
                it == ' ' ||
                it == '-' ||
                it == '+' ||
                it == '\'' ||
                it == '&' ||
                it == '/' ||
                it == '(' ||
                it == ')' ||
                it == ':'
        }
    }

    private fun getItemSource(record: ItemRecord): ItemValuator.PriceSource {
        return sourceCache.getOrPut(recordKey(record)) {
            val candidates = linkedSetOf<String>()
            // Prefer explicit SkyBlock ids from NBT/itemId. Do not use raw minecraft base ids here,
            // otherwise vanilla ids (e.g. minecraft:stone_sword) can produce false auction matches.
            ItemUtils.extractSkyblockIdFromNbtString(record.itemNbt)
                ?.takeIf { !it.contains(':') }
                ?.let { candidates += it }
            ItemUtils.normalizeSkyblockId(record.itemId)
                ?.takeIf { !it.contains(':') }
                ?.let { candidates += it }

            for (candidate in candidates) {
                val source = ItemValuator.getPriceSourceForId(candidate)
                if (source != ItemValuator.PriceSource.UNKNOWN) {
                    return@getOrPut source
                }
            }

            // Fallback for items whose market value comes from parsed NBT metadata rather than direct id lookup.
            val breakdown = ItemValuator.getPriceBreakdownFromNbt(record.itemId, record.itemNbt)
            val breakdownSource = ItemValuator.getPriceSourceForId(breakdown.itemId)
            if (breakdownSource != ItemValuator.PriceSource.UNKNOWN) {
                return@getOrPut breakdownSource
            }

            // Enchanted books are priced via enchant components (Bazaar-based).
            if (isGenericBookRecord(record) || breakdown.itemId == "ENCHANTED_BOOK") {
                return@getOrPut ItemValuator.PriceSource.BAZAAR
            }

            // Pets are Auction items (pet data usually sits in ExtraAttributes.petInfo).
            if (isPetRecord(record, breakdown.itemId)) {
                return@getOrPut ItemValuator.PriceSource.AUCTION
            }

            ItemValuator.PriceSource.UNKNOWN
        }
    }

    private fun isGenericBookRecord(record: ItemRecord): Boolean {
        val normalizedItemId = ItemUtils.normalizeSkyblockId(record.itemId)
        val normalizedBaseItemId = record.baseItemId.lowercase()
        val name = record.displayName
        return normalizedItemId == "ENCHANTED_BOOK" ||
            normalizedItemId == "BOOK" ||
            normalizedBaseItemId == "minecraft:enchanted_book" ||
            normalizedBaseItemId == "minecraft:book" ||
            name.equals("Enchanted Book", ignoreCase = true) ||
            name.equals("Book", ignoreCase = true)
    }

    private fun isPetRecord(record: ItemRecord, breakdownItemId: String): Boolean {
        if (ItemUtils.normalizeSkyblockId(record.itemId) == "PET") return true
        if (breakdownItemId == "PET") return true

        val extra = ItemUtils.extractExtraAttributesFromNbtString(record.itemNbt) ?: return false
        val petInfo = extra.getString("petInfo").orElse(null)
        return !petInfo.isNullOrBlank()
    }

    private fun matchesSourceFilter(record: ItemRecord): Boolean {
        val source = getItemSource(record)
        return when (sourceFilter) {
            ItemSourceFilter.ALL -> true
            ItemSourceFilter.BAZAAR -> source == ItemValuator.PriceSource.BAZAAR
            ItemSourceFilter.AUCTION -> source == ItemValuator.PriceSource.AUCTION
            ItemSourceFilter.UNKNOWN -> source == ItemValuator.PriceSource.UNKNOWN
        }
    }

    private fun buildSourceStats(): SourceStats {
        var bazaar = 0
        var auction = 0
        var npc = 0
        var unknown = 0

        for (record in sourceItems) {
            when (getItemSource(record)) {
                ItemValuator.PriceSource.BAZAAR -> bazaar += 1
                ItemValuator.PriceSource.AUCTION -> auction += 1
                ItemValuator.PriceSource.NPC -> npc += 1
                ItemValuator.PriceSource.UNKNOWN -> unknown += 1
            }
        }

        return SourceStats(
            total = sourceItems.size,
            bazaar = bazaar,
            auction = auction,
            npc = npc,
            unknown = unknown
        )
    }

    private fun onItemSelected(record: ItemRecord) {
        selectedItemKey = recordKey(record)
        selectedItemName = getDisplayLabel(record)
        selectedItemNameColor = getItemNameColor(record)
        selectedSource = getItemSource(record)
        selectedUnitPrice = getCachedPrice(record)
        selectedStackPrice = selectedUnitPrice * record.count.toDouble()
        selectedBreakdown = ItemValuator.getPriceBreakdownFromNbt(record.itemId, record.itemNbt)

        val chestLocations = ChestMasterMod.db.findChestLocationsForItem(
            record.itemId,
            record.baseItemId,
            record.itemNbt,
            record.displayName
        )
        selectedChestCount = chestLocations.size

        val positions = chestLocations.map { location ->
            BlockPos(location.x, location.y, location.z)
        }
        highlightedChestCount = ChestLocationHighlighter.highlight(selectedItemName ?: record.displayName, positions)
    }

    private fun recordKey(record: ItemRecord): String {
        return "${record.itemId}@@${record.baseItemId}@@${record.itemNbt}"
    }

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val loadedNow = ItemValuator.arePricesLoaded()
        if (loadedNow != pricesWereLoaded) {
            pricesWereLoaded = loadedNow
            if (loadedNow) {
                priceCache.clear()
                sourceCache.clear()
                refreshItems(searchField?.value ?: "")
            }
        }

        guiGraphics.fillGradient(0, 0, width, height, 0xB40A0F17.toInt(), 0xE0141D2B.toInt())
        guiGraphics.fillGradient(0, 0, width, 84, 0xA0224A6A.toInt(), 0x20224A6A.toInt())
        guiGraphics.fillGradient(0, height - 96, width, height, 0x00000000, 0x7A2A1427.toInt())

        super.render(guiGraphics, mouseX, mouseY, partialTick)

        val layout = computeLayout()
        if (isDraggingListScrollbar) {
            applyScrollbarDrag(mouseY, layout)
        }
        val centerX = width / 2

        guiGraphics.drawCenteredString(font, title, centerX, 14, 0xFFF4FAFF.toInt())
        guiGraphics.drawCenteredString(font, "Fast search, valuation details, chest markers", centerX, 24, 0xFFB7C7DD.toInt())

        val totalText = if (ItemValuator.arePricesLoaded()) {
            "${ItemValuator.formatPrice(totalValue)} coins"
        } else {
            "Loading..."
        }
        guiGraphics.drawString(font, "Total Value: $totalText", layout.listLeft, 82, 0xFFEAF2FF.toInt(), false)
        val topStats = "Shown ${items.size}/${sourceStats.total} | Bazaar ${sourceStats.bazaar} | Auction ${sourceStats.auction} | Filter ${sourceFilter.label}"
        guiGraphics.drawString(font, ellipsize(topStats, max(80, layout.detailRight - layout.detailLeft - 8)), layout.detailLeft, 82, 0xFF9FB5D3.toInt(), false)

        drawPanel(
            guiGraphics,
            layout.listLeft,
            layout.listTop,
            layout.listRight,
            layout.listBottom,
            0x8E1A232E.toInt(),
            0x9A111A24.toInt(),
            0xAA6A8BB0.toInt()
        )

        val rowLeft = layout.listLeft + 4
        val rowRight = layout.listRight - 7
        val rowTopStart = layout.listTop + 6
        val listVisibleRows = visibleRows(layout)

        if (items.isEmpty()) {
            guiGraphics.drawCenteredString(
                font,
                "No items found.",
                (layout.listLeft + layout.listRight) / 2,
                layout.listTop + 22,
                0xFFB8C7DD.toInt()
            )
        } else {
            val rowsToRender = min(listVisibleRows, max(0, items.size - scrollOffset))
            for (row in 0 until rowsToRender) {
                val index = scrollOffset + row
                if (index !in items.indices) continue

                val record = items[index]
                val rowTop = rowTopStart + row * itemHeight
                val rowBottom = rowTop + itemHeight - 2

                val selected = recordKey(record) == selectedItemKey
                val hovered = isInside(mouseX, mouseY, rowLeft, rowTop, rowRight, rowBottom)

                val rowTopColor = when {
                    selected -> 0xB9305D92.toInt()
                    hovered -> 0xAA2A3E56.toInt()
                    else -> 0x8D1B2531.toInt()
                }
                val rowBottomColor = when {
                    selected -> 0xB61A3456.toInt()
                    hovered -> 0xAA1D2D42.toInt()
                    else -> 0x8D131C26.toInt()
                }
                drawPanel(guiGraphics, rowLeft, rowTop, rowRight, rowBottom, rowTopColor, rowBottomColor, 0x66597897)

                val renderItemId = record.baseItemId.ifBlank { record.itemId }
                val stack = ItemUtils.deserializeItemStack(renderItemId, record.itemNbt)

                guiGraphics.renderItem(stack, rowLeft + 4, rowTop + 4)
                guiGraphics.renderItemDecorations(font, stack, rowLeft + 4, rowTop + 4)

                val nameX = rowLeft + 26
                val totalLabel = if (ItemValuator.arePricesLoaded()) {
                    ItemValuator.formatPrice(getCachedPrice(record) * record.count.toDouble())
                } else {
                    "Loading..."
                }
                val totalWidth = font.width(totalLabel)
                val totalX = rowRight - totalWidth - 6
                val availableNameWidth = max(40, totalX - nameX - 8)
                val itemNameColor = getItemNameColor(record)

                val itemLabel = ellipsize("${getDisplayLabel(record)} x${record.count}", availableNameWidth)
                guiGraphics.drawString(font, itemLabel, nameX, rowTop + 5, itemNameColor, false)
                guiGraphics.drawString(font, totalLabel, totalX, rowTop + 5, 0xFFE5EEFF.toInt(), false)

                if (ItemValuator.arePricesLoaded()) {
                    val unitText = "unit ${ItemValuator.formatPrice(getCachedPrice(record))}"
                    guiGraphics.drawString(font, unitText, nameX, rowTop + 15, 0xFF99AECB.toInt(), false)
                }
            }

            val scrollbar = computeScrollbarMetrics(layout)
            if (scrollbar != null) {
                guiGraphics.fill(
                    scrollbar.trackLeft,
                    scrollbar.trackTop,
                    scrollbar.trackRight,
                    scrollbar.trackBottom,
                    0x88445A74.toInt()
                )
                val thumbTopColor = if (isDraggingListScrollbar) 0xFFA6D3FF.toInt() else 0xFF8FB7DF.toInt()
                val thumbBottomColor = if (isDraggingListScrollbar) 0xFF6D9CCD.toInt() else 0xFF5B7DA3.toInt()
                guiGraphics.fillGradient(
                    scrollbar.trackLeft,
                    scrollbar.thumbTop,
                    scrollbar.trackRight,
                    scrollbar.thumbTop + scrollbar.thumbHeight,
                    thumbTopColor,
                    thumbBottomColor
                )
            }
        }

        renderDetailPanel(guiGraphics, layout)
    }

    private fun renderDetailPanel(guiGraphics: GuiGraphics, layout: Layout) {
        drawPanel(
            guiGraphics,
            layout.detailLeft,
            layout.detailTop,
            layout.detailRight,
            layout.detailBottom,
            0x8F1F2936.toInt(),
            0x99141D29.toInt(),
            0xAA6B8FB5.toInt()
        )

        val textLeft = layout.detailLeft + 8
        val textWidth = max(70, layout.detailRight - layout.detailLeft - 16)
        var y = layout.detailTop + 8
        val lineHeight = 10

        guiGraphics.drawString(font, "Market Summary", textLeft, y, 0xFFEAF3FF.toInt(), false)
        y += 12

        val summaryLines = listOf(
            "Filter: ${sourceFilter.label}",
            "Showing: ${items.size}/${sourceStats.total}",
            "Bazaar: ${sourceStats.bazaar} | Auction: ${sourceStats.auction}",
            "NPC (incl. fallback): ${sourceStats.npc} | Unknown: ${sourceStats.unknown}",
            "Markers active: ${ChestLocationHighlighter.getActiveMarkerCount()}"
        )
        for (line in summaryLines) {
            guiGraphics.drawString(font, ellipsize(line, textWidth), textLeft, y, 0xFFADC4DF.toInt(), false)
            y += lineHeight
        }

        y += 2
        guiGraphics.fill(textLeft, y, layout.detailRight - 8, y + 1, 0x77648CB6)
        y += 6

        guiGraphics.drawString(font, "Selected Item", textLeft, y, 0xFFEAF3FF.toInt(), false)
        y += 12

        val breakdown = selectedBreakdown
        if (breakdown == null || selectedItemName.isNullOrBlank()) {
            guiGraphics.drawString(font, "Click an item to inspect it.", textLeft, y, 0xFFC2D2E6.toInt(), false)
            y += lineHeight
            guiGraphics.drawString(font, "Click also highlights chest locations.", textLeft, y, 0xFF93A7C2.toInt(), false)
            y += lineHeight
            guiGraphics.drawString(font, "Use Show button for Bazaar/Auction.", textLeft, y, 0xFF85D8FF.toInt(), false)
            y += lineHeight
            guiGraphics.drawString(font, "Clear markers: /cm m clear", textLeft, y, 0xFF8A9FB9.toInt(), false)
            return
        }

        val lines = ArrayList<Pair<String, Int>>()
        lines += ellipsize(selectedItemName ?: "", textWidth) to selectedItemNameColor
        lines += "Source: ${selectedSource.label}" to 0xFF9FD5FF.toInt()
        lines += "Unit: ${ItemValuator.formatPrice(selectedUnitPrice)}" to 0xFFD5E6FF.toInt()
        lines += "Stack: ${ItemValuator.formatPrice(selectedStackPrice)}" to 0xFFD5E6FF.toInt()
        lines += "ID: ${breakdown.itemId}" to 0xFFABC1DD.toInt()
        lines += "Base: ${ItemValuator.formatPrice(breakdown.basePrice)}" to 0xFFE2EBF8.toInt()

        if (breakdown.stars > 0 && breakdown.starBonus > 0.0) {
            lines += "Stars ${breakdown.stars}: +${ItemValuator.formatPrice(breakdown.starBonus)}" to 0xFFFFDA94.toInt()
        }
        if (breakdown.recombed && breakdown.recombBonus > 0.0) {
            lines += "Recomb: +${ItemValuator.formatPrice(breakdown.recombBonus)}" to 0xFFC28FFF.toInt()
        }

        if (breakdown.upgradeComponents.isNotEmpty()) {
            lines += "Upgrades:" to 0xFF8ED4FF.toInt()
            for (component in breakdown.upgradeComponents.sortedByDescending { it.value }.take(8)) {
                lines += " + ${component.label}: ${ItemValuator.formatPrice(component.value)}" to 0xFFBFD9F4.toInt()
            }
        }

        if (breakdown.upgradeBonus > 0.0) {
            lines += "Upgrade Total: ${ItemValuator.formatPrice(breakdown.upgradeBonus)}" to 0xFF8ED4FF.toInt()
        }

        lines += "Estimated Total: ${ItemValuator.formatPrice(breakdown.totalPrice)}" to 0xFFFFE08D.toInt()
        lines += "Saved in chests: $selectedChestCount" to 0xFFA5BDD7.toInt()
        lines += "Highlighted markers: $highlightedChestCount" to 0xFFA5BDD7.toInt()
        lines += "Clear markers: /cm m clear" to 0xFF7FCDF3.toInt()

        val maxLines = max(1, (layout.detailHeight - (y - layout.detailTop) - 8) / lineHeight)
        for ((lineText, color) in lines.take(maxLines)) {
            guiGraphics.drawString(font, ellipsize(lineText, textWidth), textLeft, y, color, false)
            y += lineHeight
        }
    }

    private fun drawPanel(
        guiGraphics: GuiGraphics,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        topColor: Int,
        bottomColor: Int,
        borderColor: Int
    ) {
        guiGraphics.fillGradient(x1, y1, x2, y2, topColor, bottomColor)
        guiGraphics.fill(x1, y1, x2, y1 + 1, borderColor)
        guiGraphics.fill(x1, y2 - 1, x2, y2, borderColor)
        guiGraphics.fill(x1, y1, x1 + 1, y2, borderColor)
        guiGraphics.fill(x2 - 1, y1, x2, y2, borderColor)
    }

    private fun isInside(mouseX: Int, mouseY: Int, left: Int, top: Int, right: Int, bottom: Int): Boolean {
        return mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom
    }

    private fun computeLayout(): Layout {
        val margin = 12
        val gap = 8
        val top = 94
        val bottom = height - 12

        val availableWidth = (width - margin * 2 - gap).coerceAtLeast(420)
        val minListWidth = 230
        val minDetailWidth = 180

        var listWidth = (availableWidth * 0.62).toInt().coerceAtLeast(minListWidth)
        var detailWidth = availableWidth - listWidth
        if (detailWidth < minDetailWidth) {
            detailWidth = minDetailWidth
            listWidth = (availableWidth - detailWidth).coerceAtLeast(minListWidth)
        }

        val listLeft = margin
        val listRight = listLeft + listWidth
        val detailLeft = listRight + gap
        val detailRight = detailLeft + detailWidth

        return Layout(
            listLeft = listLeft,
            listTop = top,
            listRight = listRight,
            listBottom = bottom,
            detailLeft = detailLeft,
            detailTop = top,
            detailRight = detailRight,
            detailBottom = bottom
        )
    }

    private fun visibleRows(layout: Layout): Int {
        return max(1, (layout.listHeight - 12) / itemHeight)
    }

    private fun maxScrollOffset(layout: Layout): Int {
        return (items.size - visibleRows(layout)).coerceAtLeast(0)
    }

    private fun computeScrollbarMetrics(layout: Layout): ScrollbarMetrics? {
        val maxOffset = maxScrollOffset(layout)
        if (maxOffset <= 0) return null

        val trackLeft = layout.listRight - 5
        val trackRight = layout.listRight - 3
        val trackTop = layout.listTop + 6
        val trackBottom = layout.listBottom - 6
        val trackHeight = max(1, trackBottom - trackTop)
        val visible = visibleRows(layout)
        val thumbHeight = max(14, (visible.toDouble() / items.size.toDouble() * trackHeight).toInt())
        val travel = max(1, trackHeight - thumbHeight)
        val thumbTop = trackTop + ((scrollOffset.toDouble() / maxOffset.toDouble()) * travel).toInt()

        return ScrollbarMetrics(
            trackLeft = trackLeft,
            trackRight = trackRight,
            trackTop = trackTop,
            trackBottom = trackBottom,
            thumbTop = thumbTop,
            thumbHeight = thumbHeight,
            maxOffset = maxOffset
        )
    }

    private fun applyScrollbarDrag(mouseY: Int, layout: Layout) {
        val metrics = computeScrollbarMetrics(layout) ?: run {
            isDraggingListScrollbar = false
            return
        }

        val desiredThumbTop = mouseY - scrollbarDragOffsetY
        scrollOffset = scrollOffsetFromThumbTop(desiredThumbTop, metrics)
    }

    private fun scrollOffsetFromThumbTop(thumbTop: Int, metrics: ScrollbarMetrics): Int {
        val maxThumbTop = metrics.trackBottom - metrics.thumbHeight
        val clampedThumbTop = thumbTop.coerceIn(metrics.trackTop, maxThumbTop)
        val travel = max(1, metrics.trackBottom - metrics.trackTop - metrics.thumbHeight)
        val ratio = (clampedThumbTop - metrics.trackTop).toDouble() / travel.toDouble()
        return (ratio * metrics.maxOffset.toDouble()).toInt().coerceIn(0, metrics.maxOffset)
    }

    private fun ellipsize(text: String, maxWidth: Int): String {
        if (font.width(text) <= maxWidth) return text
        var result = text
        while (result.isNotEmpty() && font.width("$result...") > maxWidth) {
            result = result.dropLast(1)
        }
        return if (result.isEmpty()) "..." else "$result..."
    }

    override fun mouseClicked(mouseButtonEvent: MouseButtonEvent, bl: Boolean): Boolean {
        if (super.mouseClicked(mouseButtonEvent, bl)) return true
        if (mouseButtonEvent.button() != 0) return false

        val mouseX = mouseButtonEvent.x()
        val mouseY = mouseButtonEvent.y()
        val layout = computeLayout()

        val scrollbar = computeScrollbarMetrics(layout)
        if (scrollbar != null &&
            mouseX >= scrollbar.trackLeft &&
            mouseX <= scrollbar.trackRight &&
            mouseY >= scrollbar.trackTop &&
            mouseY <= scrollbar.trackBottom
        ) {
            val thumbBottom = scrollbar.thumbTop + scrollbar.thumbHeight
            val mouseYInt = mouseY.toInt()
            if (mouseYInt in scrollbar.thumbTop..thumbBottom) {
                isDraggingListScrollbar = true
                scrollbarDragOffsetY = mouseYInt - scrollbar.thumbTop
            } else {
                val centeredThumbTop = mouseYInt - scrollbar.thumbHeight / 2
                scrollOffset = scrollOffsetFromThumbTop(centeredThumbTop, scrollbar)
                isDraggingListScrollbar = true
                scrollbarDragOffsetY = scrollbar.thumbHeight / 2
            }
            return true
        }

        val rowLeft = layout.listLeft + 4
        val rowRight = layout.listRight - 7
        val rowTopStart = layout.listTop + 6

        if (mouseX < rowLeft || mouseX > rowRight || mouseY < rowTopStart || mouseY > layout.listBottom - 6) {
            return false
        }

        val rowIndex = ((mouseY - rowTopStart) / itemHeight).toInt()
        if (rowIndex < 0) return false

        val itemIndex = rowIndex + scrollOffset
        if (itemIndex !in items.indices) return false

        val rowTop = rowTopStart + rowIndex * itemHeight
        if (mouseY > rowTop + itemHeight - 2) return false

        onItemSelected(items[itemIndex])
        return true
    }

    override fun mouseDragged(mouseButtonEvent: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        if (isDraggingListScrollbar && mouseButtonEvent.button() == 0) {
            applyScrollbarDrag(mouseButtonEvent.y().toInt(), computeLayout())
            return true
        }
        return super.mouseDragged(mouseButtonEvent, dragX, dragY)
    }

    override fun mouseReleased(mouseButtonEvent: MouseButtonEvent): Boolean {
        if (mouseButtonEvent.button() == 0 && isDraggingListScrollbar) {
            isDraggingListScrollbar = false
            return true
        }
        return super.mouseReleased(mouseButtonEvent)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val layout = computeLayout()
        val insideList = mouseX >= layout.listLeft &&
            mouseX <= layout.listRight &&
            mouseY >= layout.listTop &&
            mouseY <= layout.listBottom

        if (insideList) {
            val maxOffset = maxScrollOffset(layout)
            if (maxOffset > 0) {
                if (verticalAmount > 0.0 && scrollOffset > 0) {
                    scrollOffset -= 1
                    return true
                }
                if (verticalAmount < 0.0 && scrollOffset < maxOffset) {
                    scrollOffset += 1
                    return true
                }
            }
        }

        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }

    override fun keyPressed(keyEvent: KeyEvent): Boolean {
        if (searchField?.keyPressed(keyEvent) == true) return true
        if (keyEvent.key() == 256) {
            onClose()
            return true
        }
        return super.keyPressed(keyEvent)
    }

    override fun charTyped(characterEvent: CharacterEvent): Boolean {
        if (searchField?.charTyped(characterEvent) == true) return true
        return super.charTyped(characterEvent)
    }
}
