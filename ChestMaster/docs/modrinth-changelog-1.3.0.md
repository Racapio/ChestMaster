# 1.3.0

**Supported: Minecraft 26.1–26.1.2 and 26.2 (Fabric).**

> ChestMaster now builds on **SkyBlockAPI** (the library behind SkyOcean, Catharsis and SkyBlockPV). It is **bundled** — nothing extra to install. The JAR grew to ~8.5 MB because of it.

Big thanks to [SkyOcean](https://modrinth.com/mod/skyocean) — a lot of this update is ported from its open-source (MIT) code.

## 🔄 Reworked chest tracking
- The chest position now comes from **right-clicking the chest** — exact coordinates, no guessing
- A container is indexed only if it is a **real chest** (vanilla chest title) — Hypixel menus like Loadouts, auction dialogs or Sack of Sacks can never get in, **in any client language**
- Contents are saved **when you close the chest**, so everything is always loaded
- Each half of a double chest remembers its own items — highlights point at the right half
- **Breaking a chest removes it** from the index; items you take out disappear from the index too
- Tracking works **only on your Private Island**

## 💰 Much better item valuation
Prices now come from SkyBlockAPI's item value calculator: enchantments at any level (**Chimera V** is no longer worth 0!), stars and master stars, gemstones, reforges, drill & rod parts, Necron scrolls, runes, dyes, skins, pets and more. The detail panel shows where the value comes from.

## 🆕 New features
- **In-inventory highlight** — select an item in the GUI and every matching stack lights up in any open inventory, together with the chest markers in the world
- **Auto-clear** — highlights clear themselves after a configurable time (Mod Menu → *Clear highlights after*, default 1 min)
- **Search the hovered item** — hover any item in an inventory and press the *Open ChestMaster* key (set it in Controls) to search for it instantly

---

### Русский
- **Новый трекинг сундуков**: позиция берётся из клика по сундуку, в базу попадают только настоящие сундуки (меню Hypixel — никогда, на любом языке), сохранение при закрытии, у двойного сундука каждая половина хранит свои предметы, сломанный сундук удаляется из базы, работает только на своём Private Island.
- **Оценка через SkyBlockAPI**: правильно считаются зачарования любого уровня (Химера V больше не 0), звёзды, гемы, реформы, части дрелей/удочек, свитки Necron, руны, красители, скины, петы и т.д.
- **Подсветка найденных предметов** прямо в открытых инвентарях + авто-сброс подсветки по таймеру.
- **Поиск по наведению**: навёл на предмет, нажал клавишу ChestMaster — открылся поиск этого предмета.
- Мод теперь использует библиотеку **SkyBlockAPI** — она **встроена**, отдельно ставить ничего не нужно (из-за неё JAR вырос до ~8.5 МБ).
