## ChestMaster 1.3.1

**Supported: Minecraft 26.1–26.1.2, 26.2 and 26.3 (Fabric).**

### Added
- **Minecraft 26.3** support
- **Shard prices** by attribute (Bazaar), including renamed shards
- **Level-aware pet prices** (type, rarity and level range, plus held item and skin)
- Automatic fallback source for auction prices
- Full value breakdown with item icons (no more "+N more")
- Config option to exclude applied dyes and skins from item values

### Fixed
- Chests outside your Private Island (e.g. dungeon secret chests) are no longer indexed; old stray entries are cleaned up once
- Chest markers are drawn only on your Private Island
- Emptied chests now clear their entries
- Search results are no longer capped at 2000 rows
- Unique items (same UUID) are listed once
- The GUI no longer jumps to the top when prices update
- Champion, Toxophilite, Compact and other use-leveled enchantments are now valued
- Enchanted books are no longer listed as "No price"
- Item names keep their original colours (pet level, stars)
