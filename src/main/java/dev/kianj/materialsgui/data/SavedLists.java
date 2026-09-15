package dev.kianj.materialsgui.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Named material lists, shared by every world and server. Saved in config/materialsgui/saved-lists.json. */
public final class SavedLists {
	private static final Logger LOGGER = LoggerFactory.getLogger("materialsgui");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type TYPE = new TypeToken<List<SavedList>>() {}.getType();

	private static @Nullable List<SavedList> lists;

	private SavedLists() {}

	public static final class SavedList {
		public String name;
		public long savedAt;
		public List<Project.MaterialEntry> materials = new ArrayList<>();
		/** The list's item swaps, so importing into it again gets them too. */
		public Map<String, String> replacements = new LinkedHashMap<>();
		/** The list's Material Boxes in each world (world key to boxes), so switching lists switches boxes. */
		public Map<String, List<Project.BoxEntry>> boxes = new LinkedHashMap<>();

		/** Repairs what a damaged save can contain. Returns false if the list can't be used at all. */
		public boolean sanitize() {
			if (name == null || name.isBlank()) {
				return false;
			}
			Project project = new Project();
			project.materials = materials;
			project.replacements = replacements;
			project.sanitize();
			materials = project.materials;
			replacements = project.replacements;
			if (boxes == null) {
				boxes = new LinkedHashMap<>();
			}
			boxes.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
			boxes.values().forEach(Project::sanitizeBoxes);
			return true;
		}
	}

	public static List<SavedList> all() {
		if (lists == null) {
			lists = read();
		}
		return lists;
	}

	/** The saved list with this name (ignoring case), or null. */
	public static @Nullable SavedList find(@Nullable String name) {
		if (name == null) {
			return null;
		}
		for (SavedList list : all()) {
			if (list.name.equalsIgnoreCase(name.strip())) {
				return list;
			}
		}
		return null;
	}

	/** A copy of the project's materials and replacements under a name. */
	public static SavedList snapshot(String name, Project project) {
		SavedList list = new SavedList();
		list.name = name;
		list.savedAt = System.currentTimeMillis();
		list.materials = copy(project.materials);
		list.replacements = new LinkedHashMap<>(project.replacements);
		return list;
	}

	/** True if the project's list has the same materials and replacements as this saved list. */
	public static boolean matches(SavedList list, Project project) {
		if (list.materials.size() != project.materials.size() || !list.replacements.equals(project.replacements)) {
			return false;
		}
		for (int i = 0; i < list.materials.size(); i++) {
			Project.MaterialEntry a = list.materials.get(i);
			Project.MaterialEntry b = project.materials.get(i);
			if (!a.item.equals(b.item) || a.count != b.count) {
				return false;
			}
		}
		return true;
	}

	/** True if the project's list is saved and hasn't changed since. */
	public static boolean isSaved(Project project) {
		SavedList list = find(project.listName);
		return list != null && matches(list, project);
	}

	/**
	 * Saves the project's list and its Material Boxes in this world under this name, replacing any list with that name
	 * (but keeping that list's boxes in other worlds), and makes it the current list.
	 */
	public static void save(String name, Project project, String worldKey) {
		SavedList list = snapshot(name, project);
		SavedList existing = find(name);
		if (existing != null) {
			list.boxes.putAll(existing.boxes);
			all().remove(existing);
		}
		list.boxes.put(worldKey, copyBoxes(project.boxes));
		all().add(list);
		project.listName = name;
		write();
	}

	/**
	 * Replaces the project's list with a copy of a saved one, including its Material Boxes in this world. A list with no
	 * boxes here yet keeps the current ones. Returns true if it brought its own boxes.
	 */
	public static boolean open(SavedList list, Project project, String worldKey) {
		project.materials = copy(list.materials);
		project.replacements = new LinkedHashMap<>(list.replacements);
		project.listName = list.name;
		List<Project.BoxEntry> boxes = list.boxes.get(worldKey);
		if (boxes == null) {
			return false;
		}
		project.boxes = copyBoxes(boxes);
		return true;
	}

	/** Makes a saved list the current one in this world. Returns true if it brought its own Material Boxes. */
	public static boolean load(SavedList list) {
		boolean ownBoxes = open(list, ProjectStore.project(), ProjectStore.worldKey());
		// Also records the kept boxes with the list when it had none here yet.
		ProjectStore.changed();
		return ownBoxes;
	}

	/** Sets a list's boxes in a world to a copy of these. Returns true if they were different. */
	public static boolean putBoxes(SavedList list, String worldKey, List<Project.BoxEntry> boxes) {
		List<Project.BoxEntry> copy = copyBoxes(boxes);
		if (GSON.toJson(copy).equals(GSON.toJson(list.boxes.get(worldKey)))) {
			return false;
		}
		list.boxes.put(worldKey, copy);
		return true;
	}

	/** Keeps the current saved list's Material Boxes in this world the same as the project's. */
	public static void syncBoxes(Project project, String worldKey) {
		SavedList list = find(project.listName);
		if (list != null && putBoxes(list, worldKey, project.boxes)) {
			write();
		}
	}

	/** Names of the saved lists, other than the current one, that use the box at this position in this world. */
	public static List<String> otherListsUsing(BoxKey key, String worldKey, @Nullable String currentList) {
		return otherListsUsing(all(), key, worldKey, currentList);
	}

	public static List<String> otherListsUsing(Collection<SavedList> lists, BoxKey key, String worldKey, @Nullable String currentList) {
		List<String> names = new ArrayList<>();
		for (SavedList list : lists) {
			List<Project.BoxEntry> boxes = list.boxes.get(worldKey);
			if (!list.name.equalsIgnoreCase(currentList) && boxes != null && Project.indexOfBox(boxes, key) >= 0) {
				names.add(list.name);
			}
		}
		return names;
	}

	/** True if the unsaved current list has a box that no saved list has in this world, so switching lists would lose it. */
	public static boolean hasUnsavedBoxes(Project project, String worldKey) {
		if (find(project.listName) != null) {
			return false;
		}
		for (Project.BoxEntry box : project.boxes) {
			boolean saved = false;
			for (SavedList list : all()) {
				List<Project.BoxEntry> boxes = list.boxes.get(worldKey);
				saved |= boxes != null && boxes.stream().anyMatch(b -> b.key().equals(box.key()));
			}
			if (!saved) {
				return true;
			}
		}
		return false;
	}

	private static List<Project.BoxEntry> copyBoxes(List<Project.BoxEntry> boxes) {
		List<Project.BoxEntry> copy = new ArrayList<>();
		for (Project.BoxEntry box : boxes) {
			copy.add(box.copy());
		}
		return copy;
	}

	public static void rename(SavedList list, String name) {
		list.name = name;
		write();
	}

	public static void delete(SavedList list) {
		all().remove(list);
		write();
	}

	private static List<Project.MaterialEntry> copy(List<Project.MaterialEntry> materials) {
		List<Project.MaterialEntry> copy = new ArrayList<>();
		for (Project.MaterialEntry m : materials) {
			copy.add(new Project.MaterialEntry(m.item, m.count));
		}
		return copy;
	}

	private static Path file() {
		return ModConfig.dir().resolve("saved-lists.json");
	}

	private static List<SavedList> read() {
		Path file = file();
		if (Files.exists(file)) {
			try {
				List<SavedList> loaded = GSON.fromJson(Files.readString(file), TYPE);
				if (loaded != null) {
					List<SavedList> lists = new ArrayList<>(loaded);
					lists.removeIf(list -> list == null || !list.sanitize());
					return lists;
				}
			} catch (Exception e) {
				ModConfig.setAsideUnreadable(file, e);
			}
		}
		return new ArrayList<>();
	}

	public static void write() {
		try {
			ModConfig.writeAtomically(file(), GSON.toJson(all(), TYPE));
		} catch (IOException e) {
			LOGGER.warn("Couldn't save the saved lists", e);
		}
	}
}
