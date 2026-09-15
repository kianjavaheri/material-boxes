package dev.kianj.materialsgui.box;

import dev.kianj.materialsgui.data.BoxKey;
import dev.kianj.materialsgui.data.Layout;
import dev.kianj.materialsgui.data.Project;
import dev.kianj.materialsgui.data.ProjectStore;
import dev.kianj.materialsgui.data.SavedLists;
import dev.kianj.materialsgui.mixin.AbstractContainerScreenAccessor;
import dev.kianj.materialsgui.screen.MaterialsScreen;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

/** Adds the Material Box buttons, header, tooltips, live layout updates, and shift-click routing to container screens. */
public final class ContainerHooks {
	private static final int BUTTON_WIDTH = 108;

	private ContainerHooks() {}

	public static void afterInit(Minecraft mc, Screen screen, int width, int height) {
		if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
			return;
		}
		BoxKey key = BoxTracker.attach(screen);
		if (key == null) {
			return;
		}
		int index = ProjectStore.project().indexOfBox(key);
		if (index >= 0) {
			Project.BoxEntry box = ProjectStore.project().boxes.get(index);
			// It's open, so it's there; and its slot plans must match the menu before any slot is drawn or clicked.
			if (box.size != BoxTracker.size() || box.missing) {
				box.missing = false;
				box.resize(BoxTracker.size());
				ProjectStore.changed();
			}
		}

		AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) containerScreen;
		int left = acc.materialsgui$getLeftPos();
		int top = acc.materialsgui$getTopPos();
		int bx = sideX(acc, width);
		int bw = sideWidth(acc, width);

		Button toggle = Button.builder(toggleLabel(key), b -> {
			toggle(containerScreen, key);
			b.setMessage(toggleLabel(key));
		}).bounds(bx, top, bw, 20).tooltip(Tooltip.create(Component.literal(
			"Material Boxes hold the materials on your list. Items already inside count toward it."))).build();
		Button list = Button.builder(Component.literal("Materials List..."), b -> {
			mc.player.closeContainer();
			mc.gui.setScreen(new MaterialsScreen());
		}).bounds(bx, top + 24, bw, 20).build();
		Button deposit = Button.builder(Component.literal("Deposit All"), b -> {
			int moved = ShiftRouter.depositAll(containerScreen);
			mc.player.sendOverlayMessage(Component.literal(moved < 0 ? "Click \"+ Material Box\" first"
				: moved == 0 ? "Nothing in your inventory goes in this box" : "Deposited " + moved + " items"));
		}).bounds(bx, top + 48, bw, 20).tooltip(Tooltip.create(Component.literal(
			"Move everything on your list from your inventory into this box's highlighted slots"))).build();
		Screens.getWidgets(screen).add(toggle);
		Screens.getWidgets(screen).add(list);
		Screens.getWidgets(screen).add(deposit);

		ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> !ShiftRouter.handle(containerScreen, event));
		// Refresh the layout from the live contents before slots are drawn, so highlights follow items as they move.
		ScreenEvents.beforeExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> {
			if (tryReattach(containerScreen, key)) {
				toggle.setMessage(toggleLabel(key));
			}
			if (snapshot(containerScreen, key)) {
				ProjectStore.recompute();
			}
			if (containerScreen.getMenu().getStateId() != 0) {
				BoxRefresher.markSeen(key);
			}
		});
		ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> afterExtract(containerScreen, key, graphics, mouseX, mouseY));
		ScreenEvents.remove(screen).register(s -> {
			snapshot(containerScreen, key);
			ProjectStore.changed();
			BoxTracker.detach(s);
			BoxRefresher.closedByPlayer(key);
		});
	}

	private static Component toggleLabel(BoxKey key) {
		int index = ProjectStore.project().indexOfBox(key);
		return index < 0 ? Component.literal("+ Material Box") : Component.literal("Remove Box #" + (index + 1));
	}

	private static void toggle(AbstractContainerScreen<?> screen, BoxKey key) {
		Project project = ProjectStore.project();
		int index = project.indexOfBox(key);
		if (index >= 0) {
			project.boxes.remove(index);
		} else {
			Project.BoxEntry box = new Project.BoxEntry(key, BoxTracker.size());
			box.block = BoxTracker.blockId(Minecraft.getInstance().level, new BlockPos(key.x(), key.y(), key.z()));
			project.boxes.add(box);
			// Whatever is already inside counts toward the list right away.
			snapshot(screen, key);
		}
		ProjectStore.changed();
	}

	/** Records the box's slot contents. Returns true if they changed since the last snapshot. */
	private static boolean snapshot(AbstractContainerScreen<?> screen, BoxKey key) {
		int index = ProjectStore.project().indexOfBox(key);
		// Until the server sends the contents (the state id is 0 until then) the menu looks empty, which isn't the box.
		if (index < 0 || screen.getMenu().getStateId() == 0) {
			return false;
		}
		Project.BoxEntry box = ProjectStore.project().boxes.get(index);
		Contents contents = read(screen.getMenu(), box.size);
		return box.setContents(contents.items(), contents.counts(), contents.nested());
	}

	record Contents(String[] items, int[] counts, Map<String, Integer> nested) {}

	/**
	 * Item id and count of the first {@code size} container slots (never the player's inventory), plus the totals of
	 * what's inside any shulker boxes there.
	 */
	static Contents read(AbstractContainerMenu menu, int size) {
		String[] items = new String[size];
		int[] counts = new int[size];
		Map<String, Integer> nested = new LinkedHashMap<>();
		List<Slot> slots = menu.slots;
		for (int i = 0; i < size && i < slots.size(); i++) {
			Slot slot = slots.get(i);
			if (slot.container instanceof Inventory) {
				break;
			}
			ItemStack stack = slot.getItem();
			if (!stack.isEmpty()) {
				items[i] = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
				counts[i] = stack.getCount();
				ItemContainerContents inside = stack.get(DataComponents.CONTAINER);
				if (inside != null) {
					inside.nonEmptyItemCopyStream().forEach(s -> nested.merge(BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), s.getCount(), Integer::sum));
				}
			}
		}
		return new Contents(items, counts, nested);
	}

	/**
	 * Recognises a picked-up shulker Material Box that has been placed again, by its colour and exact contents. The
	 * contents only arrive after the screen opens (the menu's state id is 0 until the server sends them).
	 */
	private static boolean tryReattach(AbstractContainerScreen<?> screen, BoxKey key) {
		Project project = ProjectStore.project();
		if (project.indexOfBox(key) >= 0 || screen.getMenu().getStateId() == 0 || project.boxes.stream().noneMatch(b -> b.pickedUp)) {
			return false;
		}
		String block = BoxTracker.blockId(Minecraft.getInstance().level, new BlockPos(key.x(), key.y(), key.z()));
		Contents contents = read(screen.getMenu(), BoxTracker.size());
		if (project.reattach(key, block, contents.items(), contents.counts()) < 0) {
			return false;
		}
		ProjectStore.changed();
		return true;
	}

	private static void afterExtract(AbstractContainerScreen<?> screen, BoxKey key, GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int index = ProjectStore.project().indexOfBox(key);
		if (index < 0) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) screen;
		Layout.SlotPlan[] plans = ProjectStore.layout().plans(key);

		int planned = 0;
		int done = 0;
		if (plans != null) {
			for (int i = 0; i < plans.length; i++) {
				if (plans[i] != null) {
					planned++;
					if (SlotOverlay.color(screen.getMenu().getSlot(i).getItem(), plans[i]) == SlotOverlay.GREEN) {
						done++;
					}
				}
			}
		}
		String header = "Material Box #" + (index + 1) + " of " + ProjectStore.project().boxes.size()
			+ (planned == 0 ? "  (nothing needed here)" : "  " + done + "/" + planned + " slots done");
		int top = acc.materialsgui$getTopPos();
		if (top >= 12) {
			graphics.text(mc.font, header, acc.materialsgui$getLeftPos(), top - 11, 0xFFFFFFFF, true);
		}

		// Other saved lists that use this box, listed under the side buttons.
		List<String> others = SavedLists.otherListsUsing(key, ProjectStore.worldKey(), ProjectStore.project().listName);
		if (!others.isEmpty()) {
			int x = sideX(acc, screen.width);
			int y = top + 74;
			graphics.text(mc.font, "Also in:", x, y, 0xFFA0A0A0, true);
			for (String name : others) {
				y += 10;
				graphics.text(mc.font, mc.font.plainSubstrByWidth(name, sideWidth(acc, screen.width)), x, y, 0xFFFFFFFF, true);
			}
		}
	}

	/** Width of the column beside the container for the Material Box buttons: 108, or less on narrow screens. */
	private static int sideWidth(AbstractContainerScreenAccessor acc, int screenWidth) {
		int left = acc.materialsgui$getLeftPos();
		int right = screenWidth - left - acc.materialsgui$getImageWidth();
		return Math.max(40, Math.min(BUTTON_WIDTH, Math.max(left, right) - 6));
	}

	/** Left edge of the column beside the container where the Material Box buttons go: right if it fits, else left. */
	private static int sideX(AbstractContainerScreenAccessor acc, int screenWidth) {
		int left = acc.materialsgui$getLeftPos();
		int width = sideWidth(acc, screenWidth);
		int x = left + acc.materialsgui$getImageWidth() + 4;
		return x + width <= screenWidth - 2 ? x : Math.max(2, left - width - 4);
	}
}
