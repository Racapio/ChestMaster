package com.chestmaster.config

data class ModConfig(
    var autoScan: Boolean = false,
    var verboseLogging: Boolean = false,
    var sortMode: String = "PRICE_DESC",
    var priceMode: String = "SELL_OFFER",
    // Seconds before item/chest highlights clear themselves; 0 = never.
    var highlightSeconds: Int = 60
)
