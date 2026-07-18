package com.chestmaster.valuation

import com.chestmaster.ChestMasterMod
import com.chestmaster.util.ItemUtils
import com.chestmaster.util.skyblockId
import com.google.gson.JsonParser
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object ItemValuator {
    enum class PriceSource(val label: String) {
        BAZAAR("Bazaar"),
        AUCTION("Auction"),
        NPC("NPC"),
        UNKNOWN("Unknown")
    }

    data class PriceComponent(
        val label: String,
        val value: Double
    )

    data class PriceBreakdown(
        val itemId: String,
        val found: Boolean,
        val stars: Int,
        val recombed: Boolean,
        val basePrice: Double,
        val starBonus: Double,
        val recombBonus: Double,
        val upgradeComponents: List<PriceComponent>,
        val upgradeBonus: Double,
        val totalPrice: Double
    )

    private data class PriceLookupResult(val found: Boolean, val price: Double)
    private data class PriceContext(
        val stars: Int,
        val recombed: Boolean,
        val upgradeComponents: List<PriceComponent>
    ) {
        val upgradeBonus: Double = upgradeComponents.sumOf { it.value }
    }

    private val keyIntPairRegex = Regex("\"?([A-Za-z0-9_]+)\"?\\s*:\\s*(-?\\d+)")
    private val dungeonEssenceIds = listOf(
        "ESSENCE_UNDEAD",
        "ESSENCE_WITHER",
        "ESSENCE_DRAGON",
        "ESSENCE_SPIDER",
        "ESSENCE_ICE",
        "ESSENCE_DIAMOND",
        "ESSENCE_GOLD"
    )
    private val abilityScrollNames = mapOf(
        "IMPLOSION_SCROLL" to "Implosion Scroll",
        "SHADOW_WARP_SCROLL" to "Shadow Warp Scroll",
        "WITHER_SHIELD_SCROLL" to "Wither Shield Scroll"
    )
    private val quotedAbilityScrollRegex = Regex("\"(IMPLOSION_SCROLL|SHADOW_WARP_SCROLL|WITHER_SHIELD_SCROLL)\"")
    private val quotedGemstoneIdRegex =
        Regex("\"((ROUGH|FLAWED|FINE|FLAWLESS|PERFECT)_([A-Z]+)_GEM(?:STONE)?)\"")
    private val starEssenceCostByStar = intArrayOf(100, 200, 400, 800, 1200)

    enum class PriceMode(val label: String) {
        SELL_OFFER("Sell Offer"),
        BUY_ORDER("Buy Order")
    }

    private val bazaarSellPrices = ConcurrentHashMap<String, Double>()
    private val bazaarBuyPrices = ConcurrentHashMap<String, Double>()
    private val lbinPrices = ConcurrentHashMap<String, Double>()
    private val npcPrices = ConcurrentHashMap<String, Double>()
    private val loggedIds = Collections.synchronizedSet(HashSet<String>())

    @Volatile
    private var lastBazaarUpdate: Long = 0
    @Volatile
    private var lastLbinUpdate: Long = 0
    @Volatile
    private var lastNpcUpdate: Long = 0
    @Volatile
    private var lastNpcSource: String = "none"
    @Volatile
    private var lbinUnavailable: Boolean = false

    private val pricesLoaded = AtomicBoolean(false)

    // Bumped whenever any price data actually changes (bazaar/LBIN/NPC/pet fetches).
    // The GUI watches this to invalidate its per-item price caches.
    private val dataEpoch = java.util.concurrent.atomic.AtomicLong(0)

    fun getDataEpoch(): Long = dataEpoch.get()
    // Track how many of the 3 sources have finished (success or failure).
    // Prices are marked loaded once all 3 complete regardless of individual outcome.
    private val pendingSourceCount = AtomicInteger(0)
    private const val TOTAL_PRICE_SOURCES = 3

    // Configurable via ModConfig.bazaarUpdateInterval (seconds); clamped to a sane range.
    private val cacheDurationMs: Long
        get() = runCatching { ChestMasterMod.configManager.config.bazaarUpdateInterval }
            .getOrDefault(300)
            .coerceIn(60, 3600) * 1000L

    private val httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

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

    fun getTotalValue(stack: ItemStack): Double {
        if (stack.isEmpty) return 0.0
        val price = getPrice(stack)
        return (if (price < 0) 0.0 else price) * stack.count
    }

    fun getPrice(stack: ItemStack): Double {
        if (stack.isEmpty) return 0.0
        val nbt = ItemUtils.getNbtString(stack)
        val idHint = ItemUtils.normalizeSkyblockId(stack.skyblockId)
        return getPriceFromNbt(idHint, nbt)
    }

    fun getPriceFromNbt(itemIdHint: String?, nbtString: String): Double {
        return getPriceBreakdownFromNbt(itemIdHint, nbtString).totalPrice
    }

    fun getPriceBreakdownFromNbt(itemIdHint: String?, nbtString: String): PriceBreakdown {
        val normalizedHint = ItemUtils.normalizeSkyblockId(itemIdHint)
        val extraAttributes = ItemUtils.extractExtraAttributesFromNbtString(nbtString)

        // Pets carry the generic id "PET"; the market key is "TYPE;TIER_INDEX" from petInfo.
        val petKey = ItemUtils.extractPetKey(extraAttributes)
        val rawId = ItemUtils.extractSkyblockIdFromNbtString(nbtString)
            ?: normalizedHint
            ?: "UNKNOWN"
        // Attribute shards carry the generic id "ATTRIBUTE_SHARD"; the Bazaar key
        // ("SHARD_NIGHT_SQUID") is derived from the item's display name.
        val shardKey = if (rawId == "ATTRIBUTE_SHARD") ItemUtils.shardMarketKeyFromNbt(nbtString) else null
        val id = petKey ?: shardKey ?: rawId
        val context = PriceContext(
            stars = extraAttributes?.let { getStars(it) } ?: 0,
            recombed = extraAttributes?.let { isRecombed(it) } ?: false,
            upgradeComponents = buildUpgradeComponents(extraAttributes)
        )

        val breakdown = evaluatePriceBreakdown(id, context)
        logPricingOnce(breakdown)
        return breakdown
    }

    private fun evaluatePriceBreakdown(id: String, context: PriceContext): PriceBreakdown {
        val lookup = lookupPrice(id)
        val basePrice = lookup.price
        val found = lookup.found
        val upgradeBonus = context.upgradeBonus
        val effectiveFound = found || upgradeBonus > 0.0

        if (!effectiveFound && !pricesLoaded.get()) {
            return PriceBreakdown(
                itemId = id,
                found = false,
                stars = context.stars,
                recombed = context.recombed,
                basePrice = 0.0,
                starBonus = 0.0,
                recombBonus = 0.0,
                upgradeComponents = context.upgradeComponents,
                upgradeBonus = upgradeBonus,
                totalPrice = -1.0
            )
        }

        val starBonus = if (found && basePrice > 0.0 && context.stars > 0) {
            estimateStarBonus(context.stars)
        } else {
            0.0
        }

        val recombBonus = if (found && context.recombed) {
            lookupFirstPositivePrice("RECOMBOBULATOR_3000", "RECOMBOBULATOR")
        } else {
            0.0
        }

        val total = basePrice + starBonus + recombBonus + upgradeBonus

        return PriceBreakdown(
            itemId = id,
            found = effectiveFound,
            stars = context.stars,
            recombed = context.recombed,
            basePrice = basePrice,
            starBonus = starBonus,
            recombBonus = recombBonus,
            upgradeComponents = context.upgradeComponents,
            upgradeBonus = upgradeBonus,
            totalPrice = total
        )
    }

    private fun logPricingOnce(breakdown: PriceBreakdown) {
        if (!ChestMasterMod.isVerboseLogging()) return
        if (!pricesLoaded.get()) return
        if (loggedIds.contains(breakdown.itemId)) return

        ChestMasterMod.LOGGER.debug(
            "[DEBUG] Pricing: ID=${breakdown.itemId}, " +
                "Found=${breakdown.found}, " +
                "Stars=${breakdown.stars}, " +
                "Recombed=${breakdown.recombed}, " +
                "Base=${breakdown.basePrice}, " +
                "StarsBonus=${breakdown.starBonus}, " +
                "Upgrades=${breakdown.upgradeBonus}, " +
                "Price=${breakdown.totalPrice}"
        )
        loggedIds.add(breakdown.itemId)
    }

    fun getPriceBySkyblockId(id: String): Double {
        val normalizedId = ItemUtils.normalizeSkyblockId(id) ?: return 0.0
        val lookup = lookupPrice(normalizedId)
        if (!lookup.found && !pricesLoaded.get()) return -1.0
        return lookup.price
    }

    private fun lookupPrice(id: String): PriceLookupResult {
        for (candidate in buildCandidateIds(id)) {
            if (bazaarSellPrices.containsKey(candidate)) {
                val price = if (currentMode == PriceMode.SELL_OFFER)
                    bazaarBuyPrices.getOrDefault(candidate, 0.0)
                else
                    bazaarSellPrices.getOrDefault(candidate, 0.0)
                return PriceLookupResult(found = true, price = price)
            }

            if (lbinPrices.containsKey(candidate)) {
                return PriceLookupResult(found = true, price = lbinPrices.getOrDefault(candidate, 0.0))
            }

            if (npcPrices.containsKey(candidate)) {
                return PriceLookupResult(found = true, price = npcPrices.getOrDefault(candidate, 0.0))
            }
        }

        return PriceLookupResult(found = false, price = 0.0)
    }

    private fun buildCandidateIds(id: String): LinkedHashSet<String> {
        val candidates = LinkedHashSet<String>()
        val raw = id.trim()
        if (raw.isBlank()) return candidates

        candidates.add(raw)

        val upper = raw.uppercase(Locale.ROOT)
        candidates.add(upper)

        if (raw.endsWith(";0")) {
            candidates.add(raw.removeSuffix(";0"))
        }
        if (upper.endsWith(";0")) {
            candidates.add(upper.removeSuffix(";0"))
        }

        // Support vanilla namespaced ids (e.g. minecraft:apple) against SkyBlock/NPC ids (APPLE).
        if (raw.matches(Regex("^[a-z0-9_.-]+:.+$"))) {
            val path = raw.substringAfter(':')
            if (path.isNotBlank()) {
                candidates.add(path)

                val pathUpper = path.uppercase(Locale.ROOT)
                candidates.add(pathUpper)

                if (path.endsWith(";0")) {
                    candidates.add(path.removeSuffix(";0"))
                }
                if (pathUpper.endsWith(";0")) {
                    candidates.add(pathUpper.removeSuffix(";0"))
                }
            }
        }
        return candidates
    }

    fun getPriceSourceForId(id: String?): PriceSource {
        val normalizedId = ItemUtils.normalizeSkyblockId(id) ?: return PriceSource.UNKNOWN
        for (candidate in buildCandidateIds(normalizedId)) {
            val bazaarPrice = bazaarSellPrices[candidate]
            if (bazaarPrice != null && bazaarPrice > 0.0) return PriceSource.BAZAAR

            val lbinPrice = lbinPrices[candidate]
            if (lbinPrice != null && lbinPrice > 0.0) return PriceSource.AUCTION

            val npcPrice = npcPrices[candidate]
            if (npcPrice != null && npcPrice > 0.0) return PriceSource.NPC
        }
        return PriceSource.UNKNOWN
    }

    private fun buildUpgradeComponents(extraAttributes: CompoundTag?): List<PriceComponent> {
        if (extraAttributes == null) return emptyList()

        val components = mutableListOf<PriceComponent>()

        val hotPotatoCount = extraAttributes.getIntOr("hot_potato_count", 0).coerceAtLeast(0)
        if (hotPotatoCount > 0) {
            val normalHotPotatoes = hotPotatoCount.coerceAtMost(10)
            val fumingPotatoes = (hotPotatoCount - 10).coerceAtLeast(0)

            if (normalHotPotatoes > 0) {
                val value = normalHotPotatoes * lookupFirstPositivePrice("HOT_POTATO_BOOK")
                if (value > 0.0) components.add(PriceComponent("Hot Potato Books x$normalHotPotatoes", value))
            }

            if (fumingPotatoes > 0) {
                val value = fumingPotatoes * lookupFirstPositivePrice("FUMING_POTATO_BOOK")
                if (value > 0.0) components.add(PriceComponent("Fuming Potato Books x$fumingPotatoes", value))
            }
        }

        val artOfWarCount = extraAttributes.getIntOr("art_of_war_count", 0).coerceAtLeast(0)
        if (artOfWarCount > 0) {
            val value = artOfWarCount * lookupFirstPositivePrice("THE_ART_OF_WAR")
            if (value > 0.0) components.add(PriceComponent("The Art of War x$artOfWarCount", value))
        }

        val farmingForDummiesCount = extraAttributes.getIntOr("farming_for_dummies_count", 0).coerceAtLeast(0)
        if (farmingForDummiesCount > 0) {
            val value = farmingForDummiesCount * lookupFirstPositivePrice("FARMING_FOR_DUMMIES")
            if (value > 0.0) components.add(PriceComponent("Farming for Dummies x$farmingForDummiesCount", value))
        }

        if (extraAttributes.getBooleanOr("ethermerge", false) || extraAttributes.getIntOr("ethermerge", 0) > 0) {
            val value = lookupFirstPositivePrice("ETHERWARP_CONDUIT", "ETHERWARP_MERGER")
            if (value > 0.0) components.add(PriceComponent("Etherwarp", value))
        }

        val transmissionTunerCount = listOf(
            extraAttributes.getIntOr("tuned_transmission", 0),
            extraAttributes.getIntOr("transmission_tuner", 0),
            extraAttributes.getIntOr("transmission_tuner_count", 0)
        ).maxOrNull()?.coerceIn(0, 4) ?: 0

        if (transmissionTunerCount > 0) {
            val value = transmissionTunerCount * lookupFirstPositivePrice("TRANSMISSION_TUNER")
            if (value > 0.0) components.add(PriceComponent("Transmission Tuners x$transmissionTunerCount", value))
        }

        components.addAll(getEnchantmentsComponents(extraAttributes.getCompound("enchantments").orElse(null)?.toString()))
        components.addAll(getRunesComponents(extraAttributes.getCompound("runes").orElse(null)?.toString()))
        components.addAll(getAbilityScrollComponents(extraAttributes))
        components.addAll(getGemstoneComponents(extraAttributes))

        return components
    }

    private fun getAbilityScrollComponents(extraAttributes: CompoundTag): List<PriceComponent> {
        val counts = LinkedHashMap<String, Int>()

        val scrollKeys = listOf("ability_scroll", "ability_scrolls", "scrolls")
        for (key in scrollKeys) {
            val list = extraAttributes.getList(key).orElse(null) ?: continue
            for (index in 0 until list.size) {
                val raw = list.getString(index).orElse(null) ?: continue
                val normalized = normalizeAbilityScrollId(raw) ?: continue
                counts[normalized] = (counts[normalized] ?: 0) + 1
            }
        }

        val rawTag = extraAttributes.toString().uppercase(Locale.ROOT)
        for (match in quotedAbilityScrollRegex.findAll(rawTag)) {
            val id = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (id.isNotBlank()) {
                counts[id] = maxOf(counts[id] ?: 0, 1)
            }
        }

        if (counts.isEmpty()) return emptyList()

        return counts.entries
            .sortedBy { it.key }
            .mapNotNull { (id, count) ->
                val unitPrice = lookupFirstPositivePrice(id)
                if (unitPrice <= 0.0) return@mapNotNull null

                val value = unitPrice * count.coerceAtLeast(1)
                val baseLabel = abilityScrollNames[id] ?: toTitleCase(id.removeSuffix("_SCROLL")) + " Scroll"
                val label = if (count > 1) "$baseLabel x$count" else baseLabel
                PriceComponent(label, value)
            }
    }

    private fun getGemstoneComponents(extraAttributes: CompoundTag): List<PriceComponent> {
        val matches = quotedGemstoneIdRegex.findAll(extraAttributes.toString().uppercase(Locale.ROOT))
        val counts = LinkedHashMap<String, Int>()
        for (match in matches) {
            val rawId = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (rawId.isBlank()) continue

            val canonicalId = when {
                rawId.endsWith("_GEMSTONE") -> rawId.removeSuffix("STONE")
                rawId.endsWith("_GEM") -> rawId
                else -> "${rawId}_GEM"
            }

            counts[canonicalId] = (counts[canonicalId] ?: 0) + 1
        }

        if (counts.isEmpty()) return emptyList()

        val components = mutableListOf<PriceComponent>()
        for ((gemId, count) in counts.entries.sortedBy { it.key }) {
            val unitPrice = lookupFirstPositivePrice(gemId, "${gemId}STONE")
            if (unitPrice <= 0.0) continue

            val value = unitPrice * count.coerceAtLeast(1)
            val label = formatGemstoneLabel(gemId, count)
            components.add(PriceComponent(label, value))
        }
        return components
    }

    private fun normalizeAbilityScrollId(raw: String): String? {
        val normalized = raw.trim().uppercase(Locale.ROOT)
            .replace(' ', '_')
            .replace('-', '_')

        return when {
            normalized.contains("IMPLOSION") -> "IMPLOSION_SCROLL"
            normalized.contains("SHADOW_WARP") || normalized.contains("SHADOWWARP") -> "SHADOW_WARP_SCROLL"
            normalized.contains("WITHER_SHIELD") || normalized.contains("WITHERSHIELD") -> "WITHER_SHIELD_SCROLL"
            else -> null
        }
    }

    private fun formatGemstoneLabel(gemId: String, count: Int): String {
        val normalized = gemId.removeSuffix("_GEMSTONE").removeSuffix("_GEM")
        val quality = normalized.substringBefore('_').ifBlank { "Gem" }
        val type = normalized.substringAfter('_', "Gem")

        val label = "${toTitleCase(quality)} ${toTitleCase(type)} Gemstone"
        return if (count > 1) "$label x$count" else label
    }

    private fun getEnchantmentsComponents(enchantmentsTag: String?): List<PriceComponent> {
        if (enchantmentsTag.isNullOrBlank()) return emptyList()

        val components = mutableListOf<PriceComponent>()
        for ((name, level) in parseKeyIntPairs(enchantmentsTag)) {
            if (level <= 0) continue

            val upperName = name.uppercase(Locale.ROOT)
            val candidates = LinkedHashSet<String>()
            candidates.add("ENCHANTMENT_${upperName}_$level")
            candidates.add("ENCHANTMENT_${upperName};$level")
            candidates.add("${upperName}_$level")
            candidates.add("${upperName};$level")

            if (name.startsWith("ultimate_", ignoreCase = true)) {
                val ultimateName = name.replaceFirst(Regex("^ultimate_", RegexOption.IGNORE_CASE), "")
                    .uppercase(Locale.ROOT)
                candidates.add("ULTIMATE_${ultimateName}_$level")
                candidates.add("ULTIMATE_${ultimateName};$level")
            }

            val value = lookupFirstPositivePrice(*candidates.toTypedArray())
            if (value <= 0.0) continue

            val label = "${toTitleCase(name)} ${toRoman(level)}"
            components.add(PriceComponent(label, value))
        }

        return components
    }

    private fun getRunesComponents(runesTag: String?): List<PriceComponent> {
        if (runesTag.isNullOrBlank()) return emptyList()

        val components = mutableListOf<PriceComponent>()
        for ((runeName, level) in parseKeyIntPairs(runesTag)) {
            if (level <= 0) continue

            val upperName = runeName.uppercase(Locale.ROOT)
            val value = lookupFirstPositivePrice(
                "RUNE;$upperName;$level",
                "${upperName}_RUNE;$level",
                "RUNE_${upperName}_$level",
                "${upperName}_RUNE_$level"
            )
            if (value <= 0.0) continue

            val label = "${toTitleCase(runeName)} Rune ${toRoman(level)}"
            components.add(PriceComponent(label, value))
        }

        return components
    }

    private fun getStars(extraAttributes: CompoundTag): Int {
        return extraAttributes.getIntOr(
            "dungeon_item_level",
            extraAttributes.getIntOr("upgrade_level", 0)
        ).coerceAtLeast(0)
    }

    private fun isRecombed(extraAttributes: CompoundTag): Boolean {
        return extraAttributes.getIntOr("rarity_upgrades", 0) > 0 ||
            extraAttributes.getBooleanOr("recombobulated", false)
    }

    private fun estimateStarBonus(stars: Int): Double {
        if (stars <= 0) return 0.0
        val essenceUnitPrice = estimateDungeonEssenceUnitPrice()
        if (essenceUnitPrice <= 0.0) return 0.0
        val totalEssence = getTotalStarEssenceCost(stars)
        if (totalEssence <= 0) return 0.0
        return totalEssence * essenceUnitPrice
    }

    private fun getTotalStarEssenceCost(stars: Int): Int {
        val clamped = stars.coerceAtMost(10)
        var total = 0
        for (index in 0 until clamped) {
            total += if (index < starEssenceCostByStar.size) {
                starEssenceCostByStar[index]
            } else {
                // Master stars are more expensive than normal stars.
                starEssenceCostByStar.last() + 400 * (index - starEssenceCostByStar.size + 1)
            }
        }
        return total
    }

    private fun estimateDungeonEssenceUnitPrice(): Double {
        val values = dungeonEssenceIds.mapNotNull { id ->
            val lookup = lookupPrice(id)
            if (lookup.found && lookup.price > 0.0) lookup.price else null
        }
        if (values.isEmpty()) return 0.0

        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        } else {
            sorted[mid]
        }
    }

    private fun parseKeyIntPairs(rawTag: String): List<Pair<String, Int>> {
        return keyIntPairRegex.findAll(rawTag)
            .mapNotNull { match ->
                val key = match.groupValues.getOrNull(1)?.trim().orEmpty()
                val value = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return@mapNotNull null
                if (key.isBlank()) return@mapNotNull null
                key to value
            }
            .toList()
    }

    private fun lookupFirstPositivePrice(vararg ids: String): Double {
        for (raw in ids) {
            val normalized = ItemUtils.normalizeSkyblockId(raw) ?: continue
            val lookup = lookupPrice(normalized)
            if (lookup.found && lookup.price > 0.0) {
                return lookup.price
            }
        }
        return 0.0
    }

    private fun toTitleCase(raw: String): String {
        return raw
            .replace('_', ' ')
            .lowercase(Locale.ROOT)
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { token ->
                token.replaceFirstChar { ch ->
                    if (ch.isLowerCase()) ch.titlecase(Locale.ROOT) else ch.toString()
                }
            }
    }

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

    fun updateAllPrices() {
        // Mark all 3 sources as pending before launching async loads.
        // CAS from 0 guards against overlapping refreshes: a second call while sources are
        // still in flight would reset the counter and flip pricesLoaded=true prematurely.
        if (!pendingSourceCount.compareAndSet(0, TOTAL_PRICE_SOURCES)) return
        pricesLoaded.set(false)
        lbinUnavailable = false
        loggedIds.clear()
        updateBazaarPrices()
        updateLbinPrices()
        updateNpcPrices()
    }

    private fun updateBazaarPrices() {
        if (System.currentTimeMillis() - lastBazaarUpdate < cacheDurationMs && bazaarSellPrices.isNotEmpty()) {
            markSourceComplete()
            return
        }
        CompletableFuture.runAsync {
            try {
                val request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.hypixel.net/skyblock/bazaar"))
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofSeconds(15))
                    .GET().build()
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() == 200) {
                    val json = JsonParser.parseString(response.body()).asJsonObject
                    if (json.get("success").asBoolean) {
                        val products = json.getAsJsonObject("products")
                        bazaarSellPrices.clear()
                        bazaarBuyPrices.clear()
                        for (key in products.keySet()) {
                            val status = products.getAsJsonObject(key).getAsJsonObject("quick_status")
                            bazaarSellPrices[key] = status.get("sellPrice").asDouble
                            bazaarBuyPrices[key] = status.get("buyPrice").asDouble
                        }
                        lastBazaarUpdate = System.currentTimeMillis()
                        dataEpoch.incrementAndGet()
                    }
                }
            } catch (e: Exception) {
                logVerboseWarn("Bazaar update failed: ${e.message}")
            } finally {
                markSourceComplete()
            }
        }
    }

    private fun updateLbinPrices() {
        if (System.currentTimeMillis() - lastLbinUpdate < cacheDurationMs && lbinPrices.isNotEmpty()) {
            // Bulk data is still fresh, but pets scanned since the last refresh may be missing.
            CompletableFuture.runAsync {
                try {
                    enrichMissingPricesFromCoflnet()
                } catch (e: Exception) {
                    ChestMasterMod.LOGGER.warn("[ChestMaster] Coflnet price enrichment failed: ${e.javaClass.simpleName}: ${e.message}")
                } finally {
                    markSourceComplete()
                }
            }
            return
        }
        CompletableFuture.runAsync {
            // Each entry: URL to fetch + parser that knows its response shape.
            // moulberry.codes/lowestbin.json         → flat JSON object {id: price}  (primary, community LBIN dump)
            // hysky.de/api/auctions/lowestbins       → flat JSON object {id: price}  (Skyblocker static API, reliable)
            // sky.coflnet.com/api/item/price/*/bins  → queried per-item; not suitable as bulk fallback
            val lbinSources = listOf(
                "https://moulberry.codes/lowestbin.json" to ::parseLbinObject,
                "https://hysky.de/api/auctions/lowestbins" to ::parseLbinObject
            )
            var loaded = false
            for ((url, parser) in lbinSources) {
                try {
                    val request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("User-Agent", USER_AGENT)
                        .timeout(Duration.ofSeconds(15))
                        .GET().build()
                    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                    if (response.statusCode() == 200) {
                        val parsed = parser(response.body())
                        if (parsed.isNotEmpty()) {
                            lbinPrices.clear()
                            lbinPrices.putAll(parsed)
                            lastLbinUpdate = System.currentTimeMillis()
                            dataEpoch.incrementAndGet()
                            loaded = true
                            ChestMasterMod.LOGGER.info("[ChestMaster] LBIN loaded ${parsed.size} entries from $url")
                            break
                        } else {
                            ChestMasterMod.LOGGER.warn("[ChestMaster] LBIN source $url returned 0 usable entries (body len=${response.body().length})")
                        }
                    } else {
                        ChestMasterMod.LOGGER.warn("[ChestMaster] LBIN source $url returned HTTP ${response.statusCode()}")
                    }
                } catch (e: Exception) {
                    ChestMasterMod.LOGGER.warn("[ChestMaster] LBIN source $url failed: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
            if (!loaded) {
                lbinUnavailable = true
                ChestMasterMod.LOGGER.warn("[ChestMaster] All LBIN sources unavailable; auction prices will show as Unknown.")
            }
            // Bulk LBIN dumps (hysky.de) contain no pets — fill the gap per pet via Coflnet.
            try {
                enrichMissingPricesFromCoflnet()
            } catch (e: Exception) {
                ChestMasterMod.LOGGER.warn("[ChestMaster] Coflnet price enrichment failed: ${e.javaClass.simpleName}: ${e.message}")
            }
            markSourceComplete()
        }
    }

    /**
     * Fetches lowest-bin prices from the Coflnet API for items the bulk sources don't cover:
     * pets (keyed "TYPE;TIER_INDEX") and any other stored SkyBlock ids unknown to
     * Bazaar/hysky/NPC data. hysky.de omits pets and several AH items entirely, and
     * moulberry.codes is frequently down, so these would otherwise always price at 0.
     */
    private const val MAX_COFLNET_REQUESTS_PER_REFRESH = 40

    // Coflnet rate-limits rapid-fire requests with HTTP 403 — space them out and back off.
    private const val COFLNET_REQUEST_SPACING_MS = 350L
    private const val COFLNET_RATE_LIMIT_BACKOFF_MS = 2000L

    // Ids Coflnet reported as unknown or unlisted this session — pointless to re-request.
    private val coflnetDeadIds = Collections.synchronizedSet(HashSet<String>())

    private data class CoflnetTarget(val storeKey: String, val itemTag: String, val query: String?)

    private fun enrichMissingPricesFromCoflnet() {
        val targets = LinkedHashMap<String, CoflnetTarget>()

        // Pets: bulk LBIN keys are "TYPE;TIER_INDEX"; Coflnet prices them per rarity.
        try {
            for (nbt in ChestMasterMod.db.listDistinctPetNbts()) {
                val petKey = ItemUtils.extractPetKey(ItemUtils.extractExtraAttributesFromNbtString(nbt)) ?: continue
                val rarity = ItemUtils.petTierNameFromKey(petKey) ?: continue
                targets[petKey] = CoflnetTarget(petKey, "PET_${petKey.substringBefore(';')}", "Rarity=$rarity")
            }
        } catch (e: Exception) {
            ChestMasterMod.LOGGER.warn("[ChestMaster] Coflnet enrichment failed to read pets from DB: ${e.message}")
        }

        // Any other stored SkyBlock ids (e.g. AH items missing from the hysky dump).
        try {
            for (id in ChestMasterMod.db.listDistinctSkyblockItemIds()) {
                if (id.contains(':')) continue // vanilla ids, not SkyBlock market tags
                if (id == "PET" || id == "ATTRIBUTE_SHARD" || id == "UNKNOWN") continue
                targets.putIfAbsent(id, CoflnetTarget(id, id, null))
            }
        } catch (e: Exception) {
            ChestMasterMod.LOGGER.warn("[ChestMaster] Coflnet enrichment failed to read item ids from DB: ${e.message}")
        }

        val missing = targets.values
            .filter { target ->
                target.storeKey !in coflnetDeadIds &&
                    (lbinPrices[target.storeKey] ?: 0.0) <= 0.0 &&
                    !lookupPrice(target.storeKey).found
            }
            .take(MAX_COFLNET_REQUESTS_PER_REFRESH)
        if (missing.isEmpty()) return

        var loadedCount = 0
        var failedCount = 0
        var lastError: String? = null

        loop@ for ((index, target) in missing.withIndex()) {
            if (index > 0) Thread.sleep(COFLNET_REQUEST_SPACING_MS)

            var response = sendCoflnetRequest(target)
            if (response != null && response.statusCode() == 403) {
                Thread.sleep(COFLNET_RATE_LIMIT_BACKOFF_MS)
                response = sendCoflnetRequest(target)
            }

            when {
                response == null -> {
                    failedCount += 1
                    lastError = "request error for ${target.storeKey}"
                }

                response.statusCode() == 200 -> {
                    val lowest = runCatching {
                        JsonParser.parseString(response.body())
                            .takeIf { it.isJsonObject }
                            ?.asJsonObject?.get("lowest").asDoubleOrNull()
                    }.getOrNull()
                    if (lowest != null && lowest > 0.0) {
                        lbinPrices[target.storeKey] = lowest
                        loadedCount += 1
                    } else {
                        // Known item but no listings — don't re-request this session.
                        coflnetDeadIds.add(target.storeKey)
                    }
                }

                response.statusCode() == 403 -> {
                    // Still rate-limited after backing off: give up for this round,
                    // the remaining ids are retried on the next refresh / GUI open.
                    failedCount += missing.size - index
                    lastError = "HTTP 403 (rate limited), aborting round"
                    break@loop
                }

                response.statusCode() in 400..499 -> {
                    // Unknown/untradeable tag — remember and stop asking.
                    coflnetDeadIds.add(target.storeKey)
                }

                else -> {
                    failedCount += 1
                    lastError = "HTTP ${response.statusCode()} for ${target.storeKey}"
                }
            }
        }

        if (loadedCount > 0) {
            dataEpoch.incrementAndGet()
            ChestMasterMod.LOGGER.info("[ChestMaster] Loaded $loadedCount market price(s) from Coflnet")
        }
        // Always log failures: silent price gaps are impossible to diagnose from user reports.
        if (failedCount > 0) {
            ChestMasterMod.LOGGER.warn(
                "[ChestMaster] $failedCount Coflnet price request(s) failed (last: $lastError)"
            )
        }
    }

    private fun sendCoflnetRequest(target: CoflnetTarget): java.net.http.HttpResponse<String>? {
        return try {
            val url = "https://sky.coflnet.com/api/item/price/${target.itemTag}/bin" +
                (target.query?.let { "?$it" } ?: "")
            httpClient.send(buildGetRequest(url), HttpResponse.BodyHandlers.ofString())
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses a flat JSON object: {"ITEM_ID": price, ...}
     * Used for moulberry.codes/lowestbin.json
     */
    private fun parseLbinObject(body: String): Map<String, Double> {
        val result = HashMap<String, Double>()
        val element = runCatching { JsonParser.parseString(body) }.getOrNull() ?: return result
        if (!element.isJsonObject) return result
        for (entry in element.asJsonObject.entrySet()) {
            val price = entry.value.asDoubleOrNull() ?: continue
            if (price > 0 && entry.key.isNotBlank()) result[entry.key] = price
        }
        return result
    }

    /**
     * Parses either:
     *   - A flat JSON object: {"ITEM_ID": price, ...}
     *   - A JSON array of auction objects: [{tag|id: "ITEM_ID", price|lowestBin: price}, ...]
     * Used for sky.coflnet.com which may return either format.
     */
    private fun parseLbinFlexible(body: String): Map<String, Double> {
        val result = HashMap<String, Double>()
        val element = runCatching { JsonParser.parseString(body) }.getOrNull() ?: return result
        when {
            element.isJsonObject -> {
                for (entry in element.asJsonObject.entrySet()) {
                    val price = entry.value.asDoubleOrNull() ?: continue
                    if (price > 0 && entry.key.isNotBlank()) result[entry.key] = price
                }
            }
            element.isJsonArray -> {
                for (item in element.asJsonArray) {
                    if (!item.isJsonObject) continue
                    val obj = item.asJsonObject
                    // Try common "tag" field names used by different API responses
                    val tag = obj.get("tag").asStringOrNull()
                        ?: obj.get("itemTag").asStringOrNull()
                        ?: obj.get("id").asStringOrNull()
                        ?: obj.get("itemId").asStringOrNull()
                        ?: continue
                    // Try common "price" field names
                    val price = obj.get("price").asDoubleOrNull()
                        ?: obj.get("lowestBin").asDoubleOrNull()
                        ?: obj.get("lowest_bin").asDoubleOrNull()
                        ?: obj.get("lbin").asDoubleOrNull()
                        ?: continue
                    if (price > 0 && tag.isNotBlank()) result[tag] = price
                }
            }
        }
        return result
    }

    private fun updateNpcPrices() {
        if (System.currentTimeMillis() - lastNpcUpdate < cacheDurationMs && npcPrices.isNotEmpty()) {
            markSourceComplete()
            return
        }
        CompletableFuture.runAsync {
            try {
                val loaded = loadNpcPricesFromConfiguredSources()
                if (!loaded) logVerboseWarn("NPC update failed: all configured NPC sources are unavailable.")
            } catch (e: Exception) {
                logVerboseWarn("NPC update failed: ${e.message}")
            } finally {
                markSourceComplete()
            }
        }
    }

    /** Decrements the pending-source counter; marks prices loaded when all sources have finished. */
    private fun markSourceComplete() {
        if (pendingSourceCount.decrementAndGet() <= 0) {
            pricesLoaded.set(true)
        }
    }

    private fun loadNpcPricesFromConfiguredSources(): Boolean {
        val sources = listOf(
            "Hypixel items API" to { loadNpcPricesFromHypixelItemsApi() },
            "Skyblocker NPC API" to { loadNpcPricesFromGenericJsonSource("https://hysky.de/api/npcprice", "Skyblocker NPC API") },
            "NEU constants" to { loadNpcPricesFromLegacyNeuConstants() }
        )

        for ((name, loader) in sources) {
            try {
                logVerboseInfo("NPC update: trying $name")
                if (loader()) {
                    logVerboseInfo("NPC update: loaded ${npcPrices.size} entries from $name")
                    return true
                }
            } catch (e: Exception) {
                logVerboseWarn("NPC source '$name' failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        return false
    }

    private fun loadNpcPricesFromHypixelItemsApi(): Boolean {
        val urls = listOf(
            "https://api.hypixel.net/v2/resources/skyblock/items",
            "https://api.hypixel.net/resources/skyblock/items"
        )

        for (url in urls) {
            val request = buildGetRequest(url)
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                logVerboseWarn("NPC price request returned HTTP ${response.statusCode()} for $url")
                continue
            }

            val root = JsonParser.parseString(response.body())
            if (!root.isJsonObject) {
                logVerboseWarn("NPC price source returned non-object JSON for $url")
                continue
            }

            val json = root.asJsonObject
            if (!json.get("success").asBooleanOrNull().orFalse()) {
                logVerboseWarn("NPC price source returned success=false for $url")
                continue
            }

            val itemsElement = json.get("items")
            if (itemsElement == null || !itemsElement.isJsonArray) {
                logVerboseWarn("NPC price source did not contain items array for $url")
                continue
            }

            val loadedPrices = HashMap<String, Double>()
            for (element in itemsElement.asJsonArray) {
                if (!element.isJsonObject) continue
                val itemObj = element.asJsonObject
                val id = extractIdFromObject(itemObj) ?: continue
                val npcSell = extractPriceFromObject(itemObj) ?: continue
                putNpcPrice(loadedPrices, id, npcSell)
            }

            if (applyNpcPrices(loadedPrices, "Hypixel items API ($url)")) {
                return true
            }
        }
        return false
    }

    private fun loadNpcPricesFromLegacyNeuConstants(): Boolean {
        val npcUrls = listOf(
            "https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/master/constants/npc_prices.json",
            "https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/main/constants/npc_prices.json",
            "https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/master/constants/npcprice.json",
            "https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/main/constants/npcprice.json",
            "https://cdn.jsdelivr.net/gh/NotEnoughUpdates/NotEnoughUpdates-REPO@master/constants/npc_prices.json",
            "https://cdn.jsdelivr.net/gh/NotEnoughUpdates/NotEnoughUpdates-REPO@main/constants/npc_prices.json"
        )
        for (url in npcUrls) {
            if (loadNpcPricesFromGenericJsonSource(url, "NEU constants")) {
                return true
            }
        }
        return false
    }

    private fun loadNpcPricesFromGenericJsonSource(url: String, sourceName: String): Boolean {
        val request = buildGetRequest(url)
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            logVerboseWarn("NPC price request returned HTTP ${response.statusCode()} for $url")
            return false
        }

        val root = JsonParser.parseString(response.body())
        val loadedPrices = parseNpcPricesFromJson(root)
        if (!applyNpcPrices(loadedPrices, "$sourceName ($url)")) {
            return false
        }
        return true
    }

    private fun parseNpcPricesFromJson(root: com.google.gson.JsonElement): HashMap<String, Double> {
        val loadedPrices = HashMap<String, Double>()
        when {
            root.isJsonArray -> extractNpcPricesFromArray(root.asJsonArray, loadedPrices)
            root.isJsonObject -> {
                val obj = root.asJsonObject
                extractNpcPricesFromObject(obj, loadedPrices)

                val containerKeys = listOf(
                    "data",
                    "items",
                    "npc",
                    "prices",
                    "values",
                    "result",
                    "npc_prices",
                    "npcPrices",
                    "npcprice"
                )
                for (key in containerKeys) {
                    val nested = obj.get(key) ?: continue
                    when {
                        nested.isJsonArray -> extractNpcPricesFromArray(nested.asJsonArray, loadedPrices)
                        nested.isJsonObject -> extractNpcPricesFromObject(nested.asJsonObject, loadedPrices)
                    }
                }
            }
        }
        return loadedPrices
    }

    private fun extractNpcPricesFromArray(
        array: com.google.gson.JsonArray,
        loadedPrices: MutableMap<String, Double>
    ) {
        for (element in array) {
            when {
                element.isJsonObject -> {
                    val obj = element.asJsonObject
                    val id = extractIdFromObject(obj)
                    val price = extractPriceFromObject(obj)
                    if (id != null && price != null) {
                        putNpcPrice(loadedPrices, id, price)
                    }

                    // Nested shapes: {"item":{"id":"APPLE","npc_sell_price":3.0}}
                    val nestedItem = obj.get("item")
                    if (nestedItem != null && nestedItem.isJsonObject) {
                        val nestedObj = nestedItem.asJsonObject
                        val nestedId = extractIdFromObject(nestedObj)
                        val nestedPrice = extractPriceFromObject(nestedObj)
                        if (nestedId != null && nestedPrice != null) {
                            putNpcPrice(loadedPrices, nestedId, nestedPrice)
                        }
                    }
                }

                element.isJsonArray -> extractNpcPricesFromArray(element.asJsonArray, loadedPrices)
            }
        }
    }

    private fun extractNpcPricesFromObject(
        obj: com.google.gson.JsonObject,
        loadedPrices: MutableMap<String, Double>
    ) {
        for (entry in obj.entrySet()) {
            val key = entry.key
            val value = entry.value

            // Common shape: {"APPLE": 3.0}
            value.asDoubleOrNull()?.let { price ->
                if (looksLikeItemId(key)) {
                    putNpcPrice(loadedPrices, key, price)
                }
            }

            // Common shape: {"APPLE":{"npc_sell_price":3.0}}
            if (value.isJsonObject) {
                val nested = value.asJsonObject
                val nestedPrice = extractPriceFromObject(nested)
                if (nestedPrice != null) {
                    val nestedId = extractIdFromObject(nested)
                    when {
                        nestedId != null -> putNpcPrice(loadedPrices, nestedId, nestedPrice)
                        looksLikeItemId(key) -> putNpcPrice(loadedPrices, key, nestedPrice)
                    }
                }
            } else if (value.isJsonArray) {
                extractNpcPricesFromArray(value.asJsonArray, loadedPrices)
            }
        }
    }

    private fun extractIdFromObject(obj: com.google.gson.JsonObject): String? {
        val keys = listOf("id", "item_id", "internalname", "internal_name", "itemId")
        for (key in keys) {
            val value = obj.get(key).asStringOrNull()
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun extractPriceFromObject(obj: com.google.gson.JsonObject): Double? {
        val keys = listOf("npc_sell_price", "npc_price", "npcPrice", "npcprice", "sell_price", "sellPrice", "price")
        for (key in keys) {
            val value = obj.get(key).asDoubleOrNull()
            if (value != null) return value
        }
        return null
    }

    private fun applyNpcPrices(loadedPrices: Map<String, Double>, sourceName: String): Boolean {
        if (loadedPrices.isEmpty()) {
            logVerboseWarn("NPC price source returned 0 usable entries for $sourceName")
            return false
        }

        npcPrices.clear()
        npcPrices.putAll(loadedPrices)
        lastNpcUpdate = System.currentTimeMillis()
        lastNpcSource = sourceName
        dataEpoch.incrementAndGet()
        return true
    }

    private fun buildGetRequest(url: String): HttpRequest {
        return HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("User-Agent", USER_AGENT)
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build()
    }

    private fun putNpcPrice(target: MutableMap<String, Double>, rawId: String, rawPrice: Double) {
        if (!rawPrice.isFinite() || rawPrice <= 0.0) return
        val normalized = rawId.trim().uppercase(Locale.ROOT)
        if (!looksLikeItemId(normalized)) return
        target[normalized] = rawPrice
    }

    private fun looksLikeItemId(id: String): Boolean {
        if (id.length !in 2..128) return false
        if (!id.any { it.isLetter() }) return false
        return id.all { it.isLetterOrDigit() || it == '_' || it == ':' || it == '-' || it == '.' || it == '/' || it == ';' }
    }

    private fun com.google.gson.JsonElement?.asDoubleOrNull(): Double? {
        if (this == null || !this.isJsonPrimitive) return null
        return runCatching { this.asDouble }.getOrNull()
    }

    private fun com.google.gson.JsonElement?.asStringOrNull(): String? {
        if (this == null || !this.isJsonPrimitive) return null
        return runCatching { this.asString?.trim() }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun com.google.gson.JsonElement?.asBooleanOrNull(): Boolean? {
        if (this == null || !this.isJsonPrimitive) return null
        return runCatching { this.asBoolean }.getOrNull()
    }

    private fun Boolean?.orFalse(): Boolean = this ?: false

    private fun logVerboseInfo(message: String) {
        if (ChestMasterMod.isVerboseLogging()) {
            ChestMasterMod.LOGGER.info(message)
        }
    }

    private fun logVerboseWarn(message: String) {
        if (ChestMasterMod.isVerboseLogging()) {
            ChestMasterMod.LOGGER.warn(message)
        }
    }

    fun arePricesLoaded(): Boolean = pricesLoaded.get()

    fun isLbinUnavailable(): Boolean = lbinUnavailable

    fun getDebugStatus(): String {
        return "loaded=${pricesLoaded.get()}, bazaar=${bazaarSellPrices.size}, lbin=${lbinPrices.size}" +
            (if (lbinUnavailable) " (unavailable)" else "") +
            ", npc=${npcPrices.size}, npcSource=$lastNpcSource"
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
}
