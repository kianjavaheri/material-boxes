package dev.kianj.materialsgui.importer;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/** Maps a human-written item name ("Oak Planks", "oak_plank", "minecraft:oak_planks", "stone brick") to an Item. */
public final class ItemResolver {
	private static Map<String, Item> index;

	private ItemResolver() {}

	public static @Nullable Item resolve(String rawName) {
		String name = rawName.strip();
		Identifier id = Identifier.tryParse(name.toLowerCase(Locale.ROOT).replace(' ', '_'));
		if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
			Item item = BuiltInRegistries.ITEM.getValue(id);
			if (item != Items.AIR) {
				return item;
			}
		}

		Map<String, Item> idx = index();
		String key = normalize(name);
		if (key.isEmpty()) {
			return null;
		}
		Item hit = idx.get(key);
		if (hit == null && key.endsWith("es")) {
			hit = idx.get(key.substring(0, key.length() - 2));
		}
		if (hit == null && key.endsWith("s")) {
			hit = idx.get(key.substring(0, key.length() - 1));
		}
		if (hit != null) {
			return hit;
		}
		return fuzzy(key, idx);
	}

	private static @Nullable Item fuzzy(String key, Map<String, Item> idx) {
		int limit = Math.max(1, key.length() / 5);
		Item best = null;
		int bestDist = Integer.MAX_VALUE;
		boolean tie = false;
		for (Map.Entry<String, Item> e : idx.entrySet()) {
			if (Math.abs(e.getKey().length() - key.length()) > limit) {
				continue;
			}
			int d = levenshtein(key, e.getKey(), limit);
			if (d < bestDist) {
				bestDist = d;
				best = e.getValue();
				tie = false;
			} else if (d == bestDist && e.getValue() != best) {
				tie = true;
			}
		}
		return bestDist <= limit && !tie ? best : null;
	}

	private static Map<String, Item> index() {
		if (index == null) {
			Map<String, Item> idx = new HashMap<>();
			for (Item item : BuiltInRegistries.ITEM) {
				if (item == Items.AIR) {
					continue;
				}
				idx.putIfAbsent(normalize(BuiltInRegistries.ITEM.getKey(item).getPath()), item);
				idx.putIfAbsent(normalize(item.getName(new ItemStack(item)).getString()), item);
			}
			index = idx;
		}
		return index;
	}

	public static String normalize(String s) {
		String lower = s.toLowerCase(Locale.ROOT);
		if (lower.startsWith("minecraft:")) {
			lower = lower.substring("minecraft:".length());
		}
		return lower.replaceAll("[^a-z0-9]", "");
	}

	private static int levenshtein(String a, String b, int limit) {
		int[] prev = new int[b.length() + 1];
		int[] cur = new int[b.length() + 1];
		for (int j = 0; j <= b.length(); j++) {
			prev[j] = j;
		}
		for (int i = 1; i <= a.length(); i++) {
			cur[0] = i;
			int rowMin = cur[0];
			for (int j = 1; j <= b.length(); j++) {
				int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
				cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
				rowMin = Math.min(rowMin, cur[j]);
			}
			if (rowMin > limit) {
				return Integer.MAX_VALUE;
			}
			int[] t = prev;
			prev = cur;
			cur = t;
		}
		return prev[b.length()];
	}
}
