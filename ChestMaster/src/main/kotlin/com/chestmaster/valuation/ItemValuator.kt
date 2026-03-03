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

    private var lastBazaarUpdate: Long = 0
    private var lastLbinUpdate: Long = 0
    private var lastNpcUpdate: Long = 0
    @Volatile
    private var lastNpcSource: String = "none"
    private val pricesLoaded = AtomicBoolean(false)
    private val successfulLoads = AtomicInteger(0)
    private const val CACHE_DURATION: Long = 300000

    private val httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

    var currentMode = PriceMode.SELL_OFFER

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
        val id = ItemUtils.extractSkyblockIdFromNbtString(nbtString) ?: normalizedHint ?: "UNKNOWN"

        val extraAttributes = ItemUtils.extractExtraAttributesFromNbtString(nbtString)
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
        successfulLoads.set(0)
        pricesLoaded.set(false)
        loggedIds.clear()
        updateBazaarPrices()
        updateLbinPrices()
        updateNpcPrices()
    }

    private fun updateBazaarPrices() {
        if (System.currentTimeMillis() - lastBazaarUpdate < CACHE_DURATION && bazaarSellPrices.isNotEmpty()) {
            checkLoadCompletion()
            return
        }
        CompletableFuture.runAsync {
            try {
                val request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.hypixel.net/skyblock/bazaar"))
                    .header("User-Agent", USER_AGENT)
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
                    }
                }
            } catch (e: Exception) {
                logVerboseWarn("Bazaar update failed: ${e.message}")
            } finally {
                checkLoadCompletion()
            }
        }
    }

    private fun updateLbinPrices() {
        if (System.currentTimeMillis() - lastLbinUpdate < CACHE_DURATION && lbinPrices.isNotEmpty()) {
            checkLoadCompletion()
            return
        }
        CompletableFuture.runAsync {
            try {
                val request = HttpRequest.newBuilder()
                    .uri(URI.create("https://moulberry.codes/lowestbin.json"))
                    .header("User-Agent", USER_AGENT)
                    .GET().build()
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() == 200) {
                    val json = JsonParser.parseString(response.body()).asJsonObject
                    lbinPrices.clear()
                    for (entry in json.entrySet()) {
                        lbinPrices[entry.key] = entry.value.asDouble
                    }
                    lastLbinUpdate = System.currentTimeMillis()
                }
            } catch (e: Exception) {
                logVerboseWarn("LBIN update failed: ${e.message}")
            } finally {
                checkLoadCompletion()
            }
        }
    }

    private fun updateNpcPrices() {
        if (System.currentTimeMillis() - lastNpcUpdate < CACHE_DURATION && npcPrices.isNotEmpty()) {
            checkLoadCompletion()
            return
        }
        CompletableFuture.runAsync {
            try {
                val loaded = loadNpcPricesFromConfiguredSources()
                if (!loaded) logVerboseWarn("NPC update failed: all configured NPC sources are unavailable.")
            } catch (e: Exception) {
                logVerboseWarn("NPC update failed: ${e.message}")
            } finally {
                checkLoadCompletion()
            }
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

    private fun checkLoadCompletion() {
        if (successfulLoads.incrementAndGet() >= 3) pricesLoaded.set(true)
    }

    fun arePricesLoaded(): Boolean = pricesLoaded.get()

    fun getDebugStatus(): String {
        return "loaded=${pricesLoaded.get()}, bazaar=${bazaarSellPrices.size}, lbin=${lbinPrices.size}, npc=${npcPrices.size}, npcSource=$lastNpcSource"
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
