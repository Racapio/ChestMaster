package com.chestmaster.util

import java.util.Locale

/**
 * Shared block-list of container titles that must never be indexed.
 *
 * The scanner's primary filter is now a strict whitelist of vanilla storage names
 * (see ChestScanner) — this block-list is a secondary guard, and its main job is to
 * let DatabaseManager purge rows saved by older versions whose title-based filtering
 * was too permissive (menus opened while facing a chest slipped through).
 */
object ContainerFilters {
    // Substring matches against the lowercased container title.
    val blockedTitleKeywords: List<String> = listOf(
        // Hypixel menus / non-storage GUIs
        "minion",
        "auction",
        "bazaar",
        "pet",
        "museum",
        "wardrobe",
        "trade",
        "menu",
        "craft",
        "profile",
        "bank",
        "dungeon",
        "loadout",
        "sack",
        "backpack",
        "storage",
        // Auction "create/sell" dialogs — "At what price are you selling?"
        "selling",
        "how long",
        // Hypixel sub-menu arrow — e.g. "Farming ➜ Nether Warts", "X ➜ Instant Buy"
        "➜",
        // Bazaar confirm dialogs — "Confirm Sell Offer", "Confirm Instant Buy", ...
        "confirm",
        // Personal/ender storage — contents travel with the player, coordinates are meaningless
        "ender chest",
        "personal vault",
        // Reward/loot chests (dungeons, Kuudra, events) — one-time loot, not storage
        "wood chest",
        "gold chest",
        "diamond chest",
        "emerald chest",
        "obsidian chest",
        "bedrock chest",
        "paid chest",
        "free chest",
        "reward chest",
        "treasure chest",
        "loot chest"
    )

    fun isBlockedTitle(title: String): Boolean {
        val normalized = title.lowercase(Locale.ROOT)
        return blockedTitleKeywords.any { normalized.contains(it) }
    }
}
