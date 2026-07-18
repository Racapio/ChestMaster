package com.chestmaster.config

data class ModConfig(
    var autoScan: Boolean = false,
    var verboseLogging: Boolean = false,
    var sortMode: String = "PRICE_DESC",
    var priceMode: String = "SELL_OFFER",
    // Seconds between market price refreshes (Bazaar/LBIN/NPC cache duration).
    var bazaarUpdateInterval: Int = 300
)
