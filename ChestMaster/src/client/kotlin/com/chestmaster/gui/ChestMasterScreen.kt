package com.chestmaster.gui

import com.chestmaster.ChestMasterMod
import com.chestmaster.database.ItemRecord
import com.chestmaster.highlight.ChestLocationHighlighter
import com.chestmaster.highlight.SearchHighlight
import com.chestmaster.scanner.ChestScanner
import com.chestmaster.util.ItemUtils
import com.chestmaster.util.WorldUtils
import com.chestmaster.valuation.ItemValuator
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class ChestMasterScreen(private val initialQuery: String = "") : Screen(Component.literal("ChestMaster Explorer")) {
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
        PRICE_DESC("Price ↓"),
        NAME_ASC("Name A-Z"),
        COUNT_DESC("Count ↓");

        fun next(): SortMode {
            val values = entries
            return values[(ordinal + 1) % values.size]
        }
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
        COMMON(listOf("COMMON"), 0xFFF2F7FF.toInt());

        // Pre-sorted descending by length so matchRarityPrefix never re-sorts on every call.
        val sortedAliases: List<String> = aliases.sortedByDescending { it.length }
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
    // One SkyBlockAPI valuation per record (price, source, breakdown). Unpriced entries are
    // retried periodically, because SkyBlockAPI may still be downloading its market data.
    private val valuationCache = HashMap<String, ItemValuator.Valuation>()
    private var lastValuationRetryMs = 0L
    private val displayLabelCache = LinkedHashMap<String, String>()
    private val itemNameColorCache = LinkedHashMap<String, Int>()
    private var lastMarketSignature = Long.MIN_VALUE
    private var selectedRecord: ItemRecord? = null
    private var selectedStack: ItemStack = ItemStack.EMPTY
    private var detailScroll = 0
    private var detailContentHeight = 0
    private var openMarketButton: StyledButton? = null
    private var modeButton: StyledButton? = null
    private var filterButton: StyledButton? = null
    private var sortButton: StyledButton? = null
    private var autoScanButton: StyledButton? = null
    private var sortMode = SortMode.PRICE_DESC
    private var sourceFilter = ItemSourceFilter.ALL
    private var sourceStats = SourceStats()

    private var selectedItemKey: String? = null
    private var selectedItemName: String? = null
    private var selectedValuation: ItemValuator.Valuation? = null
    private var selectedSource = ItemValuator.PriceSource.UNKNOWN
    private var selectedUnitPrice = 0.0
    private var selectedStackPrice = 0.0
    private var selectedChestCount = 0
    private var highlightedChestCount = 0
    private var selectedItemNameColor = defaultSelectedNameColor
    private var selectedChestLocations: List<com.chestmaster.database.ChestLocation> = emptyList()

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
            val label = ellipsize(message.string, width - 10)
            val textY = y + (height - 8) / 2
            guiGraphics.centeredText(font, label, x + width / 2, textY, textColor)
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

        // Restore persisted sort mode from config.
        sortMode = try {
            SortMode.valueOf(ChestMasterMod.configManager.config.sortMode)
        } catch (_: Exception) {
            SortMode.PRICE_DESC
        }

        val centerX = width / 2

        // init() re-runs on window resize; keep the current search query alive.
        val previousQuery = searchField?.value ?: initialQuery

        searchField = EditBox(
            font,
            centerX - 130,
            34,
            260,
            20,
            Component.literal("Search...")
        )
        searchField?.value = previousQuery
        searchField?.setResponder { query -> refreshItems(query) }
        addRenderableWidget(searchField!!)

        val refreshButton = createStyledButton(centerX + 136, 34, 74, 20, "Refresh") {
            valuationCache.clear()
            displayLabelCache.clear()
            itemNameColorCache.clear()
            refreshItems(searchField?.value ?: "")
        }
        addRenderableWidget(refreshButton)

        modeButton = createStyledButton(centerX - 200, 62, 118, 20, "Mode: ${ItemValuator.currentMode.label}") {
            // togglePriceMode also persists the choice to the config.
            ItemValuator.togglePriceMode()
            updateModeButtonLabel()
            valuationCache.clear()
            displayLabelCache.clear()
            itemNameColorCache.clear()
            applySortAndRecalculate()
        }
        addRenderableWidget(modeButton!!)

        filterButton = createStyledButton(centerX - 74, 62, 100, 20, "Show: ${sourceFilter.label}") {
            sourceFilter = sourceFilter.next()
            updateFilterButtonLabel()
            applySortAndRecalculate()
        }
        addRenderableWidget(filterButton!!)

        sortButton = createStyledButton(centerX + 34, 62, 100, 20, "Sort: ${sortMode.label}") {
            sortMode = sortMode.next()
            updateSortButtonLabel()
            // Persist selected sort mode.
            ChestMasterMod.configManager.config.sortMode = sortMode.name
            ChestMasterMod.configManager.save()
            applySortAndRecalculate()
        }
        addRenderableWidget(sortButton!!)

        autoScanButton = createStyledButton(
            centerX + 142, 62, 110, 20,
            autoScanLabel()
        ) {
            val nowEnabled = !ChestScanner.isAutoScanEnabled()
            ChestScanner.setAutoScanEnabled(nowEnabled)
            ChestMasterMod.configManager.config.autoScan = nowEnabled
            ChestMasterMod.configManager.save()
            updateAutoScanButtonLabel()
        }
        addRenderableWidget(autoScanButton!!)

        val layout = computeLayout()
        openMarketButton = createStyledButton(
            layout.detailLeft + 8,
            layout.detailBottom - 28,
            max(80, layout.detailRight - layout.detailLeft - 16),
            20,
            ""
        ) { openSelectedOnMarket() }
        openMarketButton!!.visible = false
        openMarketButton!!.active = false
        addRenderableWidget(openMarketButton!!)

        refreshItems(previousQuery)
        lastMarketSignature = ItemValuator.marketDataSignature()
    }

    override fun removed() {
        super.removed()
        // Keep markers active after closing GUI. They are cleared manually via command.
    }

    private fun autoScanLabel(): String =
        if (ChestScanner.isAutoScanEnabled()) "AutoScan: ON" else "AutoScan: OFF"

    private fun updateModeButtonLabel() {
        modeButton?.setMessage(Component.literal("Mode: ${ItemValuator.currentMode.label}"))
    }

    private fun updateFilterButtonLabel() {
        filterButton?.setMessage(Component.literal("Show: ${sourceFilter.label}"))
    }

    private fun updateSortButtonLabel() {
        sortButton?.setMessage(Component.literal("Sort: ${sortMode.label}"))
    }

    private fun updateAutoScanButtonLabel() {
        autoScanButton?.setMessage(Component.literal(autoScanLabel()))
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
            val serverKey = WorldUtils.getCurrentServerKey()
            sourceItems = ChestMasterMod.db.searchItems(query, serverKey)
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
            SortMode.NAME_ASC -> filtered.sortedBy { it.displayName.lowercase(Locale.ROOT) }
            SortMode.COUNT_DESC -> filtered.sortedByDescending { it.count }
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
            selectedValuation = null
            selectedRecord = null
            selectedStack = ItemStack.EMPTY
            selectedSource = ItemValuator.PriceSource.UNKNOWN
            selectedUnitPrice = 0.0
            selectedStackPrice = 0.0
            selectedChestCount = 0
            selectedChestLocations = emptyList()
            highlightedChestCount = ChestLocationHighlighter.getActiveMarkerCount()
            selectedItemNameColor = defaultSelectedNameColor
            updateOpenMarketButton()
            return
        }

        selectedItemName = getDisplayLabel(selected)
        selectedItemNameColor = getItemNameColor(selected)
        selectedSource = getItemSource(selected)
        selectedUnitPrice = getCachedPrice(selected)
        selectedStackPrice = selectedUnitPrice * selected.count.toDouble()
        selectedValuation = valuation(selected)
        selectSnapshot(selected)
        val serverKey = WorldUtils.getCurrentServerKey()
        selectedChestLocations = ChestMasterMod.db.findChestLocationsForItem(
            selected.itemId,
            selected.baseItemId,
            selected.itemNbt,
            selected.displayName,
            serverKey
        )
        selectedChestCount = selectedChestLocations.size
        highlightedChestCount = ChestLocationHighlighter.getActiveMarkerCount()
        updateOpenMarketButton()
    }

    private fun valuation(record: ItemRecord): ItemValuator.Valuation =
        valuationCache.getOrPut(recordKey(record)) {
            runCatching { ItemValuator.evaluate(record) }
                .getOrElse { ItemValuator.Valuation(record.itemId, ItemValuator.PriceSource.UNKNOWN, 0.0, emptyList()) }
        }

    /** Re-tries unpriced items every 15 s and re-sorts once something new got a price. */
    private fun retryUnpricedValuations() {
        val now = System.currentTimeMillis()
        if (now - lastValuationRetryMs < 15_000L) return
        lastValuationRetryMs = now
        val unpriced = valuationCache.filterValues { !it.priced }.keys.toHashSet()
        if (unpriced.isEmpty()) return
        unpriced.forEach { valuationCache.remove(it) }
        if (sourceItems.any { recordKey(it) in unpriced && valuation(it).priced }) {
            applySortAndRecalculate()
        }
    }

    private fun getCachedPrice(record: ItemRecord): Double = valuation(record).unitPrice

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

            val enchant = ItemValuator.bookEnchantLabel(record.itemNbt) ?: return@getOrPut baseName
            "Enchanted Book ($enchant)"
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
            // Uses pre-sorted aliases (longest first) to avoid creating a new list each call.
            for (alias in rarity.sortedAliases) {
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

    private fun getItemSource(record: ItemRecord): ItemValuator.PriceSource = valuation(record).source

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
        selectedValuation = valuation(record)
        selectSnapshot(record)
        detailScroll = 0
        SearchHighlight.set(record)

        val serverKey = WorldUtils.getCurrentServerKey()
        selectedChestLocations = ChestMasterMod.db.findChestLocationsForItem(
            record.itemId,
            record.baseItemId,
            record.itemNbt,
            record.displayName,
            serverKey
        )
        selectedChestCount = selectedChestLocations.size

        val positions = selectedChestLocations.map { location ->
            BlockPos(location.x, location.y, location.z)
        }
        highlightedChestCount = ChestLocationHighlighter.highlight(selectedItemName ?: record.displayName, positions)
        updateOpenMarketButton()
    }

    private fun recordKey(record: ItemRecord): String {
        return "${record.itemId}@@${record.baseItemId}@@${record.itemNbt}"
    }

    private fun updateOpenMarketButton() {
        val button = openMarketButton ?: return
        val query = marketQueryForSelected()
        val label = if (query == null) null else when (selectedSource) {
            ItemValuator.PriceSource.BAZAAR -> "Bazaar: /bz $query"
            ItemValuator.PriceSource.AUCTION -> "Auction: /ahs $query"
            else -> null
        }
        button.visible = label != null
        button.active = label != null
        if (label != null) {
            button.setMessage(Component.literal(label))
        }
    }

    private fun marketQueryForSelected(): String? {
        if (selectedItemKey == null) return null
        var name = selectedItemName ?: return null
        name = name.replace(Regex("§."), "")
        // Pets: "[Lvl 1] Megalodon" -> "Megalodon"
        name = name.replace(Regex("^\\[Lvl \\d+]\\s*"), "")
        name = name.replace(Regex("(?i)\\s*x\\d+$"), "")
        // "Enchanted Book (Growth V)" -> search the enchantment itself.
        Regex("^Enchanted Book \\((.+)\\)$").find(name)?.let { match ->
            name = match.groupValues[1]
        }
        // Rune/star prefixes like "✦ Snow Rune I".
        name = name.dropWhile { !it.isLetterOrDigit() }.trim()
        return name.takeIf { it.isNotBlank() }
    }

    private fun openSelectedOnMarket() {
        val query = marketQueryForSelected() ?: return
        val command = when (selectedSource) {
            ItemValuator.PriceSource.BAZAAR -> "bz $query"
            ItemValuator.PriceSource.AUCTION -> "ahs $query"
            else -> return
        }
        val connection = Minecraft.getInstance().player?.connection
        if (connection == null) {
            ChestMasterMod.LOGGER.warn("Cannot open market screen: no player connection")
            return
        }
        // Close our screen first so the server-opened Bazaar/AH GUI is not replaced.
        onClose()
        connection.sendCommand(command)
    }

    override fun extractRenderState(guiGraphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        retryUnpricedValuations()
        // SkyBlockAPI swapped in new market data (first load, auction data arriving after the
        // Bazaar, or a periodic refresh): re-evaluate everything so nothing stays half-priced.
        val signature = ItemValuator.marketDataSignature()
        if (signature != lastMarketSignature) {
            lastMarketSignature = signature
            valuationCache.clear()
            refreshItems(searchField?.value ?: "")
        }

        guiGraphics.fillGradient(0, 0, width, height, 0xB40A0F17.toInt(), 0xE0141D2B.toInt())
        guiGraphics.fillGradient(0, 0, width, 90, 0xA0224A6A.toInt(), 0x20224A6A.toInt())
        guiGraphics.fillGradient(0, height - 96, width, height, 0x00000000, 0x7A2A1427.toInt())

        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick)

        val layout = computeLayout()
        if (isDraggingListScrollbar) {
            applyScrollbarDrag(mouseY, layout)
        }
        val centerX = width / 2

        guiGraphics.centeredText(font, title, centerX, 14, 0xFFF4FAFF.toInt())
        guiGraphics.centeredText(font, "Fast search, valuation details, chest markers", centerX, 24, 0xFFB7C7DD.toInt())

        val totalText = if (ItemValuator.arePricesLoaded()) {
            "${ItemValuator.formatPrice(totalValue)} coins"
        } else {
            "Loading market data..."
        }
        guiGraphics.text(font, "Total Value: $totalText", layout.listLeft, 88, 0xFFEAF2FF.toInt(), false)
        val topStats = "Shown ${items.size}/${sourceStats.total} | Baz ${sourceStats.bazaar} | AH ${sourceStats.auction} | Filter ${sourceFilter.label}"
        guiGraphics.text(font, ellipsize(topStats, max(80, layout.detailRight - layout.detailLeft - 8)), layout.detailLeft, 88, 0xFF9FB5D3.toInt(), false)

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
            guiGraphics.centeredText(
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

                guiGraphics.item(stack, rowLeft + 4, rowTop + 4)
                guiGraphics.itemDecorations(font, stack, rowLeft + 4, rowTop + 4)

                val nameX = rowLeft + 26
                val hasPrice = ItemValuator.arePricesLoaded() || valuation(record).priced
                val totalLabel = if (hasPrice) {
                    ItemValuator.formatPrice(getCachedPrice(record) * record.count.toDouble())
                } else {
                    "Loading..."
                }
                val totalWidth = font.width(totalLabel)
                val totalX = rowRight - totalWidth - 6
                val availableNameWidth = max(40, totalX - nameX - 8)
                val itemNameColor = getItemNameColor(record)

                val itemLabel = ellipsize("${getDisplayLabel(record)} x${record.count}", availableNameWidth)
                guiGraphics.text(font, itemLabel, nameX, rowTop + 5, itemNameColor, false)
                guiGraphics.text(font, totalLabel, totalX, rowTop + 5, 0xFFE5EEFF.toInt(), false)

                if (hasPrice) {
                    val unitText = "unit ${ItemValuator.formatPrice(getCachedPrice(record))}"
                    guiGraphics.text(font, unitText, nameX, rowTop + 15, 0xFF99AECB.toInt(), false)
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

    private fun selectSnapshot(record: ItemRecord) {
        selectedRecord = record
        selectedStack = runCatching {
            ItemUtils.deserializeItemStack(record.baseItemId.ifBlank { record.itemId }, record.itemNbt)
        }.getOrDefault(ItemStack.EMPTY)
    }

    private val groupPalette = intArrayOf(
        0xFFFFC857.toInt(), // gold
        0xFFB98CFF.toInt(), // purple
        0xFF5FD3F3.toInt(), // cyan
        0xFF7BE07B.toInt(), // green
        0xFFFF7EB6.toInt(), // pink
        0xFFFF9F43.toInt()  // orange
    )

    private fun sourceColor(source: ItemValuator.PriceSource): Int = when (source) {
        ItemValuator.PriceSource.BAZAAR -> 0xFFE0B040.toInt()
        ItemValuator.PriceSource.AUCTION -> 0xFF9B6BFF.toInt()
        ItemValuator.PriceSource.NPC -> 0xFF4CC38A.toInt()
        ItemValuator.PriceSource.UNKNOWN -> 0xFF6B7A8C.toInt()
    }

    /** Small rounded-looking label chip; returns its width. */
    private fun drawPill(g: GuiGraphicsExtractor, x: Int, y: Int, text: String, color: Int): Int {
        val w = font.width(text) + 8
        val bg = (color and 0x00FFFFFF) or 0x55000000
        g.fill(x + 1, y, x + w - 1, y + 11, bg)
        g.fill(x, y + 1, x + w, y + 10, bg)
        g.fill(x + 1, y + 10, x + w - 1, y + 11, color)
        g.text(font, text, x + 4, y + 2, 0xFFF4F8FF.toInt(), false)
        return w
    }

    private fun drawScaledText(g: GuiGraphicsExtractor, text: String, x: Int, y: Int, scale: Float, color: Int) {
        val pose = g.pose()
        pose.pushMatrix()
        pose.translate(x.toFloat(), y.toFloat())
        pose.scale(scale, scale)
        g.text(font, text, 0, 0, color, true)
        pose.popMatrix()
    }

    private fun drawScaledItem(g: GuiGraphicsExtractor, stack: ItemStack, x: Int, y: Int, scale: Float) {
        val pose = g.pose()
        pose.pushMatrix()
        pose.translate(x.toFloat(), y.toFloat())
        pose.scale(scale, scale)
        g.item(stack, 0, 0)
        pose.popMatrix()
    }

    private fun drawRightAligned(g: GuiGraphicsExtractor, text: String, right: Int, y: Int, color: Int) {
        g.text(font, text, right - font.width(text), y, color, false)
    }

    private fun renderDetailPanel(guiGraphics: GuiGraphicsExtractor, layout: Layout) {
        val g = guiGraphics
        drawPanel(
            g,
            layout.detailLeft,
            layout.detailTop,
            layout.detailRight,
            layout.detailBottom,
            0x8F1F2936.toInt(),
            0x99141D29.toInt(),
            0xAA6B8FB5.toInt()
        )

        val left = layout.detailLeft + 8
        val right = layout.detailRight - 8
        val innerWidth = max(70, right - left)
        val buttonSpace = if (openMarketButton?.visible == true) 32 else 6
        val clipTop = layout.detailTop + 2
        val clipBottom = layout.detailBottom - buttonSpace

        // --- market summary chips (always visible, not scrolled)
        var y = layout.detailTop + 7
        var x = left
        x += drawPill(g, x, y, "${items.size}/${sourceStats.total} items", 0xFF6B8FB5.toInt()) + 4
        x += drawPill(g, x, y, "Bazaar ${sourceStats.bazaar}", sourceColor(ItemValuator.PriceSource.BAZAAR)) + 4
        x += drawPill(g, x, y, "AH ${sourceStats.auction}", sourceColor(ItemValuator.PriceSource.AUCTION)) + 4
        x += drawPill(g, x, y, "NPC ${sourceStats.npc}", sourceColor(ItemValuator.PriceSource.NPC)) + 4
        if (x + 50 < right) drawPill(g, x, y, "? ${sourceStats.unknown}", sourceColor(ItemValuator.PriceSource.UNKNOWN))
        y += 16
        g.fill(left, y, right, y + 1, 0x55648CB6)
        y += 6

        val valuation = selectedValuation
        val record = selectedRecord
        if (valuation == null || record == null || selectedItemName.isNullOrBlank()) {
            detailContentHeight = 0
            val hints = listOf(
                "Click an item to see its value" to 0xFFC2D2E6.toInt(),
                "and where it is stored." to 0xFFC2D2E6.toInt(),
                "" to 0,
                "Chests containing it light up" to 0xFF93A7C2.toInt(),
                "in the world, visible through walls." to 0xFF93A7C2.toInt(),
                "" to 0,
                "Clear markers: /cm m clear" to 0xFF7F95B3.toInt()
            )
            for ((text, color) in hints) {
                if (text.isNotEmpty()) g.text(font, ellipsize(text, innerWidth), left, y, color, false)
                y += 10
            }
            return
        }

        // --- scrollable body
        val bodyTop = y
        val visibleHeight = clipBottom - bodyTop
        val maxScroll = max(0, detailContentHeight - visibleHeight)
        detailScroll = detailScroll.coerceIn(0, maxScroll)
        g.enableScissor(layout.detailLeft + 1, bodyTop - 2, layout.detailRight - 1, clipBottom)
        y = bodyTop - detailScroll

        // Item card: big icon, name, source chip, prices
        val cardTop = y
        val cardHeight = 58
        g.fill(left, cardTop, right, cardTop + cardHeight, 0x40000000)
        g.fill(left, cardTop, left + 2, cardTop + cardHeight, selectedItemNameColor)
        g.fill(left + 6, cardTop + 6, left + 42, cardTop + 42, 0x50FFFFFF)
        g.fill(left + 7, cardTop + 7, left + 41, cardTop + 41, 0x90101820.toInt())
        if (!selectedStack.isEmpty) drawScaledItem(g, selectedStack, left + 8, cardTop + 8, 2f)

        val textX = left + 48
        val textWidth = max(40, right - textX - 4)
        g.text(font, ellipsize(selectedItemName ?: "", textWidth), textX, cardTop + 6, selectedItemNameColor, true)
        var chipX = textX
        chipX += drawPill(g, chipX, cardTop + 18, selectedSource.label, sourceColor(selectedSource)) + 4
        if (record.count > 1) drawPill(g, chipX, cardTop + 18, "x${record.count}", 0xFF6B8FB5.toInt())

        val unitText = if (valuation.priced) ItemValuator.formatPrice(selectedUnitPrice) else "—"
        drawScaledText(g, unitText, textX, cardTop + 34, 2f, 0xFFFFD866.toInt())
        val unitWidth = font.width(unitText) * 2
        g.text(font, "each", textX + unitWidth + 4, cardTop + 41, 0xFF8FA3BF.toInt(), false)
        if (record.count > 1 && valuation.priced) {
            val stackText = "stack ${ItemValuator.formatPrice(selectedStackPrice)}"
            drawRightAligned(g, stackText, right - 4, cardTop + 41, 0xFFE8D9A0.toInt())
        }
        y = cardTop + cardHeight + 8

        // Value breakdown
        g.text(font, "VALUE BREAKDOWN", left, y, 0xFF8ED4FF.toInt(), false)
        if (valuation.priced) drawRightAligned(g, ItemValuator.formatPrice(valuation.unitPrice), right, y, 0xFFFFE08D.toInt())
        y += 12
        if (!valuation.priced) {
            g.text(font, ellipsize("No market price found for this item.", innerWidth), left, y, 0xFFFF9C9C.toInt(), false)
            y += 10
            g.text(font, ellipsize("ID: ${valuation.skyblockId.ifBlank { "?" }}", innerWidth), left, y, 0xFF7F95B3.toInt(), false)
            y += 14
        } else {
            val total = valuation.unitPrice.coerceAtLeast(1.0)
            for ((index, group) in valuation.groups.withIndex()) {
                val color = groupPalette[index % groupPalette.size]
                val valueText = ItemValuator.formatPrice(group.value)
                g.fill(left, y + 1, left + 3, y + 8, color)
                g.text(font, ellipsize(group.label, innerWidth - font.width(valueText) - 14), left + 6, y, 0xFFEAF2FF.toInt(), false)
                drawRightAligned(g, valueText, right, y, color)
                y += 10
                // share bar
                val share = (group.value / total).coerceIn(0.0, 1.0)
                g.fill(left + 6, y, right, y + 2, 0x30FFFFFF)
                g.fill(left + 6, y, left + 6 + ((right - left - 6) * share).toInt(), y + 2, color)
                y += 5
                val shownParts = if (group.parts.size == 1 && group.parts[0].label.equals(group.label, ignoreCase = true)) {
                    emptyList()
                } else {
                    group.parts.take(4)
                }
                for (part in shownParts) {
                    val partValue = ItemValuator.formatPrice(part.value)
                    g.text(font, "•", left + 8, y, 0xFF5E7290.toInt(), false)
                    g.text(font, ellipsize(part.label, innerWidth - font.width(partValue) - 22), left + 15, y, 0xFFB8C8DD.toInt(), false)
                    drawRightAligned(g, partValue, right, y, 0xFF9FB2CC.toInt())
                    y += 10
                }
                if (group.parts.size > shownParts.size && shownParts.isNotEmpty()) {
                    g.text(font, "+${group.parts.size - shownParts.size} more", left + 15, y, 0xFF6F84A3.toInt(), false)
                    y += 10
                }
                y += 3
            }
        }

        // Locations
        y += 2
        g.fill(left, y, right, y + 1, 0x40648CB6)
        y += 6
        g.text(font, "STORED IN", left, y, 0xFF8ED4FF.toInt(), false)
        drawRightAligned(g, "$selectedChestCount chest(s)", right, y, 0xFFA5BDD7.toInt())
        y += 12
        for (loc in selectedChestLocations.take(6)) {
            g.fill(left + 2, y + 1, left + 8, y + 7, 0xFFB98A4E.toInt())
            g.fill(left + 2, y + 3, left + 8, y + 4, 0xFF5A3E1F.toInt())
            val coords = "${loc.x}, ${loc.y}, ${loc.z}"
            g.text(font, coords, left + 12, y, 0xFFD5E2F2.toInt(), false)
            if (loc.label.isNotBlank()) {
                val labelX = left + 16 + font.width(coords)
                g.text(font, ellipsize(loc.label, max(10, right - labelX)), labelX, y, 0xFF6F84A3.toInt(), false)
            }
            y += 10
        }
        if (selectedChestLocations.size > 6) {
            g.text(font, "+${selectedChestLocations.size - 6} more", left + 12, y, 0xFF6F84A3.toInt(), false)
            y += 10
        }
        y += 4
        g.text(font, ellipsize("Markers: $highlightedChestCount  ·  clear: /cm m clear", innerWidth), left, y, 0xFF6F84A3.toInt(), false)
        y += 12

        detailContentHeight = y + detailScroll - bodyTop
        g.disableScissor()

        // Scroll hint when the body overflows
        if (maxScroll > 0) {
            val trackTop = bodyTop
            val trackHeight = visibleHeight
            val thumbHeight = max(12, trackHeight * visibleHeight / max(1, detailContentHeight))
            val thumbTop = trackTop + ((trackHeight - thumbHeight) * detailScroll / max(1, maxScroll))
            g.fill(layout.detailRight - 4, trackTop, layout.detailRight - 2, trackTop + trackHeight, 0x40445A74)
            g.fill(layout.detailRight - 4, thumbTop, layout.detailRight - 2, thumbTop + thumbHeight, 0xFF8FB7DF.toInt())
        }
    }

    private fun drawPanel(
        guiGraphics: GuiGraphicsExtractor,
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
        val top = 100
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

    /** Binary-search ellipsize: O(log n) instead of O(n) per string. */
    private fun ellipsize(text: String, maxWidth: Int): String {
        if (font.width(text) <= maxWidth) return text
        val suffix = "..."
        val suffixWidth = font.width(suffix)
        if (maxWidth <= suffixWidth) return suffix

        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (font.width(text.substring(0, mid)) + suffixWidth <= maxWidth) lo = mid
            else hi = mid - 1
        }
        return if (lo == 0) suffix else "${text.substring(0, lo)}$suffix"
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

        val insideDetail = mouseX >= layout.detailLeft &&
            mouseX <= layout.detailRight &&
            mouseY >= layout.detailTop &&
            mouseY <= layout.detailBottom
        if (insideDetail && selectedValuation != null) {
            detailScroll = (detailScroll - (verticalAmount * 14).toInt()).coerceAtLeast(0)
            return true
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
