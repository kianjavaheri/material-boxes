package dev.kianj.materialsgui.screen;

import com.mojang.blaze3d.platform.InputConstants;
import dev.kianj.materialsgui.data.Layout;
import dev.kianj.materialsgui.data.ModConfig;
import dev.kianj.materialsgui.data.Project;
import dev.kianj.materialsgui.data.ProjectStore;
import dev.kianj.materialsgui.data.SavedLists;
import dev.kianj.materialsgui.importer.ClaudeImporter;
import dev.kianj.materialsgui.importer.MaterialParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * The current material list and its progress, with a collapsible import panel on the left for pasting a list, loading
 * a Litematica export, or dropping a screenshot. Saved lists and Material Boxes have their own screens.
 */
public class MaterialsScreen extends Screen {
	private static final int PAD = 10;
	private static final int ROW = 18;
	private static final int MAX_LIST_WIDTH = 360;
	private static final int HEADER_Y = 30;
	private static final int TOGGLE_WIDTH = 80;
	private static final int SORT_WIDTH = 74;
	/** Narrower than this, the open import panel covers the list instead of sitting beside it. */
	private static final int SIDE_BY_SIDE_WIDTH = 400;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GRAY = 0xFFA0A0A0;
	private static final int RED = 0xFFFF5555;
	private static final int YELLOW = 0xFFFFD040;
	private static final int GREEN = 0xFF55FF55;

	private enum Sort {
		LIST("Sort: List"), MISSING("Sort: Missing"), NAME("Sort: A-Z");

		final String label;

		Sort(String label) {
			this.label = label;
		}

		Sort next() {
			return values()[(ordinal() + 1) % values().length];
		}
	}

	/** Kept across openings so a half-edited paste isn't lost. */
	private static String draft = "";
	/** Lines from the last import that didn't match an item. Shown in the list until they're fixed or cleared. */
	private static List<String> unresolved = List.of();
	private static String search = "";
	private static Sort sort = Sort.LIST;

	private @Nullable Boolean showPanel;
	private @Nullable MultiLineEditBox input;
	private @Nullable Button clearButton;
	private @Nullable Button copyButton;
	private @Nullable Button keyButton;
	private @Nullable Button settingsButton;
	/** A right-clicked material waiting for its X to be clicked before it's removed. */
	private Project.@Nullable MaterialEntry pendingRemoval;
	private Component status = Component.empty();
	private int statusColor = GRAY;
	private int scroll;
	private boolean listVisible;
	private int listLeft;
	private int listRight;
	private int listTop;
	private int listBottom;
	private boolean confirmClear;

	public MaterialsScreen() {
		super(Component.literal("Materials List"));
	}

	@Override
	protected void init() {
		if (showPanel == null) {
			showPanel = ModConfig.get().importPanelOpen || ProjectStore.project().materials.isEmpty();
		}
		boolean sideBySide = this.width >= SIDE_BY_SIDE_WIDTH;
		int half = this.width / 2;
		listVisible = !showPanel || sideBySide;
		if (showPanel && sideBySide) {
			listLeft = half + 5;
			listRight = this.width - PAD;
		} else {
			int listWidth = Math.min(this.width - 2 * PAD, MAX_LIST_WIDTH);
			listLeft = (this.width - listWidth) / 2;
			listRight = listLeft + listWidth;
		}
		listTop = HEADER_Y + 62;
		listBottom = this.height - 30;

		// Open: above the panel. Closed: at the start of the list's header row.
		int toggleX = showPanel ? PAD : listLeft;
		addRenderableWidget(Button.builder(Component.literal(showPanel ? "Hide Import" : "Show Import"), b -> togglePanel())
			.bounds(toggleX, HEADER_Y, TOGGLE_WIDTH, 20).build());

		clearButton = null;
		copyButton = null;
		settingsButton = null;
		if (listVisible) {
			clearButton = Button.builder(Component.literal(confirmClear ? "Sure?" : "Clear"), b -> clear())
				.bounds(listRight - 50, HEADER_Y, 50, 20)
				.tooltip(Tooltip.create(Component.literal("Empty the current list. Saved lists aren't affected.")))
				.build();
			addRenderableWidget(clearButton);
			copyButton = Button.builder(Component.literal("Copy"), b -> copyMissing())
				.bounds(listRight - 104, HEADER_Y, 50, 20)
				.tooltip(Tooltip.create(Component.literal("Copy what's still missing to the clipboard")))
				.build();
			addRenderableWidget(copyButton);

			EditBox searchBox = new EditBox(this.font, listLeft, HEADER_Y + 25, listRight - listLeft - SORT_WIDTH - 4, 18, Component.literal("Search"));
			searchBox.setMaxLength(64);
			searchBox.setHint(Component.literal("Search materials"));
			searchBox.setValue(search);
			searchBox.setResponder(v -> {
				search = v;
				scroll = 0;
			});
			addRenderableWidget(searchBox);
			addRenderableWidget(Button.builder(Component.literal(sort.label), b -> {
				sort = sort.next();
				b.setMessage(Component.literal(sort.label));
			}).bounds(listRight - SORT_WIDTH, HEADER_Y + 24, SORT_WIDTH, 20).build());

			// Three buttons, then a small settings button (drawn with a comparator icon) at the end of the row.
			int bw = (listRight - listLeft - 32) / 3;
			int by = this.height - 26;
			addRenderableWidget(Button.builder(Component.literal("Lists..."), b -> this.minecraft.gui.setScreen(new ListsScreen(this)))
				.bounds(listLeft, by, bw, 20).build());
			addRenderableWidget(Button.builder(Component.literal("Boxes..."), b -> this.minecraft.gui.setScreen(new BoxesScreen(this)))
				.bounds(listLeft + bw + 4, by, bw, 20).build());
			addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(listLeft + 2 * (bw + 4), by, bw, 20).build());
			settingsButton = Button.builder(Component.empty(), b -> this.minecraft.gui.setScreen(new SettingsScreen(this)))
				.bounds(listRight - 20, by, 20, 20)
				.tooltip(Tooltip.create(Component.literal("Settings: the HUD, box refreshing, and the API key")))
				.build();
			addRenderableWidget(settingsButton);
		}

		input = null;
		keyButton = null;
		if (showPanel) {
			int panelWidth = (sideBySide ? half - 5 : this.width - PAD) - PAD;
			int top = HEADER_Y + 24;
			// Its buttons line up with the list's Lists... / Boxes... / Done row.
			int bottom = this.height - 30;
			input = MultiLineEditBox.builder()
				.setX(PAD)
				.setY(top)
				.setPlaceholder(Component.literal("64 stone\n3 stacks oak planks\nGlass: 128\n2 sb stone bricks\n\n...or drop a Litematica .txt/.csv or a screenshot here"))
				.build(this.font, panelWidth, bottom - top, Component.literal("Material list"));
			input.setCharacterLimit(200_000);
			input.setValue(draft, true);
			input.setValueListener(v -> draft = v);
			addRenderableWidget(input);

			int bw = (panelWidth - 8) / 3;
			addRenderableWidget(Button.builder(Component.literal("Replace"), b -> applyImport(true)).bounds(PAD, bottom + 4, bw, 20)
				.tooltip(Tooltip.create(Component.literal("Replace the whole list with these materials"))).build());
			addRenderableWidget(Button.builder(Component.literal("Add to List"), b -> applyImport(false)).bounds(PAD + bw + 4, bottom + 4, bw, 20)
				.tooltip(Tooltip.create(Component.literal("Add these materials to the list"))).build());
			addRenderableWidget(Button.builder(Component.literal("Litematica"), b -> loadLitematica()).bounds(PAD + 2 * (bw + 4), bottom + 4, bw, 20).build());

			// The API key is only needed for screenshot import, so it's a small key button (opening the settings) in the
			// panel's corner rather than a field.
			boolean hasKey = !ModConfig.get().anthropicApiKey.isBlank();
			keyButton = Button.builder(Component.empty(), b -> this.minecraft.gui.setScreen(new SettingsScreen(this)))
				.bounds(PAD + panelWidth - 20, HEADER_Y, 20, 20)
				.tooltip(Tooltip.create(Component.literal(hasKey
					? "Anthropic API key: set. It's only used to read screenshots."
					: "Add an Anthropic API key to import screenshots")))
				.build();
			addRenderableWidget(keyButton);
			setInitialFocus(input);
		}
	}

	@Override
	public void removed() {
		ModConfig.get().save();
	}

	static void clearUnresolved() {
		unresolved = List.of();
	}

	void setStatus(String text, int color) {
		status = Component.literal(text);
		statusColor = color;
	}

	/** Shows or hides the import panel, and remembers the choice. */
	private void togglePanel() {
		showPanel = !showPanel;
		ModConfig.get().importPanelOpen = showPanel;
		ModConfig.get().save();
		rebuildWidgets();
	}

	/** Empties the current list. Asks for a second click if it has changes that aren't in a saved list. */
	private void clear() {
		Project project = ProjectStore.project();
		boolean saved = SavedLists.isSaved(project);
		if (!saved && !confirmClear) {
			confirmClear = true;
			setStatus("This list isn't saved. Click Sure? to clear it anyway, or save it from Lists... first.", YELLOW);
			rebuildWidgets();
			return;
		}
		String name = project.listName;
		project.materials.clear();
		project.replacements.clear();
		project.listName = null;
		clearUnresolved();
		ProjectStore.changed();
		confirmClear = false;
		// Ready for the next import.
		showPanel = true;
		setStatus(saved ? "Cleared. \"" + name + "\" is still in your saved lists." : "Cleared the list.", GRAY);
		rebuildWidgets();
	}

	/** Copies what's still missing, one "count name" line per material, so it can be pasted back in as a list. */
	private void copyMissing() {
		String text = ProjectStore.layout().missingText(ProjectStore.project());
		if (text.isEmpty()) {
			setStatus("Nothing is missing, so there's nothing to copy.", GREEN);
			return;
		}
		this.minecraft.keyboardHandler.setClipboard(text);
		long lines = text.lines().count() - 1;
		setStatus("Copied " + lines + " missing material" + (lines == 1 ? "" : "s") + " to the clipboard.", GREEN);
	}

	private void applyImport(boolean replace) {
		MaterialParser.Result result = MaterialParser.parse(draft);
		if (result.materials().isEmpty() && result.unresolved().isEmpty()) {
			setStatus("Nothing to import. Lines need an amount and an item, like \"64 stone\".", RED);
			return;
		}
		Project project = ProjectStore.project();
		if (replace) {
			project.materials.clear();
		}
		int replaced = 0;
		for (Map.Entry<Item, Integer> e : result.materials().entrySet()) {
			String parsed = BuiltInRegistries.ITEM.getKey(e.getKey()).toString();
			// Swaps made earlier in this list (e.g. oak wood to oak logs) apply to fresh imports too.
			String id = project.replacementFor(parsed);
			if (!id.equals(parsed)) {
				replaced++;
			}
			Project.MaterialEntry existing = project.materials.stream().filter(m -> m.item.equals(id)).findFirst().orElse(null);
			if (existing != null) {
				existing.count = MaterialParser.addClamped(existing.count, e.getValue());
			} else {
				project.materials.add(new Project.MaterialEntry(id, e.getValue()));
			}
		}
		ProjectStore.changed();
		unresolved = result.unresolved();
		// Leave only the lines that need fixing in the editor.
		draft = String.join("\n", unresolved);
		if (input != null) {
			input.setValue(draft, true);
		}
		String imported = "Imported " + result.materials().size() + " materials."
			+ (replaced == 0 ? "" : " Applied " + replaced + " saved replacement" + (replaced == 1 ? "" : "s") + ".");
		if (unresolved.isEmpty()) {
			setStatus(imported, GREEN);
			if (!listVisible) {
				// On a narrow window the panel covers the list, so get out of the way to show the result.
				showPanel = false;
				rebuildWidgets();
			}
		} else {
			setStatus(imported + " Fix the " + unresolved.size() + " unrecognized line(s) and press Add to List.", YELLOW);
		}
	}

	private void loadLitematica() {
		Path gameDir = FabricLoader.getInstance().getGameDir();
		Optional<Path> newest = Stream.of(gameDir.resolve("config/litematica"), gameDir.resolve("litematica"))
			.filter(Files::isDirectory)
			.flatMap(dir -> {
				try (Stream<Path> files = Files.walk(dir, 3)) {
					return files.filter(MaterialsScreen::isMaterialListExport).toList().stream();
				} catch (IOException e) {
					return Stream.empty();
				}
			})
			.max(Comparator.comparingLong(p -> p.toFile().lastModified()));
		if (newest.isEmpty()) {
			setStatus("No Litematica export found. In Litematica's Material List, use Write to file, or drop the file here.", RED);
			return;
		}
		loadTextFile(newest.get());
	}

	private static boolean isMaterialListExport(Path p) {
		String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
		return name.contains("material") && (name.endsWith(".txt") || name.endsWith(".csv"));
	}

	private void setDraft(String text) {
		draft = text;
		if (input != null) {
			input.setValue(text, true);
		}
	}

	private void loadTextFile(Path file) {
		try {
			setDraft(Files.readString(file));
			setStatus("Loaded " + file.getFileName() + ". Press Replace to use it, or Add to List.", GREEN);
		} catch (IOException e) {
			setStatus("Couldn't read " + file.getFileName() + ": " + e.getMessage(), RED);
		}
	}

	/** Dropped files open the import panel if it's hidden. */
	@Override
	public void onFilesDrop(List<Path> files) {
		if (files.isEmpty()) {
			return;
		}
		if (!showPanel) {
			showPanel = true;
			rebuildWidgets();
		}
		Path file = files.getFirst();
		String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
		if (name.endsWith(".txt") || name.endsWith(".csv")) {
			loadTextFile(file);
		} else if (name.matches(".*\\.(png|jpe?g|gif|webp|bmp)$")) {
			importScreenshot(file);
		} else {
			setStatus("Drop a .txt/.csv material list or a PNG/JPG screenshot.", RED);
		}
	}

	private void importScreenshot(Path file) {
		ModConfig config = ModConfig.get();
		if (config.anthropicApiKey.isBlank()) {
			setStatus("Add your Anthropic API key first, with the key button above the import box.", RED);
			return;
		}
		config.save();
		setStatus("Reading " + file.getFileName() + " with Claude...", YELLOW);
		ClaudeImporter.extract(file, config.anthropicApiKey, config.model).whenComplete((text, error) -> this.minecraft.execute(() -> {
			if (error != null) {
				Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
				setStatus(cause.getMessage() == null ? "Screenshot import failed." : cause.getMessage(), RED);
			} else if (text.isBlank()) {
				setStatus("Claude didn't find a material list in that image.", RED);
			} else {
				setDraft(text);
				setStatus("Check the list, then press Replace or Add to List.", GREEN);
			}
		}));
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (listVisible && x >= listLeft && x < listRight && y >= listTop && y < listBottom) {
			scroll = Math.max(0, scroll - (int) Math.signum(scrollY) * ROW);
			return true;
		}
		return super.mouseScrolled(x, y, scrollX, scrollY);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		Project project = ProjectStore.project();
		List<Row> rows = rows(project, ProjectStore.layout());
		// A right-clicked row waits for its X to be clicked; any other click keeps the material.
		Project.MaterialEntry pending = pendingRemoval;
		pendingRemoval = null;
		if (pending != null && event.button() == 0 && onRemoveButton(rows, pending, event.x(), event.y())) {
			project.materials.remove(pending);
			ProjectStore.changed();
			setStatus("Removed " + displayName(pending) + " from the list.", GRAY);
			return true;
		}
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		// Click a row to replace or edit that material; right-click to ask to remove it.
		int position = rowAt(event.x(), event.y());
		if (position < 0 || position >= rows.size() || rows.get(position).index < 0) {
			return false;
		}
		if (pending != null) {
			// This click only cancelled the removal.
			return true;
		}
		int index = rows.get(position).index;
		if (event.button() == 0) {
			this.minecraft.gui.setScreen(new EditMaterialScreen(this, index));
			return true;
		}
		if (event.button() == 1) {
			pendingRemoval = project.materials.get(index);
			return true;
		}
		return false;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (pendingRemoval != null && event.key() == InputConstants.KEY_ESCAPE) {
			pendingRemoval = null;
			return true;
		}
		return super.keyPressed(event);
	}

	private static String displayName(Project.MaterialEntry entry) {
		Item item = Layout.resolve(entry.item);
		return item == null ? entry.item : new ItemStack(item).getHoverName().getString();
	}

	/** Position of a material's row in the (filtered, sorted) list, or -1. */
	private static int positionOf(List<Row> rows, Project.MaterialEntry entry) {
		List<Project.MaterialEntry> materials = ProjectStore.project().materials;
		for (int i = 0; i < rows.size(); i++) {
			if (rows.get(i).index >= 0 && materials.get(rows.get(i).index) == entry) {
				return i;
			}
		}
		return -1;
	}

	/** Whether (x, y) is on the X at the end of the row waiting to be removed. */
	private boolean onRemoveButton(List<Row> rows, Project.MaterialEntry entry, double x, double y) {
		int position = positionOf(rows, entry);
		int rowY = listTop - scroll + position * ROW;
		return position >= 0 && x >= listRight - 16 && x < listRight && y >= Math.max(rowY, listTop) && y < Math.min(rowY + ROW - 2, listBottom);
	}

	/** Position of the list row under the cursor (in the filtered, sorted list), or -1. */
	private int rowAt(double x, double y) {
		if (!listVisible || x < listLeft || x >= listRight || y < listTop || y >= listBottom) {
			return -1;
		}
		return (int) (y - listTop + scroll) / ROW;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		Project project = ProjectStore.project();
		if (clearButton != null) {
			clearButton.active = !project.materials.isEmpty() || !unresolved.isEmpty();
		}
		if (copyButton != null) {
			copyButton.active = !project.materials.isEmpty();
		}
		super.extractRenderState(graphics, mouseX, mouseY, a);
		if (keyButton != null) {
			graphics.item(new ItemStack(Items.TRIAL_KEY), keyButton.getX() + 2, keyButton.getY() + 2);
		}
		if (settingsButton != null) {
			graphics.item(new ItemStack(Items.COMPARATOR), settingsButton.getX() + 2, settingsButton.getY() + 2);
		}

		String title = project.listName == null
			? "Materials List"
			: "Materials List: " + project.listName + (SavedLists.isSaved(project) ? "" : " (unsaved changes)");
		graphics.centeredText(this.font, title, this.width / 2, 8, WHITE);
		graphics.centeredText(this.font, status, this.width / 2, 19, statusColor);
		if (!listVisible) {
			return;
		}

		Layout layout = ProjectStore.layout();
		List<Row> rows = rows(project, layout);
		long shown = rows.stream().filter(r -> r.index >= 0).count();
		int headerX = showPanel ? listLeft : listLeft + TOGGLE_WIDTH + 6;
		String count = search.isBlank() ? "Materials (" + project.materials.size() + ")" : shown + " of " + project.materials.size();
		graphics.text(this.font, count, headerX, HEADER_Y + 6, WHITE, true);
		int infoY = HEADER_Y + 49;
		String boxes = project.boxes.size() + (project.boxes.size() == 1 ? " Material Box" : " Material Boxes");
		graphics.text(this.font, boxes, listRight - this.font.width(boxes), infoY, GRAY, true);
		if (!project.boxes.isEmpty() && !layout.unplaced().isEmpty()) {
			graphics.text(this.font, "Add Material Boxes: " + layout.missingSlots() + " more slots needed", listLeft, infoY, RED, true);
		} else if (project.boxes.isEmpty() && !project.materials.isEmpty()) {
			graphics.text(this.font, "Open a chest and click \"+ Material Box\"", listLeft, infoY, YELLOW, true);
		}

		int center = (listLeft + listRight) / 2;
		if (project.materials.isEmpty() && unresolved.isEmpty()) {
			graphics.centeredText(this.font, "This list is empty.", center, listTop + 20, WHITE);
			graphics.centeredText(this.font, showPanel ? "Paste a list on the left," : "Use Show Import to paste a list,", center, listTop + 36, GRAY);
			graphics.centeredText(this.font, "or open a saved one from Lists...", center, listTop + 48, GRAY);
			return;
		}
		if (rows.isEmpty()) {
			graphics.centeredText(this.font, "No materials match \"" + search + "\".", center, listTop + 20, GRAY);
			return;
		}
		int maxScroll = Math.max(0, rows.size() * ROW - (listBottom - listTop));
		scroll = Math.min(scroll, maxScroll);
		int hovered = rowAt(mouseX, mouseY);
		boolean hoveringMaterial = hovered >= 0 && hovered < rows.size() && rows.get(hovered).index >= 0;
		graphics.enableScissor(listLeft, listTop, listRight, listBottom);
		int y = listTop - scroll;
		boolean hoveringRemoval = false;
		for (int i = 0; i < rows.size(); i++) {
			Row row = rows.get(i);
			boolean removing = pendingRemoval != null && row.index >= 0 && project.materials.get(row.index) == pendingRemoval;
			if (y + ROW >= listTop && y < listBottom) {
				if (removing) {
					graphics.fill(listLeft - 2, y - 1, listRight, y + ROW - 1, 0x50FF3030);
					hoveringRemoval |= i == hovered;
				} else if (i == hovered && hoveringMaterial) {
					graphics.fill(listLeft - 2, y - 1, listRight, y + ROW - 1, 0x30FFFFFF);
				}
				if (row.item != null) {
					graphics.item(new ItemStack(row.item), listLeft, y);
				}
				String ask = "Remove?";
				int amountWidth = removing ? this.font.width(ask) + 22 : row.amount == null ? 0 : this.font.width(row.amount) + 6;
				Component name = row.name;
				if (this.font.width(name) > listRight - listLeft - 20 - amountWidth) {
					name = Component.literal(this.font.plainSubstrByWidth(name.getString(), listRight - listLeft - 28 - amountWidth) + "...");
				}
				graphics.text(this.font, name, listLeft + 20, y + 4, row.nameColor, true);
				if (removing) {
					// The X that confirms the removal.
					int bx = listRight - 14;
					boolean hot = mouseX >= bx - 2 && mouseX < listRight && mouseY >= y && mouseY < y + ROW - 2;
					graphics.fill(bx - 1, y, bx + 14, y + 15, 0xFF000000);
					graphics.fill(bx, y + 1, bx + 13, y + 14, hot ? 0xFFFF6060 : 0xFFC02828);
					graphics.centeredText(this.font, "X", bx + 7, y + 4, WHITE);
					graphics.text(this.font, ask, bx - 6 - this.font.width(ask), y + 4, 0xFFFFB0B0, true);
				} else if (row.amount != null) {
					graphics.text(this.font, row.amount, listRight - this.font.width(row.amount), y + 4, row.amountColor, true);
				}
			}
			y += ROW;
		}
		graphics.disableScissor();
		if (hoveringRemoval) {
			graphics.setTooltipForNextFrame(this.font, Component.literal("Click the X to remove it. Click anywhere else to keep it."), mouseX, mouseY);
		} else if (hoveringMaterial) {
			graphics.setTooltipForNextFrame(this.font, Component.literal("Click to replace it or change the amount. Right-click to remove."), mouseX, mouseY);
		}
	}

	/** A list row; {@code index} is the material's position in the project, or -1 for an unrecognized line. */
	private record Row(int index, @Nullable Item item, Component name, int nameColor, @Nullable String amount, int amountColor, long missing) {}

	/** The material rows, filtered by the search box and sorted by the chosen order, then any unrecognized lines. */
	private List<Row> rows(Project project, Layout layout) {
		List<Row> rows = new ArrayList<>();
		String query = search.strip().toLowerCase(Locale.ROOT);
		for (int i = 0; i < project.materials.size(); i++) {
			Project.MaterialEntry m = project.materials.get(i);
			Item item = Layout.resolve(m.item);
			if (item == null) {
				if (query.isEmpty() || m.item.toLowerCase(Locale.ROOT).contains(query)) {
					rows.add(new Row(i, null, Component.literal(m.item + " (unknown item)"), RED, m.count + "", RED, m.count));
				}
				continue;
			}
			Component displayName = new ItemStack(item).getHoverName();
			if (!query.isEmpty() && !displayName.getString().toLowerCase(Locale.ROOT).contains(query) && !m.item.contains(query)) {
				continue;
			}
			int stored = layout.stored(item);
			boolean done = stored >= m.count;
			// Materials that are fully stored in Material Boxes get crossed off.
			Component name = done ? displayName.copy().withStyle(ChatFormatting.STRIKETHROUGH) : displayName;
			int color = done ? GREEN : stored > 0 ? YELLOW : RED;
			rows.add(new Row(i, item, name, done ? GRAY : WHITE, stored + " / " + m.count, color, (long) m.count - stored));
		}
		switch (sort) {
			case MISSING -> rows.sort(Comparator.comparingLong((Row r) -> r.missing).reversed());
			case NAME -> rows.sort(Comparator.comparing((Row r) -> r.name.getString().toLowerCase(Locale.ROOT)));
			case LIST -> { }
		}
		if (query.isEmpty()) {
			for (String line : unresolved) {
				rows.add(new Row(-1, null, Component.literal("? " + line), RED, null, RED, 0));
			}
		}
		return rows;
	}
}
