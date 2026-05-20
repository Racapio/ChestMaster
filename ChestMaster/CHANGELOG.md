# ChestMaster — Changelog / История изменений

---

## v1.1.0 — Multi-version release / Мульти-версионный релиз

**Supported / Поддерживаемые версии MC:** `1.21.10` · `1.21.11` · `26.1` *(26.1 requires separate build)*

---

### 🇷🇺 Русский

#### Исправления и стабильность
- **Исправлен рендеринг маркеров сундуков** — переписан `ChestLocationHighlighter` под актуальный Fabric API
  (`WorldRenderEvents.AFTER_ENTITIES`, `RenderTypes.LINES` / `RenderType.LINES` в зависимости от версии MC).
  Маркеры теперь корректно отображаются в мире в виде зелёных рамок вокруг сундуков.
- **Исправлен биндинг горячей клавиши** — `KeyMapping.Category` теперь создаётся правильно через
  `KeyMapping.Category.register(...)` вместо передачи строки. Категория «ChestMaster» видна в
  «Настройки → Управление».
- **Поддержка 1.21.10 и 1.21.11** — добавлен уровень совместимости (`VersionHelper`) для работы
  с двумя вариантами Minecraft API одновременно.

#### Производительность
- **Кэширование позиции сундука** — при повторных попытках сканирования позиция сундука
  переиспользуется в течение 1 секунды вместо повторного обхода 1521 блока.
- **Предкомпиляция редкостей** — список алиасов редкостей (`SkyblockRarity`) сортируется один раз
  при запуске, что ускоряет разбор названий предметов.
- **Бинарный поиск при обрезке текста** — `ellipsize()` в GUI использует бинарный поиск
  вместо линейного перебора символов.

#### База данных
- **WAL-режим SQLite** — включён режим Write-Ahead Logging для предотвращения блокировок
  при одновременной записи и чтении.
- **Поддержка `serverKey`** — каждая запись привязана к серверу/миру, что исключает
  смешивание данных с разных серверов.
- **Поле `lastSeen`** — время последнего сканирования хранится для каждого предмета.
- **Лимит запросов** — поиск теперь ограничен 2000 записями для предотвращения зависания GUI.
- **Экспорт в CSV** — команда `/cm export` сохраняет все данные в `chestmaster-export.csv`.
- **Принудительное переиспользование потоков** — запись в БД переведена с `Thread.start()` на
  `ExecutorService` (один фоновый поток-демон `ChestMaster-DB`).

#### Ценообразование (Bazaar / AH)
- **Исправлена логика загрузки цен** — счётчик источников (`pendingSourceCount`) теперь правильно
  отслеживает завершение всех 3 источников (Bazaar, LBIN, NPC).
- **Резервный источник LBIN** — при недоступности `moulberry.codes` используется `sky.coflnet.com`.
- **Предупреждение в GUI** — если цены AH недоступны, в интерфейсе отображается соответствующее
  сообщение.

#### Команды
- `/cm export` — экспорт текущего сервера в CSV.
- `/cm export all` — экспорт всех серверов в CSV.
- `/cm on` / `/cm off` — состояние автосканирования сохраняется между сессиями.
- `/cm logs on|off|status` — управление verbose-логированием.

#### GUI
- **Кнопка Auto-Scan** — добавлена кнопка включения/выключения прямо в интерфейсе.
- **Сортировка** — добавлена сортировка по цене, имени и количеству (`SortMode`).
- **Фильтрация по серверу** — результаты отображаются только с текущего сервера.
- **Панель деталей** — показывает координаты до 3 сундуков, содержащих искомый предмет.
- **Предупреждение о ценах** — отображается, если AH-цены временно недоступны.

#### Мульти-версионная сборка
- Команды для сборки JAR под конкретную версию:
  ```
  ./gradlew build -PmcVersion=1.21.10
  ./gradlew build -PmcVersion=1.21.11
  ./gradlew build -PmcVersion=26.1   # требует Fabric API для 26.1
  ```
- Заполните `versions/26.1.properties` версиями Fabric, когда они будут доступны.

---

### 🇬🇧 English

#### Fixes & Stability
- **Fixed chest-marker rendering** — rewrote `ChestLocationHighlighter` against the current Fabric API
  (`WorldRenderEvents.AFTER_ENTITIES`, `RenderTypes.LINES` / `RenderType.LINES` per MC version).
  Markers now appear as green outlines around chests in the world.
- **Fixed keybinding category** — `KeyMapping.Category` is now created correctly via
  `KeyMapping.Category.register(...)` instead of passing a raw string. The "ChestMaster" category
  appears under Options → Controls.
- **1.21.10 and 1.21.11 support** — added a `VersionHelper` compatibility shim that bridges
  the two different Minecraft API surfaces.

#### Performance
- **Chest-position cache** — during retry scanning, the chest position is reused for up to 1 second
  instead of re-scanning 1 521 blocks on every tick.
- **Pre-sorted rarity aliases** — `SkyblockRarity` alias lists are sorted once at startup,
  speeding up item-name parsing.
- **Binary-search ellipsize** — `ellipsize()` in the GUI now uses binary search instead of
  iterating character by character.

#### Database
- **SQLite WAL mode** — Write-Ahead Logging prevents lock contention between concurrent
  writes and reads.
- **`serverKey` support** — every record is scoped to a server/world, preventing data mixing
  across servers.
- **`lastSeen` field** — the last scan timestamp is stored per item.
- **Query limit** — searches are capped at 2 000 results to prevent GUI freezes.
- **CSV export** — `/cm export` writes all data to `chestmaster-export.csv`.
- **Thread-pool DB writes** — background writes use a single-thread `ExecutorService`
  (`ChestMaster-DB` daemon) instead of raw `Thread.start()`.

#### Pricing (Bazaar / AH)
- **Fixed price-loading logic** — `pendingSourceCount` now correctly tracks completion of all
  3 sources (Bazaar, LBIN, NPC).
- **LBIN fallback** — if `moulberry.codes` is unreachable, `sky.coflnet.com` is tried instead.
- **GUI warning** — a notice appears in the UI when AH prices are unavailable.

#### Commands
- `/cm export` — export current server's data to CSV.
- `/cm export all` — export all servers' data to CSV.
- `/cm on` / `/cm off` — auto-scan state now persists across game restarts.
- `/cm logs on|off|status` — toggle verbose logging at runtime.

#### GUI
- **Auto-Scan button** — toggle auto-scan directly from the GUI without using commands.
- **Sort modes** — sort results by price, name, or count (`SortMode`).
- **Server filter** — results show only the current server's data.
- **Detail panel** — shows coordinates of up to 3 chests containing the searched item.
- **Price warning** — displayed when AH prices are temporarily unavailable.

#### Multi-version builds
- Build JARs for a specific Minecraft version:
  ```
  ./gradlew build -PmcVersion=1.21.10
  ./gradlew build -PmcVersion=1.21.11
  ./gradlew build -PmcVersion=26.1   # requires Fabric API for 26.1
  ```
- Fill in `versions/26.1.properties` with the correct Fabric API version when available.

---

## v1.0.0 — Initial release / Первый релиз

- Client-side chest scanner for Hypixel SkyBlock.
- Bazaar and lowest-bin pricing.
- SQLite item database with search.
- `/cm` command family.
