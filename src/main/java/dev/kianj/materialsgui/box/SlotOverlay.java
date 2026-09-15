package dev.kianj.materialsgui.box;

import dev.kianj.materialsgui.data.BoxKey;
import dev.kianj.materialsgui.data.Layout;
import dev.kianj.materialsgui.data.ModConfig;
import dev.kianj.materialsgui.data.ProjectStore;
import dev.kianj.materialsgui.mixin.AbstractContainerScreenAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/** Draws the red/yellow/green slot tint, the ghost item, and the needed amount. Called from the screen mixin. */
public final class SlotOverlay {
	public static final int NONE = 0;
	public static final int RED = 0x90E03030;
	public static final int YELLOW = 0x90E8C020;
	public static final int GREEN = 0x9030C040;
	private static final int GHOST_WASH_ALPHA = 0x70;

	private SlotOverlay() {}

	public static Layout.@Nullable SlotPlan planFor(AbstractContainerScreen<?> screen, Slot slot) {
		BoxKey key = BoxTracker.keyFor(screen);
		// Hidden highlights hide the ghosts, amounts and progress tooltips too.
		if (key == null || slot.container instanceof Inventory || !ModConfig.get().highlightSlots) {
			return null;
		}
		// While a stack is dragged across slots, vanilla draws a preview stack and count in them; don't draw over it.
		AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) screen;
		if (acc.materialsgui$isQuickCrafting() && acc.materialsgui$getQuickCraftSlots().contains(slot)) {
			return null;
		}
		return ProjectStore.layout().plan(key, slot.index);
	}

	/** Red: empty (ghost). Yellow: partial stack that should be topped up. Green: done. */
	public static int color(ItemStack stack, Layout.SlotPlan plan) {
		if (stack.isEmpty()) {
			return RED;
		}
		if (!stack.is(plan.item())) {
			return NONE; // contents changed since the last layout; the next frame will catch up
		}
		return stack.getCount() >= plan.target() ? GREEN : YELLOW;
	}

	/** Before vanilla draws the slot's item: tint, plus a washed-out ghost of the expected item if the slot is empty. */
	public static void before(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics, Slot slot) {
		Layout.SlotPlan plan = planFor(screen, slot);
		if (plan == null) {
			return;
		}
		int x = slot.x;
		int y = slot.y;
		ItemStack stack = slot.getItem();
		int color = color(stack, plan);
		if (color == NONE) {
			return;
		}
		// Overlapping elements stack in submission order, so: tint, then ghost item, then a lighter wash over the ghost.
		graphics.fill(x, y, x + 16, y + 16, color);
		if (stack.isEmpty()) {
			graphics.fakeItem(new ItemStack(plan.item()), x, y);
			graphics.fill(x, y, x + 16, y + 16, (GHOST_WASH_ALPHA << 24) | (color & 0xFFFFFF));
		}
	}

	/** After vanilla draws the item: the amount still expected in this slot. */
	public static void after(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics, Slot slot) {
		Layout.SlotPlan plan = planFor(screen, slot);
		if (plan == null) {
			return;
		}
		Font font = Minecraft.getInstance().font;
		int x = slot.x;
		int y = slot.y;
		ItemStack stack = slot.getItem();
		int color = color(stack, plan);
		if (color == RED) {
			String s = String.valueOf(plan.target());
			graphics.text(font, s, x + 17 - font.width(s), y + 9, 0xFFFFFFFF, true);
		} else if (color == YELLOW) {
			String s = "/" + plan.target();
			graphics.pose().pushMatrix();
			graphics.pose().translate(x, y);
			graphics.pose().scale(0.5f, 0.5f);
			graphics.text(font, s, 1, 1, 0xFFFFFF80, true);
			graphics.pose().popMatrix();
		}
	}
}
