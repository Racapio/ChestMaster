# ChestMaster

Client-side Fabric mod for Hypixel SkyBlock:
- scans chest contents into a local SQLite database,
- lets you search saved items quickly,
- estimates item value (Bazaar / Auction / NPC),
- highlights chest locations for selected items.

The mod is read-only QoL: no automation, no movement/combat macros, no packet spoofing.

## Supported versions

- Minecraft: `1.21.10`, `1.21.11`
- Loader: `Fabric`

## Installation (player)

1. Download the jar for your exact Minecraft version from GitHub Releases.
2. Put it into your instance `mods` folder.
3. Remove old `chestmaster*.jar` files before updating.
4. Launch the game.

Important:
- `1.21.10` instance -> use `...+mc1.21.10.jar`
- `1.21.11` instance -> use `...+mc1.21.11.jar`

## Commands

- `/cm` or `/chestmaster` - open GUI
- `/cm s on|off|status|now` - auto scan control
- `/cm db` - show database path
- `/cm reset confirm` - clear DB and compact file
- `/cm p status|reload` - price cache status/reload
- `/cm m clear` - clear active chest markers
- `/cm logs on|off|status` - verbose logging toggle

## Features

- Auto-scan of real chest containers
- Local SQLite storage of scanned items
- Fast search with item stacking/aggregation
- Market source classification (Bazaar / Auction / NPC / Unknown)
- Tooltip-aware item labels (e.g. books)
- Rarity-colored item names in GUI
- Interactive list scrollbar
- Chest marker auto-clear on world/server change

## Build from source

This repository contains the mod project in the `ChestMaster/` directory.

```powershell
cd ChestMaster
```

Build default target:

```powershell
./gradlew.bat build
```

Build both Minecraft variants:

```powershell
./scripts/build-multi-version.ps1
```

Build outputs:

- `ChestMaster/dist/multi-version/chestmaster-1.0.0+mc1.21.10.jar`
- `ChestMaster/dist/multi-version/chestmaster-1.0.0+mc1.21.11.jar`

## License

MIT (see `ChestMaster/LICENSE.txt`).
