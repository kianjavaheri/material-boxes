package dev.kianj.materialsgui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.stream.Stream;
import com.google.gson.JsonObject;
import dev.kianj.materialsgui.importer.ClaudeImporter;
import java.util.Map;
import net.minecraft.world.item.ItemStack;
import dev.kianj.materialsgui.data.BoxKey;
import dev.kianj.materialsgui.data.Layout;
import dev.kianj.materialsgui.data.Project;
import dev.kianj.materialsgui.data.ProjectStore;
import dev.kianj.materialsgui.data.SavedLists;
import dev.kianj.materialsgui.importer.ListShare;
import dev.kianj.materialsgui.importer.MaterialParser;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MaterialParserTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		// In game, item components (max stack size, names) are bound when registries load; do the same here.
		BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createLookup())
			.forEach(DataComponentInitializers.PendingComponents::apply);
	}

	private static int count(MaterialParser.Result r, Item item) {
		return r.materials().getOrDefault(item, 0);
	}

	@Test
	void freeForm() {
		MaterialParser.Result r = MaterialParser.parse("""
			64 stone
			3x oak_planks
			Glass: 128
			stone bricks - 5 stacks + 12
			2 sb cobblestone
			1,000 dirt, 12 torches
			- Minecraft:smooth_stone x 40
			sand 3 st""");
		assertEquals(64, count(r, Items.STONE));
		assertEquals(3, count(r, Items.OAK_PLANKS));
		assertEquals(128, count(r, Items.GLASS));
		assertEquals(5 * 64 + 12, count(r, Items.STONE_BRICKS));
		assertEquals(2 * 27 * 64, count(r, Items.COBBLESTONE));
		assertEquals(1000, count(r, Items.DIRT));
		assertEquals(12, count(r, Items.TORCH));
		assertEquals(40, count(r, Items.SMOOTH_STONE));
		assertEquals(3 * 64, count(r, Items.SAND));
		assertTrue(r.unresolved().isEmpty(), () -> "unresolved: " + r.unresolved());
	}

	@Test
	void stacksUseItemStackSize() {
		MaterialParser.Result r = MaterialParser.parse("2 stacks ender pearls");
		assertEquals(32, count(r, Items.ENDER_PEARL));
	}

	@Test
	void litematicaTable() {
		MaterialParser.Result r = MaterialParser.parse("""
			+-------------------+-------+---------+-----------+
			| Material List for placement 'house'             |
			+-------------------+-------+---------+-----------+
			| Item              | Total | Missing | Available |
			+-------------------+-------+---------+-----------+
			| Piston            |    15 |       0 |         0 |
			| Oak Planks        |  1234 |      10 |         0 |
			| Stone Brick Stairs|   300 |     300 |         0 |
			+-------------------+-------+---------+-----------+""");
		assertEquals(15, count(r, Items.PISTON));
		assertEquals(1234, count(r, Items.OAK_PLANKS));
		assertEquals(300, count(r, Items.STONE_BRICK_STAIRS));
		assertTrue(r.unresolved().isEmpty(), () -> "unresolved: " + r.unresolved());
	}

	@Test
	void litematicaCsv() {
		MaterialParser.Result r = MaterialParser.parse("""
			"Item","Total","Missing","Available"
			"Oak Planks","200","0","0"
			"White Concrete","1,500","0","0\"""");
		assertEquals(200, count(r, Items.OAK_PLANKS));
		assertEquals(1500, count(r, BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace("white_concrete"))));
	}

	@Test
	void unknownNamesAreReported() {
		MaterialParser.Result r = MaterialParser.parse("5 flurbleblock\n10 glowstone");
		assertEquals(10, count(r, Items.GLOWSTONE));
		assertEquals(1, r.unresolved().size());
	}

	private static Project.BoxEntry box(Project project, BoxKey key, int size, Object... slotItemCount) {
		Project.BoxEntry box = new Project.BoxEntry(key, size);
		for (int i = 0; i < slotItemCount.length; i += 3) {
			int slot = (Integer) slotItemCount[i];
			box.slotItems[slot] = (String) slotItemCount[i + 1];
			box.slotCounts[slot] = (Integer) slotItemCount[i + 2];
		}
		project.boxes.add(box);
		return box;
	}

	private static final BoxKey A = new BoxKey("minecraft:overworld", 0, 64, 0);
	private static final BoxKey B = new BoxKey("minecraft:overworld", 1, 64, 0);

	@Test
	void emptyBoxGetsGhostsInListOrder() {
		Project project = new Project();
		project.materials.add(new Project.MaterialEntry("minecraft:stone", 100));
		project.materials.add(new Project.MaterialEntry("minecraft:ender_pearl", 20));
		box(project, A, 27);

		Layout layout = Layout.compute(project);
		assertEquals(new Layout.SlotPlan(Items.STONE, 64), layout.plan(A, 0));
		assertEquals(new Layout.SlotPlan(Items.STONE, 36), layout.plan(A, 1));
		assertEquals(new Layout.SlotPlan(Items.ENDER_PEARL, 16), layout.plan(A, 2));
		assertEquals(new Layout.SlotPlan(Items.ENDER_PEARL, 4), layout.plan(A, 3));
		assertNull(layout.plan(A, 4));
		assertEquals(0, layout.missingSlots());
	}

	@Test
	void itemsAlreadyInTheBoxCountAndStayWhereTheyAre() {
		Project project = new Project();
		project.materials.add(new Project.MaterialEntry("minecraft:stone", 100));
		project.materials.add(new Project.MaterialEntry("minecraft:oak_planks", 64));
		box(project, A, 27, 5, "minecraft:stone", 64);

		Layout layout = Layout.compute(project);
		assertEquals(64, layout.stored(Items.STONE));
		assertEquals(new Layout.SlotPlan(Items.STONE, 64), layout.plan(A, 5));
		assertEquals(new Layout.SlotPlan(Items.STONE, 36), layout.plan(A, 0));
		assertEquals(new Layout.SlotPlan(Items.OAK_PLANKS, 64), layout.plan(A, 1));
		assertNull(layout.plan(A, 2));
	}

	@Test
	void partialStacksAreToppedUpBeforeNewGhosts() {
		Project project = new Project();
		project.materials.add(new Project.MaterialEntry("minecraft:stone", 100));
		box(project, A, 27, 3, "minecraft:stone", 30);

		Layout layout = Layout.compute(project);
		assertEquals(new Layout.SlotPlan(Items.STONE, 64), layout.plan(A, 3));
		assertEquals(new Layout.SlotPlan(Items.STONE, 36), layout.plan(A, 0));
		assertNull(layout.plan(A, 1));
	}

	@Test
	void finishedMaterialsAndUnlistedItemsGetNoGhosts() {
		Project project = new Project();
		project.materials.add(new Project.MaterialEntry("minecraft:glass", 10));
		box(project, A, 27, 0, "minecraft:dirt", 7, 4, "minecraft:glass", 10);

		Layout layout = Layout.compute(project);
		assertNull(layout.plan(A, 0));
		assertEquals(new Layout.SlotPlan(Items.GLASS, 10), layout.plan(A, 4));
		for (int i = 0; i < 27; i++) {
			if (i != 4) {
				assertNull(layout.plan(A, i), "slot " + i);
			}
		}
	}

	@Test
	void ghostsSpillIntoNextBoxAndMissingSlotsAreReported() {
		Project project = new Project();
		project.materials.add(new Project.MaterialEntry("minecraft:stone", 64 * 30));
		box(project, A, 27);

		Layout layout = Layout.compute(project);
		assertEquals(3, layout.missingSlots());
		assertEquals(3 * 64, layout.unplaced().get(Items.STONE));

		box(project, B, 27);
		layout = Layout.compute(project);
		assertTrue(layout.unplaced().isEmpty());
		assertEquals(new Layout.SlotPlan(Items.STONE, 64), layout.plan(B, 2));
		assertNull(layout.plan(B, 3));
	}

	@Test
	void extraItemsAreTakenOffTheLastStacks() {
		Project project = new Project();
		project.materials.add(new Project.MaterialEntry("minecraft:glass", 5));
		project.materials.add(new Project.MaterialEntry("minecraft:stone", 64));
		box(project, A, 27, 0, "minecraft:glass", 7, 1, "minecraft:stone", 30, 2, "minecraft:stone", 64);

		Layout layout = Layout.compute(project);
		assertEquals(new Layout.SlotPlan(Items.GLASS, 5), layout.plan(A, 0)); // reads 7/5
		assertEquals(new Layout.SlotPlan(Items.STONE, 30), layout.plan(A, 1));
		assertEquals(new Layout.SlotPlan(Items.STONE, 34), layout.plan(A, 2)); // 94 stored, 30 extra
		assertNull(layout.plan(A, 3));
		assertEquals(94, layout.stored(Items.STONE));
		assertEquals(64, layout.needed(Items.STONE));
	}

	@Test
	void savesFromVersionOneStillLoad() {
		// 1.0.0 stored per-box totals ("lastSeen") and no per-slot contents.
		String json = """
			{"materials":[{"item":"minecraft:stone","count":100}],
			 "boxes":[{"dimension":"minecraft:overworld","x":928,"y":106,"z":-237,"size":27,"lastSeen":{"minecraft:stone":5}}]}""";
		Project project = new Gson().fromJson(json, Project.class);
		Project.BoxEntry box = project.boxes.getFirst();

		Layout layout = Layout.compute(project);
		assertEquals(0, layout.stored(Items.STONE));
		assertEquals(new Layout.SlotPlan(Items.STONE, 64), layout.plan(box.key(), 0));
		assertEquals(new Layout.SlotPlan(Items.STONE, 36), layout.plan(box.key(), 1));

		box.resize(54);
		assertTrue(box.isEmptyAt(53));
		assertEquals(54, Layout.compute(project).plans(box.key()).length);
	}

	@Test
	void pickedUpShulkerStillCountsButGetsNoGhosts() {
		Project project = new Project();
		project.materials.add(new Project.MaterialEntry("minecraft:stone", 100));
		Project.BoxEntry shulker = box(project, A, 27, 0, "minecraft:stone", 64);
		shulker.block = "minecraft:red_shulker_box";
		shulker.pickedUp = true;
		box(project, B, 27);

		Layout layout = Layout.compute(project);
		assertEquals(64, layout.stored(Items.STONE));
		assertNull(layout.plan(A, 1));
		assertEquals(new Layout.SlotPlan(Items.STONE, 36), layout.plan(B, 0));
		// It isn't at its old position any more, so a new container there isn't mistaken for it.
		assertEquals(-1, project.indexOfBox(A));
		assertEquals(1, project.indexOfBox(B));
		assertTrue(shulker.isShulker());
	}

	@Test
	void pickedUpShulkerReattachesOnlyWithSameColourAndContents() {
		Project project = new Project();
		Project.BoxEntry shulker = box(project, A, 27, 0, "minecraft:stone", 64, 5, "minecraft:glass", 3);
		shulker.block = "minecraft:red_shulker_box";
		shulker.pickedUp = true;

		String[] items = new String[27];
		int[] counts = new int[27];
		items[0] = "minecraft:stone";
		counts[0] = 64;
		items[5] = "minecraft:glass";
		counts[5] = 3;
		BoxKey moved = new BoxKey("minecraft:overworld", 9, 70, 9);

		assertEquals(-1, project.reattach(moved, "minecraft:blue_shulker_box", items, counts));
		counts[5] = 2;
		assertEquals(-1, project.reattach(moved, "minecraft:red_shulker_box", items, counts));
		counts[5] = 3;
		assertEquals(0, project.reattach(moved, "minecraft:red_shulker_box", items, counts));
		assertEquals(moved, shulker.key());
		assertEquals(0, project.indexOfBox(moved));
	}

	@Test
	void otherListsCopiesOfAReattachedShulkerMatchByWhereTheyWereLastSeen() {
		Project project = new Project();
		// Another list's copy of the box, recorded before 3 more glass went in.
		Project.BoxEntry stale = box(project, A, 27, 0, "minecraft:stone", 64);
		stale.block = "minecraft:red_shulker_box";
		stale.pickedUp = true;

		String[] items = new String[27];
		int[] counts = new int[27];
		items[0] = "minecraft:stone";
		counts[0] = 64;
		items[5] = "minecraft:glass";
		counts[5] = 3;

		assertEquals(-1, Project.findPickedUp(project.boxes, "minecraft:red_shulker_box", items, counts, null));
		assertEquals(-1, Project.findPickedUp(project.boxes, "minecraft:red_shulker_box", items, counts, B));
		assertEquals(-1, Project.findPickedUp(project.boxes, "minecraft:blue_shulker_box", items, counts, A));
		assertEquals(0, Project.findPickedUp(project.boxes, "minecraft:red_shulker_box", items, counts, A));
		stale.placeAt(B);
		assertFalse(stale.pickedUp);
		assertEquals(0, project.indexOfBox(B));
	}

	@Test
	void crossedOffMaterialsStayOnTheListButArentNeeded() {
		Project project = new Project();
		project.materials.add(new Project.MaterialEntry("minecraft:stone", 100));
		project.materials.add(new Project.MaterialEntry("minecraft:glass", 10));
		project.materials.getFirst().crossedOff = true;
		box(project, A, 27, 0, "minecraft:stone", 5);

		Layout layout = Layout.compute(project);
		assertEquals(0, layout.needed(Items.STONE));
		assertEquals(5, layout.stored(Items.STONE));
		// No highlight on the stone that's there, and no ghosts for the rest of it.
		assertNull(layout.plan(A, 0));
		assertEquals(new Layout.SlotPlan(Items.GLASS, 10), layout.plan(A, 1));
		assertNull(layout.plan(A, 2));
		assertFalse(layout.missingText(project).contains("Stone"));
		assertTrue(layout.missingText(project).contains("Glass"));
		assertEquals(2, project.materials.size());
	}

	@Test
	void crossingOffIsSavedButIsntAnUnsavedChange() {
		Project project = new Project();
		project.materials.add(new Project.MaterialEntry("minecraft:stone", 10));
		project.materials.getFirst().crossedOff = true;
		SavedLists.SavedList list = SavedLists.snapshot("Castle", project);
		assertTrue(list.materials.getFirst().crossedOff);
		project.materials.getFirst().crossedOff = false;
		assertTrue(SavedLists.matches(list, project));
	}

	@Test
	void mergingMaterialsKeepsThemNeededUnlessBothWereCrossedOff() {
		Project project = new Project();
		project.materials.add(new Project.MaterialEntry("minecraft:oak_wood", 5));
		project.materials.add(new Project.MaterialEntry("minecraft:oak_log", 10));
		project.materials.get(0).crossedOff = true;
		project.replaceMaterial(0, "minecraft:oak_log", 5);
		assertEquals(1, project.materials.size());
		assertFalse(project.materials.getFirst().crossedOff);
	}

	@Test
	void replacingAMaterialKeepsItsAmountOrMergesWithTheSameItem() {
		Project project = new Project();
		project.materials.add(new Project.MaterialEntry("minecraft:oak_wood", 95));
		project.materials.add(new Project.MaterialEntry("minecraft:stone", 10));
		project.materials.add(new Project.MaterialEntry("minecraft:cobblestone", 5));

		project.replaceMaterial(0, "minecraft:oak_log", 95);
		assertEquals("minecraft:oak_log", project.materials.get(0).item);
		assertEquals(95, project.materials.get(0).count);

		project.replaceMaterial(2, "minecraft:stone", 5);
		assertEquals(2, project.materials.size());
		assertEquals(15, project.materials.get(1).count);
	}

	@Test
	void replacementsAreRememberedFollowedAndUndone() {
		Project project = new Project();
		project.materials.add(new Project.MaterialEntry("minecraft:oak_wood", 95));

		project.replaceMaterial(0, "minecraft:oak_log", 95);
		assertEquals("minecraft:oak_log", project.replacementFor("minecraft:oak_wood"));
		project.replaceMaterial(0, "minecraft:spruce_log", 95);
		assertEquals("minecraft:spruce_log", project.replacementFor("minecraft:oak_wood"));
		assertEquals(1, project.replacements.size());
		project.replaceMaterial(0, "minecraft:oak_wood", 95);
		assertTrue(project.replacements.isEmpty());
		assertEquals("minecraft:stone", project.replacementFor("minecraft:stone"));
	}

	@Test
	void savedListsCopyTheMaterialsAndReplacements() {
		Project project = new Project();
		project.materials.add(new Project.MaterialEntry("minecraft:stone", 10));
		project.replacements.put("minecraft:oak_wood", "minecraft:oak_log");

		SavedLists.SavedList list = SavedLists.snapshot("Castle walls", project);
		assertTrue(SavedLists.matches(list, project));
		project.materials.getFirst().count = 11;
		assertFalse(SavedLists.matches(list, project));
		assertEquals(10, list.materials.getFirst().count);
		assertEquals("minecraft:oak_log", list.replacements.get("minecraft:oak_wood"));
	}

	@Test
	void eachSavedListHasItsOwnBoxesInEachWorld() {
		Project project = new Project();
		project.materials.add(new Project.MaterialEntry("minecraft:stone", 10));
		box(project, A, 27);
		SavedLists.SavedList castle = SavedLists.snapshot("Castle", project);
		assertTrue(SavedLists.putBoxes(castle, "sp_world", project.boxes));
		assertFalse(SavedLists.putBoxes(castle, "sp_world", project.boxes));

		project.boxes.clear();
		box(project, B, 27);
		SavedLists.SavedList garden = SavedLists.snapshot("Garden", project);
		SavedLists.putBoxes(garden, "sp_world", project.boxes);

		// Switching lists switches boxes.
		assertTrue(SavedLists.open(castle, project, "sp_world"));
		assertEquals(A, project.boxes.getFirst().key());
		assertTrue(SavedLists.open(garden, project, "sp_world"));
		assertEquals(B, project.boxes.getFirst().key());
		// A list with no boxes in a world yet keeps the current ones.
		assertFalse(SavedLists.open(castle, project, "mp_server"));
		assertEquals(B, project.boxes.getFirst().key());
		// The project gets copies, so changing its boxes doesn't change the saved list's.
		project.boxes.getFirst().pickedUp = true;
		assertFalse(garden.boxes.get("sp_world").getFirst().pickedUp);

		assertEquals(List.of("Garden"), SavedLists.otherListsUsing(List.of(castle, garden), B, "sp_world", "Castle"));
		assertEquals(List.of(), SavedLists.otherListsUsing(List.of(castle, garden), B, "mp_server", "Castle"));
	}

	// ---- Limits ----

	@Test
	void hugeAmountsAreCappedInsteadOfCrashing() {
		assertEquals(Integer.MAX_VALUE, MaterialParser.parse("99999999999999999999999 stone").materials().get(Items.STONE));
		assertEquals(Integer.MAX_VALUE, MaterialParser.parse("999999999999 stacks of stone").materials().get(Items.STONE));
		assertEquals(Integer.MAX_VALUE, MaterialParser.parse("99999999999 x 99999999999 stone").materials().get(Items.STONE));
		assertEquals(Integer.MAX_VALUE, MaterialParser.parse("9999999999 shulker boxes of stone").materials().get(Items.STONE));
		assertEquals(Integer.MAX_VALUE, MaterialParser.parse("2000000000 stone\n2000000000 stone").materials().get(Items.STONE));
		assertEquals(Integer.MAX_VALUE, MaterialParser.parse("| Stone | 99999999999999999999 | 0 | 0 |").materials().get(Items.STONE));
		assertEquals(Integer.MAX_VALUE, MaterialParser.addClamped(Integer.MAX_VALUE, 5));
	}

	@Test
	void byteOrderMarksAndWindowsLineEndingsAreIgnored() {
		String csv = "\uFEFF\"Item\",\"Total\",\"Missing\",\"Available\"\r\n\"Oak Planks\",\"1,024\",\"0\",\"0\"\r\n\"Stone\",\"64\",\"0\",\"0\"\r\n";
		Map<Item, Integer> materials = MaterialParser.parse(csv).materials();
		assertEquals(1024, materials.get(Items.OAK_PLANKS));
		assertEquals(64, materials.get(Items.STONE));
		assertEquals(2, materials.size());
	}

	@Test
	void bigExportsParseQuickly() {
		StringBuilder sb = new StringBuilder("+------+\n| Item | Total | Missing | Available |\n+------+\n");
		// Cobblestone is left out of the table so its total comes only from the free-form lines below.
		BuiltInRegistries.ITEM.stream().filter(i -> i != Items.AIR && i != Items.COBBLESTONE).limit(1500)
			.forEach(item -> sb.append("| ").append(new ItemStack(item).getHoverName().getString()).append(" | 12345 | 0 | 0 |\n"));
		for (int i = 0; i < 10_000; i++) {
			sb.append(i % 5 + 1).append(" stacks cobblestone\n");
		}
		long start = System.nanoTime();
		MaterialParser.Result result = MaterialParser.parse(sb.toString());
		long ms = (System.nanoTime() - start) / 1_000_000;
		assertTrue(ms < 5000, "Parsing took " + ms + " ms");
		assertTrue(result.materials().size() > 500, "Only " + result.materials().size() + " materials resolved");
		assertEquals(30_000 * 64, result.materials().get(Items.COBBLESTONE));
	}

	@Test
	void veryLongSingleLinePastesParseQuickly() {
		long start = System.nanoTime();
		MaterialParser.Result result = MaterialParser.parse("64 stone, ".repeat(20_000));
		long ms = (System.nanoTime() - start) / 1_000_000;
		assertTrue(ms < 5000, "Parsing took " + ms + " ms");
		assertEquals(20_000 * 64, result.materials().get(Items.STONE));
	}

	@Test
	void bigProjectsLayOutQuickly() {
		Project project = new Project();
		List<Item> items = BuiltInRegistries.ITEM.stream().filter(i -> i != Items.AIR).limit(500).toList();
		for (Item item : items) {
			project.materials.add(new Project.MaterialEntry(BuiltInRegistries.ITEM.getKey(item).toString(), 5000));
		}
		for (int b = 0; b < 200; b++) {
			Project.BoxEntry box = box(project, new BoxKey("minecraft:overworld", b, 64, 0), 54);
			for (int i = 0; i < 54; i += 2) {
				Item item = items.get((b * 54 + i) % items.size());
				box.slotItems[i] = BuiltInRegistries.ITEM.getKey(item).toString();
				box.slotCounts[i] = Math.max(1, item.getDefaultMaxStackSize() / 2);
			}
		}
		long start = System.nanoTime();
		Layout layout = Layout.compute(project);
		long ms = (System.nanoTime() - start) / 1_000_000;
		assertTrue(ms < 1000, "Layout took " + ms + " ms");
		assertTrue(layout.missingSlots() > 0);
	}

	@Test
	void hugeAmountsDontOverflowTheLayout() {
		Project project = new Project();
		project.materials.add(new Project.MaterialEntry("minecraft:stone", Integer.MAX_VALUE));
		project.materials.add(new Project.MaterialEntry("minecraft:stone", Integer.MAX_VALUE));
		project.materials.add(new Project.MaterialEntry("minecraft:dirt", Integer.MAX_VALUE));
		box(project, A, 27, 0, "minecraft:stone", 64);
		Layout layout = Layout.compute(project);
		assertEquals(Integer.MAX_VALUE, layout.needed(Items.STONE));
		assertTrue(layout.missingSlots() > 0);
		assertEquals(new Layout.SlotPlan(Items.STONE, 64), layout.plan(A, 1));
	}

	@Test
	void shulkerBoxContentsCountTowardTheList() {
		Project project = new Project();
		project.materials.add(new Project.MaterialEntry("minecraft:stone", 100));
		Project.BoxEntry box = box(project, A, 27, 0, "minecraft:shulker_box", 1);
		assertTrue(box.setContents(box.slotItems, box.slotCounts, Map.of("minecraft:stone", 64)));
		assertFalse(box.setContents(box.slotItems, box.slotCounts, Map.of("minecraft:stone", 64)));

		Layout layout = Layout.compute(project);
		assertEquals(64, layout.stored(Items.STONE));
		assertNull(layout.plan(A, 0));
		assertEquals(new Layout.SlotPlan(Items.STONE, 36), layout.plan(A, 1));
		assertEquals(64, box.copy().nested.get("minecraft:stone"));
	}

	@Test
	void copiedMissingListPastesBackIn() {
		Project project = new Project();
		project.listName = "Castle";
		project.materials.add(new Project.MaterialEntry("minecraft:stone", 100));
		project.materials.add(new Project.MaterialEntry("minecraft:glass", 10));
		box(project, A, 27, 0, "minecraft:stone", 64, 1, "minecraft:glass", 10);
		String text = Layout.compute(project).missingText(project);
		assertEquals("Still needed for Castle:\n36 Stone\n", text);
		assertEquals(Map.of(Items.STONE, 36), MaterialParser.parse(text).materials());
	}

	@Test
	void damagedSavesAreCleanedUp() {
		Project project = new Gson().fromJson("""
			{"materials": [null, {"item": null, "count": 5}, {"item": "minecraft:stone", "count": -3}, {"item": "minecraft:dirt", "count": 4}],
			 "boxes": [null, {"dimension": "minecraft:overworld", "x": 0, "y": 64, "z": 0, "size": 27, "slotItems": ["minecraft:dirt"]}],
			 "replacements": null}""", Project.class);
		project.sanitize();
		assertEquals(1, project.materials.size());
		assertEquals(1, project.boxes.size());
		assertEquals(27, project.boxes.getFirst().slotItems.length);
		assertEquals(27, project.boxes.getFirst().slotCounts.length);
		assertEquals("minecraft:dirt", project.boxes.getFirst().slotItems[0]);
		assertTrue(project.replacements.isEmpty());
		Layout.compute(project);

		SavedLists.SavedList broken = new Gson().fromJson("{\"name\": \"Walls\", \"materials\": null, \"boxes\": {\"sp_x\": null}}", SavedLists.SavedList.class);
		assertTrue(broken.sanitize());
		assertTrue(broken.materials.isEmpty());
		assertTrue(broken.boxes.isEmpty());
		assertFalse(new Gson().fromJson("{\"materials\": []}", SavedLists.SavedList.class).sanitize());
	}

	// ---- Screenshot import (no network: only the request and response handling) ----

	private static String reply(String stopReason, String text) {
		JsonObject block = new JsonObject();
		block.addProperty("type", "text");
		block.addProperty("text", text);
		JsonObject thinking = new JsonObject();
		thinking.addProperty("type", "thinking");
		thinking.addProperty("thinking", "");
		com.google.gson.JsonArray content = new com.google.gson.JsonArray();
		content.add(thinking);
		content.add(block);
		JsonObject message = new JsonObject();
		message.addProperty("stop_reason", stopReason);
		message.add("content", content);
		return message.toString();
	}

	@Test
	void claudeRepliesBecomeListLines() {
		String json = "{\"materials\": [{\"item\": \"minecraft:stone\", \"count\": 128}, {\"item\": \"Oak Planks\", \"count\": 3}]}";
		assertEquals("128 minecraft:stone\n3 Oak Planks\n", ClaudeImporter.parseResponse(200, reply("end_turn", json)));
		assertEquals("", ClaudeImporter.parseResponse(200, reply("end_turn", "{\"materials\": []}")));
	}

	@Test
	void claudeProblemsBecomeReadableMessages() {
		assertEquals("Invalid Anthropic API key.",
			assertThrows(ClaudeImporter.ImportException.class, () -> ClaudeImporter.parseResponse(401, "{}")).getMessage());
		assertEquals("Anthropic API error 400: image too large",
			assertThrows(ClaudeImporter.ImportException.class, () -> ClaudeImporter.parseResponse(400,
				"{\"type\": \"error\", \"error\": {\"type\": \"invalid_request_error\", \"message\": \"image too large\"}}")).getMessage());
		assertEquals("Anthropic API error 500: <html>oops</html>",
			assertThrows(ClaudeImporter.ImportException.class, () -> ClaudeImporter.parseResponse(500, "<html>oops</html>")).getMessage());
		assertEquals("Claude declined to read this image.",
			assertThrows(ClaudeImporter.ImportException.class, () -> ClaudeImporter.parseResponse(200, reply("refusal", ""))).getMessage());
		assertThrows(ClaudeImporter.ImportException.class, () -> ClaudeImporter.parseResponse(200, reply("max_tokens", "{\"materi")));
		assertThrows(ClaudeImporter.ImportException.class, () -> ClaudeImporter.parseResponse(200, "not json"));
		assertThrows(ClaudeImporter.ImportException.class, () -> ClaudeImporter.parseResponse(200, reply("end_turn", "sorry, no list")));
	}

	@Test
	void screenshotRequestAsksForJson() {
		JsonObject opus = ClaudeImporter.requestBody("AAAA", "image/png", "claude-opus-5");
		assertEquals("claude-opus-5", opus.get("model").getAsString());
		assertEquals("default", opus.get("fallbacks").getAsString());
		assertEquals("json_schema", opus.getAsJsonObject("output_config").getAsJsonObject("format").get("type").getAsString());
		JsonObject image = opus.getAsJsonArray("messages").get(0).getAsJsonObject().getAsJsonArray("content").get(0).getAsJsonObject();
		assertEquals("image", image.get("type").getAsString());
		assertEquals("image/png", image.getAsJsonObject("source").get("media_type").getAsString());
		// Refusal fallbacks are only sent to models that support them.
		assertFalse(ClaudeImporter.requestBody("AAAA", "image/png", "claude-haiku-4-5").has("fallbacks"));
	}

	/** Every example in docs/importing-lists.md parses the way the docs say it does. */
	@Test
	void documentedFormatsParse() {
		assertEquals(Map.of(Items.STONE, 64), MaterialParser.parse("64 stone").materials());
		assertEquals(Map.of(Items.STONE, 64), MaterialParser.parse("stone 64").materials());
		assertEquals(Map.of(Items.STONE, 64), MaterialParser.parse("Stone: 64").materials());
		assertEquals(Map.of(Items.STONE, 64), MaterialParser.parse("stone - 64").materials());
		assertEquals(Map.of(Items.OAK_PLANKS, 3), MaterialParser.parse("3x oak_planks").materials());
		assertEquals(Map.of(Items.OAK_PLANKS, 3), MaterialParser.parse("oak planks x3").materials());
		assertEquals(Map.of(Items.OAK_PLANKS, 192), MaterialParser.parse("3 stacks oak planks").materials());
		assertEquals(Map.of(Items.OAK_PLANKS, 192), MaterialParser.parse("3 st oak planks").materials());
		assertEquals(Map.of(Items.STONE_BRICKS, 5 * 64 + 12), MaterialParser.parse("stone bricks - 5 stacks + 12").materials());
		assertEquals(Map.of(Items.GLASS, 2 * 27 * 64), MaterialParser.parse("2 sb glass").materials());
		assertEquals(Map.of(Items.GLASS, 2 * 27 * 64), MaterialParser.parse("2 shulker boxes of glass").materials());
		assertEquals(Map.of(Items.COBBLESTONE, 54 * 64), MaterialParser.parse("1 dc cobblestone").materials());
		assertEquals(Map.of(Items.COBBLESTONE, 54 * 64), MaterialParser.parse("1 double chest of cobblestone").materials());
		assertEquals(Map.of(Items.STONE, 192), MaterialParser.parse("3x64 stone").materials());
		assertEquals(Map.of(Items.GLASS, 1024), MaterialParser.parse("1,024 glass").materials());
		assertEquals(Map.of(Items.GLASS, 1024), MaterialParser.parse("1.024 glass").materials());
		assertEquals(Map.of(Items.STONE, 64), MaterialParser.parse("- 64 stone").materials());
		assertEquals(Map.of(Items.STONE, 64), MaterialParser.parse("1. 64 stone").materials());
		assertEquals(Map.of(Items.STONE, 64, Items.GLASS, 32, Items.TORCH, 10), MaterialParser.parse("64 stone, 32 glass; 10 torches").materials());
		assertEquals(Map.of(Items.OAK_PLANKS, 5), MaterialParser.parse("5 minecraft:oak_planks").materials());
		assertEquals(Map.of(Items.OAK_PLANKS, 5), MaterialParser.parse("5 oak plnks").materials());
		// Comments and headings without an amount are skipped, not reported as unrecognized.
		MaterialParser.Result skipped = MaterialParser.parse("# 64 stone\nStill needed:");
		assertTrue(skipped.materials().isEmpty());
		assertTrue(skipped.unresolved().isEmpty());
	}

	@Test
	void numbersAreOnlyAddedWhenMeantTo() {
		assertEquals(Map.of(Items.STONE, 1728), MaterialParser.parse("Stone 1728 (27 stacks)").materials());
		assertEquals(Map.of(Items.GLASS, 160), MaterialParser.parse("2.5 stacks glass").materials());
		assertEquals(Map.of(Items.STONE, 1500), MaterialParser.parse("1.5k stone").materials());
		assertEquals(Map.of(Items.STONE, 1000), MaterialParser.parse("1.000 stone").materials());
		assertEquals(Map.of(Items.MUSIC_DISC_5, 1), MaterialParser.parse("1 minecraft:music_disc_5").materials());
		assertEquals(Map.of(Items.STONE_BRICKS, 5 * 64 + 12), MaterialParser.parse("5 stacks 12 stone bricks").materials());
		assertEquals(Map.of(), MaterialParser.parse("Stone 1728 27").materials());
	}

	@Test
	void namesContainingAndResolve() {
		assertEquals(Map.of(Items.FLINT_AND_STEEL, 1), MaterialParser.parse("1 Flint and steel").materials());
		assertEquals(Map.of(Items.FLINT_AND_STEEL, 3), MaterialParser.parse("3x flint_and_steel").materials());
		assertEquals(Map.of(Items.FLINT_AND_STEEL, 2), MaterialParser.parse("Flint and Steel: 2").materials());
		// "and" is still filler when it isn't part of the name.
		assertEquals(Map.of(Items.STONE, 64), MaterialParser.parse("64 stone and").materials());
	}

	@Test
	void unrecognizedLinesSplitIntoAnAmountAndAName() {
		// "2 sb" is 2 shulker boxes, which is a different total for a stone brick than for a bucket.
		MaterialParser.Line boxes = MaterialParser.split("2 sb hover stone");
		assertEquals("hover stone", boxes.name());
		assertEquals(2 * 27 * 64, boxes.countFor(Items.STONE_BRICKS));
		assertEquals(2 * 27 * 16, boxes.countFor(Items.ENDER_PEARL));

		MaterialParser.Line plain = MaterialParser.split("12 grass blok");
		assertEquals("grass blok", plain.name());
		assertEquals(12, plain.countFor(Items.GRASS_BLOCK));

		// An amount with no name at all still gives a usable amount and an empty search.
		assertEquals("", MaterialParser.split("64").name());
		assertEquals(64, MaterialParser.split("64").countFor(Items.STONE));
		// Never zero, so the fix screen always starts on a valid amount.
		assertEquals(1, MaterialParser.split("nonsense").countFor(Items.STONE));
	}

	@Test
	void namesWithOfOrUnitWordsResolve() {
		assertEquals(Map.of(Items.IRON_BLOCK, 164), MaterialParser.parse("164 Block of Iron").materials());
		assertEquals(Map.of(Items.ENDER_EYE, 12), MaterialParser.parse("12 Eye of Ender").materials());
		assertEquals(Map.of(Items.SHULKER_BOX, 4), MaterialParser.parse("4 Shulker Box").materials());
		assertEquals(Map.of(Items.SHULKER_SHELL, 8), MaterialParser.parse("8 Shulker Shell").materials());
		assertEquals(Map.of(Items.SHORT_GRASS, 64), MaterialParser.parse("64 grass").materials());
	}

	@Test
	void tableAndCsvRowsWithoutAHeaderParse() {
		assertEquals(Map.of(Items.STONE, 64), MaterialParser.parse("Stone,64").materials());
		assertEquals(Map.of(Items.OAK_PLANKS, 1234), MaterialParser.parse("Oak Planks\t1234\t10\t0").materials());
		assertEquals(Map.of(Items.STONE, 64), MaterialParser.parse("| 1 | Stone | 64 |").materials());
		// An amount without an item is reported, not dropped.
		assertEquals(List.of("64"), MaterialParser.parse("64").unresolved());
	}

	@Test
	void pickedUpShulkerPartialStackDoesntTakeWhatsNeeded() {
		Project project = new Project();
		project.materials.add(new Project.MaterialEntry("minecraft:stone", 64));
		Project.BoxEntry shulker = box(project, A, 27, 0, "minecraft:stone", 1);
		shulker.block = "minecraft:red_shulker_box";
		shulker.pickedUp = true;
		box(project, B, 27);

		Layout layout = Layout.compute(project);
		assertNull(layout.plans(A));
		assertEquals(new Layout.SlotPlan(Items.STONE, 63), layout.plan(B, 0));
	}

	@Test
	void aBoxWhereAShulkerWasPickedUpHasItsOwnPlans() {
		Project project = new Project();
		project.materials.add(new Project.MaterialEntry("minecraft:stone", 64 * 60));
		box(project, A, 54);
		Project.BoxEntry shulker = box(project, A, 27, 0, "minecraft:stone", 64);
		shulker.pickedUp = true;

		Layout layout = Layout.compute(project);
		assertEquals(54, layout.plans(A).length);
		assertEquals(new Layout.SlotPlan(Items.STONE, 64), layout.plan(A, 53));
	}

	@Test
	void missingBoxesDontCount() {
		Project project = new Project();
		project.materials.add(new Project.MaterialEntry("minecraft:stone", 100));
		Project.BoxEntry gone = box(project, A, 27, 0, "minecraft:stone", 64);
		gone.missing = true;
		box(project, B, 27);

		Layout layout = Layout.compute(project);
		assertEquals(0, layout.stored(Items.STONE));
		assertNull(layout.plans(A));
		assertEquals(new Layout.SlotPlan(Items.STONE, 64), layout.plan(B, 0));
		assertTrue(gone.copy().missing);
	}

	@Test
	void anItemListedTwiceIsMissingOnce() {
		Project project = new Project();
		project.materials.add(new Project.MaterialEntry("minecraft:stone", 64));
		project.materials.add(new Project.MaterialEntry("minecraft:stone", 32));
		box(project, A, 27, 0, "minecraft:stone", 64);
		assertEquals("Still needed:\n32 Stone\n", Layout.compute(project).missingText(project));
	}

	@Test
	void reshapingKeepsTheRightHalf() {
		Project.BoxEntry box = new Project.BoxEntry(A, 54);
		box.slotItems[3] = "minecraft:stone";
		box.slotCounts[3] = 5;
		box.slotItems[30] = "minecraft:glass";
		box.slotCounts[30] = 7;
		// The half in slots 27-53 is the one left.
		box.reshape(27, 27, 0, 27);
		assertEquals(27, box.size);
		assertEquals("minecraft:glass", box.slotItems[3]);
		assertEquals(7, box.countAt(3));
		// Joined again as the second half.
		box.reshape(54, 0, 27, 27);
		assertTrue(box.isEmptyAt(3));
		assertEquals(7, box.countAt(30));
	}

	private static Project shared() {
		Project project = new Project();
		project.listName = "Castle walls";
		project.materials.add(new Project.MaterialEntry("minecraft:stone", 1000));
		Project.MaterialEntry glass = new Project.MaterialEntry("minecraft:glass", 128);
		glass.crossedOff = true;
		project.materials.add(glass);
		project.materials.add(new Project.MaterialEntry("somemod:brass_block", 12));
		project.replacements.put("minecraft:oak_planks", "minecraft:oak_log");
		return project;
	}

	@Test
	void aShareCodeRoundTripsEverythingTextCant() {
		Project project = shared();
		ListShare.Decoded decoded = ListShare.decode(ListShare.encode(project));
		assertEquals("Castle walls", decoded.name());
		assertEquals(3, decoded.materials().size());
		assertEquals("minecraft:stone", decoded.materials().get(0).item);
		assertEquals(1000, decoded.materials().get(0).count);
		assertFalse(decoded.materials().get(0).crossedOff);
		assertTrue(decoded.materials().get(1).crossedOff);
		// A modded item keeps its namespace; a vanilla one gets it back.
		assertEquals("somemod:brass_block", decoded.materials().get(2).item);
		assertEquals(Map.of("minecraft:oak_planks", "minecraft:oak_log"), decoded.replacements());
	}

	@Test
	void aShareCodeIsFoundInSurroundingText() {
		String code = ListShare.encode(shared());
		assertEquals(code, ListShare.find("here's the list for tonight: " + code + " ping me if it breaks"));
		assertEquals(code, ListShare.find("> " + code + "\n"));
		assertNull(ListShare.find("64 stone\n12 glass"));
	}

	@Test
	void aBigListStaysOneLineAndCompresses() {
		Project project = new Project();
		for (int i = 0; i < 200; i++) {
			project.materials.add(new Project.MaterialEntry("minecraft:stone_" + i, 1234));
		}
		String code = ListShare.encode(project);
		assertFalse(code.contains("\n"));
		// Comfortably inside a chat message's length, where the same list as text would not be.
		assertTrue(code.length() < 2000, "A 200-material code was " + code.length() + " characters");
		assertEquals(200, ListShare.decode(code).materials().size());
	}

	@Test
	void damagedCodesAreRejectedRatherThanCrashing() {
		String code = ListShare.encode(shared());
		assertNull(ListShare.decode(code.substring(0, code.length() - 10)));
		assertNull(ListShare.decode("MBOX1:not-real-base64-data"));
		assertNull(ListShare.decode("64 stone"));
		// An empty list isn't a list.
		assertNull(ListShare.decode(ListShare.encode(new Project())));
	}

	@Test
	void aHandMadeCodeCantBeInflatedIntoACrash() {
		Project bomb = new Project();
		for (int i = 0; i < 400_000; i++) {
			bomb.materials.add(new Project.MaterialEntry("minecraft:stone", 1));
		}
		// Every entry is the same item, so it compresses to almost nothing and would inflate to megabytes.
		assertNull(ListShare.decode(ListShare.encode(bomb)));
	}

	@Test
	void anExportedFileReadsAsAListAndCarriesItsCode() {
		Project project = shared();
		String text = ListShare.text(project.listName, project.materials, project.replacements);
		// A person reading the file, or pasting only the lines under the header, gets the materials.
		MaterialParser.Result parsed = MaterialParser.parse(text);
		assertEquals(1000, count(parsed, Items.STONE));
		assertEquals(128, count(parsed, Items.GLASS));
		assertEquals(List.of("12 somemod:brass_block"), parsed.unresolved());
		// Dropping the whole file in finds the code, so the name and the crossed-off glass survive too.
		ListShare.Decoded decoded = ListShare.decode(ListShare.find(text));
		assertEquals("Castle walls", decoded.name());
		assertTrue(decoded.materials().get(1).crossedOff);
	}

	@Test
	void exportFileNamesStaySafe() {
		assertEquals("Castle walls", ListShare.fileName("Castle walls"));
		assertEquals("material list", ListShare.fileName(null));
		assertEquals("my list 2024", ListShare.fileName("my/list: 2024"));
		// A name can't climb out of the exports folder.
		assertEquals("up", ListShare.fileName("../up"));
		assertEquals("material list", ListShare.fileName("..."));
		assertEquals("material list CON", ListShare.fileName("CON"));
	}

	/**
	 * data/ and importer/ hold the parts of the mod that aren't tied to a Minecraft version or a mod loader: the list,
	 * the layout, saving, parsing and sharing. Keeping client and loader APIs out of them is what would let those
	 * packages move into a shared module if the mod is ever ported. Client code calls in; these never call out.
	 */
	@Test
	void theVersionAgnosticPackagesDontTouchClientOrLoaderApis() throws IOException {
		Path source = Path.of("src/main/java/dev/kianj/materialsgui");
		assertTrue(Files.isDirectory(source), "Run from the project directory; " + source.toAbsolutePath() + " isn't there");
		List<String> offenders = new ArrayList<>();
		for (String pkg : List.of("data", "importer")) {
			try (Stream<Path> files = Files.walk(source.resolve(pkg))) {
				for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
					for (String line : Files.readAllLines(file)) {
						if (line.startsWith("import ") && FORBIDDEN.stream().anyMatch(line.substring(7)::startsWith)) {
							offenders.add(file.getFileName() + ": " + line.strip());
						}
					}
				}
			}
		}
		assertEquals(List.of(), offenders, "These belong in client code, which passes what it knows into data/ and importer/");
	}

	private static final List<String> FORBIDDEN = List.of("net.minecraft.client", "net.fabricmc", "com.mojang.blaze3d");

	@Test
	void worldKeysDontCollide() {
		assertEquals("new_world", ProjectStore.sanitize("New World"));
		assertFalse(ProjectStore.sanitize("世界").equals(ProjectStore.sanitize("天堂")));
	}
}
