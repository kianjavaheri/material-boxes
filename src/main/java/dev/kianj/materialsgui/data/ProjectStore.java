package dev.kianj.materialsgui.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
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
			Path file = file();
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(project));
		} catch (IOException e) {
			LOGGER.warn("Couldn't save material project", e);
		}
	}

	private static Path file() {
		return ModConfig.dir().resolve("projects").resolve(worldKey + ".json");
	}

	private static String worldKey(Minecraft mc) {
		IntegratedServer sp = mc.getSingleplayerServer();
		if (sp != null) {
			return "sp_" + sanitize(sp.getWorldData().getLevelName());
		}
		ServerData server = mc.getCurrentServer();
		if (server != null) {
			return "mp_" + sanitize(server.ip);
		}
		return "default";
	}

	private static String sanitize(String s) {
		return s.toLowerCase().replaceAll("[^a-z0-9._-]", "_");
	}
}
