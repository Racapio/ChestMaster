# ChestMaster

**Client-side chest indexing and item valuation for Hypixel SkyBlock.**

Open your storage chests once — ChestMaster remembers everything in them, tells you what it's worth, and shows you exactly which chest an item is in.

## Features

- 🗃️ **Auto-scan** — every chest you open on your Private Island is indexed into a local SQLite database (exact position from the click, saved on close, menus never get in)
- 🔍 **Fast search** — find any item across all your scanned chests (`/cm` or a hotkey)
- 💰 **Valuation** — Bazaar, Auction House lowest-BIN and NPC prices via SkyBlockAPI, including:
  - enchantments at any level, stars & master stars, gemstones, reforges, drill/rod parts, Necron scrolls, runes, dyes, skins
  - pets (by type + tier) and attribute shards
- 📦 **Chest highlighting** — click an item and a rainbow box, visible through walls, shows which chests contain it
- ✨ **In-inventory highlight** — matching stacks light up in any open inventory; highlights clear themselves after a set time
- ⌨️ **Search the hovered item** — hover an item in any inventory and press the ChestMaster key
- 🛒 **Open on market** — one click runs `/bz <item>` or `/ahs <item>` for the selected item
- 📊 **Sorting & filters** — by price/name/count, filter by price source (Bazaar/AH/Unknown)
- 🌐 **Per-server tracking** — data from different servers never mixes
- 📤 **CSV export** — `/cm export` dumps your storage to a spreadsheet
- ⚙️ **Mod Menu config** — auto-scan, default sort, price mode, highlight time, verbose logging

## What it does NOT do

ChestMaster is **read-only QoL**: no automation, no macros, no packet spoofing.
It only reads container contents that are already visible on your client and stores them locally.

## Commands

```
/cm                     — open the ChestMaster GUI
/cm s on|off|status|now — auto-scan control
/cm export [all]        — export to CSV
/cm m clear             — clear chest highlight markers
/cm reset confirm       — wipe the local database
```

## Requirements

- Fabric Loader + [Fabric API](https://modrinth.com/mod/fabric-api)
- [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin)
- [Mod Menu](https://modrinth.com/mod/modmenu) (optional, for the config screen)
- SkyBlockAPI is bundled — nothing extra to install
- Java 25 (Minecraft 26.x)

| Minecraft | JAR |
|---|---|
| 26.1 – 26.1.2 | `chestmaster-mc26.1.2-*.jar` |
| 26.2 | `chestmaster-mc26.2-*.jar` |

---

### Русское описание

Клиентский мод для Hypixel SkyBlock: индексирует содержимое сундуков на вашем Private Island в локальную базу, даёт быстрый поиск по всем предметам, оценивает их стоимость (Базар / Аукцион / NPC — включая петов и шарды) и подсвечивает в мире сундук, где лежит нужный предмет. Найденные предметы подсвечиваются прямо в инвентарях, поиск по наведению — одной клавишей. Кнопка в GUI сразу открывает предмет на Базаре или в поиске Аукциона. Настройка — через Mod Menu. Только чтение: никакой автоматизации и макросов.

Нашли баг? Discord: **Racap**

