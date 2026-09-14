package dev.kianj.materialsgui.importer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

/**
 * Turns pasted text into materials. Understands Litematica material list exports (the ASCII table .txt and the .csv)
 * and free-form lists like "64 stone", "3x oak_planks", "Glass: 128", "stone bricks - 5 stacks + 12", "2 sb glass".
 */
public final class MaterialParser {
	public record Result(Map<Item, Integer> materials, List<String> unresolved) {}

	private static final Pattern PRODUCT = Pattern.compile("(\\d+)\\s*[x×*]\\s*(\\d+)");
	private static final Pattern AMOUNT = Pattern.compile(
		"(?i)(\\d[\\d,]*)\\s*((?:shulker\\s*box(?:es)?|shulkers?|sbs?|double\\s*chests?|dcs?|stacks?|st)\\b|x(?![a-z]))?"
	);
	private static final Pattern BULLET = Pattern.compile("^\\s*(?:[-*•>]+|\\d+[.)])\\s+");
	// A colon is only a separator when followed by whitespace, so namespaced ids like "minecraft:stone" survive.
	private static final Pattern FILLER = Pattern.compile("(?i)(?:^|\\s)(?:x|of|and)(?=\\s|$)|[+=×]|:(?=\\s|$)|\\s-\\s|^-|-$");

	private MaterialParser() {}

	/** Adds two amounts, capping at the largest amount instead of overflowing. */
	public static int addClamped(int a, int b) {
		return (int) Math.max(0, Math.min(Integer.MAX_VALUE, (long) a + b));
	}

	private static long add(long a, long b) {
		try {
			return Math.addExact(a, b);
		} catch (ArithmeticException e) {
			return Long.MAX_VALUE;
		}
	}

	private static long multiply(long a, long b) {
		try {
			return Math.multiplyExact(a, b);
		} catch (ArithmeticException e) {
			return Long.MAX_VALUE;
		}
	}

	/** Digits to a number; anything too long for a long is capped (amounts are capped far below that anyway). */
	private static long number(String digits) {
		return digits.length() > 18 ? Long.MAX_VALUE : Long.parseLong(digits);
	}

	public static Result parse(String text) {
		Map<Item, Integer> out = new LinkedHashMap<>();
		List<String> unresolved = new ArrayList<>();
		boolean csv = false;

		// Some exports start with a byte order mark, which would hide the CSV header.
		for (String rawLine : text.replace("\uFEFF", "").split("\\R")) {
			String line = rawLine.strip();
			if (line.isEmpty() || line.startsWith("+") || line.startsWith("#")) {
				continue;
			}
			if (line.replace("\"", "").toLowerCase(Locale.ROOT).startsWith("item,")) {
				csv = true;
				continue;
			}

			List<String[]> entries = new ArrayList<>();
			if (line.contains("|")) {
				// Litematica table row: | Name | Total | Missing | Available |
				String[] cells = cells(line.split("\\|"));
				if (cells.length >= 2 && isNumber(cells[1])) {
					entries.add(new String[] {cells[0], cells[1]});
				}
			} else if (csv) {
				String[] cells = cells(line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)"));
				if (cells.length >= 2 && isNumber(cells[1])) {
					entries.add(new String[] {cells[0], cells[1]});
				}
			} else {
				// Free-form. Commas between digits are thousands separators; other commas/semicolons split entries.
				for (String part : line.split(";|(?<!\\d),|,(?!\\d{3}(?:\\D|$))")) {
					entries.add(new String[] {part, null});
				}
			}

			for (String[] e : entries) {
				Parsed p = e[1] != null ? new Parsed(e[0].strip(), amount(e[1]).items, 0) : freeForm(e[0]);
				if (p == null || p.name.isBlank()) {
					continue;
				}
				Item item = ItemResolver.resolve(p.name);
				if (item == null) {
					unresolved.add(e[0].strip());
					continue;
				}
				long count = add(p.items, multiply(p.stacks, item.getDefaultMaxStackSize()));
				if (count > 0) {
					out.merge(item, (int) Math.min(Integer.MAX_VALUE, count), MaterialParser::addClamped);
				}
			}
		}
		return new Result(out, unresolved);
	}

	/** Formats materials as "count item_id" lines, the canonical form shown in the editor. */
	public static String format(Map<Item, Integer> materials) {
		StringBuilder sb = new StringBuilder();
		materials.forEach((item, count) -> sb.append(count).append(' ').append(BuiltInRegistries.ITEM.getKey(item).getPath()).append('\n'));
		return sb.toString();
	}

	private record Parsed(String name, long items, long stacks) {}

	private record Amount(long items, long stacks, String rest) {}

	private static Parsed freeForm(String text) {
		String s = text.strip();
		String unbulleted = BULLET.matcher(s).replaceFirst("");
		if (unbulleted.matches(".*\\d.*")) {
			s = unbulleted;
		}
		Amount a = amount(s);
		if (a.items == 0 && a.stacks == 0) {
			return null;
		}
		String name = FILLER.matcher(a.rest).replaceAll(" ").replaceAll("[()\\[\\]\"]", " ").replaceAll("\\s+", " ").strip();
		return new Parsed(name, a.items, a.stacks);
	}

	private static Amount amount(String text) {
		Matcher pm = PRODUCT.matcher(text);
		StringBuilder sb = new StringBuilder();
		while (pm.find()) {
			pm.appendReplacement(sb, Long.toString(Math.min(multiply(number(pm.group(1)), number(pm.group(2))), Long.MAX_VALUE / 2)));
		}
		pm.appendTail(sb);

		long items = 0;
		long stacks = 0;
		Matcher m = AMOUNT.matcher(sb);
		StringBuilder rest = new StringBuilder();
		while (m.find()) {
			long n = number(m.group(1).replace(",", ""));
			String unit = m.group(2) == null ? "" : m.group(2).toLowerCase(Locale.ROOT).replaceAll("\\s", "");
			if (unit.startsWith("shulker") || unit.startsWith("sb")) {
				stacks = add(stacks, multiply(n, 27));
			} else if (unit.startsWith("doublechest") || unit.startsWith("dc")) {
				stacks = add(stacks, multiply(n, 54));
			} else if (unit.startsWith("st")) {
				stacks = add(stacks, n);
			} else {
				items = add(items, n);
			}
			m.appendReplacement(rest, " ");
		}
		m.appendTail(rest);
		return new Amount(items, stacks, rest.toString());
	}

	private static String[] cells(String[] parts) {
		List<String> out = new ArrayList<>();
		for (String p : parts) {
			String c = p.strip().replaceAll("^\"|\"$", "").strip();
			if (!c.isEmpty()) {
				out.add(c);
			}
		}
		return out.toArray(String[]::new);
	}

	private static boolean isNumber(String s) {
		return s.strip().replace(",", "").matches("\\d+");
	}
}
