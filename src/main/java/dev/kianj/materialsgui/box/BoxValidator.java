package dev.kianj.materialsgui.box;

import dev.kianj.materialsgui.data.BoxKey;
import dev.kianj.materialsgui.data.Project;
import dev.kianj.materialsgui.data.ProjectStore;
import dev.kianj.materialsgui.data.SavedLists;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Keeps Material Boxes in sync with the world: removes boxes whose block is gone, and follows double chests that are
 * split or merged. Checks the current list's boxes and every saved list's boxes in this world. Only boxes in loaded
 * chunks of the current dimension can be checked.
 */
public final class BoxValidator {
	private static final int INTERVAL_TICKS = 10;

	private static final Set<BoxKey> missingLastCheck = new HashSet<>();
	private static int ticks;

	private BoxValidator() {}

	public static void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level == null || mc.player == null || ++ticks % INTERVAL_TICKS != 0) {
			return;
		}
		String dimension = level.dimension().identifier().toString();
		Project project = ProjectStore.project();
		Set<BoxKey> missingNow = new HashSet<>();

		boolean changed = validate(mc, level, dimension, project.boxes, true, missingNow);
		boolean listsChanged = false;
		for (SavedLists.SavedList list : SavedLists.all()) {
			List<Project.BoxEntry> boxes = list.boxes.get(ProjectStore.worldKey());
			// The current list's boxes are the project's, checked above.
			if (boxes != null && !list.name.equalsIgnoreCase(project.listName)) {
				listsChanged |= validate(mc, level, dimension, boxes, false, missingNow);
			}
		}

		missingLastCheck.clear();
		missingLastCheck.addAll(missingNow);
		if (changed) {
			ProjectStore.changed();
		}
		if (listsChanged) {
			SavedLists.write();
		}
	}

	/** Checks one list's boxes. Only the current list's changes are announced. Returns true if anything changed. */
	private static boolean validate(Minecraft mc, ClientLevel level, String dimension, List<Project.BoxEntry> boxes, boolean current,
		Set<BoxKey> missingNow) {
		boolean changed = false;
		for (Iterator<Project.BoxEntry> it = boxes.iterator(); it.hasNext(); ) {
			Project.BoxEntry box = it.next();
			BoxKey key = box.key();
			BlockPos pos = new BlockPos(box.x, box.y, box.z);
			if (box.pickedUp || !box.dimension.equals(dimension) || !level.isLoaded(pos)) {
				continue;
			}
			BlockPos canonical = BoxTracker.normalize(level, pos);
			BoxKey newKey = canonical == null ? null : BoxKey.of(dimension, canonical);
			if (newKey == null || (!newKey.equals(key) && Project.indexOfBox(boxes, newKey) >= 0)) {
				// Require two misses in a row so a mispredicted client-side break doesn't drop the box.
				if (!missingLastCheck.contains(key)) {
					missingNow.add(key);
				} else if (box.isShulker()) {
					// A broken shulker box keeps its items, so the Material Box lives on until it's placed again.
					box.pickedUp = true;
					changed = true;
					if (current) {
						mc.player.sendOverlayMessage(Component.literal("Material Box #" + (boxes.indexOf(box) + 1)
							+ " picked up. Its items still count, and it reconnects when you place and open it."));
					}
				} else {
					it.remove();
					changed = true;
					if (current) {
						mc.player.sendOverlayMessage(Component.literal("Material Box at " + key.describe() + " is gone, so it was removed"));
					}
				}
				continue;
			}
			if (box.block == null) {
				// Saves from 1.0.0 didn't record the block.
				box.block = BoxTracker.blockId(level, canonical);
				changed = true;
			}
			int size = BoxTracker.containerSize(level, canonical);
			if (!newKey.equals(key) || size != box.size) {
				box.moveTo(newKey);
				box.resize(size);
				changed = true;
			}
		}
		return changed;
	}
}
