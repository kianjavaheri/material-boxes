package dev.kianj.materialsgui.importer;

import dev.kianj.materialsgui.data.ModConfig;
import dev.kianj.materialsgui.data.Project;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import org.jspecify.annotations.Nullable;

/**
 * Handing a material list to someone else, and taking one back.
 *
 * <p>Two forms. {@link #text} is the plain "count item" list {@link MaterialParser} already reads, for pasting into
 * chat or saving as a file. {@link #encode} is a share code: the same list deflated into one "MBOX1:..." token, which
 * also carries the list's name, which materials are crossed off and its item replacements, and which survives chat
 * clients that would mangle a multi-line paste. An exported file holds both, so a person can read it and
 * {@link #find} still restores everything.
 */
public final class ListShare {
	public static final String PREFIX = "MBOX1:";
	/** A code anywhere in pasted text, so "here's my list: MBOX1:..." works. The body is URL-safe base64. */
	private static final Pattern TOKEN = Pattern.compile("(?i:mbox1:)([A-Za-z0-9_=-]+)");
	private static final String NAMESPACE = "minecraft:";
	/** Guards against a hand-made code being inflated into an out-of-memory crash. */
	private static final int MAX_CODE = 100_000;
	private static final int MAX_PAYLOAD = 1 << 20;
	private static final int MAX_MATERIALS = 10_000;
	private static final Pattern UNSAFE_FILE_NAME = Pattern.compile("[^a-zA-Z0-9 ._-]+");
	/** Names Windows keeps for devices, which can't be files. */
	private static final List<String> RESERVED = List.of("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "LPT1", "LPT2", "LPT3");

	/** A list read back out of a share code. */
	public record Decoded(@Nullable String name, List<Project.MaterialEntry> materials, Map<String, String> replacements) {}

	private ListShare() {}

	/** Where {@link #writeFile} puts exports: config/materialsgui/exports. It may not exist yet. */
	public static Path exportsDir() {
		return ModConfig.dir().resolve("exports");
	}

	/**
	 * The exports folder, made if it isn't there. Opening a folder that doesn't exist does nothing at all, so anything
	 * that shows the player the folder has to call this rather than {@link #exportsDir()}.
	 */
	public static Path createExportsDir() throws IOException {
		Path dir = exportsDir();
		Files.createDirectories(dir);
		return dir;
	}

	/**
	 * The list as text: a short comment header with the share code, then one "count item" line per material. The
	 * comment lines and the code are both ignored by {@link MaterialParser}, so the file reads as a plain list.
	 */
	public static String text(@Nullable String name, List<Project.MaterialEntry> materials, Map<String, String> replacements) {
		StringBuilder sb = new StringBuilder();
		sb.append("# Material Boxes list").append(name == null || name.isBlank() ? "" : ": " + oneLine(name)).append('\n');
		sb.append("# Paste this into the Materials List's import box, or drop this file on it.\n");
		sb.append("# This code restores the name, the crossed-off marks and the item swaps. Delete the line to use only the list below.\n");
		sb.append("# ").append(encode(name, materials, replacements)).append('\n');
		for (Project.MaterialEntry m : materials) {
			sb.append(m.count).append(' ').append(shortId(m.item)).append(m.crossedOff ? " (done)" : "").append('\n');
		}
		return sb.toString();
	}

	/** Writes {@link #text} to config/materialsgui/exports, without overwriting an earlier export. Returns the file. */
	public static Path writeFile(@Nullable String name, List<Project.MaterialEntry> materials, Map<String, String> replacements) throws IOException {
		Path dir = createExportsDir();
		String base = fileName(name);
		Path file = dir.resolve(base + ".txt");
		for (int n = 2; Files.exists(file) && n < 1000; n++) {
			file = dir.resolve(base + " (" + n + ").txt");
		}
		ModConfig.writeAtomically(file, text(name, materials, replacements));
		return file;
	}

	/** A file name for a list: its name where that's usable, and a plain fallback where it isn't. */
	public static String fileName(@Nullable String name) {
		// Separators go with everything else unusable, so a list called "../x" can't write outside the exports folder.
		String safe = name == null ? "" : UNSAFE_FILE_NAME.matcher(name).replaceAll(" ").replaceAll("\\s+", " ").strip();
		// A leading dot hides the file on Unix.
		safe = safe.replaceAll("^[.\\s]+", "").strip();
		if (safe.isEmpty() || RESERVED.contains(safe.toUpperCase(Locale.ROOT))) {
			return "material list" + (safe.isEmpty() ? "" : " " + safe);
		}
		return safe.length() > 64 ? safe.substring(0, 64).strip() : safe;
	}

	public static String encode(Project project) {
		return encode(project.listName, project.materials, project.replacements);
	}

	/** The list as one "MBOX1:..." token. */
	public static String encode(@Nullable String name, List<Project.MaterialEntry> materials, Map<String, String> replacements) {
		StringBuilder payload = new StringBuilder();
		if (name != null && !name.isBlank()) {
			payload.append("N ").append(oneLine(name)).append('\n');
		}
		for (Project.MaterialEntry m : materials) {
			payload.append("M ").append(shortId(m.item)).append(' ').append(m.count).append(m.crossedOff ? " x" : "").append('\n');
		}
		replacements.forEach((from, to) -> payload.append("R ").append(shortId(from)).append(' ').append(shortId(to)).append('\n'));
		byte[] deflated = deflate(payload.toString().getBytes(StandardCharsets.UTF_8));
		return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(deflated);
	}

	/** The first share code in some text, or null. The text can be a whole exported file or a chat message. */
	public static @Nullable String find(String text) {
		Matcher m = TOKEN.matcher(text);
		return m.find() ? PREFIX + m.group(1) : null;
	}

	/** Reads a share code back into a list, or returns null if it isn't one this version understands. */
	public static @Nullable Decoded decode(String code) {
		Matcher m = TOKEN.matcher(code);
		if (!m.find() || m.group(1).length() > MAX_CODE) {
			return null;
		}
		byte[] deflated;
		try {
			// Padding isn't written, but a tool that re-encoded the code may have added it back.
			deflated = Base64.getUrlDecoder().decode(m.group(1).replace("=", ""));
		} catch (IllegalArgumentException e) {
			return null;
		}
		byte[] payload = inflate(deflated);
		if (payload == null) {
			return null;
		}

		String name = null;
		List<Project.MaterialEntry> materials = new ArrayList<>();
		Map<String, Project.MaterialEntry> byItem = new LinkedHashMap<>();
		Map<String, String> replacements = new LinkedHashMap<>();
		for (String line : new String(payload, StandardCharsets.UTF_8).split("\n")) {
			// Anything else is from a newer version of the format, and is skipped rather than failing the whole code.
			if (line.startsWith("N ")) {
				name = line.substring(2).strip();
			} else if (line.startsWith("M ")) {
				material(line.substring(2), materials, byItem);
			} else if (line.startsWith("R ")) {
				String[] parts = line.substring(2).strip().split(" ");
				if (parts.length == 2) {
					replacements.put(longId(parts[0]), longId(parts[1]));
				}
			}
		}
		if (materials.isEmpty()) {
			return null;
		}
		return new Decoded(name == null || name.isBlank() ? null : name, materials, replacements);
	}

	/** One "<id> <count>[ x]" record. Amounts for an item already in the code are added to it. */
	private static void material(String record, List<Project.MaterialEntry> materials, Map<String, Project.MaterialEntry> byItem) {
		String[] parts = record.strip().split(" ");
		if (parts.length < 2 || parts[0].isEmpty()) {
			return;
		}
		int count;
		try {
			count = (int) Math.min(Integer.MAX_VALUE, Long.parseLong(parts[1]));
		} catch (NumberFormatException e) {
			return;
		}
		if (count <= 0) {
			return;
		}
		String id = longId(parts[0]);
		Project.MaterialEntry existing = byItem.get(id);
		if (existing != null) {
			existing.count = MaterialParser.addClamped(existing.count, count);
			return;
		}
		if (materials.size() >= MAX_MATERIALS) {
			return;
		}
		Project.MaterialEntry entry = new Project.MaterialEntry(id, count);
		entry.crossedOff = parts.length > 2 && parts[2].equals("x");
		materials.add(entry);
		byItem.put(id, entry);
	}

	/** Vanilla ids lose their namespace, which is most of a code's payload before it's compressed. */
	private static String shortId(String id) {
		return id.startsWith(NAMESPACE) ? id.substring(NAMESPACE.length()) : id;
	}

	private static String longId(String id) {
		return id.indexOf(':') < 0 ? NAMESPACE + id : id;
	}

	/** A name goes on one line of the payload, and in one comment line of an exported file. */
	private static String oneLine(String text) {
		return text.replaceAll("\\s+", " ").strip();
	}

	private static byte[] deflate(byte[] data) {
		Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
		deflater.setInput(data);
		deflater.finish();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[4096];
		try {
			while (!deflater.finished()) {
				out.write(buffer, 0, deflater.deflate(buffer));
			}
		} finally {
			deflater.end();
		}
		return out.toByteArray();
	}

	/** Inflates a code's payload, or returns null if it's damaged or would be far larger than any real list. */
	private static byte @Nullable [] inflate(byte[] data) {
		Inflater inflater = new Inflater();
		inflater.setInput(data);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[4096];
		try {
			while (!inflater.finished()) {
				int read = inflater.inflate(buffer);
				// Nothing left to read from a stream that hasn't finished: the code was cut short.
				if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
					return null;
				}
				if (out.size() + read > MAX_PAYLOAD) {
					return null;
				}
				out.write(buffer, 0, read);
			}
		} catch (DataFormatException e) {
			return null;
		} finally {
			inflater.end();
		}
		return out.toByteArray();
	}
}
