package dev.kianj.materialsgui.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Holds the active world's {@link Project} and its computed {@link Layout}; saves to config/materialsgui/projects. */
public final class ProjectStore {
	private static final Logger LOGGER = LoggerFactory.getLogger("materialsgui");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static String worldKey = "default";
	private static Project project = new Project();
	private static Layout layout = Layout.compute(project);

	private ProjectStore() {}

	public static Project project() {
		return project;
	}

	public static Layout layout() {
		return layout;
	}

	/** Key of the current world or server, e.g. "sp_new_world" or "mp_play.example.com". */
	public static String worldKey() {
		return worldKey;
	}

	/** Call after any change to the material list or boxes. Recomputes the layout, saves, and syncs the saved list's boxes. */
	public static void changed() {
		recompute();
		save();
		SavedLists.syncBoxes(project, worldKey);
	}

	/** Recomputes the layout without saving; used for live box-content updates while a box is open. */
	public static void recompute() {
		layout = Layout.compute(project);
	}

	public static void load(Minecraft mc) {
		worldKey = worldKey(mc);
		project = new Project();
		Path file = file();
		if (Files.exists(file)) {
			try {
				Project loaded = GSON.fromJson(Files.readString(file), Project.class);
				if (loaded != null) {
					project = loaded;
					project.sanitize();
				}
			} catch (Exception e) {
				ModConfig.setAsideUnreadable(file, e);
			}
		}
		layout = Layout.compute(project);
		// Boxes from before lists had their own are taken on by the list that's loaded.
		SavedLists.syncBoxes(project, worldKey);
	}

	public static void save() {
		try {
			ModConfig.writeAtomically(file(), GSON.toJson(project));
		} catch (IOException e) {
			LOGGER.warn("Couldn't save material project", e);
		}
	}

	private static Path file() {
		return ModConfig.dir().resolve("projects").resolve(worldKey + ".json");
	}

	/**
	 * Singleplayer worlds are keyed by their save folder, which is unique (two worlds can share a name). LAN worlds and
	 * Realms are keyed by name, since their address changes each time.
	 */
	private static String worldKey(Minecraft mc) {
		IntegratedServer sp = mc.getSingleplayerServer();
		if (sp != null) {
			Path folder = sp.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName();
			return "sp_" + sanitize(folder != null ? folder.toString() : sp.getWorldData().getLevelName());
		}
		ServerData server = mc.getCurrentServer();
		if (server != null) {
			if (server.isRealm()) {
				return "realm_" + sanitize(server.name);
			}
			if (server.isLan()) {
				return "lan_" + sanitize(server.name);
			}
			return "mp_" + sanitize(server.ip);
		}
		return "default";
	}

	/** A file-name-safe key. Names in other scripts also get a hash, so that "世界" and "天堂" don't both become "__". */
	public static String sanitize(String s) {
		String safe = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
		return s.chars().allMatch(c -> c < 128) ? safe : safe + "_" + Integer.toHexString(s.hashCode());
	}
}
