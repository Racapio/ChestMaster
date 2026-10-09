package com.chestmaster.valuation

import com.chestmaster.ChestMasterMod
import com.google.gson.JsonParser
import tech.thatgravyboat.skyblockapi.api.remote.hypixel.pricing.LowestBinAPI
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Safety net for SkyBlockAPI's auction (lowest-BIN) data.
 *
 * SkyBlockAPI fetches it from skyblock-pv.thatgravyboat.tech once at startup and then only
 * every hour. That host sits behind Cloudflare ranges that are unreachable for some players
 * (e.g. in Russia without a VPN), and a failed first request leaves every auction item — and
 * the base price of things like a Daedalus Blade — unpriced. While the data is missing, this
 * retries every 30 s, falling back to hysky.de (Skyblocker's lowest-BIN dump, reachable where
 * the former isn't), and hands the result to SkyBlockAPI so its whole calculator works.
 */
object AuctionDataFallback {
    // SkyBlockAPI's own source: {"ID": {"lowest", "highest", "median", "mean"}}
    private const val PRIMARY_URL = "https://skyblock-pv.thatgravyboat.tech/auctions"
    // Fallback: flat {"ID": lowestBin}
    private const val FALLBACK_URL = "https://hysky.de/api/auctions/lowestbins"
    private const val INITIAL_DELAY_SECONDS = 20L // let SkyBlockAPI make its own first attempt
    private const val RETRY_SECONDS = 30L

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(6))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ChestMaster-AuctionData").apply { isDaemon = true }
    }

    private var failures = 0

    fun init() {
        executor.scheduleWithFixedDelay(::checkAndFill, INITIAL_DELAY_SECONDS, RETRY_SECONDS, TimeUnit.SECONDS)
    }

    private fun get(url: String): String {
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", "ChestMaster (Fabric mod)")
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) error("HTTP ${response.statusCode()}")
        return response.body()
    }

    private fun fetchDetailed(url: String): Map<String, LowestBinAPI.AuctionItem> {
        val parsed = HashMap<String, LowestBinAPI.AuctionItem>()
        for ((id, value) in JsonParser.parseString(get(url)).asJsonObject.entrySet()) {
            if (!value.isJsonObject) continue
            val obj = value.asJsonObject
            parsed[id] = LowestBinAPI.AuctionItem(
                obj.get("lowest")?.asLong ?: 0L,
                obj.get("highest")?.asLong ?: 0L,
                obj.get("median")?.asLong ?: 0L,
                obj.get("mean")?.asDouble ?: 0.0
            )
        }
        return parsed
    }

    private fun fetchFlat(url: String): Map<String, LowestBinAPI.AuctionItem> {
        val parsed = HashMap<String, LowestBinAPI.AuctionItem>()
        for ((id, value) in JsonParser.parseString(get(url)).asJsonObject.entrySet()) {
            val price = runCatching { value.asDouble }.getOrNull() ?: continue
            if (price <= 0.0) continue
            val lowest = price.toLong()
            // Only the lowest BIN is known; reuse it for the other statistics.
            parsed[id] = LowestBinAPI.AuctionItem(lowest, lowest, lowest, price)
        }
        return parsed
    }

    private fun hasData(): Boolean = runCatching { LowestBinAPI.items.isNotEmpty() }.getOrDefault(true)

    private fun checkAndFill() {
        if (hasData()) {
            failures = 0
            return
        }
        try {
            val errors = mutableListOf<String>()
            var source = PRIMARY_URL
            var parsed = runCatching { fetchDetailed(PRIMARY_URL) }
                .onFailure { errors += "${PRIMARY_URL.substringAfter("//").substringBefore('/')}: ${it.javaClass.simpleName}" }
                .getOrNull()
            if (parsed.isNullOrEmpty()) {
                source = FALLBACK_URL
                parsed = runCatching { fetchFlat(FALLBACK_URL) }
                    .onFailure { errors += "${FALLBACK_URL.substringAfter("//").substringBefore('/')}: ${it.javaClass.simpleName}" }
                    .getOrNull()
            }
            if (parsed.isNullOrEmpty()) error(errors.joinToString("; ").ifEmpty { "empty response" })
            if (hasData()) return // SkyBlockAPI recovered on its own meanwhile

            val field = LowestBinAPI::class.java.getDeclaredField("items")
            field.isAccessible = true
            field.set(null, parsed)
            failures = 0
            ChestMasterMod.LOGGER.info(
                "[ChestMaster] Auction prices were missing; loaded ${parsed.size} lowest-BIN entries from $source"
            )
        } catch (e: Exception) {
            failures += 1
            // Log the first failure and then occasionally, not every 30 s.
            if (failures == 1 || failures % 10 == 0) {
                ChestMasterMod.LOGGER.warn(
                    "[ChestMaster] Auction prices unavailable, retrying every ${RETRY_SECONDS}s " +
                        "(${e.message})"
                )
            }
        }
    }
}
