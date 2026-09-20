package dev.kianj.materialsgui.gametest;

import dev.kianj.materialsgui.box.BoxRefresher;
import dev.kianj.materialsgui.box.BoxValidator;
import dev.kianj.materialsgui.box.SlotOverlay;
import dev.kianj.materialsgui.box.SlotTooltip;
import dev.kianj.materialsgui.data.BoxKey;
import dev.kianj.materialsgui.data.Layout;
import dev.kianj.materialsgui.data.Project;
import dev.kianj.materialsgui.data.ProjectStore;
import dev.kianj.materialsgui.mixin.AbstractContainerScreenAccessor;
import dev.kianj.materialsgui.preview.ShulkerPreview;
import dev.kianj.materialsgui.screen.BoxesScreen;
import dev.kianj.materialsgui.data.SavedLists;
import dev.kianj.materialsgui.data.ModConfig;
import com.mojang.blaze3d.platform.InputConstants;
import dev.kianj.materialsgui.screen.EditMaterialScreen;
import dev.kianj.materialsgui.screen.SettingsScreen;
import dev.kianj.materialsgui.screen.ListsScreen;
import dev.kianj.materialsgui.screen.MaterialsScreen;
import dev.kianj.materialsgui.screen.ShareScreen;
import dev.kianj.materialsgui.importer.ListShare;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.fabricmc.fabric.mixin.client.gametest.input.MouseHandlerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * Plays through the core flow in a real client + integrated server: mark a chest that already holds materials as a
 * Material Box, shift-click items in, move items by hand, and break the box.
 */
public class MaterialBoxGameTest implements FabricClientGameTest {
	private static final int ESCAPE = InputConstants.KEY_ESCAPE;
	private static final int ENTER = InputConstants.KEY_RETURN;
	private static final int BACKSPACE = InputConstants.KEY_BACKSPACE;
	/** What {@code MouseHandler.onButton} reads as a press and a release; not a key code. */
	private static final int MOUSE_RELEASE = 0;
	private static final int MOUSE_PRESS = 1;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();

			BlockPos chest = sp.getServer().computeOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				return player.blockPosition().relative(player.getDirection(), 2);
			});
			String at = chest.getX() + " " + chest.getY() + " " + chest.getZ();
			sp.getServer().runCommand("setblock " + at + " minecraft:chest");
			// The chest already holds a listed material and something that isn't on the list.
			sp.getServer().runOnServer(server -> {
				chestAt(server, chest).setItem(10, new ItemStack(Items.OAK_PLANKS, 64));
				chestAt(server, chest).setItem(12, new ItemStack(Items.COBBLESTONE, 3));
			});
			sp.getServer().runCommand("clear @a");
			sp.getServer().runCommand("give @a minecraft:stone 100");
			sp.getServer().runCommand("give @a minecraft:ender_pearl 20");
			sp.getServer().runCommand("give @a minecraft:glass 5");
			sp.getServer().runCommand("give @a minecraft:dirt 7");
			sp.getConnection().waitForClientboundPackets();

			ctx.runOnClient(mc -> {
				// The test's config survives between runs; start with the import panel open.
				ModConfig.get().importPanelOpen = true;
				ModConfig.get().hudCompact = false;
				ModConfig.get().refreshBoxes = true;
				ModConfig.get().highlightSlots = true;
				Project project = ProjectStore.project();
				project.materials.clear();
				project.materials.add(new Project.MaterialEntry("minecraft:stone", 100));
				project.materials.add(new Project.MaterialEntry("minecraft:ender_pearl", 20));
				project.materials.add(new Project.MaterialEntry("minecraft:glass", 10));
				project.materials.add(new Project.MaterialEntry("minecraft:oak_planks", 64));
				ProjectStore.changed();
			});

			openChest(ctx, chest);
			ctx.clickScreenButton("+ Material Box");
			ctx.waitTicks(2);
			// Oak planks already in the chest count toward the list and stay where they are; ghosts fill the gaps.
			ctx.runOnClient(mc -> {
				BoxKey key = onlyBox();
				Layout layout = ProjectStore.layout();
				expectPlan(layout, key, 0, Items.STONE, 64);
				expectPlan(layout, key, 1, Items.STONE, 36);
				expectPlan(layout, key, 2, Items.ENDER_PEARL, 16);
				expectPlan(layout, key, 3, Items.ENDER_PEARL, 4);
				expectPlan(layout, key, 4, Items.GLASS, 10);
				expectPlan(layout, key, 10, Items.OAK_PLANKS, 64);
				expectNoPlan(layout, key, 5);
				expectNoPlan(layout, key, 12);
			});
			ctx.takeScreenshot("1-new-box-with-existing-items");
			// Hovering a missing (ghost) slot names the item and shows 0/64.
			hoverChestSlot(ctx, 0);
			ctx.runOnClient(mc -> {
				// What the screen actually set as its tooltip in the last frame, not just what it would build.
				List<Component> lines = SlotTooltip.lastGhostTooltip;
				List<String> tooltip = lines == null ? List.of() : lines.stream().map(Component::getString).toList();
				if (!tooltip.contains("Stone") || !tooltip.contains("0/64") || !tooltip.contains("All boxes: 0/100")) {
					throw new AssertionError("Missing-slot tooltip is wrong: " + tooltip);
				}
				if (lines.get(1).getStyle().getColor() == null || lines.get(1).getStyle().getColor().getValue() != 0xFF5555) {
					throw new AssertionError("0/64 should be red but is " + lines.get(1).getStyle().getColor());
				}
			});
			ctx.takeScreenshot("1b-hover-missing-stone");

			shiftClick(ctx, Items.STONE, 64);
			shiftClick(ctx, Items.STONE, 36);
			shiftClick(ctx, Items.ENDER_PEARL, 16);
			shiftClick(ctx, Items.ENDER_PEARL, 4);
			shiftClick(ctx, Items.GLASS, 5);
			// Not on the list: vanilla shift-click puts it in the first free slot.
			shiftClick(ctx, Items.DIRT, 7);
			sp.getConnection().waitForServerboundPackets();
			ctx.takeScreenshot("2-after-shift-clicks");

			List<ItemStack> contents = contents(sp, chest);
			expect(contents, 0, Items.STONE, 64);
			expect(contents, 1, Items.STONE, 36);
			expect(contents, 2, Items.ENDER_PEARL, 16);
			expect(contents, 3, Items.ENDER_PEARL, 4);
			expect(contents, 4, Items.GLASS, 5);
			expect(contents, 5, Items.DIRT, 7);
			expect(contents, 10, Items.OAK_PLANKS, 64);
			expect(contents, 12, Items.COBBLESTONE, 3);

			// Move the glass by hand: the highlight follows it instead of staying in slot 4.
			ctx.runOnClient(mc -> {
				click(mc, 4);
				click(mc, 20);
			});
			ctx.waitTicks(3);
			ctx.runOnClient(mc -> {
				BoxKey key = onlyBox();
				expectPlan(ProjectStore.layout(), key, 20, Items.GLASS, 10);
				expectNoPlan(ProjectStore.layout(), key, 4);
			});
			// Hovering the partial glass stack adds 5/10 under the item name in its tooltip.
			hoverChestSlot(ctx, 20);
			ctx.runOnClient(mc -> {
				List<String> tooltip = Screen.getTooltipFromItem(mc, mc.player.containerMenu.getSlot(20).getItem())
					.stream().map(Component::getString).toList();
				if (!tooltip.contains("5/10") || !tooltip.contains("All boxes: 5/10")) {
					throw new AssertionError("Glass tooltip is missing the progress lines: " + tooltip);
				}
			});
			ctx.takeScreenshot("3-glass-moved-by-hand");

			// Hide Highlights (for building) hides the colors and the progress lines, until Show Highlights.
			ctx.clickScreenButton("Hide Highlights");
			hoverChestSlot(ctx, 20);
			ctx.runOnClient(mc -> {
				AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) mc.gui.screen();
				List<String> tooltip = Screen.getTooltipFromItem(mc, mc.player.containerMenu.getSlot(20).getItem())
					.stream().map(Component::getString).toList();
				if (ModConfig.get().highlightSlots || SlotOverlay.planFor(screen, screen.getMenu().getSlot(20)) != null || tooltip.contains("5/10")) {
					throw new AssertionError("Hide Highlights should hide the slot highlight and its tooltip lines: " + tooltip);
				}
			});
			ctx.takeScreenshot("3a-highlights-hidden");
			ctx.clickScreenButton("Show Highlights");
			ctx.runOnClient(mc -> {
				AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) mc.gui.screen();
				if (SlotOverlay.planFor(screen, screen.getMenu().getSlot(20)) == null) {
					throw new AssertionError("Show Highlights should bring the highlights back");
				}
			});

			ctx.getInput().pressKey(ESCAPE);
			ctx.waitTicks(5);
			ctx.setScreen(MaterialsScreen::new);
			ctx.waitTicks(5);
			ctx.takeScreenshot("4-materials-list");
			ctx.setScreen(() -> null);
			ctx.waitTicks(2);

			// Breaking the box yourself removes it.
			ctx.runOnClient(mc -> BoxValidator.onPlayerBreak(mc.level, mc.player, chest, mc.level.getBlockState(chest)));
			sp.getServer().runCommand("setblock " + at + " minecraft:air");
			ctx.waitFor(mc -> ProjectStore.project().boxes.isEmpty());

			// So the next chest added becomes box #1.
			sp.getServer().runCommand("setblock " + at + " minecraft:chest");
			sp.getConnection().waitForClientboundPackets();
			ctx.waitTicks(5);
			openChest(ctx, chest);
			ctx.clickScreenButton("+ Material Box");
			ctx.runOnClient(mc -> {
				if (ProjectStore.project().indexOfBox(onlyBox()) != 0) {
					throw new AssertionError("The new chest should be Material Box #1");
				}
			});
			ctx.getInput().pressKey(ESCAPE);
			ctx.waitTicks(5);

			// The box manager lists it and can remove it by hand.
			ctx.setScreen(() -> new MaterialsScreen());
			ctx.waitTicks(2);
			ctx.clickScreenButton("Boxes...");
			ctx.waitForScreen(BoxesScreen.class);
			ctx.waitTicks(2);
			ctx.takeScreenshot("5-box-manager");
			ctx.clickScreenButton("Remove");
			ctx.waitTicks(2);
			ctx.runOnClient(mc -> {
				if (!ProjectStore.project().boxes.isEmpty()) {
					throw new AssertionError("Remove should have removed the box");
				}
			});
			ctx.setScreen(() -> null);
			ctx.waitTicks(2);

			// Right-clicking a shulker box in the inventory previews its contents without touching it.
			sp.getServer().runOnServer(server -> {
				ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
				shulker.set(DataComponents.CUSTOM_NAME, Component.literal("Build Stuff"));
				shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(
					new ItemStack(Items.DIAMOND, 12), ItemStack.EMPTY, new ItemStack(Items.OAK_LOG, 64),
					ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY,
					new ItemStack(Items.TORCH, 30))));
				server.getPlayerList().getPlayers().getFirst().getInventory().setItem(9, shulker);
			});
			sp.getConnection().waitForClientboundPackets();
			ctx.getInput().pressKey(options -> options.keyInventory);
			ctx.waitForScreen(InventoryScreen.class);
			ctx.waitTicks(2);
			hoverSlotWith(ctx, Items.SHULKER_BOX);
			ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
			ctx.waitTicks(3);
			ctx.runOnClient(mc -> {
				if (!ShulkerPreview.isOpen(mc.gui.screen())) {
					throw new AssertionError("Right-clicking the shulker box should open the preview");
				}
				if (!mc.player.containerMenu.getCarried().isEmpty()) {
					throw new AssertionError("The preview must not pick the shulker box up");
				}
			});
			ctx.takeScreenshot("6-shulker-preview");
			ctx.getInput().pressKey(ESCAPE);
			ctx.waitTicks(2);
			ctx.runOnClient(mc -> {
				if (!(mc.gui.screen() instanceof InventoryScreen) || ShulkerPreview.isOpen(mc.gui.screen())) {
					throw new AssertionError("Escape should close only the preview, not the inventory");
				}
			});
			ItemStack after = sp.getServer().computeOnServer(
				server -> server.getPlayerList().getPlayers().getFirst().getInventory().getItem(9).copy());
			if (!after.is(Items.SHULKER_BOX) || after.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY)
				.nonEmptyItemCopyStream().count() != 3) {
				throw new AssertionError("The shulker box should be untouched on the server, found " + after);
			}
			ctx.getInput().pressKey(ESCAPE);
			ctx.waitTicks(2);

			// A shulker Material Box keeps counting after it's broken, and reconnects when it's placed and opened again.
			sp.getServer().runCommand("setblock " + at + " minecraft:shulker_box");
			sp.getServer().runOnServer(server -> fillShulker(server, chest));
			sp.getConnection().waitForClientboundPackets();
			ctx.waitTicks(2);
			openContainer(ctx, chest, ShulkerBoxScreen.class);
			ctx.clickScreenButton("+ Material Box");
			ctx.getInput().pressKey(ESCAPE);
			ctx.waitTicks(5);
			ctx.runOnClient(mc -> {
				Project.BoxEntry box = ProjectStore.project().boxes.getFirst();
				if (!"minecraft:shulker_box".equals(box.block) || ProjectStore.layout().stored(Items.STONE) != 64) {
					throw new AssertionError("The shulker Material Box should record its block and count its stone");
				}
			});
			sp.getServer().runCommand("setblock " + at + " minecraft:air");
			ctx.waitFor(mc -> ProjectStore.project().boxes.size() == 1 && ProjectStore.project().boxes.getFirst().pickedUp);
			ctx.runOnClient(mc -> {
				Project.BoxEntry box = ProjectStore.project().boxes.getFirst();
				if (ProjectStore.layout().stored(Items.STONE) != 64) {
					throw new AssertionError("A picked-up shulker box's items should still count");
				}
				if (ProjectStore.layout().plan(box.key(), 1) != null) {
					throw new AssertionError("A picked-up shulker box can't be filled, so it should get no ghosts");
				}
			});
			ctx.setScreen(() -> new BoxesScreen(null));
			ctx.waitTicks(2);
			ctx.takeScreenshot("7-shulker-picked-up");
			ctx.setScreen(() -> null);
			ctx.waitTicks(2);

			// Placing it again reconnects it straight away, without opening it: the item carries its contents.
			BlockPos moved = chest.east();
			sp.getServer().runOnServer(server -> {
				List<ItemStack> items = new ArrayList<>();
				for (int i = 0; i < 27; i++) {
					items.add(ItemStack.EMPTY);
				}
				items.set(0, new ItemStack(Items.STONE, 64));
				items.set(5, new ItemStack(Items.GLASS, 3));
				ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
				shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
				// The off hand, so nothing the test uses later is replaced.
				server.getPlayerList().getPlayers().getFirst().setItemInHand(InteractionHand.OFF_HAND, shulker);
			});
			sp.getConnection().waitForClientboundPackets();
			ctx.waitTicks(2);
			ctx.runOnClient(mc -> mc.gameMode.useItemOn(mc.player, InteractionHand.OFF_HAND,
				new BlockHitResult(Vec3.atCenterOf(moved.below()).add(0, 0.5, 0), Direction.UP, moved.below(), false)));
			ctx.waitFor(mc -> !ProjectStore.project().boxes.getFirst().pickedUp);
			ctx.runOnClient(mc -> {
				if (!ProjectStore.project().boxes.getFirst().key().equals(BoxKey.of("minecraft:overworld", moved))) {
					throw new AssertionError("The shulker box should reconnect at its new position");
				}
				if (mc.gui.screen() != null) {
					throw new AssertionError("The shulker box should reconnect without being opened");
				}
			});
			ctx.setScreen(() -> new BoxesScreen(null));
			ctx.waitTicks(2);
			ctx.takeScreenshot("8-shulker-reattached");
			ctx.setScreen(() -> null);
			ctx.waitTicks(2);

			// Replace a material with a different item, keeping the amount.
			ctx.setScreen(MaterialsScreen::new);
			ctx.waitTicks(2);
			ctx.runOnClient(mc -> mc.gui.setScreen(new EditMaterialScreen((MaterialsScreen) mc.gui.screen(), 3)));
			ctx.waitForScreen(EditMaterialScreen.class);
			ctx.getInput().typeChars("oak log");
			ctx.waitTicks(2);
			ctx.getInput().pressKey(ENTER);
			ctx.waitTicks(2);
			ctx.takeScreenshot("9-replace-material");
			ctx.clickScreenButton("Save");
			ctx.waitTicks(2);
			ctx.runOnClient(mc -> {
				Project.MaterialEntry m = ProjectStore.project().materials.get(3);
				if (!m.item.equals("minecraft:oak_log") || m.count != 64) {
					throw new AssertionError("Oak planks should now be 64 oak logs, but the entry is " + m.item + " x" + m.count);
				}
				if (!(mc.gui.screen() instanceof MaterialsScreen)) {
					throw new AssertionError("Save should return to the Materials List");
				}
			});

			// Save the list under a name, start a new one, and load it back. Its replacements come with it.
			ctx.runOnClient(mc -> new ArrayList<>(SavedLists.all()).forEach(SavedLists::delete));
			ctx.clickScreenButton("Lists...");
			ctx.waitForScreen(ListsScreen.class);
			ctx.getInput().typeChars("Castle walls");
			ctx.clickScreenButton("Save");
			ctx.waitTicks(2);
			ctx.clickScreenButton("New List");
			ctx.waitTicks(2);
			ctx.runOnClient(mc -> {
				Project project = ProjectStore.project();
				if (!project.materials.isEmpty() || project.listName != null || !project.replacements.isEmpty()) {
					throw new AssertionError("New List should start an empty, unnamed list");
				}
				SavedLists.SavedList saved = SavedLists.find("Castle walls");
				if (saved == null || saved.materials.size() != 4
					|| !"minecraft:oak_log".equals(saved.replacements.get("minecraft:oak_planks"))) {
					throw new AssertionError("The saved list should hold the 4 materials and the oak planks to oak log swap");
				}
			});
			ctx.takeScreenshot("10-saved-lists");
			ctx.clickScreenButton("Load");
			ctx.waitTicks(2);
			ctx.runOnClient(mc -> {
				Project project = ProjectStore.project();
				if (project.materials.size() != 4 || !"Castle walls".equals(project.listName)
					|| !"minecraft:oak_log".equals(project.replacementFor("minecraft:oak_planks"))) {
					throw new AssertionError("Loading should bring back the materials, the name and the replacement");
				}
			});
			ctx.clickScreenButton("Done");
			ctx.waitForScreen(MaterialsScreen.class);
			ctx.waitTicks(2);
			ctx.takeScreenshot("11-named-materials-list");

			// The import panel is open on the left. Importing into a saved list applies its replacements.
			ctx.getInput().typeChars("5 oak planks");
			ctx.waitTicks(2);
			ctx.takeScreenshot("12-import-panel");
			ctx.clickScreenButton("Add to List");
			ctx.waitTicks(2);
			ctx.runOnClient(mc -> {
				List<Project.MaterialEntry> materials = ProjectStore.project().materials;
				Project.MaterialEntry logs = materials.stream().filter(m -> m.item.equals("minecraft:oak_log")).findFirst().orElse(null);
				if (logs == null || logs.count != 69 || materials.stream().anyMatch(m -> m.item.equals("minecraft:oak_planks"))) {
					throw new AssertionError("5 imported oak planks should have become oak logs (64 + 5)");
				}
			});
			// A line naming no real item is reported, and can be fixed from the list itself.
			ctx.getInput().typeChars("2 sb hover stone");
			ctx.waitTicks(2);
			ctx.clickScreenButton("Add to List");
			ctx.waitTicks(2);
			ctx.runOnClient(mc -> {
				if (!MaterialsScreen.unresolvedLines().equals(List.of("2 sb hover stone"))) {
					throw new AssertionError("The unreadable line should be listed, but is " + MaterialsScreen.unresolvedLines());
				}
			});
			ctx.takeScreenshot("12c-unrecognized-line");
			int[] listRows = ctx.computeOnClient(mc -> {
				EditBox search = null;
				for (var child : mc.gui.screen().children()) {
					if (child instanceof EditBox box) {
						search = box;
					}
				}
				// {left, first row's y}: the search box sits just above the rows.
				return new int[] {search.getX(), search.getY() + 37};
			});
			// The unrecognized line is the row after the four materials.
			clickAt(ctx, listRows[0] + 60, listRows[1] + 4 * 18 + 9, InputConstants.MOUSE_BUTTON_LEFT);
			ctx.waitForScreen(EditMaterialScreen.class);
			ctx.waitTicks(2);
			ctx.takeScreenshot("12d-fix-unrecognized-line");
			ctx.runOnClient(mc -> {
				// The line's text is waiting in the search box (the wide one), so it doesn't have to be retyped.
				for (var child : mc.gui.screen().children()) {
					if (child instanceof EditBox box && box.getWidth() > 100 && !box.getValue().equals("hover stone")) {
						throw new AssertionError("The fix screen should start from the line's text, but has \"" + box.getValue() + "\"");
					}
				}
			});
			for (int i = 0; i < "hover stone".length(); i++) {
				ctx.getInput().pressKey(BACKSPACE);
			}
			ctx.getInput().typeChars("stone bricks");
			ctx.waitTicks(2);
			ctx.getInput().pressKey(ENTER);
			ctx.waitTicks(2);
			ctx.clickScreenButton("Save");
			ctx.waitForScreen(MaterialsScreen.class);
			ctx.waitTicks(2);
			ctx.runOnClient(mc -> {
				if (!MaterialsScreen.unresolvedLines().isEmpty()) {
					throw new AssertionError("Fixing the line should take it off the list");
				}
				Project.MaterialEntry bricks = ProjectStore.project().materials.stream()
					.filter(m -> m.item.equals("minecraft:stone_bricks")).findFirst().orElse(null);
				// "2 sb" is two shulker boxes of a 64-stacking item.
				if (bricks == null || bricks.count != 2 * 27 * 64) {
					throw new AssertionError("The fixed line should add " + (2 * 27 * 64) + " stone bricks, but gave " + bricks);
				}
			});
			ctx.takeScreenshot("12e-line-fixed");
			ctx.runOnClient(mc -> {
				Project project = ProjectStore.project();
				project.materials.removeIf(m -> m.item.equals("minecraft:stone_bricks"));
				ProjectStore.changed();
			});

			// The panel collapses, and the choice is remembered.
			ctx.clickScreenButton("Hide Import");
			ctx.waitTicks(2);
			ctx.runOnClient(mc -> {
				if (ModConfig.get().importPanelOpen) {
					throw new AssertionError("Hide Import should be remembered");
				}
			});
			ctx.takeScreenshot("12b-import-panel-hidden");

			// The list now differs from its saved copy, so Clear asks first. The saved copy stays.
			ctx.clickScreenButton("Clear");
			ctx.waitTicks(1);
			ctx.clickScreenButton("Sure?");
			ctx.waitTicks(2);
			ctx.runOnClient(mc -> {
				Project project = ProjectStore.project();
				if (!project.materials.isEmpty() || project.listName != null || SavedLists.find("Castle walls") == null) {
					throw new AssertionError("Clear should empty the current list and leave the saved one alone");
				}
			});
			ctx.takeScreenshot("13-cleared-list");
			ctx.setScreen(() -> null);
			ctx.waitTicks(2);

			// Clear keeps the Material Boxes for the next list, and the shulker box is still one of Castle walls' boxes.
			ctx.runOnClient(mc -> {
				Project project = ProjectStore.project();
				List<String> others = SavedLists.otherListsUsing(BoxKey.of("minecraft:overworld", moved), ProjectStore.worldKey(), project.listName);
				if (project.boxes.size() != 1 || !others.equals(List.of("Castle walls"))) {
					throw new AssertionError("After Clear the shulker box should stay, and still be in Castle walls, but lists are " + others);
				}
			});
			openContainer(ctx, moved, ShulkerBoxScreen.class);
			ctx.takeScreenshot("14-box-also-in-castle-walls");
			ctx.getInput().pressKey(ESCAPE);
			ctx.waitTicks(2);

			// A second list with its own box: switching lists switches boxes.
			BlockPos garden = chest.west();
			sp.getServer().runCommand("setblock " + garden.getX() + " " + garden.getY() + " " + garden.getZ() + " minecraft:chest");
			sp.getConnection().waitForClientboundPackets();
			ctx.waitTicks(2);
			ctx.runOnClient(mc -> {
				Project project = ProjectStore.project();
				project.materials.add(new Project.MaterialEntry("minecraft:dirt", 10));
				project.boxes.clear();
				ProjectStore.changed();
			});
			openChest(ctx, garden);
			ctx.clickScreenButton("+ Material Box");
			ctx.getInput().pressKey(ESCAPE);
			ctx.waitTicks(2);
			ctx.runOnClient(mc -> {
				SavedLists.save("Garden", ProjectStore.project(), ProjectStore.worldKey());
				ProjectStore.changed();
				SavedLists.load(SavedLists.find("Castle walls"));
				expectOnlyBox(moved);
				SavedLists.load(SavedLists.find("Garden"));
				expectOnlyBox(garden);
				SavedLists.load(SavedLists.find("Castle walls"));
			});

			// Boxes of a list that isn't loaded are checked too. A chest that goes without the player breaking it (someone
			// else did, say) is kept as missing: it stops counting, and Clear Missing removes it.
			sp.getServer().runCommand("setblock " + garden.getX() + " " + garden.getY() + " " + garden.getZ() + " minecraft:air");
			ctx.waitFor(mc -> SavedLists.find("Garden").boxes.get(ProjectStore.worldKey()).getFirst().missing);
			ctx.runOnClient(mc -> {
				expectOnlyBox(moved);
				SavedLists.find("Garden").boxes.get(ProjectStore.worldKey()).clear();
				SavedLists.write();
			});

			// A shulker box inside a Material Box counts as its contents, and Deposit All moves every listed item in.
			BlockPos depot = garden;
			sp.getServer().runCommand("setblock " + depot.getX() + " " + depot.getY() + " " + depot.getZ() + " minecraft:chest");
			sp.getServer().runOnServer(server -> {
				ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
				shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.STONE, 64))));
				chestAt(server, depot).setItem(0, shulker);
			});
			sp.getServer().runCommand("clear @a");
			sp.getServer().runCommand("give @a minecraft:stone 128");
			sp.getServer().runCommand("give @a minecraft:glass 20");
			sp.getServer().runCommand("give @a minecraft:dirt 5");
			sp.getConnection().waitForClientboundPackets();
			ctx.runOnClient(mc -> {
				Project project = ProjectStore.project();
				project.listName = null;
				project.replacements.clear();
				project.materials.clear();
				project.materials.add(new Project.MaterialEntry("minecraft:stone", 200));
				project.materials.add(new Project.MaterialEntry("minecraft:glass", 20));
				project.boxes.clear();
				ProjectStore.changed();
				ModConfig.get().hudEnabled = true;
			});
			openChest(ctx, depot);
			ctx.clickScreenButton("+ Material Box");
			ctx.waitTicks(2);
			ctx.runOnClient(mc -> {
				int stored = ProjectStore.layout().stored(Items.STONE);
				if (stored != 64) {
					throw new AssertionError("The 64 stone inside the shulker box should count, but " + stored + " do");
				}
			});
			ctx.clickScreenButton("Deposit All");
			sp.getConnection().waitForServerboundPackets();
			ctx.waitTicks(3);
			List<ItemStack> depotContents = contents(sp, depot);
			int stoneIn = depotContents.stream().filter(s -> s.is(Items.STONE)).mapToInt(ItemStack::getCount).sum();
			int glassIn = depotContents.stream().filter(s -> s.is(Items.GLASS)).mapToInt(ItemStack::getCount).sum();
			if (stoneIn != 128 || glassIn != 20 || !depotContents.get(0).is(Items.SHULKER_BOX)) {
				throw new AssertionError("Deposit All should move in 128 stone and 20 glass, found " + stoneIn + " stone and " + glassIn + " glass");
			}
			if (inventoryCount(sp, Items.DIRT) != 5 || inventoryCount(sp, Items.STONE) != 0) {
				throw new AssertionError("Deposit All should leave the dirt (not on the list) in the inventory");
			}
			ctx.takeScreenshot("15-deposit-all");
			ctx.getInput().pressKey(ESCAPE);
			ctx.waitTicks(3);

			// The HUD shows what's still missing: 8 stone (64 in the shulker box + 128 deposited, of 200). H toggles it.
			ctx.takeScreenshot("16-missing-materials-hud");
			ctx.getInput().pressKey(InputConstants.KEY_H);
			ctx.waitTicks(2);
			ctx.runOnClient(mc -> {
				if (ModConfig.get().hudEnabled) {
					throw new AssertionError("H should hide the missing materials HUD");
				}
			});
			ctx.getInput().pressKey(InputConstants.KEY_H);
			ctx.waitTicks(2);

			// Sharing the list: what's still missing, a .txt file, and a share code.
			ctx.setScreen(MaterialsScreen::new);
			ctx.waitTicks(2);
			ctx.runOnClient(mc -> {
				// Earlier exports persist between runs, so the check below can't be fooled by one.
				deleteExports();
				// Open Folder is clickable before anything has been exported, and a folder that isn't there won't open.
				try {
					if (!Files.isDirectory(ListShare.createExportsDir())) {
						throw new AssertionError("The exports folder should be made on demand, not only when a list is saved");
					}
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
				Project project = ProjectStore.project();
				project.listName = "Watchtower";
				project.replacements.put("minecraft:oak_planks", "minecraft:oak_log");
				// Glass is crossed off, which a share code carries and plain text can't.
				project.materials.get(1).crossedOff = true;
				ProjectStore.changed();
			});
			ctx.clickScreenButton("Share");
			ctx.waitForScreen(ShareScreen.class);
			ctx.waitTicks(2);
			ctx.takeScreenshot("17-share-list");
			ctx.clickScreenButton("Copy What's Missing");
			ctx.runOnClient(mc -> {
				String clipboard = mc.keyboardHandler.getClipboard();
				if (!clipboard.contains("8 Stone") || clipboard.contains("Glass")) {
					throw new AssertionError("The clipboard should list only 8 Stone, but has: " + clipboard);
				}
			});
			ctx.clickScreenButton("Save .txt File");
			ctx.waitTicks(2);
			ctx.runOnClient(mc -> {
				Path file = ListShare.exportsDir().resolve("Watchtower.txt");
				String written = read(file);
				if (!written.contains(ListShare.PREFIX) || !written.contains("200 stone") || !written.contains("20 glass (done)")) {
					throw new AssertionError("The exported file should hold the list and its share code, but has: " + written);
				}
			});

			// The code goes back in through the same import box, and brings the name, the swap and the crossed-off mark.
			ctx.clickScreenButton("Copy Share Code");
			String code = ctx.computeOnClient(mc -> mc.keyboardHandler.getClipboard());
			if (!code.startsWith(ListShare.PREFIX) || code.contains("\n")) {
				throw new AssertionError("A share code should be one line starting with " + ListShare.PREFIX + ", but is: " + code);
			}
			ctx.clickScreenButton("Done");
			ctx.waitForScreen(MaterialsScreen.class);
			ctx.runOnClient(mc -> {
				Project project = ProjectStore.project();
				project.materials.clear();
				project.replacements.clear();
				project.listName = null;
				ProjectStore.changed();
			});
			ctx.clickScreenButton("Show Import");
			ctx.waitTicks(2);
			ctx.runOnClient(mc -> {
				for (var child : mc.gui.screen().children()) {
					if (child instanceof MultiLineEditBox box) {
						box.setValue(code, true);
					}
				}
			});
			ctx.clickScreenButton("Replace");
			ctx.waitTicks(2);
			ctx.runOnClient(mc -> {
				Project project = ProjectStore.project();
				List<Project.MaterialEntry> materials = project.materials;
				if (materials.size() != 2 || !materials.get(0).item.equals("minecraft:stone") || materials.get(0).count != 200
					|| !materials.get(1).item.equals("minecraft:glass") || materials.get(1).count != 20) {
					throw new AssertionError("The share code should bring back 200 stone and 20 glass, but gave " + materials.size() + " materials");
				}
				if (!materials.get(1).crossedOff || !"Watchtower".equals(project.listName)
					|| !"minecraft:oak_log".equals(project.replacementFor("minecraft:oak_planks"))) {
					throw new AssertionError("The share code should bring back the crossed-off glass, the name and the swap");
				}
			});
			ctx.takeScreenshot("17a-share-code-imported");
			// Back to how the rest of the run expects the list: unnamed, with nothing crossed off, and the panel hidden.
			ctx.clickScreenButton("Hide Import");
			ctx.waitTicks(2);
			ctx.runOnClient(mc -> {
				Project project = ProjectStore.project();
				project.materials.get(1).crossedOff = false;
				project.replacements.clear();
				project.listName = null;
				ProjectStore.changed();
			});
			// Right-clicking a material only asks to remove it. Esc keeps it; the X confirms.
			int[] list = ctx.computeOnClient(mc -> {
				EditBox search = null;
				Button clear = null;
				for (var child : mc.gui.screen().children()) {
					if (child instanceof EditBox box) {
						search = box;
					}
					if (child instanceof Button button && button.getMessage().getString().equals("Clear")) {
						clear = button;
					}
				}
				// {left, top, right} of the material rows: the search box starts them, Clear ends them.
				return new int[] {search.getX(), search.getY() + 37, clear.getX() + clear.getWidth()};
			});
			int glassRowY = list[1] + 18 + 9;
			// The checkbox at the start of a row crosses the material off without removing it, and back.
			clickAt(ctx, list[0] + 4, glassRowY, InputConstants.MOUSE_BUTTON_LEFT);
			ctx.runOnClient(mc -> {
				Project.MaterialEntry glass = ProjectStore.project().materials.get(1);
				if (!glass.item.equals("minecraft:glass") || !glass.crossedOff || ProjectStore.layout().needed(Items.GLASS) != 0
					|| !(mc.gui.screen() instanceof MaterialsScreen)) {
					throw new AssertionError("Clicking the checkbox should cross Glass off and stay on the list");
				}
			});
			expectMaterials(ctx, 2);
			ctx.takeScreenshot("17b-crossed-off");
			clickAt(ctx, list[0] + 4, glassRowY, InputConstants.MOUSE_BUTTON_LEFT);
			ctx.runOnClient(mc -> {
				if (ProjectStore.project().materials.get(1).crossedOff || ProjectStore.layout().needed(Items.GLASS) == 0) {
					throw new AssertionError("Clicking the checkbox again should make Glass needed again");
				}
			});
			clickAt(ctx, list[0] + 60, glassRowY, InputConstants.MOUSE_BUTTON_RIGHT);
			expectMaterials(ctx, 2);
			ctx.getInput().pressKey(ESCAPE);
			ctx.waitTicks(2);
			ctx.runOnClient(mc -> {
				if (!(mc.gui.screen() instanceof MaterialsScreen)) {
					throw new AssertionError("Esc should only cancel the removal, not close the Materials List");
				}
			});
			expectMaterials(ctx, 2);
			clickAt(ctx, list[0] + 60, glassRowY, InputConstants.MOUSE_BUTTON_RIGHT);
			ctx.takeScreenshot("17a-confirm-remove");
			clickAt(ctx, list[2] - 7, glassRowY - 1, InputConstants.MOUSE_BUTTON_LEFT);
			expectMaterials(ctx, 1);
			ctx.runOnClient(mc -> {
				if (ProjectStore.project().materials.stream().anyMatch(m -> m.item.equals("minecraft:glass"))) {
					throw new AssertionError("Clicking the X should have removed Glass");
				}
			});

			ctx.clickScreenButton("Sort: List");
			ctx.waitTicks(2);
			ctx.takeScreenshot("17-sorted-by-missing");
			ctx.setScreen(() -> null);
			ctx.waitTicks(2);

			// Items put in a box while you weren't looking (someone else, or you on another computer) can't be seen until
			// it's opened. The HUD says so, and with auto-refresh a box in reach is read in the background, with no screen.
			sp.getServer().runOnServer(server -> chestAt(server, depot).setItem(26, new ItemStack(Items.STONE, 4)));
			ctx.runOnClient(mc -> {
				ModConfig.get().refreshBoxes = false;
				// As if you'd just joined.
				BoxRefresher.reset();
			});
			ctx.waitTicks(15);
			ctx.runOnClient(mc -> {
				if (BoxRefresher.uncheckedCount() != 1 || ProjectStore.layout().stored(Items.STONE) != 192) {
					throw new AssertionError("With auto-refresh off, the box should wait to be opened");
				}
			});
			ctx.takeScreenshot("18a-hud-box-not-checked");
			ctx.runOnClient(mc -> ModConfig.get().refreshBoxes = true);
			ctx.waitFor(mc -> ProjectStore.layout().stored(Items.STONE) == 196);
			ctx.waitTicks(2);
			ctx.runOnClient(mc -> {
				if (mc.gui.screen() != null || mc.player.containerMenu != mc.player.inventoryMenu || BoxRefresher.uncheckedCount() != 0) {
					throw new AssertionError("The background refresh should read the box and close it without showing a screen");
				}
			});
			// Someone else opening the box makes it worth another look once they've closed it.
			ctx.waitTicks(45);
			sp.getServer().runOnServer(server -> {
				chestAt(server, depot).setItem(25, new ItemStack(Items.STONE, 2));
				server.overworld().blockEvent(depot, Blocks.CHEST, 1, 1);
			});
			ctx.waitTicks(10);
			sp.getServer().runOnServer(server -> server.overworld().blockEvent(depot, Blocks.CHEST, 1, 0));
			ctx.waitFor(mc -> ProjectStore.layout().stored(Items.STONE) == 198);
			ctx.takeScreenshot("18b-hud-after-refresh");

			// The settings screen (also reachable from Mod Menu and the Materials List).
			ctx.setScreen(() -> new SettingsScreen(null));
			ctx.waitTicks(2);
			ctx.takeScreenshot("18-settings");
			ctx.clickScreenButton("Show HUD: On");
			ctx.runOnClient(mc -> {
				if (ModConfig.get().hudEnabled) {
					throw new AssertionError("The settings toggle should turn the HUD off");
				}
			});
			ctx.clickScreenButton("Show HUD: Off");
			ctx.clickScreenButton("Size: Full");
			ctx.runOnClient(mc -> {
				if (!ModConfig.get().hudCompact) {
					throw new AssertionError("The size button should switch the HUD to compact");
				}
			});
			ctx.setScreen(() -> null);
			ctx.waitTicks(2);
			ctx.takeScreenshot("18c-compact-hud");
			ctx.runOnClient(mc -> ModConfig.get().hudCompact = false);

			// The narrowest GUI Minecraft allows (320x240), and a wide one (1920x1080 at GUI scale 2, a 960x540 GUI).
			checkScreensAt(ctx, depot, 640, 480, 0, "19-narrow");
			checkScreensAt(ctx, depot, 1920, 1080, 2, "20-wide");
			ctx.getInput().resizeWindow(854, 480);
			ctx.runOnClient(mc -> {
				mc.options.guiScale().set(0);
				mc.resizeGui();
			});
			ctx.waitTicks(2);
		}
	}

	private static void checkScreensAt(ClientGameTestContext ctx, BlockPos box, int width, int height, int guiScale, String name) {
		ctx.getInput().resizeWindow(width, height);
		ctx.runOnClient(mc -> {
			mc.options.guiScale().set(guiScale);
			mc.resizeGui();
		});
		ctx.waitTicks(3);
		ctx.setScreen(MaterialsScreen::new);
		ctx.waitTicks(3);
		ctx.takeScreenshot(TestScreenshotOptions.of(name + "-materials-list").withSize(width, height));
		ctx.clickScreenButton("Show Import");
		ctx.waitTicks(2);
		ctx.takeScreenshot(TestScreenshotOptions.of(name + "-import-panel").withSize(width, height));
		ctx.clickScreenButton("Hide Import");
		ctx.setScreen(() -> null);
		ctx.waitTicks(2);

		openChest(ctx, box);
		ctx.takeScreenshot(TestScreenshotOptions.of(name + "-material-box").withSize(width, height));
		ctx.runOnClient(mc -> {
			// The Material Box buttons must sit beside the container, never on top of it.
			AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) mc.gui.screen();
			AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) screen;
			int left = acc.materialsgui$getLeftPos();
			int right = left + acc.materialsgui$getImageWidth();
			for (var child : screen.children()) {
				if (child instanceof Button button && isOurButton(button.getMessage().getString())
					&& button.getX() < right && button.getX() + button.getWidth() > left) {
					throw new AssertionError("\"" + button.getMessage().getString() + "\" overlaps the container at a " + width + "x" + height + " window");
				}
			}
		});
		ctx.getInput().pressKey(ESCAPE);
		ctx.waitTicks(2);

		ctx.setScreen(() -> new SettingsScreen(null));
		ctx.waitTicks(2);
		ctx.takeScreenshot(TestScreenshotOptions.of(name + "-settings").withSize(width, height));
		ctx.setScreen(() -> new ListsScreen(new MaterialsScreen()));
		ctx.waitTicks(2);
		ctx.takeScreenshot(TestScreenshotOptions.of(name + "-saved-lists").withSize(width, height));
		ctx.setScreen(() -> new BoxesScreen(null));
		ctx.waitTicks(2);
		ctx.takeScreenshot(TestScreenshotOptions.of(name + "-boxes").withSize(width, height));
		ctx.setScreen(() -> ShareScreen.of(new MaterialsScreen(), ProjectStore.project()));
		ctx.waitTicks(2);
		ctx.takeScreenshot(TestScreenshotOptions.of(name + "-share").withSize(width, height));
		ctx.setScreen(() -> null);
		ctx.waitTicks(2);
	}

	private static boolean isOurButton(String label) {
		return label.startsWith("+ Material Box") || label.startsWith("Remove Box") || label.equals("Materials List...") || label.equals("Deposit All")
			|| label.endsWith(" Highlights");
	}

	/** The run's config folder persists between runs, so an export from a previous run is cleared first. */
	private static void deleteExports() {
		Path dir = ListShare.exportsDir();
		if (!Files.isDirectory(dir)) {
			return;
		}
		try (Stream<Path> files = Files.walk(dir)) {
			for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(file);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static String read(Path file) {
		try {
			return Files.readString(file);
		} catch (IOException e) {
			throw new AssertionError("Expected an exported list at " + file, e);
		}
	}

	/** Clicks at a GUI position with a real mouse button. */
	private static void clickAt(ClientGameTestContext ctx, int guiX, int guiY, int button) {
		double scale = ctx.computeOnClient(mc -> (double) mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth());
		// Minecraft ignores the first cursor move after a screen opens, so nudge it first.
		ctx.getInput().setCursorPos(guiX * scale + 1, guiY * scale + 1);
		ctx.waitTick();
		ctx.getInput().setCursorPos(guiX * scale, guiY * scale);
		ctx.waitTick();
		ctx.getInput().pressMouse(button);
		ctx.waitTicks(2);
	}

	private static void expectMaterials(ClientGameTestContext ctx, int count) {
		ctx.runOnClient(mc -> {
			int size = ProjectStore.project().materials.size();
			if (size != count) {
				throw new AssertionError("Expected " + count + " materials on the list, found " + size);
			}
		});
	}

	private static int inventoryCount(TestSingleplayerContext sp, Item item) {
		return sp.getServer().computeOnServer(server -> {
			Inventory inventory = server.getPlayerList().getPlayers().getFirst().getInventory();
			int total = 0;
			for (int i = 0; i < inventory.getContainerSize(); i++) {
				if (inventory.getItem(i).is(item)) {
					total += inventory.getItem(i).getCount();
				}
			}
			return total;
		});
	}

	private static void fillShulker(MinecraftServer server, BlockPos pos) {
		ShulkerBoxBlockEntity box = (ShulkerBoxBlockEntity) server.overworld().getBlockEntity(pos);
		box.setItem(0, new ItemStack(Items.STONE, 64));
		box.setItem(5, new ItemStack(Items.GLASS, 3));
	}

	private static void hoverSlotWith(ClientGameTestContext ctx, Item item) {
		int index = ctx.computeOnClient(mc -> {
			for (Slot slot : ((AbstractContainerScreen<?>) mc.gui.screen()).getMenu().slots) {
				if (slot.getItem().is(item)) {
					return slot.index;
				}
			}
			throw new AssertionError("No slot holds " + item);
		});
		hoverChestSlot(ctx, index);
	}

	private static ChestBlockEntity chestAt(MinecraftServer server, BlockPos pos) {
		return (ChestBlockEntity) server.overworld().getBlockEntity(pos);
	}

	private static List<ItemStack> contents(TestSingleplayerContext sp, BlockPos chest) {
		return sp.getServer().computeOnServer(server -> {
			ChestBlockEntity be = chestAt(server, chest);
			List<ItemStack> list = new ArrayList<>();
			for (int i = 0; i < be.getContainerSize(); i++) {
				list.add(be.getItem(i).copy());
			}
			return list;
		});
	}

	private static BoxKey onlyBox() {
		List<Project.BoxEntry> boxes = ProjectStore.project().boxes;
		if (boxes.size() != 1) {
			throw new AssertionError("Expected exactly one Material Box, found " + boxes.size());
		}
		return boxes.getFirst().key();
	}

	private static void expectOnlyBox(BlockPos pos) {
		BoxKey key = onlyBox();
		if (!key.equals(BoxKey.of("minecraft:overworld", pos))) {
			throw new AssertionError("Expected the Material Box at " + pos + " but it's at " + key.describe());
		}
	}

	private static void openChest(ClientGameTestContext ctx, BlockPos chest) {
		openContainer(ctx, chest, ContainerScreen.class);
	}

	private static void openContainer(ClientGameTestContext ctx, BlockPos pos, Class<? extends Screen> screen) {
		ctx.getInput().lookAt(pos);
		ctx.waitTicks(2);
		ctx.getInput().pressKey(options -> options.keyUse);
		ctx.waitForScreen(screen);
		ctx.waitTicks(2);
	}

	private static void click(Minecraft mc, int slot) {
		mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, slot, 0, ContainerInput.PICKUP, mc.player);
	}

	private static void hoverChestSlot(ClientGameTestContext ctx, int index) {
		double[] cursor = ctx.computeOnClient(mc -> {
			AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) mc.gui.screen();
			AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) screen;
			double scale = (double) mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth();
			Slot slot = screen.getMenu().getSlot(index);
			return new double[] {
				(acc.materialsgui$getLeftPos() + slot.x + 8) * scale,
				(acc.materialsgui$getTopPos() + slot.y + 8) * scale
			};
		});
		// Minecraft ignores the first cursor move after a screen releases the mouse, so nudge it first.
		ctx.getInput().setCursorPos(cursor[0] + 1, cursor[1] + 1);
		ctx.waitTick();
		ctx.getInput().setCursorPos(cursor[0], cursor[1]);
		ctx.waitTicks(3);
	}

	private static void shiftClick(ClientGameTestContext ctx, Item item, int count) {
		double[] cursor = ctx.computeOnClient(mc -> {
			AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) mc.gui.screen();
			AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) screen;
			double scale = (double) mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth();
			for (Slot slot : screen.getMenu().slots) {
				if (slot.container instanceof Inventory && slot.getItem().is(item) && slot.getItem().getCount() == count) {
					return new double[] {
						(acc.materialsgui$getLeftPos() + slot.x + 8) * scale,
						(acc.materialsgui$getTopPos() + slot.y + 8) * scale
					};
				}
			}
			throw new AssertionError("No inventory slot with " + count + " " + item);
		});
		ctx.getInput().setCursorPos(cursor[0], cursor[1]);
		ctx.waitTicks(2);
		// TestInput always sends clicks with modifiers = 0, so it can't shift-click. Feed the real mouse handler a
		// left click with the shift modifier instead; this goes through the same path as a physical click.
		ctx.runOnClient(mc -> {
			MouseHandlerAccessor mouse = (MouseHandlerAccessor) mc.mouseHandler;
			long window = mc.getWindow().handle();
			mouse.invokeOnButton(window, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, InputConstants.MOD_SHIFT), MOUSE_PRESS);
			mouse.invokeOnButton(window, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, InputConstants.MOD_SHIFT), MOUSE_RELEASE);
		});
		ctx.waitTicks(2);
	}

	private static void expectPlan(Layout layout, BoxKey key, int slot, Item item, int target) {
		Layout.SlotPlan plan = layout.plan(key, slot);
		if (plan == null || plan.item() != item || plan.target() != target) {
			throw new AssertionError("Slot " + slot + ": expected " + target + " " + item + " but the plan is " + plan);
		}
	}

	private static void expectNoPlan(Layout layout, BoxKey key, int slot) {
		if (layout.plan(key, slot) != null) {
			throw new AssertionError("Slot " + slot + " should have no highlight but has " + layout.plan(key, slot));
		}
	}

	private static void expect(List<ItemStack> contents, int slot, Item item, int count) {
		ItemStack stack = contents.get(slot);
		if (!stack.is(item) || stack.getCount() != count) {
			throw new AssertionError("Slot " + slot + ": expected " + count + " " + item + " but found " + stack);
		}
	}
}
