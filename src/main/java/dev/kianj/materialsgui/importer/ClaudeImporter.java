package dev.kianj.materialsgui.importer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import javax.imageio.ImageIO;

/**
 * Sends a screenshot of a material list to Claude through the Messages API and returns "count item_id" lines. Uses
 * Java's HTTP client and the Gson that Minecraft ships, so the mod doesn't need to bundle an SDK.
 */
public final class ClaudeImporter {
	private static final URI MESSAGES_URL = URI.create("https://api.anthropic.com/v1/messages");
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
	/** Keeps the long edge within what the API processes at full resolution. */
	private static final int MAX_EDGE = 2576;
	private static final int MAX_BYTES = 3_750_000;

	private static final String SYSTEM_PROMPT = """
		You transcribe Minecraft build material lists from images: Litematica material lists, spreadsheets, \
		chat messages, or handwritten notes.

		For each material, give:
		- item: the Minecraft Java Edition item ID, e.g. minecraft:oak_planks or minecraft:stone_bricks. If you can't \
		map a name to an ID confidently, use the name exactly as shown.
		- count: the total number of individual items. Convert stacks (64 for most items, 16 for signs, banners, \
		snowballs, eggs, ender pearls, buckets, 1 for unstackable items), shulker boxes (27 stacks), and double \
		chests (54 stacks) into items. If the list has separate Total/Missing/Available columns, use Total.

		Skip headers, totals rows, and anything that isn't a material. Merge duplicate items into one entry. \
		Return an empty list if the image contains no material list.""";

	private ClaudeImporter() {}

	public static CompletableFuture<String> extract(Path image, String apiKey, String model) {
		return CompletableFuture.supplyAsync(() -> encode(image))
			.thenCompose(encoded -> HTTP.sendAsync(request(encoded, apiKey, model), HttpResponse.BodyHandlers.ofString()))
			.thenApply(response -> parseResponse(response.statusCode(), response.body()))
			.exceptionally(error -> {
				Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
				if (cause instanceof ImportException e) {
					throw e;
				}
				if (cause instanceof HttpTimeoutException) {
					throw new ImportException("The Anthropic API took too long to answer. Try again.");
				}
				if (cause instanceof IOException) {
					throw new ImportException("Couldn't reach the Anthropic API. Check your internet connection.");
				}
				// Only the kind of error: a message can contain the API key (an invalid header value is quoted in full).
				throw new ImportException("Screenshot import failed (" + cause.getClass().getSimpleName() + "). Try again.");
			});
	}

	private static HttpRequest request(EncodedImage image, String apiKey, String model) {
		HttpRequest.Builder request = HttpRequest.newBuilder(MESSAGES_URL)
			// The reply isn't streamed, so this covers all of it, which can take several minutes for a long list.
			.timeout(Duration.ofMinutes(10))
			.header("content-type", "application/json")
			.header("x-api-key", apiKey)
			.header("anthropic-version", "2023-06-01");
		if (usesFallbacks(model)) {
			request.header("anthropic-beta", "server-side-fallback-2026-07-01");
		}
		return request.POST(HttpRequest.BodyPublishers.ofString(requestBody(image.base64, image.mediaType, model).toString())).build();
	}

	/** The refusal fallback is only offered for these models; on others the parameter would be rejected. */
	private static boolean usesFallbacks(String model) {
		return model.equals("claude-opus-5") || model.startsWith("claude-fable-5");
	}

	/** The Messages API request: the image, an instruction, and a JSON schema the answer must follow. */
	public static JsonObject requestBody(String base64, String mediaType, String model) {
		JsonObject source = new JsonObject();
		source.addProperty("type", "base64");
		source.addProperty("media_type", mediaType);
		source.addProperty("data", base64);
		JsonObject imageBlock = new JsonObject();
		imageBlock.addProperty("type", "image");
		imageBlock.add("source", source);
		JsonObject textBlock = new JsonObject();
		textBlock.addProperty("type", "text");
		textBlock.addProperty("text", "Transcribe the material list in this image.");
		JsonArray content = new JsonArray();
		content.add(imageBlock);
		content.add(textBlock);
		JsonObject message = new JsonObject();
		message.addProperty("role", "user");
		message.add("content", content);
		JsonArray messages = new JsonArray();
		messages.add(message);

		JsonObject format = new JsonObject();
		format.addProperty("type", "json_schema");
		format.add("schema", schema());
		JsonObject outputConfig = new JsonObject();
		outputConfig.add("format", format);

		JsonObject body = new JsonObject();
		body.addProperty("model", model);
		body.addProperty("max_tokens", 16000);
		body.addProperty("system", SYSTEM_PROMPT);
		body.add("output_config", outputConfig);
		body.add("messages", messages);
		if (usesFallbacks(model)) {
			// If Claude's safety classifiers decline the request, the API retries it on Anthropic's recommended fallback model.
			body.addProperty("fallbacks", "default");
		}
		return body;
	}

	private static JsonObject schema() {
		JsonObject material = JsonParser.parseString("""
			{"type": "object",
			 "properties": {"item": {"type": "string"}, "count": {"type": "integer"}},
			 "required": ["item", "count"],
			 "additionalProperties": false}""").getAsJsonObject();
		JsonObject materials = new JsonObject();
		materials.addProperty("type", "array");
		materials.add("items", material);
		JsonObject properties = new JsonObject();
		properties.add("materials", materials);
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("properties", properties);
		JsonArray required = new JsonArray();
		required.add("materials");
		schema.add("required", required);
		schema.addProperty("additionalProperties", false);
		return schema;
	}

	/** Turns an API response into "count item" lines, or throws an {@link ImportException} with a readable reason. */
	public static String parseResponse(int status, String body) {
		if (status != 200) {
			throw new ImportException(switch (status) {
				case 401 -> "Invalid Anthropic API key.";
				case 403 -> "This API key isn't allowed to do that: " + errorMessage(body);
				case 413 -> "The screenshot is too large.";
				case 429 -> "Rate limited by the Anthropic API. Try again in a minute.";
				case 529 -> "The Anthropic API is overloaded. Try again shortly.";
				default -> "Anthropic API error " + status + ": " + errorMessage(body);
			});
		}
		try {
			JsonObject message = JsonParser.parseString(body).getAsJsonObject();
			String stopReason = message.has("stop_reason") && !message.get("stop_reason").isJsonNull() ? message.get("stop_reason").getAsString() : "";
			if (stopReason.equals("refusal")) {
				throw new ImportException("Claude declined to read this image.");
			}
			if (stopReason.equals("max_tokens")) {
				throw new ImportException("The list was too long to read in one go. Try a screenshot of part of it.");
			}
			StringBuilder json = new StringBuilder();
			for (JsonElement block : message.getAsJsonArray("content")) {
				JsonObject b = block.getAsJsonObject();
				if (b.has("type") && b.get("type").getAsString().equals("text")) {
					json.append(b.get("text").getAsString());
				}
			}
			return toLines(json.toString());
		} catch (JsonParseException | IllegalStateException | NullPointerException | UnsupportedOperationException | NumberFormatException e) {
			throw new ImportException("Claude's reply couldn't be read. Try again.");
		}
	}

	private static String errorMessage(String body) {
		try {
			return JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("error").get("message").getAsString();
		} catch (RuntimeException e) {
			return body.length() > 200 ? body.substring(0, 200) + "..." : body;
		}
	}

	private static String toLines(String json) {
		JsonObject root = JsonParser.parseString(json).getAsJsonObject();
		StringBuilder sb = new StringBuilder();
		for (JsonElement el : root.getAsJsonArray("materials")) {
			JsonObject o = el.getAsJsonObject();
			sb.append(o.get("count").getAsLong()).append(' ').append(o.get("item").getAsString()).append('\n');
		}
		return sb.toString();
	}

	private record EncodedImage(String base64, String mediaType) {}

	private static EncodedImage encode(Path path) {
		BufferedImage img;
		try {
			img = ImageIO.read(path.toFile());
		} catch (IOException e) {
			throw new ImportException("Couldn't read image: " + e.getMessage());
		}
		if (img == null) {
			throw new ImportException("Unsupported image format. Use PNG or JPG.");
		}

		int w = img.getWidth();
		int h = img.getHeight();
		double scale = Math.min(1.0, (double) MAX_EDGE / Math.max(w, h));
		int nw = Math.max(1, (int) Math.round(w * scale));
		int nh = Math.max(1, (int) Math.round(h * scale));
		BufferedImage rgb = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = rgb.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g.drawImage(img, 0, 0, nw, nh, java.awt.Color.WHITE, null);
		g.dispose();

		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(rgb, "png", out);
			if (out.size() <= MAX_BYTES) {
				return new EncodedImage(Base64.getEncoder().encodeToString(out.toByteArray()), "image/png");
			}
			out.reset();
			ImageIO.write(rgb, "jpg", out);
			return new EncodedImage(Base64.getEncoder().encodeToString(out.toByteArray()), "image/jpeg");
		} catch (IOException e) {
			throw new ImportException("Couldn't encode image: " + e.getMessage());
		}
	}

	public static final class ImportException extends RuntimeException {
		public ImportException(String message) {
			super(message);
		}
	}
}
