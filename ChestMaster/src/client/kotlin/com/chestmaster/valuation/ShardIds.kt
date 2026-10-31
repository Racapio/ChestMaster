package com.chestmaster.valuation

import com.chestmaster.ChestMasterMod
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.nbt.CompoundTag
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong

/**
 * Maps attribute shards to their Bazaar product ids.
 *
 * Every shard item carries the generic id ATTRIBUTE_SHARD plus an `attributes` compound such as
 * `{fisherman:1}`. That attribute is stable, while display names are not: Hypixel renamed several
 * shards (Night Squid → Inkling, Bruiser → Zealot Bruiser, Wither Specter → Wither Spectre…) but kept
 * the old Bazaar ids. So shards are resolved by attribute first, by name only as a fallback.
 *
 * The table ships inside the mod (works offline and without SkyBlockAPI's repo) and is refreshed
 * once at startup from the NEU repo to pick up newly added shards.
 */
object ShardIds {
    private const val REFRESH_URL =
        "https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/master/constants/attribute_shards.json"
    private val attributeIdRegex = Regex("""^ATTRIBUTE_SHARD_(.+);\d+$""")
    private val countSuffixRegex = Regex("(?i)\\s*x\\d+$")
    private val formattingRegex = Regex("§.")

    @Volatile private var byAttribute: Map<String, String> = emptyMap()
    @Volatile private var byName: Map<String, String> = emptyMap()

    private val versionCounter = AtomicLong(0)

    /** Changes when the table is (re)loaded, so cached valuations can be refreshed. */
    val version: Long get() = versionCounter.get()

    fun init() {
        loadBundled()
        CompletableFuture.runAsync { refreshFromRepo() }
    }

    /** Bazaar id for a shard, from its `attributes` compound or, failing that, its display name. */
    fun resolve(customData: CompoundTag?, displayName: String): String? {
        val attributes = customData?.getCompound("attributes")?.orElse(null)
        if (attributes != null) {
            for (key in attributes.keySet()) {
                byAttribute[key.lowercase(Locale.ROOT)]?.let { return it }
            }
        }
        val name = countSuffixRegex.replace(formattingRegex.replace(displayName, "").trim(), "").trim()
        return byName[name.lowercase(Locale.ROOT)]
    }

    private fun loadBundled() {
        try {
            val stream = ShardIds::class.java.getResourceAsStream("/chestmaster/shard_ids.json")
                ?: error("bundled shard table missing")
            val json = stream.bufferedReader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
            byAttribute = json.getAsJsonObject("attributes").toStringMap()
            byName = json.getAsJsonObject("names").toStringMap()
            versionCounter.incrementAndGet()
        } catch (e: Exception) {
            ChestMasterMod.LOGGER.warn("[ChestMaster] Could not load bundled shard table: ${e.message}")
        }
    }

    private fun refreshFromRepo() {
        try {
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build()
            val request = HttpRequest.newBuilder(URI.create(REFRESH_URL))
                .header("User-Agent", "ChestMaster (Fabric mod)")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) return

            val attributes = HashMap(byAttribute)
            val names = HashMap(byName)
            for (element in JsonParser.parseString(response.body()).asJsonObject.getAsJsonArray("attributes")) {
                val shard = element.asJsonObject
                val bazaarId = shard.get("bazaarName")?.asString ?: continue
                shard.get("internalName")?.asString?.let { internal ->
                    attributeIdRegex.find(internal)?.let { attributes[it.groupValues[1].lowercase(Locale.ROOT)] = bazaarId }
                }
                shard.get("displayName")?.asString?.let { names["${it.lowercase(Locale.ROOT)} shard"] = bazaarId }
            }
            if (attributes.size >= byAttribute.size) {
                byAttribute = attributes
                byName = names
                versionCounter.incrementAndGet()
            }
        } catch (_: Exception) {
            // Offline / blocked: the bundled table is used.
        }
    }

    private fun JsonObject.toStringMap(): Map<String, String> =
        entrySet().associate { (key, value) -> key to value.asString }
}
