# Changelog — ChestMaster

> Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).  
> Формат — [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

---

## [1.2.0] — 2026-07-18

**Supported Minecraft versions / Поддерживаемые версии:**
`26.1` – `26.1.2` · `26.2` (older `1.21.x` builds are no longer maintained)

### 🆕 Added / Добавлено

| Feature | Description |
|---|---|
| **MC 26.2 support** | New version profile `-PmcVersion=26.2` (Fabric API 0.155.0, Loader 0.19.3) |
| **Mod Menu integration** | Config screen via [Mod Menu](https://modrinth.com/mod/modmenu): auto-scan, verbose logging, default sort, price mode, price refresh interval |
| **Pet valuation** | Pets are priced by type + tier ("TYPE;TIER" market keys); missing pet prices are fetched per pet from the Coflnet API since bulk LBIN dumps omit pets. Failed fetches are now logged and retried on every GUI open |
| **Attribute shard valuation** | Shards ("Night Squid Shard", "Fire Eel Shard", …) are priced via their `SHARD_*` Bazaar products, derived from the item name |
| **Open on Bazaar / AH** | Button in the detail panel: Bazaar items run `/bz <item>`, Auction items run `/ahs <item>` (needs Booster Cookie, as usual for these commands) |
| **Live price updates in GUI** | The item list refreshes automatically when new price data arrives (initial load, pet fetches, manual refresh) instead of waiting for a reopen |
| **Mod icon** | ChestMaster now shows its own icon in Mod Menu instead of the broken-icon placeholder |
| **Persistent price mode** | Sell Offer / Buy Order choice is now saved between sessions |
| **Configurable price refresh** | `bazaarUpdateInterval` (seconds) now actually controls how often market prices are re-fetched |
| **Keybinding translations** | "Open ChestMaster" key is now properly named in Options → Controls (EN/RU) |

### 🧹 Changed / Изменено

| Change | Description |
|---|---|
| **JAR size: 13.6 MB → 3.7 MB** | The bundled SQLite driver no longer ships native builds for Android/FreeBSD/Musl/32-bit — only Windows, macOS and Linux on x86_64 + aarch64 |
| **26.x-only codebase** | Dropped 1.21.10/1.21.11 profiles and compat sources; single consolidated build script (Loom 1.16.2, Java 25) |
| **Container block-list** | Ender Chest pages, dungeon/Kuudra reward chests (Wood/Gold/…/Paid/Free Chest) and Hypixel menu GUIs (titles with `➜`, bazaar pages, Confirm dialogs) are no longer scanned |
| **DB auto-cleanup** | On startup, records previously saved from those non-storage containers are removed from the database automatically |

### 🐛 Fixed / Исправлено

| Bug | Description |
|---|---|
| **Crash on empty config file** | `chestmaster.json` with empty content made the mod crash on start — now falls back to defaults |
| **Config charset** | Config was read/written with the OS charset (e.g. cp1251) — now always UTF-8 |
| **CSV export data loss** | Export aggregated stacked items (losing per-chest coordinates) and silently capped at 2000 rows — now exports raw per-chest rows without a limit |
| **Wrong-version JAR accepted** | `fabric.mod.json` required `minecraft >= X`, so a 1.21.10 JAR would load (and crash) on 1.21.11 — version is now pinned per build |
| **Search reset on window resize** | Resizing the game window cleared the GUI search query |
| **Premature "prices loaded"** | Pressing Refresh while prices were still loading reset the async counter and flipped the loaded flag early |
| **Bazaar request could hang** | The Bazaar HTTP request had no per-request timeout (LBIN/NPC had 15 s) |
| **DB not closed on exit** | SQLite connection is now closed on shutdown after pending writes drain — WAL checkpointed cleanly |
| **Dead config fields** | Removed unused `showTotalValue` / `autoScanChests` |

---

## [1.1.0] — 2026-05-21

**Supported Minecraft versions / Поддерживаемые версии:**  
`1.21.10` · `1.21.11` · `26.1` · `26.1.2`

---

### 🆕 Added / Добавлено

| Feature | Description |
|---|---|
| **Multi-version builds** | Single codebase, separate JARs per MC version via `./gradlew build -PmcVersion=X` |
| **MC 26.1 / 26.1.2 support** | First unobfuscated Minecraft version — full port with updated Fabric + Loom APIs. Requires Java 25 |
| **Per-server item tracking** | Scanned data is scoped per server — no cross-server data mixing |
| **"Last seen" timestamps** | Each chest record stores when it was last scanned |
| **CSV export** | `/cm export` — exports current server data to `chestmaster-export.csv`. `/cm export all` — exports all servers |
| **Auto-Scan button in GUI** | Toggle auto-scan directly from the ChestMaster screen, no commands needed |
| **Sort modes** | Sort results by price ↓, name A–Z, or count ↓ — choice saved between sessions |
| **Source filter** | Filter item list by price source: All / Bazaar / Auction / Unknown |
| **Persistent settings** | Sort mode, auto-scan state, and verbose logging are now saved across game restarts |
| **LBIN price fallback** | If `moulberry.codes` is unreachable, prices are fetched from `sky.coflnet.com` instead |
| **AH unavailability warning** | GUI shows a notice when Auction House prices are temporarily unavailable |
| **Verbose logging toggle** | `/cm logs on\|off\|status` — enable/disable detailed mod logging at runtime |

---

### ⚡ Improved / Улучшено

| Improvement | Description |
|---|---|
| **Background DB writes** | Chest data is written to SQLite on a dedicated background thread (`ChestMaster-DB`) — no game stutter on scan |
| **SQLite WAL mode** | Write-Ahead Logging enabled — prevents database lock errors during concurrent access |
| **Search result cap** | Queries limited to 2 000 results — prevents GUI freeze on large inventories |
| **Chest position cache** | During retry scans, the last known chest position is reused for 1 second instead of re-scanning 1 521 blocks |
| **Rarity detection** | `SkyblockRarity` alias lists are sorted once at startup — faster item name parsing |
| **Binary-search ellipsize** | `ellipsize()` in the GUI uses binary search (O log n) instead of linear character iteration |
| **Price loading reliability** | `pendingSourceCount` now correctly waits for all 3 sources (Bazaar, LBIN, NPC) before marking prices as loaded |

---

### 🐛 Fixed / Исправлено

| Bug | Description |
|---|---|
| **Crash on item click — MC 26.x** | `GuiGraphicsExtractor` renamed methods: `renderItem` → `item`, `renderItemDecorations` → `itemDecorations`, `drawString` → `text`, `drawCenteredString` → `centeredText` |
| **Crash on chest highlight — MC 1.21.x / 26.x** | `LINES` render type requires a `LineWidth` vertex attribute — added `.setLineWidth(2.5f)` to every vertex in all compat builds |
| **"Incompatible mods" on MC 26.1** | `fabric.mod.json` was requiring `>=26.1.2`; fixed to accept any `>=26.1` |
| **Crash on keybinding — all versions** | `KeyMapping.Category` is now properly registered via `KeyMapping.Category.register(...)` instead of using a raw string |
| **Chest highlight not rendering — MC 1.21.11** | Switched from `AFTER_TRANSLUCENT_BLOCKS` (removed) to `AFTER_ENTITIES` render event |
| **Price source count bug** | Fixed incorrect tracking of async price-load completion causing prices to appear loaded prematurely |
| **AH prices always "unavailable" (LBIN sources broken)** | `moulberry.codes` unreachable; `sky.coflnet.com/api/auctions/bin` returns 404; `hysky.de/api/auctions` was wrong path (404). Fixed: primary source stays `moulberry.codes/lowestbin.json`, fallback is now the correct `hysky.de/api/auctions/lowestbins` — a flat `{id: price}` map confirmed working |

---

### 🔧 Commands reference / Команды

```
/cm                     — open the ChestMaster GUI
/cm search <item>       — search for an item across all scanned chests
/cm m clear             — clear active chest highlight markers
/cm on / /cm off        — enable / disable auto-scan
/cm export              — export current server data to CSV
/cm export all          — export all servers data to CSV
/cm logs on|off|status  — toggle verbose logging
```

---

## [1.0.0] — initial release

### Added / Добавлено

- Client-side chest scanner for Hypixel SkyBlock — automatically indexes items as you open chests
- Item search via `/cm` command or configurable hotkey (Options → Controls)
- Price valuation: Bazaar sell offer / buy order prices and Auction House lowest-bin
- Green outline box rendered around chest locations in the world when an item is selected
- Chest coordinate list shown in the detail panel (up to 3 locations + overflow count)
- SQLite local database — data persists between game sessions
- Auto-scan mode — every opened chest is scanned automatically

---

*Built with [Fabric](https://fabricmc.net/) · Requires [Fabric API](https://modrinth.com/mod/fabric-api) and [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin)*
