# ChestMaster

Client-side Fabric mod for Hypixel SkyBlock written entirely with AI assistance — fully tested and working. If you find any bugs, reach out on Discord: **Racap**.

- Scans chest contents into a local SQLite database
- Lets you search saved items quickly
- Estimates item value (Bazaar / Auction House / NPC)
- Highlights chest locations for selected items in the world

The mod is read-only QoL: no automation, no movement/combat macros, no packet spoofing.

## Supported versions

| Minecraft | Loader |
|---|---|
| `26.1` – `26.1.2` | Fabric (requires Java 25) |
| `26.2` | Fabric (requires Java 25) |

> Older builds for MC `1.21.10` / `1.21.11` are still available in past releases but are no longer maintained.

## Installation

1. Download the JAR for your Minecraft version from [GitHub Releases](../../releases).
2. Place it in your instance `mods` folder.
3. Remove any old `chestmaster*.jar` before updating.
4. Launch the game.

| Instance | JAR to use |
|---|---|
| `26.1` / `26.1.1` / `26.1.2` | `...+mc26.1.2.jar` |
| `26.2` | `...+mc26.2.jar` |

## Commands

```
/cm                     — open the ChestMaster GUI
/cm search <item>       — search for an item across all scanned chests
/cm on / /cm off        — enable / disable auto-scan
/cm export              — export current server data to CSV
/cm export all          — export all servers data to CSV
/cm m clear             — clear active chest highlight markers
/cm logs on|off|status  — toggle verbose logging
```

## Features

- Auto-scan of opened chest containers
- Local SQLite database — data persists between sessions
- Per-server item tracking — no cross-server data mixing
- Fast search with item stacking and aggregation
- Market price sources: Bazaar, Auction House lowest-bin, NPC
- "Last seen" timestamps for each scanned chest
- CSV export of scanned data
- Sort results by price, name, or count — saved between sessions
- Filter by price source: All / Bazaar / Auction / Unknown
- Rarity-colored item names in the GUI
- Auto-Scan toggle button directly in the GUI
- Chest highlights auto-clear on world/server change
- Settings persist across game restarts

## Safety and Hypixel

ChestMaster is a **read-only QoL** mod — it only reads client-visible container data and stores it locally:

- no movement or combat automation
- no macro behavior
- no packet spoofing or bypass logic

This is not an official legal guarantee. Always follow the latest Hypixel policies.

- [Hypixel Server Rules](https://support.hypixel.net/hc/en-us/articles/4427624493330-Hypixel-Server-Rules)
- [Hypixel Allowed Modifications](https://support.hypixel.net/hc/en-us/articles/6472550754962-Hypixel-Allowed-Modifications)

## Build from source

```powershell
cd ChestMaster
./gradlew.bat build                      # default profile (26.1.2)
./gradlew.bat build -PmcVersion=26.1.2
./gradlew.bat build -PmcVersion=26.2
```

Requires Java 25 (Gradle toolchain resolves it automatically).

Build outputs go to `ChestMaster/build/libs/`.

## License

MIT (see `ChestMaster/LICENSE.txt`).
