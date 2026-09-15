# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

**Material Boxes** is a client-only Fabric mod for Minecraft 26.2 (Java 25, Fabric Loader 0.19.5+, Fabric API). It turns a build's material list into color-coded "Material Boxes": chests, barrels and shulker boxes whose slots show what's missing. It works on servers that don't have the mod installed. The display name is "Material Boxes", but the mod id and package are `materialsgui` (`dev.kianj.materialsgui`). Keep the id, because config and save paths depend on it. User-facing feature docs are in `docs/`, and the CurseForge page text is in `release/CURSEFORGE.md`.

## Commands

There's no system Java. A JDK 25 lives in `.jdk/`, and every Gradle command needs it:

```bash
export JAVA_HOME=$PWD/.jdk/jdk-25.0.4.1+1/Contents/Home
```

- `./gradlew build`: compile and run the unit tests. The release jar is `build/libs/material-boxes-<version>.jar`. Ignore the `-gametest` jar next to it.
- `./gradlew test --tests 'dev.kianj.materialsgui.MaterialParserTest.hugeAmountsAreCappedInsteadOfCrashing'`: run a single unit test.
- `./gradlew runClientGameTest`: the in-world client game test. It opens a real game window, plays one long scenario in a fresh singleplayer world, and writes screenshots to `build/run/clientGameTest/screenshots/`.
- `./gradlew prodClientGameTest`: the same game test against the built release jar in a production Fabric install, not the dev classes.
- `./gradlew prodClientGameTestWithMods -PextraModsDir="/path/to/mods"`: the same, alongside another mods folder, which must include Fabric API.
- `./gradlew genSources`: decompiles Minecraft into `.gradle/loom-cache/minecraftMaven/.../minecraft-merged-*-sources.jar`. Unzip it to grep real 26.2 APIs.

There's no linter. The version lives in `gradle.properties` (`version=1.0.0+26.2`).

## Minecraft 26.2 specifics

Minecraft 26.x is unobfuscated and uses Mojang names, so there are no mappings. Many APIs differ from older versions or from memory, so **check the decompiled sources before using a Minecraft API**. Ones this code relies on:

- Rendering: `GuiGraphicsExtractor` and `extractRenderState(...)` / `extractSlot` / `extractTooltip`, not `GuiGraphics` / `render`.
- Screens: `mc.gui.setScreen(...)`, `mc.gui.screen()`, `mc.gui.hud.isHidden()` (F1), and `mc.resizeGui()`.
- Clicks: `ContainerInput` (not `ClickType`) and `MultiPlayerGameMode.handleContainerInput(...)`.
- Input: `MouseButtonEvent` / `KeyEvent` records.

## Architecture

**Client only.** There's no server code. Every inventory change is made with the same click packets a player would send (`ShiftRouter`), and the client only sees a container's contents while it's open. So each Material Box stores a per-slot snapshot of its last-seen contents (`Project.BoxEntry.slotItems`/`slotCounts`, plus `nested` for items inside shulker boxes).

**Data (`data/`).**
- `Project`: the per-world state, holding the material list, the ordered Material Boxes, `listName` and `replacements`.
- `ProjectStore`: holds the active `Project` and its computed `Layout`, and saves to `config/materialsgui/projects/<worldKey>.json`. The world key is `sp_<save folder>` (not the world's name, which two worlds can share), `mp_<server ip>`, `lan_<name>` or `realm_<name>`. All three stores write through `ModConfig.writeAtomically` (a temp file, then an atomic move).
  - `ProjectStore.changed()`: recomputes, saves, and syncs the active saved list's boxes. Call it after any change.
  - `recompute()`: recomputes only, for live per-frame box-content updates.
- `SavedLists`: named lists shared by every world, in `config/materialsgui/saved-lists.json`. Each list also stores its boxes per world key. Switching lists swaps `project.boxes`; a list with no boxes in the current world keeps the current ones.
- `ModConfig`: global settings.
- Damaged files: all three loaders set unreadable files aside as `*.unreadable` and call `sanitize()` on what they load.

**`Layout` is the single source of truth for what every slot shows.** It's a pure function of `Project` and gives each box slot a `SlotPlan(item, target)`:
- Anything already in any box counts toward the list, including shulker contents.
- Slots holding a listed item are highlighted in place, with partial stacks topped up first.
- Anything still missing becomes ghost plans in empty slots, in list order across boxes in the order they were added. Picked-up shulker boxes get none.
- Where the boxes hold more than the list needs, the excess is cut from the last stacks, so a slot can read 7/5.
- Amounts are clamped to `Integer.MAX_VALUE`, never overflowed.

`SlotOverlay`, `SlotTooltip`, `ShiftRouter`, `MaterialsHud` and `MaterialsScreen` all read `ProjectStore.layout()`, so don't duplicate this logic.

**Which block a screen belongs to (`box/`).** The server never says which block a container screen is for.
- `BoxTracker` remembers the chest/barrel/shulker from the last `UseBlockCallback` and attaches it to the next `ContainerScreen`/`ShulkerBoxScreen` of the same size that opens within 5 seconds. Positions are normalized so a double chest's key is its lower-coordinate half, and `BoxKey` is dimension plus position.
  - The click is forgotten when any other container screen opens, on `UseEntityCallback`, and when the server acknowledges the click's block-prediction sequence without a screen (`ClientLevelMixin` on `handleBlockChangedAck`; `MultiPlayerGameModeMixin` records the sequence after `useItemOn`). The server sends any menu a click opens before that acknowledgement.
- `BoxValidator` runs every 10 ticks on boxes in loaded chunks, for the current list and every saved list in this world. A box whose block is gone for two checks in a row is removed if the player broke it (`ClientPlayerBlockBreakEvents`), marked `pickedUp` if it's a shulker box, and otherwise marked `missing`: `Layout` ignores it, and it recovers if the block comes back. A missing box isn't removed because it can look gone when it isn't, e.g. another backend behind the same proxy address.
  - Double-chest splits and merges go through `reshape`. The menu lists the `ChestType.RIGHT` half in slots 0-26, and which half that is depends on facing, so the surviving half's slots are copied across. If the stored (lower) half is broken, the box moves to the other half.
- `ContainerHooks.tryReattach` recognizes a re-placed picked-up shulker by its block id and exact contents. It waits until the menu's `stateId != 0`, meaning the contents have arrived.
- `BoxRefresher` keeps snapshots current without the player opening boxes. A box is "fresh" once its contents are seen this session. It stops being fresh when its chunk unloads, or when its lid opens (chest openness, shulker animation, barrel `OPEN`) while it isn't ours. With `ModConfig.refreshBoxes` on, a non-fresh, closed box within reach and in line of sight gets a real `useItemOn` click. `MenuScreensMixin` then builds the resulting menu without a screen, and the box is read once `stateId != 0` and closed.
  - A menu is only taken as the box if it's the expected type and arrives before the click's acknowledgement (`onBlockChangedAck`). An acknowledgement with no menu means the box didn't open.
  - `MinecraftMixin` cancels `startUseItem` while a check runs, so the player can't open another container meanwhile. The server's close handler ignores the container id, so the refresher's close would otherwise close whatever the player had just opened. The close is also only sent while the hidden menu is still `player.containerMenu`.
  - `BoxTracker.onUseBlock` ignores the refresher's own click (`isUsingBlock`), and no refresh starts within a second of the player right-clicking a container.
  - It skips trapped chests (their redstone signal), blocked chests, and every box while a piglin is within 16 blocks (opening a container angers them). After a box closes, its lid is ignored until it has come down (`settling`), so lag doesn't cause refresh loops.
  - `uncheckedCount()` (boxes not seen since joining) is shown on the HUD.

**Screen hooks.**
- `ContainerHooks` registers per screen through Fabric `ScreenEvents.AFTER_INIT`. It adds the side buttons (sized by `sideWidth` so they never overlap the container), takes the contents snapshot in `beforeExtract`, and draws the header in `afterExtract`.
- `AbstractContainerScreenMixin` hooks `extractSlot` HEAD and TAIL for the tints, ghosts and counts, and `extractTooltip`. Two gotchas:
  - Tooltips must be set before the screen's deferred tooltip pass (`extractDeferredElements`). Fabric's `afterExtract` is too late, and tooltips set there never show.
  - `extractTooltip` returns early when the hovered slot is empty. `@At("TAIL")` only hooks the last return, so the ghost tooltip uses `@At("RETURN")`.
- Filled-slot progress lines come from `ItemTooltipCallback`, matched by identity against the hovered slot's stack.
- `ShulkerPreview` registers its `AFTER_INIT` before `ContainerHooks`, so its click and key listeners win while the preview is open.

**Importing (`importer/`).**
- `MaterialParser` handles free-form text plus Litematica `.txt` tables and `.csv` files, and `ItemResolver` maps names to items: registry id, display name, plurals, then fuzzy matching.
- `ClaudeImporter` reads screenshots through the Messages API with `java.net.http` and Minecraft's Gson.
  - No SDK is bundled, on purpose: the SDK took the jar from 130 KB to 37 MB.
  - It sends the `fallbacks: "default"` refusal fallback only for `claude-opus-5` / `claude-fable-5*`.
  - `requestBody` and `parseResponse` are public so they can be unit-tested without the network.

**UI (`screen/`, `hud/`, `compat/`).**
- `MaterialsScreen`: the list, with a collapsible import panel, search, sort, Copy and Clear.
  - Right-clicking a row only marks it (`pendingRemoval`). The red X at the row's end removes it, and any other click, or Esc, cancels.
  - The panel has no API key field. A small key-icon button (a trial key item drawn on a blank button) in its corner opens `SettingsScreen`, where the key lives.
  - The panel's Replace / Add to List / Litematica buttons share the bottom row with Lists... / Boxes... / Done.
- `EditMaterialScreen`: replace an item or change an amount. It records replacement rules.
- `ListsScreen`, `BoxesScreen` and `SettingsScreen`. `SettingsScreen` is also exposed through `ModMenuCompat`, which is compile-only against Mod Menu and not needed at runtime.
- Layout convention: screens use a centered column rather than the full width. The Materials List is at most 360 GUI pixels wide with the panel hidden; `ListsScreen` and `BoxesScreen` are at most 400 (`MAX_WIDTH`), with two-line rows and text trimmed to fit.
- `MaterialsHud`: registered through `HudElementRegistry`, and hidden while any screen is open.

## Testing notes

- **Unit tests** (`src/test/.../MaterialParserTest`) bootstrap vanilla registries in `@BeforeAll`, so they can use `Items`, stack sizes and display names.
- **Game test** (`src/gametest/.../MaterialBoxGameTest`) is one long scenario.
  - `TestInput` can't send modifier keys, so shift-clicks go through `MouseHandlerAccessor.invokeOnButton` with the shift modifier.
  - Minecraft ignores the first cursor move after a screen opens, so `hoverChestSlot` nudges the cursor first.
  - The run's `build/run/clientGameTest/config` persists between runs, so reset any state the test depends on (for example `ModConfig.importPanelOpen`, `refreshBoxes`, `hudCompact`, and saved lists).
  - `/setblock ... air` isn't the player breaking a block, so a box there becomes `missing` rather than removed. To test removal, call `BoxValidator.onPlayerBreak` first.
- **Layout checks at other window sizes.** `checkScreensAt` takes screenshots of every screen at the narrowest GUI (a 640×480 window, so 320×240) and at 1920×1080 with GUI scale 2 (960×540). It also asserts that the Material Box buttons never overlap the container.
- **Real mouse clicks.** `clickAt` clicks real mouse buttons at GUI coordinates, for example the X confirmation on a list row.
- **Check rendering, not just logic.** Passing assertions on computed state have missed real rendering bugs. Assert on what was actually drawn (for example `SlotTooltip.lastGhostTooltip`), and look at the screenshots.
- **Production runs** need Fabric API's client game test module added separately (the `prodTestMods` configuration), because the Fabric API release jar doesn't include it.
