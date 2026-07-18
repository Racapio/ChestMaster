# ChestMaster

**Client-side chest indexing and item valuation for Hypixel SkyBlock.**

Open your storage chests once — ChestMaster remembers everything in them, tells you what it's worth, and shows you exactly which chest an item is in.

## Features

- 🗃️ **Auto-scan** — every chest you open is indexed into a local SQLite database
- 🔍 **Fast search** — find any item across all your scanned chests (`/cm` or a hotkey)
- 💰 **Valuation** — Bazaar, Auction House lowest-BIN and NPC prices, including:
  - pets (priced by type + tier)
  - attribute shards (Bazaar `SHARD_*` products)
  - enchanted books (priced by the enchant inside)
  - stars, recombs, Hot Potato Books, gemstones, ability scrolls, runes and more
- 📦 **Chest highlighting** — click an item and glowing markers show which chests contain it
- 🛒 **Open on market** — one click runs `/bz <item>` or `/ahs <item>` for the selected item
- 📊 **Sorting & filters** — by price/name/count, filter by price source (Bazaar/AH/Unknown)
- 🌐 **Per-server tracking** — data from different servers never mixes
- 📤 **CSV export** — `/cm export` dumps your storage to a spreadsheet
- ⚙️ **Mod Menu config** — auto-scan, default sort, price mode, refresh interval, verbose logging

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
- Java 25 (Minecraft 26.x)

| Minecraft | JAR |
|---|---|
| 26.1 – 26.1.2 | `chestmaster-mc26.1.2-*.jar` |
| 26.2 | `chestmaster-mc26.2-*.jar` |

---

### Русское описание

Клиентский мод для Hypixel SkyBlock: индексирует содержимое ваших сундуков в локальную базу, даёт быстрый поиск по всем предметам, оценивает их стоимость (Базар / Аукцион / NPC — включая петов и шарды) и подсвечивает в мире сундук, где лежит нужный предмет. Кнопка в GUI сразу открывает предмет на Базаре или в поиске Аукциона. Настройка — через Mod Menu. Только чтение: никакой автоматизации и макросов.

Нашли баг? Discord: **Racap**
