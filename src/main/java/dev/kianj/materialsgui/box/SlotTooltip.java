package dev.kianj.materialsgui.box;

import dev.kianj.materialsgui.data.Layout;
import dev.kianj.materialsgui.data.ProjectStore;
import dev.kianj.materialsgui.mixin.AbstractContainerScreenAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.jspecify.annotations.Nullable;

/** Progress lines shown when hovering a highlighted Material Box slot. */
public final class SlotTooltip {
	/** The red-slot tooltip set in the last frame, or null. Lets the game test check it's really shown, not just built. */
	public static @Nullable List<Component> lastGhostTooltip;

	private SlotTooltip() {}

	/** "have/target" in the slot's highlight colour (e.g. 0/5 red, 3/5 yellow, 7/5 green), then the total across boxes. */
	public static List<Component> lines(Layout.SlotPlan plan, ItemStack stack) {
		int have = stack.is(plan.item()) ? stack.getCount() : 0;
		ChatFormatting color = have == 0 ? ChatFormatting.RED : have < plan.target() ? ChatFormatting.YELLOW : ChatFormatting.GREEN;
		Layout layout = ProjectStore.layout();
		return List.of(
			Component.literal(have + "/" + plan.target()).withStyle(color),
			Component.literal("All boxes: " + layout.stored(plan.item()) + "/" + layout.needed(plan.item())).withStyle(ChatFormatting.GRAY));
	}

	/** Tooltip lines for the hovered empty slot showing a ghost (item name, red 0/N, total), or null if none. */
	public static @Nullable List<Component> ghostTooltip(AbstractContainerScreen<?> screen) {
		Slot hovered = ((AbstractContainerScreenAccessor) screen).materialsgui$getHoveredSlot();
		if (hovered == null || hovered.hasItem() || !screen.getMenu().getCarried().isEmpty()) {
			return null;
		}
		Layout.SlotPlan plan = SlotOverlay.planFor(screen, hovered);
		if (plan == null) {
			return null;
		}
		List<Component> tooltip = new ArrayList<>();
		tooltip.add(new ItemStack(plan.item()).getHoverName());
		tooltip.addAll(lines(plan, ItemStack.EMPTY));
		tooltip.add(Component.literal("Shift-click from your inventory, or put it anywhere").withStyle(ChatFormatting.DARK_GRAY));
		return tooltip;
	}

	/** Shows {@link #ghostTooltip} for the hovered slot. Called from the screen mixin, before the deferred tooltip pass. */
	public static void extractGhostTooltip(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		List<Component> tooltip = ghostTooltip(screen);
		lastGhostTooltip = tooltip;
		if (tooltip != null) {
			graphics.setTooltipForNextFrame(Minecraft.getInstance().font, tooltip, Optional.empty(), mouseX, mouseY);
		}
	}

	/** Adds the progress lines under the item name when hovering a filled, highlighted slot in a Material Box. */
	public static void appendToItemTooltip(ItemStack stack, Item.TooltipContext context, TooltipFlag flag, List<Component> tooltip) {
		if (!(Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?> screen)) {
			return;
		}
		Slot hovered = ((AbstractContainerScreenAccessor) screen).materialsgui$getHoveredSlot();
		// The tooltip being built is for the hovered slot's own stack instance, not a copy shown elsewhere.
		if (hovered == null || hovered.getItem() != stack) {
			return;
		}
		Layout.SlotPlan plan = SlotOverlay.planFor(screen, hovered);
		if (plan != null && stack.is(plan.item())) {
			tooltip.addAll(Math.min(1, tooltip.size()), lines(plan, stack));
		}
	}
}
