package dev.kianj.materialsgui.preview;

import com.mojang.blaze3d.platform.InputConstants;
import dev.kianj.materialsgui.mixin.AbstractContainerScreenAccessor;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import org.jspecify.annotations.Nullable;

/**
 * A view-only look inside a shulker box item: right-click one in any inventory screen. It only reads the contents the
 * server already sends with the item and never clicks anything, so it works on any server.
 */
public final class ShulkerPreview {
	private static final Identifier TEXTURE = Identifier.withDefaultNamespace("textures/gui/container/shulker_box.png");
	private static final int WIDTH = 176;
	// The title bar and three slot rows from the shulker box texture, closed off with a strip of its bottom edge.
	private static final int ROWS_HEIGHT = 72;
	private static final int EDGE_HEIGHT = 4;
	private static final int TEXTURE_GUI_HEIGHT = 167;
	private static final int HEIGHT = ROWS_HEIGHT + EDGE_HEIGHT;
	private static final int SLOTS = 27;

	private static @Nullable Screen screen;
	private static @Nullable Slot slot;
	private static boolean swallowRelease;

	private ShulkerPreview() {}

	public static boolean isOpen(@Nullable Screen s) {
		return s != null && s == screen && slot != null;
	}

	private static void close() {
		screen = null;
		slot = null;
	}

	public static void afterInit(Minecraft mc, Screen s, int width, int height) {
		if (!(s instanceof AbstractContainerScreen<?> containerScreen)) {
			return;
		}
		close();
		ScreenMouseEvents.allowMouseClick(s).register((sc, event) -> onClick(containerScreen, event));
		// Swallow the release of any click the preview handled, so the screen underneath never sees half a click.
		ScreenMouseEvents.allowMouseRelease(s).register((sc, event) -> {
			boolean swallow = swallowRelease;
			swallowRelease = false;
			return !swallow;
		});
		ScreenMouseEvents.allowMouseScroll(s).register((sc, x, y, scrollX, scrollY) -> !isOpen(sc));
		ScreenKeyboardEvents.allowKeyPress(s).register((sc, event) -> onKey(mc, sc, event));
		ScreenEvents.remove(s).register(sc -> {
			if (screen == sc) {
				close();
			}
		});
	}

	private static boolean onClick(AbstractContainerScreen<?> cs, MouseButtonEvent event) {
		if (isOpen(cs)) {
			// View-only: clicks inside do nothing; right-click or clicking outside closes it.
			if (event.button() == 1 || !inside(cs, event.x(), event.y())) {
				close();
			}
			swallowRelease = true;
			return false;
		}
		// Only a plain right-click with nothing held; shift-right-click and left-click keep their vanilla behaviour.
		if (event.button() != 1 || event.hasShiftDown() || !cs.getMenu().getCarried().isEmpty()) {
			return true;
		}
		Slot hovered = ((AbstractContainerScreenAccessor) cs).materialsgui$getHoveredSlot();
		if (hovered == null || !hovered.getItem().is(ItemTags.SHULKER_BOXES)) {
			return true;
		}
		screen = cs;
		slot = hovered;
		swallowRelease = true;
		return false;
	}

	private static boolean onKey(Minecraft mc, Screen s, KeyEvent event) {
		if (!isOpen(s)) {
			return true;
		}
		if (event.key() == InputConstants.KEY_ESCAPE || mc.options.keyInventory.matches(event)) {
			close();
		}
		// Other keys (hotbar swaps, drop) would act on the slot underneath, so they're blocked while it's open.
		return false;
	}

	private static int left(Screen s) {
		return (s.width - WIDTH) / 2;
	}

	private static int top(Screen s) {
		return (s.height - HEIGHT) / 2;
	}

	private static boolean inside(Screen s, double x, double y) {
		return x >= left(s) && x < left(s) + WIDTH && y >= top(s) && y < top(s) + HEIGHT;
	}

	/** Draws the preview over the screen if it's open. Returns true if it did, so the normal slot tooltip is skipped. */
	public static boolean extract(AbstractContainerScreen<?> cs, GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (!isOpen(cs)) {
			return false;
		}
		ItemStack shulker = slot.getItem();
		if (!shulker.is(ItemTags.SHULKER_BOXES)) {
			// The box was moved out of that slot.
			close();
			return false;
		}
		NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
		shulker.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(items);

		Font font = Minecraft.getInstance().font;
		int x = left(cs);
		int y = top(cs);
		graphics.nextStratum();
		graphics.fill(0, 0, cs.width, cs.height, 0x90101010);
		graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, x, y, 0.0F, 0.0F, WIDTH, ROWS_HEIGHT, 256, 256);
		graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, x, y + ROWS_HEIGHT, 0.0F, TEXTURE_GUI_HEIGHT - EDGE_HEIGHT, WIDTH, EDGE_HEIGHT, 256, 256);
		graphics.text(font, shulker.getHoverName(), x + 8, y + 6, 0xFF404040, false);

		ItemStack hoveredItem = ItemStack.EMPTY;
		for (int i = 0; i < SLOTS; i++) {
			int sx = x + 8 + (i % 9) * 18;
			int sy = y + 18 + (i / 9) * 18;
			ItemStack stack = items.get(i);
			if (mouseX >= sx - 1 && mouseX < sx + 17 && mouseY >= sy - 1 && mouseY < sy + 17) {
				graphics.fill(sx, sy, sx + 16, sy + 16, 0x80FFFFFF);
				hoveredItem = stack;
			}
			graphics.item(stack, sx, sy);
			graphics.itemDecorations(font, stack, sx, sy);
		}
		graphics.centeredText(font, "View only. Esc or right-click to close.", cs.width / 2, y + HEIGHT + 4, 0xFFA0A0A0);
		if (!hoveredItem.isEmpty()) {
			graphics.setTooltipForNextFrame(font, hoveredItem, mouseX, mouseY);
		}
		return true;
	}
}
