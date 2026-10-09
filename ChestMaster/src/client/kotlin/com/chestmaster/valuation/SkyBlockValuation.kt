package com.chestmaster.valuation

import com.chestmaster.database.ItemRecord
import com.chestmaster.util.ItemUtils
import tech.thatgravyboat.skyblockapi.api.item.calculator.ItemValueResult
import tech.thatgravyboat.skyblockapi.api.item.calculator.getItemValue
import java.util.Locale

/**
 * Item valuation backed by SkyBlockAPI's calculator (the same one SkyOcean uses).
 * It understands everything ItemValuator guessed at by hand and more: enchantments
 * at any level (e.g. Chimera V = 16 × Chimera I), stars and master stars, gemstones,
 * reforges, drill/rod parts, Necron scrolls, runes, dyes, skins, pets, …
 */
object SkyBlockValuation {
    data class Line(val label: String, val value: Double)

    data class Result(val unitPrice: Double, val lines: List<Line>)

    /** Unit value of a stored item, or null when SkyBlockAPI can't price it. */
    fun evaluate(record: ItemRecord): Result? {
        val baseId = record.baseItemId.ifBlank { record.itemId }
        val stack = ItemUtils.deserializeItemStack(baseId, record.itemNbt)
        if (stack.isEmpty) return null
        stack.count = 1 // calculate() multiplies by the stack count — we want the unit price

        val value: ItemValueResult = runCatching { stack.getItemValue() }.getOrNull() ?: return null
        if (value.price <= 0L) return null

        val lines = value.entryTree
            .filter { it.price > 0L }
            .sortedByDescending { it.price }
            .map { Line(sourceLabel(it.source.name), it.price.toDouble()) }
        return Result(value.price.toDouble(), lines)
    }

    private fun sourceLabel(enumName: String): String =
        enumName.lowercase(Locale.ROOT).split('_').joinToString(" ") { word ->
            word.replaceFirstChar { it.titlecase(Locale.ROOT) }
        }
}
