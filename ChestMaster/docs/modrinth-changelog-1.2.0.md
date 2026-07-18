# 1.2.0

**Supported: MC 26.1–26.1.2 and 26.2 (Fabric).** Older 1.21.x builds are no longer maintained.

## Added
- **MC 26.2 support** (new rendering pipeline port)
- **Mod Menu config screen**: auto-scan, verbose logging, default sort, price mode, price refresh interval
- **Pet valuation** by type + tier (Coflnet fallback — bulk LBIN sources don't cover pets)
- **Attribute shard valuation** via Bazaar `SHARD_*` products
- **Open on Bazaar / AH button** in the detail panel (`/bz` / `/ahs`)
- **Live price updates** in the open GUI as data arrives
- Mod icon, keybinding translations (EN/RU)

## Fixed
- Ender Chest pages, dungeon/Kuudra reward chests and Hypixel menu GUIs are no longer scanned; old junk rows are cleaned from the database automatically on startup
- Crash on empty config file; config now always UTF-8
- CSV export no longer aggregates rows or silently caps at 2000 — full per-chest data
- A 1.21.10 JAR could load (and crash) on newer versions — Minecraft version is now pinned per build
- Search query no longer resets on window resize; price mode (Sell Offer / Buy Order) persists
- Coflnet rate limiting (HTTP 403) handled with request spacing and backoff
- `/cm reset` now actually shrinks the database file (WAL checkpoint after VACUUM)

## Changed
- **JAR size: 13.6 MB → 3.7 MB** (dropped SQLite natives for platforms Minecraft never runs on)
