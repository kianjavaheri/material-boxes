package dev.kianj.materialsgui.box;

import dev.kianj.materialsgui.data.BoxKey;
import dev.kianj.materialsgui.data.Layout;
import dev.kianj.materialsgui.data.ProjectStore;
import dev.kianj.materialsgui.mixin.AbstractContainerScreenAccessor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Moves items from the player inventory into a Material Box's highlighted slots (partial stacks first, then ghost
 * slots): on shift-click for one stack, or all at once with Deposit All. Items with nothing highlighted fall back to
 * vanilla shift-click. Uses the same click packets a player would send, so no server-side mod is needed.
 */
public final class ShiftRouter {
	private ShiftRouter() {}

	/** Returns true when the click was handled (and vanilla handling should be cancelled). */
	public static boolean handle(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
		BoxKey key = BoxTracker.keyFor(screen);
		if (key == null || event.button() != 0 || !event.hasShiftDown() || ProjectStore.project().indexOfBox(key) < 0) {
			return false;
		}
		Slot source = ((AbstractContainerScreenAccessor) screen).materialsgui$getHoveredSlot();
		AbstractContainerMenu menu = screen.getMenu();
		Layout.SlotPlan[] plans = ProjectStore.layout().plans(key);
		if (plans == null || source == null || !(source.container instanceof Inventory) || !source.hasItem() || !menu.getCarried().isEmpty()) {
			return false;
		}
		return route(menu, plans, source) >= 0;
	}

	/**
	 * Moves every listed item in the player's inventory into its highlighted slots in the open Material Box. Returns
	 * how many items moved, or -1 if the screen isn't a Material Box.
	 */
	public static int depositAll(AbstractContainerScreen<?> screen) {
		BoxKey key = BoxTracker.keyFor(screen);
		AbstractContainerMenu menu = screen.getMenu();
		Layout.SlotPlan[] plans = key == null ? null : ProjectStore.layout().plans(key);
		if (plans == null || ProjectStore.project().indexOfBox(key) < 0) {
			return -1;
		}
		if (!menu.getCarried().isEmpty()) {
			return 0;
		}
		int moved = 0;
		for (Slot source : menu.slots) {
			if (source.container instanceof Inventory && source.hasItem()) {
				// Targets are re-checked against the live slot contents, so later stacks only fill what earlier ones left.
				moved += Math.max(0, route(menu, plans, source));
			}
		}
		return moved;
	}

	/** Places the source stack into the slots planned for its item. Returns how many moved, or -1 if none are planned. */
	private static int route(AbstractContainerMenu menu, Layout.SlotPlan[] plans, Slot source) {
		Item item = source.getItem().getItem();
		List<Integer> targets = new ArrayList<>();
		for (int i = 0; i < plans.length; i++) {
			if (plans[i] != null && plans[i].item() == item && menu.getSlot(i).getItem().getCount() < plans[i].target()) {
				targets.add(i);
			}
		}
		if (targets.isEmpty()) {
			return -1;
		}
		// Top up partial stacks before starting new ones.
		targets.sort(Comparator.comparing(i -> menu.getSlot(i).getItem().isEmpty()));

		Minecraft mc = Minecraft.getInstance();
		int before = source.getItem().getCount();
		click(mc, menu, source.index, 0, ContainerInput.PICKUP);
		for (int i : targets) {
			ItemStack carried = menu.getCarried();
			if (carried.isEmpty()) {
				break;
			}
			ItemStack current = menu.getSlot(i).getItem();
			if (!current.isEmpty() && !ItemStack.isSameItemSameComponents(current, carried)) {
				continue;
			}
			int target = plans[i].target();
			int room = target - current.getCount();
			if (room <= 0) {
				continue;
			}
			if (carried.getCount() <= room || target >= carried.getMaxStackSize()) {
				// A left click places as much as fits, which never exceeds a full-stack target.
				click(mc, menu, i, 0, ContainerInput.PICKUP);
			} else {
				// Partial target: right click places one item at a time so we stop exactly at the target.
				for (int n = 0; n < room; n++) {
					click(mc, menu, i, 1, ContainerInput.PICKUP);
				}
			}
		}
		if (!menu.getCarried().isEmpty()) {
			// The list doesn't need the rest; put it back where it came from.
			click(mc, menu, source.index, 0, ContainerInput.PICKUP);
		}
		return before - source.getItem().getCount();
	}

	private static void click(Minecraft mc, AbstractContainerMenu menu, int slot, int button, ContainerInput input) {
		mc.gameMode.handleContainerInput(menu.containerId, slot, button, input, mc.player);
	}
}
