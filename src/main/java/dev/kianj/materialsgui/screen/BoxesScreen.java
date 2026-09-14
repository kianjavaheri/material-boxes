package dev.kianj.materialsgui.screen;

import dev.kianj.materialsgui.box.BoxTracker;
import dev.kianj.materialsgui.data.BoxKey;
import dev.kianj.materialsgui.data.Project;
import dev.kianj.materialsgui.data.ProjectStore;
import dev.kianj.materialsgui.data.SavedLists;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import org.jspecify.annotations.Nullable;

/** Lists the saved Material Boxes and lets you remove them, e.g. ones whose block is gone. */
public class BoxesScreen extends Screen {
	private static final int PAD = 10;
	/** Everything sits in a centered column, like the Materials List. */
	private static final int MAX_WIDTH = 400;
	private static final int ROW = 24;
	private static final int TOP = 32;
	private static final int REMOVE_WIDTH = 60;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GRAY = 0xFFA0A0A0;
	private static final int RED = 0xFFFF5555;
	private static final int GREEN = 0xFF55FF55;
	private static final int YELLOW = 0xFFFFD040;

	private final @Nullable Screen parent;
	private int scroll;
	private boolean confirmClearAll;
	private int left;
	private int right;

	public BoxesScreen(@Nullable Screen parent) {
		super(Component.literal("Material Boxes"));
		this.parent = parent;
	}

	private List<Project.BoxEntry> boxes() {
		return ProjectStore.project().boxes;
	}

	private int visibleRows() {
		return Math.max(1, (this.height - 36 - TOP) / ROW);
	}

	@Override
	protected void init() {
		int columnWidth = Math.min(this.width - 2 * PAD, MAX_WIDTH);
		left = (this.width - columnWidth) / 2;
		right = left + columnWidth;
		scroll = Math.max(0, Math.min(scroll, boxes().size() - visibleRows()));
		for (int r = 0; r < visibleRows() && scroll + r < boxes().size(); r++) {
			int index = scroll + r;
			addRenderableWidget(Button.builder(Component.literal("Remove"), b -> {
				boxes().remove(index);
				ProjectStore.changed();
				rebuildWidgets();
			}).bounds(right - REMOVE_WIDTH, TOP + r * ROW, REMOVE_WIDTH, 20).build());
		}

		int bw = (columnWidth - 8) / 3;
		int by = this.height - 28;
		addRenderableWidget(Button.builder(Component.literal("Clear Missing"), b -> {
			boxes().removeIf(box -> status(box) == Status.MISSING);
			ProjectStore.changed();
			rebuildWidgets();
		}).bounds(left, by, bw, 20).build());
		addRenderableWidget(Button.builder(Component.literal(confirmClearAll ? "Sure?" : "Clear All"), b -> {
			if (confirmClearAll) {
				boxes().clear();
				ProjectStore.changed();
			}
			confirmClearAll = !confirmClearAll;
			rebuildWidgets();
		}).bounds(left + bw + 4, by, bw, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(left + 2 * (bw + 4), by, bw, 20).build());
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(parent);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		int max = Math.max(0, boxes().size() - visibleRows());
		int next = Math.max(0, Math.min(max, scroll - (int) Math.signum(scrollY)));
		if (next != scroll) {
			scroll = next;
			rebuildWidgets();
		}
		return true;
	}

	private enum Status {
		OK("OK", GREEN),
		MISSING("Block missing", RED),
		NOT_LOADED("Not loaded", GRAY),
		OTHER_DIMENSION("In another dimension", GRAY),
		PICKED_UP("Picked up", YELLOW),
		IN_INVENTORY("Picked up, in your inventory", YELLOW),
		UNKNOWN("", GRAY);

		final String label;
		final int color;

		Status(String label, int color) {
			this.label = label;
			this.color = color;
		}
	}

	private Status status(Project.BoxEntry box) {
		if (box.pickedUp) {
			return inInventory(box) ? Status.IN_INVENTORY : Status.PICKED_UP;
		}
		ClientLevel level = this.minecraft.level;
		if (level == null) {
			return Status.UNKNOWN;
		}
		if (!box.dimension.equals(level.dimension().identifier().toString())) {
			return Status.OTHER_DIMENSION;
		}
		BlockPos pos = new BlockPos(box.x, box.y, box.z);
		if (!level.isLoaded(pos)) {
			return Status.NOT_LOADED;
		}
		BlockPos canonical = BoxTracker.normalize(level, pos);
		return canonical != null && BoxKey.of(box.dimension, canonical).equals(box.key()) ? Status.OK : Status.MISSING;
	}

	/** Whether the player is carrying a shulker box item of the same colour with exactly this box's contents. */
	private boolean inInventory(Project.BoxEntry box) {
		if (this.minecraft.player == null || box.block == null) {
			return false;
		}
		Inventory inventory = this.minecraft.player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty() || !BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(box.block)) {
				continue;
			}
			NonNullList<ItemStack> contents = NonNullList.withSize(box.size, ItemStack.EMPTY);
			stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(contents);
			String[] items = new String[box.size];
			int[] counts = new int[box.size];
			for (int s = 0; s < box.size; s++) {
				if (!contents.get(s).isEmpty()) {
					items[s] = BuiltInRegistries.ITEM.getKey(contents.get(s).getItem()).toString();
					counts[s] = contents.get(s).getCount();
				}
			}
			if (box.hasContents(items, counts)) {
				return true;
			}
		}
		return false;
	}

	private static String boxName(Project.BoxEntry box) {
		Identifier id = box.block == null ? null : Identifier.tryParse(box.block);
		return id == null ? "Box" : BuiltInRegistries.BLOCK.getValue(id).getName().getString();
	}

	/** The text, shortened with "..." if it's wider than {@code width}. */
	private String fit(String text, int width) {
		if (this.font.width(text) <= width) {
			return text;
		}
		return this.font.plainSubstrByWidth(text, Math.max(0, width - this.font.width("..."))) + "...";
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(this.font, this.title, this.width / 2, 8, WHITE);
		List<Project.BoxEntry> boxes = boxes();
		if (boxes.isEmpty()) {
			graphics.centeredText(this.font, "No Material Boxes. Open a chest and click \"+ Material Box\".", this.width / 2, TOP + 6, GRAY);
			return;
		}
		graphics.centeredText(this.font, fit("Boxes fill in this order. Removing one doesn't touch its items.", right - left), this.width / 2, 19, GRAY);

		int rowRight = right - REMOVE_WIDTH - 6;
		for (int r = 0; r < visibleRows() && scroll + r < boxes.size(); r++) {
			int index = scroll + r;
			Project.BoxEntry box = boxes.get(index);
			int y = TOP + r * ROW;

			Status status = status(box);
			graphics.text(this.font, status.label, rowRight - this.font.width(status.label), y + 1, status.color, true);
			String dim = box.dimension.substring(box.dimension.indexOf(':') + 1);
			String where = box.pickedUp ? boxName(box) + " (last at " + box.key().describe() + ")" : box.key().describe() + " (" + dim + ")";
			String heading = "#" + (index + 1) + "  " + where;
			graphics.text(this.font, fit(heading, rowRight - left - this.font.width(status.label) - 8), left, y + 1, WHITE, true);

			int items = 0;
			if (box.slotCounts != null) {
				for (int c : box.slotCounts) {
					items += c;
				}
			}
			String details = box.size + " slots, " + items + " items";
			List<String> others = box.pickedUp ? List.of()
				: SavedLists.otherListsUsing(box.key(), ProjectStore.worldKey(), ProjectStore.project().listName);
			if (!others.isEmpty()) {
				details += ", also in " + String.join(", ", others);
			}
			graphics.text(this.font, fit(details, rowRight - left), left, y + 11, GRAY, true);
		}
	}
}
