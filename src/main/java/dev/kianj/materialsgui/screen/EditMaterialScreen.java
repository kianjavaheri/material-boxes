package dev.kianj.materialsgui.screen;

import com.mojang.blaze3d.platform.InputConstants;
import dev.kianj.materialsgui.data.Layout;
import dev.kianj.materialsgui.data.Project;
import dev.kianj.materialsgui.data.ProjectStore;
import dev.kianj.materialsgui.importer.ItemResolver;
import dev.kianj.materialsgui.importer.MaterialParser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * Replace a material on the list with a different item (keeping the amount), or change its amount. The same screen
 * fixes an unrecognized import line: there's no material yet, so picking an item adds one and the line goes away.
 */
public class EditMaterialScreen extends Screen {
	private static final int WIDTH = 300;
	private static final int ROW = 18;
	private static final int RESULTS_TOP = 64;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GRAY = 0xFFA0A0A0;
	private static final int RED = 0xFFFF5555;
	private static final int GREEN = 0xFF55FF55;

	private final MaterialsScreen parent;
	/** The material being edited, or -1 when an unrecognized import line is being fixed. */
	private final int index;
	/** The unrecognized line this screen is fixing, or null when an existing material is being edited. */
	private final @Nullable String line;
	/** That line's amount, which can't become a number until an item is picked ("2 sb" depends on how it stacks). */
	private final MaterialParser.@Nullable Line parsed;
	private EditBox search;
	private EditBox amount;
	private String query = "";
	private @Nullable String amountText;
	/** Once the amount has been typed in by hand, picking an item stops overwriting it. */
	private boolean amountEdited;
	private List<Item> results = List.of();
	private @Nullable Item selected;
	private @Nullable String error;
	private int left;

	public EditMaterialScreen(MaterialsScreen parent, int index) {
		super(Component.literal("Edit Material"));
		this.parent = parent;
		this.index = index;
		this.line = null;
		this.parsed = null;
	}

	/** Fixes an unrecognized import line: the text goes in the search box, and its amount follows the item picked. */
	public EditMaterialScreen(MaterialsScreen parent, String line) {
		super(Component.literal("Unrecognized Line"));
		this.parent = parent;
		this.index = -1;
		this.line = line;
		this.parsed = MaterialParser.split(line);
		this.query = parsed.name();
	}

	private Project.MaterialEntry entry() {
		return ProjectStore.project().materials.get(index);
	}

	/** The amount to show for the item currently picked, before the player types over it. */
	private String suggestedAmount() {
		if (parsed == null) {
			return String.valueOf(entry().count);
		}
		return String.valueOf(parsed.countFor(selected == null ? Items.STONE : selected));
	}

	private int maxResults() {
		return Math.max(3, Math.min(8, (this.height - RESULTS_TOP - 60) / ROW));
	}

	private int amountY() {
		return RESULTS_TOP + maxResults() * ROW + 6;
	}

	@Override
	protected void init() {
		left = (this.width - WIDTH) / 2;
		search = new EditBox(this.font, left, 38, WIDTH, 20, Component.literal("Replace with"));
		search.setMaxLength(100);
		search.setHint(Component.literal(line == null ? "Replace with... type an item name" : "What did this line mean?"));
		search.setValue(query);
		search.setResponder(this::updateResults);
		addRenderableWidget(search);

		int y = amountY();
		amount = new EditBox(this.font, left + 50, y, 70, 20, Component.literal("Amount"));
		amount.setMaxLength(10);
		amount.setValue(amountText != null ? amountText : suggestedAmount());
		amount.setResponder(v -> {
			amountEdited |= !v.equals(suggestedAmount());
			amountText = v;
		});
		addRenderableWidget(amount);

		int bx = left + 128;
		int bw = (WIDTH - 128 - 8) / 3;
		addRenderableWidget(Button.builder(Component.literal("Save"), b -> save()).bounds(bx, y, bw, 20).build());
		addRenderableWidget(Button.builder(Component.literal(line == null ? "Remove" : "Discard"), b -> remove())
			.bounds(bx + bw + 4, y, bw, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(bx + 2 * (bw + 4), y, bw, 20).build());
		setInitialFocus(search);
		updateResults(query);
	}

	private record Match(Item item, int score, int length) {}

	private void updateResults(String text) {
		query = text;
		String key = ItemResolver.normalize(text);
		if (key.isEmpty()) {
			results = List.of();
			return;
		}
		String[] words = text.toLowerCase(Locale.ROOT).strip().split("\\s+");
		List<Match> matches = new ArrayList<>();
		for (Item item : BuiltInRegistries.ITEM) {
			if (item == Items.AIR) {
				continue;
			}
			String displayName = item.getName(new ItemStack(item)).getString();
			String name = ItemResolver.normalize(displayName);
			String id = ItemResolver.normalize(BuiltInRegistries.ITEM.getKey(item).getPath());
			int score;
			if (name.equals(key) || id.equals(key)) {
				score = 0;
			} else if (name.startsWith(key) || id.startsWith(key)) {
				score = 2;
			} else if (name.contains(key) || id.contains(key)) {
				score = 3;
			} else if (containsAll(displayName.toLowerCase(Locale.ROOT), words)) {
				score = 4;
			} else {
				continue;
			}
			matches.add(new Match(item, score, displayName.length()));
		}
		// Typos ("oak lgo") still find the closest item.
		Item fuzzy = ItemResolver.resolve(text);
		if (fuzzy != null && matches.stream().noneMatch(m -> m.item() == fuzzy)) {
			matches.add(new Match(fuzzy, 1, 0));
		}
		matches.sort(Comparator.comparingInt(Match::score).thenComparingInt(Match::length));
		results = matches.stream().limit(maxResults()).map(Match::item).toList();
	}

	private static boolean containsAll(String haystack, String[] words) {
		for (String word : words) {
			if (!haystack.contains(word)) {
				return false;
			}
		}
		return true;
	}

	private static String displayName(String id) {
		Item item = Layout.resolve(id);
		return item == null ? id : item.getName(new ItemStack(item)).getString();
	}

	/** Picks an item. The amount follows it until the player types one in, since "2 sb" depends on how it stacks. */
	private void select(Item item) {
		selected = item;
		error = null;
		if (parsed != null && !amountEdited) {
			amountText = suggestedAmount();
			amount.setValue(amountText);
		}
	}

	private void save() {
		int count;
		try {
			count = Integer.parseInt(amount.getValue().strip());
		} catch (NumberFormatException e) {
			error = "The amount must be a whole number.";
			return;
		}
		if (count <= 0) {
			error = "The amount must be at least 1.";
			return;
		}
		if (line != null) {
			addFixed(count);
			return;
		}
		Project.MaterialEntry entry = entry();
		String before = displayName(entry.item);
		String newId = selected == null ? entry.item : BuiltInRegistries.ITEM.getKey(selected).toString();
		boolean replaced = !newId.equals(entry.item);
		ProjectStore.project().replaceMaterial(index, newId, count);
		ProjectStore.changed();
		parent.setStatus(replaced
			? "Replaced " + before + " with " + count + " " + displayName(newId) + "."
			: "Changed " + before + " to " + count + ".", GREEN);
		this.minecraft.gui.setScreen(parent);
	}

	/** Turns the unrecognized line into a material, the way an import would have, and takes the line off the list. */
	private void addFixed(int count) {
		if (selected == null) {
			error = "Pick the item this line meant.";
			return;
		}
		Project project = ProjectStore.project();
		// A swap made earlier in this list applies here too, just as it would on import.
		String id = project.replacementFor(BuiltInRegistries.ITEM.getKey(selected).toString());
		Project.MaterialEntry existing = project.materials.stream().filter(m -> m.item.equals(id)).findFirst().orElse(null);
		if (existing == null) {
			project.materials.add(new Project.MaterialEntry(id, count));
		} else {
			existing.count = MaterialParser.addClamped(existing.count, count);
			existing.crossedOff = false;
		}
		ProjectStore.changed();
		parent.resolveLine(line);
		parent.setStatus("Added " + count + " " + displayName(id) + " from \"" + line + "\".", GREEN);
		this.minecraft.gui.setScreen(parent);
	}

	private void remove() {
		if (line != null) {
			parent.resolveLine(line);
			parent.setStatus("Discarded \"" + line + "\".", GRAY);
			this.minecraft.gui.setScreen(parent);
			return;
		}
		String name = displayName(entry().item);
		ProjectStore.project().materials.remove(index);
		ProjectStore.changed();
		parent.setStatus("Removed " + name + " from the list.", GRAY);
		this.minecraft.gui.setScreen(parent);
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(parent);
	}

	private int resultAt(double x, double y) {
		if (x < left || x >= left + WIDTH || y < RESULTS_TOP) {
			return -1;
		}
		int row = (int) (y - RESULTS_TOP) / ROW;
		return row < results.size() ? row : -1;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		int row = resultAt(event.x(), event.y());
		if (row >= 0) {
			select(results.get(row));
			return true;
		}
		return false;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		// Enter in the search box picks the top match.
		boolean enter = event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER;
		if (enter && search.isFocused() && !results.isEmpty()) {
			select(results.getFirst());
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(this.font, this.title, this.width / 2, 8, WHITE);

		// Current material (or the line that wasn't recognized), and what it becomes.
		Item current = line == null ? Layout.resolve(entry().item) : Items.BARRIER;
		int x = left;
		int y = 18;
		if (current != null) {
			graphics.item(new ItemStack(current), x, y);
			x += 20;
		}
		String currentText = line == null ? displayName(entry().item) + " x" + entry().count : line;
		graphics.text(this.font, currentText, x, y + 4, line == null ? WHITE : RED, true);
		if (selected != null && (selected != current || line != null)) {
			x += this.font.width(currentText) + 6;
			graphics.text(this.font, "->", x, y + 4, GRAY, true);
			x += this.font.width("->") + 6;
			graphics.item(new ItemStack(selected), x, y);
			graphics.text(this.font, selected.getName(new ItemStack(selected)), x + 20, y + 4, GREEN, true);
		}

		int hovered = resultAt(mouseX, mouseY);
		for (int i = 0; i < results.size(); i++) {
			Item item = results.get(i);
			int ry = RESULTS_TOP + i * ROW;
			if (item == selected) {
				graphics.fill(left, ry, left + WIDTH, ry + ROW, 0x5055FF55);
			} else if (i == hovered) {
				graphics.fill(left, ry, left + WIDTH, ry + ROW, 0x30FFFFFF);
			}
			graphics.item(new ItemStack(item), left + 2, ry + 1);
			Component name = item.getName(new ItemStack(item));
			graphics.text(this.font, name, left + 22, ry + 5, WHITE, true);
			// The id is only a hint, so it's left out when a long name needs the room.
			String id = BuiltInRegistries.ITEM.getKey(item).toString();
			int idX = left + WIDTH - 4 - this.font.width(id);
			if (idX > left + 22 + this.font.width(name) + 8) {
				graphics.text(this.font, id, idX, ry + 5, GRAY, true);
			}
		}
		if (query.isBlank()) {
			graphics.text(this.font, line == null
				? "Pick an item to swap in. The amount stays the same."
				: "Type what this line meant, then pick the item.", left, RESULTS_TOP + 5, GRAY, true);
		} else if (results.isEmpty()) {
			graphics.text(this.font, "No items match \"" + query.strip() + "\"", left, RESULTS_TOP + 5, GRAY, true);
		}

		graphics.text(this.font, "Amount", left, amountY() + 6, WHITE, true);
		if (error != null) {
			graphics.centeredText(this.font, error, this.width / 2, amountY() + 26, RED);
		}
	}
}
