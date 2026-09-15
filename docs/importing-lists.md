# Importing a material list

Lists are imported in the **import panel** on the left of the [Materials List](materials-list.md) (press **B**). If the panel is hidden, click **Show Import**.

## Pasting text

Paste or type a list into the big text box, then click one of these:

- **Replace**: replaces the whole list with these materials. If none of the lines are recognized, your list is left as it is.
- **Add to List**: adds these materials to the current list. If a material is already on the list, the amounts are added together.

One material per line works best, but a line can also hold several materials separated by commas or semicolons. All of these are understood:

| You write | Means |
|---|---|
| `64 stone` | 64 stone |
| `stone 64`, `Stone: 64`, `stone - 64`, `Stone,64` | 64 stone |
| `3x oak_planks`, `oak planks x3` | 3 oak planks |
| `3 stacks oak planks`, `3 st oak planks` | 3 stacks (3 × 64, or 3 × 16 for items that stack to 16) |
| `2.5 stacks glass` | 2½ stacks (160 glass) |
| `stone bricks - 5 stacks + 12`, `5 stacks 12 stone bricks` | 5 stacks plus 12 |
| `2 sb glass`, `2 shulker boxes of glass` | 2 shulker boxes' worth (2 × 27 stacks) |
| `1 dc cobblestone`, `1 double chest of cobblestone` | 1 double chest's worth (54 stacks) |
| `3x64 stone` | 192 stone |
| `1,024 glass`, `1.024 glass` | 1024 glass (commas, or dots before groups of three digits, are thousands separators) |
| `1.5k stone` | 1500 stone |
| `Stone 1728 (27 stacks)` | 1728 stone (a note in brackets is ignored when there's an amount outside it) |
| `- 64 stone`, `1. 64 stone` | Bullets and numbering are ignored |
| `64 stone, 32 glass; 10 torches` | Three materials on one line |

**One amount per line.** Numbers on a line are only added together when they're joined by `+` (`5 stacks + 12`), or when a plain number follows an amount in stacks or boxes (`5 stacks 12`). A line with any other extra number, like `Stone 1728 27`, isn't guessed at. It's listed as unrecognized so you can fix it.

**Item names:**
- Items can be written by their in-game name (`Oak Planks`, `Block of Iron`), their id (`oak_planks` or `minecraft:oak_planks`), or a plural (`torches`).
- Small typos are forgiven: `oak plnks` finds Oak Planks.
- A few old names still work: `grass` is Short Grass, and `redstone wire` is Redstone Dust.
- Words like "shulker box" and "stack" are read as amounts (`2 shulker boxes of glass`) unless they're the item's name. `4 Shulker Box` is four shulker boxes, and `8 Shulker Shell` is eight shulker shells.

Lines starting with `#` are skipped, as are headings, lines with no amount, and the `Still needed:` heading that [Copy](materials-list.md#copy) adds.

**Unrecognized lines** stay in the text box after importing so you can fix them, and they're listed in red (with a `?`) at the bottom of the material list. A line with an amount but no item (`64` on its own) is listed too. Fix a line and click **Add to List**.

Amounts are capped at 2,147,483,647, so an absurdly large number is capped instead of causing an error.

## Litematica exports and spreadsheets

Litematica can export a schematic's material list to a file (**Material List > Write to file**). Material Boxes reads both formats:

- the `.txt` table (`| Item | Total | Missing | Available |`)
- the `.csv` file

The **Total** column is used.

Either click **Litematica**, which loads the newest material list export from Litematica's folders (`config/litematica` or `litematica`), or drag the `.txt`/`.csv` file onto the Minecraft window. The file is loaded into the text box so you can check it. Then click **Replace** or **Add to List**.

Rows copied from a spreadsheet, with tabs between the cells, work the same way. So do table rows in another order, like `| 1 | Stone | 64 |`: the first cell that isn't a number is the item, and the first number after it is the amount.

## Screenshots (optional, uses Claude)

You can also import a picture of a list, such as a screenshot of Litematica, a spreadsheet, a chat message or handwritten notes:

1. Add your own **Anthropic API key**. Click the small **key button** in the top-right corner of the import panel, which opens [Settings](settings-and-controls.md#settings-screen), or use `/materials settings`.
2. Drag a PNG or JPG image (GIF and BMP also work) onto the Minecraft window while the Materials List is open.
3. The image is sent to Claude (Anthropic's AI), which reads the list and returns item ids and amounts. Stacks, shulker boxes and double chests are converted to item counts.
4. The result appears in the text box. Check it, then click **Replace** or **Add to List**.

Details:
- **Image size:** large images are scaled down (longest side 2576 px) before sending.
- **Model:** the default is `claude-opus-5`, and you can change it in Settings. If Claude's safety checks decline an image, the request is retried automatically on Anthropic's recommended fallback model (for Opus 5 and Fable 5 models).
- **One at a time:** while a screenshot is being read, dropping another one asks you to wait. A long list can take a few minutes. If you close the Materials List meanwhile, a message tells you when the result is ready, and it's in the text box when you open the list again.
- **Errors:** you get a plain message for an invalid key, rate limits, an overloaded API, no internet connection, or an image that contains no list.
- **Privacy:** screenshot import is off until you add a key. The image is sent only when you drop one on the window, and nothing else is ever sent. Your key is stored on your computer in `config/materialsgui/config.json`, and invisible characters pasted along with it are removed. API use is billed to your Anthropic account.

## Replacements are remembered

If you replace an item on your list with another (for example oak wood with oak logs, see [The Materials List](materials-list.md#replacing-an-item-or-changing-its-amount)), the list remembers it. When you import into that same list again (for example a fresh Litematica export), the swap is applied automatically, and the import message says how many saved replacements were applied. Replacements are saved with [saved lists](saved-lists.md).
