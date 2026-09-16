package dev.kianj.materialsgui.screen;

import dev.kianj.materialsgui.data.Project;
import dev.kianj.materialsgui.data.ProjectStore;
import dev.kianj.materialsgui.data.SavedLists;
import dev.kianj.materialsgui.importer.ListShare;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * Hand a material list to someone else: as text, as a share code, or as a .txt file they can drop straight back onto
 * the Materials List. Opened from the Materials List for the current list, and from Saved Lists for a saved one.
 */
public class ShareScreen extends Screen {
	private static final int PAD = 10;
	private static final int MAX_WIDTH = 360;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GRAY = 0xFFA0A0A0;
	private static final int RED = 0xFFFF5555;
	private static final int GREEN = 0xFF55FF55;
	private static final Component HINT =
		Component.literal("Anyone with the mod can paste either one into their import box, or drop the saved file onto their Materials List.");

	private final Screen parent;
	private final @Nullable String name;
	private final List<Project.MaterialEntry> materials;
	private final Map<String, String> replacements;
	/** The current list can also copy just what its Material Boxes are still missing. */
	private final boolean current;
	private String status = "";
	private int statusColor = GRAY;
	private int left;
	private int right;

	private ShareScreen(Screen parent, @Nullable String name, List<Project.MaterialEntry> materials, Map<String, String> replacements, boolean current) {
		super(Component.literal("Share List"));
		this.parent = parent;
		this.name = name;
		this.materials = materials;
		this.replacements = replacements;
		this.current = current;
	}

	public static ShareScreen of(Screen parent, Project project) {
		return new ShareScreen(parent, project.listName, project.materials, project.replacements, true);
	}

	public static ShareScreen of(Screen parent, SavedLists.SavedList list) {
		return new ShareScreen(parent, list.name, list.materials, list.replacements, false);
	}

	@Override
	protected void init() {
		int width = Math.min(this.width - 2 * PAD, MAX_WIDTH);
		left = (this.width - width) / 2;
		right = left + width;
		int half = (width - 4) / 2;

		addRenderableWidget(Button.builder(Component.literal("Copy Share Code"), b -> copy(ListShare.encode(name, materials, replacements), "share code"))
			.bounds(left, 40, width, 20)
			.tooltip(Tooltip.create(Component.literal("One line to paste anywhere. It carries the list's name, its crossed-off marks and its item swaps, "
				+ "and it can't be garbled the way a long paste can.")))
			.build());
		addRenderableWidget(Button.builder(Component.literal("Copy as Text"), b -> copy(ListShare.text(name, materials, replacements), "list"))
			.bounds(left, 78, half, 20)
			.tooltip(Tooltip.create(Component.literal("The plain \"count item\" list, readable by anyone. The share code rides along as a comment.")))
			.build());
		Button missing = Button.builder(Component.literal("Copy What's Missing"), b -> copyMissing())
			.bounds(left + half + 4, 78, half, 20)
			.tooltip(Tooltip.create(Component.literal("Only what your Material Boxes are still short of, as a shopping list")))
			.build();
		missing.active = current;
		addRenderableWidget(missing);

		addRenderableWidget(Button.builder(Component.literal("Save .txt File"), b -> saveFile())
			.bounds(left, 102, half, 20)
			.tooltip(Tooltip.create(Component.literal("Write the list to config/materialsgui/exports, ready to attach to a message")))
			.build());
		addRenderableWidget(Button.builder(Component.literal("Open Folder"), b -> openFolder())
			.bounds(left + half + 4, 102, half, 20)
			.tooltip(Tooltip.create(Component.literal("Show config/materialsgui/exports in your file manager")))
			.build());

		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(left, this.height - 28, width, 20).build());
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(parent);
	}

	private void copy(String text, String what) {
		if (materials.isEmpty()) {
			setStatus("This list is empty, so there's nothing to share.", RED);
			return;
		}
		this.minecraft.keyboardHandler.setClipboard(text);
		setStatus("Copied the " + what + " to the clipboard.", GREEN);
	}

	/** Only for the current list: what its Material Boxes are still short of, rather than the whole list. */
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

	/** The folder is only made when something is written to it, and opening one that isn't there does nothing. */
	private void openFolder() {
		Path dir;
		try {
			dir = ListShare.createExportsDir();
		} catch (IOException e) {
			setStatus("Couldn't open the exports folder: " + e.getMessage(), RED);
			return;
		}
		Util.getPlatform().openPath(dir);
		setStatus("Opened config/materialsgui/exports.", GRAY);
	}

	private void saveFile() {
		if (materials.isEmpty()) {
			setStatus("This list is empty, so there's nothing to save.", RED);
			return;
		}
		try {
			Path file = ListShare.writeFile(name, materials, replacements);
			setStatus("Saved " + file.getFileName() + ". Open Folder to find it.", GREEN);
		} catch (IOException e) {
			setStatus("Couldn't write the file: " + e.getMessage(), RED);
		}
	}

	private void setStatus(String text, int color) {
		status = text;
		statusColor = color;
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
		String subtitle = (name == null ? "Unnamed list" : name) + ": " + materials.size() + (materials.size() == 1 ? " material" : " materials");
		graphics.centeredText(this.font, fit(subtitle, right - left), this.width / 2, 22, GRAY);

		// Wrapped, because the column is only 300 wide on the narrowest GUI.
		List<FormattedCharSequence> hint = this.font.split(HINT, right - left);
		for (int i = 0; i < hint.size(); i++) {
			graphics.text(this.font, hint.get(i), left, 132 + i * 11, GRAY, true);
		}
		if (!status.isEmpty()) {
			graphics.centeredText(this.font, fit(status, right - left), this.width / 2, this.height - 42, statusColor);
		}
	}
}
