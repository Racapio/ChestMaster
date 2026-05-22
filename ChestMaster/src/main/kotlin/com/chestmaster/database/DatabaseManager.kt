package com.chestmaster.database

import com.chestmaster.ChestMasterMod
import com.chestmaster.util.ItemUtils
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.DriverManager
import java.util.Locale

class DatabaseManager {
    data class ResetResult(
        val deletedRecords: Int,
        val sizeBeforeBytes: Long,
        val sizeAfterBytes: Long
    ) {
        val freedBytes: Long
            get() = (sizeBeforeBytes - sizeAfterBytes).coerceAtLeast(0)
    }

    private var connection: Connection? = null
    private var dbPath: Path? = null

    fun getDatabasePath(): Path? = dbPath

    fun init() {
        try {
            Class.forName("org.sqlite.JDBC")
            val gameDir = FabricLoader.getInstance().gameDir.toAbsolutePath().normalize()
            val targetPath = gameDir.resolve("config").resolve("chestmaster.db")
            val legacyPath = gameDir.resolve("chestmaster.db")

            Files.createDirectories(targetPath.parent)
            if (!Files.exists(targetPath) && Files.exists(legacyPath)) {
                Files.move(legacyPath, targetPath, StandardCopyOption.REPLACE_EXISTING)
                if (ChestMasterMod.isVerboseLogging()) {
                    ChestMasterMod.LOGGER.info("Migrated database from $legacyPath to $targetPath")
                }
            }

            dbPath = targetPath
            connection = DriverManager.getConnection("jdbc:sqlite:$targetPath")
            // WAL mode: allows concurrent reads while a write is in progress,
            // preventing the render thread from blocking on DB saves.
            connection?.createStatement()?.use { it.execute("PRAGMA journal_mode=WAL") }
            createTables()
            migrateSchema()
            if (ChestMasterMod.isVerboseLogging()) {
                ChestMasterMod.LOGGER.info("Database initialized at $targetPath")
            }
        } catch (e: Exception) {
            ChestMasterMod.LOGGER.error("Failed to init database", e)
        }
    }

    private fun createTables() {
        val sql = """
            CREATE TABLE IF NOT EXISTS items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                itemId TEXT,
                baseItemId TEXT,
                displayName TEXT,
                itemNbt TEXT,
                count INTEGER,
                chestX INTEGER,
                chestY INTEGER,
                chestZ INTEGER,
                label TEXT,
                serverKey TEXT DEFAULT '',
                lastSeen INTEGER DEFAULT 0
            )
        """.trimIndent()
        connection?.createStatement()?.use { it.execute(sql) }

        connection?.createStatement()?.use { stmt ->
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_items_chest_pos ON items(chestX, chestY, chestZ)")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_items_identity ON items(itemId, baseItemId)")
            // idx_items_server is created in migrateSchema() after serverKey is guaranteed to exist.
            // Creating it here would crash on pre-1.1.0 databases that still lack that column.
        }
    }

    private fun migrateSchema() {
        // Read the existing columns once, then check each required column against the set.
        val existingColumns = readExistingColumns("items")

        val requiredColumns = listOf(
            "itemId" to "TEXT DEFAULT ''",
            "baseItemId" to "TEXT DEFAULT ''",
            "displayName" to "TEXT DEFAULT ''",
            "itemNbt" to "TEXT DEFAULT ''",
            "count" to "INTEGER DEFAULT 0",
            "chestX" to "INTEGER DEFAULT 0",
            "chestY" to "INTEGER DEFAULT 0",
            "chestZ" to "INTEGER DEFAULT 0",
            "label" to "TEXT DEFAULT ''",
            "serverKey" to "TEXT DEFAULT ''",
            "lastSeen" to "INTEGER DEFAULT 0"
        )

        for ((column, definition) in requiredColumns) {
            if (!existingColumns.contains(column)) {
                connection?.createStatement()?.use { stmt ->
                    stmt.execute("ALTER TABLE items ADD COLUMN $column $definition")
                }
                if (ChestMasterMod.isVerboseLogging()) {
                    ChestMasterMod.LOGGER.info("Database schema updated: added $column to items")
                }
            }
        }

        backfillLegacyRows()

        // Create the serverKey index here — after the column is guaranteed to exist (either it was
        // always present in a fresh schema, or it was just added above via ALTER TABLE).
        // This must NOT live in createTables() because that runs before the column is added for
        // pre-1.1.0 databases, causing "no such column: serverKey" and breaking all subsequent saves.
        connection?.createStatement()?.use { stmt ->
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_items_server ON items(serverKey)")
        }
    }

    private fun readExistingColumns(table: String): Set<String> {
        val columns = mutableSetOf<String>()
        connection?.createStatement()?.use { stmt ->
            stmt.executeQuery("PRAGMA table_info($table)").use { rs ->
                while (rs.next()) {
                    columns.add(rs.getString("name"))
                }
            }
        }
        return columns
    }

    private fun backfillLegacyRows() {
        val sql = """
            UPDATE items
            SET
                itemId = COALESCE(NULLIF(itemId, ''), NULLIF(baseItemId, ''), ''),
                displayName = COALESCE(NULLIF(displayName, ''), NULLIF(baseItemId, ''), NULLIF(itemId, ''), 'Unknown Item'),
                baseItemId = COALESCE(baseItemId, ''),
                itemNbt = COALESCE(itemNbt, ''),
                label = COALESCE(label, ''),
                serverKey = COALESCE(serverKey, ''),
                lastSeen = COALESCE(lastSeen, 0)
        """.trimIndent()

        connection?.createStatement()?.use { it.executeUpdate(sql) }
    }

    @Synchronized
    fun saveItems(items: List<ItemRecord>) {
        if (items.isEmpty()) return
        val conn = connection ?: return

        val groupedByChest = items.groupBy { Triple(it.chestX, it.chestY, it.chestZ) }
        // Delete only rows belonging to the same server so multi-server data stays separate.
        val deleteSql = "DELETE FROM items WHERE chestX = ? AND chestY = ? AND chestZ = ? AND serverKey = ?"
        val insertSql = """
            INSERT INTO items
              (itemId, baseItemId, displayName, itemNbt, count, chestX, chestY, chestZ, label, serverKey, lastSeen)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        val previousAutoCommit = conn.autoCommit
        conn.autoCommit = false
        try {
            conn.prepareStatement(deleteSql).use { deleteStmt ->
                conn.prepareStatement(insertSql).use { insertStmt ->
                    for ((coords, chestItems) in groupedByChest) {
                        val serverKey = chestItems.first().serverKey
                        deleteStmt.setInt(1, coords.first)
                        deleteStmt.setInt(2, coords.second)
                        deleteStmt.setInt(3, coords.third)
                        deleteStmt.setString(4, serverKey)
                        deleteStmt.executeUpdate()

                        for (item in chestItems) {
                            insertStmt.setString(1, item.itemId)
                            insertStmt.setString(2, item.baseItemId)
                            insertStmt.setString(3, item.displayName)
                            insertStmt.setString(4, item.itemNbt)
                            insertStmt.setInt(5, item.count)
                            insertStmt.setInt(6, item.chestX)
                            insertStmt.setInt(7, item.chestY)
                            insertStmt.setInt(8, item.chestZ)
                            insertStmt.setString(9, item.label)
                            insertStmt.setString(10, item.serverKey)
                            insertStmt.setLong(11, item.lastSeen)
                            insertStmt.addBatch()
                        }
                    }
                    insertStmt.executeBatch()
                }
            }
            conn.commit()
        } catch (e: Exception) {
            runCatching { conn.rollback() }
            throw e
        } finally {
            runCatching { conn.autoCommit = previousAutoCommit }
        }
    }

    @Synchronized
    fun searchItems(query: String, serverKey: String? = null): List<ItemRecord> {
        val results = mutableListOf<ItemRecord>()
        val serverFilter = if (!serverKey.isNullOrBlank()) "AND (serverKey = ? OR serverKey = '')" else ""
        val sql = """
            SELECT * FROM items
            WHERE (displayName LIKE ? OR itemId LIKE ? OR baseItemId LIKE ? OR label LIKE ?)
            $serverFilter
            ORDER BY displayName
            LIMIT 2000
        """.trimIndent()

        connection?.prepareStatement(sql)?.use { pstmt ->
            val q = "%$query%"
            pstmt.setString(1, q)
            pstmt.setString(2, q)
            pstmt.setString(3, q)
            pstmt.setString(4, q)
            if (!serverKey.isNullOrBlank()) pstmt.setString(5, serverKey)
            pstmt.executeQuery().use { rs ->
                while (rs.next()) {
                    results.add(
                        ItemRecord(
                            id = rs.getInt("id"),
                            itemId = rs.getString("itemId"),
                            baseItemId = rs.getString("baseItemId") ?: "",
                            displayName = rs.getString("displayName"),
                            itemNbt = rs.getString("itemNbt"),
                            count = rs.getInt("count"),
                            chestX = rs.getInt("chestX"),
                            chestY = rs.getInt("chestY"),
                            chestZ = rs.getInt("chestZ"),
                            label = rs.getString("label"),
                            serverKey = rs.getString("serverKey") ?: "",
                            lastSeen = rs.getLong("lastSeen")
                        )
                    )
                }
            }
        }
        return stackSimilarItems(results)
    }

    @Synchronized
    fun resetItems(): Int {
        return resetItemsAndCompact().deletedRecords
    }

    @Synchronized
    fun resetItemsAndCompact(): ResetResult {
        val conn = connection ?: return ResetResult(
            deletedRecords = 0,
            sizeBeforeBytes = 0L,
            sizeAfterBytes = 0L
        )
        val path = dbPath
        val sizeBefore = path?.let { safeFileSize(it) } ?: 0L

        var deleted = 0
        conn.createStatement().use { stmt ->
            deleted = stmt.executeUpdate("DELETE FROM items")
            stmt.executeUpdate("DELETE FROM sqlite_sequence WHERE name = 'items'")
        }

        runCatching {
            conn.createStatement().use { stmt ->
                stmt.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            }
        }.onFailure { error ->
            ChestMasterMod.LOGGER.warn("WAL checkpoint failed during DB reset: ${error.message}")
        }

        runCatching {
            conn.createStatement().use { stmt ->
                stmt.execute("VACUUM")
            }
        }.onFailure { error ->
            ChestMasterMod.LOGGER.warn("VACUUM failed during DB reset: ${error.message}")
        }

        val sizeAfter = path?.let { safeFileSize(it) } ?: 0L
        return ResetResult(
            deletedRecords = deleted,
            sizeBeforeBytes = sizeBefore,
            sizeAfterBytes = sizeAfter
        )
    }

    @Synchronized
    fun findChestLocationsForItem(
        itemId: String,
        baseItemId: String,
        itemNbt: String,
        displayName: String = "",
        serverKey: String? = null
    ): List<ChestLocation> {
        val results = linkedSetOf<ChestLocation>()
        val normalizedItemId = ItemUtils.normalizeSkyblockId(itemId) ?: itemId
        val normalizedBaseItemId = baseItemId.ifBlank { itemId }
        val targetRecord = ItemRecord(
            id = 0,
            itemId = normalizedItemId,
            baseItemId = normalizedBaseItemId,
            displayName = displayName.ifBlank { normalizedItemId },
            itemNbt = itemNbt,
            count = 1,
            chestX = 0,
            chestY = 0,
            chestZ = 0,
            label = ""
        )
        val targetKey = buildStackingKey(targetRecord)

        val serverFilter = if (!serverKey.isNullOrBlank()) "AND (serverKey = ? OR serverKey = '')" else ""
        val sql = """
            SELECT itemId, baseItemId, displayName, itemNbt, count, chestX, chestY, chestZ, label, serverKey
            FROM items
            WHERE (itemId = ? OR baseItemId = ? OR itemId = ? OR baseItemId = ?)
            $serverFilter
        """.trimIndent()

        connection?.prepareStatement(sql)?.use { pstmt ->
            pstmt.setString(1, normalizedItemId)
            pstmt.setString(2, normalizedItemId)
            pstmt.setString(3, normalizedBaseItemId)
            pstmt.setString(4, normalizedBaseItemId)
            if (!serverKey.isNullOrBlank()) pstmt.setString(5, serverKey)
            pstmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val rowRecord = ItemRecord(
                        id = 0,
                        itemId = rs.getString("itemId"),
                        baseItemId = rs.getString("baseItemId") ?: "",
                        displayName = rs.getString("displayName") ?: "",
                        itemNbt = rs.getString("itemNbt") ?: "",
                        count = rs.getInt("count"),
                        chestX = rs.getInt("chestX"),
                        chestY = rs.getInt("chestY"),
                        chestZ = rs.getInt("chestZ"),
                        label = rs.getString("label") ?: "",
                        serverKey = rs.getString("serverKey") ?: ""
                    )

                    if (buildStackingKey(rowRecord) != targetKey) continue

                    val x = rowRecord.chestX
                    val y = rowRecord.chestY
                    val z = rowRecord.chestZ

                    // Ignore legacy rows saved before chest position detection existed.
                    if (x == 0 && y == 0 && z == 0) continue

                    results.add(ChestLocation(x, y, z, rowRecord.label, rowRecord.serverKey))
                }
            }
        }

        return results.toList()
    }

    fun exportToCsv(path: Path, serverKey: String? = null) {
        val items = searchItems("", serverKey)
        Files.newBufferedWriter(path).use { writer ->
            writer.write("Name,Item ID,Count,Chest X,Chest Y,Chest Z,Label,Server,Last Seen\n")
            for (item in items) {
                val lastSeenStr = if (item.lastSeen > 0L) item.lastSeen.toString() else ""
                writer.write(
                    "${csvEscape(item.displayName)},${csvEscape(item.itemId)},${item.count}," +
                        "${item.chestX},${item.chestY},${item.chestZ}," +
                        "${csvEscape(item.label)},${csvEscape(item.serverKey)},$lastSeenStr\n"
                )
            }
        }
    }

    private fun csvEscape(value: String): String {
        if (!value.contains(',') && !value.contains('"') && !value.contains('\n')) return value
        return "\"${value.replace("\"", "\"\"")}\""
    }

    private fun stackSimilarItems(records: List<ItemRecord>): List<ItemRecord> {
        if (records.isEmpty()) return records

        val aggregated = LinkedHashMap<String, ItemRecord>()
        for (record in records) {
            val key = buildStackingKey(record)
            val existing = aggregated[key]
            if (existing == null) {
                aggregated[key] = record
            } else {
                aggregated[key] = existing.copy(
                    count = existing.count + record.count,
                    lastSeen = maxOf(existing.lastSeen, record.lastSeen)
                )
            }
        }

        return aggregated.values.toList()
    }

    private fun buildStackingKey(record: ItemRecord): String {
        val normalizedItemId = ItemUtils.normalizeSkyblockId(record.itemId) ?: record.itemId
        val normalizedBaseItemId = record.baseItemId.ifBlank { record.itemId }
        val extraAttributes = ItemUtils.extractExtraAttributesFromNbtString(record.itemNbt)

        if (extraAttributes == null) {
            return "${normalizedItemId}@@${normalizedBaseItemId}@@${record.itemNbt.ifBlank { "{}" }}"
        }

        val cleaned = extraAttributes.copy()
        sanitizeExtraAttributes(cleaned)
        return "${normalizedItemId}@@${normalizedBaseItemId}@@${cleaned}"
    }

    private fun sanitizeExtraAttributes(tag: CompoundTag) {
        val keys = tag.keySet().toList()
        for (key in keys) {
            if (isVolatileExtraKey(key)) {
                tag.remove(key)
                continue
            }

            when (val nested = tag.get(key)) {
                is CompoundTag -> sanitizeExtraAttributes(nested)
                is ListTag -> sanitizeListTag(nested)
                else -> {}
            }
        }
    }

    private fun sanitizeListTag(listTag: ListTag) {
        for (i in 0 until listTag.size) {
            when (val nested = listTag.get(i)) {
                is CompoundTag -> sanitizeExtraAttributes(nested)
                is ListTag -> sanitizeListTag(nested)
                else -> {}
            }
        }
    }

    private fun isVolatileExtraKey(rawKey: String): Boolean {
        val key = rawKey.lowercase(Locale.ROOT)
        if (key.contains("uuid")) return true

        return key in volatileExtraKeys
    }

    private val volatileExtraKeys = setOf(
        "timestamp",
        "timestamp_utc",
        "created",
        "created_at",
        "creation_date",
        "created_date",
        "obtained",
        "obtained_at",
        "pickup_time",
        "profile",
        "profile_id",
        "owner",
        "owner_name",
        "spawned_for",
        "spawnedfor",
        "new_years_cake",
        "personalized",
        "personalized_by",
        "recipient"
    )

    private fun safeFileSize(path: Path): Long {
        return runCatching {
            if (Files.exists(path)) Files.size(path) else 0L
        }.getOrDefault(0L)
    }
}
