package dev.kianj.materialsgui.hud;

import dev.kianj.materialsgui.box.BoxRefresher;
import dev.kianj.materialsgui.data.Layout;
import dev.kianj.materialsgui.data.ModConfig;
import dev.kianj.materialsgui.data.Project;
import dev.kianj.materialsgui.data.ProjectStore;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * An on-screen checklist of what's still missing from the material list, with how many you're carrying. Toggled with a
 * key (H by default) or in the settings, which also offer a compact version: no title or icons, and three materials.
 */
public final class MaterialsHud {
	private static final int MARGIN = 4;
	private static final int ROW = 17;
	private static final int COMPACT_ROW = 10;
	private static final int COMPACT_ROWS = 3;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GRAY = 0xFFA0A0A0;
	private static final int RED = 0xFFFF5555;
	private static final int YELLOW = 0xFFFFD040;
	private static final int GREEN = 0xFF55FF55;

	private MaterialsHud() {}

	public static void register() {
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("materialsgui", "missing_materials"), MaterialsHud::extract);
	}

	private record Line(Item item, Component name, String amount, int amountColor, String carried) {}

	/** Materials still missing, in list order. */
	private static List<Line> lines(Project project, Layout layout, Inventory inventory) {
		List<Line> lines = new ArrayList<>();
		for (Project.MaterialEntry m : project.materials) {
			Item item = Layout.resolve(m.item);
			if (item == null) {
				continue;
			}
			int stored = layout.stored(item);
			if (stored >= m.count) {
				continue;
			}
			int carried = count(inventory, item);
			lines.add(new Line(item, new ItemStack(item).getHoverName(), stored + "/" + m.count, stored > 0 ? YELLOW : RED,
				carried > 0 ? " +" + carried : ""));
		}
		return lines;
	}

	private static int count(Inventory inventory, Item item) {
		int total = 0;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.is(item)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	private static void extract(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		ModConfig config = ModConfig.get();
		Project project = ProjectStore.project();
		// Hidden along with the rest of the HUD when F1 is pressed, and while any screen is open: it's for gathering in
		// the world, and screens (Material Boxes, the Materials List) already show the same progress.
		if (!config.hudEnabled || mc.player == null || mc.gui.hud.isHidden() || mc.gui.screen() != null || project.materials.isEmpty()) {
			return;
		}
		boolean compact = config.hudCompact;
		List<Line> missing = lines(project, ProjectStore.layout(), mc.player.getInventory());
		Font font = mc.font;
		// The compact HUD only has a title once there's nothing left to list.
		String title = missing.isEmpty()
			? "All materials gathered!"
			: compact ? "" : missing.size() + (missing.size() == 1 ? " material" : " materials") + " still needed";
		int rows = Math.min(missing.size(), compact ? COMPACT_ROWS : Math.max(1, config.hudRows));
		int more = missing.size() - rows;
		int rowHeight = compact ? COMPACT_ROW : ROW;
		int icon = compact ? 0 : 18;
		int textY = compact ? 0 : 4;

		// Counts only change when a box is opened or refreshed, so say when some haven't been since joining.
		int unchecked = BoxRefresher.uncheckedCount();
		List<String> notes = new ArrayList<>();
		if (more > 0) {
			notes.add("+" + more + " more");
		}
		if (unchecked > 0) {
			notes.add(unchecked + (unchecked == 1 ? " box" : " boxes") + (compact ? " not checked" : " not checked since you joined"));
		}

		int width = font.width(title);
		for (int i = 0; i < rows; i++) {
			Line line = missing.get(i);
			width = Math.max(width, icon + font.width(line.name) + 8 + font.width(line.amount) + font.width(line.carried));
		}
		for (String note : notes) {
			width = Math.max(width, font.width(note));
		}
		int height = (title.isEmpty() ? 0 : 11) + rows * rowHeight + notes.size() * 10 - (compact && title.isEmpty() ? 2 : 0);
		int x = config.hudOnRight ? graphics.guiWidth() - MARGIN - width : MARGIN;
		int y = MARGIN;

		graphics.fill(x - 3, y - 3, x + width + 3, y + height + 1, 0x90000000);
		if (!title.isEmpty()) {
			graphics.text(font, title, x, y, missing.isEmpty() ? GREEN : WHITE, true);
			y += 11;
		}
		for (int i = 0; i < rows; i++) {
			Line line = missing.get(i);
			if (!compact) {
				graphics.item(new ItemStack(line.item), x, y);
			}
			graphics.text(font, line.name, x + icon, y + textY, WHITE, true);
			int right = x + width;
			graphics.text(font, line.carried, right - font.width(line.carried), y + textY, GRAY, true);
			graphics.text(font, line.amount, right - font.width(line.carried) - font.width(line.amount), y + textY, line.amountColor, true);
			y += rowHeight;
		}
		for (String note : notes) {
			graphics.text(font, note, x, y + (compact ? 0 : 1), GRAY, true);
			y += 10;
		}
	}
}
