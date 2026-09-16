# Sharing a list

**Share** hands a material list to someone else. Open it from the [Materials List](materials-list.md) header for the list you're working on, or select a [saved list](saved-lists.md) in **Lists...** and click **Share** there.

Everything Share produces can be pasted or dropped straight back into Material Boxes, on your machine or anyone else's.

## Share code

**Copy Share Code** puts the whole list on your clipboard as one line:

```
MBOX1:eNrzUwhPLEnOKMkvTy3i8lUoLsnPS1UwMjAAstNzEouLgWyFCq4ghfzE7PiCnMS87GIwMyc_nQsAPBoTOQ
```

Paste it into a chat message, a Discord post or a forum reply. To use one, paste it into the import panel and click **Replace** or **Add to List**. The code can sit in the middle of a message ("here's the list: `MBOX1:...`") and is still found.

A share code carries more than plain text does:

- the list's **name**
- which materials are **crossed off**
- the list's **[replacements](importing-lists.md#replacements-are-remembered)**
- exact item ids, so nothing has to be matched by name

It's also compressed, so it stays inside a chat message's length limit where the same list as text wouldn't. A 200-material list is about 1,800 characters.

**Replace vs Add.** **Replace** takes the whole list, including its name, crossed-off marks and replacements. **Add to List** only takes the materials, so someone else's code can't rename your list or change your swaps, and the materials go through your own replacements on the way in.

If a code is cut short in the pasting, or comes from a newer version of the mod, you get a message saying so and your list is left alone.

## Copy as Text

**Copy as Text** copies the list as plain `count item` lines, readable by anyone:

```
# Material Boxes list: Watchtower
# Paste this into the Materials List's import box, or drop this file on it.
# This code restores the name, the crossed-off marks and the item swaps. Delete the line to use only the list below.
# MBOX1:eNrzUwhPLEnOKMkvTy3i8lUoLsnPS1UwMjAAstNzEouLgWyFCq4ghfzE7PiCnMS87GIwMyc_nQsAPBoTOQ
200 stone
20 glass (done)
```

The `#` lines are comments, which the importer skips, so the text works as a list on its own. The share code rides along in one of them: importing the whole thing uses the code and restores everything, while pasting only the lines underneath imports just the materials.

**Crossed-off materials** are written with `(done)` after them. That's a note for whoever reads the file; only the share code actually carries the mark.

## Copy What's Missing

**Copy What's Missing** copies only what your Material Boxes are still short of, as a shopping list for friends helping you gather:

```
Still needed for Axolotl Globe:
38 Big Dripleaf
30 Prismarine Bricks
```

An item on the list twice is copied once, with both amounts counted. Crossed-off materials aren't included. This button is only available for the list you're working on, since a saved list you haven't loaded has no boxes to compare against.

## Save .txt File

**Save .txt File** writes the same text as **Copy as Text** to `config/materialsgui/exports/`, named after the list (`Watchtower.txt`). **Open Folder** opens that folder in your file manager, so you can attach the file to a message.

An existing export is never overwritten: a second save of the same list becomes `Watchtower (2).txt`.

Whoever receives the file drops it onto the Minecraft window with the Materials List open, checks it in the import panel, and clicks **Replace**. See [Importing a material list](importing-lists.md).

## Items the other player doesn't have

A list can name items from a mod the other player doesn't have. Those lines are reported as unrecognized in red at the bottom of their list, and the rest of the list imports normally.
