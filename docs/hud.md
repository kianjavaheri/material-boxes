# Missing materials HUD

A small checklist in the corner of the screen shows what's still missing from your list while you're out gathering.

```
3 materials still needed
[icon] Big Dripleaf        0/38
[icon] Prismarine Bricks   12/30 +8
[icon] Stone               192/200
```

- Each row shows the material, how many are stored in your Material Boxes out of how many are needed, and in gray how many you're **carrying** right now (`+8`).
- Amounts are red when none are stored and yellow when some are.
- Finished materials drop off the list. When everything is gathered it reads **All materials gathered!**
- If more materials are missing than fit, a line reads `+N more`.
- If some Material Boxes haven't been opened since you joined, a gray line says so (`2 boxes not checked since you joined`). Their counts are from the last time you saw inside them, so they may be out of date. With **Auto-refresh boxes** on, they're checked as soon as you're within reach. See [Keeping counts up to date](material-boxes.md#keeping-counts-up-to-date).

## Compact HUD

Set **Size** to **Compact** in the settings for a smaller HUD: no title or icons, and only the first 3 missing materials, one line each.

```
Big Dripleaf        0/38
Prismarine Bricks   12/30 +8
Stone               192/200
+2 more
```

## Showing and hiding it

- Press **H** to toggle the HUD, or use **Show HUD** in the settings. The key can be changed in Controls, under Material Boxes.
- It's also hidden when you press **F1** to hide the HUD, and whenever a screen (a chest, your inventory, the Materials List...) is open, since those already show your progress.
- It only appears when your current list has materials.

## Settings

In the [settings screen](settings-and-controls.md#settings-screen) you can:
- turn the HUD on or off
- switch between the full and compact HUD
- move it to the top-right corner
- choose how many rows the full HUD shows (4, 6, 8, 12 or 16)
