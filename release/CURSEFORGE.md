# Material Boxes

**Summary (CurseForge short description):**
Load a build's material list, mark chests as Material Boxes, and see at a glance what's still missing. Client-side, works on any server.

---

## What it does

Gathering materials for a big build means juggling a list, a pile of chests, and a lot of counting. Material Boxes turns your material list into color-coded storage:

- **Import your list** by pasting text ("64 stone", "3 stacks oak planks", "2 sb glass"), loading a Litematica material list export, or dropping in a screenshot for Claude to read (optional, see below).
- **Mark chests, barrels and shulker boxes as Material Boxes.** Items already inside count toward the list right away, including items inside shulker boxes you keep in them.
- **Slots show what goes where.** Red means missing (with a faded icon of the item and how many are needed), yellow means partly filled, and green means done. Hover any highlighted slot to see its item name and progress, like 3/5.
- **Put items anywhere.** The highlights rearrange around what's actually in the box.
- **Ready to build.** Hide the highlights with one click once you start building, or cross off materials you're done with. They stay on the list, but stop being highlighted or counted as missing.
- **Shift-click or Deposit All.** Shift-clicking an item sends it to its highlighted slots, and Deposit All moves every listed item from your inventory into the box in one click.
- **Counts that keep up.** Boxes that someone else may have changed are read in the background when you're nearby, so the counts stay current even after other players (or you, from another computer) add items.
- **Missing-materials HUD.** A small on-screen checklist of what's still needed, and how many of each you're carrying. Press H to toggle it, or switch to a compact three-line version in the settings.
- **Materials List screen.** Search, sort by what's most missing, replace a material with another item (oak wood to oak logs), change amounts, and copy what's still missing to share with friends.
- **Saved lists.** Name and save lists, switch between builds, and each list remembers its own Material Boxes in each world. Replacements you make are applied again when you re-import into that list.
- **Shulker box preview.** Right-click a shulker box in any inventory screen to see what's inside (view only).
- **Tidy by itself.** Breaking a chest removes its Material Box, and one that disappears any other way is kept as missing until you clear it. Double chests keep each half's contents when split or joined. A broken shulker Material Box is kept as "picked up" (its items still count) and reconnects as soon as you place it again, in every list that uses it.

## How to use

1. Press **B** (or type `/materials`) to open the Materials List, and paste or import your list.
2. Open a chest and click **+ Material Box**. Add as many as you need; the list shows how many more slots it needs.
3. Fill the red slots. Shift-click items in, or click **Deposit All**.
4. Keep the HUD on while you gather (**H** to toggle).

## Compatibility

| | |
|---|---|
| Minecraft | 26.2 |
| Loader | Fabric Loader 0.19.5 or newer |
| Required | Fabric API |
| Optional | Mod Menu (settings screen), Litematica (material list exports) |
| Side | Client only. Works on vanilla and modded servers without the server installing anything. |

Settings: the comparator button in the Materials List, Mod Menu, or `/materials settings`. Key bindings (Options > Controls > Material Boxes): Open Materials List (B), Toggle Missing Materials HUD (H).

## Screenshot import and privacy

Screenshot import is optional and off until you add your own Anthropic API key, with the key button on the Materials List's import panel or in the settings. When you drop a screenshot on the Materials List, that image is sent to Anthropic's API to read the list, and only then. The mod makes no other network connections. Your key is stored on your computer in `config/materialsgui/config.json`. Using the API is billed to your Anthropic account.

## Known limitations

- Minecraft only tells the client what's in a container while it's open. When a Material Box may have changed (it hasn't been opened since you joined, or someone else opened it), **Auto-refresh boxes** opens it in the background for a moment once you're within reach, and your right-clicks wait while it's read. Other players nearby see and hear it open. It skips trapped chests, and every box while piglins are nearby. Turn it off in the settings on servers that don't allow mods that open containers by themselves.
- Boxes in chunks that aren't loaded can't be checked, so a box that disappeared while you were far away is noticed the next time you're nearby.
- A box only removes itself when you break it. One that disappears any other way (another player broke it, or a different server behind the same address) is kept as missing until you clear it on the Boxes screen.
- Deposit All and shift-click routing use normal inventory clicks. Very strict anti-cheat plugins on some servers may rate-limit fast clicking.
- Materials are matched by item type; item components (enchantments, custom names, potion types) aren't distinguished.

## Changelog

### Unreleased
- Hide Highlights: turn the slot colors off beside any Material Box (or in the settings) while you build
- Cross off materials with the checkbox on their row: they stay on the list but aren't highlighted or counted as missing
- Picked-up shulker Material Boxes reconnect as soon as they're placed, without opening them, and in every saved list that uses them (they used to stay "picked up" in other lists)
- Auto-refresh: Material Boxes that may have changed are read in the background when you're within reach (on by default, in the settings)
- Compact HUD option, and a settings button in the Materials List
- Boxes only remove themselves when you break them; ones that disappear otherwise are kept as missing until you clear them
- Double chests keep each half's contents when split or joined, and breaking the lower half no longer removes the box
- Singleplayer worlds are told apart by their save folder, so two worlds with the same name no longer share boxes
- Importing: one amount per line (no more accidental adding up), decimals and "k" amounts, "Block of Iron" and other names with "of", "Shulker Box" as an item, spreadsheet rows, header-less CSV, and amount-only lines reported instead of dropped
- Replace no longer empties the list when nothing in the paste was recognized
- Shift-click falls back to vanilla when items can't stack with the planned slot
- Fixed a crash with picked-up shulker boxes, a crash on the Boxes screen, saves that a crash mid-write could cut short, and boxes saved as empty when closed before their contents arrived
- Screenshot import: one at a time, results reach a reopened Materials List, and error messages can no longer show your API key

### 1.0.0 (first release)
- Material lists from pasted text, Litematica exports (.txt and .csv), and screenshots (optional, uses your Anthropic API key)
- Material Boxes (chests, double chests, barrels, shulker boxes) with red, yellow and green slots, ghost items, and hover progress
- Shift-click routing and Deposit All
- Shulker box contents count toward the list
- Missing-materials HUD
- Materials List with search, sort, replace, copy missing, and a collapsible import panel
- Saved lists that remember their replacements and their Material Boxes per world
- Shulker box preview
- Settings screen (Mod Menu and `/materials settings`)
