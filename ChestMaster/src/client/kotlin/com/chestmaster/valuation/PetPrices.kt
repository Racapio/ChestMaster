package com.chestmaster.valuation

import com.chestmaster.ChestMasterMod
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Lowest-BIN pet prices by type, rarity **and level**, from Coflnet.
 *
 * Neither bulk LBIN dump distinguishes pet levels (a level 100 Megalodon is ~7× a level 1 one), and
 * the one SkyBlockAPI uses is unreachable for some players without a VPN. Coflnet answers per pet
 * with a level filter, so prices are fetched lazily for the pets actually stored: the first lookup
 * queues a request, a background worker fetches it (spaced out — Coflnet rate-limits with HTTP 403),
 * and [version] changes so the GUI re-evaluates once results are in.
 */
object PetPrices {
    data class Quote(val price: Double, val label: String)

    private data class Key(val type: String, val tier: String, val minLevel: Int, val maxLevel: Int)
    private data class Entry(val quote: Quote?, val fetchedAtMs: Long)

    private const val CACHE_TTL_MS = 30 * 60 * 1000L
    private const val FAILURE_RETRY_MS = 2 * 60 * 1000L
    private const val REQUEST_SPACING_MS = 400L
    private const val RATE_LIMIT_BACKOFF_MS = 3000L

    // Pets whose maximum level is 200 instead of 100.
    private val level200Pets = setOf("GOLDEN_DRAGON", "JADE_DRAGON", "ROSE_DRAGON")

    private val cache = ConcurrentHashMap<Key, Entry>()
    private val queued = ConcurrentHashMap.newKeySet<Key>()
    private val queue = LinkedBlockingDeque<Key>()
    private val versionCounter = AtomicLong(0)

    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build()

    /** Changes after a batch of new prices arrived. */
    val version: Long get() = versionCounter.get()

    fun init() {
        Thread(::workerLoop, "ChestMaster-PetPrices").apply { isDaemon = true }.start()
    }

    /**
     * Cached quote for a pet, or null while it is being fetched (the request is queued).
     * Stale entries are returned as-is and refreshed in the background.
     */
    fun quote(type: String, tier: String, level: Int): Quote? {
        val key = keyFor(type, tier, level)
        val entry = cache[key]
        val now = System.currentTimeMillis()
        val ttl = if (entry?.quote == null) FAILURE_RETRY_MS else CACHE_TTL_MS
        if ((entry == null || now - entry.fetchedAtMs > ttl) && queued.add(key)) queue.offer(key)
        return entry?.quote
    }

    private fun keyFor(type: String, tier: String, level: Int): Key {
        val max = if (type in level200Pets) 200 else 100
        val lvl = level.coerceIn(1, max)
        if (lvl >= max) return Key(type, tier, max, max)
        val lo = (lvl - 1) / 10 * 10 + 1
        return Key(type, tier, lo, minOf(lo + 9, max - 1))
    }

    private fun workerLoop() {
        while (true) {
            try {
                val key = queue.poll(30, TimeUnit.SECONDS) ?: continue
                val quote = fetch(key)
                cache[key] = Entry(quote, System.currentTimeMillis())
                queued.remove(key)
                if (queue.isEmpty()) versionCounter.incrementAndGet()
                Thread.sleep(REQUEST_SPACING_MS)
            } catch (_: InterruptedException) {
                return
            } catch (e: Exception) {
                ChestMasterMod.LOGGER.warn("[ChestMaster] Pet price worker error: ${e.message}")
            }
        }
    }

    private fun fetch(key: Key): Quote? {
        val levelFilter = if (key.minLevel == key.maxLevel) "${key.minLevel}" else "${key.minLevel}-${key.maxLevel}"
        val levelLabel = if (key.minLevel == key.maxLevel) "Lvl ${key.minLevel}" else "Lvl $levelFilter"
        lowestBin(key, "&PetLevel=$levelFilter")?.let { return Quote(it, levelLabel) }
        // Nobody sells this pet in that level range — fall back to any level of the same rarity.
        return lowestBin(key, "")?.let { Quote(it, "any level") }
    }

    private fun lowestBin(key: Key, extraFilter: String): Double? {
        val url = "https://sky.coflnet.com/api/item/price/PET_${key.type}/bin?Rarity=${key.tier}$extraFilter"
        repeat(2) { attempt ->
            try {
                val request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "ChestMaster (Fabric mod)")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build()
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                when (response.statusCode()) {
                    200 -> {
                        val lowest = JsonParser.parseString(response.body()).asJsonObject.get("lowest")?.asDouble ?: 0.0
                        return lowest.takeIf { it > 0.0 }
                    }
                    403, 429 -> if (attempt == 0) Thread.sleep(RATE_LIMIT_BACKOFF_MS)
                    else -> return null
                }
            } catch (_: Exception) {
                return null
            }
        }
        return null
    }
}
