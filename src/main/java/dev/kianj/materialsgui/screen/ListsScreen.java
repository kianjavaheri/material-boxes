package dev.kianj.materialsgui.screen;

import dev.kianj.materialsgui.data.Project;
import dev.kianj.materialsgui.data.ProjectStore;
import dev.kianj.materialsgui.data.SavedLists;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** Name and save the current material list, and load, rename or delete saved lists. Saved lists work in every world. */
public class ListsScreen extends Screen {
	private static final int PAD = 10;
	/** Everything sits in a centered column, like the Materials List. */
	private static final int MAX_WIDTH = 400;
	private static final int ROW = 24;
	private static final int TOP = 70;
	private static final int ROW_BUTTON = 56;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GRAY = 0xFFA0A0A0;
	private static final int RED = 0xFFFF5555;
	private static final int YELLOW = 0xFFFFD040;
	private static final int GREEN = 0xFF55FF55;
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault());

	private final Screen parent;
	private @Nullable String nameText;
	private SavedLists.@Nullable SavedList selected;
	/** The action waiting for a second click to confirm, e.g. "delete:Castle walls". */
	private @Nullable String confirm;
	private String status = "";
	private int statusColor = GRAY;
	private int scroll;
	private int left;
	private int right;

	public ListsScreen(Screen parent) {
		super(Component.literal("Saved Lists"));
		this.parent = parent;
	}

	/** Most recently saved first. */
	private static List<SavedLists.SavedList> lists() {
		List<SavedLists.SavedList> sorted = new ArrayList<>(SavedLists.all());
		sorted.sort(Comparator.comparingLong((SavedLists.SavedList l) -> l.savedAt).reversed());
		return sorted;
	}

	private int visibleRows() {
		return Math.max(1, (this.height - 52 - TOP) / ROW);
	}

	/** Right edge of a row's text, left of its Load and Delete buttons. */
	private int rowRight() {
		return right - 2 * ROW_BUTTON - 8;
	}

	@Override
	protected void init() {
		int columnWidth = Math.min(this.width - 2 * PAD, MAX_WIDTH);
		left = (this.width - columnWidth) / 2;
		right = left + columnWidth;
		Project project = ProjectStore.project();
		if (nameText == null) {
			nameText = project.listName == null ? "" : project.listName;
		}
		int bw = 64;
		EditBox name = new EditBox(this.font, left, 40, columnWidth - 2 * (bw + 4), 20, Component.literal("List name"));
		name.setMaxLength(64);
		name.setHint(Component.literal("Name this list, e.g. Castle walls"));
		name.setValue(nameText);
		name.setResponder(v -> {
			nameText = v;
			confirm = null;
		});
		addRenderableWidget(name);
		int bx = right - 2 * bw - 4;
		addRenderableWidget(Button.builder(Component.literal("save".equals(confirm) ? "Overwrite?" : "Save"), b -> save()).bounds(bx, 40, bw, 20).build());
		Button rename = Button.builder(Component.literal("Rename"), b -> rename()).bounds(bx + bw + 4, 40, bw, 20).build();
		rename.active = selected != null;
		addRenderableWidget(rename);

		List<SavedLists.SavedList> lists = lists();
		scroll = Math.max(0, Math.min(scroll, lists.size() - visibleRows()));
		for (int r = 0; r < visibleRows() && scroll + r < lists.size(); r++) {
			SavedLists.SavedList list = lists.get(scroll + r);
			int y = TOP + r * ROW;
			addRenderableWidget(Button.builder(Component.literal(("load:" + list.name).equals(confirm) ? "Sure?" : "Load"), b -> load(list))
				.bounds(right - 2 * ROW_BUTTON - 4, y, ROW_BUTTON, 20).build());
			addRenderableWidget(Button.builder(Component.literal(("delete:" + list.name).equals(confirm) ? "Sure?" : "Delete"), b -> delete(list))
				.bounds(right - ROW_BUTTON, y, ROW_BUTTON, 20).build());
		}

		int by = this.height - 28;
		int half = (columnWidth - 4) / 2;
		addRenderableWidget(Button.builder(Component.literal("new".equals(confirm) ? "Click again to start over" : "New List"), b -> newList())
			.bounds(left, by, half, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(left + half + 4, by, half, 20).build());
		setInitialFocus(name);
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(parent);
	}

	private void setStatus(String text, int color) {
		status = text;
		statusColor = color;
	}

	/** Returns true on the second click of an action; the first click only shows the warning. */
	private boolean confirmed(String action, String warning) {
		if (action.equals(confirm)) {
			confirm = null;
			return true;
		}
		confirm = action;
		setStatus(warning, YELLOW);
		rebuildWidgets();
		return false;
	}

	/** The current list has materials that aren't in a saved copy, or boxes no saved list has. */
	private static boolean hasUnsavedWork(Project project) {
		return (!project.materials.isEmpty() && !SavedLists.isSaved(project)) || SavedLists.hasUnsavedBoxes(project, ProjectStore.worldKey());
	}

	private String typedName() {
		return nameText == null ? "" : nameText.strip();
	}

	private void save() {
		Project project = ProjectStore.project();
		String name = typedName();
		if (name.isEmpty()) {
			setStatus("Type a name for this list first.", RED);
			return;
		}
		if (project.materials.isEmpty()) {
			setStatus("The current list is empty, so there's nothing to save.", RED);
			return;
		}
		SavedLists.SavedList existing = SavedLists.find(name);
		if (existing != null && !name.equalsIgnoreCase(project.listName)
			&& !confirmed("save", "There's already a list called \"" + existing.name + "\". Click Overwrite? to replace it.")) {
			return;
		}
		SavedLists.save(name, project, ProjectStore.worldKey());
		ProjectStore.changed();
		selected = SavedLists.find(name);
		confirm = null;
		setStatus("Saved \"" + name + "\".", GREEN);
		rebuildWidgets();
	}

	private void rename() {
		if (selected == null) {
			return;
		}
		Project project = ProjectStore.project();
		String name = typedName();
		if (name.isEmpty()) {
			setStatus("Type the new name first.", RED);
			return;
		}
		SavedLists.SavedList other = SavedLists.find(name);
		if (other != null && other != selected) {
			setStatus("Another list is already called \"" + other.name + "\".", RED);
			return;
		}
		String old = selected.name;
		boolean current = old.equalsIgnoreCase(project.listName);
		SavedLists.rename(selected, name);
		if (current) {
			project.listName = name;
			ProjectStore.changed();
		}
		setStatus("Renamed \"" + old + "\" to \"" + name + "\".", GREEN);
		rebuildWidgets();
	}

	private void load(SavedLists.SavedList list) {
		Project project = ProjectStore.project();
		if (hasUnsavedWork(project)
			&& !confirmed("load:" + list.name, "Your current list has unsaved changes. Click Sure? to replace it with \"" + list.name + "\".")) {
			return;
		}
		boolean ownBoxes = SavedLists.load(list);
		MaterialsScreen.clearUnresolved();
		nameText = list.name;
		selected = list;
		confirm = null;
		int boxes = project.boxes.size();
		setStatus(ownBoxes
			? "Loaded \"" + list.name + "\" with its " + boxes + " Material Box" + (boxes == 1 ? "" : "es") + "."
			: "Loaded \"" + list.name + "\". It had no Material Boxes here yet, so it uses your current ones.", GREEN);
		rebuildWidgets();
	}

	private void delete(SavedLists.SavedList list) {
		if (!confirmed("delete:" + list.name, "Click Sure? to delete \"" + list.name + "\". Your current list stays as it is.")) {
			return;
		}
		SavedLists.delete(list);
		Project project = ProjectStore.project();
		if (list.name.equalsIgnoreCase(project.listName)) {
			project.listName = null;
			ProjectStore.changed();
		}
		if (selected == list) {
			selected = null;
		}
		setStatus("Deleted \"" + list.name + "\".", GRAY);
		rebuildWidgets();
	}

	private void newList() {
		Project project = ProjectStore.project();
		// Material Boxes carry over to the new list, so only unsaved materials are at stake.
		if (!project.materials.isEmpty() && !SavedLists.isSaved(project)
			&& !confirmed("new", "Your current list has unsaved changes. Click again to start over anyway.")) {
			return;
		}
		project.materials.clear();
		project.replacements.clear();
		project.listName = null;
		MaterialsScreen.clearUnresolved();
		ProjectStore.changed();
		nameText = "";
		selected = null;
		confirm = null;
		setStatus("Started a new list. Your Material Boxes carry over.", GRAY);
		rebuildWidgets();
	}

	private int rowAt(double x, double y) {
		if (y < TOP || x < left || x >= rowRight()) {
			return -1;
		}
		int r = (int) (y - TOP) / ROW;
		return r < visibleRows() && scroll + r < SavedLists.all().size() ? scroll + r : -1;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		// Clicking a list selects it and puts its name in the box, ready to rename.
		int row = rowAt(event.x(), event.y());
		if (row >= 0) {
			selected = lists().get(row);
			nameText = selected.name;
			confirm = null;
			rebuildWidgets();
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		int max = Math.max(0, SavedLists.all().size() - visibleRows());
		int next = Math.max(0, Math.min(max, scroll - (int) Math.signum(scrollY)));
		if (next != scroll) {
			scroll = next;
			rebuildWidgets();
		}
		return true;
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
		Project project = ProjectStore.project();
		String current = project.listName == null
			? "Current list: not saved yet"
			: "Current list: " + project.listName + (SavedLists.isSaved(project) ? "" : " (unsaved changes)");
		graphics.centeredText(this.font, fit(current, right - left), this.width / 2, 22, GRAY);

		List<SavedLists.SavedList> lists = lists();
		if (lists.isEmpty()) {
			graphics.centeredText(this.font, "No saved lists yet. Name the current list above and press Save.", this.width / 2, TOP + 6, GRAY);
		}
		int rowRight = rowRight();
		for (int r = 0; r < visibleRows() && scroll + r < lists.size(); r++) {
			SavedLists.SavedList list = lists.get(scroll + r);
			int y = TOP + r * ROW;
			if (list == selected) {
				graphics.fill(left - 3, y - 2, rowRight + 2, y + ROW - 2, 0x30FFFFFF);
			}
			String date = DATE.format(Instant.ofEpochMilli(list.savedAt));
			graphics.text(this.font, date, rowRight - this.font.width(date), y + 1, GRAY, true);
			boolean isCurrent = list.name.equalsIgnoreCase(project.listName);
			String suffix = isCurrent ? "  (current)" : "";
			String name = fit(list.name, rowRight - left - this.font.width(date) - this.font.width(suffix) - 8) + suffix;
			graphics.text(this.font, name, left, y + 1, isCurrent ? GREEN : WHITE, true);
			List<Project.BoxEntry> boxesHere = list.boxes.get(ProjectStore.worldKey());
			String info = list.materials.size() + " materials"
				+ (boxesHere == null ? "" : ", " + boxesHere.size() + (boxesHere.size() == 1 ? " box" : " boxes") + " here")
				+ (list.replacements.isEmpty() ? "" : ", " + list.replacements.size() + " replaced");
			graphics.text(this.font, fit(info, rowRight - left), left, y + 11, GRAY, true);
		}
		if (!status.isEmpty()) {
			graphics.centeredText(this.font, status, this.width / 2, this.height - 42, statusColor);
		}
	}
}
