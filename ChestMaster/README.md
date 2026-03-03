# ChestMaster (Fabric, 1.21.10 / 1.21.11)

Client-side SkyBlock helper for indexing chest contents, searching saved items, and estimating value.

## Features

- Auto-scan real chest containers into local SQLite DB.
- Search and aggregate saved items.
- Value estimation from Bazaar/Auction/NPC data.
- Filter view by source: `All`, `Bazaar`, `Auction`.
- Highlight saved chest locations for selected items.
- DB reset with real file compaction (`VACUUM`).

## Commands

- `/cm` or `/chestmaster`: open GUI.
- `/cm s on|off|status|now`: auto-scan control.
- `/cm db`: show DB path.
- `/cm reset confirm`: clear DB and compact file.
- `/cm p status|reload`: price cache status/reload.
- `/cm m clear`: clear highlighted markers.
- `/cm logs on|off|status`: toggle verbose ChestMaster logs.

## Logging

Default logging is intentionally quiet for release use.

- Frequent scan and pricing logs are disabled by default.
- Enable verbose logs:
  `/cm logs on`
  or `config/chestmaster.json` -> `"verboseLogging": true`

## Safety and Hypixel

ChestMaster is designed as a **read-only QoL** mod:

- no movement automation
- no combat automation
- no macro behavior
- no packet spoofing or bypass logic

It only reads client-visible container data and stores it locally.

Important: this is not an official legal/rule guarantee. Final responsibility is on the player account owner. Always follow the latest Hypixel policies and allowed-mod guidance.

- Hypixel Rules: https://support.hypixel.net/hc/en-us/articles/4427624493330-Hypixel-Server-Rules
- Hypixel Allowed Modifications: https://support.hypixel.net/hc/en-us/articles/6472550754962-Hypixel-Allowed-Modifications

## Build

Default target (from `gradle.properties`, currently `1.21.11`):

```powershell
./gradlew.bat build
```

Build for a specific Minecraft version:

```powershell
./gradlew.bat build -Pminecraft_version=1.21.10 -Pfabric_version=0.138.0+1.21.10 -Pkotlin_loader_version=1.13.8+kotlin.2.3.0 -Pmod_version=1.0.3+mc1.21.10
./gradlew.bat build -Pminecraft_version=1.21.11 -Pfabric_version=0.141.3+1.21.11 -Pkotlin_loader_version=1.13.9+kotlin.2.3.10 -Pmod_version=1.0.3+mc1.21.11
```

Build both versions in one command:

```powershell
./scripts/build-multi-version.ps1
```

Output jars will be copied to:

`dist/multi-version`

Important for MultiMC/Prism:
- Use the exact jar for your instance version from `dist/multi-version`.
- Remove old `chestmaster*.jar` files from `mods` before copying a new one.
- `1.21.10` instance must use `chestmaster-...+mc1.21.10.jar`.
- `1.21.11` instance must use `chestmaster-...+mc1.21.11.jar`.
