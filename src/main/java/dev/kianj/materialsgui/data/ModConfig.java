package dev.kianj.materialsgui.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Global settings stored in config/materialsgui/config.json. */
public final class ModConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger("materialsgui");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static ModConfig instance;
	/**
	 * Where the mod keeps its files. The loader-specific entrypoint points this at the real config folder; the fallback
	 * only serves tests, so nothing in this package has to know how a loader finds it.
	 */
	private static Path root = Path.of("config", "materialsgui");

	/** Anthropic API key used for screenshot import. */
	public String anthropicApiKey = "";
	public String model = "claude-opus-5";
	/** Whether the Materials List shows its import panel (it also opens by itself when the list is empty). */
	public boolean importPanelOpen = true;
	/** The on-screen checklist of missing materials. */
	public boolean hudEnabled = true;
	public boolean hudOnRight = false;
	public int hudRows = 8;
	/** A smaller HUD: no title, and only the first few materials, one line each. */
	public boolean hudCompact = false;
	/** Open Material Boxes within reach in the background to read contents that may have changed. */
	public boolean refreshBoxes = true;
	/** Color, ghost items and amounts in Material Box slots. Turned off while building, when boxes are being emptied. */
	public boolean highlightSlots = true;

	/** Points the mod's files at a folder. Call once at startup, before anything is read or written. */
	public static void useDirectory(Path directory) {
		root = directory;
		// Anything already read from the old folder would be stale, and saving it would write it to the new one.
		instance = null;
	}

	public static Path dir() {
		return root;
	}

	private static Path file() {
		return dir().resolve("config.json");
	}

	public static ModConfig get() {
		if (instance == null) {
			instance = new ModConfig();
			Path file = file();
			if (Files.exists(file)) {
				try {
					ModConfig loaded = GSON.fromJson(Files.readString(file), ModConfig.class);
					if (loaded != null) {
						instance = loaded;
					}
				} catch (Exception e) {
					setAsideUnreadable(file, e);
				}
			}
			instance.sanitize();
		}
		return instance;
	}

	/** Fills in anything a hand-edited or older config left out or set to something unusable. */
	private void sanitize() {
		if (anthropicApiKey == null) {
			anthropicApiKey = "";
		}
		if (model == null || model.isBlank()) {
			model = "claude-opus-5";
		}
		hudRows = Math.max(1, Math.min(32, hudRows));
	}

	/**
	 * Renames a file that couldn't be read to "*.unreadable" (or "*.unreadable.2" and so on), so saving defaults over
	 * it doesn't destroy it, and a later failure doesn't replace an earlier copy.
	 */
	public static void setAsideUnreadable(Path file, Exception e) {
		Path aside = file.resolveSibling(file.getFileName() + ".unreadable");
		for (int n = 2; Files.exists(aside); n++) {
			aside = file.resolveSibling(file.getFileName() + ".unreadable." + n);
		}
		LOGGER.warn("Couldn't read {}; keeping it as {}", file, aside.getFileName(), e);
		try {
			Files.move(file, aside);
		} catch (IOException moveError) {
			LOGGER.warn("Couldn't set aside {}", file, moveError);
		}
	}

	/** Writes a file in one step, through a temporary file, so a crash mid-save can't leave it cut short or empty. */
	public static void writeAtomically(Path file, String text) throws IOException {
		Files.createDirectories(file.getParent());
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(temp, text);
		try {
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	public void save() {
		try {
			writeAtomically(file(), GSON.toJson(this));
		} catch (IOException e) {
			LOGGER.warn("Couldn't save config", e);
		}
	}
}
