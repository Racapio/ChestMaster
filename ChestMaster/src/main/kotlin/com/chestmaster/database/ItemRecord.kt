package com.chestmaster.database

data class ItemRecord(
    val id: Int = 0,
    val itemId: String,
    val baseItemId: String = "",
    val displayName: String,
    val itemNbt: String,
    val count: Int,
    val chestX: Int,
    val chestY: Int,
    val chestZ: Int,
    val label: String,
    val serverKey: String = "",
    val lastSeen: Long = 0L
)
