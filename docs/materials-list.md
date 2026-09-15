# The Materials List screen

Open it with **B**, `/materials`, or the **Materials List...** button next to any container. It shows your current list and how far along you are.

## Layout

- **Title:** the current list's name, and `(unsaved changes)` if it differs from its [saved copy](saved-lists.md).
- **Import panel** (left): paste or load a list. See [Importing a material list](importing-lists.md). Its **Replace / Add to List / Litematica** buttons sit on the same line as the list's buttons. The small **key button** in its top-right corner holds the Anthropic API key used for screenshot import. **Hide Import** / **Show Import** collapses or opens the panel. Your choice is remembered, and the panel opens by itself when the list is empty, after Clear, or when you drop a file on the window. On a narrow window the panel covers the list instead of sitting beside it, and it closes after a successful import so you can see the result.
- **Header:** `Materials (55)` plus the **Copy** and **Clear** buttons.
- **Search box and Sort button.**
- **Box line:**
  - how many Material Boxes you have
  - a red warning if your boxes need more empty slots (`Add Material Boxes: 34 more slots needed`)
  - a hint to add one if you have none
- **The list:** one row per material, with a checkbox for [crossing it off](#crossing-off-a-material) and `stored / needed`.
- **Bottom buttons:** **Lists...** ([saved lists](saved-lists.md)), **Boxes...** ([the Boxes screen](material-boxes.md#the-boxes-screen)), **Done**, and a small comparator button that opens the [settings](settings-and-controls.md#settings-screen).

## Reading the list

| Amount color | Meaning |
|---|---|
| Red | None stored yet |
| Yellow | Some stored |
| Green, with the name crossed out | Enough stored. Crossed off the list. |
| Gray, with the name crossed out and the checkbox filled in | [Crossed off](#crossing-off-a-material) by hand |

"Stored" means in your Material Boxes, including inside shulker boxes kept in them. Items you're carrying don't count until you put them in a box, but the [HUD](hud.md) shows them. Boxes whose block is [missing](material-boxes.md#breaking-and-moving-boxes) don't count.

Items that no longer exist (for example from a removed mod) are shown in red as `(unknown item)`. You can replace or remove them like any other row.

## Search and sort

- Type in the **search box** to filter by item name or id. The header shows `12 of 55`.
- The **Sort** button cycles through:
  - **Sort: List**: import order
  - **Sort: Missing**: most still needed first, then finished items, then crossed-off ones
  - **Sort: A-Z**: alphabetical

## Replacing an item or changing its amount

Click a material to open the editor:

- **Replace:** type in **Replace with...** to search every item by name or id (typos are fine). Click a result, or press Enter to take the top match. The amount stays the same.
- **Change the amount** in the **Amount** box.
- **Save** applies it, **Remove** takes the material off the list, and **Cancel** discards your changes.

If you replace an item with one that's already on the list, the two are merged and their amounts added together. Replacements are remembered and applied again when you import into the same list. See [Importing](importing-lists.md#replacements-are-remembered).

## Removing a material

**Right-click** a material to remove it. So nothing is removed by accident, the row turns red and shows **Remove?** with a red **X** at its end:

- Click the **X** to remove the material.
- Click anywhere else, or press **Esc**, to keep it.

You can also click the material and use **Remove** in the editor.

## Crossing off a material

Click the **checkbox** at the start of a row to cross that material off without removing it, for example once you've started building with it. A crossed-off material:
- stays on the list, grayed out and crossed out, with its checkbox filled in
- isn't highlighted in your Material Boxes, and gets no ghost slots
- isn't counted as missing, so it leaves the [HUD](hud.md) and what **Copy** copies

Click the checkbox again to need it again. Adding more of the same item with **Add to List** un-crosses it too. Crossing off is saved with the list, and doesn't count as an unsaved change to a [saved list](saved-lists.md).

To hide the highlights for every material at once, use [Hide Highlights](material-boxes.md#hiding-the-highlights).

## Copy

**Copy** puts what's still missing on your clipboard, to share with friends helping you gather:

```
Still needed for Axolotl Globe:
38 Big Dripleaf
30 Prismarine Bricks
```

The format is itself a material list, so it can be pasted straight back into Material Boxes. An item that's on the list twice is copied once, with both amounts counted.

## Clear

**Clear** empties the current list so you can start a new one:
- **Confirmation:** if the list isn't saved, or has changes since you saved it, it asks **Sure?** first. Importing something in the meantime cancels the question.
- **Saved copy:** the saved copy of a saved list is never touched, and can be loaded again from **Lists...**.
- **Boxes:** your Material Boxes stay, ready for the next list.
