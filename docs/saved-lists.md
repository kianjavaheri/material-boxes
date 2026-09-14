# Saved lists

Saved lists let you keep several builds' material lists and switch between them. They're shared by every world and server you play on.

Open the list manager with **Lists...** on the [Materials List](materials-list.md).

## Saving

Type a name in the box at the top and click **Save**. The list you're working on becomes that saved list, and its name appears in the Materials List title. If another list already has that name, the button changes to **Overwrite?**, and clicking it again replaces that list.

Saving again under the same name updates it. When the current list differs from its saved copy, the Materials List title shows `(unsaved changes)`.

## The list manager

The list manager is a centered column. Each saved list takes two lines:
- **First line:** its name (the current one is marked `(current)`), with when it was saved on the right.
- **Second line:** how many materials it has, how many Material Boxes it has in this world, and how many replacements it holds.

The most recently saved list is at the top. Names that are too long to fit are shortened with "...".

| Action | How |
|---|---|
| **Load** | Makes that list the current one. If the current list has unsaved changes, the button asks **Sure?** first. |
| **Delete** | Deletes the saved list (click **Sure?** to confirm). Your current list isn't affected. |
| **Rename** | Click a list to select it and put its name in the box, edit the name, then click **Rename**. |
| **New List** | Starts an empty, unnamed list. Your Material Boxes carry over. Asks first if the current list has unsaved changes. |

## What a saved list remembers

- **Its materials.**
- **Its replacements**, for example oak wood → oak logs. Importing into the list again applies them automatically. Replacements follow chains: oak wood → oak logs, then oak logs → spruce logs, becomes oak wood → spruce logs. Changing an item back removes the replacement.
- **Its Material Boxes, separately for each world or server.**

## Material Boxes per list

Each saved list keeps its own set of [Material Boxes](material-boxes.md), so you can run several builds in the same world. For example, one storage room for one build and another for the next.

- **Automatic saving:** adding or removing a box while a list is loaded updates that list right away. There's no separate save for boxes.
- **Switching:** loading a list switches to its boxes in the world you're in.
- **Lists with no boxes here yet:** a list that has no boxes in this world keeps the ones you're using, so you don't have to mark your storage again for a new list. Clear and New List keep your boxes too.
- **Sharing:** the same container can belong to several lists. It shows **Also in:** with the other lists' names beside it, and on the Boxes screen. Only the loaded list counts at any time, so nothing is counted twice.
- **Cleanup:** broken boxes are cleaned up in every list for the world you're in, not just the loaded one.
- **Existing boxes:** boxes you had before saving a list are taken on by the list that's loaded.
