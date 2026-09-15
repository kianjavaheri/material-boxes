package dev.kianj.materialsgui.screen;

import dev.kianj.materialsgui.data.ModConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

/**
 * Settings: the Claude API key and model for screenshot import, the HUD, box refreshing, and the import panel. Opened
 * from the Materials List, with /materials settings, and from Mod Menu.
 */
public class SettingsScreen extends Screen {
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GRAY = 0xFFA0A0A0;
	private static final int[] HUD_ROWS = {4, 6, 8, 12, 16};
	private static final int COMPACT_ROWS = 3;

	private final @Nullable Screen parent;
	private int left;
	private int keyY;
	private int modelY;
	private int hudY;
	private int otherY;

	public SettingsScreen(@Nullable Screen parent) {
		super(Component.literal("Material Boxes Settings"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		ModConfig config = ModConfig.get();
		int width = Math.min(300, this.width - 20);
		int half = (width - 4) / 2;
		left = (this.width - width) / 2;
		int right = left + half + 4;
		// Packed to fit a 240-high GUI, the smallest there is, above the Done button.
		keyY = 34;
		modelY = keyY + 34;
		hudY = modelY + 36;
		otherY = hudY + 58;

		EditBox apiKey = new EditBox(this.font, left, keyY, width, 20, Component.literal("Anthropic API key"));
		apiKey.setMaxLength(256);
		apiKey.setHint(Component.literal("sk-ant-..."));
		apiKey.setValue(config.anthropicApiKey);
		apiKey.addFormatter((text, offset) -> FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
		// Only printable ASCII: an invisible character pasted along with the key would make every request fail.
		apiKey.setResponder(v -> config.anthropicApiKey = v.replaceAll("[^\\x21-\\x7E]", ""));
		addRenderableWidget(apiKey);

		EditBox model = new EditBox(this.font, left, modelY, width, 20, Component.literal("Claude model"));
		model.setMaxLength(64);
		model.setHint(Component.literal("claude-opus-5"));
		model.setValue(config.model);
		model.setResponder(v -> config.model = v.isBlank() ? "claude-opus-5" : v.strip());
		addRenderableWidget(model);

		toggle("Show HUD: " + onOff(config.hudEnabled), "Also toggled with H", left, hudY, half, () -> config.hudEnabled = !config.hudEnabled);
		toggle("Size: " + (config.hudCompact ? "Compact" : "Full"), "Compact shows only the first " + COMPACT_ROWS + " missing materials, one line each",
			right, hudY, half, () -> config.hudCompact = !config.hudCompact);
		toggle("Corner: " + (config.hudOnRight ? "Top right" : "Top left"), null, left, hudY + 24, half, () -> config.hudOnRight = !config.hudOnRight);
		Button rows = toggle("Rows: " + (config.hudCompact ? COMPACT_ROWS : config.hudRows), "How many materials the full HUD lists",
			right, hudY + 24, half, () -> config.hudRows = nextRows(config.hudRows));
		rows.active = !config.hudCompact;

		toggle("Auto-refresh boxes: " + onOff(config.refreshBoxes),
			"When you're in reach of a Material Box that hasn't been opened since you joined, or that someone else has opened since, "
				+ "open it in the background to update its counts. Others nearby see and hear it open.",
			left, otherY, half, () -> config.refreshBoxes = !config.refreshBoxes);
		toggle("Import panel: " + (config.importPanelOpen ? "Open" : "Hidden"), "Whether the Materials List opens with its import panel showing",
			right, otherY, half, () -> config.importPanelOpen = !config.importPanelOpen);
		toggle("Slot highlights: " + onOff(config.highlightSlots),
			"The colors, ghost items and amounts in Material Box slots. Turn them off while you build. Also a button beside every Material Box.",
			left, otherY + 24, half, () -> config.highlightSlots = !config.highlightSlots);

		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(left, this.height - 28, width, 20).build());
	}

	private Button toggle(String label, @Nullable String tooltip, int x, int y, int width, Runnable flip) {
		Button.Builder builder = Button.builder(Component.literal(label), b -> {
			flip.run();
			rebuildWidgets();
		}).bounds(x, y, width, 20);
		if (tooltip != null) {
			builder.tooltip(Tooltip.create(Component.literal(tooltip)));
		}
		return addRenderableWidget(builder.build());
	}

	private static String onOff(boolean on) {
		return on ? "On" : "Off";
	}

	private static int nextRows(int rows) {
		for (int n : HUD_ROWS) {
			if (n > rows) {
				return n;
			}
		}
		return HUD_ROWS[0];
	}

	@Override
	public void removed() {
		ModConfig.get().save();
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(parent);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(this.font, this.title, this.width / 2, 10, WHITE);
		graphics.text(this.font, "Anthropic API key (only for screenshot import)", left, keyY - 11, GRAY, true);
		graphics.text(this.font, "Claude model", left, modelY - 11, GRAY, true);
		graphics.text(this.font, "Missing materials HUD", left, hudY - 11, GRAY, true);
		graphics.text(this.font, "Material Boxes and list", left, otherY - 11, GRAY, true);
	}
}
