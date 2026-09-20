# Changelog

## 1.1.0

**Updated for Minecraft 26.3.** Requires Fabric API for 26.3. The same 1.1.0 features are released for Minecraft 26.2 as a separate file (`+26.2`), maintained on the `mc/26.2` branch.

- 26.3 replaced GLFW with SDL, which renumbered the mouse buttons and key codes. Shift-click routing, right-click to remove a material, the cross-off checkbox and the shulker box preview all read those numbers, and are updated.
- **Share a list.** The Materials List's **Copy** button is now **Share**, which opens a screen with four ways to hand a list to someone:
  - **Copy Share Code**: the whole list as one `MBOX1:` line, compressed so a 200-material list still fits in a chat message. Unlike plain text it carries the list's name, its crossed-off marks and its replacements.
  - **Copy as Text**: the plain `count item` list, with the share code along for the ride in a comment.
  - **Save .txt File**: writes that text to `config/materialsgui/exports/`, with **Open Folder** beside it. An earlier export is never overwritten.
  - **Copy What's Missing**: what your Material Boxes are still short of, as before.
- Share codes are imported through the same import box, and an exported `.txt` can be dropped on the Materials List like a Litematica export. **Replace** takes the name, crossed-off marks and replacements too; **Add to List** takes only the materials, so someone else's code can't rename your list.
- **Lists...** can share a saved list without loading it first.
- Items whose name contains "and" now import. `1 Flint and Steel` was read as "flint steel" and went unrecognized; "and" is still treated as filler when it isn't part of a name (`64 stone and`).
- **Unrecognized import lines can be fixed from the list.** Click one to open a screen with its text already filled in, pick the item you meant, and it's added with the line's amount — which follows the item, since `2 sb` means a different total for a block than for ender pearls. Right-click discards a line. A fixed line also leaves the import box, so importing again can't bring it back.
- Unrecognized lines are marked with a barrier icon lined up with the other item icons, instead of a `?` floating in the name column.

## 1.0.0 (first release)

- Material lists from pasted text, Litematica exports (.txt and .csv), and screenshots (optional, uses your own Anthropic API key)
- Material Boxes (chests, double chests, barrels and shulker boxes) with red, yellow and green slots, ghost items, and hover progress
- Shift-click routing and Deposit All
- Items inside shulker boxes kept in a Material Box count toward the list
- Auto-refresh: Material Boxes that may have changed are read in the background when you're within reach
- Hide Highlights for when you start building, and a checkbox to cross off materials you're done with
- Missing-materials HUD, in full or compact size
- Materials List with search, sort, replace, copy missing, and a collapsible import panel
- Saved lists that remember their replacements and their Material Boxes in each world
- Boxes look after themselves: double chests are followed when split or joined, broken shulker boxes reconnect when placed again, and boxes that disappear are kept as missing until you clear them
- Shulker box preview in inventory screens
- Settings screen, from the Materials List, `/materials settings`, or Mod Menu
