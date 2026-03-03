package com.chestmaster.util

import com.chestmaster.ChestMasterMod
import net.minecraft.core.component.DataComponents
import net.minecraft.core.component.DataComponentPatch
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.TagParser
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomData
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private fun isLikelyExtraAttributesTag(tag: CompoundTag): Boolean {
    return !tag.getString("id").orElse(null).isNullOrBlank() ||
        tag.getCompound("enchantments").orElse(null) != null ||
        tag.getCompound("runes").orElse(null) != null ||
        tag.getIntOr("dungeon_item_level", Int.MIN_VALUE) != Int.MIN_VALUE ||
        tag.getIntOr("upgrade_level", Int.MIN_VALUE) != Int.MIN_VALUE ||
        tag.getIntOr("hot_potato_count", Int.MIN_VALUE) != Int.MIN_VALUE ||
        tag.getIntOr("rarity_upgrades", Int.MIN_VALUE) != Int.MIN_VALUE
}

private fun resolveExtraAttributesTag(root: CompoundTag?): CompoundTag? {
    if (root == null) return null

    root.getCompound("ExtraAttributes").orElse(null)?.let { return it }

    // Some serialized rows keep data under namespaced component keys.
    root.getCompound("minecraft:custom_data").orElse(null)?.let { nested ->
        resolveExtraAttributesTag(nested)?.let { return it }
    }

    // Defensive path for component wrappers.
    root.getCompound("components").orElse(null)?.let { components ->
        components.getCompound("minecraft:custom_data").orElse(null)?.let { nested ->
            resolveExtraAttributesTag(nested)?.let { return it }
        }
    }

    return if (isLikelyExtraAttributesTag(root)) root else null
}

private fun ItemStack.extraAttributesTag(): CompoundTag? {
    val customData = this.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: return null
    return resolveExtraAttributesTag(customData)
}

private fun extractSkyblockIdFromCustomData(customData: CompoundTag?): String? {
    val extraAttributes = resolveExtraAttributesTag(customData) ?: return null
    return extraAttributes.getString("id").orElse(null)
}

val ItemStack.skyblockId: String?
    get() {
        if (this.isEmpty) return null
        return extractSkyblockIdFromCustomData(this.get(DataComponents.CUSTOM_DATA)?.copyTag())
    }

val ItemStack.stars: Int
    get() {
        if (this.isEmpty) return 0
        val extraAttributes = extraAttributesTag() ?: return 0
        return extraAttributes.getIntOr("dungeon_item_level", extraAttributes.getIntOr("upgrade_level", 0)).coerceAtLeast(0)
    }

val ItemStack.isRecombed: Boolean
    get() {
        if (this.isEmpty) return false
        val extraAttributes = extraAttributesTag() ?: return false
        return extraAttributes.getIntOr("rarity_upgrades", 0) > 0 || extraAttributes.getBooleanOr("recombobulated", false)
    }

val ItemStack.hotPotatoCount: Int
    get() {
        if (this.isEmpty) return 0
        val extraAttributes = extraAttributesTag() ?: return 0
        return extraAttributes.getIntOr("hot_potato_count", 0).coerceAtLeast(0)
    }

val ItemStack.artOfWarCount: Int
    get() {
        if (this.isEmpty) return 0
        val extraAttributes = extraAttributesTag() ?: return 0
        return extraAttributes.getIntOr("art_of_war_count", 0).coerceAtLeast(0)
    }

val ItemStack.farmingForDummiesCount: Int
    get() {
        if (this.isEmpty) return 0
        val extraAttributes = extraAttributesTag() ?: return 0
        return extraAttributes.getIntOr("farming_for_dummies_count", 0).coerceAtLeast(0)
    }

val ItemStack.enchantmentsTagString: String?
    get() {
        if (this.isEmpty) return null
        val extraAttributes = extraAttributesTag() ?: return null
        return extraAttributes.getCompound("enchantments").orElse(null)?.toString()
    }

val ItemStack.runesTagString: String?
    get() {
        if (this.isEmpty) return null
        val extraAttributes = extraAttributesTag() ?: return null
        return extraAttributes.getCompound("runes").orElse(null)?.toString()
    }

val ItemStack.hasEtherwarp: Boolean
    get() {
        if (this.isEmpty) return false
        val extraAttributes = extraAttributesTag() ?: return false
        if (extraAttributes.getBooleanOr("ethermerge", false)) return true
        if (extraAttributes.getIntOr("ethermerge", 0) > 0) return true
        val ethermergeValue = extraAttributes.getString("ethermerge").orElse(null)
        return !ethermergeValue.isNullOrBlank()
    }

val ItemStack.transmissionTunerCount: Int
    get() {
        if (this.isEmpty) return 0
        val extraAttributes = extraAttributesTag() ?: return 0

        val tunedTransmission = extraAttributes.getIntOr("tuned_transmission", Int.MIN_VALUE)
        if (tunedTransmission != Int.MIN_VALUE) {
            return tunedTransmission.coerceIn(0, 4)
        }

        val transmissionTuners = extraAttributes.getIntOr("transmission_tuner", Int.MIN_VALUE)
        if (transmissionTuners != Int.MIN_VALUE) {
            return transmissionTuners.coerceIn(0, 4)
        }

        val transmissionTunerCount = extraAttributes.getIntOr("transmission_tuner_count", Int.MIN_VALUE)
        if (transmissionTunerCount != Int.MIN_VALUE) {
            return transmissionTunerCount.coerceIn(0, 4)
        }

        return 0
    }

object ItemUtils {
    private val itemLookupCache = ConcurrentHashMap<String, Item>()

    @Volatile
    private var itemRegistryIndexed = false

    fun normalizeSkyblockId(rawId: String?): String? {
        if (rawId == null) return null
        val trimmed = rawId.trim()
        if (trimmed.isEmpty()) return null
        return if (trimmed.contains(':')) trimmed else trimmed.uppercase(Locale.ROOT)
    }

    fun extractSkyblockIdFromNbtString(nbtString: String): String? {
        if (nbtString.isBlank() || nbtString == "{}") return null

        val nbt = parseNbtSafely(nbtString) ?: return null

        // Primary path: parse modern serialized DataComponentPatch and read CUSTOM_DATA.
        try {
            val patch = DataComponentPatch.CODEC.parse(NbtOps.INSTANCE, nbt).result().orElse(null)
            if (patch != null) {
                val probeStack = ItemStack(resolveItemById("minecraft:stone"))
                if (applyPatchToStackCompat(probeStack, patch)) {
                    normalizeSkyblockId(probeStack.skyblockId)?.let { return it }
                }
            }
        } catch (_: Exception) {
            // Continue to legacy fallbacks.
        }

        normalizeSkyblockId(extractSkyblockIdFromCustomData(nbt))?.let { return it }

        return null
    }

    fun extractExtraAttributesFromNbtString(nbtString: String): CompoundTag? {
        if (nbtString.isBlank() || nbtString == "{}") return null
        val nbt = parseNbtSafely(nbtString) ?: return null

        // Primary path: decode component patch and extract CUSTOM_DATA from a probe stack.
        try {
            val patch = DataComponentPatch.CODEC.parse(NbtOps.INSTANCE, nbt).result().orElse(null)
            if (patch != null) {
                val probeStack = ItemStack(resolveItemById("minecraft:stone"))
                if (applyPatchToStackCompat(probeStack, patch)) {
                    resolveExtraAttributesTag(probeStack.get(DataComponents.CUSTOM_DATA)?.copyTag())?.let { return it }
                }
            }
        } catch (_: Exception) {
            // Continue to fallback paths.
        }

        // Legacy rows: raw custom_data or direct ExtraAttributes payload.
        return resolveExtraAttributesTag(nbt)
    }

    fun getItemId(stack: ItemStack): String {
        if (stack.isEmpty) return "minecraft:air"
        return BuiltInRegistries.ITEM.getKey(stack.item).toString()
    }

    fun getNbtString(stack: ItemStack): String {
        if (stack.isEmpty) return "{}"
        return try {
            DataComponentPatch.CODEC
                .encodeStart(NbtOps.INSTANCE, stack.componentsPatch)
                .result()
                .map { it.toString() }
                .orElse("{}")
        } catch (_: Exception) {
            // Backward-compatible fallback.
            val customData = stack.get(DataComponents.CUSTOM_DATA)
            customData?.copyTag()?.toString() ?: "{}"
        }
    }

    fun getDisplayName(stack: ItemStack): String {
        if (stack.isEmpty) return "Air"
        return stack.hoverName.string
    }

    fun deserializeItemStack(itemId: String, nbtString: String): ItemStack {
        val item = resolveItemById(itemId)
        val stack = ItemStack(item)

        if (nbtString.isNotEmpty() && nbtString != "{}") {
            try {
                val nbt = TagParser.parseCompoundFully(nbtString)
                val patch = DataComponentPatch.CODEC.parse(NbtOps.INSTANCE, nbt).result().orElse(null)
                if (patch != null) {
                    if (!applyPatchToStackCompat(stack, patch)) {
                        resolveExtraAttributesTag(nbt)?.let { extra ->
                            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(extra))
                        }
                    }
                } else {
                    // Compatibility with older DB rows that stored only CUSTOM_DATA.
                    resolveExtraAttributesTag(nbt)?.let { extra ->
                        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(extra))
                    }
                }
            } catch (_: Exception) {
                try {
                    // Compatibility with older DB rows that stored only CUSTOM_DATA.
                    val legacyNbt = TagParser.parseCompoundFully(nbtString)
                    resolveExtraAttributesTag(legacyNbt)?.let { extra ->
                        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(extra))
                    }
                } catch (_: Exception) {
                    // Ignore malformed NBT saved in DB and keep plain stack.
                }
            }
        }

        return stack
    }

    private fun parseNbtSafely(nbtString: String): CompoundTag? {
        return try {
            TagParser.parseCompoundFully(nbtString)
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveItemById(rawId: String): Item {
        val normalizedId = normalizeMinecraftItemId(rawId)
        itemLookupCache[normalizedId]?.let { return it }

        indexItemRegistryIfNeeded()
        itemLookupCache[normalizedId]?.let { return it }

        val fallbackPath = normalizedId.substringAfter(':')
        val fallbackId = "minecraft:$fallbackPath"
        itemLookupCache[fallbackId]?.let { return it }

        // Defensive fallback for environments where registry iteration order/cache state changed.
        for (item in BuiltInRegistries.ITEM) {
            val key = BuiltInRegistries.ITEM.getKey(item).toString()
            if (key == normalizedId || key == fallbackId || key.substringAfter(':') == fallbackPath) {
                itemLookupCache.putIfAbsent(key, item)
                itemLookupCache.putIfAbsent(normalizedId, item)
                return item
            }
        }

        if (normalizedId != "minecraft:air" && ChestMasterMod.isVerboseLogging()) {
            ChestMasterMod.LOGGER.debug("Failed to resolve item id '{}', rendering fallback AIR.", normalizedId)
        }
        return Items.AIR
    }

    private fun normalizeMinecraftItemId(rawId: String): String {
        val trimmed = rawId.trim().ifBlank { "minecraft:air" }
        if (!trimmed.contains(':')) {
            return "minecraft:${trimmed.lowercase(Locale.ROOT)}"
        }

        val namespace = trimmed.substringBefore(':').ifBlank { "minecraft" }.lowercase(Locale.ROOT)
        val path = trimmed.substringAfter(':').ifBlank { "air" }.lowercase(Locale.ROOT)
        return "$namespace:$path"
    }

    private fun indexItemRegistryIfNeeded() {
        if (itemRegistryIndexed) return

        synchronized(itemLookupCache) {
            if (itemRegistryIndexed) return

            for (item in BuiltInRegistries.ITEM) {
                val key = BuiltInRegistries.ITEM.getKey(item).toString()
                itemLookupCache.putIfAbsent(key, item)
            }

            itemRegistryIndexed = true
        }
    }

    private fun applyPatchToStackCompat(stack: ItemStack, patch: DataComponentPatch): Boolean {
        val patchClass = patch.javaClass
        val candidateMethods = stack.javaClass.methods.filter { method ->
            method.parameterCount == 1 && method.parameterTypes[0].isAssignableFrom(patchClass)
        }

        for (method in candidateMethods) {
            runCatching {
                method.invoke(stack, patch)
                return true
            }
        }
        return false
    }
}
