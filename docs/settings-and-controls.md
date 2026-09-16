# Settings, controls and files

## Key bindings

Change these in **Options > Controls > Key Binds**, under **Material Boxes**.

| Key | Action |
|---|---|
| **B** | Open Materials List |
| **H** | Toggle Missing Materials HUD |

## Commands

| Command | Action |
|---|---|
| `/materials` | Open the Materials List |
| `/materials settings` | Open the settings screen |

These are client-side commands, so they work on any server.

## Settings screen

Open it any of these ways:
- the small comparator button at the end of the Materials List's bottom row
- `/materials settings`
- **Mod Menu** (Mods > Material Boxes > the settings button), if you have Mod Menu installed

| Setting | What it does |
|---|---|
| **Anthropic API key** | Your own key, needed only for [screenshot import](importing-lists.md#screenshots-optional-uses-claude). It's hidden while you type. The key button in the corner of the Materials List's import panel also opens this screen. |
| **Claude model** | The model used to read screenshots. Default: `claude-opus-5`. |
| **Show HUD** | Turns the [HUD](hud.md) on or off (same as **H**). |
| **Size** | **Full**, or **Compact**: no title or icons, and only the first 3 missing materials, one line each. |
| **Corner** | Top left or top right. |
| **Rows** | How many materials the full HUD lists: 4, 6, 8, 12 or 16. The compact HUD always shows 3. |
| **Auto-refresh boxes** | Keeps Material Box counts up to date when other people (or you, from another computer) change a box. See [Keeping counts up to date](material-boxes.md#keeping-counts-up-to-date). On by default. |
| **Import panel** | Whether the Materials List opens with the import panel shown or hidden. It still opens by itself when the list is empty. |
| **Slot highlights** | The colors, ghost items and amounts in Material Box slots. The same as the **Hide Highlights** button beside every Material Box. See [Hiding the highlights](material-boxes.md#hiding-the-highlights). On by default. |

## Where data is saved

Everything is saved in your Minecraft folder under `config/materialsgui/`:

| File | Contents |
|---|---|
| `config.json` | Settings, including your API key |
| `saved-lists.json` | Your [saved lists](saved-lists.md), with their replacements and Material Boxes per world |
| `projects/<world>.json` | The current list and Material Boxes for each world: `sp_<save folder>` for singleplayer, `mp_<server address>` for servers, `lan_<name>` for LAN worlds and `realm_<name>` for Realms |
| `exports/<list>.txt` | Lists you've written out with [Share](sharing-lists.md). Nothing reads these back on its own; they're yours to send on |

Files are written in one step, through a temporary `.tmp` file, so a crash while saving can't leave one half-written. If one of these files is damaged and can't be read, it's renamed to `<file>.unreadable` (or `.unreadable.2` and so on, so an earlier copy is never replaced) and kept, instead of being overwritten. Material Boxes then starts that file fresh. Entries that are only partly broken are cleaned up automatically.
