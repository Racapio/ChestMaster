package com.chestmaster.valuation

import com.chestmaster.ChestMasterMod
import com.chestmaster.database.ItemRecord
import com.chestmaster.util.ItemUtils
import net.minecraft.world.item.ItemStack
import tech.thatgravyboat.skyblockapi.api.item.calculator.CalculationEntry
import tech.thatgravyboat.skyblockapi.api.item.calculator.CostEntries
import tech.thatgravyboat.skyblockapi.api.item.calculator.GroupedEntry
import tech.thatgravyboat.skyblockapi.api.item.calculator.ItemLikeEntry
import tech.thatgravyboat.skyblockapi.api.item.calculator.ItemStarEntry
import tech.thatgravyboat.skyblockapi.api.item.calculator.ItemWithLimitEntry
import tech.thatgravyboat.skyblockapi.api.item.calculator.ItemEntry
import tech.thatgravyboat.skyblockapi.api.item.calculator.ReforgeEntry
import tech.thatgravyboat.skyblockapi.api.item.calculator.getItemValue
import tech.thatgravyboat.skyblockapi.api.remote.hypixel.itemdata.ItemData
import tech.thatgravyboat.skyblockapi.api.remote.hypixel.pricing.BazaarAPI
import tech.thatgravyboat.skyblockapi.api.remote.hypixel.pricing.LowestBinAPI
import tech.thatgravyboat.skyblockapi.utils.extentions.getApiId
import tech.thatgravyboat.skyblockapi.utils.extentions.getSkyBlockId
import java.util.Locale

/**
 * Item valuation built entirely on SkyBlockAPI: its Bazaar, lowest-BIN and NPC data plus its
 * item value calculator (enchantments at any level, stars, gemstones, reforges, drill/rod
 * parts, Necron scrolls, runes, dyes, skins, pets…). SkyBlockAPI downloads and refreshes all
 * market data itself, so ChestMaster makes no pricing requests of its own.
 */
object ItemValuator {
    enum class PriceSource(val label: String) {
        BAZAAR("Bazaar"),
        AUCTION("Auction"),
        NPC("NPC"),
        UNKNOWN("Unknown")
    }

    enum class PriceMode(val label: String) {
        SELL_OFFER("Sell Offer"),
        BUY_ORDER("Buy Order")
    }

    /** One contribution inside a group, e.g. "16x Chimera I — 405M". */
    data class Part(val label: String, val value: Double, val icon: ItemStack?)

    /** One top-level slice of the value, e.g. "Enchantments — 790M" with its parts. */
    data class Group(val label: String, val value: Double, val parts: List<Part>)

    /** Value of one item (count 1). [unitPrice] is 0 when nothing could price it. */
    data class Valuation(
        val skyblockId: String,
        val source: PriceSource,
        val unitPrice: Double,
        val groups: List<Group>
    ) {
        val priced: Boolean get() = unitPrice > 0.0
    }

    var currentMode = PriceMode.SELL_OFFER

    /** Sets the price mode and persists it to the config. */
    fun setPriceMode(mode: PriceMode) {
        currentMode = mode
        runCatching {
            ChestMasterMod.configManager.config.priceMode = mode.name
            ChestMasterMod.configManager.save()
        }
    }

    fun togglePriceMode(): PriceMode {
        val next = if (currentMode == PriceMode.SELL_OFFER) PriceMode.BUY_ORDER else PriceMode.SELL_OFFER
        setPriceMode(next)
        return next
    }

    fun loadPriceModeFromConfig() {
        currentMode = runCatching {
            PriceMode.valueOf(ChestMasterMod.configManager.config.priceMode)
        }.getOrDefault(PriceMode.SELL_OFFER)
    }

    /**
     * True once SkyBlockAPI has both its Bazaar and lowest-BIN snapshots. The Bazaar arrives
     * first; valuing items before the auction data is in leaves every AH item without its
     * base price (e.g. a Daedalus Blade missing its 280M), so both are required.
     */
    fun arePricesLoaded(): Boolean = bazaarSize() > 0 && lowestBinSize() > 0

    /**
     * Changes whenever SkyBlockAPI swaps in new market data (first load or periodic refresh).
     * The GUI watches it to re-evaluate, so no valuation is ever stuck on stale/partial data.
     */
    fun marketDataSignature(): Long {
        val bazaar = runCatching { BazaarAPI.products }.getOrNull()
        val lbin = runCatching { LowestBinAPI.items }.getOrNull()
        return (System.identityHashCode(bazaar).toLong() shl 32) xor
            System.identityHashCode(lbin).toLong() xor
            (bazaarSize().toLong() shl 16) xor lowestBinSize().toLong()
    }

    fun evaluate(record: ItemRecord): Valuation {
        val baseId = record.baseItemId.ifBlank { record.itemId }
        val stack = ItemUtils.deserializeItemStack(baseId, record.itemNbt)
        return evaluate(stack, fallbackId = record.itemId)
    }

    fun evaluate(stack: ItemStack, fallbackId: String = ""): Valuation {
        if (stack.isEmpty) return Valuation(fallbackId, PriceSource.UNKNOWN, 0.0, emptyList())
        stack.count = 1 // the calculator multiplies by the stack count — we want the unit price

        val skyblockId = runCatching { stack.getSkyBlockId() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: ItemUtils.normalizeSkyblockId(fallbackId).orEmpty()
        // The *market* id: e.g. ENCHANTMENT_ULTIMATE_SUNSET_1 for a book, the AH key for a pet.
        val marketId = runCatching { stack.getApiId() }.getOrNull()?.takeIf { it.isNotBlank() } ?: skyblockId
        val source = sourceOf(marketId).takeIf { it != PriceSource.UNKNOWN } ?: sourceOf(skyblockId)

        // Plain Bazaar items (incl. single enchanted books): honour Sell Offer / Buy Order.
        val product = runCatching { BazaarAPI.getProduct(marketId) }.getOrNull()
        if (product != null) {
            val price = if (currentMode == PriceMode.SELL_OFFER) product.buyPrice else product.sellPrice
            if (price > 0.0) {
                val group = Group("Bazaar (${currentMode.label})", price, listOf(Part(displayName(marketId, stack), price, stack.copy())))
                return Valuation(skyblockId, PriceSource.BAZAAR, price, listOf(group))
            }
        }

        val result = runCatching { stack.getItemValue() }.getOrNull()
        if (result != null && result.price > 0L) {
            // SkyBlockAPI prices Bazaar components at the instant-sell price; re-price them by the
            // selected mode so "Sell Offer" matches what other value tooltips show.
            val groups = result.entryTree
                .map { group ->
                    Group(
                        label = groupLabel(group.source.name),
                        value = group.price + bazaarDelta(group),
                        parts = group.entries
                            .map { toPart(it) }
                            .filter { it.value > 0.0 }
                            .sortedByDescending { it.value }
                    )
                }
                .filter { it.value > 0.0 }
                .sortedByDescending { it.value }
            val total = result.price + result.entryTree.sumOf { bazaarDelta(it) }
            return Valuation(skyblockId, source, total.coerceAtLeast(0.0), groups)
        }

        val npc = runCatching { ItemData.getNpcSellPrice(skyblockId) }.getOrNull()
        if (npc != null && npc > 0f) {
            val value = npc.toDouble()
            return Valuation(skyblockId, PriceSource.NPC, value, listOf(Group("NPC sell price", value, emptyList())))
        }

        return Valuation(skyblockId, source, 0.0, emptyList())
    }

    fun sourceOf(marketId: String): PriceSource {
        if (marketId.isEmpty()) return PriceSource.UNKNOWN
        return when {
            runCatching { BazaarAPI.getProduct(marketId) }.getOrNull() != null -> PriceSource.BAZAAR
            runCatching { LowestBinAPI.getLowestPrice(marketId) }.getOrNull() != null -> PriceSource.AUCTION
            runCatching { ItemData.getNpcSellPrice(marketId) }.getOrNull() != null -> PriceSource.NPC
            else -> PriceSource.UNKNOWN
        }
    }

    fun getDebugStatus(): String =
        "SkyBlockAPI market data: bazaar=${bazaarSize()}, lowestBin=${lowestBinSize()} (refreshed automatically)"

    /** "Ultimate Sunset I" for an enchanted book, from its stored NBT; null if not a book. */
    fun bookEnchantLabel(nbt: String): String? {
        val extra = ItemUtils.extractExtraAttributesFromNbtString(nbt) ?: return null
        val enchants = extra.getCompound("enchantments").orElse(null) ?: return null
        val best = enchants.keySet()
            .mapNotNull { key -> enchants.getInt(key).orElse(null)?.let { key to it } }
            .maxByOrNull { (key, level) -> (if (key.startsWith("ultimate_")) 100 else 0) + level }
            ?: return null
        return "${toTitleCase(best.first)} ${toRoman(best.second)}"
    }

    fun formatPrice(price: Double): String {
        if (price < 0) return "Loading..."
        if (price == 0.0) return "0"
        return when {
            price >= 1_000_000_000 -> String.format("%.2fB", price / 1_000_000_000.0)
            price >= 1_000_000 -> String.format("%.2fM", price / 1_000_000.0)
            price >= 1_000 -> String.format("%.1fk", price / 1_000.0)
            else -> String.format("%.0f", price)
        }
    }

    private fun bazaarSize(): Int = runCatching { BazaarAPI.products.size }.getOrDefault(0)

    private fun lowestBinSize(): Int = runCatching { LowestBinAPI.items.size }.getOrDefault(0)

    /** Mode-specific Bazaar unit price for a calculator item id, or null if it isn't on the Bazaar. */
    private fun bazaarUnitPrice(itemId: String): Double? {
        val product = runCatching { BazaarAPI.getProduct(itemId) }.getOrNull() ?: return null
        val price = if (currentMode == PriceMode.SELL_OFFER) product.buyPrice else product.sellPrice
        return price.takeIf { it > 0.0 }
    }

    /** How much re-pricing Bazaar components by the selected mode changes an entry's value. */
    private fun bazaarDelta(entry: CalculationEntry): Double = when (entry) {
        is ItemEntry -> bazaarUnitPrice(entry.itemId)?.let { it * entry.amount - entry.price } ?: 0.0
        is ItemWithLimitEntry -> bazaarUnitPrice(entry.itemId)?.let { it * entry.amount - entry.price } ?: 0.0
        is GroupedEntry -> entry.entries.sumOf { bazaarDelta(it) }
        is ItemStarEntry -> entry.stars.sumOf { bazaarDelta(it) }
        else -> 0.0
    }

    private fun toPart(entry: CalculationEntry): Part {
        val value = entry.price + bazaarDelta(entry)
        return when (entry) {
            is ItemWithLimitEntry -> Part("${entry.amount}/${entry.limit} ${displayName(entry.itemId, entry.itemStack)}", value, entry.itemStack)
            is ItemEntry -> Part(withAmount(entry.amount, displayName(entry.itemId, entry.itemStack)), value, entry.itemStack)
            is ItemLikeEntry -> Part(displayName(entry.itemId, entry.itemStack), value, entry.itemStack)
            is ReforgeEntry -> Part("Reforge: ${toTitleCase(entry.reforge)}", value, null)
            is ItemStarEntry -> Part("${entry.stars.size} star(s)", value, null)
            is CostEntries -> Part("Unlock cost", value, null)
            is GroupedEntry -> Part(groupLabel(entry.source.name), value, null)
            else -> Part(toTitleCase(entry.javaClass.simpleName.removeSuffix("Entry")), value, null)
        }
    }

    private fun withAmount(amount: Int, name: String): String = if (amount > 1) "${amount}x $name" else name

    private val formattingRegex = Regex("§.")

    /** Human name for a calculator item id: "ENCHANTMENT_ULTIMATE_CHIMERA_1" → "Chimera I". */
    private fun displayName(itemId: String, stack: ItemStack?): String {
        if (itemId.startsWith("ENCHANTMENT_")) {
            val body = itemId.removePrefix("ENCHANTMENT_").removePrefix("ULTIMATE_")
            val level = body.substringAfterLast('_').toIntOrNull()
            val name = if (level != null) body.substringBeforeLast('_') else body
            return if (level != null) "${toTitleCase(name)} ${toRoman(level)}" else toTitleCase(name)
        }
        val fromStack = stack?.takeIf { !it.isEmpty }?.hoverName?.string
            ?.let { formattingRegex.replace(it, "").trim() }
            ?.takeIf { it.isNotBlank() && !it.equals("Air", ignoreCase = true) }
            // SkyBlockAPI renders a placeholder like "Could not find item for key 'X' in Items"
            // when its item repo couldn't be downloaded — fall back to the id then.
            ?.takeUnless { it.startsWith("Could not find", ignoreCase = true) }
        return fromStack ?: toTitleCase(itemId.substringAfter(':'))
    }

    private fun groupLabel(enumName: String): String = when (enumName) {
        "BASE_ITEM" -> "Base item"
        "ENCHANTMENT" -> "Enchantments"
        "ITEM_STARS" -> "Stars"
        "GEMSTONE" -> "Gemstones"
        "REFORGE" -> "Reforge"
        "HOT_POTATO" -> "Potato books"
        "RECOMBOBULATOR" -> "Recombobulator"
        "APPLIED_RUNE" -> "Rune"
        "APPLIED_DYE" -> "Dye"
        "HELMET_SKIN" -> "Skin"
        "DRILL_COMPONENTS" -> "Drill parts"
        "FISHING_ROD_PARTS" -> "Rod parts"
        "NECRON_SCROLLS" -> "Necron scrolls"
        else -> toTitleCase(enumName)
    }

    private fun toTitleCase(raw: String): String =
        raw.replace('_', ' ')
            .lowercase(Locale.ROOT)
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.titlecase(Locale.ROOT) } }

    private fun toRoman(number: Int): String {
        if (number <= 0) return number.toString()
        val values = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val symbols = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        var n = number
        val result = StringBuilder()
        for (i in values.indices) {
            while (n >= values[i]) {
                result.append(symbols[i])
                n -= values[i]
            }
        }
        return result.toString()
    }
}
