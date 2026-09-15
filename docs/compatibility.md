# Compatibility and limitations

## Requirements

| | |
|---|---|
| Minecraft | 26.2 |
| Mod loader | Fabric Loader 0.19.5 or newer |
| Required mod | Fabric API |
| Java | 25 |
| Optional | Mod Menu (settings screen), Litematica (material list exports) |

Material Boxes is **client-only**: install it in your own mods folder. Servers don't need it, and it works on vanilla and modded servers. Everything it does in inventories uses the same clicks a player makes.

## Tested alongside

The release jar has been tested in a production Fabric install alongside:
- Inventory Profiles Next (with libIPN)
- Litematica and MaLiLib
- Sodium
- Lithium
- Jade
- Continuity
- AppleSkin
- Fabric Language Kotlin

## Known limitations

- **Counts update when a box is opened.** Minecraft only tells the client what's in a container while it's open, so a Material Box's counts are from the last time you saw inside it. With **Auto-refresh boxes** on (the default), a box that may have changed is opened in the background for a moment when you're within reach, and your right-clicks wait while it's read. Other players nearby see and hear it open. If a server doesn't allow mods that open containers by themselves, turn it off in the settings. See [Keeping counts up to date](material-boxes.md#keeping-counts-up-to-date).
- **Far-away boxes aren't checked.** Only boxes in loaded chunks can be checked, so a box whose block disappeared while you were far away is noticed the next time you're nearby.
- **A box only removes itself when you break it.** If its block disappears any other way (another player broke it, or you're on a different server behind the same address), it's kept as missing, in case it comes back. Clear it on the Boxes screen if it's gone for good.
- **Picked-up shulker boxes reconnect only for the loaded list.** If a list that isn't loaded has a picked-up shulker box, it reconnects the next time you load that list and open the shulker box.
- **Items are matched by type only.** Enchantments, custom names and potion types aren't distinguished in the counts. Shift-click and Deposit All never stack items that can't stack, though; those use normal shift-click.
- **Strict anti-cheat may slow fast clicking.** Deposit All and shift-click routing send ordinary inventory clicks quickly, and very strict anti-cheat plugins on some servers may rate-limit that.
- **Shulker boxes in your inventory are view-only.** The [preview](shulker-preview.md) can't change their contents, because only the server can do that.

## Troubleshooting

| Problem | Fix |
|---|---|
| Minecraft won't start and mentions a duplicate mod `materialsgui` | Remove any older Material Boxes / Materials GUI jar from your mods folder. Keep one jar. |
| A chest isn't recognized as a Material Box after reopening | Open it by right-clicking the block itself. Containers opened any other way can't be matched to a block. |
| A Material Box shows as **Block missing** | Its block wasn't there the last time you were nearby. If the container is really gone, click **Clear Missing** on the Boxes screen. If it's there, the box recovers by itself when you're nearby. |
| A singleplayer world's list and boxes are gone after updating | Worlds are now told apart by their save folder instead of their name, so two worlds with the same name no longer share boxes. A world whose folder name differs from its name (like `New World (1)`), or whose name isn't in Latin letters, starts fresh. The old file is still in `config/materialsgui/projects/`. |
| Screenshot import says "Invalid Anthropic API key." | Check the key in Settings. The key button in the corner of the Materials List's import panel opens them. |
| Your lists or settings disappeared | Look for a `.unreadable` file (or `.unreadable.2` and so on) in `config/materialsgui/`. The damaged original is kept there. |
| The HUD doesn't show | Press **H**, check it's on in Settings, and make sure your list has materials. It's hidden while a screen is open and when F1 hides the HUD. |
