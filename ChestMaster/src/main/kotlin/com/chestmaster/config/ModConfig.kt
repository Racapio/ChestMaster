package com.chestmaster.config

data class ModConfig(
    var showTotalValue: Boolean = true,
    var autoScanChests: Boolean = true,
    var bazaarUpdateInterval: Int = 300,
    var verboseLogging: Boolean = false
)
