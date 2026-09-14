package dev.kianj.materialsgui.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * Works out what each Material Box slot should show, based on what the boxes actually contain.
 *
 * <ol>
 *   <li>Everything already in any Material Box counts toward the list, wherever it sits.</li>
 *   <li>Slots holding a listed material are highlighted where they are. Partial stacks are topped up first.</li>
 *   <li>Whatever is still missing is shown as ghost items in empty slots, in list order, filling boxes in the order
 *       they were added.</li>
 * </ol>
 * Recomputed whenever box contents change, so moving items around just moves the ghosts.
 */
public final class Layout {
	/** A slot's material and how many it should hold. An empty slot with a plan shows a ghost of the item. */
	public record SlotPlan(Item item, int target) {}

	private final Map<BoxKey, SlotPlan[]> plans = new HashMap<>();
	private final Map<Item, Integer> needed = new LinkedHashMap<>();
	private final Map<Item, Integer> stored = new HashMap<>();
	private final Map<Item, Integer> unplaced = new LinkedHashMap<>();
	private int missingSlots;

	public static Layout compute(Project project) {
		Layout layout = new Layout();
		for (Project.MaterialEntry entry : project.materials) {
			Item item = resolve(entry.item);
			if (item != null && entry.count > 0) {
				layout.needed.merge(item, entry.count, Layout::sum);
			}
		}
		for (Project.BoxEntry box : project.boxes) {
			for (int i = 0; i < box.size; i++) {
				Item item = box.itemAt(i);
				if (item != null) {
					layout.stored.merge(item, box.countAt(i), Layout::sum);
				}
			}
			// What's inside shulker boxes kept in the box counts too.
			if (box.nested != null) {
				box.nested.forEach((id, count) -> {
					Item item = resolve(id);
					if (item != null && count != null) {
						layout.stored.merge(item, count, Layout::sum);
					}
				});
			}
		}
		Map<Item, Integer> remaining = new HashMap<>();
		layout.needed.forEach((item, n) -> remaining.put(item, Math.max(0, n - layout.stored.getOrDefault(item, 0))));

		// Slots that already hold a listed material. Partial stacks absorb what's still needed first.
		for (Project.BoxEntry box : project.boxes) {
			SlotPlan[] arr = new SlotPlan[box.size];
			layout.plans.put(box.key(), arr);
			for (int i = 0; i < box.size; i++) {
				Item item = box.itemAt(i);
				if (item == null || !layout.needed.containsKey(item)) {
					continue;
				}
				int count = box.countAt(i);
				int add = Math.min(Math.max(0, maxStack(item) - count), remaining.get(item));
				remaining.merge(item, -add, Integer::sum);
				arr[i] = new SlotPlan(item, count + add);
			}
		}

		// Where the boxes hold more than the list needs, take the extra off the last stacks of that item, so a slot
		// holding 7 when only 5 are needed reads 7/5.
		for (Map.Entry<Item, Integer> e : layout.needed.entrySet()) {
			Item item = e.getKey();
			int excess = layout.stored.getOrDefault(item, 0) - e.getValue();
			for (int b = project.boxes.size() - 1; b >= 0 && excess > 0; b--) {
				SlotPlan[] arr = layout.plans.get(project.boxes.get(b).key());
				for (int i = arr.length - 1; i >= 0 && excess > 0; i--) {
					if (arr[i] != null && arr[i].item() == item) {
						int cut = Math.min(excess, arr[i].target());
						arr[i] = new SlotPlan(item, arr[i].target() - cut);
						excess -= cut;
					}
				}
			}
		}

		// Ghosts for what's still missing, in empty slots. A picked-up shulker box can't be filled, so it gets none.
		List<Item> queue = new ArrayList<>(layout.needed.keySet());
		int q = 0;
		for (Project.BoxEntry box : project.boxes) {
			if (box.pickedUp) {
				continue;
			}
			SlotPlan[] arr = layout.plans.get(box.key());
			for (int i = 0; i < box.size; i++) {
				if (!box.isEmptyAt(i)) {
					continue;
				}
				while (q < queue.size() && remaining.get(queue.get(q)) <= 0) {
					q++;
				}
				if (q >= queue.size()) {
					break;
				}
				Item item = queue.get(q);
				int target = Math.min(maxStack(item), remaining.get(item));
				remaining.merge(item, -target, Integer::sum);
				arr[i] = new SlotPlan(item, target);
			}
		}

		long slots = 0;
		for (Map.Entry<Item, Integer> e : remaining.entrySet()) {
			int n = e.getValue();
			if (n > 0) {
				int max = maxStack(e.getKey());
				layout.unplaced.put(e.getKey(), n);
				slots += n / max + (n % max == 0 ? 0 : 1);
			}
		}
		layout.missingSlots = (int) Math.min(Integer.MAX_VALUE, slots);
		return layout;
	}

	/** Adds two amounts, capping instead of overflowing. */
	private static int sum(int a, int b) {
		return (int) Math.min(Integer.MAX_VALUE, (long) a + b);
	}

	private static int maxStack(Item item) {
		return Math.max(1, item.getDefaultMaxStackSize());
	}

	/**
	 * A heading plus a "count name" line for each material that's still missing, or "" if nothing is. It pastes back in
	 * as a material list.
	 */
	public String missingText(Project project) {
		StringBuilder sb = new StringBuilder();
		for (Project.MaterialEntry m : project.materials) {
			Item item = resolve(m.item);
			long missing = (long) m.count - (item == null ? 0 : stored(item));
			if (missing > 0) {
				sb.append(missing).append(' ').append(item == null ? m.item : new ItemStack(item).getHoverName().getString()).append('\n');
			}
		}
		if (sb.isEmpty()) {
			return "";
		}
		return (project.listName == null ? "Still needed" : "Still needed for " + project.listName) + ":\n" + sb;
	}

	public static @Nullable Item resolve(String id) {
		Identifier identifier = Identifier.tryParse(id);
		if (identifier == null || !BuiltInRegistries.ITEM.containsKey(identifier)) {
			return null;
		}
		Item item = BuiltInRegistries.ITEM.getValue(identifier);
		return item == Items.AIR ? null : item;
	}

	public SlotPlan @Nullable [] plans(BoxKey box) {
		return plans.get(box);
	}

	public @Nullable SlotPlan plan(BoxKey box, int slot) {
		SlotPlan[] arr = plans.get(box);
		return arr == null || slot < 0 || slot >= arr.length ? null : arr[slot];
	}

	/** How many of this item the material list needs in total. */
	public int needed(Item item) {
		return needed.getOrDefault(item, 0);
	}

	/** How many of this item are in Material Boxes (as of each box's last snapshot). */
	public int stored(Item item) {
		return stored.getOrDefault(item, 0);
	}

	/** Materials that don't fit in the current boxes, with how many are left over. */
	public Map<Item, Integer> unplaced() {
		return unplaced;
	}

	/** Extra empty slots needed to fit everything that's still missing. */
	public int missingSlots() {
		return missingSlots;
	}
}
