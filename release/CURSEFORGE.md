# Material Boxes

**Summary (CurseForge short description):**
Turn a build's material list into color-coded chests that show what's still missing. Client-side, works on any server.

---

Paste your material list (or load a Litematica export), mark chests, barrels or shulker boxes as **Material Boxes**, and every slot shows what goes there: **red** for missing, **yellow** for partly filled, **green** for done.

- **Shift-click or Deposit All** puts each item in its slot, in exactly the amount needed.
- **Missing-materials HUD** shows what's left and how much you're carrying. Press **H** to toggle it.
- **Counts stay current.** Boxes that may have changed are re-read in the background when you're nearby.
- **Saved lists,** each with its own Material Boxes, shared across worlds.
- **Share a list** as a one-line code, as text, or as a .txt file. Whoever gets it pastes or drops it straight back in.
- **Ready to build:** hide the highlights with one click, or cross off materials you're done with.

**Getting started:** press **B**, paste your list, then open a chest and click **+ Material Box**.

**Client-side only.** Works on vanilla and modded servers without installing anything there. Requires **Fabric API**. **Mod Menu** is optional.

**Minecraft 26.3.** For 26.2, use the 1.0.x files.

**Good to know**
- To keep counts current, the mod briefly opens nearby Material Boxes in the background, and other players see and hear them open. Turn off **Auto-refresh boxes** in the settings on servers that don't allow this.
- Screenshot import is optional. It sends the screenshot you drop to Anthropic's API using your own API key, and only then. The mod makes no other network connections.

Full documentation: [github.com/kianjavaheri/material-boxes](https://github.com/kianjavaheri/material-boxes)
