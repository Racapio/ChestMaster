package com.chestmaster.valuation

import com.chestmaster.ChestMasterMod
import com.chestmaster.database.ItemRecord
import com.chestmaster.util.ItemUtils
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
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
        UNKNOWN("No price")
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
            (bazaarSize().toLong() shl 16) xor lowestBinSize().toLong() xor
            (PetPrices.version shl 40) xor (ShardIds.version shl 48) xor
            (if (includeSkinsAndDyes()) 0L else 1L shl 62)
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
        val customData = stack.get(DataComponents.CUSTOM_DATA)?.copyTag()

        // Pets: priced by type + rarity + level (SkyBlockAPI ignores the level).
        if (skyblockId == "PET" || customData?.contains("petInfo") == true) {
            evaluatePet(stack, customData)?.let { return it }
        }

        // Attribute shards: resolved by their attribute (names were renamed, Bazaar ids weren't).
        if (skyblockId == "ATTRIBUTE_SHARD" || marketId == "ATTRIBUTE_SHARD") {
            ShardIds.resolve(customData, stack.hoverName.string)?.let { shardId ->
                bazaarUnitPrice(shardId)?.let { price ->
                    val group = Group("Bazaar (${currentMode.label})", price, listOf(Part(shardId.removePrefix("SHARD_").let(::toTitleCase) + " Shard", price, stack.copy())))
                    return Valuation(shardId, PriceSource.BAZAAR, price, listOf(group))
                }
            }
        }

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
            val includeCosmetics = includeSkinsAndDyes()
            val entries = result.entryTree.filter { includeCosmetics || it.source.name !in cosmeticSources }
            val calculated = entries
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
            val (groups, usageValue) = withUsageEnchants(calculated, customData, entries)
            val sortedGroups = groups.filter { it.value > 0.0 }.sortedByDescending { it.value }
            val excluded = if (includeCosmetics) 0.0 else
                result.entryTree.filter { it.source.name in cosmeticSources }.sumOf { it.price.toDouble() }
            val total = result.price - excluded + entries.sumOf { bazaarDelta(it) } + usageValue
            // Enchanted books etc. have no market id of their own: take the source of their
            // most valuable component (e.g. the Bazaar book behind "Ultimate Sunset I").
            val resolvedSource = if (source != PriceSource.UNKNOWN) source else dominantSource(entries)
            if (total > 0.0) return Valuation(skyblockId, resolvedSource, total, sortedGroups)
        }

        val npc = runCatching { ItemData.getNpcSellPrice(skyblockId) }.getOrNull()
        if (npc != null && npc > 0f) {
            val value = npc.toDouble()
            return Valuation(skyblockId, PriceSource.NPC, value, listOf(Group("NPC sell price", value, emptyList())))
        }

        return Valuation(skyblockId, source, 0.0, emptyList())
    }

    private val petLevelRegex = Regex("""\[Lvl (\d+)]""")

    /**
     * Pet value = lowest BIN of the same pet type, rarity and level range (Coflnet), plus the held
     * pet item and the applied skin at their market prices. Returns null when the pet can't be
     * identified; while its price is still being fetched, SkyBlockAPI's tier-only value is used.
     */
    private fun evaluatePet(stack: ItemStack, customData: CompoundTag?): Valuation? {
        val petInfo = customData?.getString("petInfo")?.orElse(null) ?: return null
        val info = runCatching { com.google.gson.JsonParser.parseString(petInfo).asJsonObject }.getOrNull() ?: return null
        val type = info.get("type")?.asString?.uppercase(Locale.ROOT) ?: return null
        val tier = info.get("tier")?.asString?.uppercase(Locale.ROOT) ?: return null
        val name = formattingRegex.replace(stack.hoverName.string, "")
        val level = petLevelRegex.find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 1

        val quote = PetPrices.quote(type, tier, level)
        if (quote == null) {
            // Price not fetched yet: show SkyBlockAPI's tier-only estimate meanwhile, if any.
            return null
        }

        val groups = ArrayList<Group>()
        val petName = "${toTitleCase(type)} ${toTitleCase(tier)} (${quote.label})"
        groups += Group("Pet (Lvl $level)", quote.price, listOf(Part(petName, quote.price, stack.copy())))

        info.get("heldItem")?.takeIf { !it.isJsonNull }?.asString?.let { heldItem ->
            marketPrice(heldItem)?.let {
                val icon = repoIcon(heldItem)
                val label = icon?.let { stack -> displayName(heldItem, stack) } ?: toTitleCase(heldItem.removePrefix("PET_ITEM_"))
                groups += Group("Pet item", it, listOf(Part(label, it, icon)))
            }
        }
        info.get("skin")?.takeIf { !it.isJsonNull && includeSkinsAndDyes() }?.asString?.let { skin ->
            marketPrice("PET_SKIN_$skin")?.let { groups += Group("Skin", it, listOf(Part(toTitleCase(skin) + " Skin", it, repoIcon("PET_SKIN_$skin")))) }
        }

        val total = groups.sumOf { it.value }
        return Valuation("PET_$type", PriceSource.AUCTION, total, groups.sortedByDescending { it.value })
    }

    /**
     * Enchantments that level up by use (Champion, Toxophilite, Compact…). Only the level-I book
     * exists on the Bazaar, so SkyBlockAPI finds no price for e.g. "Champion X" and skips it.
     * They are valued at the level-I book they were applied from.
     */
    private val usageEnchants = setOf("champion", "toxophilite", "compact", "cultivating", "expertise", "hecatomb")

    private fun withUsageEnchants(
        groups: List<Group>,
        customData: CompoundTag?,
        entries: List<CalculationEntry>
    ): Pair<List<Group>, Double> {
        val enchants = customData?.getCompound("enchantments")?.orElse(null) ?: return groups to 0.0
        val pricedIds = HashSet<String>().also { ids -> entries.forEach { collectItemIds(it, ids) } }
        val extra = enchants.keySet()
            .filter { it in usageEnchants }
            .mapNotNull { key ->
                val prefix = "ENCHANTMENT_${key.uppercase(Locale.ROOT)}_"
                if (pricedIds.any { it.startsWith(prefix) }) return@mapNotNull null
                val price = bazaarUnitPrice("${prefix}1") ?: return@mapNotNull null
                val level = enchants.getInt(key).orElse(1)
                Part("${toTitleCase(key)} ${toRoman(level)}", price, ItemStack(Items.ENCHANTED_BOOK))
            }
        if (extra.isEmpty()) return groups to 0.0

        val added = extra.sumOf { it.value }
        val existing = groups.firstOrNull { it.label == "Enchantments" }
        val merged = if (existing != null) {
            groups.map {
                if (it !== existing) it
                else Group(it.label, it.value + added, (it.parts + extra).sortedByDescending { p -> p.value })
            }
        } else {
            groups + Group("Enchantments", added, extra.sortedByDescending { it.value })
        }
        return merged to added
    }

    private fun dominantSource(entries: List<CalculationEntry>): PriceSource {
        var bestId: String? = null
        var bestPrice = 0L
        fun visit(entry: CalculationEntry) {
            when (entry) {
                is ItemLikeEntry -> if (entry.price > bestPrice) { bestPrice = entry.price; bestId = entry.itemId }
                is GroupedEntry -> entry.entries.forEach(::visit)
                else -> Unit
            }
        }
        entries.forEach(::visit)
        return bestId?.let { sourceOf(it) } ?: PriceSource.UNKNOWN
    }

    private fun collectItemIds(entry: CalculationEntry, into: MutableSet<String>) {
        when (entry) {
            is ItemLikeEntry -> if (entry.price > 0L) into += entry.itemId
            is GroupedEntry -> entry.entries.forEach { collectItemIds(it, into) }
            else -> Unit
        }
    }

    /** Calculator groups that are purely cosmetic and can be excluded in the config. */
    private val cosmeticSources = setOf("APPLIED_DYE", "HELMET_SKIN")

    private fun includeSkinsAndDyes(): Boolean =
        runCatching { ChestMasterMod.configManager.config.valueSkinsAndDyes }.getOrDefault(true)

    /** Item icon from SkyBlockAPI's item repo, or null when the repo doesn't know the id. */
    private fun repoIcon(id: String): ItemStack? =
        iconOf(id, runCatching { ItemEntry(id, 0L, 1).itemStack }.getOrNull())

    /**
     * Icon for a breakdown row. Enchantments aren't items in the repo (SkyBlockAPI hands back a
     * barrier placeholder), so they get an enchanted book; other unknown ids get no icon.
     */
    private fun iconOf(itemId: String, stack: ItemStack?): ItemStack? {
        if (itemId.startsWith("ENCHANTMENT_")) return ItemStack(Items.ENCHANTED_BOOK)
        return stack?.takeIf {
            !it.isEmpty && !it.`is`(Items.BARRIER) && !it.hoverName.string.startsWith("Could not find", ignoreCase = true)
        }
    }

    /** Bazaar price (by mode) or lowest BIN for a market id. */
    private fun marketPrice(id: String): Double? =
        bazaarUnitPrice(id) ?: runCatching { LowestBinAPI.getLowestPrice(id) }.getOrNull()?.toDouble()?.takeIf { it > 0.0 }

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
            is ItemWithLimitEntry -> Part("${entry.amount}/${entry.limit} ${displayName(entry.itemId, entry.itemStack)}", value, iconOf(entry.itemId, entry.itemStack))
            is ItemEntry -> Part(withAmount(entry.amount, displayName(entry.itemId, entry.itemStack)), value, iconOf(entry.itemId, entry.itemStack))
            is ItemLikeEntry -> Part(displayName(entry.itemId, entry.itemStack), value, iconOf(entry.itemId, entry.itemStack))
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
