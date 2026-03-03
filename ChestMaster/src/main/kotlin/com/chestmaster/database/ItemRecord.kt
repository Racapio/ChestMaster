package com.chestmaster.database

/**
 * Data class для записи предмета в БД.
 * В Kotlin это заменяет весь громоздкий Java-класс с геттерами и сеттерами.
 */
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
    val label: String
)
