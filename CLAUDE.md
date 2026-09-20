# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

**Material Boxes** is a client-only Fabric mod for Minecraft 26.3 (Java 25, Fabric Loader 0.19.5+, Fabric API). It turns a build's material list into color-coded "Material Boxes": chests, barrels and shulker boxes whose slots show what's missing. It works on servers that don't have the mod installed. The display name is "Material Boxes", but the mod id and package are `materialsgui` (`dev.kianj.materialsgui`). Keep the id, because config and save paths depend on it. User-facing feature docs are in `docs/`, the CurseForge page text is in `release/CURSEFORGE.md`, and the changelog (used for GitHub release notes and CurseForge's changelog field) is in `release/CHANGELOG.md`.

## Working in this repo

Don't commit, push, tag, or create releases. Leave changes uncommitted and say what's ready; the repo owner commits, and their name is the only one on the history.

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
- `./gradlew genSources`: decompiles Minecraft into `.gradle/loom-cache/minecraftMaven/.../minecraft-merged-*-sources.jar`. Unzip it to grep real 26.3 APIs. Both the current and the previous version stay in the cache, so a version bump can be diffed class by class.

There's no linter. The version lives in `gradle.properties` (`version=1.1.0+26.3`). Older Minecraft versions live on their own branches (`mc/26.2`); `main` always tracks the newest.

## Minecraft 26.3 specifics

Minecraft 26.x is unobfuscated and uses Mojang names, so there are no mappings. Many APIs differ from older versions or from memory, so **check the decompiled sources before using a Minecraft API**. Ones this code relies on:

- Rendering: `GuiGraphicsExtractor` and `extractRenderState(...)` / `extractSlot` / `extractTooltip`, not `GuiGraphics` / `render`.
- Screens: `mc.gui.setScreen(...)`, `mc.gui.screen()`, `mc.gui.hud.isHidden()` (F1), and `mc.resizeGui()`.
- Clicks: `ContainerInput` (not `ClickType`) and `MultiPlayerGameMode.handleContainerInput(...)`.
- Input: `MouseButtonEvent` / `KeyEvent` records.
- Opening a folder in the file manager: `Blaze3D.openPath(Path)`, not `Util.getPlatform().openPath(...)`.

**26.3 replaced GLFW with SDL, which renumbered every input code.** Nothing about this fails to compile, so never hardcode an input number; use the `InputConstants` names, which are correct per version:

| | 26.2 (GLFW) | 26.3 (SDL) |
|---|---|---|
| `MOUSE_BUTTON_LEFT` / `MOUSE_BUTTON_RIGHT` | 0 / 1 | 1 / 3 |
| `KEY_ESCAPE` / `KEY_RETURN` / `KEY_BACKSPACE` | 256 / 257 / 259 | 41 / 40 / 42 |
| `MOD_SHIFT` | 1 | 3 |
| Key-binding type | `InputConstants.Type.KEYSYM` | `InputConstants.Type.KEYBOARD` |

A wrong button number is silent and asymmetric: the mod's own click handling still fires while vanilla's does nothing (or the wrong thing), so shift-click routing looked fine on 26.3 while the vanilla fallthrough for unlisted items quietly stopped working. The game test's unlisted-item shift-click is what caught it.

## Architecture

**Client only.** There's no server code. Every inventory change is made with the same click packets a player would send (`ShiftRouter`), and the client only sees a container's contents while it's open. So each Material Box stores a per-slot snapshot of its last-seen contents (`Project.BoxEntry.slotItems`/`slotCounts`, plus `nested` for items inside shulker boxes).

**Data (`data/`).**

`data/` and `importer/` import nothing from `net.minecraft.client`, `net.fabricmc` or `com.mojang.blaze3d`, and a unit test (`theVersionAgnosticPackagesDontTouchClientOrLoaderApis`) fails if that changes. They hold the parts that aren't tied to a Minecraft version or a loader, so they could move into a shared module if the mod is ever ported. Client code calls in and passes what it knows; these packages never reach back out. The two things they'd otherwise need are handed to them at startup: `ModConfig.useDirectory(...)` sets the config folder (`MaterialsGuiClient` gets it from `FabricLoader`), and `ProjectStore.load(key)` takes a world key that `WorldKeys.of(mc)` derives from the running client.

- `Project`: the per-world state, holding the material list, the ordered Material Boxes, `listName` and `replacements`.
- `ProjectStore`: holds the active `Project` and its computed `Layout`, and saves to `config/materialsgui/projects/<worldKey>.json`. The world key is `sp_<save folder>` (not the world's name, which two worlds can share), `mp_<server ip>`, `lan_<name>` or `realm_<name>`. All three stores write through `ModConfig.writeAtomically` (a temp file, then an atomic move).
  - `ProjectStore.changed()`: recomputes, saves, and syncs the active saved list's boxes. Call it after any change.
  - `ProjectStore.load(key)` takes the key rather than a `Minecraft`; `WorldKeys` (root package) is what turns the client's world or server into one.
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
- Crossed-off materials (`MaterialEntry.crossedOff`, the checkbox on a Materials List row) aren't needed: no plans, and not missing. They stay on the list. `SavedLists.matches` ignores the flag, and `syncBoxes` copies it to the current saved list, so crossing off isn't an unsaved change.

`SlotOverlay.planFor` returns null while `ModConfig.highlightSlots` is off (the Hide Highlights button), which hides the tints, ghosts and slot tooltips; `ShiftRouter.handle` then leaves shift-click to vanilla.

`SlotOverlay`, `SlotTooltip`, `ShiftRouter`, `MaterialsHud` and `MaterialsScreen` all read `ProjectStore.layout()`, so don't duplicate this logic.

**Which block a screen belongs to (`box/`).** The server never says which block a container screen is for.
- `BoxTracker` remembers the chest/barrel/shulker from the last `UseBlockCallback` and attaches it to the next `ContainerScreen`/`ShulkerBoxScreen` of the same size that opens within 5 seconds. Positions are normalized so a double chest's key is its lower-coordinate half, and `BoxKey` is dimension plus position.
  - The click is forgotten when any other container screen opens, on `UseEntityCallback`, and when the server acknowledges the click's block-prediction sequence without a screen (`ClientLevelMixin` on `handleBlockChangedAck`; `MultiPlayerGameModeMixin` records the sequence after `useItemOn`). The server sends any menu a click opens before that acknowledgement.
- `BoxValidator` runs every 10 ticks on boxes in loaded chunks, for the current list and every saved list in this world. A box whose block is gone for two checks in a row is removed if the player broke it (`ClientPlayerBlockBreakEvents`), marked `pickedUp` if it's a shulker box, and otherwise marked `missing`: `Layout` ignores it, and it recovers if the block comes back. A missing box isn't removed because it can look gone when it isn't, e.g. another backend behind the same proxy address.
  - Double-chest splits and merges go through `reshape`. The menu lists the `ChestType.RIGHT` half in slots 0-26, and which half that is depends on facing, so the surviving half's slots are copied across. If the stored (lower) half is broken, the box moves to the other half.
- `PlacedShulkers` reattaches a picked-up shulker as soon as the player places it: its `UseBlockCallback` records the placement position (`BlockPlaceContext`) and the item's contents, and its tick waits up to 5 seconds for that block to appear. `ContainerHooks.tryReattach` is the fallback when it's opened (placed by someone else, say), and waits until the menu's `stateId != 0`, meaning the contents have arrived. Both match by block id and exact contents.
  - `PlacedShulkers.reattach` also reattaches the box in every saved list in this world. Other lists' copies can have older contents, so they also match by where the current list last saw the box (`Project.findPickedUp`'s `lastSeen`).
- `BoxRefresher` keeps snapshots current without the player opening boxes. A box is "fresh" once its contents are seen this session. It stops being fresh when its chunk unloads, or when its lid opens (chest openness, shulker animation, barrel `OPEN`) while it isn't ours. With `ModConfig.refreshBoxes` on, a non-fresh, closed box within reach and in line of sight gets a real `useItemOn` click. `MenuScreensMixin` then builds the resulting menu without a screen, and the box is read once `stateId != 0` and closed.
  - A menu is only taken as the box if it's the expected type and arrives before the click's acknowledgement (`onBlockChangedAck`). An acknowledgement with no menu means the box didn't open.
  - `MinecraftMixin` cancels `startUseItem` while a check runs, so the player can't open another container meanwhile. The server's close handler ignores the container id, so the refresher's close would otherwise close whatever the player had just opened. The close is also only sent while the hidden menu is still `player.containerMenu`.
  - `BoxTracker.onUseBlock` ignores the refresher's own click (`isUsingBlock`), and no refresh starts within a second of the player right-clicking a container.
  - It skips trapped chests (their redstone signal), blocked chests, and every box while a piglin is within 16 blocks (opening a container angers them). After a box closes, its lid is ignored until it has come down (`settling`), so lag doesn't cause refresh loops.
  - `uncheckedCount()` (boxes not seen since joining) is shown on the HUD.

**Screen hooks.**
- `ContainerHooks` registers per screen through Fabric `ScreenEvents.AFTER_INIT`. It adds the four side buttons (+ Material Box / Remove Box, Materials List..., Deposit All, Hide/Show Highlights) at `top`, `top + 24`, `top + 48` and `top + 72`, sized by `sideWidth` so they never overlap the container, with the "Also in:" list below them at `top + 98`. It also takes the contents snapshot in `beforeExtract` and draws the header in `afterExtract`. A new side button has to be added to the game test's `isOurButton`, which asserts none of them overlap the container.
- `AbstractContainerScreenMixin` hooks `extractSlot` HEAD and TAIL for the tints, ghosts and counts, and `extractTooltip`. Two gotchas:
  - Tooltips must be set before the screen's deferred tooltip pass (`extractDeferredElements`). Fabric's `afterExtract` is too late, and tooltips set there never show.
  - `extractTooltip` returns early when the hovered slot is empty. `@At("TAIL")` only hooks the last return, so the ghost tooltip uses `@At("RETURN")`.
- Filled-slot progress lines come from `ItemTooltipCallback`, matched by identity against the hovered slot's stack.
- `ShulkerPreview` registers its `AFTER_INIT` before `ContainerHooks`, so its click and key listeners win while the preview is open.

**Importing (`importer/`).**
- `MaterialParser` handles free-form text plus Litematica `.txt` tables and `.csv` files, and `ItemResolver` maps names to items: registry id, display name, plurals, then fuzzy matching.
  - Filler words are stripped before resolving, so every entry keeps two candidate names: without filler, and with "and" left in. The first is tried, then the second, so both `64 stone and` and `1 Flint and Steel` work. `MaterialParser.split` pulls one line apart for the fix screen, keeping the amount as items plus stacks because `2 sb` is a different total per item.
- `ListShare` is the other direction, and the way back: a list as text, as a `.txt` file in `config/materialsgui/exports`, or as an `MBOX1:` share code (tagged lines, deflated, URL-safe base64). Only a code carries `listName`, `crossedOff` and `replacements`, so `MaterialsScreen.applyImport` looks for one with `ListShare.find` before falling back to `MaterialParser`. An exported file holds both: the code sits in a `#` comment the parser skips, so reading the file and dropping it in both work. Decoding is bounded (code length, inflated size, material count) because a code is untrusted input, and unknown line tags are skipped so a newer format degrades instead of failing.
- `ClaudeImporter` reads screenshots through the Messages API with `java.net.http` and Minecraft's Gson.
  - No SDK is bundled, on purpose: the SDK took the jar from 130 KB to 37 MB.
  - It sends the `fallbacks: "default"` refusal fallback only for `claude-opus-5` / `claude-fable-5*`.
  - `requestBody` and `parseResponse` are public so they can be unit-tested without the network.

**UI (`screen/`, `hud/`, `compat/`).**
- `MaterialsScreen`: the list, with a collapsible import panel, search, sort, Copy and Clear.
  - Right-clicking a row only marks it (`pendingRemoval`). The red X at the row's end removes it, and any other click, or Esc, cancels.
  - Each row is a cross-off checkbox, the item icon, then the name (`CHECK_SIZE`, `ICON_X`, `NAME_X`). A left click in the checkbox column (`onCheckbox`) toggles `crossedOff` instead of opening `EditMaterialScreen`, so keep the two click zones in step when changing the row layout.
  - The panel has no API key field. A small key-icon button (a trial key item drawn on a blank button) in its corner opens `SettingsScreen`, where the key lives.
  - The panel's Replace / Add to List / Litematica buttons share the bottom row with Lists... / Boxes... / Done.
- `EditMaterialScreen`: replace an item or change an amount. It records replacement rules. Its second constructor fixes an unrecognized import line instead (`index` is -1): the line's text seeds the search, the amount follows the item picked until it's typed over, and saving adds a material and calls `MaterialsScreen.resolveLine`, which drops the line from both the red list and the import box's leftover text.
- `ShareScreen`: the share code, text and `.txt` export, opened from the Materials List for the current list (`ShareScreen.of(parent, project)`) and from `ListsScreen` for the selected saved list. Only the current list gets Copy What's Missing, which needs a `Layout`.
- `ListsScreen`, `BoxesScreen` and `SettingsScreen`. `SettingsScreen` is also exposed through `ModMenuCompat`, which is compile-only against Mod Menu and not needed at runtime. Its rows are packed to fit a 240-high GUI, the smallest there is, above the Done button; another setting means reflowing `keyY`/`modelY`/`hudY`/`otherY`, and the narrow screenshot in `checkScreensAt` is what catches an overflow.
- Layout convention: screens use a centered column rather than the full width. The Materials List is at most 360 GUI pixels wide with the panel hidden; `ListsScreen` and `BoxesScreen` are at most 400 (`MAX_WIDTH`), with two-line rows and text trimmed to fit.
- `MaterialsHud`: registered through `HudElementRegistry`, and hidden while any screen is open.

## Testing notes

- **Unit tests** (`src/test/.../MaterialParserTest`) bootstrap vanilla registries in `@BeforeAll`, so they can use `Items`, stack sizes and display names.
- **Game test** (`src/gametest/.../MaterialBoxGameTest`) is one long scenario.
  - `TestInput` can't send modifier keys, so shift-clicks go through `MouseHandlerAccessor.invokeOnButton` with the shift modifier.
  - Minecraft ignores the first cursor move after a screen opens, so `hoverChestSlot` nudges the cursor first.
  - The run's `build/run/clientGameTest/config` persists between runs, so reset any state the test depends on (for example `ModConfig.importPanelOpen`, `refreshBoxes`, `hudCompact`, `highlightSlots`, and saved lists).
  - `/setblock ... air` isn't the player breaking a block, so a box there becomes `missing` rather than removed. To test removal, call `BoxValidator.onPlayerBreak` first.
- **Layout checks at other window sizes.** `checkScreensAt` takes screenshots of every screen at the narrowest GUI (a 640×480 window, so 320×240) and at 1920×1080 with GUI scale 2 (960×540). It also asserts that the Material Box buttons never overlap the container.
- **Real mouse clicks.** `clickAt` clicks real mouse buttons at GUI coordinates, for example the X confirmation on a list row, or a row's cross-off checkbox (`list[0] + 4`).
- **Placing a block in the game test.** `mc.gameMode.useItemOn(mc.player, hand, hit)` from `runOnClient` fires `UseBlockCallback`, so it exercises the real placement path (`PlacedShulkers`). Put the item in the off hand, so the test's hotbar items survive, and build the `BlockHitResult` against the top face of the block below the target.
- **Check rendering, not just logic.** Passing assertions on computed state have missed real rendering bugs. Assert on what was actually drawn (for example `SlotTooltip.lastGhostTooltip`), and look at the screenshots.
- **Production runs** need Fabric API's client game test module added separately (the `prodTestMods` configuration), because the Fabric API release jar doesn't include it.
