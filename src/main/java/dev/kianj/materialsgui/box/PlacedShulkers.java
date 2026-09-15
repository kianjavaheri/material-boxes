package dev.kianj.materialsgui.box;

import dev.kianj.materialsgui.data.BoxKey;
import dev.kianj.materialsgui.data.Project;
import dev.kianj.materialsgui.data.ProjectStore;
import dev.kianj.materialsgui.data.SavedLists;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * Reconnects picked-up shulker Material Boxes. A shulker box the player places is recognized as soon as its block
 * appears, without being opened: the item's contents are in its data, so it can be matched the same way as when it's
 * opened, by its colour and exact contents. The same box is reconnected in every saved list in this world that uses
 * it, not just the current one.
 */
public final class PlacedShulkers {
	/** Shulker boxes hold 27 items. */
	private static final int SIZE = 27;
	/** How long after the click the block may take to appear. Also covers the box only just being marked picked up. */
	private static final long PLACE_WINDOW_MS = 5000;

	private record Placement(BoxKey key, String block, ContainerHooks.Contents contents, long time) {}

	private static final List<Placement> pending = new ArrayList<>();

	private PlacedShulkers() {}

	/** Forgets everything; called when joining a world or server. */
	public static void reset() {
		pending.clear();
	}

	/** Notes a shulker box the player is about to place, with where it goes and what's in it. */
	public static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		ItemStack stack = player.getItemInHand(hand);
		if (level.isClientSide() && !BoxRefresher.isUsingBlock() && stack.getItem() instanceof BlockItem item
			&& item.getBlock() instanceof ShulkerBoxBlock) {
			BlockPos pos = new BlockPlaceContext(player, hand, stack, hit).getClickedPos();
			pending.add(new Placement(BoxKey.of(level.dimension().identifier().toString(), pos),
				BuiltInRegistries.BLOCK.getKey(item.getBlock()).toString(), ContainerHooks.contentsOf(stack, SIZE), System.currentTimeMillis()));
		}
		return InteractionResult.PASS;
	}

	public static void tick(Minecraft mc) {
		if (pending.isEmpty()) {
			return;
		}
		ClientLevel level = mc.level;
		long now = System.currentTimeMillis();
		pending.removeIf(p -> level == null || now - p.time() > PLACE_WINDOW_MS);
		if (level == null) {
			return;
		}
		String dimension = level.dimension().identifier().toString();
		// Kept until it matches: the box it was may only be marked picked up after it's placed (broken and placed quickly).
		pending.removeIf(p -> p.key().dimension().equals(dimension)
			&& p.block().equals(BoxTracker.blockId(level, new BlockPos(p.key().x(), p.key().y(), p.key().z())))
			&& reattach(p.key(), p.block(), p.contents()));
	}

	/** True if any list in this world has a picked-up box that could be reconnected. */
	static boolean anyPickedUp() {
		if (ProjectStore.project().boxes.stream().anyMatch(b -> b.pickedUp)) {
			return true;
		}
		for (SavedLists.SavedList list : SavedLists.all()) {
			List<Project.BoxEntry> boxes = list.boxes.get(ProjectStore.worldKey());
			if (boxes != null && boxes.stream().anyMatch(b -> b.pickedUp)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Reconnects the picked-up shulker Material Box with this block and these contents, now placed at {@code key}, in the
	 * current list and every saved list in this world. Returns true if anything was reconnected.
	 */
	static boolean reattach(BoxKey key, String block, ContainerHooks.Contents contents) {
		Project project = ProjectStore.project();
		int index = project.indexOfBox(key) >= 0 ? -1 : Project.findPickedUp(project.boxes, block, contents.items(), contents.counts(), null);
		// Where the current list last saw it, so other lists' copies of the same box match even if their contents are older.
		@Nullable BoxKey lastSeen = index < 0 ? null : project.boxes.get(index).key();
		if (index >= 0) {
			project.boxes.get(index).placeAt(key);
		}

		boolean listsChanged = false;
		for (SavedLists.SavedList list : SavedLists.all()) {
			List<Project.BoxEntry> boxes = list.boxes.get(ProjectStore.worldKey());
			// The current list's boxes are the project's, synced below.
			if (boxes == null || list.name.equalsIgnoreCase(project.listName) || Project.indexOfBox(boxes, key) >= 0) {
				continue;
			}
			int i = Project.findPickedUp(boxes, block, contents.items(), contents.counts(), lastSeen);
			if (i >= 0) {
				Project.BoxEntry box = boxes.get(i);
				box.placeAt(key);
				box.setContents(contents.items().clone(), contents.counts().clone(), new LinkedHashMap<>(contents.nested()));
				listsChanged = true;
			}
		}

		if (listsChanged) {
			SavedLists.write();
		}
		if (index >= 0) {
			ProjectStore.changed();
			// Its contents are known exactly, so there's no need to open it in the background.
			BoxRefresher.markSeen(key);
		}
		return index >= 0 || listsChanged;
	}
}
