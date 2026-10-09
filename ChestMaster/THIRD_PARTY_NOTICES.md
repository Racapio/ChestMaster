# Third-party notices

## SkyOcean

Parts of ChestMaster are ported from or inspired by [SkyOcean](https://github.com/meowdding/SkyOcean)
by meowdding and contributors:

- `src/client/kotlin/com/chestmaster/scanner/ChestScanner.kt` — chest tracking (right-click position,
  translation-key title check, save on close, per-half double chests, removal on block break)
- `src/client/kotlin/com/chestmaster/highlight/SearchHighlight.kt` — in-inventory item highlighting
- `src/client/kotlin/com/chestmaster/search/HoverSearch.kt` — search the hovered item via keybind

SkyOcean's source code (`.kt`, `.java`, `.kts`) is licensed under the MIT License:

> Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
> associated documentation files (the "Software"), to deal in the Software without restriction, including
> without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
> copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the
> following conditions:
>
> The above copyright notice and this permission notice shall be included in all copies or substantial
> portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
> LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
> EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
> IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
> THE USE OR OTHER DEALINGS IN THE SOFTWARE.

No SkyOcean assets (textures, translations or other non-code files, which are "All Rights Reserved")
are included.

## SkyBlockAPI

ChestMaster bundles [SkyBlockAPI](https://github.com/SkyblockAPI/SkyblockAPI) by ThatGravyBoat (MIT) as a
nested jar for location detection, events and item valuation. Its own nested dependencies (Hypixel Mod
API, repo-lib, item-data-fixer) are distributed under their respective licenses.
