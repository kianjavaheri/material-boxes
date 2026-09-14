# Material Boxes

A **Material Box** is a container you've chosen to hold materials for your build. Its slots are highlighted to show what goes where, and everything inside counts toward your material list.

## Making a Material Box

Open a container and click **+ Material Box** in the buttons beside it. Once added, the button reads **Remove Box #1** (or whatever number it is). Click it to stop using that container as a Material Box. The container and its items are never touched.

These containers can be Material Boxes:

- chests and trapped chests, including double chests
- barrels
- shulker boxes of any color

Ender chests can't be Material Boxes (they're shared between every place you open them). Neither can containers you don't open by right-clicking a block, such as chest minecarts.

Items already in the container **count toward the list right away**. Boxes are used in the order you add them. The header above the container shows its number and progress, for example `Material Box #1 of 3  5/12 slots done`, or `(nothing needed here)`.

The **Materials List...** button opens the [Materials List](materials-list.md), and **Deposit All** is described [below](#deposit-all).

## Reading the slots

| Slot | Meaning |
|---|---|
| **Red**, with a faded item and a number | Empty. This item goes here, and the number is how many. |
| **Yellow**, with a small `/64` | Partly filled. Top it up to the number shown. |
| **Green** | Done. |
| No color | Not needed for your list, or holds an item that isn't on your list. |

### How slots are assigned

The highlights always follow what's actually in the boxes, so you can put items anywhere:

- **Everything counts.** Everything on your list that's in any Material Box counts, whichever slot it's in.
- **Highlighted in place.** A slot that already holds a listed item is highlighted where it is. Partial stacks are topped up before new slots are asked for.
- **Ghost slots.** Whatever is still missing is shown as red ghost slots in empty slots. They're filled in list order, starting with the first Material Box you added.
- **Moving items.** If you move an item to a different slot, the highlights rearrange around it.
- **Extras.** If you have more than the list needs, the extra is taken off the last stacks, so a slot can read 7/5.

If your boxes don't have enough empty slots for everything, the Materials List says how many more slots you need.

### Hover tooltips

Hover a highlighted slot to see its progress:

- **Empty (red) slots** show the item's name, a red `0/64`, the total across all your boxes (`All boxes: 36/100`), and a hint.
- **Filled slots** add the same progress under the item's normal tooltip: yellow `3/5` for partly filled, green `5/5` when done, or `7/5` if there's extra.

## Shift-click

Shift-clicking an item in your inventory sends it to that item's highlighted slots in the open Material Box. Partial stacks are topped up first, then empty ghost slots. It places exactly the amount each slot needs, and anything left over goes back where it came from. Items with no highlighted slot in this box use normal shift-click.

## Deposit All

**Deposit All** moves everything on your list from your inventory (including your hotbar) into this box's highlighted slots in one click. The message says how many items moved. Items that aren't on your list, or that this box has no room for, stay in your inventory.

## Shulker boxes inside Material Boxes

Items inside shulker boxes that sit in a Material Box count toward your list. A shulker box full of stone in a chest counts as that stone. The shulker box's own slot isn't highlighted.

## Double chests

If you turn a single-chest Material Box into a double chest, or break one half of a double chest, the Material Box follows the change.

## Breaking and moving boxes

- **Chests, barrels and other containers** spill their items when broken, so their Material Box is **removed** automatically, with a message.
- **Shulker boxes** keep their items when broken, so a broken shulker Material Box is kept as **picked up**:
  - Its items still count toward your list.
  - It doesn't get new red slots, since you can't fill it while it's an item.
  - When you place it again and open it, it reconnects as the same Material Box. It's recognized by its color and exact contents.

Boxes are checked about twice a second, but only in loaded chunks of the dimension you're in. A box broken while you're far away is removed the next time you're nearby.

## Keeping counts up to date

Minecraft only tells your game what's in a container while it's open. So a Material Box's counts are from the last time you saw inside it, and items someone else put in (or you did, from another computer) don't show until the box is opened again.

**Auto-refresh boxes** (on by default, in the [settings](settings-and-controls.md#settings-screen)) handles this for you. When you're within reach of a Material Box that might have changed, and can see it, the box is opened in the background for a moment. It reads what's inside and closes again, without a screen opening or your movement stopping. A box might have changed when:
- it hasn't been opened since you joined the world or server
- you've been far enough away that it wasn't loaded
- someone else opened it (it's checked after they close it)

Other players nearby see the box open and hear it, as if you'd opened it. If a server doesn't allow mods that open containers by themselves, turn this off and open your boxes yourself.

While some boxes haven't been opened since you joined, the [HUD](hud.md) shows a gray line like `2 boxes not checked since you joined`.

## The Boxes screen

Click **Boxes...** on the Materials List to see every Material Box for the current list:
- **What each row shows:** its number, position, dimension, slot count and how many items it holds.
- **Status:** OK, Block missing, Not loaded, or In another dimension. Picked-up shulker boxes show **Picked up** (or **Picked up, in your inventory** if you're carrying it) and where they were last seen.
- **Layout:** the screen is a centered column. Each box takes two lines: its number and position (with the status on the right), then its slots, items and any other lists that use it.
- **Also in:** listed when the box is shared with another saved list.

Buttons:
- **Remove**: removes that Material Box.
- **Clear Missing**: removes boxes whose block is gone.
- **Clear All**: removes every box. It changes to **Sure?** first; click again to confirm.

## Boxes and saved lists

Each [saved list](saved-lists.md) remembers its own Material Boxes, so switching lists switches boxes. A box used by more than one list shows **Also in:** with the other lists' names under its buttons.
