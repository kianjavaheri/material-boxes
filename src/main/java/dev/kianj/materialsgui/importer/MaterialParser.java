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
import org.jspecify.annotations.Nullable;

/**
 * Turns a pasted material list into item amounts. Understands free-form lines ("64 stone", "3 stacks oak planks",
 * "Glass: 128"), Litematica's text tables and CSV exports, and cells copied from a spreadsheet.
 */
public final class MaterialParser {
	public record Result(Map<Item, Integer> materials, List<String> unresolved) {}

	private static final Pattern PRODUCT = Pattern.compile("(\\d+)\\s*[x×*]\\s*(\\d+)");
	private static final String UNITS = "shulker\\s*box(?:es)?|shulkers?|sbs?|double\\s*chests?|dcs?|stacks?|st";
	/**
	 * A number: digits with thousands commas or a decimal point, and an optional "k". Not straight after a letter or an
	 * underscore, so the 5 in "music_disc_5" stays part of the name (the 3 in "x3" still counts).
	 */
	private static final String NUMBER = "(?<![a-wyz_:])(\\d[\\d,]*(?:\\.\\d+)?)(k(?![a-z]))?";
	private static final Pattern AMOUNT = Pattern.compile("(?i)" + NUMBER + "\\s*((?:" + UNITS + ")\\b|x(?![a-z]))?");
	private static final Pattern PLAIN_AMOUNT = Pattern.compile("(?i)" + NUMBER + "\\s*(x(?![a-z]))?");
	/** "1.000" in the European style is a thousand, not one. */
	private static final Pattern THOUSANDS_DOT = Pattern.compile("(?<![\\d.,])(\\d{1,3}(?:\\.\\d{3})+)(?![\\d.,])");
	private static final Pattern BRACKETS = Pattern.compile("\\([^)]*\\)|\\[[^\\]]*]");
	private static final Pattern BULLET = Pattern.compile("^\\s*(?:[-*•>]+|\\d+[.)])\\s+");
	// A colon is only a separator when followed by whitespace, so namespaced ids like "minecraft:stone" survive. "of" is
	// only filler at the start ("2 stacks of stone"), so "Block of Iron" keeps it.
	private static final Pattern FILLER = Pattern.compile("(?i)(?:^|\\s)(?:x|and)(?=\\s|$)|^\\s*of(?=\\s|$)|[+=×]|:(?=\\s|$)|\\s-\\s|^-|-$");

	private MaterialParser() {}

	/** Adds two amounts, capping at the largest amount instead of overflowing. */
	public static int addClamped(int a, int b) {
		return (int) Math.max(0, Math.min(Integer.MAX_VALUE, (long) a + b));
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

	private static double value(String digits) {
		try {
			return Double.parseDouble(digits.replace(",", ""));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	public static Result parse(String text) {
		Map<Item, Integer> out = new LinkedHashMap<>();
		List<String> unresolved = new ArrayList<>();
		boolean csv = false;

		// Some exports start with a byte order mark, which would hide the CSV header.
		for (String rawLine : text.replace("﻿", "").split("\\R")) {
			String line = rawLine.strip();
			// Comments, table borders, and the heading Copy puts above a list.
			if (line.isEmpty() || line.startsWith("+") || line.startsWith("#") || line.startsWith("Still needed")) {
				continue;
			}
			if (line.replace("\"", "").toLowerCase(Locale.ROOT).startsWith("item,")) {
				csv = true;
				continue;
			}

			List<String[]> entries = new ArrayList<>();
			if (line.contains("|") || line.contains("\t")) {
				// A table row: Litematica's "| Name | Total | Missing | Available |", or cells copied from a spreadsheet.
				String[] entry = nameAndAmount(cells(line.split("[|\\t]")));
				if (entry != null) {
					entries.add(entry);
				}
			} else if (csv && line.contains(",")) {
				String[] entry = nameAndAmount(cells(line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")));
				if (entry != null) {
					entries.add(entry);
				}
			} else {
				// Free-form. Commas between digits are thousands separators; other commas/semicolons split entries.
				String[] parts = line.split(";|(?<!\\d),|,(?!\\d{3}(?:\\D|$))");
				for (int i = 0; i < parts.length; i++) {
					// "Stone,64": a name followed by a bare amount is one entry.
					if (i + 1 < parts.length && !parts[i].matches(".*\\d.*") && isNumber(parts[i + 1])) {
						entries.add(new String[] {parts[i], parts[i + 1]});
						i++;
					} else {
						entries.add(new String[] {parts[i], null});
					}
				}
			}

			for (String[] e : entries) {
				String source = e[0].strip();
				Parsed p;
				if (e[1] != null) {
					Amount a = amount(e[1], true);
					p = new Parsed(source, a.items, a.stacks, false);
				} else {
					p = freeForm(source, true);
				}
				if (p == null) {
					// No amount: a heading or a note.
					continue;
				}
				Item item = p.name.isBlank() ? null : ItemResolver.resolve(p.name);
				if (item == null && p.usedUnit) {
					// "4 Shulker Box" and "8 Shulker Shell" name items; they aren't amounts in shulker boxes.
					Parsed plain = freeForm(source, false);
					Item plainItem = plain == null || plain.name.isBlank() ? null : ItemResolver.resolve(plain.name);
					if (plainItem != null) {
						p = plain;
						item = plainItem;
					}
				}
				if (item == null) {
					unresolved.add(e[1] != null ? e[1].strip() + " " + source : source);
					continue;
				}
				long count = Math.round(p.items + p.stacks * item.getDefaultMaxStackSize());
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

	private record Parsed(String name, double items, double stacks, boolean usedUnit) {}

	private record Amount(boolean found, double items, double stacks, boolean usedUnit, String rest) {}

	/** A free-form entry, or null if it has no amount. With {@code units} off, "stacks" and the like are part of the name. */
	private static @Nullable Parsed freeForm(String text, boolean units) {
		String s = text.strip();
		String unbulleted = BULLET.matcher(s).replaceFirst("");
		if (unbulleted.matches(".*\\d.*")) {
			s = unbulleted;
		}
		// "Stone 1728 (27 stacks)": a note in brackets doesn't add to an amount given outside them.
		String outside = BRACKETS.matcher(s).replaceAll(" ");
		if (outside.matches(".*\\d.*")) {
			s = outside;
		}
		Amount a = amount(s, units);
		if (!a.found) {
			return null;
		}
		String name = FILLER.matcher(a.rest).replaceAll(" ").replaceAll("[()\\[\\]\"]", " ").replaceAll("\\s+", " ").strip();
		return new Parsed(name, a.items, a.stacks, a.usedUnit);
	}

	/**
	 * The amount in a piece of text, and the text without it. A line has one amount: a later number only adds to it
	 * when joined by "+" ("5 stacks + 12") or straight after an amount in stacks or boxes ("5 stacks 12"). Any other
	 * number stays in the text, so a line like "Stone 1728 27" is reported instead of guessed at.
	 */
	private static Amount amount(String text, boolean units) {
		String s = THOUSANDS_DOT.matcher(text).replaceAll(m -> m.group(1).replace(".", ""));
		Matcher pm = PRODUCT.matcher(s);
		StringBuilder sb = new StringBuilder();
		while (pm.find()) {
			pm.appendReplacement(sb, Long.toString(Math.min(multiply(number(pm.group(1)), number(pm.group(2))), Long.MAX_VALUE / 2)));
		}
		pm.appendTail(sb);

		double items = 0;
		double stacks = 0;
		boolean found = false;
		boolean usedUnit = false;
		boolean lastHadUnit = false;
		int lastEnd = 0;
		Matcher m = (units ? AMOUNT : PLAIN_AMOUNT).matcher(sb);
		StringBuilder rest = new StringBuilder();
		while (m.find()) {
			String unit = units && m.group(3) != null ? m.group(3).toLowerCase(Locale.ROOT).replaceAll("\\s", "") : "";
			if (unit.equals("x")) {
				unit = "";
			}
			String between = sb.substring(lastEnd, m.start());
			if (found && !between.contains("+") && !(lastHadUnit && unit.isEmpty() && between.isBlank())) {
				continue;
			}
			double n = value(m.group(1)) * (m.group(2) != null ? 1000 : 1);
			if (unit.startsWith("shulker") || unit.startsWith("sb")) {
				stacks += n * 27;
			} else if (unit.startsWith("doublechest") || unit.startsWith("dc")) {
				stacks += n * 54;
			} else if (unit.startsWith("st")) {
				stacks += n;
			} else {
				items += n;
			}
			found = true;
			usedUnit |= !unit.isEmpty();
			lastHadUnit = !unit.isEmpty();
			lastEnd = m.end();
			m.appendReplacement(rest, " ");
		}
		m.appendTail(rest);
		return new Amount(found, items, stacks, usedUnit, rest.toString());
	}

	/** The first cell that isn't a number is the name; the amount is the first number after it, or else before it. */
	private static String @Nullable [] nameAndAmount(String[] cells) {
		int name = -1;
		for (int i = 0; i < cells.length && name < 0; i++) {
			if (!isNumber(cells[i])) {
				name = i;
			}
		}
		if (name < 0) {
			return null;
		}
		for (int i = name + 1; i < cells.length; i++) {
			if (isNumber(cells[i])) {
				return new String[] {cells[name], cells[i]};
			}
		}
		for (int i = 0; i < name; i++) {
			if (isNumber(cells[i])) {
				return new String[] {cells[name], cells[i]};
			}
		}
		return null;
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
		return s.strip().replace(",", "").matches("\\d+(?:\\.\\d+)?");
	}
}
