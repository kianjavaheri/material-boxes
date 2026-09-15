package dev.kianj.materialsgui.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import org.jspecify.annotations.Nullable;

/** Everything saved per world/server: the material list and the ordered Material Boxes. Serialized with Gson. */
public final class Project {
	public List<MaterialEntry> materials = new ArrayList<>();
	public List<BoxEntry> boxes = new ArrayList<>();
	/** Name of the saved list this was loaded from or last saved as, or null if it hasn't been saved. */
	public @Nullable String listName;
	/** Item swaps made in this list (from id to id), applied again when a list is imported into it. */
	public Map<String, String> replacements = new LinkedHashMap<>();

	public static final class MaterialEntry {
		/** Item registry id, e.g. "minecraft:stone". */
		public String item;
		public int count;

		public MaterialEntry(String item, int count) {
			this.item = item;
			this.count = count;
		}
	}

	public static final class BoxEntry {
		public String dimension;
		public int x;
		public int y;
		public int z;
		/** Number of container slots (27 for chests/barrels/shulkers, 54 for double chests). */
		public int size;
		/**
		 * Item id and count per slot as of the last time this box was open (live while it's open).
		 * The client only sees a container's contents while it's open.
		 */
		public String[] slotItems;
		public int[] slotCounts;
		/** The block when the box was added, e.g. "minecraft:red_shulker_box". Missing in saves from 1.0.0. */
		public @Nullable String block;
		/**
		 * A shulker box Material Box that was broken. Its items still count toward the list, and it re-attaches when
		 * it's placed and opened again. Its position is where it was last seen.
		 */
		public boolean pickedUp;
		/**
		 * The block is gone, but not because the player broke it (someone else did, or this is a different server
		 * behind the same address). Its items don't count, and it comes back if the block does.
		 */
		public boolean missing;

		public BoxEntry(BoxKey key, int size) {
			moveTo(key);
			this.size = size;
			this.slotItems = new String[size];
			this.slotCounts = new int[size];
		}

		public BoxKey key() {
			return new BoxKey(dimension, x, y, z);
		}

		public void moveTo(BoxKey key) {
			this.dimension = key.dimension();
			this.x = key.x();
			this.y = key.y();
			this.z = key.z();
		}

		public boolean isEmptyAt(int slot) {
			return slotItems == null || slot >= slotItems.length || slotItems[slot] == null;
		}

		public @Nullable Item itemAt(int slot) {
			return isEmptyAt(slot) ? null : Layout.resolve(slotItems[slot]);
		}

		public int countAt(int slot) {
			return isEmptyAt(slot) || slotCounts == null || slot >= slotCounts.length ? 0 : slotCounts[slot];
		}

		public void resize(int newSize) {
			size = newSize;
			slotItems = slotItems == null ? new String[newSize] : Arrays.copyOf(slotItems, newSize);
			slotCounts = slotCounts == null ? new int[newSize] : Arrays.copyOf(slotCounts, newSize);
		}

		/** Resizes, moving {@code count} recorded slots from {@code from} to {@code to}. Every other slot is empty. */
		public void reshape(int newSize, int from, int to, int count) {
			String[] items = new String[newSize];
			int[] counts = new int[newSize];
			for (int i = 0; i < count; i++) {
				int source = from + i;
				int target = to + i;
				if (target < newSize && !isEmptyAt(source)) {
					items[target] = slotItems[source];
					counts[target] = countAt(source);
				}
			}
			size = newSize;
			slotItems = items;
			slotCounts = counts;
		}

		/** Items inside shulker boxes kept in this box (item id to count). They count toward the list too. */
		public @Nullable Map<String, Integer> nested;

		/** Replaces the recorded contents, including what's inside shulker boxes. Returns true if anything changed. */
		public boolean setContents(String[] items, int[] counts, Map<String, Integer> nestedItems) {
			Map<String, Integer> newNested = nestedItems.isEmpty() ? null : nestedItems;
			boolean changed = setSlots(items, counts) | !Objects.equals(nested, newNested);
			nested = newNested;
			return changed;
		}

		public BoxEntry copy() {
			BoxEntry copy = new BoxEntry(key(), size);
			copy.slotItems = slotItems == null ? null : slotItems.clone();
			copy.slotCounts = slotCounts == null ? null : slotCounts.clone();
			copy.nested = nested == null ? null : new LinkedHashMap<>(nested);
			copy.block = block;
			copy.pickedUp = pickedUp;
			copy.missing = missing;
			return copy;
		}

		/** Shulker boxes keep their items when broken; other containers spill them. */
		public boolean isShulker() {
			Identifier id = block == null ? null : Identifier.tryParse(block);
			return id != null && BuiltInRegistries.BLOCK.getValue(id) instanceof ShulkerBoxBlock;
		}

		/** True if the recorded contents are exactly these: the same item and count in every slot. */
		public boolean hasContents(String[] items, int[] counts) {
			for (int i = 0; i < Math.max(size, items.length); i++) {
				String item = i < items.length ? items[i] : null;
				int count = item == null ? 0 : counts[i];
				if (!Objects.equals(item, isEmptyAt(i) ? null : slotItems[i]) || count != countAt(i)) {
					return false;
				}
			}
			return true;
		}

		/** Replaces the recorded contents. Returns true if anything changed. */
		public boolean setSlots(String[] items, int[] counts) {
			if (Arrays.equals(items, slotItems) && Arrays.equals(counts, slotCounts)) {
				return false;
			}
			slotItems = items;
			slotCounts = counts;
			return true;
		}
	}

	/** Drops or repairs what a damaged or hand-edited save can contain, so nothing else ever sees nulls. */
	public void sanitize() {
		if (materials == null) {
			materials = new ArrayList<>();
		}
		materials.removeIf(m -> m == null || m.item == null || m.item.isBlank() || m.count <= 0);
		if (boxes == null) {
			boxes = new ArrayList<>();
		}
		sanitizeBoxes(boxes);
		if (replacements == null) {
			replacements = new LinkedHashMap<>();
		}
		replacements.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
	}

	public static void sanitizeBoxes(List<BoxEntry> boxes) {
		boxes.removeIf(b -> b == null || b.dimension == null || b.size <= 0);
		for (BoxEntry box : boxes) {
			if (box.slotItems == null || box.slotItems.length != box.size || box.slotCounts == null || box.slotCounts.length != box.size) {
				box.resize(box.size);
			}
		}
	}

	/** Index of the placed box at this position. Picked-up shulker boxes aren't anywhere, so they never match. */
	public int indexOfBox(BoxKey key) {
		return indexOfBox(boxes, key);
	}

	public static int indexOfBox(List<BoxEntry> boxes, BoxKey key) {
		for (int i = 0; i < boxes.size(); i++) {
			if (!boxes.get(i).pickedUp && boxes.get(i).key().equals(key)) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Re-attaches a picked-up shulker box that has been placed again at {@code key}, recognised by its block (so its
	 * colour) and its exact contents, which can't change while it's an item. Returns its index, or -1.
	 */
	public int reattach(BoxKey key, String block, String[] items, int[] counts) {
		for (int i = 0; i < boxes.size(); i++) {
			BoxEntry box = boxes.get(i);
			if (box.pickedUp && block.equals(box.block) && box.hasContents(items, counts)) {
				box.moveTo(key);
				box.pickedUp = false;
				return i;
			}
		}
		return -1;
	}

	/**
	 * Changes a material's item and amount, remembering the swap so a re-import gets it too. If the new item is already
	 * on the list, the two entries are merged.
	 */
	public void replaceMaterial(int index, String item, int count) {
		String from = materials.get(index).item;
		if (!from.equals(item)) {
			recordReplacement(from, item);
		}
		for (int i = 0; i < materials.size(); i++) {
			if (i != index && materials.get(i).item.equals(item)) {
				materials.get(i).count = (int) Math.min(Integer.MAX_VALUE, (long) materials.get(i).count + count);
				materials.remove(index);
				return;
			}
		}
		materials.get(index).item = item;
		materials.get(index).count = count;
	}

	/** Remembers a swap, following chains (A to B, then B to C, becomes A to C) and dropping swaps that were undone. */
	private void recordReplacement(String from, String to) {
		boolean chained = false;
		for (Map.Entry<String, String> e : replacements.entrySet()) {
			if (e.getValue().equals(from)) {
				e.setValue(to);
				chained = true;
			}
		}
		if (!chained) {
			replacements.put(from, to);
		}
		replacements.entrySet().removeIf(e -> e.getKey().equals(e.getValue()));
	}

	/** The item an imported item id becomes in this list, after its replacements. */
	public String replacementFor(String id) {
		return replacements.getOrDefault(id, id);
	}
}
