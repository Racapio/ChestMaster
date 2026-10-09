# 1.3.0

**Supported: Minecraft 26.1–26.1.2 and 26.2 (Fabric).**

> ChestMaster now runs on **SkyBlockAPI** — the library used by many SkyBlock mods. It is **bundled**, nothing extra to install (the JAR grew to ~8.5 MB because of it).

## 🔄 Reworked chest tracking
- The chest position comes from **right-clicking the chest** — exact coordinates, no guessing
- Only **real chests** are indexed — Hypixel menus like Loadouts, auction dialogs or Sack of Sacks can never get in, **in any client language**
- Contents are saved **when you close the chest**, so everything is always loaded
- Each half of a double chest remembers its own items
- **Breaking a chest removes it** from the index; items you take out disappear too
- Tracking works **only on your Private Island**

## 💰 Much better and faster item valuation
- All prices now come from SkyBlockAPI: Bazaar, lowest-BIN, NPC prices and a full item value calculator
- Enchantments at any level are valued correctly — **Chimera V is no longer worth 0!** Also stars & master stars, gemstones, reforges, drill & rod parts, Necron scrolls, runes, dyes, skins and pets
- The detail panel shows where the value comes from
- No more endless "Loading..." — prices show up as soon as the market data is there

## ✨ New highlighting
- **Chest markers** are now a translucent **rainbow box visible through walls**; double chests light up as a whole
- **In-inventory highlight** — select an item in the GUI and every matching stack lights up in any open inventory
- **Auto-clear** — highlights clear themselves after a configurable time (Mod Menu → *Clear highlights after*, default 1 min)

## ⌨️ Search the hovered item
Hover any item in an inventory and press the *Open ChestMaster* key (set it in Controls) to search for it instantly.

---

### Русский
- **Новый трекинг сундуков**: позиция берётся из клика по сундуку, в базу попадают только настоящие сундуки (меню Hypixel — никогда, на любом языке), сохранение при закрытии, у двойного сундука каждая половина хранит свои предметы, сломанный сундук удаляется из базы, работает только на своём Private Island.
- **Быстрая и точная оценка**: все цены теперь берутся из SkyBlockAPI — зачарования любого уровня (Химера V больше не 0), звёзды, гемы, реформы, части дрелей/удочек, свитки Necron, руны, красители, скины, петы. Больше никакого бесконечного «Loading...».
- **Новая подсветка**: сундук в мире — полупрозрачный радужный блок, видимый сквозь стены; найденные предметы подсвечиваются прямо в инвентарях; подсветка сама сбрасывается по таймеру.
- **Поиск по наведению**: навёл на предмет, нажал клавишу ChestMaster — открылся поиск этого предмета.
- Библиотека **SkyBlockAPI встроена** — отдельно ставить ничего не нужно.
